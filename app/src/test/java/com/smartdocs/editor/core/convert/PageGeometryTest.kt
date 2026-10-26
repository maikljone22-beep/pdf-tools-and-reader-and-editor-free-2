package com.smartdocs.editor.core.convert

import com.smartdocs.editor.core.sign.SealGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The page frame of the converter (rotation, sideways text, crop box) and the paging-seal geometry. */
class PageGeometryTest {

    private fun raw(x: Float, y: Float, dx: Float, dy: Float, t: String) = RawGlyph(x, y, dx, dy, 5f, 7f, t, 10f)

    @Test fun uprightPageIsIdentityWithYDown() {
        val f = PageFrame.of(595f, 842f, 0, listOf(raw(100f, 700f, 1f, 0f, "a")))
        assertEquals(595f, f.width, 0.01f); assertEquals(842f, f.height, 0.01f)
        val p = f.map(100f, 700f)
        assertEquals(100f, p[0], 0.01f); assertEquals(142f, p[1], 0.01f)
    }

    @Test fun rotate90TextReadsUpright() {
        // content drawn with rotate(90) translate(W,0): text direction (0,1) in user space; the page has /Rotate 90
        val f = PageFrame.of(595f, 842f, 90, listOf(raw(555f, 40f, 0f, 1f, "R"), raw(555f, 50f, 0f, 1f, "o")))
        assertEquals(842f, f.width, 0.01f); assertEquals(595f, f.height, 0.01f)
        assertEquals(0, f.turn)
        val g = f.glyphs(listOf(raw(555f, 40f, 0f, 1f, "R"), raw(555f, 50f, 0f, 1f, "o")))
        assertEquals(2, g.size)
        assertTrue(g[1].x > g[0].x)                          // reads left → right
        assertEquals(g[0].baseline, g[1].baseline, 0.01f)
        assertEquals(40f, g[0].x, 0.01f); assertEquals(555f, g[0].baseline, 0.01f)
    }

    @Test fun sidewaysContentIsTurned() {
        // portrait page, no /Rotate, landscape text running upwards
        val text = (0 until 10).map { raw(60f, 50f + it * 6f, 0f, 1f, "x") }
        val f = PageFrame.of(595f, 842f, 0, text)
        assertEquals(3, f.turn)
        assertEquals(842f, f.width, 0.01f); assertEquals(595f, f.height, 0.01f)
        val g = f.glyphs(text)
        assertTrue(g.zipWithNext().all { (a, b) -> b.x > a.x && abs(a.baseline - b.baseline) < 0.01f })
    }

    @Test fun minorityTextBecomesOneRun() {
        val main = (0 until 20).map { raw(50f + it * 6f, 700f, 1f, 0f, "m") }
        val label = (0 until 5).map { raw(300f, 300f + it * 6f, 0f, 1f, "L") }  // rotated label
        val f = PageFrame.of(595f, 842f, 0, main + label)
        val g = f.glyphs(main + label)
        val ls = g.filter { it.text == "L" }
        assertEquals(5, ls.size)
        assertTrue(ls.zipWithNext().all { (a, b) -> b.x > a.x && abs(a.baseline - b.baseline) < 0.01f })
    }

    @Test fun cropBoxDropsOutsideText() {
        val f = PageFrame.of(400f, 500f, 0, listOf(raw(20f, 450f, 1f, 0f, "in")))
        val g = f.glyphs(listOf(raw(20f, 450f, 1f, 0f, "in"), raw(-90f, -100f, 1f, 0f, "out")))
        assertEquals(listOf("in"), g.map { it.text })
    }

    @Test fun imageOrientation() {
        assertEquals(0 to false, PageFrame.imageOrientation(100f, 0f, 0f, -50f))
        assertEquals(90 to false, PageFrame.imageOrientation(0f, 100f, 50f, 0f))
        assertEquals(180 to false, PageFrame.imageOrientation(-100f, 0f, 0f, 50f))
        assertEquals(270 to false, PageFrame.imageOrientation(0f, -100f, -50f, 0f))
        assertEquals(0 to true, PageFrame.imageOrientation(-100f, 0f, 0f, -50f))
    }

    /** The seal matrix must put the image upright at the displayed right edge, for every /Rotate. */
    @Test fun sealGeometryMatchesDisplayFrame() {
        for (rot in listOf(0, 90, 180, 270)) {
            val llx = 10f; val lly = 20f; val cw = 595f; val ch = 842f
            val geom = SealGeometry(llx, lly, cw, ch, rot)
            val frame = PageFrame.of(cw, ch, rot, emptyList())
            assertEquals(frame.width, geom.displayWidth, 0.01f)
            val w = 20f; val h = 110f
            val x = geom.displayWidth - w; val y = geom.displayHeight * 0.5f - h / 2
            val m = geom.uprightImageMatrix(x, y, w, h)
            fun unit(u: Float, v: Float): FloatArray {
                val ux = m[0] * u + m[2] * v + m[4] - llx; val uy = m[1] * u + m[3] * v + m[5] - lly
                return frame.map(ux, uy)    // display, y down
            }
            val o = unit(0f, 0f); val ex = unit(1f, 0f); val ey = unit(0f, 1f)
            // bottom-left of the image at (x, displayHeight - y), x axis to the right, up axis upwards
            assertEquals("rot $rot x", x, o[0], 0.05f)
            assertEquals("rot $rot y", frame.height - y, o[1], 0.05f)
            assertEquals("rot $rot right", w, ex[0] - o[0], 0.05f); assertEquals(0f, ex[1] - o[1], 0.05f)
            assertEquals("rot $rot up", -h, ey[1] - o[1], 0.05f); assertEquals(0f, ey[0] - o[0], 0.05f)
            assertFalse(o[0] + w > frame.width + 0.1f)
        }
    }
}
