package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scanner essentials: sideways pages and fingers on book edges. */
class ScanEssentialsTest {
    private val white = 0xFFF5F5F2.toInt(); private val ink = 0xFF202020.toInt()

    /** Lines of "text" across the page (or down it when [sideways]). */
    private fun page(w: Int, h: Int, sideways: Boolean) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val a = if (sideways) x else y; val b = if (sideways) y else x
        if (a % 14 < 4 && (b / 3) % 4 != 0) ink else white
    }

    @Test fun textAcrossIsUpright() {
        assertFalse(ScanOrient.sideways(page(300, 400, false), 300, 400))
        assertTrue(ScanOrient.sideways(page(300, 400, true), 300, 400))
        // blank paper: no decision, never sideways
        assertFalse(ScanOrient.sideways(IntArray(300 * 400) { white }, 300, 400))
    }

    @Test fun fingerAtTheEdgeIsRemoved() {
        val w = 200; val h = 260
        val px = page(w, h, false)
        val skin = 0xFFD8A07E.toInt()
        // a thumb coming in from the left edge, and a skin-toned picture in the middle that must stay
        for (y in 100 until 140) for (x in 0 until 22) px[y * w + x] = skin
        for (y in 120 until 150) for (x in 90 until 120) px[y * w + x] = skin
        val n = ScanClean.removeFingers(px, w, h)
        assertTrue(n > 0)
        assertTrue(px[120 * w + 5] != skin)
        assertEquals(skin, px[130 * w + 100])
    }

    @Test fun noFingerNoChange() {
        val w = 200; val h = 260
        val px = page(w, h, false); val before = px.copyOf()
        assertEquals(0, ScanClean.removeFingers(px, w, h))
        assertTrue(before.contentEquals(px))
    }
}
