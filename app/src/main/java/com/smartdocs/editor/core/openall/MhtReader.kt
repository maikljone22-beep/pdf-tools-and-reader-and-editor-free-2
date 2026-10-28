package com.smartdocs.editor.core.openall

import java.util.Base64

/** One MIME part of a web archive. [location] = Content-Location, [cid] = Content-ID (no angle brackets). */
class MimePart(val type: String, val charset: String?, val location: String?, val cid: String?, val data: ByteArray)

/** A web archive: the main HTML, its base URL and the resources the viewer serves by URL. */
class WebArchive(val html: String, val baseUrl: String?, val parts: List<MimePart>) {
    fun resource(url: String): MimePart? {
        parts.firstOrNull { it.location == url }?.let { return it }
        val last = url.substringBefore('?').substringAfterLast('/')
        if (last.isEmpty()) return null
        return parts.firstOrNull { p -> p.location?.substringBefore('?')?.substringAfterLast('/') == last }
    }
}

/** MHT / MHTML (multipart/related) reader for the safe HTML viewer. Pure. */
object MhtReader {

    private fun headers(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>(); var last: String? = null
        for (line in s.split(Regex("""\r?\n"""))) {
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t') && last != null) out[last] = out[last] + " " + line.trim()
            else { val k = line.substringBefore(':', ""); if (k.isNotEmpty()) { last = k.trim().lowercase(); out[last] = line.substringAfter(':').trim() } }
        }
        return out
    }

    private fun param(h: String?, name: String): String? =
        h?.let { Regex("""(?i)(?:^|;)\s*$name\s*=\s*(?:"([^"]*)"|([^;\s]+))""").find(it) }?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }

    private fun split(data: ByteArray): Pair<Map<String, String>, ByteArray> {
        val s = String(data, Charsets.ISO_8859_1)
        val m = Regex("""\r?\n\r?\n""").find(s)
        val head = if (m != null) s.substring(0, m.range.first) else s
        val start = m?.range?.last?.plus(1) ?: s.length
        return headers(head) to data.copyOfRange(start.coerceAtMost(data.size), data.size)
    }

    private fun decode(h: Map<String, String>, b: ByteArray): ByteArray = when (h["content-transfer-encoding"]?.lowercase()?.trim()) {
        "base64" -> runCatching { Base64.getMimeDecoder().decode(String(b, Charsets.ISO_8859_1).replace(Regex("""[^A-Za-z0-9+/=]"""), "")) }.getOrDefault(b)
        "quoted-printable" -> com.smartdocs.editor.core.tools5.EmlReader.quotedPrintable(b)
        else -> b
    }

    fun read(bytes: ByteArray, maxParts: Int = 5000): WebArchive {
        val (h, body) = split(bytes)
        val ct = h["content-type"].orEmpty()
        val parts = ArrayList<MimePart>()
        val boundary = param(ct, "boundary")
        if (boundary == null) {
            // a lone HTML part (or plain HTML saved as .mht)
            val data = if (h.isEmpty()) bytes else decode(h, body)
            val html = String(data, param(ct, "charset")?.let { runCatching { charset(it) }.getOrNull() } ?: TextDecode.detect(data).first)
            return WebArchive(html, h["content-location"], emptyList())
        }
        val s = String(body, Charsets.ISO_8859_1)
        val delim = "--$boundary"
        var idx = s.indexOf(delim)
        while (idx >= 0 && parts.size < maxParts) {
            if (s.startsWith("$delim--", idx)) break
            val nl = s.indexOf('\n', idx)
            if (nl < 0) break
            val start = nl + 1
            val next = s.indexOf(delim, start)
            var end = if (next < 0) s.length else next
            if (end > start && s[end - 1] == '\n') end--
            if (end > start && s[end - 1] == '\r') end--
            val (ph, pb) = split(s.substring(start, maxOf(start, end)).toByteArray(Charsets.ISO_8859_1))
            val pct = ph["content-type"] ?: "text/plain"
            parts += MimePart(pct.substringBefore(';').trim().lowercase(), param(pct, "charset"), ph["content-location"]?.trim(), ph["content-id"]?.trim()?.trim('<', '>'), decode(ph, pb))
            idx = next
        }
        val start = param(ct, "start")?.trim('<', '>')
        val main = parts.firstOrNull { start != null && it.cid == start } ?: parts.firstOrNull { it.type == "text/html" } ?: parts.firstOrNull()
            ?: return WebArchive("", null, parts)
        val cs = main.charset?.let { runCatching { charset(it) }.getOrNull() } ?: TextDecode.detect(main.data).first
        var html = String(main.data, cs)
        // cid: references → a URL the viewer can intercept
        html = Regex("""(?i)cid:([^"'\s>)]+)""").replace(html) { m -> "https://cid.local/" + m.groupValues[1] }
        val rest = parts.filter { it !== main } + parts.filter { it.cid != null }.map { MimePart(it.type, it.charset, "https://cid.local/" + it.cid, it.cid, it.data) }
        return WebArchive(html, main.location, rest)
    }
}
