package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageQuadTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A dark desk with a light, slightly rotated sheet whose corners are [qx]/[qy] (0..1). */
    private fun scene(w: Int, h: Int, qx: FloatArray, qy: FloatArray, paper: Int = rgb(235, 232, 225), desk: Int = rgb(70, 60, 50)): IntArray {
        val px = IntArray(w * h) { desk }
        for (y in 0 until h) for (x in 0 until w) {
            val fx = (x + 0.5f) / w; val fy = (y + 0.5f) / h
            if (inside(fx, fy, qx, qy)) px[y * w + x] = paper
        }
        // some text lines on the sheet
        for (y in 0 until h) for (x in 0 until w) {
            val fx = (x + 0.5f) / w; val fy = (y + 0.5f) / h
            if (inside(fx, fy, qx, qy) && (y / 6) % 3 == 1 && (x / 5) % 2 == 0 && fx in 0.3f..0.7f) px[y * w + x] = rgb(30, 30, 30)
        }
        return px
    }

    private fun inside(fx: Float, fy: Float, qx: FloatArray, qy: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val c = (qx[j] - qx[i]) * (fy - qy[i]) - (qy[j] - qy[i]) * (fx - qx[i])
            val s = if (c >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    @Test fun findsRotatedSheetCorners() {
        val w = 320; val h = 240
        val qx = floatArrayOf(0.18f, 0.80f, 0.84f, 0.14f); val qy = floatArrayOf(0.12f, 0.16f, 0.86f, 0.82f)
        val q = PageQuad.detect(scene(w, h, qx, qy), w, h)
        assertNotNull(q); q!!
        for (i in 0 until 4) {
            assertTrue("corner $i x ${q.x[i]} vs ${qx[i]}", abs(q.x[i] - qx[i]) < 0.03f)
            assertTrue("corner $i y ${q.y[i]} vs ${qy[i]}", abs(q.y[i] - qy[i]) < 0.03f)
        }
    }

    @Test fun darkPageOnLightDeskToo() {
        val w = 240; val h = 320
        val qx = floatArrayOf(0.2f, 0.8f, 0.8f, 0.2f); val qy = floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)
        val q = PageQuad.detect(scene(w, h, qx, qy, paper = rgb(40, 40, 60), desk = rgb(230, 230, 230)), w, h)
        assertNotNull(q); q!!
        assertTrue(abs(q.x[0] - 0.2f) < 0.03f && abs(q.y[2] - 0.9f) < 0.03f)
    }

    @Test fun plainFrameHasNoPage() {
        val w = 200; val h = 150
        val px = IntArray(w * h) { i -> val v = 120 + ((i * 7919) % 11); rgb(v, v, v) }   // flat noise, no sheet
        // either nothing, or a "page" that is nearly the whole frame (which the camera treats as no page)
        val q = PageQuad.detect(px, w, h)
        assertTrue(q == null || q.area() > 0.9f)
    }

    @Test fun warpStraightensTheSheet() {
        val w = 400; val h = 300
        val qx = floatArrayOf(0.2f, 0.7f, 0.8f, 0.1f); val qy = floatArrayOf(0.15f, 0.1f, 0.9f, 0.85f)
        val src = scene(w, h, qx, qy)
        val (out, ow, oh) = PageQuad.warp(src, w, h, PageQuad.Quad(qx, qy))
        assertTrue(ow in 200..300 && oh in 200..260)
        // the four output corners (just inside) are paper, and the centre of each edge is paper too
        fun lum(x: Int, y: Int) = PixelOps.lum(out[y * ow + x])
        for ((x, y) in listOf(3 to 3, ow - 4 to 3, ow - 4 to oh - 4, 3 to oh - 4, ow / 2 to 2, ow / 2 to oh - 3, 2 to oh / 2, ow - 3 to oh / 2))
            assertTrue("($x,$y) lum ${lum(x, y)}", lum(x, y) > 180)
    }

    @Test fun homographyMapsCorners() {
        val sx = floatArrayOf(0f, 10f, 10f, 0f); val sy = floatArrayOf(0f, 0f, 10f, 10f)
        val dx = floatArrayOf(2f, 9f, 11f, 1f); val dy = floatArrayOf(1f, 2f, 12f, 9f)
        val hm = PageQuad.homography(sx, sy, dx, dy)
        for (i in 0 until 4) {
            val d = hm[6] * sx[i] + hm[7] * sy[i] + 1.0
            assertEquals(dx[i].toDouble(), (hm[0] * sx[i] + hm[1] * sy[i] + hm[2]) / d, 1e-6)
            assertEquals(dy[i].toDouble(), (hm[3] * sx[i] + hm[4] * sy[i] + hm[5]) / d, 1e-6)
        }
    }

    @Test fun fullQuadIsIdentity() {
        val w = 40; val h = 30
        val src = IntArray(w * h) { rgb(it % 256, (it * 3) % 256, (it * 7) % 256) }
        val (out, ow, oh) = PageQuad.warp(src, w, h, PageQuad.Quad.FULL)
        assertEquals(w, ow); assertEquals(h, oh)
        assertEquals(src[17 * w + 23], out[17 * w + 23])
        assertNull(null)
    }
}
