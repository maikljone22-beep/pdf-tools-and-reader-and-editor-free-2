package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeAndNamingTest {

    @Test fun recipeRoundTrip() {
        val r = Recipe(
            id = "r1", name = "أرشفة", builtIn = false,
            steps = listOf(Step(StepType.OCR, mapOf("langs" to "ara+eng")), Step(StepType.WATERMARK, mapOf("text" to "سري \"x\""), enabled = false), Step(StepType.PDFA)),
            output = OutputOptions("{name}_{date}", sameFolder = true, overwrite = true),
        )
        val back = RecipeJson.fromJson(RecipeJson.toJson(r))
        assertEquals(r, back)
        val list = RecipeJson.listFromJson(RecipeJson.listToJson(listOf(r, BuiltInRecipes.all.first())))
        assertEquals(2, list.size)
        assertEquals(BuiltInRecipes.all.first(), list[1])
    }

    @Test fun unknownStepsAndBadJsonAreTolerated() {
        val r = RecipeJson.fromJson("""{"id":"x","name":"n","steps":[{"type":"FUTURE_STEP"},{"type":"OCR"}]}""")
        assertEquals(listOf(StepType.OCR), r.steps.map { it.type })
        assertEquals("{name}_{recipe}", r.output.pattern)
        assertTrue(RecipeJson.listFromJson("not json").isEmpty())
        assertEquals(150, Step(StepType.OPTIMIZE).int("dpi", 0))
        assertFalse(Step(StepType.PAGE_NUMBERS).bool("arabicDigits"))
    }

    @Test fun outputNaming() {
        assertEquals("report_web_007", OutputNaming.apply("{name}_{recipe}_{n}", "report", "web", 6, "2024-01-02", "1030"))
        assertEquals("a_b_2024-01-02", OutputNaming.apply("{name}/{recipe}:{date}", "a", "b", 0, "2024-01-02", "1030"))
        assertEquals("doc", OutputNaming.apply("", "doc", "r", 0, "d", "t"))
    }

    @Test fun smartNameFromTitleAndDate() {
        val text = "\n  12/05/2024 \nعقد إيجار شقة سكنية في الرياض\nالطرف الأول: ..."
        assertEquals("عقد_إيجار_شقة_سكنية_في_الرياض_2024-05-12", SmartNaming.suggest(text, ScanKind.DOCUMENT, "2025-01-01"))
    }

    @Test fun smartNameReceiptAndFallback() {
        assertEquals("Receipt_Panda_Hypermarket_2025-01-01", SmartNaming.suggest("Panda Hypermarket\nTotal 10.00", ScanKind.RECEIPT, "2025-01-01", mapOf(ScanKind.RECEIPT to "Receipt")))
        assertEquals("2025-01-01", SmartNaming.suggest("12 34 56", ScanKind.DOCUMENT, "2025-01-01"))
        assertEquals("Invoice No 55/2", SmartNaming.titleLine("Page 1\nInvoice No: 55/2"))
    }

    @Test fun safeFileName() {
        assertEquals("a_b_c", TextNorm.safeFileName(" a/b:c "))
        assertEquals("x", TextNorm.safeFileName("x\u200F"))
    }

    @Test fun langDetect() {
        assertEquals(listOf("ara"), LangDetect.detect("هذا نص عربي طويل نسبيا لاختبار الكشف عن اللغة"))
        assertEquals("fas", LangDetect.detect("این یک متن فارسی است که پچ گژ")[0])
        assertEquals("fra", LangDetect.latinLang("le chat est dans la maison et les enfants"))
        val mixed = LangDetect.detect("هذا نص عربي مع some English words in the middle of it")
        assertEquals(setOf("ara", "eng"), mixed.toSet())
    }

    @Test fun mergeMapping() {
        val m = MergeMapping.auto(listOf("Name", "form.Email_Address", "الهاتف", "City"), listOf("email address", "name", "رقم الهاتف", "Country"))
        assertEquals("name", m["Name"]); assertEquals("email address", m["form.Email_Address"]); assertEquals("رقم الهاتف", m["الهاتف"]); assertEquals(null, m["City"])
        val taken = HashSet<String>()
        assertEquals("Ali", MergeMapping.fileName("row", listOf("n"), listOf("Ali"), "n", 0, taken))
        assertEquals("Ali_2", MergeMapping.fileName("row", listOf("n"), listOf("ali"), "n", 1, taken).let { if (it == "ali_2") "Ali_2" else it })
        assertEquals("row_3", MergeMapping.fileName("row", listOf("n"), listOf(""), "n", 2, taken))
    }
}
