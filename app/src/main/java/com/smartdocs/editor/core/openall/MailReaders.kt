package com.smartdocs.editor.core.openall

import com.smartdocs.editor.core.convert6.CfbReader
import java.nio.charset.Charset

class MailAttachment(val name: String, val mime: String, val bytes: ByteArray, val cid: String? = null)

/** An e-mail ready for the viewer: header lines (label key → value), the body as HTML, and its attachments. */
class MailDoc(val subject: String, val headers: List<Pair<String, String>>, val bodyHtml: String?, val bodyText: String?, val attachments: List<MailAttachment>)

/**
 * Outlook .msg (OLE2 / MAPI property streams) reader: subject, sender, recipients, date, plain and HTML body,
 * attachments with inline content ids. Own reader over [CfbReader] — no Apache POI. Pure.
 */
object MsgReader {

    private fun prop(name: String): Pair<Int, Int>? {
        if (!name.startsWith("__substg1.0_", true) || name.length < 20) return null
        val hex = name.substring(12, 20)
        return hex.substring(0, 4).toIntOrNull(16)?.let { id -> hex.substring(4, 8).toIntOrNull(16)?.let { id to it } }
    }

    private class Props(val strings: Map<Int, ByteArray>, val types: Map<Int, Int>)

    private fun propsOf(cfb: CfbReader, storage: Int): Props {
        val strings = HashMap<Int, ByteArray>(); val types = HashMap<Int, Int>()
        for (e in cfb.children(storage)) {
            if (e.type != 2) continue
            val (id, type) = prop(e.name) ?: continue
            if (e.size > 64L * 1024 * 1024) continue
            strings[id] = cfb.streamOf(e); types[id] = type
        }
        return Props(strings, types)
    }

    /** Fixed-size property [tag] (id shl 16 | type) from a `__properties_version1.0` stream; [header] = 32 (message) or 8. */
    private fun fixedProp(cfb: CfbReader, storage: Int, tag: Long, header: Int): Long? {
        val e = cfb.children(storage).firstOrNull { it.type == 2 && it.name.equals("__properties_version1.0", true) } ?: return null
        val b = cfb.streamOf(e)
        var o = header
        while (o + 16 <= b.size) {
            val t = CfbReader.le32(b, o).toLong() and 0xFFFFFFFFL
            if (t == tag) return (CfbReader.le32(b, o + 8).toLong() and 0xFFFFFFFFL) or (CfbReader.le32(b, o + 12).toLong() shl 32)
            o += 16
        }
        return null
    }

