package com.smartdocs.editor.core.pageflip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.random.Random

class CurlMathTest {
    private val w = 600f
    private val h = 900f
    private val eps = 0.05f

    @Test fun flatWhenFingerOnGrabPoint() {
        assertNull(CurlMath.fold(w, h, w, h, 30f, h))
    }

    @Test fun plainFoldIsAReflection() {
        // r = 0, finger at the middle: the axis is x = w * 3/4, a point beyond it is mirrored
        val f = CurlMath.fold(w, h / 2, w / 2, h / 2, 0f, h)!!
        assertEquals(0.75f * w, f.ax, eps)
        assertEquals(1f, f.nx, eps)
        val out = FloatArray(4)
        CurlMath.deform(w, h / 2, f, CurlMath.Side.BOTH, out)
        assertEquals(w / 2, out[0], eps)            // the grabbed edge lands on the finger
        CurlMath.deform(0.5f * w, 10f, f, CurlMath.Side.BOTH, out)
        assertEquals(0.5f * w, out[0], eps)         // flat side untouched
        assertEquals(10f, out[1], eps)
    }

    @Test fun cylinderProjection() {
        val r = 40f
        val f = CurlMath.Fold(300f, 0f, 1f, 0f, r)
        val out = FloatArray(4)
        // quarter turn: crest of the cylinder, z = r
        CurlMath.deform(300f + (PI / 2 * r).toFloat(), 5f, f, CurlMath.Side.BOTH, out)
        assertEquals(300f + r, out[0], eps); assertEquals(r, out[2], eps); assertEquals(5f, out[1], eps)
        // half turn: back on the page side, z = 2r
        CurlMath.deform(300f + (PI * r).toFloat(), 0f, f, CurlMath.Side.BOTH, out)
        assertEquals(300f, out[0], 0.5f); assertEquals(2 * r, out[2], eps)
        // beyond half a turn: flat on the back, mirrored
        CurlMath.deform(300f + (PI * r).toFloat() + 100f, 0f, f, CurlMath.Side.BOTH, out)
        assertEquals(200f, out[0], 0.5f); assertEquals(2 * r, out[2], eps)
    }

    @Test fun frontAndBackMeshesSplitAtTheCrest() {
        val r = 40f
        val f = CurlMath.Fold(300f, 0f, 1f, 0f, r)
        val out = FloatArray(4)
        // a back-side point drawn by the front mesh collapses onto the crest line (zero area, invisible)
        CurlMath.deform(560f, 0f, f, CurlMath.Side.FRONT, out)
        assertEquals(340f, out[0], eps)
        // a front-side point drawn by the back mesh collapses onto the crest line too
        CurlMath.deform(100f, 0f, f, CurlMath.Side.BACK, out)
        assertEquals(340f, out[0], eps)
    }

    @Test fun spineNeverLifts() {
        val rnd = Random(7)
        val out = FloatArray(2)
        repeat(500) {
            val gy = if (rnd.nextBoolean()) h else rnd.nextFloat() * h
            CurlMath.constrain(rnd.nextFloat() * 3 * w - 2 * w, rnd.nextFloat() * 2 * h - h / 2, w, gy, h, out)
            val t = CurlMath.progressOf(out[0], w)
            val f = CurlMath.fold(w, gy, out[0], out[1], CurlMath.radiusFor(t, w, rnd.nextFloat()), h) ?: return@repeat
            assertTrue("top spine corner lifted", f.d(0f, 0f) <= 0.01f)
            assertTrue("bottom spine corner lifted", f.d(0f, h) <= 0.01f)
        }
    }

    @Test fun constrainKeepsLeafLength() {
        val out = FloatArray(2)
        CurlMath.constrain(-5 * w, h, w, h, h, out)
        // never farther from the bottom spine corner than the grabbed corner is
        assertTrue(hypot(out[0], out[1] - h) <= w + 0.5f)
        CurlMath.constrain(w * 2, h, w, h, h, out)
        assertTrue(out[0] <= w)
    }

    @Test fun radiusVanishesAtBothEnds() {
        assertEquals(0f, CurlMath.radiusFor(0f, w, 1f), 0f)
        assertEquals(0f, CurlMath.radiusFor(1f, w, 1f), 0f)
        assertTrue(CurlMath.radiusFor(0.5f, w, 1f) > CurlMath.radiusFor(0.5f, w, 0f))
    }

    @Test fun autoPathEnds() {
        val out = FloatArray(2)
        CurlMath.autoPath(0f, 1f, w, h, 0.1f, out)
        assertEquals(w, out[0], eps); assertEquals(h, out[1], eps)
        CurlMath.autoPath(1f, 1f, w, h, 0.1f, out)
        assertEquals(-w, out[0], eps); assertEquals(h, out[1], 0.5f)
        CurlMath.autoPath(0.5f, 1f, w, h, 0.1f, out)
        assertTrue("bottom corner lifts towards the middle", out[1] < h)
        assertEquals(0.5f, CurlMath.progressOf(0f, w), eps)
    }

    @Test fun meshFlatAndMirrored() {
        val cols = 4; val rows = 3
        val v = FloatArray((cols + 1) * (rows + 1) * 2); val l = FloatArray((cols + 1) * (rows + 1))
        assertTrue(CurlMath.buildMesh(w, h, cols, rows, null, CurlMath.Side.FRONT, false, 100f, 1f, 50f, 1f, v, l))
        assertEquals(100f, v[0], eps); assertEquals(50f, v[1], eps)
        assertEquals(100f + w, v[cols * 2], eps)
        assertEquals(1f, l[0], eps)
        assertFalse(CurlMath.buildMesh(w, h, cols, rows, null, CurlMath.Side.BACK, false, 100f, 1f, 50f, 1f, v, l))
        // RTL: spine on the right at x = 700, texture flipped so the bitmap is not mirrored
        CurlMath.buildMesh(w, h, cols, rows, null, CurlMath.Side.FRONT, true, 700f, -1f, 0f, 1f, v, l)
        assertEquals(100f, v[0], eps)               // bitmap left edge -> free edge on the left
        assertEquals(700f, v[cols * 2], eps)        // bitmap right edge -> spine
    }

    @Test fun meshBackVisibleWhenCurled() {
        val cols = 8; val rows = 4
        val v = FloatArray((cols + 1) * (rows + 1) * 2); val l = FloatArray((cols + 1) * (rows + 1))
        val f = CurlMath.fold(w, h, 0f, h, CurlMath.radiusFor(0.5f, w, 0.5f), h)
        assertNotNull(f)
        assertTrue(CurlMath.buildMesh(w, h, cols, rows, f, CurlMath.Side.BACK, false, 0f, 1f, 0f, 1f, v, l))
        assertTrue(l.all { it in 0f..1f })
    }

    @Test fun lightingDarkensTowardsTheCrest() {
        assertEquals(1f, CurlMath.light(0f, false, 1f), eps)
        assertTrue(CurlMath.light((PI / 2).toFloat(), false, 1f) < 0.6f)
        assertEquals(1f, CurlMath.light(PI.toFloat(), true, 1f), eps)
        assertEquals(1f, CurlMath.light(1f, false, 0f), eps)
    }
}
