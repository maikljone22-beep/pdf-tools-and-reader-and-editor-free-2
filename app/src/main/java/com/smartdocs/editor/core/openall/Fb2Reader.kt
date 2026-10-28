package com.smartdocs.editor.core.openall

import org.xml.sax.Attributes
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.util.zip.ZipInputStream

/** A book turned into one HTML page plus its table of contents (anchors `#s1`, `#s2`…). */
class HtmlBook(val title: String, val author: String, val rtl: Boolean, val html: String, val toc: List<TocItem>)

/** Shared HTML shell of the book-like viewers (FB2, MOBI). */
object BookHtml {
    fun esc(s: String): String = buildString(s.length + 8) {
        s.forEach { c -> when (c) { '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;"); else -> append(c) } }
    }

    fun rtlDominant(s: String): Boolean {
        var r = 0; var l = 0
        for (c in s) { if (c in '\u0590'..'\u08FF' || c in '\uFB1D'..'\uFDFF' || c in '\uFE70'..'\uFEFF') r++ else if (c.isLetter()) l++ }
        return r > l
    }

    fun page(title: String, body: String, rtl: Boolean, lang: String = ""): String =
        "<!DOCTYPE html><html dir=\"" + (if (rtl) "rtl" else "ltr") + "\"" + (if (lang.isNotBlank()) " lang=\"" + esc(lang) + "\"" else "") +
            "><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" + esc(title) + "</title>" +
            "<style>body{font-family:'Noto Naskh Arabic','Noto Sans',sans-serif;line-height:1.7;margin:0 auto;max-width:860px;padding:12px 16px;word-wrap:break-word}" +
            "img{max-width:100%;height:auto;display:block;margin:8px auto}h1,h2,h3{line-height:1.3}.epigraph,.cite{margin:8px 24px;font-style:italic}" +
            ".poem{margin:8px 24px}.author{text-align:end;font-style:italic}table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px}" +
            "a.note{vertical-align:super;font-size:75%}</style></head><body>" + body + "</body></html>"
}

/** FictionBook 2 (.fb2, .fb2.zip) → [HtmlBook]. SAX with [SafeXml] hardening. Pure. */
object Fb2Reader {

    private const val XLINK = "http://www.w3.org/1999/xlink"

    fun unzipIfNeeded(bytes: ByteArray, maxBytes: Long = 200L * 1024 * 1024): ByteArray {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return bytes
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".fb2", true)) {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(65536); var total = 0L
                    while (true) { val r = z.read(buf); if (r <= 0) break; total += r; if (total > maxBytes) throw ArchiveLimitException("size"); out.write(buf, 0, r) }
                    return out.toByteArray()
                }
            }
        }
        throw IllegalArgumentException("no .fb2 inside")
    }

    fun read(bytes0: ByteArray, fallbackTitle: String = ""): HtmlBook {
        val bytes = unzipIfNeeded(bytes0)
        val body = StringBuilder()
        val binaries = HashMap<String, String>()
        val toc = ArrayList<TocItem>()
        var title = ""; var lang = ""
        val authorParts = ArrayList<String>()
        var sectionCount = 0

        val h = object : DefaultHandler() {
            val stack = ArrayList<String>()
            var inDescription = false; var inTitleInfo = false; var inAuthor = false
            var sectionDepth = 0
            var titleBuf: StringBuilder? = null
            var pendingAnchor: String? = null
            var binId: String? = null; var binType = ""; val bin = StringBuilder()
            val meta = StringBuilder()

            fun href(a: Attributes): String? {
                a.getValue(XLINK, "href")?.let { return it }
                for (i in 0 until a.length) if (a.getLocalName(i) == "href" || a.getQName(i).endsWith(":href")) return a.getValue(i)
                return null
            }

            override fun startElement(uri: String?, local: String, qName: String?, a: Attributes) {
                val n = local.ifEmpty { qName?.substringAfter(':') ?: "" }
                stack += n
                if (binId != null) return
                when (n) {
                    "description" -> inDescription = true
                    "title-info" -> inTitleInfo = true
                    "author" -> if (inTitleInfo) inAuthor = true
                    "book-title", "first-name", "middle-name", "last-name", "lang" -> if (inTitleInfo) meta.setLength(0)
                    "binary" -> { binId = a.getValue("id"); binType = a.getValue("content-type") ?: "image/jpeg"; bin.setLength(0) }
                }
                if (inDescription) {
                    if (n == "image" && stack.contains("coverpage")) href(a)?.let { body.append("<img src=\"fb2img:").append(BookHtml.esc(it.removePrefix("#"))).append("\">") }
                    return
                }
                when (n) {
                    "body" -> if (a.getValue("name") == "notes") body.append("<hr>")
                    "section" -> { sectionDepth++; sectionCount++; pendingAnchor = "s$sectionCount"; body.append("<div class=\"section\" id=\"").append(pendingAnchor).append("\"")
                        a.getValue("id")?.let { body.append(" data-id=\"").append(BookHtml.esc(it)).append("\"") }; body.append(">")
                        a.getValue("id")?.let { body.append("<a id=\"").append(BookHtml.esc(it)).append("\"></a>") } }
                    "title" -> { val lvl = (sectionDepth + 1).coerceIn(1, 6); body.append("<h$lvl>"); titleBuf = StringBuilder() }
                    "subtitle" -> body.append("<h4>")
                    "p" -> body.append("<p>")
                    "v" -> body.append("<div>")
                    "stanza" -> body.append("<div class=\"stanza\">")
                    "poem" -> body.append("<div class=\"poem\">")
                    "epigraph" -> body.append("<div class=\"epigraph\">")
                    "cite" -> body.append("<blockquote class=\"cite\">")
                    "text-author" -> body.append("<div class=\"author\">")
                    "emphasis" -> body.append("<i>")
                    "strong" -> body.append("<b>")
                    "strikethrough" -> body.append("<s>")
                    "sub" -> body.append("<sub>")
                    "sup" -> body.append("<sup>")
                    "code" -> body.append("<code>")
                    "empty-line" -> body.append("<br>")
                    "table" -> body.append("<table>")
                    "tr" -> body.append("<tr>")
                    "td", "th" -> body.append("<$n>")
                    "a" -> {
                        val link = href(a).orEmpty()
                        val note = a.getValue("type") == "note"
                        body.append("<a").append(if (note) " class=\"note\"" else "").append(" href=\"").append(BookHtml.esc(if (link.startsWith("#")) link else "#")).append("\">")
                    }
                    "image" -> href(a)?.let { body.append("<img alt=\"\" src=\"fb2img:").append(BookHtml.esc(it.removePrefix("#"))).append("\">") }
                }
            }

            override fun endElement(uri: String?, local: String, qName: String?) {
                val n = local.ifEmpty { qName?.substringAfter(':') ?: "" }
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                if (n == "binary" && binId != null) {
                    binaries[binId!!] = "data:" + binType + ";base64," + bin.toString().replace(Regex("""\s"""), "")
                    binId = null; bin.setLength(0); return
                }
                if (binId != null) return
                if (inDescription) {
                    when (n) {
                        "book-title" -> if (inTitleInfo) title = meta.toString().trim()
                        "first-name", "middle-name", "last-name" -> if (inAuthor && authorParts.size < 6) meta.toString().trim().takeIf { it.isNotEmpty() }?.let { authorParts += it }
                        "lang" -> if (inTitleInfo) lang = meta.toString().trim()
                        "author" -> inAuthor = false
                        "title-info" -> inTitleInfo = false
                        "description" -> inDescription = false
                    }
                    return
                }
                when (n) {
                    "section" -> { sectionDepth--; body.append("</div>") }
                    "title" -> {
                        body.append("</h${(sectionDepth + 1).coerceIn(1, 6)}>")
                        val t = titleBuf?.toString()?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
                        if (t.isNotEmpty() && pendingAnchor != null) toc += TocItem(t, "", pendingAnchor!!, (sectionDepth - 1).coerceAtLeast(0))
                        titleBuf = null; pendingAnchor = null
                    }
                    "subtitle" -> body.append("</h4>")
                    "p" -> { body.append("</p>"); titleBuf?.append(' ') }
                    "v", "stanza", "poem", "epigraph", "text-author" -> body.append("</div>")
                    "cite" -> body.append("</blockquote>")
                    "emphasis" -> body.append("</i>")
                    "strong" -> body.append("</b>")
                    "strikethrough" -> body.append("</s>")
                    "sub" -> body.append("</sub>")
                    "sup" -> body.append("</sup>")
                    "code" -> body.append("</code>")
                    "table" -> body.append("</table>")
                    "tr" -> body.append("</tr>")
                    "td", "th" -> body.append("</$n>")
                    "a" -> body.append("</a>")
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (binId != null) { bin.append(ch, start, length); return }
                if (inDescription) { meta.append(ch, start, length); return }
                if (stack.lastOrNull() in setOf("body", "section", "table", "tr", "poem", "stanza", "epigraph", "cite", "title")) {
                    // whitespace between block elements
                    if (String(ch, start, length).isBlank()) return
                }
                val s = String(ch, start, length)
                body.append(BookHtml.esc(s))
                titleBuf?.append(s)
            }

            override fun error(e: SAXParseException) {}
        }
        SafeXml.parse(bytes, h)
        var html = body.toString()
        html = Regex("""src="fb2img:([^"]*)"""").replace(html) { m -> "src=\"" + (binaries[EpubParser.unescape(m.groupValues[1])] ?: "") + "\"" }
        val t = title.ifBlank { fallbackTitle }
        val rtl = lang.lowercase().let { it.startsWith("ar") || it.startsWith("fa") || it.startsWith("he") || it.startsWith("ur") } || BookHtml.rtlDominant(html.take(20000).replace(Regex("<[^>]*>"), ""))
        val author = authorParts.joinToString(" ")
        val head = "<h1>" + BookHtml.esc(t) + "</h1>" + (if (author.isNotBlank()) "<p class=\"author\">" + BookHtml.esc(author) + "</p>" else "")
        return HtmlBook(t, author, rtl, BookHtml.page(t, head + html, rtl, lang), toc)
    }
}
