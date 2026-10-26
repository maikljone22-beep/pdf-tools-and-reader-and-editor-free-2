package com.smartdocs.editor.core.search

import org.junit.Assert.assertEquals
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
}
