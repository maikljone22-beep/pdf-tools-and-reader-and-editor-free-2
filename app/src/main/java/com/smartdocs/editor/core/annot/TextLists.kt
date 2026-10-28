package com.smartdocs.editor.core.annot

/**
 * 24.105 lists of text comments: bullets ("• ") or numbering ("1. ", "2. " ...) in front of every non-empty line.
 * Numbering restarts after an empty line, so separate lists in one text box count on their own. Pure, for tests.
 */
object TextLists {
    const val BULLET = "• "
    private val NUMBER = Regex("""^\d{1,4}\. """)

    /** The marker of every line ("" for empty lines and when neither list style is on). */
    fun markers(lines: List<String>, bullets: Boolean, numbering: Boolean): List<String> {
        if (!bullets && !numbering) return lines.map { "" }
        var n = 0
        return lines.map { l ->
            when {
                l.isBlank() -> { n = 0; "" }
                numbering -> { n++; "$n. " }
                else -> BULLET
            }
        }
    }

    /** [text] split in lines with their markers in front. */
    fun decorate(text: String, bullets: Boolean, numbering: Boolean): List<String> {
        val lines = text.split('\n')
        return markers(lines, bullets, numbering).zip(lines) { m, l -> m + l }
    }

    /** Remove markers written into the text by other viewers (or older versions) when reading a list back. */
    fun strip(text: String, bullets: Boolean, numbering: Boolean): String =
        text.split('\n').joinToString("\n") { l -> when { numbering -> l.replaceFirst(NUMBER, ""); bullets -> l.removePrefix(BULLET); else -> l } }
}
