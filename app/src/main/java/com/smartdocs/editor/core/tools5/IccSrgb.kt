package com.smartdocs.editor.core.tools5

import java.io.ByteArrayOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * An sRGB IEC 61966-2.1 display profile (ICC v2.1) generated in code, for the PDF/A OutputIntent
 * (no asset file needed). D50-adapted colorants, 1024-entry sRGB tone curves. Plain Kotlin, unit-tested.
 */
object IccSrgb {

    val bytes: ByteArray by lazy { build() }

    private class Buf { val o = ByteArrayOutputStream()
        fun u32(v: Long) { o.write(((v shr 24) and 0xFF).toInt()); o.write(((v shr 16) and 0xFF).toInt()); o.write(((v shr 8) and 0xFF).toInt()); o.write((v and 0xFF).toInt()) }
        fun u16(v: Int) { o.write((v shr 8) and 0xFF); o.write(v and 0xFF) }
        fun sig(s: String) { require(s.length == 4); s.forEach { o.write(it.code) } }
        fun s15(v: Double) { u32((v * 65536.0).roundToInt().toLong() and 0xFFFFFFFFL) }
        fun pad4() { while (o.size() % 4 != 0) o.write(0) }
        fun bytes(): ByteArray = o.toByteArray()
    }

    private fun xyz(x: Double, y: Double, z: Double): ByteArray = Buf().apply { sig("XYZ "); u32(0); s15(x); s15(y); s15(z) }.bytes()

    private fun text(s: String): ByteArray = Buf().apply { sig("text"); u32(0); s.forEach { o.write(it.code) }; o.write(0) }.bytes()

    private fun desc(s: String): ByteArray = Buf().apply {
        sig("desc"); u32(0)
        u32((s.length + 1).toLong()); s.forEach { o.write(it.code) }; o.write(0)
        u32(0); u32(0)             // Unicode language code + count
        u16(0); o.write(0)          // ScriptCode code + count
        repeat(67) { o.write(0) }   // ScriptCode description
    }.bytes()

    private fun curve(): ByteArray = Buf().apply {
        sig("curv"); u32(0)
        val n = 1024
        u32(n.toLong())
        for (i in 0 until n) {
            val v = i / (n - 1.0)
            val lin = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            u16((lin * 65535.0).roundToInt().coerceIn(0, 65535))
        }
    }.bytes()

    private fun build(): ByteArray {
        // tag data (shared curve for r/g/b TRC)
        val tags = listOf(
            "desc" to desc("sRGB IEC61966-2.1"),
            "cprt" to text("No copyright, use freely"),
            "wtpt" to xyz(0.9642, 1.0, 0.8249),
            "rXYZ" to xyz(0.4361, 0.2225, 0.0139),
            "gXYZ" to xyz(0.3851, 0.7169, 0.0971),
            "bXYZ" to xyz(0.1431, 0.0606, 0.7141),
            "rTRC" to curve(),
        )
        val shared = mapOf("gTRC" to "rTRC", "bTRC" to "rTRC")
        val count = tags.size + shared.size
        val tableSize = 4 + count * 12
        var offset = 128 + tableSize
        val offsets = LinkedHashMap<String, Pair<Int, Int>>()
        val data = Buf()
        tags.forEach { (sig, b) ->
            while ((offset + data.o.size() - (128 + tableSize)) % 4 != 0) data.o.write(0)
            val at = 128 + tableSize + data.o.size()
            data.o.write(b); offsets[sig] = at to b.size
        }
        data.pad4()
        shared.forEach { (sig, to) -> offsets[sig] = offsets[to]!! }
        val total = 128 + tableSize + data.o.size()
        offset = total
        val out = Buf()
        // header
        out.u32(total.toLong()); out.u32(0)                      // size, preferred CMM
        out.u32(0x02100000L)                                      // version 2.1
        out.sig("mntr"); out.sig("RGB "); out.sig("XYZ ")
        out.u16(2024); out.u16(1); out.u16(1); out.u16(0); out.u16(0); out.u16(0)   // date
        out.sig("acsp"); out.u32(0); out.u32(0); out.u32(0); out.u32(0)              // platform, flags, manufacturer, model
        out.u32(0); out.u32(0)                                   // attributes (8 bytes)
        out.u32(0)                                               // rendering intent: perceptual
        out.s15(0.9642); out.s15(1.0); out.s15(0.8249)           // PCS illuminant D50
        out.u32(0)                                               // creator
        repeat(44) { out.o.write(0) }                            // profile id (16) + reserved (28)
        check(out.o.size() == 128)
        out.u32(count.toLong())
        (tags.map { it.first } + shared.keys).forEach { sig ->
            val (at, size) = offsets[sig]!!
            out.sig(sig); out.u32(at.toLong()); out.u32(size.toLong())
        }
        out.o.write(data.bytes())
        return out.bytes()
    }
}

