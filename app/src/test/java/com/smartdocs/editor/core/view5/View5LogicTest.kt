package com.smartdocs.editor.core.view5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

class MeasureGeometryTest {
    private val eps = 1e-6

    @Test fun distanceAndPath() {
        assertEquals(5.0, Geom.dist(P(0.0, 0.0), P(3.0, 4.0)), eps)
        val sq = listOf(P(0.0, 0.0), P(10.0, 0.0), P(10.0, 10.0), P(0.0, 10.0))
        assertEquals(30.0, Geom.pathLength(sq), eps)
        assertEquals(40.0, Geom.pathLength(sq, closed = true), eps)
        assertEquals(0.0, Geom.pathLength(listOf(P(1.0, 1.0))), eps)
    }

    @Test fun polygonAreaAnyWinding() {
        val sq = listOf(P(0.0, 0.0), P(10.0, 0.0), P(10.0, 10.0), P(0.0, 10.0))
        assertEquals(100.0, Geom.polygonArea(sq), eps)
        assertEquals(100.0, Geom.polygonArea(sq.reversed()), eps)
        assertEquals(6.0, Geom.polygonArea(listOf(P(0.0, 0.0), P(4.0, 0.0), P(0.0, 3.0))), eps)
        assertEquals(0.0, Geom.polygonArea(listOf(P(0.0, 0.0), P(1.0, 1.0))), eps)
    }

    @Test fun circleAndAngle() {
        assertEquals(Math.PI * 4, Geom.circleArea(2.0), eps)
        assertEquals(90.0, Geom.angleDeg(P(1.0, 0.0), P(0.0, 0.0), P(0.0, 1.0)), eps)
        assertEquals(180.0, Geom.angleDeg(P(1.0, 0.0), P(0.0, 0.0), P(-1.0, 0.0)), eps)
        assertEquals(45.0, Geom.angleDeg(P(1.0, 0.0), P(0.0, 0.0), P(1.0, 1.0)), eps)
        assertEquals(0.0, Geom.angleDeg(P(0.0, 0.0), P(0.0, 0.0), P(1.0, 1.0)), eps)
    }

    @Test fun constrainAndSnap() {
        val c = Geom.constrain45(P(0.0, 0.0), P(10.0, 1.0))
        assertEquals(0.0, c.y, 1e-9); assertEquals(Math.hypot(10.0, 1.0), c.x, 1e-9)
        val d = Geom.constrain45(P(0.0, 0.0), P(10.0, 9.0))
        assertEquals(d.x, d.y, 1e-9)
        assertEquals(P(5.0, 5.0), Geom.snap(P(5.5, 5.2), listOf(P(0.0, 0.0), P(5.0, 5.0)), 2.0))
        assertNull(Geom.snap(P(50.0, 50.0), listOf(P(0.0, 0.0)), 2.0))
        val x = Geom.intersect(P(0.0, 0.0), P(10.0, 10.0), P(0.0, 10.0), P(10.0, 0.0))
        assertNotNull(x); assertEquals(5.0, x!!.x, eps); assertEquals(5.0, x.y, eps)
        assertNull(Geom.intersect(P(0.0, 0.0), P(1.0, 0.0), P(0.0, 1.0), P(1.0, 1.0)))
        // end point beats the nearest point on the segment
        val segs = listOf(P(0.0, 0.0) to P(100.0, 0.0))
        assertEquals(P(100.0, 0.0), Geom.snapToSegments(P(98.0, 1.0), segs, 3.0))
        assertEquals(P(50.0, 0.0), Geom.snapToSegments(P(51.0, 1.0), segs, 3.0))        // midpoint
        assertEquals(P(30.0, 0.0), Geom.snapToSegments(P(30.0, 2.0), segs, 3.0))        // on path
    }
}

