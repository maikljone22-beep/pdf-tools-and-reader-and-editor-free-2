package com.smartdocs.editor.core.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text-layer quality on REAL PDFium extractions of the user's chemistry PDFs (rootcause r5): pages whose fonts map
 * glyphs to the wrong Unicode (Simplified Arabic / PT Bold Heading / Yakout / Yaser) must be recognised as unusable,
 * while clean Arabic and Word-export text with reversed ligatures («يف», «امل») must not.
 */
class ArabicTextQualityTest {
    // all_4_p054
    private val ALL_4_P054 = "ا\u064Cذسط األوي \u0650مذ\u0650خ يف ا\u064Cى\u064F\u0651\u064F\u0640\u0640بء احل\u0640\u0640شاس\u064Eخ ) chemistry Thermo) \uF0B7 وشث\u0652\u065B ١\u0658\u065Aذس\u065Aع\u0653١ )١\u0658ذس\u065Aوشث\u0654\u065Bبد( \uF0B7 أوغغ\u0653١ ١\u0658\u065Aذس\u065Aع\u0653١ \uF0B7 ػب١\u064Cخ )ر\u0655زظ ؽبلخ ؽشاس٠خ ػب١\u064Cخ ػ\u0655ذ اؽزشال\u0659ب( \uF0B7 \u0655\u0650خفؼخ"
    // all_2_p008
    private val ALL_2_P008 = "Tc خڀعٽى\u0655ع\u064F\u0655ڂ -2 \u0654٭\u064B خڀمىفى\u064F\u0655 خڀ٭ى\u064F\u065D خڀؽدڀػ \u064F\u0659٬ ڄه ل\u0655ػ خڀ\u064Fٴ\u064Fش ٴ\u0653 خڀٹ\u064F\u0659ش -3 \u0654ٽ\u064Fن خڀمىفى\u064F\u0655 ڄ\u064Fټسدض زمد\u065Cض ظإټ\u064B\u0655 )+ 2 , + 3 + 4 , + 5 \u0658و\u0640\u064B \u0654ع\u0640\u064E\u064Bؾ ٴ\u0640\u0653 ٴٹ\u0640\u064B \u0640م\u0655\u0640٫ بڀٽع\u064E\u064Fودظ\u064B ٴ\u0653 )٬ځٿ("
    // all_6_p003
    private val ALL_6_P003 = "ز ذؽ\u064Cخ هثريحإىل أكصح ف\u064Cرح هثدي أتن\u064D كمب 0إىل ه\u064D من شعلن\u064D كىل هرا ا\u064Bلم\u064D طىاء تسأي أو فوسح األطربذ / \u064Bؼف\u064D كمس ؼ\u064Cمد طلد تبصث\u064Cؾ"
    // all_3_p011
    private val ALL_3_P011 = "ا\u064Cذسط األ\u065Aي ا\u064Cغبلخ \u065Aا\u064Cزفبػ\u0640\u0640\u0640\u0640\u0640\u0640الد ا٠\u065A\u065B\u0655\u064Cخ \uF03F \u0653\u0650 خالي دساعز\u0655ب ا\u064Cغبثمخ \u064D\u064Cزفبػالد ا\u064Cز\u065F رز\u064F يف ا\u064Cزسح ، ثأ\u0652 ا\u064Cزفبػالد ر\u0655مغ\u064F إىل لغ\u0651ني \u0651\u0658ب : 1( \u0658\u065F ا\u064Cزفبعالد ا\u064Cز\u065F رؾذس \u0654ز١غخ \u064Cفمذ أ\u065A اوزغبة اإل\u064Cىزش\u0654\u065Aبد ا\u065B\u0651\u064Cع\u065Bدح ف\u065F ا\u0651\u064Cغتز\u065C\u065B"
    // all_9_p027
    private val ALL_9_P027 = "-2 طف\u0640ج انع\u064Fاطش ف\u064D سطىس أفق\u064Fت ) دوساث ( حبعا\u064B نعذد يسخى\u064Eاث انطاقت األنكخشو\u064F\u064Eت ف\u064Fها ان\u064Bشغىنت باالنكخشو\u064Eاث -3 وػعج انع\u064Fاطش ف\u064D أع\u064Bذة سأس\u064Fت ) يج\u064Bىعاث( حبعا\u064B نعذد األنكخشو\u064Eاث ان\u064Bىجىدة ف\u064D يسخىي انطاقت األخ\u064Fش ) انكخشو\u064Eاث انخكافؤ(. وبهزا \u064Eالحظ أ\u064C انجذول انذوس\u064C بشكم عاو \u064Eخأنف ي\u064D سبع دوساث أفق\u064Fت ور\u064Bا\u064C يج\u064Bىعاث"
    // all_4_p033
    private val ALL_4_P033 = "ورقة عمل ) 6 - 1 ( : أسئلة وزارية على الدرس \uF056 السؤال األول : ضع عالمة ص\u0640)✓ (\u0640ح أمام العبارة الصحيحة ، وعالمة خ\u0640)\uF0FB)\u0640طأ أمام العبارة اخلاطئة : درجة لكل فقرة 1 يختزل الهيماتيت في الفرن الالفح بواسطة أول أكسيد الكربون ."
    // all_9_p020
    private val ALL_9_P020 = "الذرة الأخرى برابطة ھیدروجینیة حیث تعمل ذرة الھیدروجین كقنطرة أو جسر یربط الجزیئات ببعضھا ویرمز للرابطة ھي رابطة تنشأ من سحابة إلكترونات التكافؤ الحرة المحیطة بأیونات الفلز الموجبة في الشبكة البلوریة"
    // all_8_p010
    private val ALL_8_P010 = "نموذج نموذج دالتون نموذج تومسون للإلكترونات ولكنھ اتفق مع بوھر حول وجود مستویات للطاقة، وبدلا \u064B"
    // all_5_p010
    private val ALL_5_P010 = "حمت\u0640\u0640\u0640\u0640\u0640\u0640\u0640\u0640ويات الكت\u0640\u0640\u0640\u0640\u0640\u0640\u0640\u0640\u0640اب الوحدة األولى : العناصر االنتقالية الوحدة الثانية : الكيمياء الحرارية"

