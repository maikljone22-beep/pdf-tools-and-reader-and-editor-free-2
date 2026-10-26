package com.smartdocs.editor.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic checks of the edit engine's Arabic handling (bidi reordering, shaping order, glyph names). */
class EditBidiTest {

    @Test fun logicalToVisualKeepsNumbersAndLatin() {
        assertEquals("٣٤ نم ١٢ ةحفص", Bidi.logicalToVisual("صفحة ١٢ من ٣٤", true))
        assertEquals("Smart Docs نم 2.5 " + "الإصدار".reversed(), Bidi.logicalToVisual("الإصدار 2.5 من Smart Docs", true))
        assertEquals("50% مصخ", Bidi.logicalToVisual("خصم 50%", true))
        assertEquals("2024/05/01 خيراتلا", Bidi.logicalToVisual("التاريخ 2024/05/01", true))
        assertEquals("(يبيرجت) ةخسن", Bidi.logicalToVisual("نسخة (تجريبي)", true))
        assertEquals("Hello ملاع world", Bidi.logicalToVisual("Hello عالم world", false))
    }

    @Test fun visualToLogicalRoundTrips() {
        val samples = listOf(
            "السلام عليكم ورحمة الله", "الإصدار 2.5 من Smart Docs", "صفحة ١٢ من ٣٤", "خصم 50% على Windows 11",
            "التاريخ 2024/05/01 الساعة 10:30", "نسخة (تجريبي) رقم ٧", "مُحَمَّدٌ رسول الله",
        )
        for (s in samples) assertEquals(s, Bidi.visualToLogical(Bidi.logicalToVisual(s, true), true))
        assertEquals("Hello عالم world", Bidi.visualToLogical(Bidi.logicalToVisual("Hello عالم world", false), false))
    }

    @Test fun shapeVisualDoesNotReverseDigitsOrLatin() {
        assertTrue(Bidi.shapeVisual("صفحة ١٢", true).startsWith("١٢"))
        assertTrue(Bidi.shapeVisual("نظام Smart Docs", true).startsWith("Smart Docs"))
        // lam-alef becomes one ligature glyph
        assertTrue(Bidi.shapeVisual("لا", true).single().code == 0xFEFB)
    }

    @Test fun glyphNames() {
        assertEquals("ا", GlyphDecoder.nameToUnicode("uni0627"))
        assertEquals("ا", GlyphDecoder.nameToUnicode("afii57415"))
        assertEquals("ا", GlyphDecoder.nameToUnicode("alef"))          // not the Hebrew alef of the AGL
        assertEquals("ب", GlyphDecoder.nameToUnicode("beh.init"))
        assertEquals("ب", GlyphDecoder.nameToUnicode("beh-ar.medi"))
        assertEquals("لا", GlyphDecoder.nameToUnicode("lam_alef"))
        assertEquals("لا", GlyphDecoder.nameToUnicode("uni06440627"))
        assertEquals("لا", GlyphDecoder.nameToUnicode("uni0644_uni0627.fina"))
        assertEquals("ه", GlyphDecoder.nameToUnicode("hehfinal"))
        assertNull(GlyphDecoder.nameToUnicode(".notdef"))
    }
}
