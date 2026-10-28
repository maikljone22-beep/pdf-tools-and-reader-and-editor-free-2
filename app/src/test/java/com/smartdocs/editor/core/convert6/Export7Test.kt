package com.smartdocs.editor.core.convert6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Stage C (Nitro 6 / Foxit 6): TIFF, BMP, PostScript/EPS, XPS, XML, Excel 2003 XML and HTML-set writers. */
class Export7Test {
    private val white = 0xFFFFFFFF.toInt(); private val black = 0xFF000000.toInt()

    private fun unpackBits(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); var i = 0
        while (i < b.size) {
            val n = b[i++].toInt()
            if (n >= 0) { out.write(b, i, n + 1); i += n + 1 } else if (n != -128) { repeat(1 - n) { out.write(b[i].toInt()) }; i++ }
        }
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    @Test fun packBitsRoundTrips() {
        val data = byteArrayOf(1, 1, 1, 1, 2, 3, 4, 4, 5) + ByteArray(300) { 7 } + ByteArray(50) { it.toByte() }
        val row = data.size
        assertTrue(TiffWriter.packBits(data, row).size < data.size)
        assertEquals(data.toList(), unpackBits(TiffWriter.packBits(data, row)).toList())
        // rows are packed separately
        val two = byteArrayOf(9, 9, 9, 9, 9, 9)
        assertEquals(two.toList(), unpackBits(TiffWriter.packBits(two, 3)).toList())
    }

    @Test fun multiPageTiffHasLinkedIfds() {
        val a = Raster(3, 2, intArrayOf(white, black, white, black, black, white), 100)
        val out = ByteArrayOutputStream(); TiffWriter.write(listOf(a, a), TiffColor.BW, out)
        val b = out.toByteArray()
        assertEquals('I'.code.toByte(), b[0]); assertEquals(42, u16(b, 2))
        var ifd = u32(b, 4).toInt(); var pages = 0
        while (ifd != 0) {
            pages++
            val n = u16(b, ifd)
            val tags = (0 until n).associate { k -> val o = ifd + 2 + k * 12; u16(b, o) to u32(b, o + 8) }
            assertEquals(3L, tags[256]); assertEquals(2L, tags[257]); assertEquals(0L, tags[262] ?: -1L)
            val data = b.copyOfRange(tags.getValue(273).toInt(), (tags.getValue(273) + tags.getValue(279)).toInt())
            // 1 bit, white is zero: row 1 = 010, row 2 = 110
            assertEquals(listOf(0b01000000.toByte(), 0b11000000.toByte()), unpackBits(data).toList())
            ifd = u32(b, ifd + 2 + n * 12).toInt()
        }
        assertEquals(2, pages)
        val hist = IntArray(256).also { it[20] = 50; it[230] = 50 }
        assertTrue(TiffWriter.otsu(hist) in 20 until 230)
    }

    @Test fun bmpHeaderAndPixels() {
        val b = BmpWriter.write(Raster(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), 72))
        assertEquals('B'.code.toByte(), b[0]); assertEquals(54 + 8, b.size)        // row padded to 8 bytes
        assertEquals(listOf(0.toByte(), 0.toByte(), 0xFF.toByte()), b.copyOfRange(54, 57).toList())   // BGR of red
    }

    @Test fun postScriptAndEps() {
        assertEquals("9jqo^~>\n", PsWriter.ascii85("Man ".toByteArray()))
        assertEquals("z~>\n", PsWriter.ascii85(ByteArray(4)))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val ps = PsWriter.write(listOf(PsWriter.Page(jpeg, 10, 20, 595f, 842f), PsWriter.Page(jpeg, 10, 20, 595f, 842f)), false, "Doc")
        assertTrue(ps.startsWith("%!PS-Adobe-3.0\n")); assertTrue(ps.contains("%%Pages: 2")); assertEquals(2, Regex("showpage").findAll(ps).count())
        assertTrue(ps.contains("/DCTDecode filter"))
        val eps = PsWriter.write(listOf(PsWriter.Page(jpeg, 10, 20, 100f, 50f)), true, "x")
        assertTrue(eps.startsWith("%!PS-Adobe-3.0 EPSF-3.0")); assertTrue(eps.contains("%%BoundingBox: 0 0 100 50")); assertTrue(!eps.contains("showpage"))
    }

    @Test fun xpsPackage() {
        val out = ByteArrayOutputStream()
        XpsWriter.write(listOf(XpsWriter.Page(byteArrayOf(1), 72f, 72f), XpsWriter.Page(byteArrayOf(2), 72f, 144f)), out)
        val names = ArrayList<String>(); var page2 = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            var e = z.nextEntry
            while (e != null) { names += e.name; if (e.name == "Documents/1/Pages/2.fpage") page2 = z.readBytes().toString(Charsets.UTF_8); e = z.nextEntry }
        }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "FixedDocSeq.fdseq", "Documents/1/FixedDoc.fdoc", "Resources/Images/2.jpg")))
        assertTrue(page2.contains("Width=\"96.0\" Height=\"192.0\""))
    }

    @Test fun xmlExports() {
        val xml = PdfXml.write("R&D", mapOf("author" to "Ali"), listOf(Triple(0, 595f, 842f)), listOf(PdfXml.Mark("Intro", 0, 0)),
            listOf(PdfXml.Line(0, "a < b", 72f, 700f, 12f, "Arial")))
        assertTrue(xml.contains("title=\"R&amp;D\"")); assertTrue(xml.contains("<bookmark level=\"0\" page=\"1\">Intro</bookmark>"))
        assertTrue(xml.contains("x=\"72.0\" y=\"700.0\" size=\"12.0\" font=\"Arial\">a &lt; b</line>"))
        val x03 = Xml2003.write(listOf("p1/2" to listOf(listOf("Name", "1,250")), "p1/2" to emptyList()), rtl = true)
        assertTrue(x03.contains("ss:Name=\"p1 2\"")); assertTrue(x03.contains("ss:Name=\"p1 2 (2)\""))
        assertTrue(x03.contains("<Data ss:Type=\"Number\">1250</Data>")); assertTrue(x03.contains("DisplayRightToLeft"))
    }

    @Test fun htmlSetPages() {
        assertEquals("page_003.html", HtmlSet.pageName(2))
        val doc = FlowDoc("Book", listOf(FBlock.Heading(1, listOf(FRun("One")), false), FBlock.Para(listOf(FRun("x")), false),
            FBlock.Heading(1, listOf(FRun("Two")), false), FBlock.Para(listOf(FRun("y")), false)))
        val out = ByteArrayOutputStream(); HtmlSet.write(doc, out)
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> var e = z.nextEntry; while (e != null) { files[e.name] = z.readBytes().toString(Charsets.UTF_8); e = z.nextEntry } }
        assertEquals(setOf("index.html", "page_001.html", "page_002.html"), files.keys)
        assertTrue(files.getValue("index.html").contains("href=\"page_002.html\">Two</a>"))
    }
}
