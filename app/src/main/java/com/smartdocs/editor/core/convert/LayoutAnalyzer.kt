package com.smartdocs.editor.core.convert

import kotlin.math.abs

/**
 * Turns the positioned glyphs of a page into lines, words, paragraphs, headings and tables.
 * Pure Kotlin: no Android / PdfBox types.
 */
object LayoutAnalyzer {

    /** Word break when the horizontal gap between glyphs exceeds this fraction of the font size. */
    private const val WORD_GAP = 0.18f
    /** Column ("chunk") break inside a line when the gap exceeds this fraction of the font size. */
    const val CHUNK_GAP = 1.2f

    fun analyze(page: PageContent, detectTables: Boolean = true): PageLayout {
        val lines = buildLines(page.glyphs)
        val body = bodyFontSize(lines)
        val blocks = ArrayList<Block>()
        var rest = lines
        if (detectTables) {
            val ruled = TableDetector.ruled(page.segments, rest)
            blocks += ruled.tables
            rest = removeWords(rest, ruled.usedWords)
            val stream = TableDetector.stream(rest)
            blocks += stream.tables
            rest = rest.filter { it !in stream.usedLines }
        }
        blocks += paragraphs(rest, body, page.width)
        page.images.forEach { blocks += ImageBlock(it) }
        // reading order: top → bottom; blocks starting on the same line go right → left on RTL pages
        val rtlPage = lines.sumOf { if (it.rtl) it.text.length else 0 } * 2 > lines.sumOf { it.text.length }
        blocks.sortWith(compareBy<Block>({ Math.round(it.rect.y0 / 4f) }, { if (rtlPage) -it.rect.x1 else it.rect.x0 }))
        return PageLayout(page.index, page.width, page.height, blocks, body)
    }

    // ---------------------------------------------------------------- lines & words

    fun buildLines(raw: List<Glyph>): List<Line> {
        val glyphs = ArrayList<Glyph>(raw.size)
        val marks = ArrayList<Glyph>()
        for (g in raw) {
            val t = BidiText.normalize(g.text)
            if (t.isEmpty() || g.fontSize <= 0f || !g.x.isFinite() || !g.baseline.isFinite()) continue
            val n = if (t == g.text) g else g.copy(text = t)
            if (BidiText.isMark(t)) marks += n else glyphs += n
        }
        if (glyphs.isEmpty()) return emptyList()
        glyphs.sortWith(compareBy<Glyph>({ it.baseline }, { it.x }))
        // cluster by baseline
        val groups = ArrayList<MutableList<Glyph>>()
        var cur = ArrayList<Glyph>(); var base = glyphs[0].baseline; var fs = glyphs[0].fontSize
        for (g in glyphs) {
            val tol = 0.35f * minOf(fs, g.fontSize).coerceAtLeast(2f)
            if (cur.isNotEmpty() && abs(g.baseline - base) > tol) { groups += cur; cur = ArrayList(); }
            if (cur.isEmpty()) { base = g.baseline; fs = g.fontSize }
            cur += g
        }
        if (cur.isNotEmpty()) groups += cur
        // dedupe fake-bold double drawing and sort each line left → right
        val sorted = groups.map { grp ->
            grp.sortBy { it.x }
            val out = ArrayList<Glyph>(grp.size)
            for (g in grp) {
                val p = out.lastOrNull()
                if (p != null && p.text == g.text && abs(p.x - g.x) < 0.6f && abs(p.baseline - g.baseline) < 0.6f) continue
                out += g
            }
            out
        }.toMutableList()
        // attach combining marks (harakat) to the glyph they sit on
        for (m in marks) {
            var bestL = -1; var bestD = Float.MAX_VALUE
            sorted.forEachIndexed { i, l -> val d = abs(l[0].baseline - m.baseline); if (d < bestD) { bestD = d; bestL = i } }
            if (bestL < 0 || bestD > 1.2f * m.fontSize.coerceAtLeast(4f)) continue
            val l = sorted[bestL]
            var bi = -1; var bd = Float.MAX_VALUE
            l.forEachIndexed { i, g -> if (!g.text.isBlank()) { val d = if (m.cx >= g.x && m.cx <= g.right) 0f else abs(g.cx - m.cx); if (d < bd) { bd = d; bi = i } } }
            if (bi >= 0) l[bi] = l[bi].copy(text = l[bi].text + m.text)
        }
        return sorted.mapNotNull { lineFromGlyphs(it) }
    }

