package com.smartdocs.editor.core.openall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsAndCadTest {

    @Test fun vcards() {
        val v = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ahmed Ali\r\nN:Ali;Ahmed;;;\r\nTEL;TYPE=CELL,VOICE:+967 777\r\n" +
            "EMAIL;TYPE=INTERNET:ahmed@example.com\r\nNOTE:line one\\nline two that is\r\n  folded\r\nORG:Co\\, Ltd\r\nEND:VCARD\r\n" +
            "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D8=B3=D8=A7=D8=B1=D8=A9;;;\r\nTEL;HOME:123\r\nEND:VCARD\r\n"
        val cs = CardReaders.contacts(v)
        assertEquals(2, cs.size)
        assertEquals("Ahmed Ali", cs[0].name)
        assertEquals("tel|cell" to "+967 777", cs[0].fields.first { it.first.startsWith("tel") })
        assertEquals("email" to "ahmed@example.com", cs[0].fields.first { it.first.startsWith("email") })
        assertEquals("line one\nline two that is folded", cs[0].fields.first { it.first == "note" }.second)
        assertEquals("Co, Ltd", cs[0].fields.first { it.first == "org" }.second)
        assertEquals("سارة", cs[1].name)
        assertEquals("tel|home" to "123", cs[1].fields.single())
    }

    @Test fun calendar() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nSUMMARY:Meeting\\, team\r\nDTSTART:20241005T120000Z\r\nDTEND;TZID=Asia/Aden:20241005T130000\r\n" +
            "LOCATION:Sana'a\r\nBEGIN:VALARM\r\nDESCRIPTION:Reminder\r\nEND:VALARM\r\nDESCRIPTION:Agenda\r\nEND:VEVENT\r\n" +
            "BEGIN:VTODO\r\nSUMMARY:Task\r\nDUE;VALUE=DATE:20241010\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val ev = CardReaders.events(ics)
        assertEquals(2, ev.size)
        assertEquals("Meeting, team", ev[0].summary)
        assertEquals("2024-10-05 12:00 UTC", ev[0].start)
        assertEquals("2024-10-05 13:00 (Asia/Aden)", ev[0].end)
        assertEquals("Agenda", ev[0].description)
        assertEquals("VTODO", ev[1].kind)
        assertEquals("2024-10-10", ev[1].start)
    }

    private fun dxf(vararg pairs: Any): String = pairs.toList().chunked(2).joinToString("\n") { "${it[0]}\n${it[1]}" } + "\n"

    @Test fun dxfEntitiesAndBlocks() {
        val text = dxf(
            0, "SECTION", 2, "BLOCKS",
            0, "BLOCK", 2, "SQ", 10, 0.0, 20, 0.0,
            0, "LINE", 10, 0.0, 20, 0.0, 11, 1.0, 21, 0.0,
            0, "ENDBLK",
            0, "ENDSEC",
            0, "SECTION", 2, "ENTITIES",
            0, "LINE", 8, "0", 62, 1, 10, 0.0, 20, 0.0, 11, 10.0, 21, 10.0,
            0, "CIRCLE", 10, 5.0, 20, 5.0, 40, 2.0,
            0, "LWPOLYLINE", 90, 3, 70, 1, 10, 0.0, 20, 0.0, 10, 4.0, 20, 0.0, 10, 4.0, 20, 3.0,
            0, "TEXT", 10, 1.0, 20, 1.0, 40, 0.5, 1, "Room",
            0, "INSERT", 2, "SQ", 10, 100.0, 20, 50.0, 41, 2.0, 42, 2.0,
            0, "ENDSEC", 0, "EOF",
        )
        val d = DxfReader.read(text)
        assertEquals(5, d.shapes.size)
        val line = d.shapes[0] as DxfShape.Line
        assertEquals(0xFFE53935, line.color)
        assertEquals(3, (d.shapes[2] as DxfShape.Poly).pts.size / 2)
        assertTrue((d.shapes[2] as DxfShape.Poly).closed)
        assertEquals("Room", (d.shapes[3] as DxfShape.Text).text)
        val inserted = d.shapes[4] as DxfShape.Line
        assertEquals(100.0, inserted.x1, 1e-9); assertEquals(102.0, inserted.x2, 1e-9); assertEquals(50.0, inserted.y2, 1e-9)
        assertEquals(102.0, d.maxX, 1e-9); assertEquals(0.0, d.minY, 1e-9)
        assertEquals(FType.DXF, FileSniffer.detect("plan", text.toByteArray()))
        assertEquals("a\nb Ø", DxfReader.mtext("{\\fArial;a}\\Pb %%c"))
    }

    @Test fun woffRoundTrip() {
        // one uncompressed table: "head" with 4 bytes
        val table = byteArrayOf(1, 2, 3, 4)
        val w = ByteArray(44 + 20 + 4)
        fun put16(o: Int, v: Int) { w[o] = (v shr 8).toByte(); w[o + 1] = v.toByte() }
        fun put32(o: Int, v: Long) { put16(o, (v shr 16).toInt()); put16(o + 2, v.toInt()) }
        "wOFF".toByteArray().copyInto(w, 0); put32(4, 0x00010000); put32(8, w.size.toLong()); put16(12, 1)
        "head".toByteArray().copyInto(w, 44); put32(48, 64); put32(52, 4); put32(56, 4); put32(60, 0x12345678)
        table.copyInto(w, 64)
        val s = Woff.toSfnt(w)
        assertEquals(0x00, s[0].toInt()); assertEquals(0x01, s[1].toInt())
        assertEquals("head", String(s, 12, 4, Charsets.ISO_8859_1))
        assertTrue(s.copyOfRange(28, 32).contentEquals(table))
    }
}
