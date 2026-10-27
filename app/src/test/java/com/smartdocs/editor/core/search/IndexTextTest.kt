package com.smartdocs.editor.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexTextTest {
    @Test fun bodyFoldsAndStems() {
        val b = IndexText.body("قرأ الطلابُ الكتابَ، في المدرسة!")
        assertTrue(b, b.split(' ').containsAll(listOf("قرا", "الطلاب", "طلاب", "الكتاب", "كتاب", "المدرسه")))
        assertTrue(b.none { it == '،' || it == '!' })
    }

    @Test fun queryUsesStemPrefixes() {
        assertEquals("كتاب* جديد*", IndexText.ftsQuery("الكتاب الجديد", SearchMode.PHRASE))
        assertEquals("كتاب* OR جديد*", IndexText.ftsQuery("الكتاب الجديد", SearchMode.ANY_WORDS))
        assertEquals("report*", IndexText.ftsQuery("Reports", SearchMode.ALL_WORDS))
        assertNull(IndexText.ftsQuery("a.*b", SearchMode.REGEX))
        assertNull(IndexText.ftsQuery("  ، ! ", SearchMode.PHRASE))
    }

    @Test fun ftsOperatorsAreNeutralised() {
        // FTS operators are upper case; the folded query is lower case and has no quotes or parentheses
        assertEquals("cat* or* dog*", IndexText.ftsQuery("\"cats\" OR (dogs)", SearchMode.PHRASE))
    }

    /** FTS4 prefix semantics for the expressions [IndexText.ftsQueries] produces (AND of terms, or OR). */
    private fun ftsMatches(body: String, expr: String): Boolean {
        val words = body.split(' ')
        fun term(t: String) = if (t.endsWith("*")) words.any { it.startsWith(t.dropLast(1)) } else t in words
        return if (" OR " in expr) expr.split(" OR ").any(::term) else expr.split(' ').all(::term)
    }

    /** Audit AN-15: a part stored in visual (reversed) order is a candidate for a normal logical-order query. */
    @Test fun reversedArabicIsFoundByANormalQuery() {
        val logical = "درس الكيمياء مع محمد في الكتابه"
        val visual = logical.reversed()
        val body = IndexText.body(visual)
        for (q in listOf("محمد", "الكيمياء", "الكتابه", "محمد الكيمياء")) {
            assertFalse("forward-only query misses '$q' (the bug)", ftsMatches(body, IndexText.ftsQuery(q, SearchMode.PHRASE)!!))
            assertTrue("'$q' must reach TextMatcher", IndexText.ftsQueries(q, SearchMode.PHRASE).any { ftsMatches(body, it) })
            assertTrue(IndexText.ftsQueries(q, SearchMode.ANY_WORDS).any { ftsMatches(body, it) })
            assertTrue("TextMatcher then verifies '$q'", TextMatcher.find(visual, q, SearchOptions()).isNotEmpty() || q.contains(' '))
        }
        // a logical-order part still matches, and Latin-only queries are unchanged
        assertTrue(IndexText.ftsQueries("محمد", SearchMode.PHRASE).any { ftsMatches(IndexText.body(logical), it) })
        assertEquals(listOf("report*"), IndexText.ftsQueries("Reports", SearchMode.PHRASE))
        assertEquals(emptyList<String>(), IndexText.ftsQueries("a.*b", SearchMode.REGEX))
    }
}
