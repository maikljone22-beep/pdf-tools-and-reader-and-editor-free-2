package com.smartdocs.editor.core.convert6

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Nitro 6 / Foxit 6 export formats the hub lacked, as pure writers (JVM-tested): multi-page TIFF (RGB, grey or
 * black & white, PackBits), BMP, PostScript / EPS (page pictures, DCT), XPS (page pictures), XML 1.0 (text with
 * positions), Excel XML Spreadsheet 2003, and the HTML set split at chapters with a navigation page.
 */

/** One page picture: [w]×[h] pixels, [argb] pixels; [dpi] for the resolution tags. */
class Raster(val w: Int, val h: Int, val argb: IntArray, val dpi: Int = 150)

enum class TiffColor { RGB, GRAY, BW }

object TiffWriter {
    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /** Otsu threshold of a grey histogram (0..255). */
    fun otsu(hist: IntArray): Int {
        val total = hist.sum().coerceAtLeast(1)
        var sum = 0.0; for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0; var best = 0.0; var t = 128
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = i }
        }
        return t
    }

    /** Pixel bytes of one strip-less image in [color]: RGB 3 bytes, GRAY 1 byte, BW 1 bit (0 = white, PhotometricInterpretation 0). */
    fun samples(r: Raster, color: TiffColor): ByteArray = when (color) {
        TiffColor.RGB -> ByteArray(r.w * r.h * 3).also { b -> r.argb.forEachIndexed { i, c -> b[i * 3] = (c shr 16).toByte(); b[i * 3 + 1] = (c shr 8).toByte(); b[i * 3 + 2] = c.toByte() } }
        TiffColor.GRAY -> ByteArray(r.w * r.h) { lum(r.argb[it]).toByte() }
        TiffColor.BW -> {
            val hist = IntArray(256); r.argb.forEach { hist[lum(it)]++ }
            val t = otsu(hist)
            val row = (r.w + 7) / 8
            ByteArray(row * r.h).also { b ->
                for (y in 0 until r.h) for (x in 0 until r.w) if (lum(r.argb[y * r.w + x]) <= t) {
                    val i = y * row + x / 8; b[i] = (b[i].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
        }
    }

    /** PackBits (TIFF compression 32773) of [data], row by row of [rowBytes]. */
    fun packBits(data: ByteArray, rowBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 16)
        var r = 0
        while (r < data.size) {
            val end = minOf(r + rowBytes, data.size)
            var i = r
            while (i < end) {
                var run = 1
                while (i + run < end && run < 128 && data[i + run] == data[i]) run++
                if (run >= 2) { out.write(1 - run); out.write(data[i].toInt()); i += run; continue }
                val start = i; var lit = 0
                while (i < end && lit < 128 && !(i + 1 < end && data[i + 1] == data[i])) { i++; lit++ }
                if (lit == 0) { i++; lit = 1 }
                out.write(lit - 1); out.write(data, start, lit)
            }
            r = end
        }
        return out.toByteArray()
    }

    /** One page compressed ahead (the raster can be dropped right away). */
    class Encoded(val w: Int, val h: Int, val dpi: Int, val data: ByteArray)

    fun encode(r: Raster, color: TiffColor): Encoded {
        val spp = if (color == TiffColor.RGB) 3 else 1
        val rowBytes = if (color == TiffColor.BW) (r.w + 7) / 8 else r.w * spp
        return Encoded(r.w, r.h, r.dpi, packBits(samples(r, color), rowBytes))
    }

    fun write(pages: List<Raster>, color: TiffColor, out: OutputStream) = writeEncoded(pages.map { encode(it, color) }, color, out)

    /** Multi-page baseline TIFF (little endian, one strip per page, PackBits). */
    fun writeEncoded(pages: List<Encoded>, color: TiffColor, out: OutputStream) {
        val buf = ByteArrayOutputStream()
        fun u16(v: Int) { buf.write(v and 0xFF); buf.write((v shr 8) and 0xFF) }
        fun u32(v: Long) { for (k in 0 until 4) buf.write(((v shr (8 * k)) and 0xFF).toInt()) }
        buf.write('I'.code); buf.write('I'.code); u16(42); u32(8)
        pages.forEachIndexed { pi, r ->
            val spp = if (color == TiffColor.RGB) 3 else 1
            val bps = if (color == TiffColor.BW) 1 else 8
            val data = r.data
            val tags = ArrayList<Triple<Int, Int, Long>>()   // tag, type, value (or offset)
            val ifdStart = buf.size().toLong()
            val nTags = if (color == TiffColor.RGB) 14 else 13
            val extra = ifdStart + 2 + nTags * 12 + 4           // data area after the IFD
            var cursor = extra
            val bpsOffset = cursor; if (color == TiffColor.RGB) cursor += 6
            val xRes = cursor; cursor += 8; val yRes = cursor; cursor += 8
            val dataOffset = cursor; cursor += data.size
            val next = if (pi < pages.lastIndex) (cursor + (cursor and 1)) else 0L
            tags += Triple(254, 4, 0L)                                     // NewSubfileType
            tags += Triple(256, 4, r.w.toLong()); tags += Triple(257, 4, r.h.toLong())
            tags += Triple(258, 3, if (color == TiffColor.RGB) bpsOffset else bps.toLong())
            tags += Triple(259, 3, 32773L)                                 // PackBits
            tags += Triple(262, 3, when (color) { TiffColor.RGB -> 2L; TiffColor.GRAY -> 1L; TiffColor.BW -> 0L })
            tags += Triple(273, 4, dataOffset)
            tags += Triple(277, 3, spp.toLong())
            tags += Triple(278, 4, r.h.toLong())
            tags += Triple(279, 4, data.size.toLong())
            tags += Triple(282, 5, xRes); tags += Triple(283, 5, yRes)
            tags += Triple(296, 3, 2L)                                     // inches
            if (color == TiffColor.RGB) tags += Triple(284, 3, 1L)         // planar: chunky
            val sorted = tags.sortedBy { it.first }
            check(sorted.size == nTags)
            u16(nTags)
            sorted.forEach { (tag, type, v) ->
                u16(tag); u16(type)
                val count = if (tag == 258 && color == TiffColor.RGB) 3L else 1L
                u32(count)
                if (type == 3 && count == 1L) { u16(v.toInt()); u16(0) } else u32(v)
            }
            u32(next)
            if (color == TiffColor.RGB) { u16(8); u16(8); u16(8) }
            u32(r.dpi.toLong()); u32(1); u32(r.dpi.toLong()); u32(1)
            buf.write(data)
            if (next != 0L && (buf.size() and 1) == 1) buf.write(0)
        }
        out.write(buf.toByteArray())
    }
}

object BmpWriter {
    /** 24-bit bottom-up BMP with the resolution in pixels per metre. */
    fun write(r: Raster): ByteArray {
        val row = (r.w * 3 + 3) / 4 * 4
        val size = 54 + row * r.h
        val b = ByteArray(size)
        fun put(i: Int, v: Int, n: Int) { for (k in 0 until n) b[i + k] = (v shr (8 * k)).toByte() }
        b[0] = 'B'.code.toByte(); b[1] = 'M'.code.toByte(); put(2, size, 4); put(10, 54, 4)
        put(14, 40, 4); put(18, r.w, 4); put(22, r.h, 4); put(26, 1, 2); put(28, 24, 2); put(34, row * r.h, 4)
        val ppm = (r.dpi / 0.0254).toInt(); put(38, ppm, 4); put(42, ppm, 4)
        for (y in 0 until r.h) {
            var o = 54 + (r.h - 1 - y) * row
            for (x in 0 until r.w) { val c = r.argb[y * r.w + x]; b[o++] = c.toByte(); b[o++] = (c shr 8).toByte(); b[o++] = (c shr 16).toByte() }
        }
        return b
    }
}

/** PostScript level 2 / EPS made of page pictures (JPEG through /DCTDecode, ASCII85). */
object PsWriter {
    class Page(val jpeg: ByteArray, val pxW: Int, val pxH: Int, val ptW: Float, val ptH: Float, val gray: Boolean = false)

    /** ASCII85 with the `~>` end marker, lines of 76. */
    fun ascii85(data: ByteArray): String {
        val sb = StringBuilder(data.size * 5 / 4 + 16); var col = 0
        fun emit(s: String) { for (c in s) { sb.append(c); if (++col == 76) { sb.append('\n'); col = 0 } } }
        var i = 0
        while (i < data.size) {
            val n = minOf(4, data.size - i)
            var v = 0L
            for (k in 0 until 4) v = (v shl 8) or (if (k < n) (data[i + k].toLong() and 0xFF) else 0L)
            if (v == 0L && n == 4) emit("z") else {
                val ch = CharArray(5); var t = v
                for (k in 4 downTo 0) { ch[k] = ('!'.code + (t % 85).toInt()).toChar(); t /= 85 }
                emit(String(ch, 0, n + 1))
            }
            i += 4
        }
        sb.append("~>\n"); return sb.toString()
    }

    fun write(pages: List<Page>, eps: Boolean, title: String): String = buildString {
        val first = pages.firstOrNull()
        append(if (eps) "%!PS-Adobe-3.0 EPSF-3.0\n" else "%!PS-Adobe-3.0\n")
        append("%%Creator: SmartDocs\n%%Title: ").append(title.replace(Regex("[\\r\\n()]"), " ")).append('\n')
        append("%%Pages: ").append(if (eps) 1 else pages.size).append('\n')
        if (first != null) append("%%BoundingBox: 0 0 ").append(Math.ceil(first.ptW.toDouble()).toInt()).append(' ').append(Math.ceil(first.ptH.toDouble()).toInt()).append('\n')
        append("%%LanguageLevel: 2\n%%EndComments\n")
        (if (eps) pages.take(1) else pages).forEachIndexed { i, p ->
            append("%%Page: ").append(i + 1).append(' ').append(i + 1).append('\n')
            if (!eps) append("<< /PageSize [").append(p.ptW).append(' ').append(p.ptH).append("] >> setpagedevice\n")
            append("gsave\n").append(p.ptW).append(' ').append(p.ptH).append(" scale\n")
            append(if (p.gray) "/DeviceGray" else "/DeviceRGB").append(" setcolorspace\n")
            append("<< /ImageType 1 /Width ").append(p.pxW).append(" /Height ").append(p.pxH).append(" /BitsPerComponent 8")
            append(" /Decode [").append(if (p.gray) "0 1" else "0 1 0 1 0 1").append("] /ImageMatrix [").append(p.pxW).append(" 0 0 -").append(p.pxH).append(" 0 ").append(p.pxH).append(']')
            append(" /DataSource currentfile /ASCII85Decode filter /DCTDecode filter >> image\n")
            append(ascii85(p.jpeg))
            append("grestore\n")
            if (!eps) append("showpage\n")
        }
        append("%%EOF\n")
    }
}

/** XPS (OpenXPS-compatible) package whose pages are pictures. */
object XpsWriter {
    class Page(val jpeg: ByteArray, val ptW: Float, val ptH: Float)

    fun write(pages: List<Page>, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="utf-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="jpg" ContentType="image/jpeg"/>""" +
                """<Default Extension="fdseq" ContentType="application/vnd.ms-package.xps-fixeddocumentsequence+xml"/><Default Extension="fdoc" ContentType="application/vnd.ms-package.xps-fixeddocument+xml"/>""" +
                """<Default Extension="fpage" ContentType="application/vnd.ms-package.xps-fixedpage+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="utf-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="R0" Type="http://schemas.microsoft.com/xps/2005/06/fixedrepresentation" Target="/FixedDocSeq.fdseq"/></Relationships>""")
            put("FixedDocSeq.fdseq", """<FixedDocumentSequence xmlns="http://schemas.microsoft.com/xps/2005/06"><DocumentReference Source="/Documents/1/FixedDoc.fdoc"/></FixedDocumentSequence>""")
            put("Documents/1/FixedDoc.fdoc", """<FixedDocument xmlns="http://schemas.microsoft.com/xps/2005/06">""" +
                pages.indices.joinToString("") { """<PageContent Source="/Documents/1/Pages/${it + 1}.fpage"/>""" } + "</FixedDocument>")
            pages.forEachIndexed { i, p ->
                val n = i + 1
                // XPS units are 1/96 inch
                val w = p.ptW * 96f / 72f; val h = p.ptH * 96f / 72f
                put("Documents/1/Pages/$n.fpage", """<FixedPage xmlns="http://schemas.microsoft.com/xps/2005/06" Width="$w" Height="$h" xml:lang="und">""" +
                    """<Path Data="M 0,0 L $w,0 $w,$h 0,$h Z"><Path.Fill><ImageBrush ImageSource="/Resources/Images/$n.jpg" Viewbox="0,0,$w,$h" ViewboxUnits="Absolute" Viewport="0,0,$w,$h" ViewportUnits="Absolute"/></Path.Fill></Path></FixedPage>""")
                put("Documents/1/Pages/_rels/$n.fpage.rels", """<?xml version="1.0" encoding="utf-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="I$n" Type="http://schemas.microsoft.com/xps/2005/06/required-resource" Target="/Resources/Images/$n.jpg"/></Relationships>""")
                z.putNextEntry(ZipEntry("Resources/Images/$n.jpg")); z.write(p.jpeg); z.closeEntry()
            }
        }
    }
}

