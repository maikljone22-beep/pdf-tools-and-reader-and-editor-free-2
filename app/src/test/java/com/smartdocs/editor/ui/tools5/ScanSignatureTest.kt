package com.smartdocs.editor.ui.tools5

import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto-capture re-arms only when the page under the camera really changes. */
class ScanSignatureTest {
    private val w = 320; private val h = 240
    /** White paper with text-like dark dashes, shifted by [dx]; [layout] changes where the lines are. */
    private fun page(dx: Int, layout: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val line = (y + layout * 7) % 18 < 3 && x in 40..280 && ((x + dx) / 5) % 3 != 0
        if (line) 0xFF202020.toInt() else 0xFFF4F4F4.toInt()
    }
    @Test fun samePageMovedSlightlyIsTheSame() {
        assertTrue(sigDiff(signature(page(0, 0), w, h), signature(page(2, 0), w, h)) <= 10)
    }
    @Test fun emptyTableVsPageDiffers() {
        val table = IntArray(w * h) { 0xFF5A3A20.toInt() }
        assertTrue(sigDiff(signature(page(0, 0), w, h), signature(table, w, h)) > 10)
    }
}