class ScaleParsingTest {
    @Test fun parseForms() {
        val s = MeasureScale.parse("1 in = 10 ft")!!
        assertEquals(MUnit.IN, s.docUnit); assertEquals(MUnit.FT, s.realUnit); assertEquals(10.0, s.realValue, 1e-9)
        // 72 pt = 1 inch on paper = 10 ft
        assertEquals(10.0, s.length(72.0), 1e-9)
        assertEquals(100.0, s.area(72.0 * 72.0), 1e-6)
        val r = MeasureScale.parse("1:100")!!
        assertEquals(MUnit.CM, r.docUnit); assertEquals(100.0, r.realValue, 1e-9)
        assertEquals(254.0, r.length(72.0), 1e-6)
        val ar = MeasureScale.parse("١ سم = ٢٫٥ م")!!
        assertEquals(MUnit.CM, ar.docUnit); assertEquals(MUnit.M, ar.realUnit); assertEquals(2.5, ar.realValue, 1e-9)
        assertEquals(MeasureScale(2.0, MUnit.MM, 1.0, MUnit.M), MeasureScale.parse("2mm=1m"))
        assertNull(MeasureScale.parse("abc"))
        assertNull(MeasureScale.parse("0 cm = 1 m"))
        assertNull(MeasureScale.parse("1 cm = 1 parsec"))
        assertEquals("1 in = 10 ft", s.label())
    }

    @Test fun calibration() {
        // a 144 pt segment is 3 m long
        val s = MeasureScale.calibrate(144.0, 3.0, MUnit.M)!!
        assertEquals(3.0, s.length(144.0), 1e-9)
        assertEquals(1.5, s.length(72.0), 1e-9)
        assertNull(MeasureScale.calibrate(0.0, 3.0, MUnit.M))
    }

    @Test fun unitsAndFormat() {
        assertEquals(2.54, MUnit.convert(1.0, MUnit.IN, MUnit.CM), 1e-9)
        assertEquals(1.0, MUnit.convert(72.0, MUnit.PT, MUnit.IN), 1e-9)
        assertEquals("12.35 cm", MeasureFormat.value(12.345, 2, "cm"))
        assertEquals("١٢٫٣ cm", MeasureFormat.value(12.3, 1, "cm", arabicDigits = true))
        val csv = MeasureFormat.csv(listOf(MeasureRow(0, MeasureType.DISTANCE, 2.0, "m"), MeasureRow(1, MeasureType.DISTANCE, 3.0, "m", label = "a,b")))
        assertTrue(csv.startsWith("﻿"))
        assertTrue(csv.contains("\"a,b\""))
        assertTrue(csv.contains("total,distance,5.0000,m,n=2"))
    }
}

class HijriDateTest {
    @Test fun knownDates() {
        // Umm al-Qura: 1 Muharram 1446 = 7 July 2024
        assertEquals(Hijri(1446, 1, 1), HijriDate.fromGregorian(2024, 7, 7))
        // Umm al-Qura: 1 Ramadan 1445 = 11 March 2024
        assertEquals(Hijri(1445, 9, 1), HijriDate.fromGregorian(2024, 3, 11))
        // tabular calendar: one day behind Umm al-Qura here; epoch 19 July 622 (proleptic Gregorian) = 1/1/1
        assertEquals(Hijri(1445, 12, 30), HijriDate.tabular(2024, 7, 7))
        assertEquals(Hijri(1445, 9, 1), HijriDate.tabular(2024, 3, 11))
        assertEquals(Hijri(1, 1, 1), HijriDate.tabular(622, 7, 19))
    }

    @Test fun roundTripAndAdjust() {
        for (jd in 2451545L..2451545L + 3000 step 37) {
            val h = HijriDate.fromJulianDay(jd)
            assertEquals(jd, HijriDate.toJulianDay(h))
        }
        assertEquals(Hijri(1445, 12, 30), HijriDate.fromGregorian(2024, 7, 7, adjustDays = -1))
        assertEquals(Hijri(1446, 1, 2), HijriDate.fromGregorian(2024, 7, 7, adjustDays = 1))
    }

