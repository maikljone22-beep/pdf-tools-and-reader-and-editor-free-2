package com.smartdocs.editor.core.annot

import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-comments-forms: pencil stroke merge timeout. */
class InkMergeTest {
    private val s1 = listOf(0f to 0f, 10f to 10f)
    private val s2 = listOf(20f to 0f, 30f to 10f)
    private val a = Annot(id = 1, kind = AnnotKind.INK, page = 0, paths = listOf(s1))
    private val b = Annot(id = 2, kind = AnnotKind.INK, page = 0, paths = listOf(s2))

    @Test fun mergesWithinTimeoutAndSameStyle() {
        assertTrue(InkMerge.canMerge(a, b, 800, 1000))
        assertEquals(listOf(s1, s2), InkMerge.merge(a, b).paths)
        assertEquals(1L, InkMerge.merge(a, b).id)
        assertFalse(InkMerge.canMerge(a, b, 1200, 1000))                    // too late
        assertFalse(InkMerge.canMerge(a, b, 100, 0))                        // off
        assertFalse(InkMerge.canMerge(null, b, 100, 1000))
        assertFalse(InkMerge.canMerge(a, b.copy(page = 1), 100, 1000))
        assertFalse(InkMerge.canMerge(a, b.copy(color = 0x0000FF), 100, 1000))
        assertFalse(InkMerge.canMerge(a.copy(locked = true), b, 100, 1000))
    }

    @Test fun menuCycles() {
        assertEquals(1000L, InkMerge.next(0L)); assertEquals(0L, InkMerge.next(3000L)); assertEquals(1000L, InkMerge.next(42L))
    }
}
