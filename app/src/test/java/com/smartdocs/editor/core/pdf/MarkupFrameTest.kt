package com.smartdocs.editor.core.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** audit 5 C-6: QuadPoints order (upper corners at the top of the text) and the text frame on rotated pages. */
class MarkupFrameTest {

    private fun quad(rot: Int) = MarkupFrame.of(100f, 200f, 160f, 212f, rot).quad()

    @Test fun uprightPageUpperCornersAtMaxY() {
        // UL, UR, LL, LR: the upper edge is y = 212 (it used to be 200: underline drawn on top)
        assertArrayEquals(floatArrayOf(100f, 212f, 160f, 212f, 100f, 200f, 160f, 200f), quad(0), 1e-4f)
    }

    @Test fun inputCornersInAnyOrder() {
        assertArrayEquals(quad(0), MarkupFrame.of(160f, 212f, 100f, 200f, 0).quad(), 1e-4f)
    }

    @Test fun rotate90TextRunsAlongPlusY() {
        // shown turned clockwise: user +y reads to the right, user -x is "up"
        val q = quad(90)
        assertArrayEquals(floatArrayOf(100f, 200f, 100f, 212f, 160f, 200f, 160f, 212f), q, 1e-4f)
        val f = MarkupFrame.of(100f, 200f, 160f, 212f, 90)
        assertEquals(12f, f.w, 1e-4f)       // along the text = y extent
        assertEquals(60f, f.h, 1e-4f)       // text height = x extent
        // an underline 1 pt above the lower edge is a vertical line near x = 160 (not a horizontal one)
        val (x0, y0) = f.point(0f, 1f); val (x1, y1) = f.point(f.w, 1f)
        assertEquals(159f, x0, 1e-4f); assertEquals(159f, x1, 1e-4f)
        assertEquals(200f, y0, 1e-4f); assertEquals(212f, y1, 1e-4f)
    }

    @Test fun rotate180And270() {
        assertArrayEquals(floatArrayOf(160f, 200f, 100f, 200f, 160f, 212f, 100f, 212f), quad(180), 1e-4f)
        assertArrayEquals(floatArrayOf(160f, 212f, 160f, 200f, 100f, 212f, 100f, 200f), quad(270), 1e-4f)
    }

    @Test fun rotationIsNormalised() {
        assertArrayEquals(quad(270), quad(-90), 1e-4f)
        assertArrayEquals(quad(90), quad(450), 1e-4f)
    }

    @Test fun quadCoversTheRectangle() {
        for (rot in listOf(0, 90, 180, 270)) {
            val q = quad(rot)
            val xs = (0 until 4).map { q[it * 2] }; val ys = (0 until 4).map { q[it * 2 + 1] }
            assertEquals(100f, xs.min(), 1e-4f); assertEquals(160f, xs.max(), 1e-4f)
            assertEquals(200f, ys.min(), 1e-4f); assertEquals(212f, ys.max(), 1e-4f)
        }
    }
}