    /** Split a left → right sorted glyph run into words and order them logically. */
    private fun lineFromGlyphs(gs: List<Glyph>): Line? {
        val words = ArrayList<Word>()
        var cur = ArrayList<Glyph>()
        for (g in gs) {
            if (g.text.isBlank()) { if (cur.isNotEmpty()) { words += word(cur); cur = ArrayList() }; continue }
            val p = cur.lastOrNull()
            if (p != null && g.x - p.right > WORD_GAP * minOf(p.fontSize, g.fontSize)) { words += word(cur); cur = ArrayList() }
            cur += g
        }
        if (cur.isNotEmpty()) words += word(cur)
        return if (words.isEmpty()) null else lineOf(words)
    }

    private fun word(gs: List<Glyph>): Word {
        val text = gs.joinToString("") { it.text }
        val rtl = BidiText.dirOf(text) == BidiText.Dir.R
        val ordered = BidiText.visualToLogical(gs, rtl) { g -> BidiText.dirOf(g.text) }
        return Word(gs, BidiText.fixTanween(ordered.joinToString("") { it.text }), rtl)
    }

    /** Build a line from words (any order). */
    fun lineOf(words: List<Word>): Line {
        val visual = words.sortedBy { it.x0 }
        val rtl = BidiText.rtlDominant(visual.joinToString("") { it.text })
        val logical = BidiText.visualToLogical(visual, rtl) { BidiText.dirOf(it.text) }
        val bl = visual.flatMap { w -> w.glyphs.map { it.baseline } }.sorted()
        return Line(visual, logical, rtl, bl[bl.size / 2])
    }

    /** Group loose words (e.g. of one table cell) into lines top → bottom. */
    fun linesOfWords(words: List<Word>): List<Line> {
        if (words.isEmpty()) return emptyList()
        val sorted = words.sortedBy { it.glyphs[0].baseline }
        val out = ArrayList<Line>()
        var cur = ArrayList<Word>(); var base = sorted[0].glyphs[0].baseline
        for (w in sorted) {
            val b = w.glyphs[0].baseline
            if (cur.isNotEmpty() && abs(b - base) > 0.4f * w.fontSize) { out += lineOf(cur); cur = ArrayList() }
            if (cur.isEmpty()) base = b
            cur += w
        }
        if (cur.isNotEmpty()) out += lineOf(cur)
        return out
    }

