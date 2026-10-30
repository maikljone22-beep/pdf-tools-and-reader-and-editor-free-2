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

    /** [streamTables] false: only ruled (lined) tables, no tables found from text alignment alone (s6 complex-table threshold). */
    fun analyze(page: PageContent, detectTables: Boolean = true, furniture: PageFurniture = PageFurniture.NONE, streamTables: Boolean = true): PageLayout {
        val all = buildLines(page.glyphs)
        val body = bodyFontSize(all)
        val header = if (furniture.isEmpty) emptyList() else all.filter { furniture.isHeader(it, page.height, body) }
        val footer = if (furniture.isEmpty) emptyList() else all.filter { furniture.isFooter(it, page.height, body) }
        val lines = if (header.isEmpty() && footer.isEmpty()) all else all.filter { it !in header && it !in footer }
        Decorations.decorateWords(all, page.segments, page.fills, page.width)
        val blocks = ArrayList<Block>()
        var rest = lines
        if (detectTables) {
            val ruled = TableDetector.ruled(page.segments, rest)
            blocks += ruled.tables
            rest = removeWords(rest, ruled.usedWords)
            if (streamTables) {
                val stream = TableDetector.stream(rest)
                blocks += stream.tables
                rest = rest.filter { it !in stream.usedLines }
            }
        }
        blocks += paragraphs(rest, body, page.width)
        val fills = page.fills.filter { !Decorations.isBackground(it.color) }
        if (fills.isNotEmpty()) for (b in blocks) when (b) {
            is TableBlock -> {
                Decorations.fillCells(b, fills)
                // a cell fill is not a highlight of the words in it
                b.cells.forEach { c -> c.fill?.let { f -> c.lines.forEach { l -> l.visual.forEach { w -> if (w.bg == f) w.bg = null } } } }
            }
            is ParagraphBlock -> {
                Decorations.shadeParagraph(b, fills)
                b.shade?.let { f -> b.lines.forEach { l -> l.visual.forEach { w -> if (w.bg == f) w.bg = null } } }
            }
            else -> Unit
        }
        page.images.filter { keepImage(it, all, page) }.forEach { blocks += ImageBlock(it) }
        // reading order: recursive XY-cut (columns left → right, right → left on Arabic pages)
        val rtlPage = lines.sumOf { if (it.rtl) it.text.length else 0 } * 2 > lines.sumOf { it.text.length }
        val ordered = ReadingOrder.order(blocks, rtlPage)
        return PageLayout(page.index, page.width, page.height, ordered, body, header, footer, hasFills = fills.isNotEmpty(),
            rebuiltByOcr = page.rebuiltByOcr, ocrChars = lines.sumOf { l -> l.words.sumOf { if (it.ocr) it.text.length else 0 } }, comments = page.comments)
    }

    /**
     * An image is exported unless it is a background under the page's text: a picture of at least [BACKGROUND_SHARE] of
     * the page that text lines cover for at least [BACKGROUND_COVER] of its area — a watermark logo behind the body, the
     * scanned page under its OCR text, the page picture of a page an older OCR build rebuilt (its words painted out).
     * Word shows inline pictures before the text, so such a picture used to push a full copy of the page above the text.
     */
    fun keepImage(img: PageImage, lines: List<Line>, page: PageContent): Boolean {
        val area = img.w * img.h
        if (area < BACKGROUND_SHARE * page.width * page.height) return true
        val r = Rect(img.x, img.top, img.x + img.w, img.top + img.h)
        var covered = 0f
        for (l in lines) {
            val w = minOf(r.x1, l.x1) - maxOf(r.x0, l.x0); val h = minOf(r.y1, l.bottom) - maxOf(r.y0, l.top)
            if (w > 0f && h > 0f) covered += w * h
        }
        // line boxes are thin (glyph heights): weigh them up to the line pitch
        return covered * 1.6f < BACKGROUND_COVER * area
    }

    private const val BACKGROUND_SHARE = 0.15f
    private const val BACKGROUND_COVER = 0.35f

    // ---------------------------------------------------------------- lines & words

    fun buildLines(raw: List<Glyph>): List<Line> {
        val glyphs = ArrayList<Glyph>(raw.size)
        val ocr = ArrayList<Glyph>()
        val marks = ArrayList<Glyph>()
        for (g in raw) {
            // a tatweel (kashida) glyph stays: it joins the letters around it, and dropping it split «مثـــال» into «مث ال»
            val t = if (g.text.isNotEmpty() && g.text.all { it == TATWEEL }) g.text else BidiText.normalize(g.text)
            if (t.isEmpty() || g.fontSize <= 0f || !g.x.isFinite() || !g.baseline.isFinite()) continue
            val n = if (t == g.text) g else g.copy(text = t)
            if (BidiText.isMark(t)) marks += n else if (n.ocr) ocr += n else glyphs += n
        }
        if (glyphs.isEmpty() && ocr.isEmpty()) return emptyList()
        // typeset text and recognised (OCR) text are grouped separately: OCR words carry box heights as sizes and box
        // bottoms as baselines, so each OCR line gets one size and one baseline first, then lines beside each other join
        val sorted = (byBaseline(glyphs) + byBaseline(ocr).map { normaliseOcr(it) }).toMutableList()
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
        return OcrText.cleanLines(joinBands(sorted.mapNotNull { lineFromGlyphs(it) }))
    }

    private const val TATWEEL = '\u0640'

    /** Glyphs clustered by baseline, each cluster sorted left → right with fake-bold double drawing removed. */
    private fun byBaseline(glyphs: MutableList<Glyph>): List<MutableList<Glyph>> {
        if (glyphs.isEmpty()) return emptyList()
        glyphs.sortWith(compareBy<Glyph>({ it.baseline }, { it.x }))
        val groups = ArrayList<MutableList<Glyph>>()
        var cur = ArrayList<Glyph>(); var base = glyphs[0].baseline; var fs = glyphs[0].fontSize
        for (g in glyphs) {
            val tol = 0.35f * minOf(fs, g.fontSize).coerceAtLeast(2f)
            if (cur.isNotEmpty() && abs(g.baseline - base) > tol) { groups += cur; cur = ArrayList(); }
            if (cur.isEmpty()) { base = g.baseline; fs = g.fontSize }
            cur += g
        }
        if (cur.isNotEmpty()) groups += cur
        return groups.map { grp ->
            grp.sortBy { it.x }
            val out = ArrayList<Glyph>(grp.size)
            for (g in grp) {
                val p = out.lastOrNull()
                if (p != null && p.text == g.text && abs(p.x - g.x) < 0.6f && abs(p.baseline - g.baseline) < 0.6f) continue
                out += g
            }
            out
        }
    }

    /** One size ([OcrText.BOX_TO_SIZE] × the median box height) and the median baseline for a line of OCR glyphs. */
    private fun normaliseOcr(line: MutableList<Glyph>): MutableList<Glyph> {
        val fs = (line.map { it.fontSize }.sorted()[line.size / 2] * OcrText.BOX_TO_SIZE).let { Math.round(it * 2f) / 2f }.coerceAtLeast(4f)
        val base = line.map { it.baseline }.sorted()[line.size / 2]
        val h = fs * 0.7f
        return line.mapTo(ArrayList(line.size)) { it.copy(fontSize = fs, baseline = base, top = base - h, h = h) }
    }

    /**
     * Joins lines that stand side by side on one visual line: sub- and superscripts of formulas («CH4(g)» drawn as
     * «CH» + a lowered «4(g)»), recognised words next to typeset ones (a page whose text is part pictures), runs a producer
     * wrote at slightly different heights. Two lines join when their bands overlap by half the smaller one — or one is a
     * smaller-font script whose middle lies within the other's band — and no word of one stands above a word of the other.
     */
    fun joinBands(lines: List<Line>): List<Line> {
        if (lines.size < 2) return lines
        val work = lines.sortedBy { it.top }.toMutableList()
        var changed = true
        var guard = 0
        while (changed && guard++ < 8) {
            changed = false
            var i = 0
            while (i < work.size) {
                var j = i + 1
                while (j < work.size && work[j].top <= work[i].bottom + 0.5f * work[i].fontSize) {
                    val a = work[i]; val b = work[j]
                    if (sideBySide(a, b)) {
                        // words are cut again from all glyphs: a subscript touching its letters joins their word («CH» + «4(g)»)
                        work[i] = lineFromGlyphs((a.visual + b.visual).flatMap { it.glyphs }.sortedBy { it.x }) ?: lineOf(a.visual + b.visual)
                        work.removeAt(j); changed = true
                        continue
                    }
                    j++
                }
                i++
            }
            work.sortBy { it.top }
        }
        return work
    }

    private fun sideBySide(a: Line, b: Line): Boolean {
        val ov = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val ha = a.bottom - a.top; val hb = b.bottom - b.top
        val band = ov > 0.5f * minOf(ha, hb).coerceAtLeast(1f)
        val (big, small) = if (a.fontSize >= b.fontSize) a to b else b to a
        val mid = (small.top + small.bottom) / 2f
        val script = small.fontSize <= 0.85f * big.fontSize && mid >= big.top - 0.3f * big.fontSize && mid <= big.bottom + 0.4f * big.fontSize
        if (!band && !script) return false
        // stacked, not side by side: a word of one line stands over a word of the other (fractions, duplicated text).
        // Symbols («×», «=», «→») do not count: OCR reads arrows and rules drawn as lines into letters lying on them.
        for (u in a.visual) {
            if (u.text.none { it.isLetterOrDigit() }) continue
            for (v in b.visual) {
                if (v.text.none { it.isLetterOrDigit() }) continue
                val w = minOf(u.x1, v.x1) - maxOf(u.x0, v.x0)
                if (w > 0.6f * minOf(u.x1 - u.x0, v.x1 - v.x0).coerceAtLeast(0.5f)) return false
            }
        }
        // far apart on the line: different columns stay separate lines
        val gap = if (a.x1 < b.x0) b.x0 - a.x1 else if (b.x1 < a.x0) a.x0 - b.x1 else 0f
        return gap <= GUTTER * big.fontSize
    }

    /** Split a left → right sorted glyph run into words and order them logically. */
    private fun lineFromGlyphs(gs: List<Glyph>): Line? {
        val words = ArrayList<Word>()
        var cur = ArrayList<Glyph>()
        for (g in gs) {
            if (g.text.isBlank()) { if (cur.isNotEmpty()) { word(cur)?.let { words += it }; cur = ArrayList() }; continue }
            val p = cur.lastOrNull()
            // a recognised word and a typeset glyph never form one word
            if (p != null && (g.x - p.right > WORD_GAP * minOf(p.fontSize, g.fontSize) || p.ocr != g.ocr)) { word(cur)?.let { words += it }; cur = ArrayList() }
            cur += g
        }
        if (cur.isNotEmpty()) word(cur)?.let { words += it }
        return if (words.isEmpty()) null else lineOf(words)
    }

    private fun word(gs: List<Glyph>): Word? {
        val text = gs.joinToString("") { it.text }
        // a recognised Arabic word was written in the OCR layer's visual order: undo exactly that (brackets included)
        if (gs.count { it.ocr } * 2 > gs.size && text.any { BidiText.isArabicScript(it) }) {
            val t = BidiText.ocrLogical(gs.map { it.text }).replace(TATWEEL.toString(), "")
            return if (t.isEmpty()) null else Word(gs, t, BidiText.dirOf(t) == BidiText.Dir.R || t.any { BidiText.isArabicScript(it) })
        }
        val rtl = BidiText.dirOf(text) == BidiText.Dir.R
        val ordered = BidiText.visualToLogical(gs, rtl) { g -> BidiText.dirOf(g.text) }
        val t = BidiText.fixTanween(ordered.joinToString("") { it.text }.replace(TATWEEL.toString(), ""))
        return if (t.isEmpty()) null else Word(gs, t, rtl)
    }

    /** Build a line from words (any order). [baseline] null = the median glyph baseline. */
    fun lineOf(words: List<Word>, baseline: Float? = null): Line {
        val visual = words.sortedBy { it.x0 }
        val rtl = BidiText.rtlDominant(visual.joinToString("") { it.text })
        val logical = BidiText.visualToLogical(visual, rtl) { BidiText.dirOf(it.text) }
        val bl = baseline ?: visual.flatMap { w -> w.glyphs.map { it.baseline } }.sorted().let { it[it.size / 2] }
        return Line(visual, logical, rtl, bl)
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
        // a heading has words: «م», «.», «=», «)1» in a bigger font are symbols or pieces, not headings
        val letters = l.text.count { it.isLetter() }
        if (len > 160 || letters < 3) return 0
        // recognised (OCR) sizes come from word boxes: only a clearly bigger line counts, and OCR has no bold
        if (l.ocr) return when { ratio >= 1.9f -> 1; ratio >= 1.5f -> 2; else -> 0 }
        return when {
            ratio >= 1.7f -> 1
            ratio >= 1.3f -> 2
            ratio >= 1.12f && l.bold -> 3
            else -> 0
        }
    }

    /** Gutter between text columns: a gap of this many font sizes splits a line into separate lines. */
    private const val GUTTER = 2.5f

    /** A gap this many font sizes wide splits a line even without a column gutter through neighbouring lines. */
    private const val WIDE_GAP = 6f

    /**
     * Splits lines at column gutters so multi-column layouts become separate flows: gaps that line up with a
     * [ReadingOrder.rivers] gutter over several lines, or wider than [WIDE_GAP] font sizes. A wide gap on its own line
     * is spacing inside the line («المعطيات : كتلة الميثان = ٤ جم ،      حرارة احتراق …», answer choices, a formula
     * spread over the width): splitting there ([GUTTER] font sizes before) cut such lines into pieces and every
     * piece became its own paragraph.
     */
    fun splitColumns(lines: List<Line>, rivers: List<Pair<Float, Float>> = ReadingOrder.rivers(lines)): List<Line> = lines.flatMap { l ->
        val parts = ArrayList<MutableList<Word>>()
        for (w in l.visual) {
            val p = parts.lastOrNull()?.last()
            val gap = if (p == null) 0f else w.x0 - p.x1
            val river = p != null && gap > 0.5f * minOf(p.fontSize, w.fontSize) && rivers.any { r -> p.x1 <= r.second + 0.5f && w.x0 >= r.first - 0.5f && minOf(w.x0, r.second) - maxOf(p.x1, r.first) > 0f }
            if (p == null || gap > WIDE_GAP * maxOf(p.fontSize, w.fontSize) || river) parts += mutableListOf(w) else parts.last() += w
        }
        if (parts.size == 1) listOf(l) else parts.map { lineOf(it) }
    }

    private class Para(val lines: MutableList<Line>, val level: Int)

    fun paragraphs(lines: List<Line>, body: Float, pageWidth: Float): List<ParagraphBlock> {
        // a column gutter runs down a good part of the page; a few lines with spacing at the same place are no columns
        val rivers = ReadingOrder.rivers(lines, minLines = maxOf(3, lines.size / 6))
        val sorted = splitColumns(lines, rivers).sortedWith(compareBy<Line>({ it.top }, { it.x0 }))
        val open = ArrayList<Para>()
        val all = ArrayList<Para>()
        for (l in sorted) {
            val level = headingLevel(l, body)
            // the open paragraph whose last line sits directly above this one
            val target = open.lastOrNull { para -> continues(para.lines, para.level, l, level) }
            // paragraphs that can no longer grow (this line is well below them) are closed
            open.removeAll { para -> l.top - para.lines.last().bottom > 2.5f * para.lines.last().fontSize }
            if (target != null) target.lines += l
            else { val np = Para(mutableListOf(l), level); open += np; all += np }
        }
        return all.filter { !OcrText.isFragmentParagraph(it.lines) }.map { para ->
            val rtl = para.lines.count { it.rtl } * 2 > para.lines.size
            ParagraphBlock(para.lines, rtl, para.level, alignOf(para.lines, rtl, pageWidth))
        }
    }

    /**
     * [l] is the next line of the paragraph [lines] (heading level [level]): same level and direction (a line of only
     * digits / symbols takes the paragraph's), a similar size, horizontally overlapping, directly below. "Directly below"
     * is measured baseline to baseline: Arabic fonts are tall and Word's 1.15–1.5 line spacing put the boxes of
     * consecutive lines of one paragraph more than a font size apart (every line became its own paragraph). The first
     * step may be up to [MAX_PITCH] font sizes; later steps keep the paragraph's own pitch.
     */
    private fun continues(lines: List<Line>, pLevel: Int, l: Line, level: Int): Boolean {
        val p = lines.last()
        if (pLevel != level) return false
        if (p.rtl != l.rtl && !l.neutral && !p.neutral) return false
        val ocr = p.ocr || l.ocr
        if (abs(l.fontSize - p.fontSize) > (if (ocr) 0.3f else 0.15f) * p.fontSize) return false
        if (startsItem(l)) return false
        if (!(l.x0 < p.x1 && l.x1 > p.x0)) return false
        val fs = maxOf(p.fontSize, l.fontSize)
        val pitch = l.baseline - p.baseline
        if (l.top - p.bottom <= -0.5f * p.fontSize || pitch < 0.6f * fs) return false
        if (lines.size >= 2) {
            val prev = p.baseline - lines[lines.size - 2].baseline
            if (pitch > 1.3f * prev + 0.15f * fs) return false
        } else if (pitch > MAX_PITCH * fs) return false
        return level > 0 || !endsParagraph(p, lines)
    }

    private val NUMBER_MARK = Regex("""^[()\[\].\-]?[0-9٠-٩]{1,3}[()\[\].\-]?$""")
    private val ITEM_START = Regex("""^\s*(?:[(\[]?[0-9٠-٩]{1,3}\s?[)\].\-]|[•●▪◦‣*∵∴])""")

    /**
     * [l] opens an item of its own: a list number or bullet («١)», «2.», «•», «∵», «∴»), or a label within its first three
     * words («المعطيات :», «المطلوب :», «مثال (٤) :»). Worked examples and exercise sheets are made of such lines, each
     * as wide as the text, and they used to run together into one paragraph.
     */
    fun startsItem(l: Line): Boolean {
        val t = l.text
        if (ITEM_START.containsMatchIn(t)) return true
        val toks = t.split(' ').filter { it.isNotBlank() }.take(4)
        // «١)», «(1», «.33» (Word's right-to-left list numbers come out with the mark on either side)
        if (toks.isNotEmpty() && NUMBER_MARK.matches(toks[0]) && toks[0].any { !it.isLetterOrDigit() }) return true
        return toks.size >= 2 && toks.dropLast(if (toks.size > 3) 1 else 0).any { it == ":" || (it.endsWith(":") && it.length > 1) }
    }

    /** Largest baseline step (in font sizes) from a paragraph's first line to its second. */
    private const val MAX_PITCH = 1.9f

    /** True when [p] is noticeably shorter than the paragraph width so far (last line of a paragraph). */
    private fun endsParagraph(p: Line, para: List<Line>): Boolean {
        if (para.size < 2) return false
        val w = para.maxOf { it.x1 } - para.minOf { it.x0 }
        val t = p.text.trimEnd()
        // a line well short of the paragraph's width ends it even without a final stop (OCR often reads «.» as «-»)
        if ((p.x1 - p.x0) < 0.55f * w) return true
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
