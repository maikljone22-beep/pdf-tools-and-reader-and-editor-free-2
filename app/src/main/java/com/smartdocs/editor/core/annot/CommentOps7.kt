package com.smartdocs.editor.core.annot

import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SignedSave
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place operations on stored annotations for section 7 (no appearance rewrite unless stated): properties
 * (author / subject / name / lock / note icon), per-annotation moves (align / distribute / same size), copy to
 * other pages or another document (paste, paste in place, stamp on a page range), rotation, pop-up reset and
 * open state, reply author / colour, flatten chosen annotations. Doc-level functions are pure PdfBox (unit
 * tested); the suspend wrappers load src, change and save to out (signed files incrementally, [SignedSave]).
 */
object CommentOps7 {

    private val POPUP = COSName.getPDFName("Popup")
    private val IRT = COSName.getPDFName("IRT")
    private val NM = COSName.getPDFName("NM")

    data class Props(val author: String? = null, val subject: String? = null, val name: String? = null, val contents: String? = null,
                     val locked: Boolean? = null, val icon: String? = null, val color: Int? = null)

    // ================= doc-level =================

    fun setProps(doc: PDDocument, page: Int, indices: Collection<Int>, p: Props): Int {
        val list = doc.getPage(page).annotations
        var n = 0
        indices.distinct().mapNotNull { list.getOrNull(it) }.forEach { an ->
            val cos = an.cosObject
            AnnotStyle.setMeta(cos, p.author, p.subject, p.name)
            p.contents?.let { cos.setString(COSName.CONTENTS, it) }
            p.locked?.let { AnnotStyle.setLocked(cos, it) }
            p.color?.let { c -> cos.setItem(COSName.C, COSArray().apply { AnnotStyle.comps(c).forEach { add(COSFloat(it)) } }) }
            if (p.icon != null && p.icon.isNotBlank()) {
                when (an.subtype) {
                    // the viewer draws the standard icon: our own generic note appearance is dropped
                    "Text" -> { AnnotStyle.setNoteIcon(cos, p.icon); cos.removeItem(COSName.AP) }
                    "FileAttachment" -> { AnnotStyle.setNoteIcon(cos, p.icon); cos.removeItem(COSName.AP) }
                }
            }
            n++
        }
        return n
    }

    /** Move each annotation of [page] by its own (dx, dy) (align / distribute). */
    fun moveEach(doc: PDDocument, page: Int, moves: Map<Int, FloatArray>) {
        val list = doc.getPage(page).annotations
        moves.forEach { (i, d) -> list.getOrNull(i)?.let { StoredOps.shift(it.cosObject, d[0], d[1]) } }
    }

    /** Set /Rect of annotation [index] (same size / resize to anchor): only for annotations whose geometry is the rect. */
    fun setRect(doc: PDDocument, page: Int, index: Int, rect: FloatArray): Boolean {
        val an = doc.getPage(page).annotations.getOrNull(index) ?: return false
        if (an.subtype !in setOf("Square", "Circle", "FreeText", "Stamp", "Text", "FileAttachment", "Caret")) return false
        an.cosObject.setItem(COSName.RECT, COSArray().apply { rect.forEach { add(COSFloat(it)) } })
        an.cosObject.removeItem(COSName.getPDFName("RD"))
        return true
    }

    /** Annotation dictionary without its page / pop-up / thread links (safe to clone). */
    private fun detached(cos: COSDictionary): COSDictionary {
        val c = COSDictionary()
        for (k in cos.keySet()) if (k != COSName.P && k != POPUP && k != COSName.PARENT && k != IRT && k != NM) c.setItem(k, cos.getItem(k))
        return c
    }