/** XMP packet for PDF/A (pdfaid), optionally PDF/UA (pdfuaid). Values are XML-escaped. Plain Kotlin. */
object XmpWriter {

    fun esc(s: String): String = buildString { s.forEach { c -> when (c) { '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;"); else -> if (c.code >= 0x20 || c == '\t' || c == '\n') append(c) } } }

    /** ISO 8601 with offset from a java Calendar ("2024-05-01T10:20:30+03:00"). */
    fun isoDate(cal: java.util.Calendar): String {
        val off = (cal.get(java.util.Calendar.ZONE_OFFSET) + cal.get(java.util.Calendar.DST_OFFSET)) / 60000
        val sign = if (off >= 0) "+" else "-"; val a = kotlin.math.abs(off)
        return String.format(java.util.Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d%s%02d:%02d",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND), sign, a / 60, a % 60,
        )
    }

    fun packet(
        title: String?, author: String?, subject: String?, keywords: String?, creatorTool: String, producer: String,
        created: String, modified: String, part: Int = 2, conformance: String = "B", uaPart: Int? = null, lang: String = "x-default",
        /** FINAL_CHECK_2 Low: a PDF/X version ("PDF/X-4") or PDF/E version ("PDF/E-1") claimed instead of PDF/A (part is then ignored). */
        pdfx: String? = null, pdfe: String? = null,
    ): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"")
        append(" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"")
        if (uaPart != null) append(" xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\"")
        if (pdfx != null) append(" xmlns:pdfxid=\"http://www.npes.org/pdfx/ns/id/\"")
        if (pdfe != null) append(" xmlns:pdfe=\"http://www.aiim.org/pdfe/ns/id/\"")
        append(">\n")
        append("<dc:format>application/pdf</dc:format>\n")
        if (!title.isNullOrBlank()) append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(esc(title)).append("</rdf:li></rdf:Alt></dc:title>\n")
        if (!author.isNullOrBlank()) append("<dc:creator><rdf:Seq><rdf:li>").append(esc(author)).append("</rdf:li></rdf:Seq></dc:creator>\n")
        if (!subject.isNullOrBlank()) append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(esc(subject)).append("</rdf:li></rdf:Alt></dc:description>\n")
        if (!keywords.isNullOrBlank()) append("<pdf:Keywords>").append(esc(keywords)).append("</pdf:Keywords>\n")
        append("<pdf:Producer>").append(esc(producer)).append("</pdf:Producer>\n")
        append("<xmp:CreatorTool>").append(esc(creatorTool)).append("</xmp:CreatorTool>\n")
        append("<xmp:CreateDate>").append(created).append("</xmp:CreateDate>\n")
        append("<xmp:ModifyDate>").append(modified).append("</xmp:ModifyDate>\n")
        append("<xmp:MetadataDate>").append(modified).append("</xmp:MetadataDate>\n")
        when {
            pdfx != null -> append("<pdfxid:GTS_PDFXVersion>").append(esc(pdfx)).append("</pdfxid:GTS_PDFXVersion>\n")
            pdfe != null -> append("<pdfe:ISO_PDFEVersion>").append(esc(pdfe)).append("</pdfe:ISO_PDFEVersion>\n")
            else -> {
                append("<pdfaid:part>").append(part).append("</pdfaid:part>\n")
                if (part >= 4) append("<pdfaid:rev>2020</pdfaid:rev>\n")
                if (conformance.isNotBlank()) append("<pdfaid:conformance>").append(conformance).append("</pdfaid:conformance>\n")
            }
        }
        if (uaPart != null) append("<pdfuaid:part>").append(uaPart).append("</pdfuaid:part>\n")
        append("</rdf:Description>\n</rdf:RDF>\n</x:xmpmeta>\n")
        repeat(20) { append("                                                                                \n") }
        append("<?xpacket end=\"w\"?>")
    }
}
