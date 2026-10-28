package com.smartdocs.editor.core.tts

/**
 * Block navigation for read aloud (FINAL_CHECK §19 "next / previous / repeat block"): where paragraphs and headings
 * start in a page's reading-order text. Pure, so the rules are unit-tested.
 *
 * - a paragraph starts at the first character, after a blank line, or after a line break that follows a sentence end;
 * - a heading is a short line (≤ [HEADING_MAX] chars) that does not end with sentence punctuation and is followed by a
 *   line break (typical of titles and section headings in extracted text).
 */
object ReadBlocks {

    enum class Kind { PARAGRAPH, HEADING }

    const val HEADING_MAX = 80
    private val SENTENCE_END = setOf('.', '!', '?', '؟', '。', '؛', ':', '…')

    /** Start offsets (sorted, distinct) of the blocks of [kind] in [text]. */
    fun starts(text: String, kind: Kind): List<Int> {
        val lines = ArrayList<IntRange>()
        var a = 0
        for (i in text.indices) if (text[i] == '\n') { lines += a until i; a = i + 1 }
        if (a <= text.length) lines += a until text.length
        val out = ArrayList<Int>()
        var prevEndsSentence = true
        var prevBlank = true
        for (r in lines) {
            val line = if (r.isEmpty()) "" else text.substring(r.first, r.last + 1)
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { prevBlank = true; continue }
            val first = r.first + line.indexOfFirst { !it.isWhitespace() }
            when (kind) {
                Kind.PARAGRAPH -> if (prevBlank || prevEndsSentence) out += first
                Kind.HEADING -> if (trimmed.length <= HEADING_MAX && trimmed.last() !in SENTENCE_END && trimmed.any { it.isLetter() }) out += first
            }
            prevEndsSentence = trimmed.last() in SENTENCE_END
            prevBlank = false
        }
        return out.distinct().sorted()
    }

    /** The first start after [pos], or null. */
    fun next(starts: List<Int>, pos: Int): Int? = starts.firstOrNull { it > pos }

    /**
     * The start of the block containing [pos]; when [pos] is within [slack] chars of that start (the listener just
     * heard it begin), the one before — so pressing "previous" twice keeps going back.
     */
    fun previous(starts: List<Int>, pos: Int, slack: Int = 12): Int? {
        val cur = starts.lastOrNull { it <= pos } ?: return null
        return if (pos - cur <= slack) starts.lastOrNull { it < cur } else cur
    }
}
