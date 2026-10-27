package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SignedDocumentException
import com.smartdocs.editor.core.pdf.SignedSave
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Signature form fields (Nitro "signature field actions"): list the /Sig fields of a document, sign into an
 * existing empty field or into a new field placed anywhere, lock form fields after signing (FieldMDP, /Lock),
 * and clear a signed field. The visible appearance always lives in the field's widget (/AP), so a cleared
 * field shows nothing and the page content is never touched.
 */
object SigFields {

    /** [rect] is the widget rectangle in default user space (x, y, w, h); null for an invisible field. */
    data class Field(
        val name: String,
        val page: Int,
        val rect: FloatArray?,
        val signed: Boolean,
        val signer: String?,
        /** Lock the author set on the field (/Lock): fields that become read-only once it is signed. */
        val lock: Lock?,
    )

    enum class LockAction { ALL, INCLUDE, EXCLUDE }

    /**
     * FieldMDP lock written with an approval signature: [fields] (full names) are locked with INCLUDE, all others
     * with EXCLUDE; [docPermission] 1..3 also restricts later changes of the whole document like DocMDP (PDF 2.0).
     */
    data class Lock(val action: LockAction, val fields: List<String> = emptyList(), val docPermission: Int? = null) {
        fun locks(name: String): Boolean = when (action) {
            LockAction.ALL -> true
            LockAction.INCLUDE -> name in fields
            LockAction.EXCLUDE -> name !in fields
        }
    }

    // ---------------------------------------------------------------- listing

    fun list(file: File, password: String? = null): List<Field> = PdfOps.loadRaw(file, password).use { list(it) }

    fun list(doc: PDDocument): List<Field> {
        val form = doc.documentCatalog.acroForm ?: return emptyList()
        return form.fieldTree.filterIsInstance<PDSignatureField>().map { f ->
            val w = f.widgets.firstOrNull()
            val r = w?.rectangle?.takeIf { it.width > 0f && it.height > 0f }
            val sig = f.signature
            Field(
                name = f.fullyQualifiedName, page = w?.let { pageOf(doc, it) } ?: -1,
                rect = r?.let { floatArrayOf(it.lowerLeftX, it.lowerLeftY, it.width, it.height) },
                signed = sig != null, signer = sig?.name, lock = readLock(f.cosObject.getDictionaryObject(LOCK) as? COSDictionary),
            )
        }
    }

    /** Fully qualified name of the field whose value is [sig], or null (signature not attached to a field). */
    fun fieldOf(doc: PDDocument, sig: PDSignature): String? =
        doc.documentCatalog.acroForm?.fieldTree?.filterIsInstance<PDSignatureField>()?.firstOrNull { it.signature?.cosObject == sig.cosObject }?.fullyQualifiedName

    /** Lock stored on the signature field whose value is [sig] (approval signature with FieldMDP), if any. */
    fun lockOf(doc: PDDocument, sig: PDSignature): Lock? {
        val refs = sig.cosObject.getDictionaryObject(COSName.REFERENCE) as? COSArray ?: return null
        for (i in 0 until refs.size()) {
            val ref = refs.getObject(i) as? COSDictionary ?: continue
            if (ref.getCOSName(COSName.TRANSFORM_METHOD) != FIELD_MDP) continue
            return readLock(ref.getDictionaryObject(COSName.TRANSFORM_PARAMS) as? COSDictionary)
        }
        return null
    }

    internal fun readLock(d: COSDictionary?): Lock? {
        d ?: return null
        val action = when (d.getNameAsString(ACTION)) { "All" -> LockAction.ALL; "Include" -> LockAction.INCLUDE; "Exclude" -> LockAction.EXCLUDE; else -> return null }
        val fields = (d.getDictionaryObject(COSName.FIELDS) as? COSArray)?.let { a -> (0 until a.size()).mapNotNull { (a.getObject(it) as? COSString)?.string } }.orEmpty()
        val p = d.getInt(COSName.P, -1).takeIf { it in 1..3 }
        return Lock(action, fields, p)
    }

