package com.smartdocs.editor.core.scan2word

import com.smartdocs.editor.core.convert.Align
import com.smartdocs.editor.core.convert.Block
import com.smartdocs.editor.core.convert.Decorations
import com.smartdocs.editor.core.convert.Glyph
import com.smartdocs.editor.core.convert.ImageBlock
import com.smartdocs.editor.core.convert.LayoutAnalyzer
import com.smartdocs.editor.core.convert.Line
import com.smartdocs.editor.core.convert.PageLayout
import com.smartdocs.editor.core.convert.ParagraphBlock
import com.smartdocs.editor.core.convert.Rect
import com.smartdocs.editor.core.convert.TableBlock
import com.smartdocs.editor.core.convert.TableCell
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

    /** Stroke / box-height ratio from which type reads as bold (regular book text ≈ 0.08–0.10, bold ≈ 0.13–0.16). */
    const val BOLD_ABS = 0.118f

    /** Stroke width / em from which type reads as bold (regular Arabic book text ≈ 0.07–0.09 em, bold ≈ 0.11–0.16). */
    const val BOLD_EM = 0.105f

    private val SIZES = floatArrayOf(8f, 9f, 10f, 11f, 12f, 13f, 14f, 16f, 18f, 20f, 22f, 24f, 28f, 32f, 36f, 40f, 48f)

    /**
     * Point size from a word-box height in points. Measured on printed Yemeni books: an Arabic word box is about one
     * em tall (its marks above and below included), a Latin one about 0.9 em.
     */
    fun pointSize(boxHeightPt: Float, arabic: Boolean): Float {
        val raw = boxHeightPt / (if (arabic) 1.0f else 0.9f)
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
            grid[gy * gw + gx] = tot > 0 && ink * 4 > tot
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
            if (bw * cell < w / 12 || bh * cell < h / 40 || count * 2 < bw * bh || bw * bh < 16) continue
            var f = intArrayOf(minX * cell, minY * cell, min(w, (maxX + 1) * cell), min(h, (maxY + 1) * cell))
            // words at a side of the region are next to the picture, not in it: the region stops before them
            for (wd in words) {
                if (wd.r <= f[0] || wd.l >= f[2] || wd.b <= f[1] || wd.t >= f[3]) continue
                val cx = (wd.l + wd.r) / 2
                if (cx > (f[0] + f[2]) / 2 && wd.l > f[0] + (f[2] - f[0]) / 3) f = intArrayOf(f[0], f[1], wd.l - 2, f[3])
                else if (cx <= (f[0] + f[2]) / 2 && wd.r < f[2] - (f[2] - f[0]) / 3) f = intArrayOf(wd.r + 2, f[1], f[2], f[3])
            }
            if (f[2] - f[0] < w / 12) continue
            // mostly covered by recognised words: dense bold text, not a picture
            var covered = 0L
            for (wd in words) { val ix = min(f[2], wd.r) - max(f[0], wd.l); val iy = min(f[3], wd.b) - max(f[1], wd.t); if (ix > 0 && iy > 0) covered += ix.toLong() * iy }
            if (covered * 4 > (f[2] - f[0]).toLong() * (f[3] - f[1])) continue
            var mid = 0; var colour = 0; var tot = 0
            var yy = f[1]
            while (yy < f[3]) { var xx = f[0]; while (xx < f[2]) { val c = px[yy * w + xx]; val l = lum(c); tot++
                if (l in 70..215) mid++
                if (maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) - minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) > 50) colour++
                xx += 3 }; yy += 3 }
            if (mid * 5 < tot && colour * 10 < tot) continue
            out += f
        }
        return out
    }

    /**
     * The converter page for one recognised page picture [px] (w×h). [crop] encodes a figure region to image bytes
     * (JPEG / PNG); [arabicFont] / [latinFont] are the families the text is set in (closest match, not identity).
     */
    /** The table and box rules of a page picture (shorter ones than the OCR writer uses: table cells are small). */
    fun rules(px: IntArray, w: Int, h: Int): List<TableRules.Rule> {
        var sum = 0L; for (c in px) sum += lum(c)
        val thr = (sum / px.size * 0.62).toInt()
        val dark = BooleanArray(px.size) { lum(px[it]) < thr }
        return lines(dark, w, h, true) + lines(dark, w, h, false)
    }

    /**
     * Ruled lines in a photo: they are never exactly straight (a slight slant or curl), so a pixel counts as on the
     * line when one of its 3 neighbours across the line is dark; runs may have gaps of a few pixels; consecutive rows
     * of runs with about the same ends make one rule, kept when thin and with paper on both sides.
     */
    internal fun lines(dark: BooleanArray, w: Int, h: Int, horizontal: Boolean): List<TableRules.Rule> {
        val len = if (horizontal) w else h; val n = if (horizontal) h else w
        fun d(a: Int, b: Int): Boolean = if (horizontal) dark[b * w + a] else dark[a * w + b]   // a = along, b = across
        fun band(a: Int, b: Int) = (b > 0 && d(a, b - 1)) || d(a, b) || (b < n - 1 && d(a, b + 1))
        val minLen = max(60, (len * 0.06f).toInt())
        val runs = Array(n) { ArrayList<IntArray>() }
        for (b in 0 until n) {
            var a = 0
            while (a < len) {
                if (!band(a, b)) { a++; continue }
                var e = a; var g = 0; var p = a + 1
                while (p < len) { if (band(p, b)) { e = p; g = 0 } else if (++g > 4) break; p++ }
                if (e - a + 1 >= minLen) runs[b] += intArrayOf(a, e)
                a = e + 1
            }
        }
        val used = Array(n) { BooleanArray(runs[it].size) }
        val out = ArrayList<TableRules.Rule>()
        for (b in 0 until n) for ((k, r) in runs[b].withIndex()) {
            if (used[b][k]) continue
            used[b][k] = true
            var s0 = r[0]; var e0 = r[1]; var last = b
            while (last + 1 < n) {
                val j = runs[last + 1].indices.firstOrNull { !used[last + 1][it] && abs(runs[last + 1][it][0] - s0) <= 14 && abs(runs[last + 1][it][1] - e0) <= 14 } ?: break
                used[last + 1][j] = true; s0 = min(s0, runs[last + 1][j][0]); e0 = max(e0, runs[last + 1][j][1]); last++
            }
            val thick = last - b + 1
            if (thick > 12) continue
            // the line itself: most positions along it really dark in the band
            var on = 0; var a2 = s0
            while (a2 <= e0) { if ((b..last).any { bb -> d(a2, bb) }) on++; a2++ }
            if (on < (e0 - s0 + 1) * 0.85f) continue
            // paper just outside it on both sides (not a text line, whose letters rise above and below)
            fun light(bb: Int): Boolean { if (bb !in 0 until n) return true; var l = 0; var t = 0; var q = s0; while (q <= e0) { t++; if (!d(q, bb)) l++; q += 3 }; return l * 10 >= t * 4 }
            if (!light(b - 4) && !light(last + 4)) continue
            out += if (horizontal) TableRules.Rule(true, s0, b, e0, last, 0) else TableRules.Rule(false, b, s0, last, e0, 0)
        }
        return out
    }

    /**
     * Regions to recognise one at a time (l, t, r, b): the page is cut into bands at its long horizontal rules, and a
     * band crossed by vertical rules is cut into its cells. Recognising the whole page as one block mixed the lines of
     * neighbouring table cells (word boxes as tall as the row, cell texts interleaved). Pure.
     */
    fun ocrRegions(rules: List<TableRules.Rule>, w: Int, h: Int): List<IntArray> {
        val hs = rules.filter { it.horizontal && it.x1 - it.x0 >= w * 0.3f }.map { (it.y0 + it.y1) / 2 }.sorted()
        val cuts = ArrayList<Int>().apply { add(0); hs.forEach { y -> if (y - last() > h / 120) add(y) }; if (h - last() > h / 120) add(h) else this[lastIndex] = h }
        val out = ArrayList<IntArray>()
        for (k in 0 until cuts.size - 1) {
            val t = cuts[k]; val b = cuts[k + 1]; if (b - t < h / 150) continue
            val xs = rules.filter { !it.horizontal && it.y0 <= t + (b - t) * 0.15f && it.y1 >= b - (b - t) * 0.15f }.map { (it.x0 + it.x1) / 2 }.sorted()
            val cx = ArrayList<Int>().apply { add(0); xs.forEach { x -> if (x - last() > w / 60) add(x) }; if (w - last() > w / 60) add(w) else this[lastIndex] = w }
            for (j in 0 until cx.size - 1) if (cx[j + 1] - cx[j] >= w / 40) out += intArrayOf(cx[j], t, cx[j + 1], b)
        }
        return out
    }

    /** A copy for OCR with the rules painted out (borders and table lines were read as junk letters "|", "7", "["). */
    fun forOcr(px: IntArray, w: Int, h: Int, rules: List<TableRules.Rule>): IntArray {
        val out = px.copyOf()
        for (r in rules) for (y in max(0, r.y0 - 2)..min(h - 1, r.y1 + 2)) for (x in max(0, r.x0 - 2)..min(w - 1, r.x1 + 2)) out[y * w + x] = -0x1
        return out
    }

    /** Recognised "words" that are noise: no letter or digit, low confidence fragments, stray marks. */
    fun junk(b: OcrBox): Boolean {
        val t = b.text.filterNot { it == '\u200e' || it == '\u200f' }.trim()
        val letters = t.count { it.isLetter() }; val digits = t.count { it.isDigit() }
        // question / answer labels (س١، ج٢) are short but real
        if (t.length <= 3 && t[0] in "سج" && t.drop(1).all { it in '٠'..'٩' || it == ':' }) return false
        // Latin and Arabic-Indic digits mixed in one token: an italic or underlined word misread (٠٠٠٠٠.7٦)
        if (t.any { it in '0'..'9' } && t.any { it in '٠'..'٩' }) return true
        // one letter four times running: a border or a pattern, not a word (للالللللكيل)
        if (Regex("(.)\\1{3}").containsMatchIn(t)) return true
        if (letters + digits == 0) return !(t.length == 1 && t[0] in ".:،؛؟?!()-–—*•➢") || b.conf < 60f
        if (b.conf < 30f) return true
        // a row of ornaments read as a few letters: the box is far wider than its letters could be
        if (b.r - b.l > (t.length + 1) * 2.5f * (b.b - b.t)) return true
        if (letters + digits <= 2 && b.conf < 55f) return true
        // a stray number read from a border or a page edge
        if (letters == 0 && digits <= 2 && b.conf < 70f) return true
        // a lone Latin letter or digit inside Arabic text is nearly always a misread border or mark
        if (t.length <= 2 && t.none { it in '؀'..'ۿ' } && b.conf < 80f) return true
        return false
    }

    fun page(index: Int, px: IntArray, w: Int, h: Int, words1: List<OcrBox>, arabicFont: String, latinFont: String,
             rules0: List<TableRules.Rule>? = null, lineOf: MutableMap<Int, Int>? = null, crop: (IntArray) -> Pair<ByteArray, String>?): PageContent {
        val (pw, ph) = pageSize(w, h)
        val s = pw / w
        val rules = rules0 ?: rules(px, w, h)
        // bidi marks the OCR adds are not text
        // the word that starts each line in reading order (the rightmost on an Arabic line, the leftmost on a Latin one)
        val lineStart = words1.groupBy { it.line }.values.mapNotNull { lw ->
            val ar = lw.count { w -> w.text.any { it in '؀'..'ۿ' } } * 2 >= lw.size
            if (ar) lw.maxByOrNull { it.r } else lw.minByOrNull { it.l }
        }.toSet()
        val clean = words1.map { wd ->
            val t = wd.text.filterNot { c -> c == '\u200e' || c == '\u200f' || c == '\u200b' }
            wd.copy(text = when {
                // a bold Arabic comma is read as Arabic zero: a lone ٠ (maybe with a dot) between words is the comma
                t.matches(Regex("[٠][.:]*")) -> "،"
                // list marks at the start of a line: an arrow bullet read as ">" / "»", a round one as "." / "·"
                wd in lineStart && wd.conf >= 60f && t in setOf(">", "<", "»", "«", "›", "‹", "➢", "➤") -> "➢"
                wd in lineStart && wd.conf >= 60f && t in setOf(".", "·", "•", "●", "o") -> "•"
                else -> t
            })
        }
        // an Arabic page: a Latin "word" read with low confidence is a misread Arabic word or a mark (EGG, Joey, OER…)
        val arabicPage = clean.count { w -> w.text.any { it in '؀'..'ۿ' } } * 10 >= clean.size * 6
        // a page numbered in Arabic-Indic digits: a Latin number read with less than high confidence is a misread word
        // (counted on confidently read words only: the misreads are what this test is for)
        val sure = clean.filter { it.conf >= 90f }
        val indicPage = sure.sumOf { w -> w.text.count { it in '٠'..'٩' } } > sure.sumOf { w -> w.text.count { it in '0'..'9' } }
        val kept = clean.filterNot { junk(it) || (arabicPage && it.conf < 80f && it.text.any { c -> c in 'A'..'Z' || c in 'a'..'z' }) ||
            (indicPage && it.conf < 90f && it.text.any { c -> c in '0'..'9' }) }
        // a whole line read with low confidence is a border, a pattern or a decorated title gone wrong
        // (judged on everything read on the line, the junk included; one surely read word of 3+ letters saves it)
        val lineOk = clean.groupBy { it.line }.mapValues { (_, lw) ->
            lw.map { it.conf }.sorted()[lw.size / 2] >= 50f || lw.any { it.conf >= 85f && it.text.count { c -> c.isLetter() } >= 3 }
        }
        val words0 = kept.filter { lineOk[it.line] != false }
        val figs = figures(px, w, h, words0, rules)
        // words inside a figure belong to the picture (labels of a diagram)
        val words = words0.filter { wd -> figs.none { f -> wd.l >= f[0] - 2 && wd.r <= f[2] + 2 && wd.t >= f[1] - 2 && wd.b <= f[3] + 2 } && wd.text.isNotBlank() }
        // per line: one size (the median box height), bold when most of its words are thick, colour when shared
        val strokes = words.associateWith { strokeRatio(px, w, it.l, it.t, it.r, it.b) }
        val byLine = words.groupBy { it.line }
        // the page's body size: most lines are body text; a line only gets its own size when clearly bigger or smaller
        // (box heights vary ±20 % with the letters a line happens to have)
        fun lineSize(lw: List<OcrBox>): Float {
            val ar = lw.any { wd -> wd.text.any { it in '؀'..'ۿ' } }
            val hPt = lw.map { (it.b - it.t) * s }.sorted()[lw.size / 2]
            return pointSize(hPt, ar)
        }
        val sizes = byLine.values.filter { it.size >= 2 }.map { lineSize(it) to it.size }
        val body = sizes.groupBy { it.first }.maxByOrNull { e -> e.value.sumOf { it.second } }?.key ?: 14f
        // stroke width in px; the page is "set in bold" when its usual stroke is thick for the body size
        val widths = words.associateWith { (strokes[it] ?: 0f) * (it.b - it.t + 1) }
        val emPx = body / s
        val pageStroke = widths.values.filter { it > 0f }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val pageBold = pageStroke >= BOLD_EM * emPx
        val glyphs = ArrayList<Glyph>()
        for ((_, lw) in byLine) {
            val arabic = lw.any { wd -> wd.text.any { it in '؀'..'ۿ' } }
            val own = lineSize(lw)
            // bold: thick strokes for this page (a heading among regular text), or thick in absolute terms (a page set in
            // bold all through, as Yemeni school books often are — then nothing stands out from the page's own norm)
            // measured as the absolute stroke width (OCR boxes are line-tall, so a ratio to the box height wanders)
            val lineStroke = lw.mapNotNull { widths[it]?.takeIf { v -> v > 0f } }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
            val bold = if (pageBold) lineStroke >= pageStroke * 0.8f else lineStroke >= max(BOLD_EM * emPx, pageStroke * 1.3f)
            // headings are clearly taller and printed bold; anything else is body text (a regular line measured tall
            // has an underline, diacritics or noise in its boxes; smaller boxes are short words or fragments)
            // on a page set in bold all through, a heading is also thicker than the page's usual stroke
            val heading = if (pageBold) lineStroke >= pageStroke * 1.15f else bold
            val size = if (own >= body * 1.45f && lw.size in 2..8 && heading) own else body
            val colors = lw.map { inkColor(px, w, it.l, it.t, it.r, it.b) }
            val color = colors.groupingBy { it }.eachCount().maxBy { it.value }.key.takeIf { c -> colors.count { it == c } * 2 > colors.size } ?: 0
            val sortedLw = lw.sortedBy { it.l }
            for ((k, wd) in sortedLw.withIndex()) {
                val wordArabic = wd.text.any { it in '؀'..'ۿ' }
                val base = wd.b * s - (wd.b - wd.t) * s * 0.18f
                glyphs += Glyph(x = wd.l * s, top = wd.t * s, w = (wd.r - wd.l) * s, h = (wd.b - wd.t) * s, baseline = base,
                    text = wd.text, fontSize = size, bold = bold, color = color, font = if (wordArabic || arabic) arabicFont else latinFont)
                lineOf?.set(glyphs.size - 1, lw.first().line)
                // a space between neighbouring words (before the next one's left edge, even when the boxes touch), so the
                // analyser never glues two recognised words together
                sortedLw.getOrNull(k + 1)?.let { nx -> glyphs += Glyph(x = min((wd.r + nx.l) / 2f, nx.l - 0.05f) * s, top = wd.t * s, w = 0.01f, h = (wd.b - wd.t) * s, baseline = base, text = " ", fontSize = size) }
            }
        }
        val segments = rules.map { r ->
            if (r.horizontal) { val y = (r.y0 + r.y1) / 2f * s; Segment(r.x0 * s, y, (r.x1 + 1) * s, y) }
            else { val x = (r.x0 + r.x1) / 2f * s; Segment(x, r.y0 * s, x, (r.y1 + 1) * s) }
        }
        val images = figs.mapNotNull { f -> crop(f)?.let { (bytes, ext) -> PageImage(f[0] * s, f[1] * s, (f[2] - f[0]) * s, (f[3] - f[1]) * s, bytes, ext) } }
        return PageContent(index, pw, ph, glyphs, segments, images)
    }

    /** Rules worth painting out before OCR: long ones only (a short "rule" may be the long stroke of a word). */
    fun longRules(rules: List<TableRules.Rule>, w: Int, h: Int) = rules.filter { if (it.horizontal) it.x1 - it.x0 >= w * 0.3f else it.y1 - it.y0 >= h * 0.08f }

    /** Short "rules" lying on a recognised word are its own strokes (a kashida), not lines of the page. */
    /**
     * Second reading (review: the page reader skipped whole printed lines — a title, «س٣», a line under a table): the
     * bands of ink inside each region that no recognised word covers, as crops (l, t, r, b) to read one line at a time
     * (the caller pads them with white: a neighbour line's edge must not be read). [clean] is the page as read (rules painted out).
     */
    fun missedLines(clean: IntArray, w: Int, h: Int, regions: List<IntArray>, words: List<OcrBox>): List<IntArray> {
        val out = ArrayList<IntArray>()
        val thr = 110
        val bodyH = words.map { it.b - it.t }.sorted().let { if (it.isEmpty()) h / 40 else it[it.size / 2] }.coerceAtLeast(8)
        for (r in regions) {
            val x0 = r[0]; val x1 = r[2]; val rw = x1 - x0
            if (rw < bodyH * 2) continue
            // rows with ink: at least a few dark pixels across the region
            val ink = BooleanArray(r[3] - r[1]) { i -> val y = r[1] + i; var n = 0; for (x in x0 until x1) if (lum(clean[y * w + x]) < thr) n++; n >= 3 }
            var i = 0
            while (i < ink.size) {
                if (!ink[i]) { i++; continue }
                var j = i; while (j < ink.size && (ink[j] || (j + 1 < ink.size && ink[j + 1]))) j++
                val t = r[1] + i; val b = r[1] + j
                if (b - t >= bodyH * 0.45f && b - t <= bodyH * 2.5f) {
                    val covered = words.filter { it.l < x1 && it.r > x0 && it.t < b && it.b > t }.sumOf { min(it.b, b) - max(it.t, t) }
                    if (covered < (b - t) / 3) {
                        var l = x1; var rr = x0
                        for (y in t until b) for (x in x0 until x1) if (lum(clean[y * w + x]) < thr) { if (x < l) l = x; if (x > rr) rr = x }
                        if (rr - l >= bodyH) out += intArrayOf(l, t, rr + 1, b)
                    }
                }
                i = j + 1
            }
        }
        return out
    }

    /** [band] of [clean] on a white margin of [pad] px (for [missedLines]). */
    fun padded(clean: IntArray, w: Int, band: IntArray, pad: Int = 20): Triple<IntArray, Int, Int> {
        val bw = band[2] - band[0]; val bh = band[3] - band[1]; val ow = bw + 2 * pad; val oh = bh + 2 * pad
        val out = IntArray(ow * oh) { -1 }
        for (y in 0 until bh) System.arraycopy(clean, (band[1] + y) * w + band[0], out, (y + pad) * ow + pad, bw)
        return Triple(out, ow, oh)
    }

    fun dropWordStrokes(rules: List<TableRules.Rule>, words: List<OcrBox>, w: Int, h: Int): List<TableRules.Rule> {
        val long = longRules(rules, w, h).toSet()
        return rules.filter { r -> r in long || words.none { b ->
            val ox = min(r.x1, b.r) - max(r.x0, b.l); val oy = min(r.y1, b.b) - max(r.y0, b.t)
            ox > 0 && oy > 0 && ox >= (r.x1 - r.x0) * 0.4f
        } }
    }

    /**
     * The page as the converter's layout, built from the recognition regions ([ocrRegions]): each recognised line is
     * its own paragraph, in order, as printed (so the page looks as in the book and edits stay local); rows of cells
     * become a Word table with borders; a small band closed by rules on all sides (a header / footer box) becomes a
     * one-cell table, so its frame is kept; figures are pictures at their place.
     */
    fun layout(index: Int, px: IntArray, w: Int, h: Int, words1: List<OcrBox>, arabicFont: String, latinFont: String,
               rules0: List<TableRules.Rule>, crop: (IntArray) -> Pair<ByteArray, String>?): PageLayout {
        val regions = ocrRegions(rules0, w, h)
        val glyphLine = HashMap<Int, Int>()
        val kept = dropWordStrokes(rules0, words1, w, h)
        val content = page(index, px, w, h, words1, arabicFont, latinFont, kept, glyphLine, crop)
        val s = content.width / w
        // short rules lying on words are not table lines: those under a word are its underline (Decorations keeps only
        // the ones at the baseline, so a letter's own long stroke read as a rule is not)
        val keptSet = kept.toSet()
        val under = rules0.filter { it.horizontal && it !in keptSet }.map { r -> val y = (r.y0 + r.y1) / 2f * s; Segment(r.x0 * s, y, (r.x1 + 1) * s, y) }
        fun inside(g: Glyph, r: IntArray) = g.x + g.w / 2 >= r[0] * s && g.x + g.w / 2 < r[2] * s && g.top + g.h / 2 >= r[1] * s && g.top + g.h / 2 < r[3] * s
        val blocks = ArrayList<Block>()
        content.images.forEach { blocks += ImageBlock(it) }
        fun linesIn(r: IntArray): List<Line> {
            // the words of each recognised line (spaces go with the word before them)
            val groups = LinkedHashMap<Int, ArrayList<Glyph>>()
            var cur = -1
            content.glyphs.forEachIndexed { gi, g -> glyphLine[gi]?.let { cur = it }; if (cur >= 0 && inside(g, r)) groups.getOrPut(cur) { ArrayList() } += g }
            // fragments of one printed row (split by an underline or a gap) join again: vertical overlap ≥ half
            val rows = ArrayList<ArrayList<Glyph>>()
            for (gs in groups.values.sortedBy { g -> g.minOf { it.top } }) {
                val t = gs.minOf { it.top }; val b = gs.maxOf { it.top + it.h }
                val row = rows.firstOrNull { rr -> val rt = rr.minOf { it.top }; val rb = rr.maxOf { it.top + it.h }; abs((t + b) - (rt + rb)) / 2f < 0.3f * min(b - t, rb - rt) }
                if (row != null) row += gs else rows += gs
            }
            return rows.mapNotNull { gs -> LayoutAnalyzer.buildLines(gs).let { ls -> if (ls.isEmpty()) null else if (ls.size == 1) ls[0] else LayoutAnalyzer.lineOf(ls.flatMap { it.words }) } }.sortedBy { it.top }
                .also { if (under.isNotEmpty()) Decorations.decorateWords(it, under, emptyList(), content.width) }
        }
        val bands = regions.groupBy { it[1] to it[3] }.toSortedMap(compareBy { it.first })
        val bodySize = content.glyphs.filter { it.text.isNotBlank() }.map { it.fontSize }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 14f
        // tables: runs of consecutive bands cut into 2+ real cells
        val keys = bands.keys.toList()
        var i = 0
        while (i < keys.size) {
            val cells0 = bands[keys[i]]!!.filter { it[2] - it[0] >= w / 12 }
            if (cells0.size >= 2) {
                var j = i
                while (j + 1 < keys.size && bands[keys[j + 1]]!!.count { it[2] - it[0] >= w / 12 } >= 2) j++
                val rowsK = keys.subList(i, j + 1)
                val xs = rowsK.flatMap { k -> bands[k]!!.filter { it[2] - it[0] >= w / 12 }.flatMap { listOf(it[0], it[2]) } }.sorted()
                    .fold(ArrayList<Int>()) { acc, x -> if (acc.isEmpty() || x - acc.last() > w / 60) acc += x; acc }
                val colX = xs.map { it * s }
                val rowY = (rowsK.map { it.first } + rowsK.last().second).map { it * s }
                val cells = ArrayList<TableCell>()
                rowsK.forEachIndexed { ri, k ->
                    bands[k]!!.filter { it[2] - it[0] >= w / 12 }.forEach { c ->
                        val c0 = xs.indexOfFirst { abs(it - c[0]) <= w / 60 }.coerceAtLeast(0)
                        val c1 = xs.indexOfFirst { abs(it - c[2]) <= w / 60 }.let { if (it <= c0) c0 + 1 else it }
                        cells += TableCell(ri, c0, 1, c1 - c0, Rect(c[0] * s, c[1] * s, c[2] * s, c[3] * s), linesIn(c))
                    }
                }
                blocks += TableBlock(colX, rowY, cells, ruled = true)
                i = j + 1; continue
            }
            val band = bands[keys[i]]!!
            val t = keys[i].first; val b = keys[i].second
            // a small framed band (header / footer box): rules close it on the left and right, not the page edges
            val main = band.maxBy { it[2] - it[0] }
            val framed = (b - t) < h * 0.12f && main[0] > 0 && main[2] < w && main[2] - main[0] > w / 2
            val lines = band.flatMap { linesIn(it) }.sortedBy { it.top }
            if (framed && lines.isNotEmpty()) {
                blocks += TableBlock(listOf(main[0] * s, main[2] * s), listOf(t * s, b * s), listOf(TableCell(0, 0, 1, 1, Rect(main[0] * s, t * s, main[2] * s, b * s), lines)), ruled = true)
            } else {
                val left = band.minOf { it[0] } * s; val right = band.maxOf { it[2] } * s
                for (l in lines) {
                    val gl = l.x0 - left; val gr = right - l.x1; val width = right - left
                    val align = when {
                        gl > width * 0.12f && gr > width * 0.12f && abs(gl - gr) < width * 0.08f -> Align.CENTER
                        l.rtl && gr > width * 0.25f && gl < width * 0.06f -> Align.END
                        !l.rtl && gl > width * 0.25f && gr < width * 0.06f -> Align.END
                        // a printed line from margin to margin: both edges, as in the book
                        gl < width * 0.06f && gr < width * 0.06f -> Align.JUSTIFY
                        else -> Align.START
                    }
                    blocks += ParagraphBlock(listOf(l), l.rtl, 0, align)
                }
            }
            i++
        }
        val ordered = blocks.sortedBy { it.rect.y0 }
        // the printed line pitch: the usual distance between consecutive body lines (gaps between sections left out)
        val tops = ordered.filterIsInstance<ParagraphBlock>().map { it.lines[0].top }.sorted()
        val steps = tops.zipWithNext { a, b -> b - a }.filter { it in bodySize * 0.9f..bodySize * 2.6f }.sorted()
        val pitch = if (steps.size >= 3) steps[steps.size / 2] else 0f
        return PageLayout(index, content.width, content.height, ordered, bodySize, hasFills = true, ocrChars = content.glyphs.sumOf { it.text.length }, linePitch = pitch)
    }

    /** Point size steps, for tests. */
    internal fun snapped(v: Float) = SIZES.minBy { abs(it - v) }.let { it.roundToInt() }
}
