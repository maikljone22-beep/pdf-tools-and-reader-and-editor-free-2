package com.smartdocs.editor.core.convert

/** A formatting run in logical order. */
data class TextRun(val text: String, val bold: Boolean, val italic: Boolean, val size: Float)

object TextRuns {

    private fun round(fs: Float) = Math.round(fs * 2f) / 2f

    /** Runs of one line: adjacent words with the same bold/italic/size are merged, separated by spaces. */
    fun of(line: Line): List<TextRun> {
        val out = ArrayList<TextRun>()
        line.words.forEachIndexed { i, w ->
            val t = if (i == 0) w.text else " " + w.text
            val last = out.lastOrNull()
            val size = round(w.fontSize)
            if (last != null && last.bold == w.bold && last.italic == w.italic && last.size == size) out[out.size - 1] = last.copy(text = last.text + t)
            else out += TextRun(t, w.bold, w.italic, size)
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
                if (last != null && last.bold == r.bold && last.italic == r.italic && last.size == r.size) out[out.size - 1] = last.copy(text = last.text + r.text)
                else out += r
            }
        }
        return out
    }

    fun plain(lines: List<Line>) = ofParagraph(lines).joinToString("") { it.text }
}