    @Test fun wronglyMappedFontsAreUnusable() {
        for ((name, s) in listOf("المرشد p54" to ALL_4_P054, "الملخص الشامل p8" to ALL_2_P008, "المساند p3" to ALL_6_P003,
                "المرشد ف2 p11" to ALL_3_P011, "ملخص p27" to ALL_9_P027)) {
            assertTrue(name, ArabicTextFix.isBrokenArabic(s))
            assertTrue(name, ArabicTextFix.isUnusableText(s))
            assertFalse(name, ArabicTextFix.isGarbage(s))        // the old check let all of them through
        }
    }

    @Test fun cleanAndWordExportTextIsUsable() {
        for ((name, s) in listOf("المرشد p33" to ALL_4_P033, "ملخص p20" to ALL_9_P020, "ملخص p10" to ALL_8_P010, "المرشد و1 p10" to ALL_5_P010))
            assertFalse(name, ArabicTextFix.isUnusableText(s))
    }

    @Test fun vocalisedArabicIsPlausible() {
        val s = "ب\u0650س\u0652م\u0650 الل\u064E\u0651ه\u0650 الر\u064E\u0651ح\u0652م\u064Eن\u0650 الر\u064E\u0651ح\u0650يم\u0650 قرأ الطالب\u064F كتاب\u064Bا جديد\u064Bا ثم كتب درس\u064Bا مفيد\u064Bا وشيئ\u064Bا آخر في المدرسة\u0650 مساء\u064B مع صديق\u0650ه\u0650 محم\u0651د\u064D"
        assertFalse(ArabicTextFix.isBrokenArabic(s))
        assertFalse(ArabicTextFix.implausibleWord("كتاب\u064Bا"))
        assertFalse(ArabicTextFix.implausibleWord("مساء\u064B"))
        assertFalse(ArabicTextFix.implausibleWord("محم\u0651د"))
    }

    @Test fun implausibleWordRules() {
        assertTrue(ArabicTextFix.implausibleWord("ا\u064Cذسط"))      // tanween inside a word
        assertTrue(ArabicTextFix.implausibleWord("\u064Fكتب"))       // vowel starts the word
        assertTrue(ArabicTextFix.implausibleWord("خڀمىفى"))           // Sindhi letter in Arabic text
        assertTrue(ArabicTextFix.implausibleWord("ذس١عن"))             // Arabic-Indic digit between letters
        assertTrue(ArabicTextFix.implausibleWord("ذس\u065Aعن"))       // small-v mark (wrong font map)
        assertFalse(ArabicTextFix.implausibleWord("پژوهش"))           // Persian letters are normal
        assertFalse(ArabicTextFix.implausibleWord("ٹھیک"))            // Urdu letters are normal
    }

    @Test fun shortTextIsNeverJudged() {
        assertFalse(ArabicTextFix.isBrokenArabic("ا\u064Cذسط ا\u064Cى"))
    }
}