/** Plain XML 1.0 export: information, bookmarks and the text lines of every page with their position. */
object PdfXml {
    class Line(val page: Int, val text: String, val x: Float?, val y: Float?, val size: Float, val font: String)
    class Mark(val title: String, val page: Int, val level: Int)

    fun write(title: String, info: Map<String, String>, pageSizes: List<Pair<Float, Float>>, marks: List<Mark>, lines: List<Line>): String = buildString {
        fun x(s: String) = Text6.xml(s)
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<document title=\"").append(x(title)).append("\" pages=\"").append(pageSizes.size).append("\">\n")
        if (info.isNotEmpty()) { append("  <info>\n"); info.forEach { (k, v) -> append("    <").append(k).append('>').append(x(v)).append("</").append(k).append(">\n") }; append("  </info>\n") }
        if (marks.isNotEmpty()) {
            append("  <bookmarks>\n")
            marks.forEach { m -> append("    <bookmark level=\"").append(m.level).append("\" page=\"").append(m.page + 1).append("\">").append(x(m.title)).append("</bookmark>\n") }
            append("  </bookmarks>\n")
        }
        val byPage = lines.groupBy { it.page }
        pageSizes.forEachIndexed { i, (w, h) ->
            append("  <page number=\"").append(i + 1).append("\" width=\"").append(w).append("\" height=\"").append(h).append("\">\n")
            byPage[i].orEmpty().forEach { l ->
                append("    <line")
                l.x?.let { append(" x=\"").append("%.1f".format(java.util.Locale.US, it)).append('"') }
                l.y?.let { append(" y=\"").append("%.1f".format(java.util.Locale.US, it)).append('"') }
                append(" size=\"").append("%.1f".format(java.util.Locale.US, l.size)).append('"')
                if (l.font.isNotBlank()) append(" font=\"").append(x(l.font)).append('"')
                append('>').append(x(l.text)).append("</line>\n")
            }
            append("  </page>\n")
        }
        append("</document>\n")
    }
}

