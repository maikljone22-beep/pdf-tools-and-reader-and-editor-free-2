package com.smartdocs.editor.core.ocr

import com.smartdocs.editor.core.ocr.OcrRefine.Tok
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR quality (OCR_QUALITY_r5): «المرشد…الجزء_الاول» page 78 (tablet: page 25 of 55), OCRed in the reader and copied.
 * Real Tesseract 5.4 words (tessdata_best ara+eng) and real second readings of the Arabic script model.
 */
class OcrQualityTest {

    /** The user's copied text (tablet, English UI), lines 1..58 as pasted, then the short junk lines it ended with. */
    private val USER_COPY = """- = = C2H2
C2H2(g) +

⁄ O2(g) → 2CO2(g) + H2O(l) ∆Hc = - 1256 KJ/mole
ا حت ات ات امت تج الت ست الست ات ات ست الست ست الست ات ست ست الست ست امت امح تمصت ست رصت رست رست امت رست رست رست رست رست رست ست ست رصت
/ الكيمياء الحرارية ADE
من المعادلةّjG : ب ¢
أحسب حرارة احتراق & جو من الميثان ( إذا Cale أن :(1=cH12=C(A
المعطيات :lisa الميثان tT جم |( حرارة احتراق مول واحد من الميثان Ade كيلوجول/مول
المطلوب : حساب حرارة احتراق 4 جرام من غازالميثان -
كتلٌ مول واحد من غاز الميثان ١( )١؟ +) )١ = 4+1 - ١ جرام -
١6 جرام منغاز الميثان Adeكيلوجول .
تح
#
حت
#
حت
4
حت
OT
NT
TT
: اا
وعتد صرب طرفين في وسطين
t جرام من غازالميثان - +(س) كيلوجول/مول . i
Ade x ¢
- 0YYYL كيلوجول . :
A
(س) حرارة احتراق & جرام من الميثان
CT
ST
يحترق Joi أكسيد الكَرجوْنَ ¢ كالآتي : ...
)١ أحسب حرارة احتتراق ٠4 جور من أول أكسيد 592,581 ¢ 13( Cale أن
YF (أحسب lism Jol أكسيد الكربون التي تعطي عند احتراقها ) - 011.3 كيلؤجول ( -
المعطيات : كتلمّ ١4 جور < +( حرارة احتراق مول واحد من YAY كيلوجول/مول -
المطلوب 1V (حساب حرارة احتزاق VE جرام منغاز )0
CAL 08 - 11 + ١١ = (XV) H(A )١ SLE من واحد مول ٌكتلم
YA جرام je غاز ست( YAY كيلوجول/مول
١4 جرام من غاز —(س) كيلوجول .
YAY x ٠٠
+ 141.9 كيلوجول -
(س) حرارة احتراق ٠١4 جرام من
KEAN
سبمنهج<
بن
Leg صرب طرفين في وسطين بمنهج<
بن
NT
ST
(" حساب lisa غاز التي تعطي حرارة ) - 0117 كيلوجول -(
كتلتٌ مول واحد من ١( )١١ (MX (- ١ +11 - 14 جرام -
YA جرام من غاز مس46 YAY كيلوجول /مول
س جرام من غاز -حث+ 00 كيلوجول
071.0 x YA
(س) Wiss غاز ست 0 جرام .
YAY
13( علمت أن الحرارة المتطلقت من احتراق 1.07 جرام من الأسيتيلين تساوي 3٠4 كيلوجول ؛ أحسب
حرارة احتراق مول واحد dite ؛ علماً gly : ) ؛ خم أكتب معادل اختراق الأسيتيلين -
و
SE
نت
#رحتح
HEI
CE
EOE
""" + List(50) { "وات" }.joinToString(" ")

    private val lines get() = USER_COPY.lines().map { it.trim() }

