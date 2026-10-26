package com.smartdocs.editor.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherModesTest {
    private fun found(text: String, q: String, o: SearchOptions) = TextMatcher.find(text, q, o).map { text.substring(it.first, it.last + 1) }

    @Test fun anyWords() {
        val o = SearchOptions(mode = SearchMode.ANY_WORDS, wholeWord = true)
        assertEquals(listOf("أحمد", "علي"), found("جاء أحمد ثم علي ثم سالم", "احمد علي", o))
    }

    @Test fun allWordsRequiresEveryWord() {
        val o = SearchOptions(mode = SearchMode.ALL_WORDS, wholeWord = true)
        assertEquals(listOf("عقد", "إيجار"), found("هذا عقد بيع وعقد آخر عن إيجار", "عقد ايجار", o).let { listOf(it.first(), it.last()) })
        assertTrue(found("هذا عقد بيع", "عقد ايجار", o).isEmpty())
    }

    @Test fun proximity() {
        val near = SearchOptions(mode = SearchMode.ALL_WORDS, wholeWord = true, proximity = 3)
        assertEquals(listOf("عقد", "الإيجار"), found("وقع عقد الإيجار أمس", "عقد الايجار", near))
        val text = "عقد " + "كلمة ".repeat(10) + "إيجار"
        assertTrue(found(text, "عقد إيجار", near).isEmpty())
        assertEquals(2, found(text, "عقد إيجار", near.copy(proximity = 0)).size)
    }

    @Test fun lightStem() {
        assertEquals("كتاب", ArabicNormalizer.lightStem("الكتاب"))
        assertEquals("كتاب", ArabicNormalizer.lightStem("والكتاب"))
        assertEquals("معلم", ArabicNormalizer.lightStem("المعلمون"))
        assertEquals("مدرس", ArabicNormalizer.lightStem("مدرسة"))
        assertEquals("report", ArabicNormalizer.lightStem("reports"))
    }

    @Test fun stemmedSearch() {
        val o = SearchOptions(stem = true)
        assertEquals(listOf("والمعلمين"), found("شكر والمعلمين جميعاً", "معلم", o))
        assertEquals(listOf("الكتاب الجديد"), found("قرأت الكتاب الجديد", "كتاب جديد", o))
    }

    @Test fun regexWithFolding() {
        val o = SearchOptions(mode = SearchMode.REGEX)
        assertEquals(listOf("أحمد 25"), found("الاسم: أحمد 25 سنة", "احمد \\d+", o))
        assertEquals(listOf("INV-2024-001"), found("ref inv-2024-001? no: INV-2024-001", "INV-\\d{4}-\\d{3}", o.copy(norm = NormOptions(matchCase = true))))
        assertTrue(found("abc", "([", o).isEmpty())
        // \W must not be lower-cased into \w
        assertEquals(listOf("a-b"), found("a-b", "a\\Wb", o))
    }

    @Test fun phraseUnchanged() {
        assertEquals(listOf("مُحَمَّدٌ"), found("قال مُحَمَّدٌ ذلك", "محمد", SearchOptions()))
    }
}
