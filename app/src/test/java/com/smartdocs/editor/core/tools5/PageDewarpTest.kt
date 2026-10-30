package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Curved-page flattening on synthetic pages: bent text lines become straight; flat pages and photos stay untouched. */
class PageDewarpTest {
    private val w = 900; private val h = 1200
    private val white = 0xFFF2F2F2.toInt(); private val ink = 0xFF202020.toInt()

    /** A page of word blocks along lines bent by [bend] (px at the page edges, a parabola like an open book). */
    private fun page(bend: Double, tilt: Double = 0.0): IntArray {
        val px = IntArray(w * h) { white }
        val rnd = java.util.Random(7)
        for (line in 0 until 14) {
            val y0 = 120 + line * 70
            var x = 80
            while (x < w - 80) {
                val ww = 25 + rnd.nextInt(45)
                for (xx in x until minOf(w - 80, x + ww)) {
                    val u = (xx - w / 2.0) / (w / 2.0)
                    val dy = bend * u * u * (0.6 + 0.4 * y0 / h) + tilt * u
                    for (k in 0 until 14) { val y = (y0 + k + dy).toInt(); if (y in 0 until h) px[y * w + xx] = ink }
                }
                x += ww + 12
            }
        }
        return px
    }

    /** Rows that contain ink: straight lines need few, bent lines spread over many. */
    private fun inkRows(px: IntArray): Int = (0 until h).count { y -> (0 until w).any { x -> (px[y * w + x] and 0xFF) < 128 } }

    @Test fun bentLinesBecomeStraight() {
        val px = page(bend = 40.0)
        val before = inkRows(px)
        assertTrue(PageDewarp.flatten(px, w, h))
        val after = inkRows(px)
        assertTrue("rows with ink $before → $after", after < before * 0.6)
    }

    @Test fun tiltIsRemovedToo() {
        val px = page(bend = 0.0, tilt = 25.0)
        val before = inkRows(px)
        assertTrue(PageDewarp.flatten(px, w, h))
        assertTrue(inkRows(px) < before * 0.7)
    }

    @Test fun flatPageIsLeftAlone() {
        val px = page(bend = 0.0)
        val copy = px.copyOf()
        assertFalse(PageDewarp.flatten(px, w, h))
        assertTrue(px.contentEquals(copy))
    }

    @Test fun pageWithoutTextIsLeftAlone() {
        val px = IntArray(w * h) { i -> if ((i / w) in 300..700 && (i % w) in 200..600) ink else white }
        assertFalse(PageDewarp.flatten(px, w, h))
    }

    @Test fun unevenLightIsDetected() {
        val shadow = IntArray(w * h) { i -> val v = 240 - (i % w) * 120 / w; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertTrue(PixelOps.unevenLight(shadow, w, h))
        assertFalse(PixelOps.unevenLight(IntArray(w * h) { white }, w, h))
    }
}