    @Test fun userCopyJunkLinesAreRecognised() {
        val junk = listOf(
            "ا حت ات ات امت تج الت ست الست ات ات ست الست ست الست ات ست ست الست ست امت امح تمصت ست رصت رست رست امت رست رست رست رست رست رست ست ست رصت",
            "تح", "#", "حت", "OT", "NT", "TT", ": اا", "A", "CT", "ST", "KEAN", "بن", "و", "SE", "نت", "#رحتح", "HEI", "CE", "EOE",
            List(50) { "وات" }.joinToString(" "),
        )
        for (j in junk) {
            assertTrue("line from the copy: «$j»", j in lines)
            assertTrue("junk kept: «$j»", OcrCleanup.isJunkTextLine(j))
        }
    }

    @Test fun userCopyRealLinesAreKept() {
        val dropped = lines.filter { OcrCleanup.isJunkTextLine(it) }.toSet()
        val real = listOf(
            "- = = C2H2 ", "C2H2(g) + ", "⁄ O2(g) → 2CO2(g) + H2O(l) ∆Hc = - 1256 KJ/mole", "/ الكيمياء الحرارية ADE",
            "المطلوب : حساب حرارة احتراق 4 جرام من غازالميثان -", "وعتد صرب طرفين في وسطين", "(س) حرارة احتراق & جرام من الميثان",
            "يحترق Joi أكسيد الكَرجوْنَ ¢ كالآتي : ...", "١4 جرام من غاز —(س) كيلوجول .", "YAY x ٠٠", "071.0 x YA", "Ade x ¢", "4",
            "Leg صرب طرفين في وسطين بمنهج<", "حرارة احتراق مول واحد dite ؛ علماً gly : ) ؛ خم أكتب معادل اختراق الأسيتيلين -",
        )
        for (r in real) {
            assertTrue("line from the copy: «$r»", r.trim() in lines)
            assertFalse("real line dropped: «$r»", r.trim() in dropped)
        }
        // every line with two Arabic words of 5+ letters is kept
        lines.filter { l -> l.split(' ').count { w -> w.count { OcrRefine.isArabicLetter(it) } >= 5 } >= 2 }
            .forEach { assertFalse("«$it»", it in dropped) }
    }

    @Test fun formulasAreNoFragments() {
        listOf("CO", "CH4", "C2H2(g)", "Fe2O3", "KSCN", "H2O(l)", "CO2(g)", "(NH4)2").forEach { assertTrue(it, OcrCleanup.isFormula(it)) }
        listOf("NT", "EOE", "SE", "OT", "YAY", "KEAN", "A", "Cale").forEach { assertFalse(it, OcrCleanup.isFormula(it)) }
        assertFalse(OcrCleanup.isFragment("CO")); assertFalse(OcrCleanup.isFragment("٢٨٣")); assertFalse(OcrCleanup.isFragment("من"))
        assertTrue(OcrCleanup.isFragment("حت")); assertTrue(OcrCleanup.isFragment("TT"))
    }

    // ---- word-level cleanup on real Tesseract words of page 78 (box l, t, r, b in px of the 2474x3500 render) ----

    private fun t(s: String, l: Int, tp: Int, r: Int, b: Int, c: Int, line: Int) = Tok(s, l, tp, r, b, c.toFloat(), line)

    /** A real text line («المطلوب : حساب حرارة احتراق ٤ جرام من غاز الميثان CH4 .»). */
    private val REAL = listOf(
        t("المطلوب", 2164, 492, 2318, 536, 91, 0), t(":", 2140, 507, 2148, 524, 92, 0), t("حساب", 2012, 493, 2127, 536, 92, 0),
        t("حرارة", 1894, 494, 1999, 534, 90, 0), t("احتراق", 1749, 493, 1879, 534, 88, 0), t("4", 1719, 495, 1734, 524, 88, 0),
        t("جرام", 1604, 495, 1703, 536, 92, 0), t("من", 1543, 495, 1592, 535, 93, 0), t("غاز", 1463, 493, 1530, 534, 91, 0),
        t("الميثان", 1325, 490, 1454, 535, 91, 0), t("CHy‏", 1210, 485, 1312, 533, 89, 0), t("-", 1180, 516, 1191, 524, 60, 0),
    )