    @Test fun formatAndTokens() {
        assertEquals("1 Muharram 1446 AH", Hijri(1446, 1, 1).format(arabic = false))
        assertEquals("١ محرم ١٤٤٦ هـ", Hijri(1446, 1, 1).format())
        val utc = TimeZone.getTimeZone("UTC")
        val ms = 1720310400000L    // 2024-07-07 00:00 UTC
        val s = StampTokens.fill("{name} - {date} - {hijri_num} - {unknown}", StampTokens.Identity("Ali"), ms, tz = utc)
        assertEquals("Ali - 2024/07/07 - 1446/01/01 - {unknown}", s)
    }
}

class PositionHistoryTest {
    @Test fun backForward() {
        val h = PositionHistory()
        assertFalse(h.canBack)
        h.visit(0, 10)            // at 10 now
        h.visit(10, 25)           // at 25 now
        assertEquals(10, h.back(25))
        assertEquals(0, h.back(10))
        assertNull(h.back(0))
        assertEquals(10, h.forward(0))
        assertEquals(25, h.forward(10))
        assertNull(h.forward(25))
    }

    @Test fun visitClearsForwardAndCollapses() {
        val h = PositionHistory(max = 3)
        h.visit(1, 2); h.visit(1, 3); h.visit(5, 5)
        assertEquals(1, h.backSize)
        assertEquals(1, h.back(3))
        assertTrue(h.canForward)
        h.visit(1, 7)
        assertFalse(h.canForward)
        for (i in 10..20) h.visit(i, i + 100)
        assertEquals(3, h.backSize)
        assertEquals(listOf(20, 19, 18), h.backList())
    }
}

class TrashTest {
    @Test fun expiry() {
        val day = Trash.DAY_MS
        assertFalse(Trash.isExpired(0, 29 * day))
        assertTrue(Trash.isExpired(0, 30 * day))
        assertTrue(Trash.isExpired(0, 31 * day))
        assertFalse(Trash.isExpired(0, 6 * day, days = 7))
        val e = Trash.Entry("x", File("x"), "/a/x.pdf", "x.pdf", 0, 1, false)
        assertEquals(30, e.daysLeft(1))
        assertEquals(1, e.daysLeft(29 * day + 5))
        assertEquals(0, e.daysLeft(31 * day))
    }

    @Test fun moveRestorePurge() {
        val root = Files.createTempDirectory("trash").toFile()
        try {
            val docs = File(root, "docs").apply { mkdirs() }
            val f = File(docs, "a.pdf").apply { writeText("pdf") }
            val t = Trash(File(root, "trash"))
            val e = t.moveToTrash(f, now = 1000L)!!
            assertFalse(f.exists())
            assertEquals(1, t.list().size)
            assertEquals("a.pdf", t.list()[0].name)
            File(docs, "a.pdf").writeText("new")       // name taken: restore picks "a (2).pdf"
            val back = t.restore(t.list()[0])!!
            assertEquals("a (2).pdf", back.name)
            assertEquals("pdf", back.readText())
            assertTrue(t.list().isEmpty())
            t.moveToTrash(back, now = 0L)
            t.moveToTrash(File(docs, "a.pdf"), now = 40 * Trash.DAY_MS)
            assertEquals(1, t.purgeExpired(now = 45 * Trash.DAY_MS))
            assertEquals(1, t.list().size)
            assertTrue(e.id.isNotEmpty())
        } finally { root.deleteRecursively() }
    }
}

class PrintLayoutTest {
    @Test fun ranges() {
        assertEquals(listOf(0, 1, 2, 4), PrintLayout.parseRange("1-3, 5", 10))
        assertEquals(listOf(7, 8, 9), PrintLayout.parseRange("8-", 10))
        assertEquals(listOf(0, 1), PrintLayout.parseRange("-2", 10))
        assertEquals(listOf(2, 1, 0), PrintLayout.parseRange("3-1", 10))
        assertEquals((0 until 4).toList(), PrintLayout.parseRange("", 4))
        assertEquals(listOf(1, 3), PrintLayout.parseRange("٢، ٤, 99", 5))
        assertEquals(listOf(0, 2), PrintLayout.subset(listOf(0, 1, 2, 3), PrintLayout.Subset.ODD))
    }

