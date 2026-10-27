package com.smartdocs.editor.ui.reader

import com.smartdocs.editor.ui.reader.TapZones.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit RD-01 (open errors), RD-05 (tap zones), AN-06 (rotated / offset pages), AN-13 (eraser on saved ink). */
class ReaderStage5Test {

    // ---- AN-06 ----

    private fun close(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.01f

    /** A 600×800 (user space) crop box at (36, 50), shown with each /Rotate; PDFium reports the rotated size. */
    @Test fun userSpaceMapsOntoTheRenderedPageForEveryRotation() {
        val cw = 600f; val ch = 800f; val ox = 36f; val oy = 50f
        // expected display position (y down, points) of the user-space crop corners for each /Rotate
        data class Case(val rot: Int, val bottomLeft: Pair<Float, Float>, val topLeft: Pair<Float, Float>, val bottomRight: Pair<Float, Float>)
        val cases = listOf(
            Case(0, 0f to ch, 0f to 0f, cw to ch),
            Case(90, 0f to 0f, ch to 0f, 0f to cw),       // clockwise: the user's top edge becomes the right edge
            Case(180, cw to 0f, cw to ch, 0f to 0f),
            Case(270, ch to cw, 0f to cw, ch to 0f),
        )
        for (c in cases) {
            val (pw, ph) = if (c.rot % 180 == 0) cw to ch else ch to cw
            val g = PageGeometry(pw, ph, scale = 2f, rotation = 0, pageRotate = c.rot, cropX = ox, cropY = oy)
            for ((user, disp) in listOf((ox to oy) to c.bottomLeft, (ox to oy + ch) to c.topLeft, (ox + cw to oy) to c.bottomRight)) {
                val s = g.pdfToScreen(user.first, user.second)
                assertTrue("rot ${c.rot} $user -> $s", close(s.x, disp.first * 2f) && close(s.y, disp.second * 2f))
                val (x, y) = g.screenToPdf(s.x, s.y)
                assertTrue("rot ${c.rot} round trip", close(x, user.first) && close(y, user.second))
            }
        }
    }

    @Test fun plainPagesKeepTheOldMapping() {
        val g = PageGeometry(595f, 842f, 1.5f)
        val s = g.pdfToScreen(100f, 700f)
        assertTrue(close(s.x, 150f) && close(s.y, (842f - 700f) * 1.5f))
    }

    @Test fun rotateValuesAreNormalised() {
        assertEquals(270, PageUserFrame.normRotate(-90)); assertEquals(90, PageUserFrame.normRotate(450))
        assertEquals(0, PageUserFrame.normRotate(360)); assertEquals(180, PageUserFrame.normRotate(180))
    }

    // ---- RD-05 ----

    @Test fun tapZonesAreScreenEdges() {
        assertEquals(Zone.PREV, TapZones.zone(50f, 1000f, rtl = false, zoomed = false))
        assertEquals(Zone.NEXT, TapZones.zone(950f, 1000f, rtl = false, zoomed = false))
        assertEquals(Zone.NEXT, TapZones.zone(50f, 1000f, rtl = true, zoomed = false))
        // facing mode: the spine is the middle of the screen, a tap there shows the toolbar
        assertEquals(Zone.CENTER, TapZones.zone(500f, 1000f, rtl = false, zoomed = false))
        // zoomed in: edge taps belong to the content
        assertEquals(Zone.CENTER, TapZones.zone(20f, 1000f, rtl = false, zoomed = true))
        assertEquals(Zone.CENTER, TapZones.zone(20f, 0f, rtl = false, zoomed = false))
    }

    // ---- RD-01 ----

    @Test fun openErrorsAreClassified() {
        assertEquals(OpenError.PERMISSION, OpenError.of(SecurityException("denied"), true))
        assertEquals(OpenError.MISSING, OpenError.of(java.io.FileNotFoundException("x"), null))
        assertEquals(OpenError.MISSING, OpenError.of(IllegalStateException("cannot open"), false))
        assertEquals(OpenError.DAMAGED, OpenError.of(java.io.IOException("File not in PDF format or corrupted."), true))
        assertEquals(OpenError.DAMAGED, OpenError.of(RuntimeException("wrap", java.io.IOException("Missing root object specification in trailer")), true))
        assertEquals(OpenError.OTHER, OpenError.of(IllegalArgumentException("bad"), true))
    }

    // ---- AN-13 ----

    @Test fun eraserHitsSavedStrokes() {
        val stroke = listOf(listOf(100f to 100f, 110f to 100f, 120f to 100f))
        assertTrue(EraseLogic.touches(stroke, 2f, listOf(0f to 0f, 111f to 104f), radius = 5f))
        assertFalse(EraseLogic.touches(stroke, 2f, listOf(111f to 120f), radius = 5f))
        assertTrue(EraseLogic.nearRect(100f, 98f, 120f, 102f, listOf(122f to 101f), pad = 5f))
        assertFalse(EraseLogic.nearRect(100f, 98f, 120f, 102f, listOf(200f to 101f), pad = 5f))
    }
}
