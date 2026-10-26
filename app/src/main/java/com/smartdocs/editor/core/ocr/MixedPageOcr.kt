package com.smartdocs.editor.core.ocr

import android.graphics.Rect
import android.graphics.RectF
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OCR for pages that mix real text with scanned parts. A page with a few selectable words (a typed title, a stamp,
 * a page number) plus a scanned body used to be skipped entirely; now it is recognised and only the words that are
 * not already selectable are added, so nothing is duplicated.
 */
object MixedPageOcr {

    /** Page geometry needed to map PDFium char boxes (PDF user space) onto the rendered bitmap. */
    data class PageGeom(val rotation: Int, val box: RectF)

    /** [pages]: pages that need OCR. [geom]: rotation + visible box of every page of the range. */
    data class Plan(val pages: List<Int>, val geom: Map<Int, PageGeom>)

    /**
     * Pages of [range] that need OCR: pages without a text layer, and pages that have text AND pictures
     * (scanned content next to real text). Pure digital pages (text, no images) are skipped.
     */
    suspend fun plan(session: PdfSession, file: File, password: String?, range: List<Int>): Plan = withContext(Dispatchers.IO) {
        val geom = HashMap<Int, PageGeom>()
        val withImages = HashSet<Int>()
        runCatching {
            PdfOps.load(file, password).use { doc ->
                for (pi in range) {
                    if (pi !in 0 until doc.numberOfPages) continue
                    val p = doc.getPage(pi)
                    val b = p.cropBox ?: p.mediaBox
                    geom[pi] = PageGeom(p.rotation.mod(360), RectF(b.lowerLeftX, b.upperRightY, b.upperRightX, b.lowerLeftY))
                    if (hasImages(p.resources, 0)) withImages += pi
                }
            }
        }
        val pages = range.filter { pi ->
            val text = runCatching { session.text(pi) }.getOrDefault("")
            // garbage extraction (glyph codes without Unicode) counts as no text: OCR the page fully
            text.isBlank() || pi in withImages || com.smartdocs.editor.core.search.ArabicTextFix.isGarbage(text)
        }
        Plan(pages, geom)
    }

    private fun hasImages(res: PDResources?, depth: Int): Boolean {
        if (res == null || depth > 3) return false
        for (name: COSName in res.xObjectNames) {
            val x = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            if (x is PDImageXObject) return true
            if (x is PDFormXObject && hasImages(x.resources, depth + 1)) return true
        }
        return false
    }

    /**
     * Removes OCR words that lie on text the page already has, keeping only new words.
     * Returns [page] unchanged when the page has no text layer.
     */
    suspend fun dropExisting(session: PdfSession, pi: Int, page: OcrPage, plan: Plan?): OcrPage {
        if (page.words.isEmpty()) return page
        val text = runCatching { session.text(pi) }.getOrDefault("")
        if (text.isBlank() || com.smartdocs.editor.core.search.ArabicTextFix.isGarbage(text)) return page
        val boxes = runCatching { session.charBoxes(pi) }.getOrDefault(emptyList())
        if (boxes.isEmpty()) return page
        val g = plan?.geom?.get(pi) ?: run {
            val (w, h) = session.pageSize(pi)
            PageGeom(0, RectF(0f, h, w, 0f))
        }
        val px = boxes.mapNotNull { toBitmap(it, g, page.imageWidth) }
        val kept = filterWords(page.words, px)
        if (kept.size == page.words.size) return page
        return page.copy(text = kept.joinToString(" ") { it.text }, words = kept)
    }

    /**
     * Maps a char box in PDF user space ([r]: left, top, right, bottom with top > bottom) to bitmap pixels of a
     * page rendered [bmpW] pixels wide in its displayed orientation. Null for empty boxes.
     */
    fun toBitmap(r: RectF, g: PageGeom, bmpW: Int): RectF? {
        val l = g.box.left; val t = g.box.top; val rr = g.box.right; val b = g.box.bottom
        val w = rr - l; val h = t - b
        if (w <= 0f || h <= 0f || bmpW <= 0) return null
        val dispW = if (g.rotation == 90 || g.rotation == 270) h else w
        val s = bmpW / dispW
        fun map(x: Float, y: Float): Pair<Float, Float> = when (g.rotation) {
            90 -> (y - b) to (x - l)
            180 -> (rr - x) to (y - b)
            270 -> (t - y) to (rr - x)
            else -> (x - l) to (t - y)
        }
        val (x1, y1) = map(r.left, maxOf(r.top, r.bottom))
        val (x2, y2) = map(r.right, minOf(r.top, r.bottom))
        val out = RectF(minOf(x1, x2) * s, minOf(y1, y2) * s, maxOf(x1, x2) * s, maxOf(y1, y2) * s)
        return if (out.width() < 0.5f || out.height() < 0.5f) null else out
    }

    /** Keeps words whose area is covered by existing text for at most [maxCovered] (0..1). */
    fun filterWords(words: List<OcrWord>, existing: List<RectF>, maxCovered: Float = 0.35f): List<OcrWord> {
        if (existing.isEmpty()) return words
        return words.filter { w -> coveredRatio(w.box, existing) <= maxCovered }
    }

    fun coveredRatio(box: Rect, existing: List<RectF>): Float {
        val area = box.width().toFloat() * box.height().toFloat()
        if (area <= 0f) return 1f
        var covered = 0f
        for (e in existing) {
            val ix = minOf(box.right.toFloat(), e.right) - maxOf(box.left.toFloat(), e.left)
            if (ix <= 0f) continue
            val iy = minOf(box.bottom.toFloat(), e.bottom) - maxOf(box.top.toFloat(), e.top)
            if (iy <= 0f) continue
            covered += ix * iy
            if (covered >= area) return 1f
        }
        return covered / area
    }
}
