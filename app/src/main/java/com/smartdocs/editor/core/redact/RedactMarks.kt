package com.smartdocs.editor.core.redact

import android.graphics.RectF
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * Look of a redaction once applied. [fill] 0xRRGGBB (white = whiteout); [outline] shown while the mark is only
 * marked (null = none); [overlay] text drawn over the box in [textColor]; [fontSize] 0 = auto; [repeat] fills the
 * box with copies of the text; [align] 0 left, 1 centre, 2 right (PDF /Q); [font] an
 * [com.smartdocs.editor.core.edit5.E5Font] name ("" = automatic: Helvetica Bold, the bundled Arabic font for Arabic);
 * [textCase] one of the CASE_ constants (Latin letters only; Arabic has no case).
 */
data class RedactStyle(
    val fill: Int = 0x000000,
    val outline: Int? = 0xE53935,
    val overlay: String = "",
    val textColor: Int = 0xFFFFFF,
    val fontSize: Float = 0f,
    val repeat: Boolean = false,
    val align: Int = 1,
    val font: String = "",
    val textCase: Int = CASE_AS_TYPED,
) {
    /** The overlay text as it is drawn: trimmed, with [textCase] applied. */
    fun shownOverlay(): String = applyCase(overlay.trim(), textCase)

    companion object {
        const val CASE_AS_TYPED = 0
        const val CASE_UPPER = 1
        const val CASE_LOWER = 2
        const val CASE_TITLE = 3

        fun applyCase(t: String, case: Int): String = when (case) {
            CASE_UPPER -> t.uppercase(java.util.Locale.ROOT)
            CASE_LOWER -> t.lowercase(java.util.Locale.ROOT)
            CASE_TITLE -> t.lowercase(java.util.Locale.ROOT).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(java.util.Locale.ROOT) } }
            else -> t
        }
    }
}

/**
 * A redaction mark in PDF user space (unrotated, top > bottom like [com.smartdocs.editor.core.pdf.SearchHit]).
 * [wholePage] wipes the page when applied. [label] is what was found (shown in the review list).
 */
data class RedactMark(
    val id: String,
    val page: Int,
    val rects: List<RectF>,
    val style: RedactStyle = RedactStyle(),
    val label: String = "",
    val wholePage: Boolean = false,
) {
    fun boxes(): List<Box> = rects.map { Box.of(it.left, it.bottom, it.right, it.top) }
    fun bounds(): Box? = boxes().reduceOrNull { a, b -> a.union(b) }
}

/** Reads and writes marks as standard `/Redact` annotations, so other viewers show and can apply them. */
object RedactMarks {
    private val REDACT = "Redact"
    private val K_WHOLE = COSName.getPDFName("SDWholePage")
    private val K_AUTO = COSName.getPDFName("SDAutoSize")
    private val K_OVERLAY = COSName.getPDFName("OverlayText")
    private val K_REPEAT = COSName.getPDFName("Repeat")
    private val K_IC = COSName.getPDFName("IC")
    private val K_QUAD = COSName.getPDFName("QuadPoints")
    private val K_Q = COSName.getPDFName("Q")
    private val K_DA = COSName.getPDFName("DA")
    private val K_NM = COSName.getPDFName("NM")
    private val K_FONT = COSName.getPDFName("SDFont")
    private val K_CASE = COSName.getPDFName("SDCase")

