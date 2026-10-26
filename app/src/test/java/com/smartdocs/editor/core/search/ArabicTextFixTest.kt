package com.smartdocs.editor.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class ArabicTextFixTest {
    @Test fun logicalPresentationFormsBecomeLetters() {
        // "محمد" in presentation forms, logical order: meem initial, hah medial, meem medial, dal final
        assertEquals("محمد", ArabicTextFix.fix("ﻣﺤﻤﺪ"))
    }

    @Test fun visualOrderPresentationFormsAreReversed() {
        // same word stored reversed (visual order): dal final, meem medial, hah medial, meem initial
        assertEquals("محمد", ArabicTextFix.fix("ﺪﻤﺤﻣ"))
    }

    @Test fun lamAlefLigature() {
        assertEquals("لا", ArabicTextFix.fix("ﻻ"))
    }

    @Test fun reversedPlainLettersWithArticle() {
        // "الكتاب الجديد في المكتبة" reversed character by character
        val logical = "الكتاب الجديد في المكتبة"
        assertEquals(logical, ArabicTextFix.fix(logical.reversed()))
    }

    @Test fun logicalPlainTextIsUntouched() {
        val s = "ذهب الطالب إلى المدرسة"
        assertEquals(s, ArabicTextFix.fix(s))
    }

    @Test fun digitsAndLatinKeepTheirOrder() {
        // visual (display left-to-right) order of "عام 2026 في PDF": Arabic letters reversed, digits/Latin stay LTR
        assertEquals("عام 2026 في PDF", ArabicTextFix.reverseVisualLine("PDF يف 2026 ماع"))
    }

    @Test fun latinOnlyUntouched() {
        assertEquals("Hello (world)", ArabicTextFix.fix("Hello (world)"))
    }

    @Test fun mojibakeCp1256() {
        // "السلام عليكم" encoded in windows-1256, wrongly decoded as windows-1252
        val bytes = "السلام عليكم".toByteArray(charset("windows-1256"))
        val moji = String(bytes, charset("windows-1252"))
        assertEquals("السلام عليكم", ArabicTextFix.fix(moji))
        assertEquals(moji.length, ArabicTextFix.demojibake(moji).length)
    }

    @Test fun normalFrenchIsNotMojibake() {
        assertEquals("Le café est très bon", ArabicTextFix.fix("Le café est très bon"))
    }

    @Test fun privateUseGlyphsAreGarbage() {
        org.junit.Assert.assertTrue(ArabicTextFix.isGarbage(" "))
        org.junit.Assert.assertFalse(ArabicTextFix.isGarbage("السلام عليكم"))
    }
}
