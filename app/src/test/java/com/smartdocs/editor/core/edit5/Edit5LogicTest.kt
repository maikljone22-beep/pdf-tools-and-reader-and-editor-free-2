package com.smartdocs.editor.core.edit5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

class Edit5LogicTest {

    // ---------------- Bates ----------------
    @Test fun batesFormat() {
        val s = BatesSpec(prefix = "ABC-", suffix = "-X", digits = 6, start = 42)
        assertEquals("ABC-000042-X", Bates.format(s, Bates.numberAt(s, 0)))
        assertEquals("ABC-000051-X", Bates.format(s, Bates.numberAt(s, 9)))
        assertEquals("1234567", Bates.format(BatesSpec(digits = 3), 1234567))
    }

    @Test fun batesAcrossFiles() {
        val r = Bates.ranges(BatesSpec(start = 100), listOf(3, 0, 5))
        assertEquals(100L..102L, r[0])
        assertTrue(r[1].isEmpty())
        assertEquals(103L..107L, r[2])
    }

    // ---------------- page labels ----------------
    @Test fun romanAndLetters() {
        assertEquals("XIV", PageLabelFormat.roman(14))
        assertEquals("mcmxcix", PageLabelFormat.roman(1999, upper = false))
        assertEquals("A", PageLabelFormat.letters(1))
        assertEquals("Z", PageLabelFormat.letters(26))
        assertEquals("AA", PageLabelFormat.letters(27))
        assertEquals("bbb", PageLabelFormat.letters(54, upper = false))
    }

    @Test fun labelsForRanges() {
        val ranges = listOf(
            LabelRange(0, LabelStyle.ROMAN_LOWER),
            LabelRange(3, LabelStyle.DECIMAL, start = 1),
            LabelRange(6, LabelStyle.LETTERS_UPPER, prefix = "A-"),
        )
        assertEquals(listOf("i", "ii", "iii", "1", "2", "3", "A-A", "A-B"), PageLabelFormat.labels(ranges, 8))
        // pages before the first range keep their plain number
        assertEquals(listOf("1", "2", "P1"), PageLabelFormat.labels(listOf(LabelRange(2, LabelStyle.DECIMAL, "P")), 3))
    }

    @Test fun findLabel() {
        val labels = listOf("i", "ii", "1", "2")
        assertEquals(1, PageLabelFormat.find(labels, "ii"))
        assertEquals(2, PageLabelFormat.find(labels, "1"))
        assertEquals(3, PageLabelFormat.find(labels, "٢"))      // Arabic-Indic 2
        assertNull(PageLabelFormat.find(labels, "x"))
    }

    // ---------------- imposition ----------------
    @Test fun bookletLtr() {
        val s = ImposeOrder.booklet(8, rtl = false)
        assertEquals(listOf(SheetSide(7, 0), SheetSide(1, 6), SheetSide(5, 2), SheetSide(3, 4)), s)
    }

    @Test fun bookletRtlPadsBlanks() {
        val s = ImposeOrder.booklet(6, rtl = true)
        // padded to 8: slots 6 and 7 are blank (-1); right binding swaps every side
        assertEquals(listOf(SheetSide(0, -1), SheetSide(-1, 1), SheetSide(2, 5), SheetSide(4, 3)), s)
    }

    @Test fun nUpOrder() {
        val sheets = ImposeOrder.nUp(5, 2, 2)
        assertEquals(2, sheets.size)
        assertEquals(listOf(Cell(0, 0, 0), Cell(1, 1, 0), Cell(2, 0, 1), Cell(3, 1, 1)), sheets[0])
        assertEquals(listOf(Cell(4, 0, 0)), sheets[1])
        val rtl = ImposeOrder.nUp(2, 2, 1, rtl = true)
        assertEquals(listOf(Cell(0, 1, 0), Cell(1, 0, 0)), rtl[0])
        val colFirst = ImposeOrder.nUp(4, 2, 2, columnFirst = true)
        assertEquals(listOf(Cell(0, 0, 0), Cell(1, 0, 1), Cell(2, 1, 0), Cell(3, 1, 1)), colFirst[0])
    }

