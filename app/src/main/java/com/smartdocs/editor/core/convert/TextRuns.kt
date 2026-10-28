package com.smartdocs.editor.core.convert

/** A formatting run in logical order. */
data class TextRun(
    val text: String, val bold: Boolean, val italic: Boolean, val size: Float,
    val color: Int = 0, val font: String? = null, val underline: Boolean = false, val strike: Boolean = false, val bg: Int? = null,
) {
    /** Same formatting (everything but the text). */
    fun sameStyle(o: TextRun) = bold == o.bold && italic == o.italic && size == o.size && color == o.color && font == o.font &&
        underline == o.underline && strike == o.strike && bg == o.bg
}

object TextRuns {

    private fun round(fs: Float) = Math.round(fs * 2f) / 2f

    /** Runs of one line: adjacent words with the same bold/italic/size are merged, separated by spaces. */
    fun of(line: Line): List<TextRun> {
        val out = ArrayList<TextRun>()
        line.words.forEachIndexed { i, w ->
            // recognised (OCR) words have no font of their own: the layer's font is the app's, not the document's
            val run = TextRun(w.text, w.bold, w.italic, round(w.fontSize), w.color, if (w.ocr) null else w.font, w.underline, w.strike, w.bg)
            val last = out.lastOrNull()
            if (last != null && last.sameStyle(run)) out[out.size - 1] = last.copy(text = last.text + " " + w.text)
            else if (last != null && i > 0) {
                // the space between two differently formatted words keeps the earlier word's look unless it is decorated
                if (last.underline || last.strike || last.bg != null) out += run.copy(text = " " + w.text)
                else { out[out.size - 1] = last.copy(text = last.text + " "); out += run }
            } else out += run
        }
        return out
    }

    /**
     * Runs of a reflowed paragraph: lines joined with a space (a trailing hyphen before a Latin lowercase
     * letter is treated as a soft hyphen and removed).
     */
    fun ofParagraph(lines: List<Line>): List<TextRun> {
        val out = ArrayList<TextRun>()
        lines.forEachIndexed { li, line ->
            val runs = of(line)
            if (runs.isEmpty()) return@forEachIndexed
            if (li > 0 && out.isNotEmpty()) {
                val prev = out.last()
                val next = runs.first()
                if (prev.text.endsWith("-") && next.text.firstOrNull()?.isLowerCase() == true) out[out.size - 1] = prev.copy(text = prev.text.dropLast(1))
                else out[out.size - 1] = prev.copy(text = prev.text + " ")
            }
            for (r in runs) {
                val last = out.lastOrNull()
                if (last != null && last.sameStyle(r)) out[out.size - 1] = last.copy(text = last.text + r.text)
                else out += r
            }
        }
        return out
    }

    fun plain(lines: List<Line>) = ofParagraph(lines).joinToString("") { it.text }
}
