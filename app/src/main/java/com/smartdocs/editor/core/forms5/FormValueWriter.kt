package com.smartdocs.editor.core.forms5

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smartdocs.editor.core.pdf.FormOps
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes the in-page edits into a PDF: field values through [FormOps.fillFields] (Arabic font + shaped
 * appearances, formatted appearances with raw /V) and picked pictures into image buttons
 * (new /AP /N plus the /MK /I icon, as Acrobat's buttonImportIcon does).
 */
object FormValueWriter {

    suspend fun write(
        context: Context, src: File, values: Map<String, String>, display: Map<String, String>, images: Map<String, File>,
        out: File, password: String?, dropXfa: Boolean,
    ) = withContext(Dispatchers.IO) {
        val mid = if (images.isEmpty()) out else File(out.parentFile, ".${out.name}.v.pdf")
        // rotated widgets first get an appearance that matches /MK /R, so the new value is drawn in the right direction
        val names = values.keys + display.keys
        val rot = File(out.parentFile, ".${out.name}.r.pdf")
        val source = if (names.isNotEmpty() && runCatching { AppearanceRotation.prepare(src, password, names, rot) }.getOrDefault(false)) rot else src
        try {
            if (values.isNotEmpty() || display.isNotEmpty() || dropXfa) FormOps.fillFields(source, values, mid, password, context, display, dropXfa)
            else source.copyTo(mid, overwrite = true)
        } finally { rot.delete() }
        if (images.isEmpty()) return@withContext
        try {
            PdfOps.load(mid, password).use { doc ->
                images.forEach { (name, file) -> runCatching { setImage(doc, name, file) } }
                doc.save(out)
            }
        } finally { mid.delete() }
    }

    /**
     * Fill-time save of a digitally signed PDF (audit V-01): every step of [write] plus the Hide-action flags
     * ([hidden]) and the barcode refresh runs on one document loaded with its original security, and the result
     * is appended as an incremental update ([SignedSave.saveIncremental]), so the signed bytes stay a prefix and
     * the signatures stay valid. A certification that allows no changes (DocMDP P=1, or a FieldMDP /P 1 lock)
     * throws [com.smartdocs.editor.core.pdf.SignedDocumentException] (the reader then offers "save a copy").
     * Fields locked by a signature are read-only and are skipped like any read-only field. The XFA packet is kept.
     */
    suspend fun writeSigned(
        context: Context?, src: File, values: Map<String, String>, display: Map<String, String>, images: Map<String, File>,
        hidden: Map<String, Boolean>, out: File, password: String?,
    ) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, password).use { doc ->
            com.smartdocs.editor.core.pdf.SignedSave.requireFormFillAllowed(doc)
            val names = values.keys + display.keys
            if (names.isNotEmpty()) runCatching { AppearanceRotation.fixIn(doc, names) }
            if (values.isNotEmpty() || display.isNotEmpty()) FormOps.fillIn(doc, values, context, display, dropXfa = false)
            images.forEach { (name, file) -> runCatching { setImage(doc, name, file) } }
            FieldVisibility.apply(doc, hidden)
            val barcodes = runCatching { com.smartdocs.editor.core.formsx.FxBarcodes.refreshAll(doc) }.getOrDefault(0)
            val acro = doc.documentCatalog.getAcroForm(null) ?: throw IllegalStateException("no form")
            val touched = (names + images.keys + hidden.keys).toMutableSet()
            if (barcodes > 0) acro.fieldTree.forEach { f -> if (f.cosObject.containsKey(COSName.getPDFName("SDBarcode"))) touched += f.fullyQualifiedName }
            val pages = SignedFormMarks.mark(doc, acro, touched)
            com.smartdocs.editor.core.pdf.SignedSave.saveIncremental(doc, pages, out)
        }
    }

    private fun decode(file: File, maxSide: Int = 1600): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var s = 1; while (o.outWidth / s > maxSide || o.outHeight / s > maxSide) s *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun setImage(doc: PDDocument, name: String, file: File) {
        val f = doc.documentCatalog.acroForm?.getField(name) as? PDPushButton ?: return
        val bmp = decode(file) ?: return
        val img = if (bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, 0.88f)
        for (w in f.widgets) {
            val r = w.rectangle ?: continue
            val ap = PDAppearanceStream(doc)
            // /MK /R: the picture is drawn upright in the turned box (bbox sides swapped, rotation matrix)
            val rot = AppearanceRotation.rotationOf(w.cosObject)
            val quarter = rot % 180 == 90
            val bw = if (quarter) r.height else r.width; val bh = if (quarter) r.width else r.height
            ap.bBox = PDRectangle(0f, 0f, bw, bh)
            if (rot != 0) ap.setMatrix(AppearanceRotation.matrix(rot, bw, bh).createAffineTransform())
            ap.resources = PDResources()
            PDPageContentStream(doc, ap).use { cs ->
                // fit inside the box keeping the aspect ratio, centred
                val k = minOf(bw / img.width, bh / img.height)
                val w2 = img.width * k; val h2 = img.height * k
                cs.drawImage(img, (bw - w2) / 2f, (bh - h2) / 2f, w2, h2)
            }
            w.appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }
            val mk = (w.cosObject.getDictionaryObject(COSName.getPDFName("MK")) as? COSDictionary) ?: COSDictionary().also { w.cosObject.setItem(COSName.getPDFName("MK"), it) }
            mk.setItem(COSName.getPDFName("I"), ap.cosObject)
            // TP 1 = icon only
            mk.setInt(COSName.getPDFName("TP"), 1)
        }
    }
}

