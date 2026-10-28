package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-convert: Combine thumbnail page picking (row 111). */
class CombinePickTest {
    @Test fun pickedPagesBecomeACompactRange() {
        assertEquals("1-3, 5, 8-9", CombinePick.rangeOf(listOf(0, 1, 2, 4, 7, 8), 10))
        assertEquals("", CombinePick.rangeOf((0 until 10).toList(), 10))      // every page = blank (all)
        assertEquals("", CombinePick.rangeOf(emptyList(), 10))
        assertEquals("2", CombinePick.rangeOf(listOf(1, 1, 42), 10))           // duplicates and pages past the end dropped
        assertEquals(setOf(0, 1, 2, 4), CombinePick.pagesOf("1-3, 5", 10))
        assertEquals((0 until 4).toSet(), CombinePick.pagesOf("", 4))
        assertEquals(CombinePick.pagesOf("2-4, 7", 9), CombinePick.pagesOf(CombinePick.rangeOf(CombinePick.pagesOf("2-4, 7", 9), 9), 9))
    }
}
