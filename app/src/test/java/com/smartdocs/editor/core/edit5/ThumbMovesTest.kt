package com.smartdocs.editor.core.edit5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** fix/thumbs-panel: drag-to-reorder arithmetic and the size steps of the reader's thumbnails panel. */
class ThumbMovesTest {
    @Test fun singlePageMatchesOrganizeDrag() {
        // Organize: removeAt(from), add(to, moved)
        assertEquals(listOf(0, 2, 3, 1), ThumbMoves.moveOrder(4, listOf(1), 3, forward = true))
        assertEquals(listOf(0, 3, 1, 2), ThumbMoves.moveOrder(4, listOf(3), 1, forward = false))
        assertEquals(listOf(1, 0, 2, 3), ThumbMoves.moveOrder(4, listOf(0), 1, forward = true))
    }

    @Test fun selectionMovesAsABlockInPageOrder() {
        assertEquals(listOf(1, 2, 4, 0, 3, 5), ThumbMoves.moveOrder(6, listOf(3, 0), 4, forward = true))
        assertEquals(listOf(4, 5, 0, 1, 2, 3), ThumbMoves.moveOrder(6, listOf(5, 4), 0, forward = false))
    }

    @Test fun noChangeGivesNull() {
        assertNull(ThumbMoves.moveOrder(4, listOf(1, 2), 2, forward = true))      // target inside the block
        assertNull(ThumbMoves.moveOrder(4, listOf(1), 2, forward = false))        // lands where it was
        assertNull(ThumbMoves.moveOrder(4, emptyList(), 2, forward = true))
        assertNull(ThumbMoves.moveOrder(4, listOf(1), 9, forward = true))
    }

    @Test fun movedPagesStaySelected() {
        val order = listOf(1, 2, 4, 0, 3, 5)
        assertEquals(setOf(3, 4), ThumbMoves.indexesAfter(order, listOf(0, 3)))
    }

    @Test fun sizeSteps() {
        assertEquals(120, ThumbMoves.step(96, 1))
        assertEquals(80, ThumbMoves.step(96, -1))
        assertEquals(64, ThumbMoves.step(64, -1))
        assertEquals(240, ThumbMoves.step(240, 1))
        assertEquals(120, ThumbMoves.step(100, 1))       // off-step values snap first
        assertEquals(96, ThumbMoves.normalize(100))
        assertEquals(240, ThumbMoves.normalize(5000))
    }
}
