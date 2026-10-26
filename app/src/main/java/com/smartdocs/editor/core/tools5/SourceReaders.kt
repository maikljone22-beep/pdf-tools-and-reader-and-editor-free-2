package com.smartdocs.editor.core.tools5

import java.io.File
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.ZipFile

/*
 * Source → HTML readers for the any-format → PDF converter: EML (MIME), EPUB, Markdown and plain text.
 * The HTML is printed to PDF by the WebView (ToPdf). Plain JVM code (java.util.zip / Base64), unit-testable.
 */

class Attachment(val name: String, val mime: String, val bytes: ByteArray)

class HtmlDoc(val title: String, val html: String, val attachments: List<Attachment> = emptyList())

object HtmlBits {
    fun esc(s: String): String = buildString(s.length + 16) {
        s.forEach { c -> when (c) { '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;"); else -> append(c) } }
    }

    fun page(title: String, body: String, rtl: Boolean, extraCss: String = ""): String =
        "<!DOCTYPE html><html dir=\"" + (if (rtl) "rtl" else "ltr") + "\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "<title>" + esc(title) + "</title><style>body{font-family:'Noto Naskh Arabic','Noto Sans',sans-serif;font-size:12pt;line-height:1.5;margin:0;word-wrap:break-word}" +
            "img{max-width:100%;height:auto}pre{white-space:pre-wrap;font-family:inherit}table{border-collapse:collapse}td,th{border:1px solid #bbb;padding:4px}" +
            ".hdr td{border:none;padding:2px 8px}.hdr{margin-bottom:12px;border-bottom:1px solid #999}.chapter{page-break-before:always}" +
            extraCss + "</style></head><body>" + body + "</body></html>"

    fun rtlDominant(s: String): Boolean { var r = 0; var l = 0; s.forEach { if (TextNorm.isArabicChar(it)) r++ else if (it.isLetter()) l++ }; return r > l }
}

object TextHtml {
    fun fromText(text: String, title: String): HtmlDoc =
        HtmlDoc(title, HtmlBits.page(title, "<pre dir=\"auto\">" + HtmlBits.esc(text) + "</pre>", HtmlBits.rtlDominant(text)))
}

/** Small Markdown subset: headings, bold/italic/code, links, lists, quotes, fences, rules, paragraphs. */
object MarkdownHtml {
    private fun inline(s0: String): String {
        var s = HtmlBits.esc(s0)
        s = s.replace(Regex("""`([^`]+)`"""), "<code>$1</code>")
        s = s.replace(Regex("""!\[([^\]]*)]\(([^)\s]+)\)"""), "<img alt=\"$1\" src=\"$2\">")
        s = s.replace(Regex("""\[([^\]]+)]\(([^)\s]+)\)"""), "<a href=\"$2\">$1</a>")
        s = s.replace(Regex("""\*\*([^*]+)\*\*"""), "<b>$1</b>").replace(Regex("""__([^_]+)__"""), "<b>$1</b>")
        s = s.replace(Regex("""(?<![*\w])\*([^*]+)\*(?!\*)"""), "<i>$1</i>")
        return s
    }

    fun toHtmlBody(md: String): String = buildString {
        val lines = md.replace("\r\n", "\n").split('\n')
        var i = 0; var list: String? = null; val para = StringBuilder()
        fun flushPara() { if (para.isNotEmpty()) { append("<p dir=\"auto\">").append(inline(para.toString().trim())).append("</p>\n"); para.clear() } }
        fun closeList() { list?.let { append("</").append(it).append(">\n") }; list = null }
        while (i < lines.size) {
            val l = lines[i]; val t = l.trim()
            when {
                t.startsWith("```") -> {
                    flushPara(); closeList()
                    val code = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
                    append("<pre dir=\"ltr\"><code>").append(HtmlBits.esc(code.toString())).append("</code></pre>\n")
                }
                t.isEmpty() -> { flushPara(); closeList() }
                Regex("""^#{1,6}\s""").containsMatchIn(t) -> {
                    flushPara(); closeList()
                    val n = t.takeWhile { it == '#' }.length
                    append("<h$n dir=\"auto\">").append(inline(t.drop(n).trim().trimEnd('#').trim())).append("</h$n>\n")
                }
                t == "---" || t == "***" || t == "___" -> { flushPara(); closeList(); append("<hr>\n") }
                t.startsWith(">") -> { flushPara(); closeList(); append("<blockquote dir=\"auto\">").append(inline(t.drop(1).trim())).append("</blockquote>\n") }
                Regex("""^[-*+]\s""").containsMatchIn(t) || Regex("""^\d+[.)]\s""").containsMatchIn(t) -> {
                    flushPara()
                    val tag = if (t[0].isDigit()) "ol" else "ul"
                    if (list != tag) { closeList(); append("<").append(tag).append(">\n"); list = tag }
                    append("<li dir=\"auto\">").append(inline(t.replaceFirst(Regex("""^([-*+]|\d+[.)])\s+"""), ""))).append("</li>\n")
                }
                else -> { closeList(); para.append(l).append(' ') }
            }
            i++
        }
        flushPara(); closeList()
    }

    fun read(file: File): HtmlDoc {
        val md = file.readText()
        val title = md.lineSequence().firstOrNull { it.trim().startsWith("#") }?.trim()?.trimStart('#')?.trim() ?: file.nameWithoutExtension
        return HtmlDoc(title, HtmlBits.page(title, toHtmlBody(md), HtmlBits.rtlDominant(md)))
    }
}

/** RFC 5322 / MIME e-mail → HTML with a header table; inline images become data URIs; attachments are returned. */
object EmlReader {

    private class Part(val headers: Map<String, String>, val body: ByteArray)

    fun charset(name: String?): Charset = runCatching { Charset.forName(name?.trim('"', ' ')?.ifBlank { null } ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)

    /** RFC 2047 encoded words (=?cs?B?..?= / =?cs?Q?..?=); adjacent encoded words are joined. */
    fun decodeWords(s: String): String {
        val re = Regex("""=\?([^?]+)\?([BbQq])\?([^?]*)\?=""")
        val joined = s.replace(Regex("""\?=\s+=\?"""), "?==?")
        return re.replace(joined) { m ->
            val cs = charset(m.groupValues[1].substringBefore('*'))
            val data = m.groupValues[3]
            runCatching {
                if (m.groupValues[2].equals("B", true)) String(Base64.getMimeDecoder().decode(data), cs)
                else String(quotedPrintable(data.replace('_', ' ').toByteArray(Charsets.ISO_8859_1)), cs)
            }.getOrDefault(m.value)
        }
    }

    fun quotedPrintable(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        fun hex(c: Byte): Int = Character.digit(c.toInt().toChar(), 16)
        while (i < b.size) {
            val c = b[i]
            if (c == '='.code.toByte()) {
                if (i + 1 < b.size && (b[i + 1] == '\n'.code.toByte())) { i += 2; continue }
                if (i + 2 < b.size && b[i + 1] == '\r'.code.toByte() && b[i + 2] == '\n'.code.toByte()) { i += 3; continue }
                if (i + 2 < b.size && hex(b[i + 1]) >= 0 && hex(b[i + 2]) >= 0) { out.write(hex(b[i + 1]) * 16 + hex(b[i + 2])); i += 3; continue }
            }
            out.write(c.toInt()); i++
        }
        return out.toByteArray()
    }

    private fun splitHeaders(data: ByteArray): Part {
        val s = String(data, Charsets.ISO_8859_1)
        val m = Regex("""\r?\n\r?\n""").find(s)
        val headText = if (m != null) s.substring(0, m.range.first) else s
        val bodyStart = m?.range?.last?.plus(1) ?: s.length
        val headers = LinkedHashMap<String, String>()
        var last: String? = null
        headText.split(Regex("""\r?\n""")).forEach { line ->
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t') && last != null) headers[last!!] = headers[last!!] + " " + line.trim()
            else { val k = line.substringBefore(':', ""); if (k.isNotEmpty()) { last = k.trim().lowercase(); headers[last!!] = line.substringAfter(':').trim() } }
        }
        return Part(headers, data.copyOfRange(bodyStart.coerceAtMost(data.size), data.size))
    }

    private fun param(header: String?, name: String): String? {
        header ?: return null
        val m = Regex("""(?i)(?:^|;)\s*$name\*?=\s*(?:"([^"]*)"|([^;\s]+))""").find(header) ?: return null
        val v = m.groupValues[1].ifEmpty { m.groupValues[2] }
        // RFC 2231: charset''percent-encoded
        return if (v.contains("''")) runCatching { java.net.URLDecoder.decode(v.substringAfter("''"), v.substringBefore("''").ifBlank { "UTF-8" }) }.getOrDefault(v) else decodeWords(v)
    }

    private fun decodeBody(p: Part): ByteArray = when (p.headers["content-transfer-encoding"]?.lowercase()?.trim()) {
        "base64" -> runCatching { Base64.getMimeDecoder().decode(String(p.body, Charsets.ISO_8859_1).replace(Regex("""[^A-Za-z0-9+/=]"""), "")) }.getOrDefault(p.body)
        "quoted-printable" -> quotedPrintable(p.body)
        else -> p.body
    }

    private fun splitMultipart(body: ByteArray, boundary: String): List<ByteArray> {
        val s = String(body, Charsets.ISO_8859_1)
        val delim = "--$boundary"
        val parts = ArrayList<ByteArray>()
        var idx = s.indexOf(delim)
        while (idx >= 0) {
            val start = s.indexOf('\n', idx).let { if (it < 0) return parts else it + 1 }
            if (s.startsWith("$delim--", idx)) break
            val next = s.indexOf(delim, start)
            val end = if (next < 0) s.length else next
            var e = end
            if (e > start && s[e - 1] == '\n') e--
            if (e > start && s[e - 1] == '\r') e--
            parts += s.substring(start, maxOf(start, e)).toByteArray(Charsets.ISO_8859_1)
            idx = next
        }
        return parts
    }

    private class Collected { var html: String? = null; var text: String? = null; val inline = HashMap<String, String>(); val attachments = ArrayList<Attachment>() }

    private fun walk(p: Part, c: Collected, depth: Int = 0) {
        val ct = p.headers["content-type"] ?: "text/plain"
        val mime = ct.substringBefore(';').trim().lowercase()
        if (mime.startsWith("multipart/") && depth < 8) {
            val b = param(ct, "boundary") ?: return
            splitMultipart(p.body, b).forEach { walk(splitHeaders(it), c, depth + 1) }
            return
        }
        if (mime == "message/rfc822" && depth < 8) { walk(splitHeaders(decodeBody(p)), c, depth + 1); return }
        val disp = p.headers["content-disposition"].orEmpty()
        val name = param(disp, "filename") ?: param(ct, "name")
        val data = decodeBody(p)
        val cid = p.headers["content-id"]?.trim()?.trim('<', '>')
        when {
            mime == "text/html" && !disp.startsWith("attachment", true) && c.html == null -> c.html = String(data, charset(param(ct, "charset")))
            mime == "text/plain" && !disp.startsWith("attachment", true) && name == null && c.text == null -> c.text = String(data, charset(param(ct, "charset")))
            mime.startsWith("image/") && cid != null -> {
                c.inline[cid] = "data:$mime;base64," + Base64.getEncoder().encodeToString(data)
                if (disp.startsWith("attachment", true)) c.attachments += Attachment(name ?: cid, mime, data)
            }
            else -> c.attachments += Attachment(name ?: "attachment_${c.attachments.size + 1}", mime, data)
        }
    }

    fun read(bytes: ByteArray, fallbackTitle: String, labels: Map<String, String> = emptyMap()): HtmlDoc {
        val root = splitHeaders(bytes)
        val c = Collected()
        walk(root, c)
        val subject = decodeWords(root.headers["subject"].orEmpty()).ifBlank { fallbackTitle }
        val rows = listOf("from", "to", "cc", "date", "subject").mapNotNull { k ->
            root.headers[k]?.let { v -> "<tr><td><b>" + HtmlBits.esc(labels[k] ?: k.replaceFirstChar { it.uppercase() }) + "</b></td><td dir=\"auto\">" + HtmlBits.esc(decodeWords(v)) + "</td></tr>" }
        }.joinToString("")
        var body = c.html?.let { h ->
            var x = h.replace(Regex("""(?is)<script.*?</script>"""), "")
            x = Regex("""(?i)cid:([^"'\s>]+)""").replace(x) { m -> c.inline[m.groupValues[1]] ?: m.value }
            Regex("""(?is)<body[^>]*>(.*)</body>""").find(x)?.groupValues?.get(1) ?: x
        } ?: ("<pre dir=\"auto\">" + HtmlBits.esc(c.text.orEmpty()) + "</pre>")
        if (c.attachments.isNotEmpty()) {
            body += "<hr><p><b>" + HtmlBits.esc(labels["attachments"] ?: "Attachments") + "</b></p><ul>" +
                c.attachments.joinToString("") { "<li>" + HtmlBits.esc(it.name) + " (" + (it.bytes.size / 1024 + 1) + " KB)</li>" } + "</ul>"
        }
        val html = HtmlBits.page(subject, "<table class=\"hdr\">$rows</table>$body", HtmlBits.rtlDominant(subject + (c.text ?: c.html ?: "").take(2000)))
        return HtmlDoc(subject, html, c.attachments)
    }
}

/** EPUB 2/3 → one HTML document in spine order, images inlined as data URIs, scripts dropped. */
object EpubReader {

    private fun resolve(base: String, href: String): String {
        val h = java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        if (h.startsWith("/")) return h.trimStart('/')
        val parts = (base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() } + h.split('/')).toMutableList()
        val out = ArrayList<String>()
        parts.forEach { p ->
            when (p) {
                ".", "" -> {}
                ".." -> { if (out.isNotEmpty()) out.removeAt(out.size - 1) }
                else -> out += p
            }
        }
        return out.joinToString("/")
    }

    private fun attr(tag: String, name: String): String? = Regex("""(?i)\b$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)

    fun read(file: File, maxImageBytes: Int = 4 shl 20): HtmlDoc = ZipFile(file).use { z ->
        fun text(path: String): String? = z.getEntry(path)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
        val container = text("META-INF/container.xml") ?: throw IllegalArgumentException("not an EPUB")
        val opfPath = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(container)?.groupValues?.get(1) ?: throw IllegalArgumentException("no OPF")
        val opf = text(opfPath) ?: throw IllegalArgumentException("no OPF")
        val title = Regex("""(?is)<dc:title[^>]*>(.*?)</dc:title>""").find(opf)?.groupValues?.get(1)?.trim() ?: file.nameWithoutExtension
        val manifest = HashMap<String, Pair<String, String>>()
        Regex("""(?is)<item\b[^>]*>""").findAll(opf).forEach { m ->
            val id = attr(m.value, "id") ?: return@forEach
            val href = attr(m.value, "href") ?: return@forEach
            manifest[id] = resolve(opfPath, href) to (attr(m.value, "media-type") ?: "")
        }
        val spine = Regex("""(?is)<itemref\b[^>]*>""").findAll(opf).mapNotNull { attr(it.value, "idref") }.toList()
        val body = StringBuilder()
        var first = true
        var sample = ""
        spine.forEach { id ->
            val (path, type) = manifest[id] ?: return@forEach
            if (!type.contains("html") && !path.endsWith("html", true) && !path.endsWith(".htm", true)) return@forEach
            val x = text(path) ?: return@forEach
            var inner = Regex("""(?is)<body[^>]*>(.*)</body>""").find(x)?.groupValues?.get(1) ?: x
            inner = inner.replace(Regex("""(?is)<script.*?</script>"""), "")
            inner = Regex("""(?is)<(img|image)\b[^>]*>""").replace(inner) { m ->
                val tag = m.value
                val src = attr(tag, "src") ?: attr(tag, "xlink:href") ?: attr(tag, "href") ?: return@replace tag
                if (src.startsWith("data:") || src.startsWith("http")) return@replace tag
                val p = resolve(path, src)
                val e = z.getEntry(p) ?: return@replace tag
                if (e.size > maxImageBytes) return@replace ""
                val mime = when (p.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"; else -> "image/jpeg" }
                val data = z.getInputStream(e).use { it.readBytes() }
                "<img src=\"data:$mime;base64," + Base64.getEncoder().encodeToString(data) + "\" alt=\"" + HtmlBits.esc(attr(tag, "alt") ?: "") + "\">"
            }
            // internal links between chapters cannot work in one printed document: keep the text
            inner = inner.replace(Regex("""(?i)<a\b[^>]*href\s*=\s*["'](?!https?:)[^"']*["'][^>]*>"""), "<a>")
            if (sample.length < 2000) sample += inner.replace(Regex("<[^>]+>"), " ").take(2000)
            body.append(if (first) "<div>" else "<div class=\"chapter\">").append(inner).append("</div>\n")
            first = false
        }
        HtmlDoc(title, HtmlBits.page(title, body.toString(), HtmlBits.rtlDominant(sample)))
    }
}
