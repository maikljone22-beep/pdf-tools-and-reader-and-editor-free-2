package com.smartdocs.editor.core.forms5

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldRotationTest {

    @Test fun textAngleCombinesPageMkAndView() {
        assertEquals(0, FieldRotation.textAngle(0, 0, 0))
        assertEquals(90, FieldRotation.textAngle(90, 0, 0))        // page turned, plain widget: text turns with the page
        assertEquals(0, FieldRotation.textAngle(90, 90, 0))        // Acrobat's /MK /R compensates the page rotation
        assertEquals(270, FieldRotation.textAngle(0, 90, 0))       // /MK /R 90 on an upright page reads bottom-to-top
        assertEquals(180, FieldRotation.textAngle(270, 90, 0))
        assertEquals(90, FieldRotation.textAngle(0, 0, 90))        // reader view rotation
        assertEquals(0, FieldRotation.textAngle(180, 0, 180))
        assertEquals(270, FieldRotation.textAngle(-90, 0, 0))
    }

    @Test fun frameSwapsSidesForQuarterTurns() {
        assertArrayEquals(floatArrayOf(10f, 20f, 110f, 40f), FieldRotation.frame(10f, 20f, 110f, 40f, 0), 1e-4f)
        assertArrayEquals(floatArrayOf(10f, 20f, 110f, 40f), FieldRotation.frame(10f, 20f, 110f, 40f, 180), 1e-4f)
        // a tall 20 x 100 screen rect holds a 100 x 20 upright frame around the same centre (60, 70)
        assertArrayEquals(floatArrayOf(10f, 60f, 110f, 80f), FieldRotation.frame(50f, 20f, 70f, 120f, 90), 1e-4f)
        assertArrayEquals(floatArrayOf(10f, 60f, 110f, 80f), FieldRotation.frame(50f, 20f, 70f, 120f, 270), 1e-4f)
    }

    @Test fun rotatedFrameCoversTheWidgetRect() {
        val rect = floatArrayOf(50f, 20f, 70f, 120f)     // l, t, r, b
        for (a in listOf(90, 270)) {
            val f = FieldRotation.frame(rect[0], rect[1], rect[2], rect[3], a)
            val cx = (f[0] + f[2]) / 2; val cy = (f[1] + f[3]) / 2
            val corners = listOf(f[0] to f[1], f[2] to f[1], f[2] to f[3], f[0] to f[3]).map { (x, y) -> FieldRotation.frameToScreen(x, y, cx, cy, a) }
            assertEquals(rect[0], corners.minOf { it.first }, 1e-3f); assertEquals(rect[2], corners.maxOf { it.first }, 1e-3f)
            assertEquals(rect[1], corners.minOf { it.second }, 1e-3f); assertEquals(rect[3], corners.maxOf { it.second }, 1e-3f)
        }
    }

    @Test fun textStartCornerFollowsTheAngle() {
        // the frame's top-left (where left-to-right text starts) after the turn
        fun start(a: Int): Pair<Float, Float> {
            val f = FieldRotation.frame(0f, 0f, 20f, 100f, a)
            return FieldRotation.frameToScreen(f[0], f[1], 10f, 50f, a)
        }
        assertEquals(20f to 0f, start(90))       // reads downwards from the top-right corner
        assertEquals(0f to 100f, start(270))     // reads upwards from the bottom-left corner
        val f = FieldRotation.frame(0f, 0f, 100f, 20f, 180)
        assertEquals(100f to 20f, FieldRotation.frameToScreen(f[0], f[1], 50f, 10f, 180))
    }

    @Test fun appearanceMatrix() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), FieldRotation.matrixFor(0), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, -1f, 0f), FieldRotation.matrixFor(90), 0f)
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f, -1f), FieldRotation.matrixFor(180), 0f)
        assertArrayEquals(floatArrayOf(0f, -1f, 1f, 0f), FieldRotation.matrixFor(270), 0f)
        assertEquals(90, FieldRotation.angleOf(0f, 1f)); assertEquals(270, FieldRotation.angleOf(0f, -2f)); assertEquals(0, FieldRotation.angleOf(1f, 0f))
        assertNull(FieldRotation.angleOf(0.7f, 0.7f))
    }

    @Test fun appearanceMismatchDetection() {
        // /MK /R 90 on a 200 x 20 widget: bbox 20 x 200 with a 90 degree matrix is right
        assertFalse(FieldRotation.appearanceMismatch(90, 200f, 20f, 20f, 200f, 0f, 1f))
        // an unrotated stream (identity matrix, 200 x 20 bbox) must be regenerated
        assertTrue(FieldRotation.appearanceMismatch(90, 200f, 20f, 200f, 20f, 1f, 0f))
        // matrix right but bbox not swapped
        assertTrue(FieldRotation.appearanceMismatch(90, 200f, 20f, 200f, 20f, 0f, 1f))
        assertFalse(FieldRotation.appearanceMismatch(0, 200f, 20f, 200f, 20f, 1f, 0f))
        assertFalse(FieldRotation.appearanceMismatch(180, 200f, 20f, 200.5f, 20f, -1f, 0f))
    }
}
