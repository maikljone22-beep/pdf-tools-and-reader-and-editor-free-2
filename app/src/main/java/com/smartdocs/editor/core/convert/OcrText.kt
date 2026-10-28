package com.smartdocs.editor.core.convert

import com.smartdocs.editor.core.ocr.OcrCleanup
import kotlin.math.abs

/**
 * Recognised (OCR) text inside a PDF being converted (PDF_TO_WORD_r5). Pure Kotlin.
 *
 * A PDF that went through OCR carries the recognised words as text, but that text is not typeset: its size is the height
 * of a word box, its baseline the bottom of the box, and it holds what Tesseract read in borders and pictures. Two kinds:
 *  - an invisible layer (rendering mode 3/7): [com.smartdocs.editor.core.ocr.SearchablePdfWriter], other apps' OCR;
 *  - the visible words an older build of this app re-typed over a page picture (pre-847d5d8 "editable" OCR, [ourVisibleLayer]).
 * Such glyphs are flagged [Glyph.ocr]: words lying on real text are dropped as duplicates ([dropDuplicates]), their size and
 * baseline are normalised per line ([LayoutAnalyzer.buildLines]) and the OCR junk rules ([OcrCleanup]) apply ([cleanLines]).
 */
object OcrText {

    /** Per extracted glyph: set in the app's OCR fonts ([isOcrFont]), horizontal scaling (Tz, percent), invisible. */
    data class Mark(val ourFont: Boolean, val tz: Float, val invisible: Boolean)

    /** The fonts the app's OCR writers type with: the bundled Noto Naskh Arabic (any subset prefix) and standard Helvetica. */
    fun isOcrFont(name: String?, embedded: Boolean): Boolean {
        val n = name.orEmpty()
        return n.contains("NotoNaskhArabic") || (n == "Helvetica" && !embedded)
    }

    /**
     * True when the page's visible text was re-typed by the app's old OCR rebuild: at least 20 visible glyphs in its fonts,
     * most of them (60 %) stretched with a horizontal scaling other than 100 % — the writer fitted every word to its box.
     * Typeset text in Noto Naskh is never scaled word by word.
     */
    fun ourVisibleLayer(marks: List<Mark>): Boolean {
        val ours = marks.filter { it.ourFont && !it.invisible }
        if (ours.size < 20) return false
        return ours.count { abs(it.tz - 100f) > 1f } * 5 >= ours.size * 3
    }

