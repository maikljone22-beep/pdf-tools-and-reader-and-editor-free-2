package com.smartdocs.editor.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copy of Arabic pages (ARABIC_COPY_r5): the user's excerpt from page 59 of «المرشد_2025م_في_الكيمياء_الجزء_الاول.pdf»
 * after OCR — the original layer with wrongly mapped fonts plus the OCR layer of the old build (reversed lines).
 */
class ArabicCopyTest {

    /** The user's excerpt: three lines of the broken original layer, then lines of the OCR layer (RLM kept as in the paste). */
    private val USER_EXCERPT = listOf(
        "إػذاد األعزبر : حمّذ ادلغمف 777047544 - ِذسط ِبدح اٌىُُّبء مبذاسط اٌششُذ احلذَضخ - صٕؼبء 46",
        "اٌذسط اٌضبٍٔ اٌغؼخ احلـــشاسَخ واحلشاسح إٌـــىػُخ",
        "اٌغؼخ احلشاسَخ Heat Capacity :",
        "» ٌتتيعم نم تجرد ةدحاو \u200F (م“١) دحاو نم ةداملا جرد ةدحاو",
        "جرام حرارة لرقع اللازمثٌ الطاقٌ كميهيا كمي حرارة درج لرفع اللازمن هي",
        "اهنأل جاتحت ىلإ ةرارح لقأ دنع نيخستلا",
    ).joinToString("\n")

    @Test fun userExcerptCopiesWithoutTheBrokenDuplicate() {
        val out = ArabicTextFix.fix(USER_EXCERPT).split('\n')
        assertEquals(
            listOf(
                "777047544 46",                                      // numbers / Latin of a dropped broken line are kept
                "Heat Capacity",
                "واحدة درج المادة من واحد (١“م) \u200F واحدة درجت من معيتتٌ «",   // reversed OCR line back in reading order
                "جرام حرارة لرقع اللازمثٌ الطاقٌ كميهيا كمي حرارة درج لرفع اللازمن هي", // Tesseract's own errors stay
                "التسخين عند أقل حرارة إلى تحتاج لأنها",
            ),
            out,
        )
    }

    @Test fun brokenLinesAreRecognised() {
        assertTrue(ArabicTextFix.brokenLine("إػذاد األعزبر : حمّذ ادلغمف 777047544 - ِذسط ِبدح اٌىُُّبء مبذاسط اٌششُذ احلذَضخ - صٕؼبء 46"))
        assertTrue(ArabicTextFix.brokenLine("اٌغؼخ احلشاسَخ Heat Capacity :"))
        // the same lines after ToUnicodeRepair (real PDFium extraction of the repaired page)
        assertFalse(ArabicTextFix.brokenLine("إعداد الأستاذ : محمد المسقف 777047544 - مدرس مادة الكيمياء بمدارس الرشيد الحديثة - صنعاء 46"))
        assertFalse(ArabicTextFix.brokenLine("الدرس الثاني السعة الحـــرارية والحرارة النـــوعية"))
        assertFalse(ArabicTextFix.brokenLine("هي كمية الطاقة اللازمةّ لرفع ay حرارة كمية || كمية الطاقة اللازمّ لرفع Gry حرارة جرام"))
    }

    @Test fun onlyBrokenTextIsKeptAsIs() {
        // nothing readable to fall back on: the broken text is all the user has, keep it
        val only = "إػذاد األعزبر : حمّذ ادلغمف\nاٌذسط اٌضبٍٔ اٌغؼخ"
        assertEquals(only, ArabicTextFix.fix(only))
        // repaired page: nothing is dropped
        val good = "المرشد في الكيمياء - للصف الثالث الثانوي 2024 – 2025 الوحدة الثانية\nإعداد الأستاذ : محمد المسقف 777047544"
        assertEquals(good, ArabicTextFix.fix(good))
    }

    @Test fun reversedWordsAreDetectedByTehMarbutaAndAlefMaksura() {
        assertTrue(ArabicTextFix.isVisualOrder("اهنأل جاتحت ىلإ ةرارح لقأ دنع نيخستلا"))
        assertFalse(ArabicTextFix.isVisualOrder("التسخين عند أقل حرارة إلى تحتاج لأنها"))
        assertFalse(ArabicTextFix.isVisualOrder("العلاقة بين السعة الحرارية وكمية الطاقة المكتسبة أو المفقودة :"))
    }

    @Test fun allahLigatureAfterAlefGlyph() {
        assertEquals("أسأل الله لكم", ArabicTextFix.fix("أسأل اهلل لكم"))
        assertEquals("والله بالله", ArabicTextFix.fix("واهلل باهلل"))
        assertEquals("هلل", ArabicTextFix.fix("هلل"))                         // a real word, untouched
    }

    /** Real PDFium output of the repaired page 59 and the x centres of its characters (PDF points). */
    @Test fun mixedLineInVisualOrderIsPutBackIntoReadingOrder() {
        val line = ": Capacity Heat الحرارية السعة"
        val xs = listOf<Float?>(397f, null, 407f, 415f, 422f, 429f, 435f, 440f, 443f, 448f, null, 460f, 469f, 475f, 481f, null,
            522f, 514f, 511f, 505f, 504f, 497f, 492f, 487f, null, 554f, 548f, 539f, 533f, 528f)
        assertEquals(line.length, xs.size)
        assertEquals("السعة الحرارية Capacity Heat :", ArabicTextFix.reorderMixedLine(line, xs))
        // the same line already in reading order (as the broken original came out) is left alone
        val rtl = "السعة الحرارية Heat Capacity :"
        val rx = listOf<Float?>(554f, 548f, 539f, 533f, 528f, null, 522f, 514f, 511f, 505f, 504f, 497f, 492f, 487f, null,
            460f, 469f, 475f, 481f, null, 407f, 415f, 422f, 429f, 435f, 440f, 443f, 448f, null, 397f)
        assertEquals(rtl, ArabicTextFix.reorderMixedLine(rtl, rx))
        // Latin-majority lines are not touched
        val en = "Heat Capacity of water ماء"
        assertEquals(en, ArabicTextFix.reorderMixedLine(en, en.indices.map { it.toFloat() }))
    }
}
