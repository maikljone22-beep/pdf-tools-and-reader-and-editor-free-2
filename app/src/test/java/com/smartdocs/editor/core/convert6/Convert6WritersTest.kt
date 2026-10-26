package com.smartdocs.editor.core.convert6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private fun unzip(b: ByteArray): LinkedHashMap<String, Pair<ByteArray, Int>> {
    val out = LinkedHashMap<String, Pair<ByteArray, Int>>()
    ZipInputStream(ByteArrayInputStream(b)).use { z ->
        var e: ZipEntry? = z.nextEntry
        while (e != null) { out[e.name] = z.readBytes() to e.method; e = z.nextEntry }
    }
    return out
}

private fun text(m: Map<String, Pair<ByteArray, Int>>, n: String) = String(m.getValue(n).first, Charsets.UTF_8)

private val FF = 12.toChar().toString()
private const val AR = "مرحبا بالعالم"

class FlowWritersTest {

    private val doc = FlowDoc("كتاب", listOf(
        FBlock.Heading(1, listOf(FRun("الفصل الأول")), true),
        FBlock.Para(listOf(FRun(AR), FRun(" bold", bold = true)), true),
        FBlock.Item(listOf(FRun("بند")), true, number = 1),
        FBlock.Item(listOf(FRun("بند ثان")), true, number = 2),
        FBlock.Table(listOf(listOf("الاسم", "العمر"), listOf("علي", "30")), true, true),
        FBlock.Heading(1, listOf(FRun("Chapter 2")), false),
        FBlock.Code("val x = 1 | 2"),
        FBlock.Para(listOf(FRun("a*b", italic = true), FRun(" link", link = "https://x.y")), false),
    ))

    @Test fun markdown() {
        val md = FlowMarkdown.write(doc)
        assertTrue(md.startsWith("# الفصل الأول\n"))
        assertTrue(md.contains("**bold**"))
        assertTrue(md.contains("1. بند\n2. بند ثان"))
        assertTrue(md.contains("| الاسم | العمر |\n| --- | --- |\n| علي | 30 |"))
        assertTrue(md.contains("```\nval x = 1 | 2\n```"))
        assertTrue(md.contains("*a\\*b*"))
        assertTrue(md.contains("[ link](https://x.y)") || md.contains(" [link](https://x.y)"))
    }

    @Test fun markdownRoundTrip() {
        val back = MdFlow.parse(FlowMarkdown.write(doc))
        assertEquals("الفصل الأول", back.title)
        assertTrue(back.blocks.first() is FBlock.Heading)
        val t = back.blocks.filterIsInstance<FBlock.Table>().single()
        assertEquals(listOf("علي", "30"), t.rows[1])
        val items = back.blocks.filterIsInstance<FBlock.Item>()
        assertEquals(listOf(1, 2), items.map { it.number })
        assertTrue(back.blocks.filterIsInstance<FBlock.Para>().first().rtl)
    }

    @Test fun mdInline() {
        val r = MdFlow.inline("a **b** *c* `d` [e](http://f) snake_case_word")
        assertTrue(r.any { it.bold && it.text == "b" })
        assertTrue(r.any { it.italic && it.text == "c" })
        assertTrue(r.any { it.code && it.text == "d" })
        assertTrue(r.any { it.link == "http://f" && it.text == "e" })
        assertEquals("a b c d e snake_case_word", Text6.plain(r))
    }

    @Test fun docxRtlParagraphs() {
        val bos = ByteArrayOutputStream(); FlowDocx.write(doc, bos)
        val z = unzip(bos.toByteArray())
        val xml = text(z, "word/document.xml")
        assertTrue(xml.contains("<w:pStyle w:val=\"Heading1\"/><w:bidi/>"))
        assertTrue(xml.contains("<w:rtl/>"))
        assertTrue(xml.contains("<w:bidiVisual/>"))
        assertTrue(xml.contains("<w:numId w:val=\""))
        assertTrue(xml.contains("<w:hyperlink r:id=\"rIdL1\">"))
        assertTrue(text(z, "word/_rels/document.xml.rels").contains("https://x.y"))
        assertTrue(z.containsKey("word/numbering.xml") && z.containsKey("word/styles.xml"))
    }