    /**
     * Copy annotation [index] of [srcPage] in [src] to page [dstPage] of [dst] (the same or another document),
     * moved by (dx, dy). Gets a fresh /NM and a closed pop-up for markups. Returns the new dictionary.
     */
    fun copyTo(src: PDDocument, srcPage: Int, index: Int, dst: PDDocument, dstPage: Int, dx: Float = 0f, dy: Float = 0f): COSDictionary? {
        val an = src.getPage(srcPage).annotations.getOrNull(index) ?: return null
        val base = detached(an.cosObject)
        val copy = (if (src === dst) deepCopy(base) else PDFCloneUtility(dst).cloneForNewDocument(base)) as? COSDictionary ?: return null
        StoredOps.shift(copy, dx, dy)
        copy.setString(NM, "sd-cp-" + System.nanoTime().toString(36))
        val page = dst.getPage(dstPage)
        val list = page.annotations
        list.add(PDAnnotation.createAnnotation(copy))
        page.annotations = list
        if (copy.getNameAsString(COSName.SUBTYPE) in Annot2Writer.POPUP_KINDS) AnnotStyle.addPopup(page, copy)
        return copy
    }

    /** Copy inside one document: arrays are copied (geometry is then shifted), streams and fonts stay shared. */
    private fun deepCopy(d: COSDictionary): COSDictionary {
        val c = COSDictionary()
        for (k in d.keySet()) {
            val v = d.getItem(k)
            c.setItem(k, if (v is COSArray) copyArray(v) else if (v is COSDictionary && v !is COSStream) deepCopy(v) else v)
        }
        return c
    }

    private fun copyArray(a: COSArray): COSArray = COSArray().apply {
        for (i in 0 until a.size()) { val v = a.get(i); add(if (v is COSArray) copyArray(v) else v) }
    }

