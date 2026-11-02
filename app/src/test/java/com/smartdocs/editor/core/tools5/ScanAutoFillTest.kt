package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAutoFillTest {

    private fun grid(f: (Int) -> Int) = IntArray(16 * 12) { f(it) }

    @Test fun brightUniformPageCounts() {
        // white paper with text: cells average high, a few slightly darker
        assertTrue(ScanAutoFill.brightPage(grid { if (it % 7 == 0) 170 else 215 }))
    }

    @Test fun darkOrMixedSceneDoesNot() {
        assertFalse(ScanAutoFill.brightPage(grid { 60 }))
        // page on a dark desk: half the cells dark
        assertFalse(ScanAutoFill.brightPage(grid { if (it % 16 < 8) 220 else 50 }))
        assertFalse(ScanAutoFill.brightPage(IntArray(0)))
    }

    @Test fun largeQuadFillsFrame() {
        assertTrue(ScanAutoFill.fillsFrame(0.9f, grid { 40 }))
        assertTrue(ScanAutoFill.fillsFrame(0.99f, grid { 40 }))
        assertFalse(ScanAutoFill.fillsFrame(0.5f, grid { 220 }))
    }

    @Test fun noQuadUsesBrightness() {
        assertTrue(ScanAutoFill.fillsFrame(null, grid { 210 }))
        assertFalse(ScanAutoFill.fillsFrame(null, grid { 80 }))
    }

    @Test fun steadinessCountsOnlyStillFillingFrames() {
        var st = 0
        repeat(10) { st = ScanAutoFill.steadyStep(st, fills = true, frameDiff = 1) }
        assertEquals(10, st)
        assertEquals(0, ScanAutoFill.steadyStep(st, fills = true, frameDiff = 20))
        assertEquals(0, ScanAutoFill.steadyStep(st, fills = false, frameDiff = 0))
    }

    @Test fun failedPageList() {
        assertEquals("3، 7", ScanBuilder.pageList(listOf(3, 7), "، "))
        assertEquals("1, 2, 3…", ScanBuilder.pageList(listOf(1, 2, 3, 4), ", ", max = 3))
        assertEquals("", ScanBuilder.pageList(emptyList(), ", "))
    }
}