    @Test fun booklet() {
        assertEquals(listOf(3 to 0, 1 to 2), PrintLayout.booklet(listOf(0, 1, 2, 3)))
        val b = PrintLayout.booklet(listOf(0, 1, 2, 3, 4))      // padded to 8
        assertEquals(4, b.size)
        assertEquals(-1 to 0, b[0])
        assertEquals(0 to -1, PrintLayout.booklet(listOf(0, 1, 2, 3, 4), rightBinding = true)[0])
    }

    @Test fun nUp() {
        assertEquals(2 to 2, PrintLayout.grid(4, false))
        assertEquals(3 to 2, PrintLayout.grid(6, true))
        val cells = PrintLayout.place(listOf(0, 1), { 600f to 800f }, 2, 600f, 800f, margin = 0f, gap = 0f)
        assertEquals(2, cells.size)
        assertTrue(cells[0].y > cells[1].y)                  // first page on top
        assertEquals(0.5f, cells[0].scale, 1e-4f)
    }
}

class CommandAndCommentTest {
    @Test fun rankCommands() {
        val cmds = listOf(CommandDef("measure", "قياس المسافة", listOf("measure", "distance")),
            CommandDef("print", "طباعة", listOf("print")), CommandDef("present", "وضع العرض التقديمي", listOf("presentation")))
        assertEquals("print", CommandRank.rank(cmds, "طبا").first().id)
        assertEquals("measure", CommandRank.rank(cmds, "dist").first().id)
        assertEquals("present", CommandRank.rank(cmds, "presen").first().id)
        assertTrue(CommandRank.rank(cmds, "zzz").isEmpty())
        assertEquals(3, CommandRank.rank(cmds, "").size)
        assertEquals("present", CommandRank.rank(cmds, "", pinned = setOf("present")).first().id)
        assertEquals(listOf("b", "a"), CommandRank.pushRecent(listOf("a", "b"), "b"))
    }

    @Test fun filters() {
        val rows = listOf(
            CRow(0, 0, "Highlight", "Ali", "مُراجعة", 10, markedText = "النص"),
            CRow(1, 0, "Text", "Sara", "note", 20, status = "Accepted"),
            CRow(2, 1, "Text", "Ali", "other", 30, checked = true),
        )
        assertEquals(1, CommentFilter.apply(rows, CFilter(query = "مراجعه")).size)       // folding: tashkeel + taa marbuta
        assertEquals(2, CommentFilter.apply(rows, CFilter(authors = setOf("Ali"))).size)
        assertEquals(1, CommentFilter.apply(rows, CFilter(types = setOf("Text"), statuses = setOf("Accepted"))).size)
        assertEquals(1, CommentFilter.apply(rows, CFilter(pages = setOf(2))).size)
        assertEquals(1, CommentFilter.apply(rows, CFilter(checked = true)).size)
        assertEquals(listOf(30L, 20L, 10L), CommentFilter.apply(rows, CFilter(), CSort.DATE_NEW).map { it.date })
        assertTrue(CommentFilter.csv(rows, listOf("p")).contains("Sara"))
        assertTrue(CommentFilter.highlightedText(rows) { "p${it + 1}" }.contains("النص"))
    }

    @Test fun reflow() {
        val lines = listOf(
            RLine("Title", 20f, 800f, 780f, 50f, 200f),
            RLine("first line of text", 10f, 760f, 750f, 50f, 500f),
            RLine("second line", 10f, 748f, 738f, 50f, 300f),
            RLine("new para", 10f, 700f, 690f, 50f, 300f),
            RLine("12", 10f, 30f, 20f, 290f, 300f),
        )
        val b = ReflowBlocks.build(0, lines, listOf(RImage(720f, 705f, 50f, 300f)), 842f)
        assertTrue(b[0] is RBlock.PageMark)
        assertEquals(RBlock.Heading(0, "Title", 1), b[1])
        assertEquals("first line of text second line", (b[2] as RBlock.Para).text)
        assertTrue(b[3] is RBlock.Image)
        assertEquals("new para", (b[4] as RBlock.Para).text)
        assertEquals(5, b.size)                                // page number dropped
        assertTrue(ReflowBlocks.isRtl("مرحبا بالعالم world"))
    }
}