    @Test fun epubStructure() {
        val bos = ByteArrayOutputStream(); EpubWriter.write(doc, bos, id = "urn:uuid:test")
        val z = unzip(bos.toByteArray())
        assertEquals("mimetype", z.keys.first())
        assertEquals(ZipEntry.STORED, z.getValue("mimetype").second)
        val opf = text(z, "OEBPS/content.opf")
        assertTrue(opf.contains("page-progression-direction=\"rtl\""))
        assertTrue(opf.contains("<dc:language>ar</dc:language>"))
        assertEquals(2, doc.chapters().size)
        assertTrue(z.containsKey("OEBPS/ch2.xhtml"))
        assertTrue(text(z, "OEBPS/nav.xhtml").contains("Chapter 2"))
        // XHTML must be well-formed
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(z.getValue("OEBPS/ch1.xhtml").first))
    }

    @Test fun chaptersFromMarkers() {
        val d = FlowDoc("t", listOf(FBlock.Para(listOf(FRun("intro")), false), FBlock.Chapter("A"), FBlock.Para(listOf(FRun("x")), false), FBlock.Chapter("B"), FBlock.Para(listOf(FRun("y")), false)))
        assertEquals(listOf("t", "A", "B"), d.chapters().map { it.first })
    }

    @Test fun odtRoundTrip() {
        val bos = ByteArrayOutputStream(); OdfWriter.odt(doc, bos)
        val z = unzip(bos.toByteArray())
        assertEquals("mimetype", z.keys.first())
        assertEquals("application/vnd.oasis.opendocument.text", text(z, "mimetype"))
        val back = OdfReader.readOdt({ n -> z[n]?.first }, "x")
        assertTrue(back.blocks.any { it is FBlock.Heading && Text6.plain(it.runs) == "الفصل الأول" })
        assertTrue(back.blocks.any { it is FBlock.Para && Text6.plain(it.runs).startsWith(AR) && it.rtl })
        val t = back.blocks.filterIsInstance<FBlock.Table>().single()
        assertEquals("علي", t.rows[1][0])
        val items = back.blocks.filterIsInstance<FBlock.Item>()
        assertEquals(2, items.size)
        assertTrue(items.all { it.number > 0 })
    }

    @Test fun odsRoundTrip() {
        val bos = ByteArrayOutputStream()
        OdfWriter.ods(listOf("ورقة" to listOf(listOf("a", "1,234"), listOf("", "x"))), bos, true)
        val z = unzip(bos.toByteArray())
        assertTrue(text(z, "content.xml").contains("office:value=\"1234\""))
        val back = OdfReader.readOds { n -> z[n]?.first }
        assertEquals("ورقة", back.single().first)
        assertEquals(listOf(listOf("a", "1,234"), listOf("", "x")), back.single().second)
    }

    @Test fun odpSlides() {
        val bos = ByteArrayOutputStream()
        OdfWriter.odp(listOf(OdfWriter.Slide("عنوان", listOf("سطر 1", "line 2"))), bos, true)
        val back = OdfReader.readOdp { n -> unzip(bos.toByteArray())[n]?.first }
        assertEquals("عنوان" to listOf("سطر 1", "line 2"), back.single())
    }

    @Test fun csv() {
        assertEquals("a,\"b,c\",\"d\"\"e\"\r\n", Csv6.write(listOf(listOf("a", "b,c", "d\"e"))))
        assertEquals("1234.5", Csv6.number("1,234.5"))
        assertEquals("12", Csv6.number("١٢"))
        assertEquals(null, Csv6.number("12abc"))
        assertEquals(null, Csv6.number("1,23"))
    }

    @Test fun textAndPageBreaks() {
        val d = FlowDoc.fromText("سطر\nline" + FF + "page2", "t")
        assertTrue(d.blocks.contains(FBlock.PageBreak))
        assertTrue((d.blocks[0] as FBlock.Para).rtl)
        assertTrue(FlowText.write(d).contains("page2"))
    }
}

class ReadersTest {

    private val bs = "\\"

    @Test fun rtfUnicodeCodepageAndTables() {
        val rtf = "{" + bs + "rtf1" + bs + "ansi" + bs + "ansicpg1256{" + bs + "fonttbl{" + bs + "f0" + bs + "fnil" + bs + "fcharset178 Arial;}}" +
            "{" + bs + "*" + bs + "generator x;}" + bs + "f0" + bs + "rtlpar " + bs + "'e3" + bs + "'d1" + bs + "'cd" + bs + "'c8" + bs + "'c7" + bs + "par " +
            bs + "b Bold" + bs + "b0  " + bs + "u1593?" + bs + "u1604?" + bs + "u1610?" + bs + "par " +
            bs + "trowd" + bs + "intbl A" + bs + "cell B" + bs + "cell" + bs + "row " + bs + "pard after" + bs + "par}"
        val d = RtfReader.parse(rtf.toByteArray(Charsets.ISO_8859_1))
        val paras = d.blocks.filterIsInstance<FBlock.Para>().map { Text6.plain(it.runs) }
        assertEquals("مرحبا", paras[0])
        assertTrue(d.blocks.filterIsInstance<FBlock.Para>()[0].rtl)
        assertTrue(paras[1].contains("Bold") && paras[1].contains("علي"))
        assertTrue(d.blocks.filterIsInstance<FBlock.Para>()[1].runs.any { it.bold && it.text.contains("Bold") })
        assertEquals(listOf(listOf("A", "B")), d.blocks.filterIsInstance<FBlock.Table>().single().rows)
        assertTrue(paras.contains("after"))
    }