    /** The vertical side border, read by Tesseract as one "line" of stacked words. */
    private val SIDE_BORDER = listOf(
        t("كن", 2379, 1987, 2384, 2006, 47, 1), t("ف", 2378, 2015, 2391, 2023, 45, 1), t("حت", 2364, 2033, 2396, 2057, 33, 1),
        t("في", 2364, 2075, 2396, 2085, 29, 1), t("حت", 2364, 2093, 2396, 2119, 29, 1), t("ف", 2377, 2135, 2392, 2145, 55, 1),
        t("حتت", 2364, 2155, 2396, 2179, 35, 1), t("ف", 2377, 2195, 2392, 2203, 62, 1), t("حتت", 2364, 2215, 2396, 2239, 48, 1),
        t("ف", 2376, 2255, 2392, 2265, 52, 1), t("حتت", 2364, 2275, 2396, 2299, 31, 1), t("حت", 2379, 2347, 2384, 2365, 26, 1),
        t("COCEHOCAHEOCAHEO", 2376, 2526, 2392, 2904, 0, 1), t("EO‏", 2379, 2407, 2384, 2485, 63, 1),
    )

    /** The chain border at the top (glyph pieces 5..15 px high). */
    private val CHAIN = listOf(
        t("حم", 2280, 91, 2299, 96, 49, 2), t("فحت", 2220, 91, 2239, 96, 47, 2), t("#حتخ.", 2160, 84, 2200, 99, 19, 2),
        t("#رحتخ.", 2026, 84, 2080, 99, 45, 2), t("#", 1887, 74, 1897, 106, 46, 2), t("تح", 1801, 92, 1804, 94, 67, 2),
        t("حت", 1741, 91, 1760, 96, 6, 2), t("حت", 1622, 91, 1640, 96, 28, 2), t("حت", 1502, 91, 1520, 96, 47, 2),
        t("جصبوجحبن", 1169, 87, 1281, 99, 16, 2), t("صجججمدصوجدهح+ومججبججمج+جججوهجبمىر+جججرهج", 553, 84, 1161, 99, 0, 2),
        t("مرحت", 191, 91, 209, 96, 0, 2), t("حل", 129, 91, 150, 96, 32, 2),
    )

    @Test fun cleanupDropsBordersKeepsText() {
        val denominator = listOf(t("YA‏", 1003, 2016, 1040, 2043, 90, 3))      // «٢٨» under a fraction bar: kept
        val formula = listOf(t("CO", 1821, 2006, 1904, 2046, 93, 4))
        val out = OcrCleanup.clean(REAL + SIDE_BORDER + CHAIN + denominator + formula)
        assertEquals(REAL + denominator + formula, out)
    }

    // ---- second readings with the Arabic script model (readings measured with tessdata_best/script/Arabic, psm 7) ----

    private val ARABIC_LINE = listOf(
        t("المعطيات", 2145, 416, 2318, 459, 92, 0), t("lisa:‏", 1990, 416, 2128, 455, 23, 0), t("الميثان", 1845, 414, 1974, 459, 91, 0),
        t("ET‏", 1767, 419, 1833, 448, 30, 0), t("حرارة", 1380, 418, 1486, 458, 91, 0), t("Me‏", 715, 419, 774, 448, 57, 0),
        t("CO", 1892, 1248, 1975, 1288, 92, 0), t("4", 1719, 495, 1734, 524, 88, 0),
    )

    @Test fun candidatesAreNonArabicWordsOfArabicLines() {
        val c = OcrRefine.candidates(ARABIC_LINE).map { ARABIC_LINE[it].text }
        assertEquals(listOf("lisa:‏", "ET‏", "Me‏", "4"), c)            // «CO» read with 92 % is left as it is
        // a Latin line is left alone
        val latin = listOf(t("Heat", 10, 10, 90, 40, 60, 0), t("Capacity", 100, 10, 250, 40, 55, 0))
        assertTrue(OcrRefine.candidates(latin).isEmpty())
    }