    fun read(bytes: ByteArray): MailDoc {
        val cfb = CfbReader(bytes)
        val root = 0
        val cp = (fixedProp(cfb, root, 0x3FFD0003L, 32) ?: fixedProp(cfb, root, 0x3FDE0003L, 32))?.toInt()
        val ansi: Charset = cp?.let { c -> if (c == 65001) Charsets.UTF_8 else runCatching { Charset.forName("windows-$c") }.recoverCatching { Charset.forName("cp$c") }.getOrNull() } ?: TextDecode.WIN1252
        val p = propsOf(cfb, root)
        fun str(id: Int): String? {
            val b = p.strings[id] ?: return null
            return when (p.types[id]) {
                0x001F -> String(b, Charsets.UTF_16LE).trimEnd('\u0000')
                0x001E -> String(b, if (TextDecode.isUtf8(b)) Charsets.UTF_8 else ansi).trimEnd('\u0000')
                0x0102 -> TextDecode.decode(b, truncated = false).text.trimEnd('\u0000')
                else -> null
            }
        }
        val subject = str(0x0037) ?: str(0x0E1D) ?: ""
        val fromName = str(0x0C1A) ?: str(0x0042)
        val fromAddr = str(0x5D01) ?: str(0x0C1F) ?: str(0x0065)
        val headersRaw = str(0x007D)
        val submit = fixedProp(cfb, root, 0x00390040L, 32) ?: fixedProp(cfb, root, 0x0E060040L, 32)
        val date = submit?.takeIf { it > 0 }?.let { ft -> java.util.Date(ft / 10_000 - 11_644_473_600_000L).toString() }
            ?: headersRaw?.let { Regex("""(?im)^Date:\s*(.+)$""").find(it)?.groupValues?.get(1)?.trim() }
        // recipients: display lists, or each recipient storage
        var to = str(0x0E04); var cc = str(0x0E03)
        if (to.isNullOrBlank()) {
            val rec = cfb.children(root).filter { it.type == 1 && it.name.startsWith("__recip_version1.0_", true) }.map { r ->
                val rp = propsOf(cfb, cfb.indexOf(r))
                fun rs(id: Int) = rp.strings[id]?.let { if (rp.types[id] == 0x001F) String(it, Charsets.UTF_16LE) else String(it, ansi) }?.trimEnd('\u0000')
                listOfNotNull(rs(0x3001), (rs(0x39FE) ?: rs(0x3003))?.let { "<$it>" }).joinToString(" ")
            }
            to = rec.joinToString(", ").ifBlank { null }
        }
        val html = p.strings[0x1013]?.let { b ->
            if (p.types[0x1013] == 0x001F) String(b, Charsets.UTF_16LE)
            else {
                val decl = Regex("""(?i)charset\s*=\s*["']?([\w-]+)""").find(String(b, 0, minOf(b.size, 2048), Charsets.ISO_8859_1))?.groupValues?.get(1)
                val cs = decl?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: if (TextDecode.isUtf8(b)) Charsets.UTF_8 else ansi
                String(b, cs)
            }
        }?.trimEnd('\u0000')
        val text = str(0x1000)
        val atts = cfb.children(root).filter { it.type == 1 && it.name.startsWith("__attach_version1.0_", true) }.mapNotNull { a ->
            val ap = propsOf(cfb, cfb.indexOf(a))
            fun s(id: Int) = ap.strings[id]?.let { if (ap.types[id] == 0x001F) String(it, Charsets.UTF_16LE) else String(it, ansi) }?.trimEnd('\u0000')
            val data = ap.strings[0x3701]?.takeIf { ap.types[0x3701] == 0x0102 } ?: return@mapNotNull null
            val name = s(0x3707) ?: s(0x3704) ?: s(0x3001) ?: "attachment"
            MailAttachment(name, s(0x370E) ?: "application/octet-stream", data, s(0x3712)?.trim('<', '>'))
        }
        val headers = listOfNotNull(
            (listOfNotNull(fromName, fromAddr?.let { "<$it>" }).joinToString(" ")).takeIf { it.isNotBlank() }?.let { "from" to it },
            to?.takeIf { it.isNotBlank() }?.let { "to" to it }, cc?.takeIf { it.isNotBlank() }?.let { "cc" to it },
            date?.let { "date" to it }, subject.takeIf { it.isNotBlank() }?.let { "subject" to it },
        )
        return MailDoc(subject, headers, html, text, atts)
    }
}

/** Builds the viewer page of a [MailDoc]: header table, sanitized body with inline images, attachment list. */
object MailHtml {
    fun stripActive(html: String): String = html
        .replace(Regex("""(?is)<script\b.*?</script\s*>"""), "")
        .replace(Regex("""(?is)<(iframe|object|embed|frame|frameset)\b.*?(</\1\s*>|/>)"""), "")
        .replace(Regex("""(?i)\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)"""), "")
        .replace(Regex("""(?i)javascript:"""), "blocked:")
        .replace(Regex("""(?is)<meta\b[^>]*http-equiv\s*=\s*["']?refresh[^>]*>"""), "")

    fun page(m: MailDoc, labels: Map<String, String>): String {
        val inline = HashMap<String, String>()
        m.attachments.forEach { a -> a.cid?.let { inline[it] = "data:${a.mime};base64," + java.util.Base64.getEncoder().encodeToString(a.bytes) } }
        val rows = m.headers.joinToString("") { (k, v) -> "<tr><td><b>" + BookHtml.esc(labels[k] ?: k) + "</b></td><td dir=\"auto\">" + BookHtml.esc(v) + "</td></tr>" }
        val body = m.bodyHtml?.let { h ->
            var x = stripActive(h)
            x = Regex("""(?i)cid:([^"'\s>]+)""").replace(x) { mm -> inline[mm.groupValues[1]] ?: mm.value }
            Regex("""(?is)<body[^>]*>(.*)</body>""").find(x)?.groupValues?.get(1) ?: x
        } ?: ("<pre style=\"white-space:pre-wrap;font-family:inherit\" dir=\"auto\">" + BookHtml.esc(m.bodyText.orEmpty()) + "</pre>")
        val rtl = BookHtml.rtlDominant(m.subject + " " + (m.bodyText ?: m.bodyHtml?.replace(Regex("<[^>]*>"), "") ?: "").take(3000))
        return BookHtml.page(m.subject, "<table class=\"hdr\" style=\"border:none;margin-bottom:10px\">$rows</table><hr>$body", rtl)
            .replace("td,th{border:1px solid #999;padding:4px}", "td,th{padding:4px}")
    }
}
