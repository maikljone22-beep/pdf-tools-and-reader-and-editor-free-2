package com.smartdocs.editor.ui.reader.view

import com.smartdocs.editor.ui.reader.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** tab-view-core V-09: Space / Ctrl+PgDn / Ctrl+PgUp step whole screens in facing and cover modes. */
class PageSpreadStepTest {
    private fun walk(mode: ViewMode, count: Int, delta: Int, start: Int, steps: Int): List<Int> {
        val sp = PageSpread(mode); var p = start; val out = mutableListOf(p)
        repeat(steps) { p = sp.step(p, delta, count) ?: p; out += p }
        return out
    }

    @Test fun facingAdvancesBySpread() = assertEquals(listOf(0, 2, 4, 6, 8, 8, 8), walk(ViewMode.FACING, 10, 1, 0, 6))
    @Test fun coverAdvancesPastTheCover() = assertEquals(listOf(0, 1, 3, 5, 7, 9, 9), walk(ViewMode.COVER, 10, 1, 0, 6))
    @Test fun singleAdvancesByPage() = assertEquals(listOf(0, 1, 2, 3), walk(ViewMode.SINGLE, 10, 1, 0, 3))
    @Test fun facingBackFromTheRightPage() = assertEquals(listOf(5, 2, 0, 0), walk(ViewMode.FACING, 10, -1, 5, 3))
    @Test fun coverBack() = assertEquals(listOf(4, 1, 0, 0), walk(ViewMode.COVER, 10, -1, 4, 3))
    @Test fun emptyDocument() = assertEquals(null, PageSpread(ViewMode.FACING).step(0, 1, 0))
}