    /** Matrix product: apply [a] then [b] (PDF row-vector convention). */
    fun mul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
        a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
        a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5])

    private fun boxOf(bbox: FloatArray, m: FloatArray): FloatArray {
        val xs = FloatArray(4); val ys = FloatArray(4)
        listOf(bbox[0] to bbox[1], bbox[2] to bbox[1], bbox[2] to bbox[3], bbox[0] to bbox[3]).forEachIndexed { i, (x, y) ->
            xs[i] = m[0] * x + m[2] * y + m[4]; ys[i] = m[1] * x + m[3] * y + m[5]
        }
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun floats(v: Any?): FloatArray? = (v as? COSArray)?.let { a -> FloatArray(a.size()) { (a.getObject(it) as? COSNumber)?.floatValue() ?: 0f } }

    /**
     * Rotate the appearance of annotation [index] by [degrees] (counter-clockwise) about its centre: the
     * appearance /Matrix gets the rotation and /Rect becomes the rotated box, so every viewer shows it turned.
     * Text-markup and line geometry is not changed (they are drawn from their appearance). Returns false when the
     * annotation has no appearance stream.
     */
    fun rotate(doc: PDDocument, page: Int, index: Int, degrees: Float): Boolean {
        val an = doc.getPage(page).annotations.getOrNull(index) ?: return false
        val ap = (an.cosObject.getDictionaryObject(COSName.AP) as? COSDictionary)?.getDictionaryObject(COSName.N) as? COSStream ?: return false
        val rect = floats(an.cosObject.getDictionaryObject(COSName.RECT))?.takeIf { it.size >= 4 } ?: return false
        val bbox = floats(ap.getDictionaryObject(COSName.BBOX))?.takeIf { it.size >= 4 } ?: return false
        val m = floats(ap.getDictionaryObject(COSName.MATRIX))?.takeIf { it.size >= 6 } ?: floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        val old = boxOf(bbox, m)
        val rx0 = minOf(rect[0], rect[2]); val ry0 = minOf(rect[1], rect[3]); val rx1 = maxOf(rect[0], rect[2]); val ry1 = maxOf(rect[1], rect[3])
        val sx = (rx1 - rx0) / (old[2] - old[0]).coerceAtLeast(1e-3f); val sy = (ry1 - ry0) / (old[3] - old[1]).coerceAtLeast(1e-3f)
        val t = Math.toRadians(degrees.toDouble())
        val r = floatArrayOf(cos(t).toFloat(), sin(t).toFloat(), -sin(t).toFloat(), cos(t).toFloat(), 0f, 0f)
        val nm = mul(m, r)
        val nb = boxOf(bbox, nm)
        val w = (nb[2] - nb[0]) * sx; val h = (nb[3] - nb[1]) * sy
        val cx = (rx0 + rx1) / 2; val cy = (ry0 + ry1) / 2
        ap.setItem(COSName.MATRIX, COSArray().apply { nm.forEach { add(COSFloat(it)) } })
        an.cosObject.setItem(COSName.RECT, COSArray().apply { listOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2).forEach { add(COSFloat(it)) } })
        // private key so the rotation can be shown / undone in the properties
        an.cosObject.setItem(COSName.getPDFName("SDRotate"), COSFloat(((an.cosObject.getFloat(COSName.getPDFName("SDRotate"), 0f) + degrees) % 360f + 360f) % 360f))
        return true
    }

    /** Put every pop-up of [pages] (all pages when null) back next to its parent, closed. */
    fun resetPopups(doc: PDDocument, pages: Collection<Int>? = null): Int {
        var n = 0
        (pages ?: (0 until doc.numberOfPages).toList()).forEach { pi ->
            val page = doc.getPage(pi)
            page.annotations.forEach { an -> if (AnnotStyle.resetPopup(page, an.cosObject)) n++ }
        }
        return n
    }

    /** Open or close every pop-up of the document (Nitro / Foxit "open all pop-ups"). */
    fun setPopupsOpen(doc: PDDocument, open: Boolean): Int {
        var n = 0
        doc.pages.forEach { page -> page.annotations.forEach { an ->
            (an.cosObject.getDictionaryObject(POPUP) as? COSDictionary)?.let { it.setBoolean(COSName.getPDFName("Open"), open); n++ }
        } }
        return n
    }

    // ================= file wrappers =================

    private suspend fun edit(src: File, out: File, pw: String?, pages: Collection<Int>, change: (PDDocument) -> Unit) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, pw).use { doc ->
            change(doc)
            SignedSave.saveEdited(doc, out, pw, pages.ifEmpty { (0 until doc.numberOfPages).toList() })
        }
    }

    suspend fun setProps(src: File, out: File, pw: String?, page: Int, indices: List<Int>, p: Props) = edit(src, out, pw, listOf(page)) { setProps(it, page, indices, p) }

    suspend fun moveEach(src: File, out: File, pw: String?, page: Int, moves: Map<Int, FloatArray>) = edit(src, out, pw, listOf(page)) { moveEach(it, page, moves) }

    suspend fun setRects(src: File, out: File, pw: String?, page: Int, rects: Map<Int, FloatArray>) = edit(src, out, pw, listOf(page)) { d ->
        rects.forEach { (i, r) -> setRect(d, page, i, r) }
    }

    suspend fun rotate(src: File, out: File, pw: String?, page: Int, indices: List<Int>, degrees: Float) = edit(src, out, pw, listOf(page)) { d ->
        indices.forEach { rotate(d, page, it, degrees) }
    }

    suspend fun flatten(src: File, out: File, pw: String?, page: Int, indices: List<Int>) = edit(src, out, pw, listOf(page)) { d ->
        AnnotStyle.flatten(d, d.getPage(page), indices)
    }

    suspend fun resetPopups(src: File, out: File, pw: String?) = edit(src, out, pw, emptyList()) { resetPopups(it) }

    suspend fun setPopupsOpen(src: File, out: File, pw: String?, open: Boolean) = edit(src, out, pw, emptyList()) { setPopupsOpen(it, open) }

    /** Copy stored annotations [indices] of [srcPage] (from [from] when another document) to every page in [targets]. */
    suspend fun copyStored(from: File, fromPw: String?, srcPage: Int, indices: List<Int>, src: File, out: File, pw: String?,
                           targets: List<Int>, dx: Float, dy: Float) = withContext(Dispatchers.IO) {
        val same = from.canonicalPath == src.canonicalPath
        PdfOps.loadRaw(src, pw).use { dst ->
            val origin = if (same) dst else PdfOps.loadRaw(from, fromPw)
            try {
                val valid = targets.filter { it in 0 until dst.numberOfPages }
                // indices refer to the original list: copy them all before the target page grows
                val n = origin.getPage(srcPage).annotations.size
                valid.forEach { tp -> indices.filter { it < n }.forEach { i -> copyTo(origin, srcPage, i, dst, tp, dx, dy) } }
                SignedSave.saveEdited(dst, out, pw, valid)
            } finally { if (!same) origin.close() }
        }
    }

    /**
     * Copy of [src] (not encrypted, for export only) keeping just the annotations [refs] (page -> indices) with
     * their replies / states / pop-ups and every non-comment annotation (links, widgets). Used by "export selected
     * comments" so the FDF / XFDF writers export the selection only.
     */
    fun keepOnly(doc: PDDocument, refs: Map<Int, Set<Int>>) {
        doc.pages.forEachIndexed { pi, page ->
            val list = page.annotations
            val keep = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<COSDictionary, Boolean>())
            refs[pi].orEmpty().mapNotNullTo(keep) { list.getOrNull(it)?.cosObject }
            // replies and states of kept comments (several levels)
            var grew = true
            while (grew) {
                grew = false
                list.forEach { an ->
                    val c = an.cosObject
                    val parent = (c.getDictionaryObject(IRT) as? COSDictionary) ?: (c.getDictionaryObject(COSName.PARENT) as? COSDictionary)
                    if (parent != null && parent in keep && keep.add(c)) grew = true
                }
            }
            page.annotations = list.filter { an -> an.subtype in setOf("Link", "Widget") || an.cosObject in keep }.toMutableList()
        }
    }

    suspend fun keepOnly(src: File, pw: String?, out: File, refs: Map<Int, Set<Int>>) = withContext(Dispatchers.IO) {
        val doc = if (pw.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, pw)
        doc.use { d ->
            d.isAllSecurityToBeRemoved = true
            keepOnly(d, refs)
            d.save(out)
        }
    }

    /** File name of the PDF an FDF (/F) or XFDF (<f href>) belongs to (Foxit 24.90), or null. */
    fun fdfTargetName(file: File): String? = runCatching {
        val head = file.inputStream().use { i -> ByteArray(minOf(file.length(), 1 shl 20).toInt()).also { i.read(it) } }
        val text = String(head, Charsets.ISO_8859_1)
        if (text.contains("<xfdf")) Regex("""<f\s+href\s*=\s*"([^"]+)"""").find(String(head, Charsets.UTF_8))?.groupValues?.get(1)
        else com.tom_roush.pdfbox.pdmodel.fdf.FDFDocument.load(file).use { fdf -> fdf.catalog?.fdf?.file?.file }
    }.getOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }

    /** Find the PDF named [name] in [dirs] (recursively, newest first). */
    fun findPdf(name: String, dirs: List<File>): File? = dirs.asSequence().filter { it.isDirectory }
        .flatMap { d -> d.walkTopDown().maxDepth(4).filter { it.isFile && it.name.equals(name, true) } }
        .maxByOrNull { it.lastModified() }

    /** Write numbered tickmarks [marks] (page, x, y, label) into the file. */
    suspend fun tickmarks(src: File, out: File, pw: String?, marks: List<Pair<Int, Triple<Float, Float, String>>>, color: Int, author: String) =
        edit(src, out, pw, marks.map { it.first }.distinct()) { d ->
            marks.forEach { (pg, m) -> AnnotStyle.writeTickmark(d, d.getPage(pg), m.first, m.second, m.third, color, author = author) }
        }
}
