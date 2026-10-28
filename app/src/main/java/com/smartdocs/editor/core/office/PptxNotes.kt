package com.smartdocs.editor.core.office

/**
 * Row 103: the speaker notes of a PPTX slide (its notesSlide part) and the slide's hidden flag (`<p:sld show="0">`).
 * Regex-based so it is plain Kotlin (unit-tested on the JVM, where the XML pull parser is a stub).
 */
object PptxNotes {
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
        .replace("&amp;", "&")

    /** Text of the body placeholder(s) of a notes slide: one line per paragraph; blank → null. */
    fun text(xml: String): String? {
        val shapes = Regex("""<p:sp\b.*?</p:sp>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.value }
            .filter { Regex("""<p:ph\b[^>]*type="body"""").containsMatchIn(it) }.toList()
        val paras = shapes.flatMap { sp ->
            Regex("""<a:p\b.*?</a:p>|<a:p/>""", RegexOption.DOT_MATCHES_ALL).findAll(sp).map { p ->
                Regex("""<a:t(?:\s[^>]*)?>(.*?)</a:t>""", RegexOption.DOT_MATCHES_ALL).findAll(p.value).joinToString("") { unescape(it.groupValues[1]) }
            }.toList()
        }
        return paras.joinToString("\n").trim().ifEmpty { null }
    }

    /** Whether a slide part is hidden in the slide show (`show="0"` on its root). */
    fun hidden(slideXml: String): Boolean =
        Regex("""<p:sld\b[^>]*\bshow="(0|false)"""").containsMatchIn(slideXml.take(2000))
}
