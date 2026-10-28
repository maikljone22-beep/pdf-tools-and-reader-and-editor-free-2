package com.smartdocs.editor.core.formsx

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs

/**
 * Form field recognition on flat forms (Foxit "Run Form Field Recognition", Acrobat auto-detect; OLD_PLAN H1):
 * underscores, check-box glyphs, empty boxes and fill-in lines on a page become suggested fields named after the
 * label next to them. [detect] is pure geometry (unit tested); [scanPage] gathers the page's glyphs and vector
 * segments with PdfBox on the device.
 */
object FieldRecognition {

    data class Glyph(val text: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float)   // PDF space, y up
    data class Seg(val x0: Float, val y0: Float, val x1: Float, val y1: Float)
    data class Suggestion(val type: FxType, val rect: FxRect, val name: String, val label: String)

    /** OLD I1: the suggestion under PDF point ([x], [y]) — the smallest one when boxes overlap. */
    fun at(list: List<Suggestion>?, x: Float, y: Float): Suggestion? = list?.filter { s ->
        val r = s.rect
        x >= minOf(r.left, r.right) && x <= maxOf(r.left, r.right) && y >= minOf(r.bottom, r.top) && y <= maxOf(r.bottom, r.top)
    }?.minByOrNull { kotlin.math.abs((it.rect.right - it.rect.left) * (it.rect.top - it.rect.bottom)) }

    private val BOX_CHARS = setOf("\u2610", "\u25A1", "\u274F", "\u2751", "\u25A2", "\u25FB", "\u2B1C", "\u274E", "\u2612", "\u2611")
    private const val MIN_LINE = 36f