    private fun pageOf(doc: PDDocument, w: PDAnnotationWidget): Int {
        w.page?.let { p -> doc.pages.indexOf(p).takeIf { it >= 0 }?.let { return it } }
        doc.pages.forEachIndexed { i, p -> if (p.annotations.any { it.cosObject == w.cosObject }) return i }
        return -1
    }

    // ---------------------------------------------------------------- placement

    /**
     * Converts a box given as fractions (0..1, origin top-left) of the page as it is displayed (after /Rotate)
     * into a rectangle in default user space of the unrotated page ([box] = crop box). Returns x, y, w, h.
     */
    fun toUserSpace(fx: Float, fy: Float, fw: Float, fh: Float, box: PDRectangle, rotation: Int): FloatArray {
        val bw = box.width; val bh = box.height
        val rot = ((rotation % 360) + 360) % 360
        // displayed page size
        val dw = if (rot == 90 || rot == 270) bh else bw
        val dh = if (rot == 90 || rot == 270) bw else bh
        val l = fx * dw; val t = fy * dh; val r = (fx + fw) * dw; val b = (fy + fh) * dh
        // displayed (origin top-left, y down) → unrotated user space (origin bottom-left)
        fun map(x: Float, y: Float): Pair<Float, Float> = when (rot) {
            90 -> y to x
            180 -> (bw - x) to y
            270 -> (bw - y) to (bh - x)
            else -> x to (bh - y)
        }
        val (x1, y1) = map(l, t); val (x2, y2) = map(r, b)
        return floatArrayOf(box.lowerLeftX + minOf(x1, x2), box.lowerLeftY + minOf(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
    }

    /** Inverse of [toUserSpace]: user-space rectangle → displayed fractions (left, top, width, height). */
    fun toDisplayed(rect: FloatArray, box: PDRectangle, rotation: Int): FloatArray {
        val bw = box.width; val bh = box.height
        val rot = ((rotation % 360) + 360) % 360
        val dw = if (rot == 90 || rot == 270) bh else bw
        val dh = if (rot == 90 || rot == 270) bw else bh
        val x1 = rect[0] - box.lowerLeftX; val y1 = rect[1] - box.lowerLeftY; val x2 = x1 + rect[2]; val y2 = y1 + rect[3]
        fun map(x: Float, y: Float): Pair<Float, Float> = when (rot) {
            90 -> y to x
            180 -> (bw - x) to y
            270 -> (bh - y) to (bw - x)
            else -> x to (bh - y)
        }
        val (a, b) = map(x1, y1); val (c, d) = map(x2, y2)
        return floatArrayOf(minOf(a, c) / dw, minOf(b, d) / dh, kotlin.math.abs(c - a) / dw, kotlin.math.abs(d - b) / dh)
    }

    // ---------------------------------------------------------------- signing

    /** Where the signature goes: an existing empty field, or a new field on [page] at [rect] (null = invisible). */
    data class Target(val fieldName: String? = null, val page: Int = 0, val rect: FloatArray? = null)

    /** Result of [prepare]: the page index and widget rectangle a visual template must use (rect null = invisible). */
    data class Prepared(val page: Int, val rect: PDRectangle?, val rotation: Int, val lock: Lock?)

    /**
     * Attaches [sig] to its field before `PDDocument.addSignature` (which then finds the field by its value and
     * keeps it, instead of creating an invisible one): the existing empty field [Target.fieldName], or a new field.
     * Applies [lock] (or the field's own /Lock) as a FieldMDP reference and makes the locked fields read-only.
     */
    fun prepare(doc: PDDocument, sig: PDSignature, target: Target, lock: Lock?): Prepared {
        val catalog = doc.documentCatalog
        val form = catalog.acroForm ?: PDAcroForm(doc).also { catalog.acroForm = it }
        val field: PDSignatureField
        val pageIndex: Int
        if (target.fieldName != null) {
            field = form.getField(target.fieldName) as? PDSignatureField ?: throw IllegalArgumentException("no signature field ${target.fieldName}")
            if (field.signature != null) throw IllegalStateException("field ${target.fieldName} is already signed")
            val w = field.widgets.firstOrNull() ?: throw IllegalStateException("field ${target.fieldName} has no widget")
            pageIndex = pageOf(doc, w).takeIf { it >= 0 } ?: throw IllegalStateException("field ${target.fieldName} is not on a page")
            // an invisible field (empty /Rect) takes the box the user placed on its page
            val empty = w.rectangle?.let { it.width <= 0f || it.height <= 0f } ?: true
            if (empty && target.rect != null) { w.rectangle = PDRectangle(target.rect[0], target.rect[1], target.rect[2], target.rect[3]); w.cosObject.isNeedToBeUpdated = true }
        } else {
            pageIndex = target.page.coerceIn(0, doc.numberOfPages - 1)
            val page = doc.getPage(pageIndex)
            field = PDSignatureField(form)
            field.partialName = uniqueName(form)
            val w = field.widgets.first()
            w.rectangle = target.rect?.let { PDRectangle(it[0], it[1], it[2], it[3]) } ?: PDRectangle()
            w.page = page
            w.isPrinted = true
            page.annotations.add(w)
            (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.isNeedToBeUpdated = true
            form.fields.add(field)
        }
        field.value = sig
        val effective = lock ?: readLock(field.cosObject.getDictionaryObject(LOCK) as? COSDictionary)
        if (effective != null) applyLock(form, field, sig, effective)
        val w = field.widgets.first()
        val r = w.rectangle?.takeIf { it.width > 0f && it.height > 0f }
        return Prepared(pageIndex, r, doc.getPage(pageIndex).rotation, effective)
    }

    private fun uniqueName(form: PDAcroForm): String {
        val used = form.fieldTree.map { it.fullyQualifiedName }.toSet()
        var i = 1
        while ("Signature$i" in used) i++
        return "Signature$i"
    }

    private fun lockDict(lock: Lock, type: COSName): COSDictionary = COSDictionary().apply {
        setItem(COSName.TYPE, type)
        setName(ACTION, when (lock.action) { LockAction.ALL -> "All"; LockAction.INCLUDE -> "Include"; LockAction.EXCLUDE -> "Exclude" })
        if (lock.action != LockAction.ALL) setItem(COSName.FIELDS, COSArray().apply { lock.fields.forEach { add(COSString(it)) } })
        lock.docPermission?.let { setInt(COSName.P, it.coerceIn(1, 3)) }
        isNeedToBeUpdated = true
    }

    /** FieldMDP signature reference + field /Lock, and the read-only flag on every locked field (Acrobat does the same). */
    private fun applyLock(form: PDAcroForm, field: PDSignatureField, sig: PDSignature, lock: Lock) {
        val tp = lockDict(lock, COSName.TRANSFORM_PARAMS).apply { setName(COSName.V, "1.2") }
        val ref = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.SIG_REF)
            setItem(COSName.TRANSFORM_METHOD, FIELD_MDP)
            setItem(COSName.DIGEST_METHOD, COSName.getPDFName("SHA256"))
            setItem(COSName.TRANSFORM_PARAMS, tp)
            isNeedToBeUpdated = true
        }
        val refs = sig.cosObject.getDictionaryObject(COSName.REFERENCE) as? COSArray ?: COSArray().also { sig.cosObject.setItem(COSName.REFERENCE, it) }
        refs.add(ref); refs.isNeedToBeUpdated = true
        if (field.cosObject.getDictionaryObject(LOCK) == null) field.cosObject.setItem(LOCK, lockDict(lock, SIG_FIELD_LOCK))
        for (f in form.fieldTree) {
            if (f is PDNonTerminalField || f === field || f is PDSignatureField) continue
            if (!lock.locks(f.fullyQualifiedName)) continue
            f.isReadOnly = true
            markField(f)
        }
    }

    private fun markField(f: PDField) {
        var d: COSDictionary? = f.cosObject
        while (d != null) { d.isNeedToBeUpdated = true; d = d.getDictionaryObject(COSName.PARENT) as? COSDictionary }
    }

    /**
     * Template document for `SignatureOptions.setVisualSignature`: one page the size of the target page, with a
     * signature field whose widget has [rect] and an appearance drawn by [draw] in an upright box (w, h), rotated
     * with the page so it reads upright like the page content.
     */
    fun template(pageBox: PDRectangle, rect: PDRectangle, rotation: Int, draw: (PDDocument, PDPageContentStream, Float, Float) -> Unit): ByteArray =
        PDDocument().use { t ->
            val page = PDPage(pageBox); page.rotation = rotation; t.addPage(page)
            val form = PDAcroForm(t); t.documentCatalog.acroForm = form
            val field = PDSignatureField(form)
            val w = field.widgets.first()
            w.rectangle = rect; w.page = page
            val rot = ((rotation % 360) + 360) % 360
            val upW = if (rot == 90 || rot == 270) rect.height else rect.width
            val upH = if (rot == 90 || rot == 270) rect.width else rect.height
            val ap = PDAppearanceStream(t).apply {
                resources = PDResources()
                bBox = PDRectangle(upW, upH)
                matrixFor(rot)?.let { m -> cosObject.setItem(COSName.MATRIX, COSArray().apply { m.forEach { add(COSFloat(it)) } }) }
            }
            PDPageContentStream(t, ap).use { cs -> draw(t, cs, upW, upH) }
            w.appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }
            form.fields.add(field)
            page.annotations.add(w)
            ByteArrayOutputStream().also { t.save(it) }.toByteArray()
        }