    fun isRedact(a: PDAnnotation) = a.subtype == REDACT

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
    private fun colorArray(c: Int) = COSArray().apply { rgb(c).forEach { add(COSFloat(it)) } }
    private fun colorOf(a: COSArray?): Int? {
        if (a == null || a.size() < 3) return null
        fun f(i: Int) = (((a.getObject(i) as? COSNumber)?.floatValue() ?: 0f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (f(0) shl 16) or (f(1) shl 8) or f(2)
    }

    /** Every /Redact annotation of the document as marks (page order). */
    fun read(doc: PDDocument): List<RedactMark> {
        val out = ArrayList<RedactMark>()
        val seen = HashSet<String>()   // /NM can repeat (copied annotations): ids are LazyColumn keys and must be unique
        doc.pages.forEachIndexed { pi, page ->
            runCatching { page.annotations }.getOrNull()?.forEachIndexed { ai, an ->
                if (!isRedact(an)) return@forEachIndexed
                val d = an.cosObject
                val q = d.getDictionaryObject(K_QUAD) as? COSArray
                val rects = ArrayList<RectF>()
                if (q != null && q.size() >= 8) {
                    var i = 0
                    while (i + 7 < q.size()) {
                        val xs = (0 until 4).map { (q.getObject(i + it * 2) as? COSNumber)?.floatValue() ?: 0f }
                        val ys = (0 until 4).map { (q.getObject(i + it * 2 + 1) as? COSNumber)?.floatValue() ?: 0f }
                        rects += RectF(xs.min(), ys.max(), xs.max(), ys.min())
                        i += 8
                    }
                }
                if (rects.isEmpty()) an.rectangle?.let { r -> rects += RectF(r.lowerLeftX, r.upperRightY, r.upperRightX, r.lowerLeftY) }
                val auto = d.getBoolean(K_AUTO, true)
                val da = d.getString(K_DA) ?: ""
                val size = Regex("([0-9.]+)\\s+Tf").find(da)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val tc = Regex("([0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s+rg").find(da)?.groupValues?.let { g ->
                    fun b(s: String) = ((s.toFloatOrNull() ?: 0f).coerceIn(0f, 1f) * 255 + 0.5f).toInt()
                    (b(g[1]) shl 16) or (b(g[2]) shl 8) or b(g[3])
                } ?: 0xFFFFFF
                val style = RedactStyle(
                    fill = colorOf(d.getDictionaryObject(K_IC) as? COSArray) ?: 0x000000,
                    outline = colorOf(d.getDictionaryObject(COSName.C) as? COSArray),
                    overlay = d.getString(K_OVERLAY) ?: "",
                    textColor = tc,
                    fontSize = if (auto) 0f else size,
                    repeat = d.getBoolean(K_REPEAT, false),
                    align = d.getInt(K_Q, 1).coerceIn(0, 2),
                    font = d.getString(K_FONT) ?: "",
                    textCase = d.getInt(K_CASE, RedactStyle.CASE_AS_TYPED).coerceIn(0, 3),
                )
                out += RedactMark((d.getString(K_NM) ?: "r:$pi:$ai").let { if (seen.add(it)) it else "$it#$pi:$ai:${System.nanoTime()}".also { k -> seen.add(k) } }, pi, rects, style, an.contents ?: "", d.getBoolean(K_WHOLE, false))
            }
        }
        return out
    }

    /** Removes every /Redact annotation (all pages). Returns how many were removed. */
    fun removeAll(doc: PDDocument): Int {
        var n = 0
        doc.pages.forEach { page ->
            val list = runCatching { page.annotations }.getOrNull() ?: return@forEach
            val keep = list.filterNot { isRedact(it) }
            if (keep.size != list.size) { n += list.size - keep.size; page.annotations = keep }
        }
        return n
    }

    /** Replaces the document's /Redact annotations by [marks]. */
    fun writeAll(doc: PDDocument, marks: List<RedactMark>, author: String = "") {
        removeAll(doc)
        val now = Calendar.getInstance()
        marks.groupBy { it.page }.forEach { (pi, list) ->
            if (pi !in 0 until doc.numberOfPages) return@forEach
            val page = doc.getPage(pi)
            val annots = page.annotations
            list.forEach { m -> annots.add(toAnnotation(doc, page, m, author, now)) }
            page.annotations = annots
        }
    }

    private fun toAnnotation(doc: PDDocument, page: PDPage, m: RedactMark, author: String, now: Calendar): PDAnnotation {
        val a = PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, REDACT) }
        val d = a.cosObject
        val rects = if (m.wholePage || m.rects.isEmpty()) {
            val cb = page.cropBox; listOf(RectF(cb.lowerLeftX, cb.upperRightY, cb.upperRightX, cb.lowerLeftY))
        } else m.rects.map { RectF(minOf(it.left, it.right), maxOf(it.top, it.bottom), maxOf(it.left, it.right), minOf(it.top, it.bottom)) }
        val quads = COSArray()
        rects.forEach { r ->
            // upper-left, upper-right, lower-left, lower-right
            floatArrayOf(r.left, r.top, r.right, r.top, r.left, r.bottom, r.right, r.bottom).forEach { quads.add(COSFloat(it)) }
        }
        d.setItem(K_QUAD, quads)
        // RectF.union ignores rects with top > bottom, so the union is computed by hand
        val ul = rects.minOf { it.left }; val ur = rects.maxOf { it.right }; val ub = rects.minOf { it.bottom }; val ut = rects.maxOf { it.top }
        a.rectangle = PDRectangle(ul, ub, (ur - ul).coerceAtLeast(1f), (ut - ub).coerceAtLeast(1f))
        d.setItem(K_IC, colorArray(m.style.fill))
        m.style.outline?.let { d.setItem(COSName.C, colorArray(it)) }
        // other viewers draw /OverlayText as it is, so the case is applied here; SDCase/SDFont bring the settings back
        if (m.style.overlay.isNotEmpty()) d.setString(K_OVERLAY, m.style.shownOverlay())
        if (m.style.font.isNotEmpty()) d.setString(K_FONT, m.style.font)
        if (m.style.textCase != RedactStyle.CASE_AS_TYPED) d.setInt(K_CASE, m.style.textCase)
        d.setBoolean(K_REPEAT, m.style.repeat)
        val c = rgb(m.style.textColor)
        d.setString(K_DA, "/Helv ${if (m.style.fontSize > 0f) m.style.fontSize else 0f} Tf %.3f %.3f %.3f rg".format(java.util.Locale.US, c[0], c[1], c[2]))
        d.setInt(K_Q, m.style.align)
        d.setString(K_NM, m.id)
        d.setBoolean(K_WHOLE, m.wholePage)
        d.setBoolean(K_AUTO, m.style.fontSize <= 0f)
        a.contents = m.label
        a.titlePopup = author
        a.setCreationDate(now); a.setModifiedDate(now)
        a.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance(doc, a.rectangle, rects, m.style)) }
        return a
    }

    /** Before applying, a mark shows as an outline with a light tint of the fill colour. */
    private fun appearance(doc: PDDocument, bounds: PDRectangle, rects: List<RectF>, st: RedactStyle): PDAppearanceStream {
        val ap = PDAppearanceStream(doc)
        ap.bBox = bounds
        ap.resources = com.tom_roush.pdfbox.pdmodel.PDResources()
        PDPageContentStream(doc, ap).use { cs ->
            val gs = com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.25f }
            cs.setGraphicsStateParameters(gs)
            val f = rgb(st.fill); cs.setNonStrokingColor(f[0], f[1], f[2])
            rects.forEach { r -> cs.addRect(r.left, r.bottom, r.right - r.left, r.top - r.bottom) }
            cs.fill()
            val o = rgb(st.outline ?: 0xE53935)
            val solid = com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 1f; strokingAlphaConstant = 1f }
            cs.setGraphicsStateParameters(solid)
            cs.setStrokingColor(o[0], o[1], o[2]); cs.setLineWidth(1f)
            rects.forEach { r -> cs.addRect(r.left, r.bottom, r.right - r.left, r.top - r.bottom) }
            cs.stroke()
        }
        return ap
    }

    /** Loads [src], replaces its marks with [marks] and saves to [out] (full save). */
    suspend fun save(src: File, out: File, password: String?, marks: List<RedactMark>, author: String = "") = withContext(Dispatchers.IO) {
        require(out.canonicalPath != src.canonicalPath) { "out must differ from src" }
        PdfOps.load(src, password).use { doc -> writeAll(doc, marks, author); doc.save(out) }
    }

    /** Marks stored in [src]. */
    suspend fun load(src: File, password: String?): List<RedactMark> = withContext(Dispatchers.IO) {
        PdfOps.load(src, password).use { read(it) }
    }
}
