package com.smartdocs.editor.cmr

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import com.smartdocs.editor.core.annot.StampSet
import com.smartdocs.editor.core.view5.CRow
import com.smartdocs.editor.core.view5.CommentFilter
import com.smartdocs.editor.core.view5.StampTokens
import com.smartdocs.editor.ui.reader.annot.CommentHoverLogic
import com.smartdocs.editor.ui.reader.annot.TickSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone

/** fix/comments-rest: comments / measure / stamps rows of FINAL_CHECK_2. */
class CommentsRestTest {
    @Test fun hoverTipShowsAuthorAndShortText() {
        val t = CommentHoverLogic.tip(Offset(3f, 4f), " Sara ", "line one\n  line two")!!
        assertEquals("Sara", t.author); assertEquals("line one line two", t.text)
        assertNull(CommentHoverLogic.tip(Offset.Zero, " ", ""))
        val long = CommentHoverLogic.tip(Offset.Zero, "", "x".repeat(300), max = 10)!!
        assertEquals("x".repeat(10) + "…", long.text)
    }

    @Test fun onlyMouseAndStylusHover() {
        assertTrue(CommentHoverLogic.hovers(PointerType.Mouse, false))
        assertTrue(CommentHoverLogic.hovers(PointerType.Stylus, false))
        assertFalse(CommentHoverLogic.hovers(PointerType.Touch, false))
        assertFalse(CommentHoverLogic.hovers(PointerType.Mouse, true))
    }

    @Test fun tickPaletteHas31DistinctAsciiSymbols() {
        assertEquals(31, TickSymbols.all.size)
        assertEquals(31, TickSymbols.all.map { it.first }.distinct().size)
        assertTrue(TickSymbols.all.all { (c, _) -> c.length == 1 && c[0].code in 0x21..0x7E })
    }

    @Test fun csvKeepsOnlyPickedColumns() {
        val rows = listOf(CRow(0, 0, "Text", "Sara", "hello, world", 0L))
        val header = listOf("page", "type", "author", "date", "status", "checked", "contents", "marked", "replies")
        val csv = CommentFilter.csv(rows, header, setOf(6, 2))
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertEquals("author,contents", lines[0])
        assertEquals("Sara,\"hello, world\"", lines[1])
        assertEquals(9, CommentFilter.csv(rows, header).removePrefix("﻿").split("\r\n")[0].split(',').size)
        assertEquals(setOf(0, 2, 6), CommentFilter.columnsOf("6,0,2,99"))
        assertEquals(CommentFilter.CSV_COLUMNS.toSet(), CommentFilter.columnsOf(""))
        assertEquals("0,2,6", CommentFilter.columnsPref(setOf(6, 2, 0)))
    }

    @Test fun sequentialStampCounter() {
        val id = StampTokens.Identity("Ali")
        assertTrue(StampTokens.hasSeq("No. {seq}")); assertFalse(StampTokens.hasSeq("{name}"))
        assertEquals("No. 7 Ali", StampTokens.fill("No. {seq} {name}", id, 0L, tz = TimeZone.getTimeZone("UTC"), seq = 7))
        assertEquals("No. 007", StampTokens.fill("No. {seq:3}", id, 0L, tz = TimeZone.getTimeZone("UTC"), seq = 7))
        assertEquals("No. ٠١٢", StampTokens.fill("No. {seq:3}", id, 0L, arabicDigits = true, tz = TimeZone.getTimeZone("UTC"), seq = 12))
    }

    @Test fun stampSetManifestRoundTrip() {
        val m = StampSet.Manifest("Team\tA", locked = false, stamps = listOf(
            StampSet.Entry("stamp_1.png", "Approved", "QA", null, 0xFF1565C0L, 0.8f, true, locked = true),
            StampSet.Entry(null, "Text", "", "Line 1\nLine \\2", 0x11L)))
        val back = StampSet.parse(StampSet.write(m))!!
        assertEquals(m, back)
        assertNull(StampSet.parse("not a set"))
        // a locked set locks every stamp; paths never leave the set
        val locked = StampSet.parse(StampSet.write(m.copy(locked = true)).replace("stamp_1.png", "../../evil/stamp_1.png"))!!
        assertTrue(locked.stamps.all { it.locked })
        assertEquals("stamp_1.png", locked.stamps[0].file)
    }

    @Test fun stampSetZipRoundTrip() {
        val dir = File(System.getProperty("java.io.tmpdir"), "cmr_set_" + System.nanoTime()).apply { mkdirs() }
        try {
            val png = File(dir, "a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val m = StampSet.Manifest("S", true, listOf(StampSet.Entry("stamp_1.png", "A", "", null, 1L)))
            val bytes = ByteArrayOutputStream().also { StampSet.zip(m, mapOf("stamp_1.png" to png), it) }.toByteArray()
            val out = File(dir, "out").apply { mkdirs() }
            val (m2, files) = StampSet.unzip(ByteArrayInputStream(bytes), out) { File(out, "x_$it") }!!
            assertTrue(m2.locked)
            assertNotNull(files["stamp_1.png"])
            assertEquals(3L, files["stamp_1.png"]!!.length())
        } finally { dir.deleteRecursively() }
    }
}