/** Excel "XML Spreadsheet 2003" (SpreadsheetML): one worksheet per entry. */
object Xml2003 {
    fun write(sheets: List<Pair<String, List<List<String>>>>, rtl: Boolean): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<?mso-application progid=\"Excel.Sheet\"?>\n")
        append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">\n")
        val used = HashSet<String>()
        sheets.ifEmpty { listOf("Sheet1" to emptyList()) }.forEach { (name0, rows) ->
            // sheet names: max 31 chars, no []:*?/\ and unique
            val clean = name0.replace(Regex("[\\[\\]:*?/\\\\]"), " ").ifBlank { "Sheet" }
            var name = clean.take(31)
            var k = 2; while (!used.add(name)) name = clean.take(26) + " (${k++})"
            append("  <Worksheet ss:Name=\"").append(Text6.xml(name)).append("\">\n")
            if (rtl) append("    <WorksheetOptions xmlns=\"urn:schemas-microsoft-com:office:excel\"><DisplayRightToLeft/></WorksheetOptions>\n")
            append("    <Table>\n")
            rows.forEach { r ->
                append("      <Row>")
                r.forEach { c ->
                    val num = c.trim().replace(",", "").toDoubleOrNull()
                    if (num != null && c.isNotBlank()) append("<Cell><Data ss:Type=\"Number\">").append(c.trim().replace(",", "")).append("</Data></Cell>")
                    else append("<Cell><Data ss:Type=\"String\">").append(Text6.xml(c)).append("</Data></Cell>")
                }
                append("</Row>\n")
            }
            append("    </Table>\n  </Worksheet>\n")
        }
        append("</Workbook>\n")
    }
}

