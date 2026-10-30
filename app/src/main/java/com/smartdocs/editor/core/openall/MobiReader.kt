package com.smartdocs.editor.core.openall

import java.util.Base64

/** The book cannot be shown here; [reason] is a stable code ("drm", "huffcdic", "format"). */
class UnsupportedBookException(val reason: String) : Exception(reason)

/**
 * MOBI / PRC / AZW / AZW3 (Mobipocket, PalmDOC compression) → [HtmlBook]. Own decoder of the public PalmDB / MOBI
 * headers; HUFF/CDIC-compressed and DRM-protected books are refused with [UnsupportedBookException]. KF8 (AZW3)
 * books come out as one reflowed flow (their skeleton/fragment layout is not rebuilt), with their pictures placed
 * (fix/files-index: "kindle:embed" resources). Pure.
 */
object MobiReader {

    private fun u16(b: ByteArray, o: Int) = if (o + 2 <= b.size) ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF) else 0
    private fun u32(b: ByteArray, o: Int): Long = if (o + 4 <= b.size) ((u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()) else 0

    /** PalmDOC LZ77 variant. */
    fun palmDoc(src: ByteArray, from: Int = 0, to: Int = src.size, maxOut: Int = 65536): ByteArray {
        val out = java.io.ByteArrayOutputStream(4096)
        val buf = ByteArray(maxOut); var n = 0
        var i = from
        while (i < to && n < maxOut) {
            val c = src[i++].toInt() and 0xFF
            when {
                c == 0 || c in 0x09..0x7F -> buf[n++] = c.toByte()
                c in 1..8 -> { var k = c; while (k-- > 0 && i < to && n < maxOut) buf[n++] = src[i++] }
                c >= 0xC0 -> { buf[n++] = ' '.code.toByte(); if (n < maxOut) buf[n++] = (c xor 0x80).toByte() }
                else -> {
                    if (i >= to) break
                    val m = ((c shl 8) or (src[i++].toInt() and 0xFF)) and 0x3FFF
                    val dist = m shr 3; val len = (m and 7) + 3
                    if (dist <= 0 || dist > n) continue
                    var k = 0
                    while (k < len && n < maxOut) { buf[n] = buf[n - dist]; n++; k++ }
                }
            }
        }
        out.write(buf, 0, n)
        return out.toByteArray()
    }

    private fun trailingEntrySize(b: ByteArray, end: Int): Int {
        var bitpos = 0; var result = 0; var p = end
        while (p > 0) {
            val v = b[p - 1].toInt() and 0xFF
            result = result or ((v and 0x7F) shl bitpos)
            bitpos += 7; p--
            if ((v and 0x80) != 0 || bitpos >= 28) break
        }
        return result
    }

    /** Size of the trailing data entries ([flags] = extra record data flags of the MOBI header). */
    fun trailingSize(b: ByteArray, start: Int, end: Int, flags: Int): Int {
        var num = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) { val s = trailingEntrySize(b, end - num); if (s <= 0 || s > end - start - num) return num; num += s }
            f = f shr 1
        }
        if (flags and 1 != 0 && end - num - 1 >= start) num += ((b[end - num - 1].toInt() and 0xFF) and 0x3) + 1
        return num.coerceAtMost(end - start)
    }

    class Info(val title: String, val compression: Int, val encrypted: Boolean, val utf8: Boolean, val kf8: Boolean)

    fun read(b: ByteArray, fallbackTitle: String = ""): HtmlBook {
        if (b.size < 78 + 8) throw UnsupportedBookException("format")
        val type = String(b, 60, 8, Charsets.ISO_8859_1)
        if (type != "BOOKMOBI" && type != "TEXtREAd") throw UnsupportedBookException("format")
        val nRec = u16(b, 76)
        val offsets = IntArray(nRec) { u32(b, 78 + it * 8).toInt() }
        fun rec(i: Int): IntRange {
            val s = offsets.getOrElse(i) { b.size }.coerceIn(0, b.size)
            val e = (if (i + 1 < nRec) offsets[i + 1] else b.size).coerceIn(s, b.size)
            return s until e
        }
        val r0 = rec(0).first
        val compression = u16(b, r0)
        val textRecords = u16(b, r0 + 8)
        val encryption = u16(b, r0 + 12)
        if (encryption != 0) throw UnsupportedBookException("drm")
        if (compression == 17480) throw UnsupportedBookException("huffcdic")
        if (compression != 1 && compression != 2) throw UnsupportedBookException("format")
        var utf8 = false; var title = String(b, 0, 32, Charsets.ISO_8859_1).trimEnd('\u0000').replace('_', ' ')
        var extraFlags = 0; var firstImage = -1; var kf8 = false
        val isMobi = String(b, r0 + 16, 4, Charsets.ISO_8859_1) == "MOBI"
        if (isMobi) {
            val hl = u32(b, r0 + 20).toInt()
            utf8 = u32(b, r0 + 28) == 65001L
            val version = u32(b, r0 + 36)
            kf8 = version >= 8
            val nameOff = u32(b, r0 + 84).toInt(); val nameLen = u32(b, r0 + 88).toInt()
            if (nameLen in 1..1000 && r0 + nameOff + nameLen <= b.size) title = String(b, r0 + nameOff, nameLen, if (utf8) Charsets.UTF_8 else TextDecode.WIN1252)
            firstImage = u32(b, r0 + 108).toInt().takeIf { it in 1 until nRec } ?: -1
            if (hl >= 0xE4 && r0 + 16 + 0xE4 <= b.size) extraFlags = u16(b, r0 + 0xF2)
        }
        val text = java.io.ByteArrayOutputStream()
        for (i in 1..minOf(textRecords, nRec - 1)) {
            val r = rec(i)
            if (r.isEmpty()) continue
            val end = r.last + 1 - trailingSize(b, r.first, r.last + 1, extraFlags)
            if (compression == 2) text.write(palmDoc(b, r.first, end)) else text.write(b, r.first, end - r.first)
            if (text.size() > 200 * 1024 * 1024) break
        }
        val raw = text.toByteArray()
        val cs = if (utf8) Charsets.UTF_8 else if (TextDecode.isUtf8(raw)) Charsets.UTF_8 else TextDecode.singleByteGuess(raw)
        var html = String(raw, cs)
        if (!isMobi && !html.contains('<')) html = "<pre>" + BookHtml.esc(html) + "</pre>"   // plain PalmDOC text
        // images: <img recindex="00003"> → data URI of record firstImage + n - 1
        fun imageUri(n: Int): String? {
            if (firstImage <= 0 || n < 1) return null
            val r = rec(firstImage + n - 1)
            if (r.isEmpty() || r.count() > 20 * 1024 * 1024) return null
            val bytes = b.copyOfRange(r.first, r.last + 1)
            val ext = FileSniffer.imageExt(bytes) ?: return null
            return "data:image/$ext;base64," + Base64.getEncoder().encodeToString(bytes)
        }
        if (firstImage > 0) {
            html = Regex("""(?i)<img\b([^>]*?)recindex\s*=\s*["']?(\d+)["']?([^>]*)>""").replace(html) { m ->
                imageUri(m.groupValues[2].toIntOrNull() ?: 0)?.let { "<img src=\"$it\">" } ?: ""
            }
            // fix/files-index: KF8 (AZW3) resources "kindle:embed:XXXX?mime=…" (base-32 resource number, 1-based)
            html = kindleEmbeds(html) { n -> imageUri(n) }
        }
        html = html.replace(Regex("""(?i)<mbp:pagebreak\s*/?>"""), "<hr>")
            .replace(Regex("""(?is)<script\b.*?</script\s*>"""), "")
            .replace(Regex("""(?i)\son\w+\s*=\s*(["']).*?\1"""), "")
            .replace(Regex("""(?i)</?(mbp:[\w-]+|html|head|body)\b[^>]*>"""), "")
            // KF8 image references that did not resolve to a picture record
            .replace(Regex("""(?i)<img\b[^>]*kindle:embed[^>]*>"""), "")
        val t = title.ifBlank { fallbackTitle }
        val plain = html.take(30000).replace(Regex("<[^>]*>"), "")
        val rtl = BookHtml.rtlDominant(plain)
        // headings become the table of contents
        val toc = ArrayList<TocItem>(); var k = 0
        html = Regex("""(?is)<h([1-3])\b([^>]*)>(.*?)</h\1>""").replace(html) { m ->
            k++
            val label = EpubParser.unescape(m.groupValues[3].replace(Regex("<[^>]*>"), "")).trim()
            if (label.isNotEmpty() && toc.size < 2000) toc += TocItem(label.take(120), "", "h$k", m.groupValues[1].toInt() - 1)
            "<h${m.groupValues[1]} id=\"h$k\"${m.groupValues[2]}>${m.groupValues[3]}</h${m.groupValues[1]}>"
        }
        return HtmlBook(t, "", rtl, BookHtml.page(t, html, rtl), toc)
    }

    /** Base-32 number of a KF8 resource reference ("0001" … "VVVV", digits 0-9 A-V). */
    fun base32(s: String): Int? = s.uppercase().fold(0) { acc, c ->
        val d = when (c) { in '0'..'9' -> c - '0'; in 'A'..'V' -> c - 'A' + 10; else -> return null }
        acc * 32 + d
    }

    /** Every "kindle:embed:XXXX[?mime=…]" reference → [uri] of that resource number; references that do not resolve stay. */
    fun kindleEmbeds(html: String, uri: (Int) -> String?): String =
        Regex("""kindle:embed:([0-9A-Va-v]{1,6})(\?mime=[\w/+.-]*)?""").replace(html) { m -> base32(m.groupValues[1])?.let(uri) ?: m.value }

    fun info(b: ByteArray): Info? = runCatching {
        val nRec = u16(b, 76); if (nRec < 1) return null
        val r0 = u32(b, 78).toInt()
        Info(String(b, 0, 32, Charsets.ISO_8859_1).trimEnd('\u0000'), u16(b, r0), u16(b, r0 + 12) != 0, u32(b, r0 + 28) == 65001L, u32(b, r0 + 36) >= 8)
    }.getOrNull()
}
