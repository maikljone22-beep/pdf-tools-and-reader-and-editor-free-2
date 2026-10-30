package com.smartdocs.editor.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FINAL_CHECK_2 Low: table rules found in a scanned page for vector redrawing. */
class TableRulesTest {
    private val W = 400; private val H = 300
    private fun page() = IntArray(W * H) { 0xFFFFFF }
    private fun rect(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0..y1) for (x in x0..x1) px[y * W + x] = 0x101010 }

    @Test fun findsThinRulesNotTextOrBoxes() {
        val px = page()
        rect(px, 20, 50, 380, 51)            // horizontal rule, 2 px
        rect(px, 100, 60, 100, 280)          // vertical rule, 1 px
        rect(px, 30, 100, 36, 110)           // a "letter": too short
        rect(px, 200, 150, 300, 200)         // a solid box: too thick
        px[50 * W + 200] = 0xFFFFFF          // a 1-px gap in the rule
        val r = TableRules.detect(px, W, H)
        val h = r.filter { it.horizontal }; val v = r.filter { !it.horizontal }
        assertEquals(1, h.size); assertEquals(20, h[0].x0); assertEquals(380, h[0].x1); assertEquals(2, h[0].thick)
        assertEquals(1, v.size); assertEquals(100, v[0].x0); assertEquals(60, v[0].y0); assertEquals(280, v[0].y1)
        assertTrue(r.all { (it.color and 0xFF) < 0x40 })
    }

    @Test fun blankPageHasNoRules() { assertTrue(TableRules.detect(page(), W, H).isEmpty()) }
}