/** HTML set: one page per chapter plus index.html with the navigation pane (Foxit "multiple HTML pages"). */
object HtmlSet {
    /** File name of chapter [i]. Pure. */
    fun pageName(i: Int) = "page_%03d.html".format(i + 1)

    fun write(doc: FlowDoc, out: OutputStream) {
        val chapters = doc.chapters()
        val rtl = Text6.rtlDominant(doc.blocks.joinToString(" ") { FlowDoc.textOf(it).orEmpty() }.take(4000))
        val dir = if (rtl) "rtl" else "ltr"
        fun page(title: String, body: String) = "<!DOCTYPE html>\n<html dir=\"$dir\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>${Text6.xml(title)}</title><style>body{font-family:sans-serif;max-width:860px;margin:auto;padding:16px;line-height:1.6}nav a{display:block;padding:4px 0}</style></head><body>$body</body></html>\n"
        ZipOutputStream(out).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            val nav = chapters.mapIndexed { i, (t, _) -> "<a href=\"${pageName(i)}\">${Text6.xml(t.ifBlank { "${i + 1}" })}</a>" }.joinToString("")
            put("index.html", page(doc.title, "<h1>${Text6.xml(doc.title)}</h1><nav>$nav</nav>"))
            chapters.forEachIndexed { i, (t, blocks) ->
                val prev = if (i > 0) "<a href=\"${pageName(i - 1)}\">&larr;</a> " else ""
                val next = if (i < chapters.lastIndex) " <a href=\"${pageName(i + 1)}\">&rarr;</a>" else ""
                put(pageName(i), page(t, "<p>$prev<a href=\"index.html\">☰</a>$next</p>" + FlowHtml.body(blocks) + "<p>$prev<a href=\"index.html\">☰</a>$next</p>"))
            }
        }
    }
}
