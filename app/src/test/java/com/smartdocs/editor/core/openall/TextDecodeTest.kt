package com.smartdocs.editor.core.openall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDecodeTest {

    private val arabic = "مرحبا بكم في التطبيق، هذا نص تجريبي للقراءة."
    private val french = "Le café est très bon à Paris, déjà vu."

    @Test fun boms() {
        val u8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + arabic.toByteArray(Charsets.UTF_8)
        TextDecode.decode(u8).let { assertEquals(arabic, it.text); assertEquals(3, it.bomLength); assertEquals(Charsets.UTF_8, it.charset) }
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + arabic.toByteArray(Charsets.UTF_16LE)
        TextDecode.decode(le).let { assertEquals(arabic, it.text); assertEquals(Charsets.UTF_16LE, it.charset) }
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + french.toByteArray(Charsets.UTF_16BE)
        assertEquals(french, TextDecode.decode(be).text)
    }

    @Test fun utf16WithoutBom() {
        assertEquals(Charsets.UTF_16LE, TextDecode.detect(arabic.toByteArray(Charsets.UTF_16LE)).first)
        assertEquals(Charsets.UTF_16LE, TextDecode.detect("plain ascii text in utf-16".toByteArray(Charsets.UTF_16LE)).first)
        assertEquals(Charsets.UTF_16BE, TextDecode.detect("plain ascii text in utf-16".toByteArray(Charsets.UTF_16BE)).first)
        assertEquals(arabic, TextDecode.decode(arabic.toByteArray(Charsets.UTF_16LE)).text)
        assertNull(TextDecode.utf16Guess(arabic.toByteArray(Charsets.UTF_8)))
        assertTrue(TextDecode.looksText(arabic.toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun utf8AndSingleByte() {
        assertEquals(Charsets.UTF_8, TextDecode.detect(arabic.toByteArray(Charsets.UTF_8)).first)
        val w1256 = arabic.toByteArray(charset("windows-1256"))
        assertEquals(TextDecode.WIN1256, TextDecode.detect(w1256).first)
        assertEquals(arabic, TextDecode.decode(w1256).text)
        val w1252 = french.toByteArray(charset("windows-1252"))
        assertEquals(TextDecode.WIN1252, TextDecode.detect(w1252).first)
        assertEquals(french, TextDecode.decode(w1252).text)
    }

    @Test fun truncatedUtf8HeadIsStillUtf8() {
        val b = arabic.toByteArray(Charsets.UTF_8)
        val cut = b.copyOf(b.size - 1)   // last character cut in half
        assertTrue(TextDecode.isUtf8(cut, truncated = true))
        assertFalse(TextDecode.isUtf8(cut, truncated = false))
    }

    @Test fun binaryIsNotText() {
        assertFalse(TextDecode.looksText(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 1, 2, 0, 0)))
        assertTrue(TextDecode.looksText(ByteArray(0)))
    }

    @Test fun csvDelimiters() {
        assertEquals(',', CsvSniff.delimiter("a,b,c\n1,2,3\n4,5,6\n"))
        assertEquals(';', CsvSniff.delimiter("name;price;note\n\"Doe, John\";3,5;x\n\"Roe, Jane\";4,25;y\n"))
        assertEquals('\t', CsvSniff.delimiter("a\tb\tc\n1\t2\t3\n"))
        assertEquals('|', CsvSniff.delimiter("a|b|c\n1|2|3\n"))
        assertEquals('\t', CsvSniff.delimiter("a,b", "tsv"))
        assertEquals(';', CsvSniff.delimiter("الاسم;المدينة\nأحمد;صنعاء\nسارة;عدن\n"))
    }

    @Test fun prettyJsonAndXml() {
        assertEquals("{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": {}\n}", Pretty.json("{\"a\":[1,2],\"b\":{}}"))
        assertNull(Pretty.json("{\"a\":[1,2}"))
        assertEquals("{\n  \"s\": \"x,{y}\"\n}", Pretty.json("{\"s\":\"x,{y}\"}"))
        val x = Pretty.xml("<?xml version=\"1.0\"?><a><b>t</b><c/></a>")!!
        assertEquals("<?xml version=\"1.0\"?>\n<a>\n  <b>t</b>\n  <c/>\n</a>", x)
    }

    @Test fun syntaxSpans() {
        val (spans, _) = Syntax.line("val s = \"hi\" // note", Lang.CLIKE)
        assertEquals(listOf(Tok.KEYWORD, Tok.STRING, Tok.COMMENT), spans.map { it.kind })
        val (j, _) = Syntax.line("  \"key\": 12, \"v\": true", Lang.JSON)
        assertEquals(listOf(Tok.KEY, Tok.NUMBER, Tok.KEY, Tok.KEYWORD), j.map { it.kind })
        val (c1, st) = Syntax.line("x /* open", Lang.CLIKE)
        assertEquals(Tok.COMMENT, c1.last().kind)
        val (c2, st2) = Syntax.line("still */ val", Lang.CLIKE, st)
        assertEquals(Tok.COMMENT, c2.first().kind)
        assertFalse(st2.blockComment)
    }

    @Test fun hexDump() {
        val d = HexDump.dump("PK\u0003\u0004".toByteArray(Charsets.ISO_8859_1))
        assertTrue(d.startsWith("000000  50 4B 03 04"))
        assertTrue(d.trimEnd().endsWith("PK.."))
    }
}
