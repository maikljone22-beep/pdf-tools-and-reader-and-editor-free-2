package com.smartdocs.editor.core.edit

import com.smartdocs.editor.core.edit5.LayerPageFilter
import com.smartdocs.editor.core.nve.BoxDrag
import com.smartdocs.editor.core.nve.shapes.Gradient
import com.smartdocs.editor.core.nve.shapes.GradientHandles
import com.smartdocs.editor.ui.nve.imgedit.HealSynth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** edit-text-tools: E10 frames / nested form ids, article box drag, gradient handles, spot healing, layers filter. */
class EditTextToolsTest {

    // ---- E10 ----

    @Test fun mirroredAndSkewedFrames() {
        assertEquals(listOf(-1f, 0f, 0f, 1f), GlyphFrames.of(floatArrayOf(-12f, 0f, 0f, 12f, 300f, 400f)))
        assertEquals(listOf(1f, 0f, 0f, -1f), GlyphFrames.of(floatArrayOf(10f, 0f, 0f, -10f, 0f, 0f)))
        // a slanted baseline (shear of the x axis): rows normalised to length 1
        val f = GlyphFrames.of(floatArrayOf(10f, 3f, 0f, 12f, 0f, 0f))!!
        assertEquals(0.96f, f[0], 1e-4f); assertEquals(0.28f, f[1], 1e-4f); assertEquals(0f, f[2], 1e-4f); assertEquals(1f, f[3], 1e-4f)
        // the same row at another size shares the frame
        assertEquals(f, GlyphFrames.of(floatArrayOf(20f, 6f, 0f, 24f, 50f, 9f)))
        // degenerate (both axes parallel) / zero size
        assertNull(GlyphFrames.of(floatArrayOf(5f, 0f, 5f, 0f, 0f, 0f)))
        assertNull(GlyphFrames.of(floatArrayOf(0f, 0f, 0f, 12f, 0f, 0f)))
    }

    @Test fun nestedFormIdsDoNotCollide() {
        val first = FormOps.key(2000, 999_999)
        assertTrue(FormOps.isForm(first)); assertFalse(FormOps.isNested(first))
        assertEquals(2000, FormOps.doOp(first)); assertEquals(999_999, FormOps.formOp(first))
        val nested = FormOps.NESTED_BASE - 12
        assertTrue(FormOps.isForm(nested)); assertTrue(FormOps.isNested(nested))
        assertFalse(FormOps.isForm(5))
    }

    // ---- §22 article boxes ----

    @Test fun boxDragMovesAndResizes() {
        val box = floatArrayOf(10f, 20f, 110f, 220f)
        assertArrayEquals(floatArrayOf(15f, 10f, 115f, 210f), BoxDrag.apply(box, BoxDrag.Handle.MOVE, 5f, -10f, 12f), 1e-4f)
        assertArrayEquals(floatArrayOf(10f, 20f, 130f, 250f), BoxDrag.apply(box, BoxDrag.Handle.BOTTOM_RIGHT, 20f, 30f, 12f), 1e-4f)
        // never smaller than the minimum
        assertArrayEquals(floatArrayOf(98f, 20f, 110f, 220f), BoxDrag.apply(box, BoxDrag.Handle.TOP_LEFT, 500f, -5f, 12f).let { floatArrayOf(it[0], 20f, it[2], it[3]) }, 1e-4f)
        assertEquals(BoxDrag.Handle.TOP_RIGHT, BoxDrag.hit(box, 108f, 22f, 10f))
        assertEquals(BoxDrag.Handle.MOVE, BoxDrag.hit(box, 60f, 120f, 10f))
        assertNull(BoxDrag.hit(box, 300f, 300f, 10f))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), BoxDrag.normalized(floatArrayOf(3f, 4f, 1f, 2f)), 0f)
    }

    // ---- §14d gradient reference line ----

    @Test fun gradientHandlesFollowTheDrag() {
        val bb = floatArrayOf(100f, 100f, 300f, 200f)      // l, b, r, t
        val g = Gradient()
        val (a, e) = GradientHandles.points(g, bb)
        assertEquals(100f to 150f, a); assertEquals(300f to 150f, e)
        assertEquals(1, GradientHandles.hit(g, bb, 298f, 151f, 6f))
        assertEquals(-1, GradientHandles.hit(g, bb, 200f, 150f, 6f))
        val d = GradientHandles.drag(g, bb, 1, 200f, 200f)
        assertEquals(0.5f, d.x1, 1e-4f); assertEquals(1f, d.y1, 1e-4f)
        // free: past the shape, but bounded
        val far = GradientHandles.drag(g, bb, 0, -10_000f, 150f)
        assertEquals(-1f, far.x0, 1e-4f)
        val rad = GradientHandles.drag(Gradient(radial = true, x0 = 0.5f, y0 = 0.5f), bb, 1, 300f, 150f)
        assertEquals(0.5f, rad.r, 1e-4f)
    }

    // ---- §14c spot healing ----

    @Test fun healCopiesTheSurroundingTexture() {
        val w = 80; val h = 80
        val black = 0xFF000000.toInt(); val white = 0xFFFFFFFF.toInt()
        // vertical stripes, 2 px wide: a ring average would give grey
        val px = IntArray(w * h) { i -> if ((i % w) / 2 % 2 == 0) black else white }
        val cx = 40; val cy = 40; val r = 5
        for (y in cy - r..cy + r) for (x in cx - r..cx + r)
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = 0xFFFF0000.toInt()    // the round blemish
        assertTrue(HealSynth.heal(px, w, h, cx.toFloat(), cy.toFloat(), r))
        for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) assertEquals("$x,$y", if (x / 2 % 2 == 0) black else white, px[y * w + x])
    }

    @Test fun healNeedsRoomForAPatch() {
        val px = IntArray(10 * 10) { 0xFF808080.toInt() }
        assertFalse(HealSynth.heal(px, 10, 10, 5f, 5f, 3))
        assertTrue(HealSynth.ringOffsets(4).isNotEmpty())
    }

    // ---- pdf39 §22 #270 layers filter ----

    @Test fun layersFilteredByPages() {
        assertTrue(LayerPageFilter.keep(emptyList(), null))
        assertTrue(LayerPageFilter.keep(listOf(0, 4), setOf(4, 5)))
        assertFalse(LayerPageFilter.keep(listOf(0, 4), setOf(2)))
        assertFalse(LayerPageFilter.keep(emptyList(), setOf(0)))
    }
}
