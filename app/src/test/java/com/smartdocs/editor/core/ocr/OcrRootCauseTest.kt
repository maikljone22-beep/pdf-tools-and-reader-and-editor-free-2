package com.smartdocs.editor.core.ocr

import com.smartdocs.editor.core.ocr.MixedPageOcr.Box
import com.smartdocs.editor.core.tools5.LangDetect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression tests for the OCR root-cause fixes (ROOTCAUSE_arabic_ocr_r5.md). */
class OcrRootCauseTest {

    private val page = Box(0f, 0f, 595f, 842f)

    // --- MixedPageOcr.scanShare: which digital pages still need OCR ---

    @Test fun highlightTilesAndLabelledDiagramsAreNotScans() {
        // page 54 of «المرشد»: 2x2-px highlight tiles are filtered before (size), a 161x35 pt diagram strip is 1 % of the page
        val diagram = Box(32f, 397f, 193f, 432f)
        assertTrue(MixedPageOcr.scanShare(listOf(diagram), emptyList(), page) < MixedPageOcr.SCAN_SHARE)
    }

    @Test fun pictureWithTextOnItIsNotAScan() {
        // full-page background picture with the real text drawn over it (periodic table page, watermark + text)
        val bg = Box(0f, 0f, 595f, 842f)
        val chars = (0 until 200).map { i -> Box(50f + (i % 40) * 12f, 100f + (i / 40) * 20f, 58f + (i % 40) * 12f, 110f + (i / 40) * 20f) }
        assertEquals(0f, MixedPageOcr.scanShare(listOf(bg), chars, page), 0.0001f)
    }

    @Test fun scannedRegionWithoutTextIsAScan() {
        val scan = Box(40f, 300f, 555f, 700f)                      // half a page of scanned paragraph, no text layer on it
        val title = listOf(Box(200f, 780f, 210f, 795f))             // a typed title elsewhere
        assertTrue(MixedPageOcr.scanShare(listOf(scan), title, page) >= MixedPageOcr.SCAN_SHARE)
    }

    @Test fun picturesAreClippedToThePage() {
        val huge = Box(-1000f, -1000f, 3000f, 3000f)
        assertEquals(1f, MixedPageOcr.scanShare(listOf(huge), emptyList(), page), 0.0001f)
        assertEquals(0f, MixedPageOcr.scanShare(listOf(Box(700f, 0f, 900f, 100f)), emptyList(), page), 0.0001f)
    }

    // --- EditableTextPdfWriter.inkOf: rewritten words keep a visible colour ---

    private fun word(bg: Int, ink: Int, inkPixels: Int) = IntArray(400) { if (it < inkPixels) ink else bg }

    @Test fun darkInkOnPaper() {
        val c = EditableTextPdfWriter.inkOf(word(0xFFFFFFFF.toInt(), 0xFF000080.toInt(), 60), 0xFFFFFFFF.toInt())
        assertNotNull(c); assertTrue(lum(c!!) < 60)
    }

    @Test fun lightInkOnDarkBoxStaysLight() {
        // cover page: white words in a black oval used to be redrawn black on black (= erased)
        val c = EditableTextPdfWriter.inkOf(word(0xFF101010.toInt(), 0xFFFFFFFF.toInt(), 60), 0xFF101010.toInt())
        assertNotNull(c); assertTrue("ink ${Integer.toHexString(c!!)}", lum(c) > 200)
    }

    @Test fun noInkMeansDoNotRewrite() {
        assertNull(EditableTextPdfWriter.inkOf(word(0xFFF0F0F0.toInt(), 0xFF000000.toInt(), 2), 0xFFF0F0F0.toInt()))
    }

    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    // --- OCR languages ---

    @Test fun englishTermsKeepEnglishForArabicDocuments() {
        // Latin share ~5 % (like «الملخص الشامل», 4.8 %): detect() drops English, OCR must keep it
        val text = "الكيمياء الحرارية هي فرع من فروع علم الكيمياء يختص بدراسة التغيرات الحرارية المصاحبة للتغيرات الكيميائية " +
            "والفيزيائية وتسمى Thermochemistry ويتكون الماء والغاز CO2 عند احتراق الوقود في السيارات " +
            "وتختلف كمية الطاقة المخزونة في أي مادة تبعا لاختلاف عاملين هما نوع الذرات الداخلة في تركيب المادة ونظام ترتيب الذرات"
        assertFalse(LangDetect.detect(text).contains("eng"))
        assertEquals(listOf("ara", "eng"), LangDetect.forOcr(text))
    }

    @Test fun pureArabicStaysArabic() {
        assertEquals(listOf("ara"), LangDetect.forOcr("هذا نص عربي طويل نسبيا لاختبار الكشف عن اللغة دون كلمات أجنبية"))
    }

    // --- pinned, verified tessdata ---

    @Test fun everyOfferedOrDetectableLanguageIsPinned() {
        val langs = listOf("ara", "eng", "fra", "deu", "spa", "tur", "fas", "urd", "ita", "por", "rus", "ell", "heb", "hin", "chi_sim")
        langs.forEach { l ->
            val p = OcrEngine.PINNED[l]
            assertNotNull(l, p)
            assertEquals(l, 64, p!!.sha256.length)
            assertTrue(l, p.size > 1_000_000)
            assertFalse(OcrEngine.modelUrl(l).contains("/main/"))
            assertTrue(OcrEngine.modelUrl(l).contains(OcrEngine.TESSDATA_COMMIT))
        }
    }

    @Test fun sha256OfFile() {
        val f = File.createTempFile("sha", ".bin"); f.writeText("abc")
        try { assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", OcrEngine.sha256(f)) } finally { f.delete() }
    }
}
