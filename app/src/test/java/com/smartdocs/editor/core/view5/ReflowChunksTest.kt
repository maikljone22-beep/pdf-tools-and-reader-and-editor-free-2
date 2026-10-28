package com.smartdocs.editor.core.view5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** tab-view-core V-13: the first reflow chunk is bounded, every page is built exactly once. */
class ReflowChunksTest {
    @Test fun startNearTheEndDoesNotScanEverything() {
        val c = ReflowChunks.order(495, 500)
        assertEquals(495..499, c.first())
        assertTrue(c.all { it.last - it.first + 1 <= 12 })
        assertEquals((0 until 500).toList(), c.flatMap { it.toList() }.sorted())
    }

    @Test fun startAtZero() {
        val c = ReflowChunks.order(0, 30)
        assertEquals(listOf(0..11, 12..23, 24..29), c)
    }

    @Test fun startInTheMiddle() {
        val c = ReflowChunks.order(7, 20, 12)
        assertEquals(listOf(7..18, 19..19, 0..6), c)
    }

    @Test fun edgeCases() {
        assertEquals(emptyList<IntRange>(), ReflowChunks.order(0, 0))
        assertEquals(listOf(0..0), ReflowChunks.order(5, 1))
    }
}