    @Test fun posterAndSplit() {
        val t = ImposeOrder.posterTiles(100f, 100f, 2f, 100f, 100f)
        assertEquals(4, t.size)
        assertEquals(0f, t[0].x, 0.01f); assertEquals(50f, t[0].y, 0.01f); assertEquals(50f, t[0].w, 0.01f)
        val parts = ImposeOrder.splitParts(2, 1, rtl = true)
        assertEquals(0.5f, parts[0][0], 0.001f)       // RTL: right half first
        assertEquals(0f, parts[1][0], 0.001f)
        assertEquals(listOf(0 to 0, 1 to 2, 0 to 1, 1 to 1, 1 to 0), ImposeOrder.interleave(2, 3, reverseB = true))
    }

    @Test fun marginDetect() {
        val w = 10; val h = 8
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        assertNull(MarginDetect.contentBounds(px, w, h))
        px[3 * w + 2] = 0xFF000000.toInt(); px[5 * w + 7] = 0xFF333333.toInt()
        assertEquals(listOf(2, 3, 7, 5), MarginDetect.contentBounds(px, w, h)!!.toList())
    }

    // ---------------- Hijri ----------------
    @Test fun hijriTabularEpoch() {
        // 1 Muharram 1 AH (civil epoch) = 16 July 622 Julian = 19 July 622 proleptic Gregorian
        assertEquals(Hijri(1, 1, 1), HijriDate.tabular(LocalDate.of(622, 7, 19)))
    }

    @Test fun hijriTabularRoundTrip() {
        var d = LocalDate.of(2020, 1, 1)
        repeat(800) {
            val h = HijriDate.tabular(d)
            assertTrue(h.month in 1..12 && h.day in 1..30)
            assertEquals(d, HijriDate.tabularToGregorian(h))
            d = d.plusDays(3)
        }
    }

    @Test fun hijriUmmAlQura() {
        assertEquals(Hijri(1446, 1, 1), HijriDate.of(LocalDate.of(2024, 7, 7)))
        assertEquals(Hijri(1446, 9, 1), HijriDate.of(LocalDate.of(2025, 3, 1)))
        // the arithmetic calendar stays within two days of Umm al-Qura
        val d = LocalDate.of(2025, 3, 1)
        val diff = HijriDate.toJdn(1446, 9, 1) - (d.toEpochDay() + 2440588L)
        assertTrue(abs(diff) <= 2)
    }

    @Test fun hijriFormatAndTokens() {
        assertEquals("1 رمضان 1446", HijriDate.format(Hijri(1446, 9, 1), "d MMMM yyyy", true))
        assertEquals("01/09/1446", HijriDate.format(Hijri(1446, 9, 1), "dd/MM/yyyy", false))
        val ctx = TokenContext(page = 3, pages = 10, date = LocalDate.of(2025, 3, 1), time = LocalTime.of(9, 5), file = "f.pdf", user = "Ali")
        assertEquals("3 / 10", Tokens.expand("{page} / {pages}", ctx))
        assertEquals("٣ / ١٠", Tokens.expand("{page} / {pages}", ctx, arabicDigits = true))
        assertEquals("2025/03/01 09:05 Ali", Tokens.expand("{date} {time} {user}", ctx))
        assertEquals("1 رمضان 1446 هـ", Tokens.expand("{hijri}", ctx))
        assertEquals("{nope}", Tokens.expand("{nope}", ctx))
    }

    // ---------------- misc ----------------
    @Test fun pageSetParse() {
        assertEquals(listOf(0, 1, 2, 4), PageSet.parse("1-3, 5", 10))
        assertEquals(listOf(7, 8, 9), PageSet.parse("8-", 10))
        assertEquals(listOf(1, 2), PageSet.parse("٢،٣", 10))
        assertEquals(listOf(0, 2), PageSet.filter(listOf(0, 1, 2, 3), PageSubset.ODD))
    }

    @Test fun linkDetect() {
        val l = LinkDetect.find("زر www.example.com أو راسل a.b@c.org الآن")
        assertEquals(listOf("https://www.example.com", "mailto:a.b@c.org"), l.map { it.target })
        assertEquals("https://x.y", LinkDetect.normalizeUrl("x.y"))
        assertEquals("ftp://x", LinkDetect.normalizeUrl("ftp://x"))
        val m = LinkDetect.mailto("a@b.c", "مرحبا بك", "x y")
        assertEquals(Triple("a@b.c", "مرحبا بك", "x y"), LinkDetect.parseMailto(m))
    }
}