    /** Largest channel difference of two ARGB / RGB colours. */
    fun colourDistance(a: Int, b: Int): Int = maxOf(
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)), abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)), abs((a and 0xFF) - (b and 0xFF)))

    // ---------------------------------------------------------------- duplicates

    /** Area index of glyph boxes for "how much of this rectangle is text" ([covered]). */
    class Coverage(glyphs: List<Glyph>, private val cell: Float = 24f) {
        private val grid = HashMap<Long, MutableList<Rect>>()
        private fun key(i: Int, j: Int) = (i.toLong() shl 32) xor (j.toLong() and 0xFFFFFFFFL)

        init { glyphs.forEach { g -> if (g.text.isNotBlank() && g.w > 0f && g.h > 0f) add(Rect(g.x, g.top, g.x + g.w, g.top + g.h)) } }

        fun add(r: Rect) {
            for (i in (r.x0 / cell).toInt()..(r.x1 / cell).toInt()) for (j in (r.y0 / cell).toInt()..(r.y1 / cell).toInt()) grid.getOrPut(key(i, j)) { ArrayList() } += r
        }

        /** Share (0..1) of [r] covered by indexed boxes (overlapping boxes are counted once per box, capped at 1). */
        fun covered(r: Rect): Float {
            val area = r.w * r.h
            if (area <= 0f) return 0f
            val seen = HashSet<Rect>()
            var sum = 0f
            for (i in (r.x0 / cell).toInt()..(r.x1 / cell).toInt()) for (j in (r.y0 / cell).toInt()..(r.y1 / cell).toInt()) {
                for (b in grid[key(i, j)].orEmpty()) {
                    if (!seen.add(b)) continue
                    val w = minOf(r.x1, b.x1) - maxOf(r.x0, b.x0); val h = minOf(r.y1, b.y1) - maxOf(r.y0, b.y0)
                    if (w > 0f && h > 0f) sum += w * h
                }
            }
            return (sum / area).coerceAtMost(1f)
        }
    }

    /**
     * Drops recognised words that lie on real (typeset) text: an OCR layer over a digital page repeats the page's own
     * text, which Word would show twice. A word is a run of consecutive OCR glyphs on one baseline (one string the OCR
     * writer showed); it goes when real glyph boxes, widened to the full line height, cover more than [maxCovered] of it
     * (the rule [com.smartdocs.editor.core.ocr.MixedPageOcr.filterWords] applies before writing the layer).
     */
    fun dropDuplicates(glyphs: List<Glyph>, maxCovered: Float = 0.35f): List<Glyph> {
        if (glyphs.none { it.ocr } || glyphs.none { !it.ocr }) return glyphs
        // real glyphs as line-high boxes: their PDF heights are often only the x-height of the font
        val real = Coverage(emptyList()).also { c ->
            glyphs.forEach { g -> if (!g.ocr && g.text.isNotBlank() && g.w > 0f) c.add(Rect(g.x, g.baseline - 0.8f * g.fontSize, g.x + g.w, g.baseline + 0.2f * g.fontSize)) }
        }
        val drop = HashSet<Int>()
        var i = 0
        while (i < glyphs.size) {
            if (!glyphs[i].ocr) { i++; continue }
            var j = i + 1
            while (j < glyphs.size && glyphs[j].ocr && abs(glyphs[j].baseline - glyphs[j - 1].baseline) < 0.5f &&
                abs(glyphs[j].fontSize - glyphs[i].fontSize) < 0.01f && adjacent(glyphs[j - 1], glyphs[j])) j++
            val run = glyphs.subList(i, j)
            val box = Rect(run.minOf { it.x }, run.minOf { it.baseline - 0.8f * it.fontSize }, run.maxOf { it.right }, run.maxOf { it.baseline + 0.2f * it.fontSize })
            if (real.covered(box) > maxCovered) for (k in i until j) drop += k
            i = j
        }
        return if (drop.isEmpty()) glyphs else glyphs.filterIndexed { k, _ -> k !in drop }
    }

    /** Two glyphs of one shown string, written left → right or right → left. */
    private fun adjacent(a: Glyph, b: Glyph): Boolean {
        val tol = 0.6f * maxOf(a.fontSize, 1f)
        return abs(b.x - a.right) < tol || abs(a.x - b.right) < tol
    }

    // ---------------------------------------------------------------- size / junk

    /**
     * Font size of a recognised line from its word-box heights. Tesseract boxes hold the ink of a word, about 0.6–0.9 of
     * the font size for Arabic words; measured on «المرشد…» (typeset header 9 pt over boxes of 6.5–9 pt, body
     * pictures of 13–14 pt text over boxes of 9–12 pt).
     */
    const val BOX_TO_SIZE = 1.25f

    /**
     * Drops recognised "words" standing on end: a run of at least two characters whose box is less than half as wide as
     * it is high is a piece of a vertical border or a rotated label Tesseract read as a line («COC», «OEE»).
     */
    fun dropVertical(glyphs: List<Glyph>): List<Glyph> {
        if (glyphs.none { it.ocr }) return glyphs
        val drop = HashSet<Int>()
        var i = 0
        while (i < glyphs.size) {
            if (!glyphs[i].ocr) { i++; continue }
            var j = i + 1
            while (j < glyphs.size && glyphs[j].ocr && abs(glyphs[j].baseline - glyphs[j - 1].baseline) < 0.5f &&
                abs(glyphs[j].fontSize - glyphs[i].fontSize) < 0.01f && adjacent(glyphs[j - 1], glyphs[j])) j++
            val run = glyphs.subList(i, j)
            val chars = run.sumOf { g -> g.text.count { it.isLetterOrDigit() } }
            val w = run.maxOf { it.right } - run.minOf { it.x }
            if (chars >= 2 && w < 0.5f * glyphs[i].fontSize) for (k in i until j) drop += k
            i = j
        }
        return if (drop.isEmpty()) glyphs else glyphs.filterIndexed { k, _ -> k !in drop }
    }

    /**
     * Decoration that OCR "read" ([OcrCleanup]): a line made only of recognised words that is junk as a whole
     * («KEK EEK», «حت ات امت ست», «<-<--+») is dropped, and junk words («#رحتح», «EEEK», impossible Arabic) are taken
     * out of every line. A Tesseract «¢» in Arabic text is its reading of the Arabic comma.
     */
    fun cleanLines(lines: List<Line>): List<Line> {
        if (lines.none { l -> l.words.any { it.ocr } }) return lines
        return lines.mapNotNull { l ->
            if (l.words.none { it.ocr }) return@mapNotNull l
            if (l.ocr && OcrCleanup.isJunkTextLine(l.text)) return@mapNotNull null
            val keep = l.visual.filter { w -> !w.ocr || !junkWord(w.text, l.rtl) }.map { w -> if (w.ocr && l.rtl && w.text == "¢") Word(w.glyphs.map { it.copy(text = "،") }, "،", true) else w }
            when {
                keep.isEmpty() -> null
                keep.size == l.visual.size && keep.indices.all { keep[it] === l.visual[it] } -> l
                else -> LayoutAnalyzer.lineOf(keep)
            }
        }
    }

    /**
     * A paragraph of one line that carries nothing: no letter or digit at all («:», «(», «'»), or recognised text of at
     * most two letters / digits that is no word («ا», «م», «١», «0 CC» from a border, a bullet or a picture).
     */
    fun isFragmentParagraph(lines: List<Line>): Boolean {
        if (lines.size != 1) return false
        val l = lines[0]
        val alnum = l.text.count { it.isLetterOrDigit() }
        if (alnum == 0) return true
        if (!l.ocr) return false
        if (alnum <= 2) return true
        return l.words.all { OcrCleanup.isFragment(it.text) || junkWord(it.text, l.rtl) }
    }

    /** Marks OCR reads in box edges, table rules and specks: never text on their own. */
    private const val SPECKS = "|¦!'`´~^_\\{}[]<>\"“”‘’•·¬"

    fun junkWord(t: String, rtlLine: Boolean = true): Boolean {
        if (OcrCleanup.isJunkWord(t)) return true
        if (t.isNotEmpty() && t.all { it in SPECKS }) return true
        // a lone Latin letter inside Arabic text is a speck or a piece of a border («i», «y», «A»)
        if (rtlLine && t.length == 1 && (t[0] in 'a'..'z' || t[0] in 'A'..'Z')) return true
        val core = t.trim { !it.isLetterOrDigit() }
        // capitals-only runs are border pieces («KYE», «EEK», «OOS», «YEDOEDOE»), not words; formulas stay (a formula
        // never names one element twice in a row: «OOS», «CC» are no formulas)
        if (core.length >= 3 && core.all { it in 'A'..'Z' }) {
            val doubled = (1 until core.length).any { core[it] == core[it - 1] }
            if ((doubled || !OcrCleanup.isFormula(core)) && (core.toSet().size <= 3 || core.length >= 6 || doubled)) return true
        }
        // Latin reads of Arabic letters: «@Jjlp», «el—sasSJI» (a capital after a small letter inside a word, a dash inside)
        if (core.any { it in 'a'..'z' || it in 'A'..'Z' } && core.none { BidiText.isArabicScript(it) }) {
            if ('@' in t) return true
            for (i in 1 until core.length) {
                if (core[i] in 'A'..'Z' && core[i - 1] in 'a'..'z') return true
                if (core[i - 1] in "—–" && i >= 2 && core[i].isLetter() && core[i - 2].isLetter()) return true
            }
        }
        // an Arabic "word" of 15+ letters is letters of a border or a picture run together («عوجححوصحوعوصحمومصي»)
        return core.count { it.code in 0x0621..0x064A } >= 15
    }
}
