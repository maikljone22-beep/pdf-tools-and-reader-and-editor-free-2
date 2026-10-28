package com.smartdocs.editor.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** tab-view-core V-03: top-bar actions that do not fit go into the always-visible ⋮ menu. */
class TopBarOverflowTest {
    private val dirtyPhone = listOf("undo" to 80, "save" to 48, "search" to 48, "v5:tellme" to 48, "tabs" to 48,
        "v5:pin:measure" to 48, "v5:pin:comments" to 48, "v5:back" to 48)

    @Test fun phone360KeepsTheMostImportant() {
        val s = TopBarOverflow.partition(TopBarOverflow.budget(360), dirtyPhone)
        assertEquals(listOf("undo", "save"), s.shown)
        assertEquals(dirtyPhone.size, s.shown.size + s.overflow.size)
        assertTrue("search" in s.overflow)
    }

    @Test fun wideScreenShowsEverything() {
        val s = TopBarOverflow.partition(TopBarOverflow.budget(1200), dirtyPhone)
        assertEquals(dirtyPhone.map { it.first }, s.shown); assertTrue(s.overflow.isEmpty())
    }

    @Test fun orderNeverJumps() {
        // "search" (48) does not fit after "save" -> nothing after it is shown, although a later item would fit
        val s = TopBarOverflow.partition(100, listOf("a" to 50, "b" to 48, "c" to 10))
        assertEquals(listOf("a", "b"), s.shown)
        val t = TopBarOverflow.partition(90, listOf("a" to 50, "b" to 48, "c" to 10))
        assertEquals(listOf("a"), t.shown); assertEquals(listOf("b", "c"), t.overflow)
    }

    @Test fun tinyBudget() {
        assertEquals(0, TopBarOverflow.budget(100))
        val s = TopBarOverflow.partition(0, dirtyPhone)
        assertTrue(s.shown.isEmpty()); assertEquals(dirtyPhone.size, s.overflow.size)
    }
}
