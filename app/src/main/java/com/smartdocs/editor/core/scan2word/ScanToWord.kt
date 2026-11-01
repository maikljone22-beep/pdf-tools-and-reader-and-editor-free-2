package com.smartdocs.editor.core.scan2word

import com.smartdocs.editor.core.convert.Glyph
import com.smartdocs.editor.core.convert.PageContent
import com.smartdocs.editor.core.convert.PageImage
import com.smartdocs.editor.core.convert.Segment
import com.smartdocs.editor.core.ocr.TableRules
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photo → faithful Word, phase 1 (specs/SCAN_TO_WORD_PLAN.md). A recognised page photo becomes the converter's
 * [PageContent] directly — no PDF in between — so the layout analyser and the Word writer see what the page really
 * has: the physical page size, each word at its place with an estimated point size, bold and colour, the table rules
 * found in the picture as real line segments (so ruled tables become Word tables with borders), and the figures cut
 * out as pictures. Pure Kotlin over ARGB pixels (tested); the Android side only supplies the bitmap and OCR words.
 */
object ScanToWord {

    /** One recognised word in picture pixels. */
    data class OcrBox(val text: String, val l: Int, val t: Int, val r: Int, val b: Int, val conf: Float, val line: Int)

    /** Standard paper sizes (portrait, points) a photographed page is snapped to by its shape. */
    val PAPERS = listOf("A4" to (595.28f to 841.89f), "A5" to (419.53f to 595.28f), "B5" to (498.9f to 708.66f), "Letter" to (612f to 792f))

    /** The physical page in points for a page picture of [w]×[h] px: the standard paper with the nearest shape (A4 when none is close). */
    fun pageSize(w: Int, h: Int): Pair<Float, Float> {
        val land = w > h
        val ratio = max(w, h).toFloat() / min(w, h).coerceAtLeast(1)
        val best = PAPERS.minBy { (_, s) -> abs(s.second / s.first - ratio) }
        val s = if (abs(best.second.second / best.second.first - ratio) < 0.06f) best.second else PAPERS[0].second.let { a4 -> a4.first to a4.first * ratio }
        return if (land) s.second to s.first else s
    }

    private val SIZES = floatArrayOf(8f, 9f, 10f, 11f, 12f, 13f, 14f, 16f, 18f, 20f, 22f, 24f, 28f, 32f, 36f, 40f, 48f)

    /** Point size from a word-box height in points: Arabic boxes hold dots and marks above and below (≈ 1.3 × the size). */
    fun pointSize(boxHeightPt: Float, arabic: Boolean): Float {
        val raw = boxHeightPt / (if (arabic) 1.32f else 1.12f)
        return SIZES.minBy { abs(it - raw) }
    }

    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /**
     * Stroke thickness of the ink in a box relative to its height (2·area / boundary ÷ height): bold type is clearly
     * thicker than the page's regular text. 0 when the box holds no ink.
     */
    fun strokeRatio(px: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int): Float {
        val x0 = l.coerceAtLeast(0); val x1 = r.coerceAtMost(w - 1); val y0 = t.coerceAtLeast(0); val y1 = b.coerceAtMost(px.size / w - 1)
        if (x1 <= x0 || y1 <= y0) return 0f
        var sum = 0L; var n = 0
        for (y in y0..y1) for (x in x0..x1) { sum += lum(px[y * w + x]); n++ }
        val thr = (sum / n * 0.6).toInt()
        fun ink(x: Int, y: Int) = x in x0..x1 && y in y0..y1 && lum(px[y * w + x]) < thr
        var area = 0; var edge = 0
        for (y in y0..y1) for (x in x0..x1) if (ink(x, y)) {
            area++
            if (!ink(x - 1, y) || !ink(x + 1, y) || !ink(x, y - 1) || !ink(x, y + 1)) edge++
        }
        if (area == 0 || edge == 0) return 0f
        return (2f * area / edge) / (y1 - y0 + 1)
    }

    /** The dominant ink colour of a box (0 = black / grey ink). */
    fun inkColor(px: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int): Int {
        val h = px.size / w
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        var sum = 0L; var all = 0
        for (y in max(0, t)..min(h - 1, b)) for (x in max(0, l)..min(w - 1, r)) { sum += lum(px[y * w + x]); all++ }
        if (all == 0) return 0
        val thr = sum / all * 0.6
        for (y in max(0, t)..min(h - 1, b)) for (x in max(0, l)..min(w - 1, r)) {
            val c = px[y * w + x]; if (lum(c) >= thr) continue
            rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF; n++
        }
        if (n == 0) return 0
        val r0 = (rs / n).toInt(); val g0 = (gs / n).toInt(); val b0 = (bs / n).toInt()
        val chroma = maxOf(r0, g0, b0) - minOf(r0, g0, b0)
        return if (chroma < 55) 0 else (r0 shl 16) or (g0 shl 8) or b0
    }