    private fun overlap(a: FxRect, b: FxRect): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left); val h = minOf(a.top, b.top) - maxOf(a.bottom, b.bottom)
        if (w <= 0f || h <= 0f) return 0f
        return (w * h) / minOf(a.width * a.height, b.width * b.height).coerceAtLeast(1f)
    }

    /** Name from a label: letters / digits kept, spaces become '_', at most 40 characters. */
    fun nameOf(label: String, fallback: String): String {
        val t = label.trim().trimEnd(':', '\uFF1A', '.', '*', ' ').trim()
        val s = buildString { t.forEach { c -> when { c.isLetterOrDigit() -> append(c); c == ' ' || c == '_' || c == '-' || c == '/' -> if (isNotEmpty() && last() != '_') append('_') } } }.trim('_')
        return s.take(40).ifEmpty { fallback }
    }

    private class Line(val glyphs: MutableList<Glyph>) {
        val base get() = glyphs.minOf { it.y0 }
        val top get() = glyphs.maxOf { it.y1 }
        val h get() = (top - base).coerceAtLeast(4f)
    }

    private fun lines(glyphs: List<Glyph>): List<Line> {
        val out = ArrayList<Line>()
        glyphs.filter { it.text.isNotBlank() || it.text == " " }.sortedWith(compareBy({ -it.y0 }, { it.x0 })).forEach { g ->
            val l = out.firstOrNull { abs(it.base - g.y0) < maxOf(2f, (g.y1 - g.y0) * 0.35f) }
            if (l != null) l.glyphs += g else out += Line(mutableListOf(g))
        }
        out.forEach { it.glyphs.sortBy { g -> g.x0 } }
        return out
    }

    /** Text of [line] left of [x] (the label of a field starting at x), up to the previous gap of a field. */
    private fun labelLeft(line: Line?, x: Float): String {
        if (line == null) return ""
        val left = line.glyphs.filter { it.x1 <= x + 1f && it.text != "_" }
        if (left.isEmpty()) return ""
        // stop at a big gap (another column) or at a previous underscore run
        val sb = ArrayList<Glyph>()
        for (g in left.asReversed()) {
            val prev = sb.lastOrNull()
            if (prev != null && prev.x0 - g.x1 > line.h * 2.5f) break
            if (prev == null && x - g.x1 > 220f) break
            sb += g
            if (sb.size > 60) break
        }
        return sb.asReversed().joinToString("") { it.text }.trim()
    }

    private fun labelRight(line: Line?, x: Float): String {
        if (line == null) return ""
        val right = line.glyphs.filter { it.x0 >= x - 1f && it.text !in BOX_CHARS }
        val sb = ArrayList<Glyph>()
        for (g in right) {
            val prev = sb.lastOrNull()
            if (prev != null && g.x0 - prev.x1 > line.h * 2.5f) break
            if (prev == null && g.x0 - x > 60f) break
            sb += g
            if (sb.size > 60) break
        }
        return sb.joinToString("") { it.text }.trim()
    }

    private fun lineAt(ls: List<Line>, y: Float, x: Float): Line? = ls.filter { y >= it.base - 4f && y <= it.top + 4f && it.glyphs.any { g -> g.x1 < x } }.minByOrNull { abs(it.base - y) }

    /**
     * Suggested fields of one page. [existing] are rects of fields already in the document (never suggested again).
     */
    fun detect(segs: List<Seg>, glyphs: List<Glyph>, pageW: Float, pageH: Float, existing: List<FxRect> = emptyList()): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        val ls = lines(glyphs)
        var nText = 0; var nCheck = 0
        fun add(type: FxType, r: FxRect, label: String) {
            if (r.width < 4f || r.height < 4f) return
            if (existing.any { overlap(it, r) > 0.3f } || out.any { overlap(it.rect, r) > 0.3f }) return
            val fallback = if (type == FxType.CHECKBOX) "Check${++nCheck}" else "Text${++nText}"
            out += Suggestion(type, r, nameOf(label, fallback), label)
        }
        // 1) underscore runs: "Name: ________"
        ls.forEach { line ->
            var i = 0; val gs = line.glyphs
            while (i < gs.size) {
                if (gs[i].text != "_") { i++; continue }
                val s = i
                while (i < gs.size && gs[i].text == "_" && (i == s || gs[i].x0 - gs[i - 1].x1 < line.h)) i++
                if (i - s >= 3) {
                    val x0 = gs[s].x0; val x1 = gs[i - 1].x1
                    val y = line.base
                    add(FxType.TEXT, FxRect(x0, y, x1, y + maxOf(12f, line.h * 1.4f)), labelLeft(line, x0))
                }
            }
        }
        // 2) check-box glyphs
        ls.forEach { line ->
            line.glyphs.filter { it.text in BOX_CHARS }.forEach { g ->
                val sz = maxOf(g.x1 - g.x0, g.y1 - g.y0).coerceAtLeast(8f)
                val cx = (g.x0 + g.x1) / 2; val cy = (g.y0 + g.y1) / 2
                add(FxType.CHECKBOX, FxRect(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2), labelRight(line, g.x1).ifEmpty { labelLeft(line, g.x0) })
            }
        }
        // 3) vector boxes and fill-in lines
        val hs = segs.filter { abs(it.y0 - it.y1) < 0.8f && abs(it.x1 - it.x0) >= 5f }.map { Seg(minOf(it.x0, it.x1), (it.y0 + it.y1) / 2, maxOf(it.x0, it.x1), (it.y0 + it.y1) / 2) }.take(3000)
        val vs = segs.filter { abs(it.x0 - it.x1) < 0.8f && abs(it.y1 - it.y0) >= 5f }.map { Seg((it.x0 + it.x1) / 2, minOf(it.y0, it.y1), (it.x0 + it.x1) / 2, maxOf(it.y0, it.y1)) }.take(3000)
        fun vAt(x: Float, b: Float, t: Float) = vs.any { abs(it.x0 - x) < 2f && it.y0 <= b + 2f && it.y1 >= t - 2f }
        val usedH = HashSet<Int>()
        val sortedH = hs.withIndex().sortedBy { it.value.y0 }
        for ((bi, bot) in sortedH) {
            for ((ti, top) in sortedH) {
                if (top.y0 <= bot.y0 + 5f || top.y0 - bot.y0 > 80f) continue
                if (abs(top.x0 - bot.x0) > 2f || abs(top.x1 - bot.x1) > 2f) continue
                if (!vAt(bot.x0, bot.y0, top.y0) || !vAt(bot.x1, bot.y0, top.y0)) continue
                val r = FxRect(bot.x0, bot.y0, bot.x1, top.y0)
                usedH += bi; usedH += ti
                val inside = glyphs.any { g -> g.text.isNotBlank() && r.contains((g.x0 + g.x1) / 2, (g.y0 + g.y1) / 2) }
                if (inside) break
                val w = r.width; val h = r.height
                if (w in 5f..26f && h in 5f..26f && abs(w - h) < 3f) {
                    val line = ls.filter { r.cy >= it.base - 4f && r.cy <= it.top + 4f }.minByOrNull { abs((it.base + it.top) / 2 - r.cy) }
                    add(FxType.CHECKBOX, r, labelRight(line, r.right).ifEmpty { labelLeft(line, r.left) })
                } else if (w >= 30f && h in 9f..80f && w < pageW * 0.97f) {
                    val line = lineAt(ls, r.cy, r.left) ?: ls.filter { it.base >= r.top && it.base - r.top < 20f && it.glyphs.any { g -> g.x0 < r.right && g.x1 > r.left } }.minByOrNull { it.base }
                    val label = labelLeft(line, r.left).ifEmpty { line?.glyphs?.filter { it.x0 < r.right && it.x1 > r.left - 4f }?.joinToString("") { it.text }?.trim().orEmpty() }
                    add(FxType.TEXT, FxRect(r.left + 1f, r.bottom + 1f, r.right - 1f, r.top - 1f), label)
                }
                break
            }
        }
        hs.forEachIndexed { i, l ->
            if (i in usedH || l.x1 - l.x0 < MIN_LINE || l.x1 - l.x0 > pageW * 0.9f) return@forEachIndexed
            // text standing on the line is printed content (a rule under a heading), not a blank to fill
            val band = FxRect(l.x0, l.y0, l.x1, l.y0 + 12f)
            if (glyphs.any { g -> g.text.isNotBlank() && g.text != "_" && band.contains((g.x0 + g.x1) / 2, (g.y0 + g.y1) / 2) }) return@forEachIndexed
            val above = hs.filter { it !== l && it.y0 > l.y0 + 2f && it.x0 < l.x1 && it.x1 > l.x0 }.minOfOrNull { it.y0 } ?: (l.y0 + 40f)
            val h = minOf(18f, above - l.y0 - 1f).coerceAtLeast(10f)
            val line = ls.filter { it.base >= l.y0 - 2f && it.base <= l.y0 + h && it.glyphs.any { g -> g.x1 <= l.x0 + 1f } }.minByOrNull { abs(it.base - l.y0) }
            add(FxType.TEXT, FxRect(l.x0, l.y0 + 1f, l.x1, l.y0 + h), labelLeft(line, l.x0))
        }
        // reading order: top to bottom, then left to right
        return out.filter { it.rect.top <= pageH + 1f && it.rect.left >= -1f }.sortedWith(compareBy({ -Math.round(it.rect.top / 6f) }, { it.rect.left }))
    }

    // ---------------- page scan (device) ----------------

    private class Collector : PDFTextStripper() {
        val out = ArrayList<Glyph>()
        var pageH = 0f
        override fun processTextPosition(t: TextPosition) {
            val u = t.unicode ?: return
            val x = t.xDirAdj; val w = t.widthDirAdj
            val h = t.heightDir.takeIf { it.isFinite() && it > 0f } ?: (t.fontSizeInPt * 0.7f)
            val yBase = pageH - t.yDirAdj
            if (!x.isFinite() || !yBase.isFinite()) return
            out += Glyph(u, x, yBase, x + w, yBase + h)
        }
    }

    /** Glyphs (PDF space) of page [index]. Rotated pages are read in their unrotated user space. */
    fun glyphs(doc: PDDocument, index: Int): List<Glyph> {
        val c = Collector()
        val page = doc.getPage(index)
        c.pageH = page.cropBox.height + page.cropBox.lowerLeftY
        c.setSortByPosition(false)
        c.setStartPage(index + 1); c.setEndPage(index + 1)
        runCatching { c.getText(doc) }
        return c.out
    }

    /** Suggestions for page [index] of [doc] (device: uses PdfBox graphics and text extraction). */
    fun scanPage(doc: PDDocument, index: Int): List<Suggestion> {
        val page = doc.getPage(index)
        val segs = runCatching { com.smartdocs.editor.core.view5.PageGraphics.scan(doc, index).segments.map { (a, b) -> Seg(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat()) } }.getOrDefault(emptyList())
        val existing = FxEditor.readAll(doc).flatMap { s -> s.widgets.filter { it.page == index }.map { it.rect } }
        return detect(segs, glyphs(doc, index), page.mediaBox.width, page.mediaBox.height, existing)
    }

    /** Create text fields / check boxes for [suggestions] on [page]. Returns their names. */
    /**
     * Create PDF → form (Nitro preference "run form field recognition when creating PDFs"): fields for the likely
     * blanks of every page (up to [maxPages]) of a document that has no form fields yet; names are made unique over
     * the document. Returns how many fields were created.
     */
    fun recognizeAll(doc: PDDocument, maxPages: Int = 200): Int {
        if (doc.documentCatalog.acroForm?.fields?.isNotEmpty() == true) return 0
        val used = HashSet<String>()
        var n = 0
        for (p in 0 until minOf(doc.numberOfPages, maxPages)) {
            val found = runCatching { scanPage(doc, p) }.getOrDefault(emptyList())
            if (found.isEmpty()) continue
            val named = found.map { s -> s.copy(name = uniqueName(s.name, used)) }
            n += runCatching { apply(doc, p, named).size }.getOrDefault(0)
        }
        return n
    }

    /** [name], or name_2, name_3 … when [used] already has it (the chosen name is added to [used]). */
    fun uniqueName(name: String, used: MutableSet<String>): String {
        val base = name.ifBlank { "field" }
        var c = base; var k = 2
        while (!used.add(c)) c = "${base}_${k++}"
        return c
    }

    fun apply(doc: PDDocument, page: Int, suggestions: List<Suggestion>, defaults: FieldSpec? = null): List<String> = suggestions.map { s ->
        val base = defaults?.takeIf { it.type == s.type } ?: FieldSpec(name = s.name, type = s.type, widgets = emptyList())
        FxEditor.create(doc, base.copy(name = s.name, type = s.type, tooltip = s.label, widgets = listOf(FxWidget(page, s.rect)),
            borderColor = if (s.type == FxType.CHECKBOX) 0x000000 else null, borderWidth = if (s.type == FxType.CHECKBOX) 1f else 0f))
    }
}
