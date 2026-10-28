package com.smartdocs.editor.core.pdf

/**
 * tab-view-core E-17: keep an existing XMP packet in step with the /Info values written by the quick Document
 * properties, without dropping the rest of the packet (PDF/A identification, custom schemas). Only values that the
 * packet already has are replaced: dc:title / dc:creator / dc:description (first rdf:li), pdf:Keywords,
 * pdf:Producer and xmp:CreatorTool, as elements or as attributes. Pure (unit-tested).
 */
object XmpSync {
    data class Values(val title: String, val author: String, val subject: String, val keywords: String, val creator: String, val producer: String)

    fun update(xml: String, v: Values): String {
        var s = xml
        s = li(s, "dc:title", v.title)
        s = li(s, "dc:creator", v.author)
        s = li(s, "dc:description", v.subject)
        s = simple(s, "pdf:Keywords", v.keywords)
        s = simple(s, "pdf:Producer", v.producer)
        s = simple(s, "xmp:CreatorTool", v.creator)
        return s
    }

    fun esc(t: String): String = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** First rdf:li inside <[tag]> … </[tag]>. */
    private fun li(s: String, tag: String, value: String): String {
        val re = Regex("(<" + Regex.escape(tag) + """[^>]*>.*?<rdf:li[^>]*>)(.*?)(</rdf:li>)""", RegexOption.DOT_MATCHES_ALL)
        return first(re, s, value)
    }

    /** <[tag]>v</[tag]> or [tag]="v". */
    private fun simple(s: String, tag: String, value: String): String {
        val t = Regex.escape(tag)
        val el = Regex("(<$t>)(.*?)(</$t>)", RegexOption.DOT_MATCHES_ALL)
        var out = first(el, s, value)
        val attr = Regex("""(\s$t=")([^"]*)(")""")
        out = first(attr, out, value)
        return out
    }

    /** Replace group 2 of the first match of [re] in [s] by the escaped [value]. */
    private fun first(re: Regex, s: String, value: String): String {
        val m = re.find(s) ?: return s
        return s.replaceRange(m.range, m.groupValues[1] + esc(value) + m.groupValues[3])
    }
}
