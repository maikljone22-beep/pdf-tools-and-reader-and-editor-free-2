package com.smartdocs.editor.core.nve

/** Finds web addresses and e-mail addresses in plain page text (pure, unit tested). */
object UrlFinder {
    private val URL = Regex("""(?i)\b((?:https?://|ftp://|www\.)[^\s<>"'()\[\]{}]+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,})""")

    /** Each URL (normalised: "www." gets "https://") with its character range in [text]. */
    fun find(text: String): List<Pair<String, IntRange>> = URL.findAll(text).map { m ->
        var v = m.value
        var end = m.range.last
        // trailing punctuation belongs to the sentence
        while (v.isNotEmpty() && v.last() in ".,;:!?،؛") { v = v.dropLast(1); end-- }
        val url = when {
            v.startsWith("www.", ignoreCase = true) -> "https://$v"
            v.contains('@') && !v.contains("://") -> "mailto:$v"
            else -> v
        }
        url to (m.range.first..end)
    }.filter { it.second.last >= it.second.first }.toList()
}
