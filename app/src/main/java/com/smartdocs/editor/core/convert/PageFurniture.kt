package com.smartdocs.editor.core.convert

/**
 * Running headers and footers (pure Kotlin). A line is page furniture when it sits in the top or bottom 10 % of the
 * page, is not larger than the body text, and its text with digits ignored repeats at the same height on at least
 * half of the sampled pages (and on at least two).
 */
class PageFurniture private constructor(private val keys: Set<String>, private val variable: Map<String, Set<Int>>) {

    /** One piece of a furniture line: text, or the page number field. */
    sealed class Piece {
        data class Text(val text: String) : Piece()
        data class PageNumber(val sample: String) : Piece()
    }

    enum class Pos { START, CENTER, END }

    /** A positioned part of a header/footer line (start / centre / end of the line in reading direction). */
    class Part(val pos: Pos, val pieces: List<Piece>)

    val isEmpty: Boolean get() = keys.isEmpty()

    fun isHeader(line: Line, pageH: Float, body: Float) = zone(line, pageH, body) == 'H' && key(line, pageH, body) in keys
    fun isFooter(line: Line, pageH: Float, body: Float) = zone(line, pageH, body) == 'F' && key(line, pageH, body) in keys

    /**
     * Splits a furniture line into start / centre / end parts (by the chunk positions on the page) and turns the digit
     * runs that change from page to page into page-number fields.
     */
    fun parts(line: Line, pageW: Float, pageH: Float, body: Float): List<Part> {
        val varying = variable[key(line, pageH, body)].orEmpty()
        val visual = LayoutAnalyzer.chunks(line)
        val logical = if (line.rtl) visual.reversed() else visual
        var digitIndex = 0
        return logical.map { ws ->
            val l = LayoutAnalyzer.lineOf(ws)
            val cx = (l.x0 + l.x1) / 2f
            val third = when { cx < pageW / 3f -> 0; cx > 2f * pageW / 3f -> 2; else -> 1 }
            val pos = when (third) {
                1 -> Pos.CENTER
                0 -> if (line.rtl) Pos.END else Pos.START
                else -> if (line.rtl) Pos.START else Pos.END
            }
            val pieces = ArrayList<Piece>()
            for (tok in tokens(l.text)) {
                if (tok.first().isDigitAscii()) {
                    if (digitIndex in varying) pieces += Piece.PageNumber(tok) else pieces += Piece.Text(tok)
                    digitIndex++
                } else pieces += Piece.Text(tok)
            }
            Part(pos, merge(pieces))
        }
    }

    companion object {
        val NONE = PageFurniture(emptySet(), emptyMap())

        /** One sampled page: its lines, height and body font size. */
        class Sample(val lines: List<Line>, val height: Float, val body: Float)

        private fun Char.isDigitAscii() = this in '0'..'9' || this in '٠'..'٩' || this in '۰'..'۹'

        fun zone(line: Line, pageH: Float, body: Float): Char? = when {
            line.fontSize > 1.15f * body + 0.5f -> null
            line.bottom <= 0.10f * pageH -> 'H'
            line.top >= 0.90f * pageH -> 'F'
            else -> null
        }

        private fun norm(s: String) = s.filter { !it.isDigitAscii() && !it.isWhitespace() }

        fun key(line: Line, pageH: Float, body: Float): String {
            val z = zone(line, pageH, body) ?: return ""
            return "$z${Math.round(line.top / 6f)}|${norm(line.text)}"
        }

        /** Runs of digits and of non-digits. */
        fun tokens(s: String): List<String> {
            val out = ArrayList<String>(); val sb = StringBuilder(); var digit: Boolean? = null
            for (c in s) {
                val d = c.isDigitAscii()
                if (digit != null && d != digit) { out += sb.toString(); sb.setLength(0) }
                sb.append(c); digit = d
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }

        private fun merge(ps: List<Piece>): List<Piece> {
            val out = ArrayList<Piece>()
            for (p in ps) {
                val last = out.lastOrNull()
                if (p is Piece.Text && last is Piece.Text) out[out.size - 1] = Piece.Text(last.text + p.text) else out += p
            }
            return out
        }

        fun detect(samples: List<Sample>): PageFurniture {
            if (samples.size < 2) return NONE
            val occurrences = HashMap<String, MutableList<String>>() // key → texts (one per page)
            for (s in samples) {
                val seen = HashSet<String>()
                for (l in s.lines) {
                    val k = key(l, s.height, s.body)
                    if (k.isEmpty() || k.substringAfter('|').isEmpty() && !l.text.any { it.isDigitAscii() }) continue
                    if (seen.add(k)) occurrences.getOrPut(k) { ArrayList() } += l.text
                }
            }
            val need = maxOf(2, (samples.size + 1) / 2)
            val keys = occurrences.filter { it.value.size >= need }.keys
            val variable = keys.associateWith { k ->
                val toks = occurrences[k]!!.map { t -> tokens(t).filter { it.first().isDigitAscii() } }
                val n = toks.minOf { it.size }
                (0 until n).filter { i -> toks.map { it[i] }.toSet().size > 1 }.toSet()
            }
            return PageFurniture(keys, variable)
        }
    }
}