    /** Form matrix that turns the upright appearance with the page (PDF rotates counter-clockwise for positive angles). */
    internal fun matrixFor(rot: Int): FloatArray? = when (rot) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, 0f, 0f)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, 0f, 0f)
        270 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, 0f)
        else -> null
    }

    // ---------------------------------------------------------------- clear

    /**
     * Nitro/Acrobat "Clear signature": removes the value of the signed field [name] and blanks its appearance, as an
     * incremental update so the other signatures keep their signed bytes. Refused when the document is certified
     * with "no changes" (or a signature locked it with P=1) by another signature.
     */
    fun clear(src: File, out: File, name: String, password: String? = null) {
        require(out.canonicalPath != src.canonicalPath) { "clear: out must differ from src" }
        PdfOps.loadRaw(src, password).use { d ->
            val form = d.documentCatalog.acroForm ?: throw IllegalArgumentException("no form")
            val f = form.getField(name) as? PDSignatureField ?: throw IllegalArgumentException("no signature field $name")
            val sig = f.signature ?: throw IllegalStateException("field $name is not signed")
            val others = d.signatureDictionaries.filter { it.cosObject != sig.cosObject }
            val noChanges = (SignedSave.certifyLevel(d) == 1 && d.documentCatalog.cosObject.let { c ->
                ((c.getDictionaryObject(COSName.PERMS) as? COSDictionary)?.getDictionaryObject(COSName.DOCMDP) as? COSDictionary) != sig.cosObject
            }) || others.any { lockOf(d, it)?.docPermission == 1 }
            if (noChanges) throw SignedDocumentException(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES, "another signature does not allow changes")
            f.cosObject.removeItem(COSName.V)
            f.cosObject.isNeedToBeUpdated = true
            for (w in f.widgets) {
                val r = w.rectangle ?: PDRectangle()
                w.appearance = PDAppearanceDictionary().apply { setNormalAppearance(PDAppearanceStream(d).apply { bBox = PDRectangle(r.width, r.height) }) }
                w.cosObject.isNeedToBeUpdated = true
            }
            form.cosObject.isNeedToBeUpdated = true
            (form.cosObject.getDictionaryObject(COSName.FIELDS) as? COSArray)?.isNeedToBeUpdated = true
            val pages = f.widgets.mapNotNull { w -> pageOf(d, w).takeIf { it >= 0 }?.let { d.getPage(it) } }
            SignedSave.saveIncremental(d, pages, out)
        }
    }

    private val LOCK = COSName.getPDFName("Lock")
    private val ACTION = COSName.getPDFName("Action")
    private val FIELD_MDP = COSName.getPDFName("FieldMDP")
    private val SIG_FIELD_LOCK = COSName.getPDFName("SigFieldLock")
}
