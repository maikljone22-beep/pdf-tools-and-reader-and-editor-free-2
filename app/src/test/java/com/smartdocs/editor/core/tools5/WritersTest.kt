package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WritersTest {

    @Test fun rtfEscapes() {
        assertEquals("a\\\\b\\{c\\}", RtfWriter.escape("a\\b{c}"))
        assertEquals("\\u1576?", RtfWriter.escape("\u0628"))
        assertEquals("\\u-1?", RtfWriter.escape("\uFFFF"))
        assertEquals("x\\line y\\tab z", RtfWriter.escape("x\ny\tz"))
    }

    @Test fun rtfDocument() {
        val rtf = RtfWriter.write(
            listOf(
                RtfPara(listOf(RtfRun("عنوان")), rtl = true, heading = 1),
                RtfPara(listOf(RtfRun("Hello "), RtfRun("bold", bold = true), RtfRun(" it", italic = true, size = 12f))),
                RtfPageBreak,
                RtfTable(listOf(listOf("a", "b"), listOf("1")), header = true),
            ),
            title = "T",
        )
        assertTrue(rtf.startsWith("{\\rtf1"))
        assertTrue(rtf.endsWith("}"))
        assertTrue(rtf.all { it.code < 128 })
        assertTrue(rtf.contains("\\rtlpar\\qr\\outlinelevel0"))
        assertTrue(rtf.contains("\\b\\ab bold}"))
        assertTrue(rtf.contains("\\fs24\\afs24\\i\\ai  it}"))
        assertTrue(rtf.contains("\\page"))
        assertEquals(2, Regex("""\\row""").findAll(rtf).count())
        assertEquals(4, Regex("""\\cell\n""").findAll(rtf).count())
        // braces balance
        var depth = 0; var i = 0
        while (i < rtf.length) { val c = rtf[i]; if (c == '\\') i++ else if (c == '{') depth++ else if (c == '}') depth--; i++ }
        assertEquals(0, depth)
    }

    @Test fun iccProfileHeader() {
        val b = IccSrgb.bytes
        fun u32(o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        assertEquals(b.size, u32(0))
        assertEquals("acsp", String(b, 36, 4, Charsets.US_ASCII))
        assertEquals("mntr", String(b, 12, 4, Charsets.US_ASCII))
        assertEquals(9, u32(128))
        for (t in 0 until 9) {
            val off = u32(132 + t * 12 + 4); val size = u32(132 + t * 12 + 8)
            assertTrue(off % 4 == 0 && off + size <= b.size)
        }
    }

    @Test fun xmpPacket() {
        val x = XmpWriter.packet("A & B", "Ali", null, null, "SmartDocs", "SmartDocs", "2024-01-01T00:00:00+00:00", "2024-01-01T00:00:00+00:00")
        assertTrue(x.contains("<pdfaid:part>2</pdfaid:part>"))
        assertTrue(x.contains("<pdfaid:conformance>B</pdfaid:conformance>"))
        assertTrue(x.contains("A &amp; B"))
        assertTrue(x.startsWith("<?xpacket begin=\"\uFEFF\""))
        assertTrue(x.endsWith("<?xpacket end=\"w\"?>"))
    }

    @Test fun accessRulesCountAndEvaluate() {
        assertEquals(30, AccessRules.rules.size)
        assertEquals(30, AccessRules.rules.map { it.id }.toSet().size)
        val res = AccessRules.evaluate(AccessFacts(pageCount = 3, tagged = true, marked = true, lang = "ar", title = "x", displayDocTitle = true, figures = 2, figuresWithoutAlt = 1))
        assertEquals(AccStatus.FAIL, res.first { it.rule.id == "figure_alt" }.status)
        assertEquals(1, res.first { it.rule.id == "figure_alt" }.count)
        assertEquals(AccStatus.PASS, res.first { it.rule.id == "title" }.status)
        assertEquals(AccStatus.NA, res.first { it.rule.id == "bookmarks" }.status)
        val html = AccessRules.htmlReport("f.pdf", res, true, "R", { it }, { it.name }, { it.name })
        assertTrue(html.contains("dir=\"rtl\""))
        assertTrue(html.contains("figure_alt"))
    }

    @Test fun emlMultipart() {
        val eml = (
            "From: =?UTF-8?B?2LnZhNmK?= <ali@x.com>\r\nTo: b@x.com\r\nSubject: =?UTF-8?Q?Hello_W=C3=B6rld?=\r\n" +
                "Content-Type: multipart/mixed; boundary=\"BB\"\r\n\r\n" +
                "--BB\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n<html><body><p>Hi =3D there</p><img src=3D\"cid:img1\"></body></html>\r\n" +
                "--BB\r\nContent-Type: image/png\r\nContent-ID: <img1>\r\nContent-Transfer-Encoding: base64\r\n\r\niVBORw0KGgo=\r\n" +
                "--BB\r\nContent-Type: application/pdf; name=\"a.pdf\"\r\nContent-Disposition: attachment; filename=\"a.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERi0=\r\n" +
                "--BB--\r\n"
            ).toByteArray(Charsets.UTF_8)
        val d = EmlReader.read(eml, "mail")
        assertEquals("Hello Wörld", d.title)
        assertTrue(d.html.contains("Hi = there"))
        assertTrue(d.html.contains("data:image/png;base64,iVBORw0KGgo="))
        assertTrue(d.html.contains("\u0639\u0644\u064A"))
        assertEquals(listOf("a.pdf"), d.attachments.map { it.name })
        assertEquals("%PDF-", String(d.attachments[0].bytes, Charsets.US_ASCII))
    }

    @Test fun markdown() {
        val h = MarkdownHtml.toHtmlBody("# Title\n\nSome **bold** and *it* [l](http://a)\n\n- a\n- b\n\n```\ncode <x>\n```")
        assertTrue(h.contains("<h1 dir=\"auto\">Title</h1>"))
        assertTrue(h.contains("<b>bold</b>") && h.contains("<i>it</i>") && h.contains("<a href=\"http://a\">l</a>"))
        assertTrue(h.contains("<ul>") && h.contains("<li dir=\"auto\">b</li>"))
        assertTrue(h.contains("code &lt;x&gt;"))
    }

    @Test fun pixelBinding() {
        val w = 200; val h = 100
        val px = IntArray(w * h) { -1 }
        for (y in 0 until h) for (x in 108..112) px[y * w + x] = 0xFF202020.toInt()
        assertEquals(110, PixelOps.bindingColumn(px, w, h).let { if (it in 108..112) 110 else it })
        val bw = IntArray(16) { if (it % 2 == 0) 0xFF000000.toInt() else -1 }
        assertTrue(PixelOps.isBilevel(bw))
    }
}
