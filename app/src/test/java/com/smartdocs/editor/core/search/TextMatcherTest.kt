package com.smartdocs.editor.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {
    private val def = NormOptions()
    private fun hits(text: String, q: String, o: NormOptions = def, whole: Boolean = false) = TextMatcher.find(text, q, o, whole)
    private fun found(text: String, q: String, o: NormOptions = def, whole: Boolean = false) =
        hits(text, q, o, whole).map { text.substring(it.first, it.last + 1) }

    @Test fun tashkeelIgnored() {
        assertEquals(listOf("مُحَمَّدٌ"), found("قال مُحَمَّدٌ ذلك", "محمد"))
        assertEquals(listOf("محمد"), found("قال محمد ذلك", "مُحَمَّد"))
    }

    @Test fun alefForms() {
        assertEquals(listOf("أحمد"), found("جاء أحمد", "احمد"))
        assertEquals(listOf("إلى"), found("ذهب إلى البيت", "الي"))
        assertEquals(listOf("آمن"), found("بلد آمن", "امن"))
    }

    @Test fun switchesOff() {
        val strict = NormOptions(ignoreDiacritics = false, unifyLetters = false)
        assertTrue(hits("جاء أحمد", "احمد", strict).isEmpty())
        assertTrue(hits("قال مُحَمَّد", "محمد", strict).isEmpty())
    }

    @Test fun taMarbutaOptional() {
        assertTrue(hits("مدرسة", "مدرسه").isEmpty())
        assertEquals(1, hits("مدرسة", "مدرسه", def.copy(taMarbuta = true)).size)
    }

    @Test fun digitsAndTatweel() {
        assertEquals(listOf("٢٠٢٦"), found("عام ٢٠٢٦ م", "2026"))
        assertEquals(listOf("محـــمد"), found("محـــمد", "محمد"))
    }

    @Test fun presentationFormsAndLigature() {
        // ﻻ (U+FEFB) is lam-alef
        assertEquals(1, hits("ﻻ", "لا").size)
        // isolated/final forms of م ح م د
        assertEquals(1, hits("ﻣﺤﻤﺪ", "محمد").size)
    }

    @Test fun phraseAcrossLineBreak() {
        assertEquals(1, hits("السلام\r\nعليكم", "السلام عليكم").size)
    }

    @Test fun wholeWord() {
        assertEquals(1, hits("كتب الكاتبون", "كتب", whole = true).size)
        assertTrue(hits("مكتبة", "كتب", whole = true).isEmpty())
        assertEquals(1, hits("مكتبة", "كتب").size)
        assertTrue(hits("report", "port", whole = true).isEmpty())
    }

    @Test fun reversedVisualOrder() {
        // PDFs that store Arabic in visual order: "دمحم" for "محمد"
        assertEquals(1, hits("قال دمحم", "محمد").size)
    }

    @Test fun latinCaseAndAccents() {
        assertEquals(1, hits("Café", "cafe").size)
        assertTrue(hits("Hello", "hello", def.copy(matchCase = true)).isEmpty())
    }
}