    @Test fun secondReadingIsTakenOnlyWhenArabic() {
        fun w(s: String, c: Int = 50) = t(s, 0, 0, 60, 30, c, 0)
        assertEquals("٨٩٠", OcrRefine.accept(w("Me‏"), "۸۹۰", 73.9f))           // Persian digit shapes folded to Arabic-Indic
        assertEquals("١٤", OcrRefine.accept(w("VE‏"), "۱٤", 83.9f))
        assertEquals("علمت", OcrRefine.accept(w("Cale‏"), "علمت", 50.7f))
        assertEquals("وعند", OcrRefine.accept(w("Leg‏"), "وعند", 62.1f))
        assertEquals("٤", OcrRefine.accept(w("4"), "٤", 76.8f))
        assertEquals("١٦", OcrRefine.accept(w("١6"), "۱٦", 79f))
        assertNull(OcrRefine.accept(w("CO", 92), "CO", 94.9f))                    // Latin stays Latin
        assertNull(OcrRefine.accept(w("4", 93), "3", 76.8f))                      // no Arabic content: first reading stands
        assertNull(OcrRefine.accept(w("YAY", 43), "YAY", 56.7f))
        assertNull(OcrRefine.accept(w("Me"), "سے ۸۰", 50f))                       // Urdu letter: not Arabic text
        assertNull(OcrRefine.accept(w("Gaya‏"), "التغير في درجة", 60f))          // padded box caught the next words
        assertNull(OcrRefine.accept(w("Angie"), "27 ي", 60f))                     // Latin digits + lone letter
        assertNull(OcrRefine.accept(w("Jol‏"), "ول", 16.4f))                       // too unsure
        assertNull(OcrRefine.accept(w("CA"), "جراھ ۔", 30f))
        // a digit-like Arabic piece («4ق», a lone «ر») may only become a number
        assertNull(OcrRefine.accept(w("4ق"), "إذا", 80f))
        assertEquals("٢٦", OcrRefine.accept(w("ر"), "۲٦", 70f))
        assertNull(OcrRefine.accept(w("ر"), "من", 90f))
        assertNull(OcrRefine.accept(w("حرارة", 40), "حرارة", 90f))               // Arabic words get no second reading
    }

    @Test fun refineAsksOnlyForCandidates() {
        val asked = ArrayList<IntArray>()
        val readings = mapOf("lisa:‏" to (": ڪلي" to 44f), "ET‏" to ("٤ =" to 52.3f), "Me‏" to ("۸۹۰" to 73.9f),
            "CO" to ("CO" to 94.9f), "4" to ("٤" to 76.8f))
        val out = OcrRefine.refine(ARABIC_LINE) { l, tp, r, b ->
            asked += intArrayOf(l, tp, r, b)
            val w = ARABIC_LINE.first { it.left - OcrRefine.pad(it) == l && it.top - OcrRefine.pad(it) == tp }
            readings[w.text]
        }
        assertEquals(4, asked.size)
        assertEquals(listOf("المعطيات", ": كلي", "الميثان", "٤ =", "حرارة", "٨٩٠", "CO", "٤"), out.map { it.text })
        // padding: a quarter of the height, at least 6 px
        assertEquals(intArrayOf(1990 - 9, 416 - 9, 2128 + 9, 455 + 9).toList(), asked[0].toList())
    }

    @Test fun foldScript() {
        assertEquals("٨٩٠ كيلوجول", OcrRefine.foldScript("۸۹۰ کیلوجول‏"))
        assertEquals("كتلة", OcrRefine.foldScript("ڪتلۃ"))
    }

    @Test fun maskedTinyImagesCountAsPictures() {
        // page 78: Arabic words drawn as a 2x2 purple image through a 1569x153 1-bit soft mask
        assertEquals(1569 to 153, MixedPageOcr.drawnSize(2, 2, 1569, 153))
        assertEquals(265 to 416, MixedPageOcr.drawnSize(265, 416, 0, 0))
        assertEquals(840 to 828, MixedPageOcr.drawnSize(840, 828, 420, 414))
    }
}
