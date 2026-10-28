package com.smartdocs.editor.core.redact

import com.smartdocs.editor.core.pdf.SearchHit
import com.smartdocs.editor.core.search.Found
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SearchRemoveTest {

    @Test fun parsesLinesCommasAndArabicSeparators() {
        assertEquals(listOf("سري", "confidential", "Project X", "رقم الهوية"),
            SearchRemove.parseWords("﻿سري\r\nconfidential, Project X\n\n  رقم الهوية ؛ سري\n"))
        assertEquals(emptyList<String>(), SearchRemove.parseWords(" \n ,; "))
    }

    @Test fun mergeAppendsWithoutDuplicates() {
        assertEquals("a\nb\nc", SearchRemove.merge("a\nb", "b\r\nc\n"))
        assertEquals("x", SearchRemove.merge("", "x"))
    }

    @Test fun groupsHitsByPageInOrder() {
        fun f(p: Int, i: Int) = Found(SearchHit(p, i, 3, emptyList(), "s"), "w")
        val g = SearchRemove.byPage(listOf(f(2, 0), f(0, 5), f(2, 9), f(0, 1)))
        assertEquals(listOf(0, 2), g.map { it.first })
        assertEquals(2, g[1].second.size)
        assertEquals("2:9:3", SearchRemove.key(f(2, 9)))
    }

    @Test fun hitsWithoutRectsMakeNoMarks() {
        val found = listOf(Found(SearchHit(0, 0, 3, emptyList(), "s"), "w"))
        assertEquals(0, SearchRemove.marks(found, setOf("0:0:3")).size)
    }

    @Test fun removeStyleIsWhiteWithoutOverlay() {
        val m = RedactMark("m", 1, emptyList(), RedactStyle(fill = 0x000000, overlay = "REDACTED"), wholePage = true)
        val s = RedactApplier.removeStyle(m)
        assertEquals(0xFFFFFF, s.style.fill)
        assertNull(s.style.outline)
        assertEquals("", s.style.shownOverlay())
        assertFalse(s.wholePage)
    }
}
