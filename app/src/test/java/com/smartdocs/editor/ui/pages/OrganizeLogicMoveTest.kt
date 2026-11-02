package com.smartdocs.editor.ui.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrganizeLogicMoveTest {
    @Test fun moveOnePage() {
        assertEquals(listOf(4, 0, 1, 2, 3), OrganizeLogic.moveTo(5, listOf(4), 1))      // page 5 becomes page 1
        assertEquals(listOf(1, 2, 0, 3, 4), OrganizeLogic.moveTo(5, listOf(0), 3))      // page 1 becomes page 3
        assertEquals(listOf(1, 2, 3, 4, 0), OrganizeLogic.moveTo(5, listOf(0), 99))     // past the end: last
        assertNull(OrganizeLogic.moveTo(5, listOf(2), 3))                                 // already there
        assertNull(OrganizeLogic.moveTo(5, listOf(2), 0))
    }

    @Test fun moveASelectionTogether() {
        // pages 2 and 4 (indexes 1, 3) become pages 1 and 2, in their order
        assertEquals(listOf(1, 3, 0, 2, 4), OrganizeLogic.moveTo(5, setOf(3, 1), 1))
        assertEquals(listOf(0, 2, 4, 1, 3), OrganizeLogic.moveTo(5, listOf(1, 3), 9))
    }
}
