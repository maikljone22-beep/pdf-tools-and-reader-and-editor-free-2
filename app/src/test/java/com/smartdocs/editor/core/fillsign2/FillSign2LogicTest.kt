package com.smartdocs.editor.core.fillsign2

import com.smartdocs.editor.core.fillsign2.MultiPage.Box
import com.smartdocs.editor.core.fillsign2.MultiPage.PageSet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** nitro-fillsign2: comb layout, rounded rectangle path, multi-page placement, recolour and image resolution. */
class FillSign2LogicTest {
    private val eps = 0.001f

    // ---------------- comb ----------------

    @Test fun combPlacesOneCharPerCell() {
        assertEquals(listOf(0 to "1", 1 to "2", 2 to "3"), CombLayout.place("123", 5, rtl = false))
        // right-to-left text starts in the rightmost cell
        assertEquals(listOf(4 to "ا", 3 to "ب"), CombLayout.place("اب", 5, rtl = true))
        // more characters than cells: the rest is cut
        assertEquals(3, CombLayout.place("123456", 3, rtl = false).size)
        // line breaks are not characters
        assertEquals(listOf("1", "2"), CombLayout.chars("1\n2"))
        assertEquals(1, CombLayout.defaultCells(""))
        assertEquals(4, CombLayout.defaultCells("2026"))
    }

    @Test fun combCentresGlyphsInCells() {
        // 4 cells over 100 pt starting at x = 10: cell 2 starts at 60, a 5 pt glyph is centred at 70
        assertEquals(60f, CombLayout.cellLeft(10f, 100f, 4, 2), eps)
        assertEquals(70f - 2.5f, CombLayout.glyphX(10f, 100f, 4, 2, 5f), eps)
        assertEquals(15f, CombLayout.charSpacing(20f, 5f), eps)
        assertEquals(0f, CombLayout.charSpacing(4f, 5f), eps)
        val (w, h) = CombLayout.boxSize(6, 18f, 12f)
        assertEquals(108f, w, eps); assertEquals(22f, h, eps)
    }

    @Test fun combKeepsSurrogatePairsWhole() {
        val s = "a😀b"
        assertEquals(listOf("a", "😀", "b"), CombLayout.chars(s))
    }

    // ---------------- rounded rectangle ----------------

    @Test fun roundRectPathIsClosedAndClamped() {
        val p = RoundRect.path(10f, 20f, 100f, 40f, 50f)
        val start = p.first() as RoundRect.Seg.Move
        val end = p.last() as RoundRect.Seg.Curve
        assertEquals(start.x, end.x3, eps); assertEquals(start.y, end.y3, eps)
        // radius clamped to half the shorter side (20)
        assertEquals(30f, start.x, eps)
        assertEquals(20f, RoundRect.radius(100f, 40f, 50f), eps)
        assertEquals(0f, RoundRect.radius(100f, 40f, -3f), eps)
        // every point inside the box
        p.forEach { s ->
            val pts = when (s) {
                is RoundRect.Seg.Move -> listOf(s.x to s.y); is RoundRect.Seg.Line -> listOf(s.x to s.y)
                is RoundRect.Seg.Curve -> listOf(s.x1 to s.y1, s.x2 to s.y2, s.x3 to s.y3)
            }
            pts.forEach { (x, y) -> assertTrue(x in 10f - eps..110f + eps && y in 20f - eps..60f + eps) }
        }
        assertTrue(RoundRect.defaultRadius(10f, 10f) >= 2f)
        assertEquals(24f, RoundRect.defaultRadius(500f, 500f), eps)
    }

    // ---------------- place on multiple pages ----------------

    @Test fun pageSets() {
        assertEquals(listOf(0, 1, 2, 3, 4), MultiPage.pages(PageSet.ALL, "", 5))
        assertEquals(listOf(0, 2, 4), MultiPage.pages(PageSet.ODD, "", 5))
        assertEquals(listOf(1, 3), MultiPage.pages(PageSet.EVEN, "", 5))
        assertEquals(listOf(0, 1, 2, 4), MultiPage.pages(PageSet.CUSTOM, "1-3,5", 6))
        assertEquals(listOf(0, 1, 4), MultiPage.pages(PageSet.CUSTOM, "1-3,5", 6, except = 2))
        assertEquals(listOf(2, 3), MultiPage.pages(PageSet.CUSTOM, "٣-٤, 9", 4))
        assertEquals(emptyList<Int>(), MultiPage.pages(PageSet.CUSTOM, "abc", 4))
        assertEquals(listOf(1, 2, 3), MultiPage.pages(PageSet.ALL, "", 4, except = 0))
    }

    @Test fun relocateKeepsRelativePositionAndSize() {
        val b = Box(400f, 50f, 500f, 90f)          // 100 x 40 on a 600 x 800 page, centre (450, 70)
        val same = MultiPage.relocate(b, 600f, 800f, 600f, 800f)
        assertEquals(b, same)
        val other = MultiPage.relocate(b, 600f, 800f, 300f, 400f)   // half-size page: centre (225, 35), size kept
        assertEquals(100f, other.width, eps); assertEquals(40f, other.height, eps)
        assertEquals(175f, other.left, eps); assertEquals(15f, other.bottom, eps)
        // never off the page, shrunk only when bigger than the page
        val big = MultiPage.relocate(Box(0f, 0f, 500f, 100f), 600f, 800f, 200f, 300f)
        assertEquals(200f, big.width, eps); assertTrue(big.left >= 0f && big.right <= 200f + eps)
    }

    @Test fun pasteSameSpotOrCentre() {
        val b = Box(10f, 10f, 110f, 60f)
        assertEquals(b, MultiPage.pasteBox(b, 600f, 800f))
        val c = MultiPage.pasteBox(Box(500f, 10f, 700f, 60f), 600f, 800f)
        assertEquals(200f, c.left, eps); assertEquals(375f, c.bottom, eps)
    }

    // ---------------- recolour / resolution ----------------

    @Test fun recolorKeepsAlpha() {
        val out = SignatureImages.recolor(intArrayOf(0x00FFFFFF, 0x80112233.toInt(), 0xFF000000.toInt()), 0x1A237E)
        assertArrayEquals(intArrayOf(0, 0x801A237E.toInt(), 0xFF1A237E.toInt()), out)
    }

    @Test fun dpiFromPngAndJpeg() {
        // PNG signature + IHDR (13 bytes) + pHYs 11811 px/m (300 dpi), unit 1
        fun be(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = be(13) + "IHDR".toByteArray() + ByteArray(13) + ByteArray(4)
        val phys = be(9) + "pHYs".toByteArray() + be(11811) + be(11811) + byteArrayOf(1) + ByteArray(4)
        assertEquals(300f, SignatureImages.dpi(sig + ihdr + phys)!!, 0.1f)
        assertNull(SignatureImages.dpi(sig + ihdr + be(0) + "IEND".toByteArray() + ByteArray(4)))
        // JFIF APP0 with 200 dpi
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16) + "JFIF".toByteArray() +
            byteArrayOf(0, 1, 1, 1, 0, 200.toByte(), 0, 200.toByte(), 0, 0)
        assertEquals(200f, SignatureImages.dpi(jpg)!!, 0.1f)
        assertNull(SignatureImages.dpi(ByteArray(30)))
        assertEquals(72f, SignatureImages.widthPt(300, 300f), eps)
        assertEquals(75f, SignatureImages.widthPt(100, null), eps)    // 96 dpi default
    }
}