    /** Column chunks of a line: runs of words separated by gaps wider than [CHUNK_GAP] × font size. */
    fun chunks(line: Line): List<List<Word>> {
        val out = ArrayList<List<Word>>()
        var cur = ArrayList<Word>()
        for (w in line.visual) {
            val p = cur.lastOrNull()
            if (p != null && w.x0 - p.x1 > CHUNK_GAP * minOf(p.fontSize, w.fontSize)) { out += cur; cur = ArrayList() }
            cur += w
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun removeWords(lines: List<Line>, used: Set<Word>): List<Line> {
        if (used.isEmpty()) return lines
        return lines.mapNotNull { l ->
            if (l.visual.none { it in used }) l
            else l.visual.filter { it !in used }.takeIf { it.isNotEmpty() }?.let { lineOf(it) }
        }
    }

    // ---------------------------------------------------------------- paragraphs

    fun bodyFontSize(lines: List<Line>): Float {
        val hist = HashMap<Int, Int>()
        lines.forEach { l -> l.words.forEach { w -> val k = (w.fontSize * 2).toInt(); hist[k] = (hist[k] ?: 0) + w.text.length } }
        val best = hist.maxByOrNull { it.value }?.key ?: return 12f
        return best / 2f
    }

    private fun headingLevel(l: Line, body: Float): Int {
        val ratio = l.fontSize / body
        val len = l.text.length
        return when {
            len > 160 -> 0
            ratio >= 1.7f -> 1
            ratio >= 1.3f -> 2
            ratio >= 1.12f && l.bold -> 3
            else -> 0
        }
    }

    /** Gutter between text columns: a gap of this many font sizes splits a line into separate lines. */
    private const val GUTTER = 2.5f

    /** Splits lines at column gutters so two-column layouts become two flows. */
    fun splitColumns(lines: List<Line>): List<Line> = lines.flatMap { l ->
        val parts = ArrayList<MutableList<Word>>()
        for (w in l.visual) {
            val p = parts.lastOrNull()?.last()
            if (p == null || w.x0 - p.x1 > GUTTER * minOf(p.fontSize, w.fontSize)) parts += mutableListOf(w) else parts.last() += w
        }
        if (parts.size == 1) listOf(l) else parts.map { lineOf(it) }
    }

    private class Para(val lines: MutableList<Line>, val level: Int)

    fun paragraphs(lines: List<Line>, body: Float, pageWidth: Float): List<ParagraphBlock> {
        val sorted = splitColumns(lines).sortedWith(compareBy<Line>({ it.top }, { it.x0 }))
        val open = ArrayList<Para>()
        val all = ArrayList<Para>()
        for (l in sorted) {
            val level = headingLevel(l, body)
            // the open paragraph whose last line sits directly above this one
            val target = open.lastOrNull { para ->
                val p = para.lines.last()
                para.level == level && p.rtl == l.rtl &&
                    abs(l.fontSize - p.fontSize) <= 0.15f * p.fontSize &&
                    l.top - p.bottom < 0.9f * p.fontSize && l.top - p.bottom > -0.5f * p.fontSize &&
                    l.x0 < p.x1 && l.x1 > p.x0 &&
                    (level > 0 || !endsParagraph(p, para.lines))
            }
            // paragraphs that can no longer grow (this line is well below them) are closed
            open.removeAll { para -> l.top - para.lines.last().bottom > 2f * para.lines.last().fontSize }
            if (target != null) target.lines += l
            else { val np = Para(mutableListOf(l), level); open += np; all += np }
        }
        return all.map { para ->
            val rtl = para.lines.count { it.rtl } * 2 > para.lines.size
            ParagraphBlock(para.lines, rtl, para.level, alignOf(para.lines, rtl, pageWidth))
        }
    }

    /** True when [p] is noticeably shorter than the paragraph width so far (last line of a paragraph). */
    private fun endsParagraph(p: Line, para: List<Line>): Boolean {
        if (para.size < 2) return false
        val w = para.maxOf { it.x1 } - para.minOf { it.x0 }
        val t = p.text.trimEnd()
        return (p.x1 - p.x0) < 0.7f * w && (t.endsWith('.') || t.endsWith(':') || t.endsWith('؟') || t.endsWith('?') || t.endsWith('!'))
    }

    private fun alignOf(lines: List<Line>, rtl: Boolean, pageWidth: Float): Align {
        val pc = pageWidth / 2f
        val centered = lines.all { abs((it.x0 + it.x1) / 2f - pc) < 0.04f * pageWidth && (it.x1 - it.x0) < 0.85f * pageWidth }
        if (centered && lines.any { it.x0 > 0.15f * pageWidth }) return Align.CENTER
        val lSpread = lines.maxOf { it.x0 } - lines.minOf { it.x0 }
        val rSpread = lines.maxOf { it.x1 } - lines.minOf { it.x1 }
        if (lines.size >= 2) {
            if (!rtl && rSpread < 2f && lSpread > 6f) return Align.END
            if (rtl && lSpread < 2f && rSpread > 6f) return Align.END
        } else {
            val l = lines[0]
            if (!rtl && l.x0 > 0.55f * pageWidth && l.x1 > 0.85f * pageWidth) return Align.END
            if (rtl && l.x1 < 0.45f * pageWidth && l.x0 < 0.15f * pageWidth) return Align.END
        }
        return Align.START
    }

    /** First row of [t] looks like a header: all its cells are text and (bold, or numbers appear below). */
    fun headerLikely(t: TableBlock): Boolean {
        if (t.rows < 2) return false
        val first = t.cells.filter { it.row == 0 && it.lines.isNotEmpty() }
        if (first.isEmpty() || first.any { BidiText.parseNumber(it.text) != null }) return false
        if (first.all { it.bold }) return true
        val below = t.cells.filter { it.row > 0 && it.lines.isNotEmpty() }
        return below.count { BidiText.parseNumber(it.text) != null } * 4 >= below.size
    }
}