    /**
     * Figures: regions of ink that are not words and not table rules, found on a coarse grid and kept when big
     * enough. Returned as pixel rectangles (l, t, r, b).
     */
    fun figures(px: IntArray, w: Int, h: Int, words: List<OcrBox>, rules: List<TableRules.Rule>): List<IntArray> {
        val cell = max(6, min(w, h) / 120)
        val gw = (w + cell - 1) / cell; val gh = (h + cell - 1) / cell
        val masked = BooleanArray(w * h)
        val pad = cell / 2
        for (wd in words) for (y in max(0, wd.t - pad)..min(h - 1, wd.b + pad)) for (x in max(0, wd.l - pad)..min(w - 1, wd.r + pad)) masked[y * w + x] = true
        for (r in rules) for (y in max(0, r.y0 - 3)..min(h - 1, r.y1 + 3)) for (x in max(0, r.x0 - 3)..min(w - 1, r.x1 + 3)) masked[y * w + x] = true
        var sum = 0L; for (c in px) sum += lum(c)
        val thr = (sum / px.size * 0.75).toInt()
        val grid = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var ink = 0; var tot = 0
            for (y in gy * cell until min(h, (gy + 1) * cell)) for (x in gx * cell until min(w, (gx + 1) * cell)) {
                tot++; val i = y * w + x; val c = px[i]
                if (!masked[i] && (lum(c) < thr || (maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) - minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)) > 60)) ink++
            }
            grid[gy * gw + gx] = tot > 0 && ink * 12 > tot
        }
        // close small gaps between figure parts, then connected components
        val closed = BooleanArray(gw * gh) { i -> val x = i % gw; val y = i / gw; (-1..1).any { dy -> (-1..1).any { dx -> val xx = x + dx; val yy = y + dy; xx in 0 until gw && yy in 0 until gh && grid[yy * gw + xx] } } }
        val seen = BooleanArray(gw * gh); val out = ArrayList<IntArray>()
        val stack = IntArray(gw * gh)
        for (s in closed.indices) {
            if (!closed[s] || seen[s]) continue
            var sp = 0; stack[sp++] = s; seen[s] = true
            var minX = gw; var minY = gh; var maxX = 0; var maxY = 0; var count = 0
            while (sp > 0) {
                val i = stack[--sp]; val x = i % gw; val y = i / gw; count++
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until gw && yy in 0 until gh) { val j = yy * gw + xx; if (closed[j] && !seen[j]) { seen[j] = true; stack[sp++] = j } }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            // at least ~4 % of the page side both ways, and solid enough to be a picture rather than stray marks
            if (bw * cell >= w / 25 && bh * cell >= h / 25 && count * 3 >= bw * bh && bw * bh >= 12)
                out += intArrayOf(minX * cell, minY * cell, min(w, (maxX + 1) * cell), min(h, (maxY + 1) * cell))
        }
        return out
    }

    /**
     * The converter page for one recognised page picture [px] (w×h). [crop] encodes a figure region to image bytes
     * (JPEG / PNG); [arabicFont] / [latinFont] are the families the text is set in (closest match, not identity).
     */
    fun page(index: Int, px: IntArray, w: Int, h: Int, words0: List<OcrBox>, arabicFont: String, latinFont: String,
             crop: (IntArray) -> Pair<ByteArray, String>?): PageContent {
        val (pw, ph) = pageSize(w, h)
        val s = pw / w
        val rules = TableRules.detect(px, w, h, minFrac = 0.06f)
        val figs = figures(px, w, h, words0, rules)
        // words inside a figure belong to the picture (labels of a diagram)
        val words = words0.filter { wd -> figs.none { f -> wd.l >= f[0] - 2 && wd.r <= f[2] + 2 && wd.t >= f[1] - 2 && wd.b <= f[3] + 2 } && wd.text.isNotBlank() }
        // per line: one size (the median box height), bold when most of its words are thick, colour when shared
        val strokes = words.associateWith { strokeRatio(px, w, it.l, it.t, it.r, it.b) }
        val regular = strokes.values.filter { it > 0f }.sorted().let { if (it.isEmpty()) 0f else it[it.size * 2 / 5] }
        val byLine = words.groupBy { it.line }
        val glyphs = ArrayList<Glyph>()
        for ((_, lw) in byLine) {
            val arabic = lw.any { wd -> wd.text.any { it in '؀'..'ۿ' } }
            val hPt = lw.map { (it.b - it.t) * s }.sorted()[lw.size / 2]
            val size = pointSize(hPt, arabic)
            val bold = regular > 0f && lw.count { (strokes[it] ?: 0f) > regular * 1.35f } * 2 > lw.size
            val colors = lw.map { inkColor(px, w, it.l, it.t, it.r, it.b) }
            val color = colors.groupingBy { it }.eachCount().maxBy { it.value }.key.takeIf { c -> colors.count { it == c } * 2 > colors.size } ?: 0
            for (wd in lw) {
                val wordArabic = wd.text.any { it in '؀'..'ۿ' }
                val base = wd.b * s - (wd.b - wd.t) * s * 0.18f
                glyphs += Glyph(x = wd.l * s, top = wd.t * s, w = (wd.r - wd.l) * s, h = (wd.b - wd.t) * s, baseline = base,
                    text = wd.text, fontSize = size, bold = bold, color = color, font = if (wordArabic || arabic) arabicFont else latinFont)
                // a space after every word: the analyser never glues two close recognised words together
                glyphs += Glyph(x = wd.r * s, top = wd.t * s, w = 0.1f, h = (wd.b - wd.t) * s, baseline = base, text = " ", fontSize = size)
            }
        }
        val segments = rules.map { r ->
            if (r.horizontal) { val y = (r.y0 + r.y1) / 2f * s; Segment(r.x0 * s, y, (r.x1 + 1) * s, y) }
            else { val x = (r.x0 + r.x1) / 2f * s; Segment(x, r.y0 * s, x, (r.y1 + 1) * s) }
        }
        val images = figs.mapNotNull { f -> crop(f)?.let { (bytes, ext) -> PageImage(f[0] * s, f[1] * s, (f[2] - f[0]) * s, (f[3] - f[1]) * s, bytes, ext) } }
        return PageContent(index, pw, ph, glyphs, segments, images)
    }

    /** Point size steps, for tests. */
    internal fun snapped(v: Float) = SIZES.minBy { abs(it - v) }.let { it.roundToInt() }
}