    @Test fun htmlFlow() {
        val d = HtmlFlow.parse("<html><head><title>T &amp; Q</title><style>p{}</style></head><body><h2>عنوان</h2><p>نص <b>عريض</b> &laquo;x&raquo;</p>" +
            "<ul><li>one</li><li>two</li></ul><ol><li>a</li><li>b</li></ol><table><tr><th>h1</th><th>h2</th></tr><tr><td>1</td><td>2</td></tr></table><pre>code  x</pre></body></html>")
        assertEquals("T & Q", d.title)
        assertTrue(d.blocks[0] is FBlock.Heading)
        val p = d.blocks.filterIsInstance<FBlock.Para>().first()
        assertTrue(p.rtl && p.runs.any { it.bold && it.text == "عريض" })
        assertEquals(listOf(0, 0, 1, 2), d.blocks.filterIsInstance<FBlock.Item>().map { it.number })
        val t = d.blocks.filterIsInstance<FBlock.Table>().single()
        assertTrue(t.header); assertEquals(listOf("1", "2"), t.rows[1])
        assertEquals("code  x", (d.blocks.last() as FBlock.Code).text)
        assertEquals("a\nb", HtmlFlow.plain("<p>a</p><p>b</p>"))
    }

    @Test fun wordControlChars() {
        val raw = "Title" + 13.toChar() + 19.toChar() + " PAGE " + 20.toChar() + "3" + 21.toChar() + 13.toChar() + "A" + 7.toChar() + "B" + 7.toChar() + 13.toChar()
        assertEquals("Title\n3\nA | B", DocReader.clean(raw))
    }

    @Test fun odfLengths() {
        assertEquals(72f, OdfReader.ptOf("1in"), 0.01f)
        assertEquals(72f, OdfReader.ptOf("2.54cm"), 0.01f)
        assertEquals(0f, OdfReader.ptOf("auto"), 0.01f)
    }
}

class TiffDecoderTest {

    /** Little-endian baseline TIFF with [pages] of 2×1 RGB pixels, [packbits] compressed or not. */
    private fun tiff(pages: List<ByteArray>, packbits: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }
        out.write('I'.code); out.write('I'.code); u16(42); u32(8)
        var offset = 8
        pages.forEachIndexed { i, px ->
            val data = if (packbits) byteArrayOf((px.size - 1).toByte()) + px else px
            val nTags = 9
            val ifdSize = 2 + nTags * 12 + 4
            val bpsOff = offset + ifdSize
            val dataOff = bpsOff + 6
            val next = if (i == pages.size - 1) 0 else dataOff + data.size
            fun tag(t: Int, type: Int, count: Int, value: Int) { u16(t); u16(type); u32(count); if (type == 3 && count == 1) { u16(value); u16(0) } else u32(value) }
            u16(nTags)
            tag(256, 3, 1, 2); tag(257, 3, 1, 1); tag(258, 3, 3, bpsOff); tag(259, 3, 1, if (packbits) 32773 else 1)
            tag(262, 3, 1, 2); tag(273, 4, 1, dataOff); tag(277, 3, 1, 3); tag(278, 3, 1, 1); tag(279, 4, 1, data.size)
            u32(next)
            u16(8); u16(8); u16(8)
            out.write(data)
            offset = next
        }
        return out.toByteArray()
    }

    @Test fun multiPageAndPackBits() {
        val red = byteArrayOf(255.toByte(), 0, 0, 0, 0, 255.toByte())
        val green = byteArrayOf(0, 255.toByte(), 0, 10, 20, 30)
        val t = tiff(listOf(red, green), packbits = false)
        assertEquals(2, TiffDecoder.pageCount(t))
        val pages = TiffDecoder.decode(t)
        assertEquals(0xFFFF0000.toInt(), pages[0].argb!![0])
        assertEquals(0xFF0000FF.toInt(), pages[0].argb!![1])
        assertEquals(0xFF0A141E.toInt(), pages[1].argb!![1])
        val p = TiffDecoder.decode(tiff(listOf(green), packbits = true)).single()
        assertEquals(0xFF00FF00.toInt(), p.argb!![0])
    }

    @Test fun packBitsRuns() {
        // run of 3 x 0xAA, then literal 1,2
        val src = byteArrayOf((-2).toByte(), 0xAA.toByte(), 1, 1, 2)
        assertEquals(listOf(0xAA, 0xAA, 0xAA, 1, 2), TiffDecoder.packBits(src, 5).map { it.toInt() and 0xFF })
    }
}
