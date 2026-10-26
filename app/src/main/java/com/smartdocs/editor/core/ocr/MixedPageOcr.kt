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

    /** Axis-aligned box in PDF user space (y up). Plain Kotlin so the planning rules are unit-testable. */
    data class Box(val left: Float, val bottom: Float, val right: Float, val top: Float) {
        val area: Float get() = maxOf(0f, right - left) * maxOf(0f, top - bottom)
        fun contains(x: Float, y: Float) = x in left..right && y in bottom..top
    }

    /**
     * Pages of [range] that need OCR:
     *  - pages without a text layer (scans, pictures of text);
     *  - pages whose text layer is unusable ([com.smartdocs.editor.core.search.ArabicTextFix.isUnusableText]: glyph
     *    codes without Unicode, Arabic from fonts with a wrong ToUnicode map) — they get an invisible layer only;
     *  - pages with real text that also show a scanned region: pictures of at least 64x64 px with no text on them
     *    covering at least [SCAN_SHARE] of the page ([scanShare]).
     * Digital pages whose pictures are decorations, highlight tiles, diagrams with labels, logos… are skipped
     * (they used to be planned whenever the page had any image).
     */
    suspend fun plan(session: PdfSession, file: File, password: String?, range: List<Int>): Plan = withContext(Dispatchers.IO) {
        val geom = HashMap<Int, PageGeom>()
        val texts = HashMap<Int, String>()
        range.forEach { pi -> texts[pi] = runCatching { session.text(pi) }.getOrDefault("") }
        val scanned = HashSet<Int>()
        runCatching {
            PdfOps.load(file, password).use { doc ->
                for (pi in range) {
                    if (pi !in 0 until doc.numberOfPages) continue
                    val p = doc.getPage(pi)
                    val b = p.cropBox ?: p.mediaBox
                    geom[pi] = PageGeom(p.rotation.mod(360), RectF(b.lowerLeftX, b.upperRightY, b.upperRightX, b.lowerLeftY))
                    val t = texts[pi].orEmpty()
                    // only digital pages with good text need the (slower) look at where their pictures are drawn
                    if (t.isBlank() || com.smartdocs.editor.core.search.ArabicTextFix.isUnusableText(t) || !hasImages(p.resources, 0)) continue
                    val images = runCatching { imageBoxes(p) }.getOrDefault(emptyList())
                    if (images.isEmpty()) continue
                    val chars = runCatching { session.charBoxes(pi) }.getOrDefault(emptyList())
                        .map { Box(minOf(it.left, it.right), minOf(it.top, it.bottom), maxOf(it.left, it.right), maxOf(it.top, it.bottom)) }
                    val page = Box(b.lowerLeftX, b.lowerLeftY, b.upperRightX, b.upperRightY)
                    if (scanShare(images, chars, page) >= SCAN_SHARE) scanned += pi
                }
            }
        }
        val pages = range.filter { pi ->
            val text = texts[pi].orEmpty()
            text.isBlank() || com.smartdocs.editor.core.search.ArabicTextFix.isUnusableText(text) || pi in scanned
        }
        Plan(pages, geom)
    }

    /** Share of the page covered by a scanned region before a digital page is OCRed. */
    const val SCAN_SHARE = 0.10f

    /**
     * Share (0..1) of [page] covered by pictures that carry no text of their own: pictures with fewer than 10 text
     * characters (centres from [chars]) on them. Pictures are clipped to the page; overlaps are not merged
     * (a slight over-estimate, fine for a threshold).
     */
    fun scanShare(images: List<Box>, chars: List<Box>, page: Box): Float {
        if (page.area <= 0f) return 0f
        var covered = 0f
        for (im in images) {
            val c = Box(maxOf(im.left, page.left), maxOf(im.bottom, page.bottom), minOf(im.right, page.right), minOf(im.top, page.top))
            if (c.area <= 0f) continue
            val inside = chars.count { ch -> c.contains((ch.left + ch.right) / 2f, (ch.bottom + ch.top) / 2f) }
            if (inside < 10) covered += c.area
        }
        return (covered / page.area).coerceIn(0f, 1f)
    }

    /** Where the page draws its pictures (at least 64x64 px; tiny tiles and masks are decoration), in user space. */
    internal fun imageBoxes(page: com.tom_roush.pdfbox.pdmodel.PDPage): List<Box> {
        val out = ArrayList<Box>()
        val engine = object : com.tom_roush.pdfbox.contentstream.PDFStreamEngine() {
            init {
                addOperator(com.tom_roush.pdfbox.contentstream.operator.state.Concatenate())
                addOperator(com.tom_roush.pdfbox.contentstream.operator.state.Save())
                addOperator(com.tom_roush.pdfbox.contentstream.operator.state.Restore())
                addOperator(com.tom_roush.pdfbox.contentstream.operator.DrawObject())
            }
            fun add(w: Int, h: Int) {
                if (w < 64 || h < 64 || out.size > 500) return
                val m = graphicsState.currentTransformationMatrix
                val a = m.getValue(0, 0); val bb = m.getValue(0, 1); val c = m.getValue(1, 0); val d = m.getValue(1, 1)
                val e = m.getValue(2, 0); val f = m.getValue(2, 1)
                val xs = floatArrayOf(e, a + e, c + e, a + c + e); val ys = floatArrayOf(f, bb + f, d + f, bb + d + f)
                out += Box(xs.min(), ys.min(), xs.max(), ys.max())
            }
            override fun processOperator(operator: com.tom_roush.pdfbox.contentstream.operator.Operator, operands: MutableList<com.tom_roush.pdfbox.cos.COSBase>) {
                if (operator.name == "Do") {
                    val name = operands.firstOrNull() as? COSName
                    val x = name?.let { n -> runCatching { resources?.getXObject(n) }.getOrNull() }
                    if (x is PDImageXObject) { add(x.width, x.height); return }
                } else if (operator.name == "BI") {
                    val p = operator.imageParameters
                    if (p != null) add(p.getInt(COSName.W, p.getInt(COSName.WIDTH, 0)), p.getInt(COSName.H, p.getInt(COSName.HEIGHT, 0)))
                    return
                }
                super.processOperator(operator, operands)
            }
        }
        engine.processPage(page)
        return out
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
        // an unusable text layer (glyph codes, wrongly mapped Arabic) is not "existing text": keep every OCR word
        if (text.isBlank() || com.smartdocs.editor.core.search.ArabicTextFix.isUnusableText(text)) return page
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
