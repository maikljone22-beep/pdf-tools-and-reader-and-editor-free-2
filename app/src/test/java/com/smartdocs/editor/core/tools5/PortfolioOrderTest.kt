package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Test

/** FINAL_CHECK_2 Low: manual portfolio order. */
class PortfolioOrderTest {
    @Test fun reorderMovesAndClamps() {
        val k = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), PortfolioEdit.reorder(k, 1, 0))
        assertEquals(listOf("a", "c", "b"), PortfolioEdit.reorder(k, 1, 2))
        assertEquals(listOf("b", "c", "a"), PortfolioEdit.reorder(k, 0, 9))
        assertEquals(k, PortfolioEdit.reorder(k, 5, 0))
    }
}