/**
 * Appearance rotation of widgets (`/MK /R`). PdfBox regenerates a field appearance inside the existing
 * stream's BBox and Matrix, so a stream that does not match /MK /R would show the new value in the wrong
 * direction: such streams are dropped before filling, and a widget whose stream is turned by its Matrix
 * without /MK /R gets the matching /MK /R.
 */
object AppearanceRotation {
    private val MK = COSName.getPDFName("MK")
    private val R = COSName.getPDFName("R")
    private val MATRIX = COSName.getPDFName("Matrix")

    private fun normalStream(w: COSDictionary): COSStream? {
        val ap = w.getDictionaryObject(COSName.AP) as? COSDictionary ?: return null
        return when (val n = ap.getDictionaryObject(COSName.N)) {
            is COSStream -> n
            is COSDictionary -> n.getDictionaryObject(w.getCOSName(COSName.AS) ?: COSName.Off) as? COSStream
                ?: n.keySet().firstOrNull { it.name != "Off" }?.let { n.getDictionaryObject(it) as? COSStream }
            else -> null
        }
    }

    private fun floats(base: Any?): List<Float> = (base as? COSArray)?.let { a -> (0 until a.size()).mapNotNull { (a.getObject(it) as? COSNumber)?.floatValue() } }.orEmpty()

    /** Counter-clockwise rotation of a widget: /MK /R, else the quarter turn of its appearance /Matrix. */
    fun rotationOf(w: COSDictionary): Int {
        val mk = w.getDictionaryObject(MK) as? COSDictionary
        if (mk != null && mk.containsKey(R)) return FieldRotation.norm(mk.getInt(R, 0))
        val m = floats(normalStream(w)?.getDictionaryObject(MATRIX))
        return if (m.size >= 4) FieldRotation.angleOf(m[0], m[1]) ?: 0 else 0
    }

    /** Appearance matrix of a [rot]-turned stream whose BBox is (0, 0, [bw], [bh]) (PdfBox's own convention). */
    fun matrix(rot: Int, bw: Float, bh: Float): Matrix = when (FieldRotation.norm(rot)) {
        90 -> Matrix(0f, 1f, -1f, 0f, bh, 0f)
        180 -> Matrix(-1f, 0f, 0f, -1f, bw, bh)
        270 -> Matrix(0f, -1f, 1f, 0f, 0f, bw)
        else -> Matrix()
    }

    /**
     * Fix the widgets of [names] in [src]; writes [out] and returns true only when something changed.
     */
    fun prepare(src: File, password: String?, names: Set<String>, out: File): Boolean = PdfOps.load(src, password).use { doc ->
        val changed = fixIn(doc, names)
        if (changed) doc.save(out)
        changed
    }

    /** [prepare] on an open document, without saving. */
    fun fixIn(doc: PDDocument, names: Set<String>): Boolean {
        val acro = doc.documentCatalog.acroForm ?: return false
        var changed = false
        for (name in names) {
            val f = acro.getField(name) as? PDTerminalField ?: continue
            if (f is PDPushButton) continue
            for (w in f.widgets) {
                val d = w.cosObject
                val mk = d.getDictionaryObject(MK) as? COSDictionary
                val rot = rotationOf(d)
                if ((mk == null || !mk.containsKey(R)) && rot != 0) {
                    (mk ?: COSDictionary().also { d.setItem(MK, it) }).setInt(R, rot); changed = true
                }
                if (rot == 0) continue
                val s = normalStream(d) ?: continue
                val r = w.rectangle ?: continue
                val bb = floats(s.getDictionaryObject(COSName.BBOX))
                val m = floats(s.getDictionaryObject(MATRIX)).takeIf { it.size >= 4 } ?: listOf(1f, 0f, 0f, 1f)
                val bw = if (bb.size >= 4) kotlin.math.abs(bb[2] - bb[0]) else r.width
                val bh = if (bb.size >= 4) kotlin.math.abs(bb[3] - bb[1]) else r.height
                if (FieldRotation.appearanceMismatch(rot, r.width, r.height, bw, bh, m[0], m[1])) {
                    (d.getDictionaryObject(COSName.AP) as? COSDictionary)?.let { ap -> ap.removeItem(COSName.N); ap.removeItem(COSName.D) }
                    changed = true
                }
            }
        }
        return changed
    }
}
