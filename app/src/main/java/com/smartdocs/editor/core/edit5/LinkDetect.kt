package com.smartdocs.editor.core.edit5

/** A link target found in plain text: [range] in the text, [target] the normalised URI (https:, mailto:, tel:). */
data class TextLink(val range: IntRange, val target: String)

/** URL / e-mail / phone detection in page text and URI normalisation (pure). */
object LinkDetect {
    private val url = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"'،()\[\]]+[^\s<>"'.,;:!?،؟()\[\]])""")
    private val email = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")
    private val phone = Regex("""(?<![\w])\+?\d[\d\s-]{7,}\d(?![\w])""")

    fun find(text: String, phones: Boolean = false): List<TextLink> {
        val out = ArrayList<TextLink>()
        url.findAll(text).forEach { m -> out += TextLink(m.range, normalizeUrl(m.value)) }
        email.findAll(text).forEach { m -> if (out.none { it.range.first <= m.range.last && m.range.first <= it.range.last }) out += TextLink(m.range, "mailto:" + m.value) }
        if (phones) phone.findAll(text).forEach { m ->
            if (out.none { it.range.first <= m.range.last && m.range.first <= it.range.last }) out += TextLink(m.range, "tel:" + m.value.filter { it.isDigit() || it == '+' })
        }
        return out.sortedBy { it.range.first }
    }

    /** "example.com" → "https://example.com"; schemes (https, http, ftp, mailto, tel, file …) are kept. */
    fun normalizeUrl(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        if (Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""").containsMatchIn(t)) return t
        if (email.matches(t)) return "mailto:$t"
        return "https://$t"
    }

    /** Build a mailto: URI with optional subject/body (percent-encoded, RFC 6068). */
    fun mailto(to: String, subject: String = "", body: String = ""): String {
        val q = buildList {
            if (subject.isNotBlank()) add("subject=" + enc(subject))
            if (body.isNotBlank()) add("body=" + enc(body))
        }
        return "mailto:" + to.trim() + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }

    /** Split a mailto: URI back into (to, subject, body). */
    fun parseMailto(uri: String): Triple<String, String, String> {
        val rest = uri.removePrefix("mailto:").removePrefix("MAILTO:")
        val to = rest.substringBefore('?')
        val params = rest.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=').lowercase() to dec(it.substringAfter('=')) }
        return Triple(dec(to), params["subject"].orEmpty(), params["body"].orEmpty())
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String): String = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}
