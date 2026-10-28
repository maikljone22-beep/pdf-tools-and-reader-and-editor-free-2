package com.smartdocs.editor.core.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * PDF → Word on Arabic pages (PDF_TO_WORD_r5): paragraph reconstruction for right-to-left text, recognised (OCR) text
 * over real text, the OCR junk rules inside the converter, and the bidi markup Word needs.
 * The fixture lines are the user's own Word output of «المرشد…» (tablet page 1, an older build's OCR rebuild).
 */
class PdfToWordTest {

    /** Glyphs of one word, laid out right → left from [xRight] (visual positions, one glyph per letter, 0.5 em). */
    private fun rtlWord(text: String, xRight: Float, baseline: Float, fs: Float = 12f, ocr: Boolean = false): List<Glyph> {
        val w = fs * 0.5f
        return text.mapIndexed { i, c -> Glyph(xRight - (i + 1) * w, baseline - fs * 0.7f, w, fs * 0.7f, baseline, c.toString(), fs, ocr = ocr) }
    }

    /** An Arabic line: words right → left with one space between, starting at [xRight]. */
    private fun rtlLine(words: List<String>, xRight: Float, baseline: Float, fs: Float = 12f, ocr: Boolean = false): List<Glyph> {
        val out = ArrayList<Glyph>(); var x = xRight
        for (w in words) { out += rtlWord(w, x, baseline, fs, ocr); x -= (w.length + 1) * fs * 0.5f }
        return out
    }

    private fun ltrWord(text: String, x: Float, baseline: Float, fs: Float = 12f, ocr: Boolean = false): List<Glyph> {
        val w = fs * 0.5f
        return text.mapIndexed { i, c -> Glyph(x + i * w, baseline - fs * 0.7f, w, fs * 0.7f, baseline, c.toString(), fs, ocr = ocr) }
    }

    private fun page(glyphs: List<Glyph>, images: List<PageImage> = emptyList()) = PageContent(0, 595f, 842f, glyphs, images = images)

    private fun paragraphs(l: PageLayout) = l.blocks.filterIsInstance<ParagraphBlock>()

    // ---------------------------------------------------------------- paragraphs (RTL)

    @Test fun arabicLinesWithWideLineSpacingFormOneParagraph() {
        // Word's 1.5 line spacing with a tall Arabic font: baselines 20 pt apart at 12 pt, boxes 11.6 pt apart
        val words = "هي فرع من فروع علم الكيمياء يختص بدراسة التغيرات الحرارية المصاحبة للتغيرات الكيميائية".split(' ')
        val gs = rtlLine(words.take(8), 540f, 100f) + rtlLine(words.drop(8), 540f, 120f) + rtlLine(listOf("والفيزيائية", "."), 540f, 140f)
        val ps = paragraphs(LayoutAnalyzer.analyze(page(gs)))
        assertEquals(1, ps.size)
        assertTrue(ps[0].rtl)
        assertEquals(3, ps[0].lines.size)
        assertTrue(TextRuns.plain(ps[0].lines).startsWith("هي فرع من فروع"))
    }

    @Test fun labelAndNumberedLinesStartTheirOwnParagraph() {
        val gs = rtlLine("مثال (٣) : من المعادلة الآتية احسب حرارة احتراق الميثان".split(' '), 540f, 100f) +
            rtlLine("المعطيات : كتلة الميثان = ٤ جم".split(' '), 540f, 118f) +
            rtlLine("المطلوب : حساب حرارة احتراق ٤ جرام من غاز الميثان".split(' '), 540f, 136f) +
            rtlLine("١) نوع الذرات الداخلة في تركيب المادة".split(' '), 540f, 154f) +
            rtlLine("٢) نظام ترتيب الذرات".split(' '), 540f, 172f)
        assertEquals(5, paragraphs(LayoutAnalyzer.analyze(page(gs))).size)
        val l = LayoutAnalyzer.buildLines(rtlLine("المعطيات : كتلة".split(' '), 540f, 100f)).single()
        assertTrue(LayoutAnalyzer.startsItem(l))
        assertTrue(LayoutAnalyzer.startsItem(LayoutAnalyzer.buildLines(rtlLine(".33 درجة الحرارة".split(' '), 540f, 100f)).single()))
        assertFalse(LayoutAnalyzer.startsItem(LayoutAnalyzer.buildLines(rtlLine("الكيمياء الحرارية فرع من".split(' '), 540f, 100f)).single()))
    }

    @Test fun wideSpacesInsideALineDoNotCutIt() {
        // «المعطيات : كتلة الميثان = ٤ جم ،      حرارة احتراق …»: a 30 pt gap (2.5 em) on one line only
        val gs = rtlLine("المعطيات : كتلة الميثان".split(' '), 540f, 100f) + rtlLine("حرارة احتراق مول".split(' '), 370f, 100f)
        val ps = paragraphs(LayoutAnalyzer.analyze(page(gs)))
        assertEquals(1, ps.size)
        assertEquals("المعطيات : كتلة الميثان حرارة احتراق مول", TextRuns.plain(ps[0].lines))
    }

    @Test fun subscriptsJoinTheirLine() {
        // «CH4(g)»: «CH» on the baseline, «4(g)» 3 pt lower in a smaller font
        val gs = ltrWord("CH", 100f, 100f) + ltrWord("4(g)", 112f, 103f, fs = 8f)
        val lines = LayoutAnalyzer.buildLines(gs)
        assertEquals(1, lines.size)
        assertEquals("CH4(g)", lines[0].text.replace(" ", ""))
    }

    @Test fun tatweelKeepsAWordTogether() {
        // «مثـــال» drawn with three tatweel glyphs between «مث» and «ال»
        val gs = rtlWord("مثـــال", 300f, 100f)
        assertEquals("مثال", LayoutAnalyzer.buildLines(gs).single().text)
    }

    // ---------------------------------------------------------------- OCR text

    @Test fun invisibleWordsOverRealTextAreDropped() {
        val real = rtlLine(listOf("الكيمياء", "الحرارية"), 540f, 100f)
        // the same words again as an (invisible) OCR layer, slightly offset, plus one OCR word where the page has no text
        val dup = rtlLine(listOf("الكيمياء", "الحرارية"), 541f, 101f, fs = 13f, ocr = true)
        val fresh = rtlLine(listOf("كمية"), 540f, 300f, fs = 13f, ocr = true)
        val kept = OcrText.dropDuplicates(real + dup + fresh)
        assertEquals(real.size + fresh.size, kept.size)
        assertTrue(kept.filter { it.ocr }.all { it.baseline == 300f })
        val text = paragraphs(LayoutAnalyzer.analyze(page(kept))).joinToString("\n") { TextRuns.plain(it.lines) }
        assertEquals(1, Regex("الكيمياء").findAll(text).count())
    }

    @Test fun ocrWordsComeBackInLogicalOrderWithBrackets() {
        // what SearchablePdfWriter.visualWord writes: «(س)» → «(س)» left to right, «١)» → «(١», «كمية» reversed
        assertEquals("(س)", BidiText.ocrLogical("(س)"))
        assertEquals("١)", BidiText.ocrLogical("(١"))
        assertEquals("كمية", BidiText.ocrLogical("ةيمك"))
        assertEquals("٢٠٢٥م", BidiText.ocrLogical("م٢٠٢٥"))
        // a lam-alef ligature glyph of a shaped layer stays «لا», not «ال»
        assertEquals("لا", BidiText.ocrLogical(listOf("لا")))
        assertEquals("الأخرى", BidiText.ocrLogical(listOf("ى", "ر", "خ", "لأ", "ا")))
    }

    @Test fun ocrLinesGetOneSizeAndOneBaseline() {
        // an OCR line: every word at its own box height / bottom
        val gs = rtlWord("كمية", 540f, 100f, fs = 9f, ocr = true) + rtlWord("الحرارة", 510f, 101.5f, fs = 12f, ocr = true) + rtlWord("في", 455f, 100.5f, fs = 10f, ocr = true)
        val l = LayoutAnalyzer.buildLines(gs).single()
        assertEquals(3, l.words.size)
        assertEquals(1, l.words.map { it.fontSize }.toSet().size)
        assertEquals("كمية الحرارة في", l.text)
    }

    @Test fun userDocxJunkLinesAreDroppedRealLinesKept() {
        // lines of the user's Word file (text of tablet page 1: an older build re-typed its OCR words visibly)
        val junk = listOf("EEK", "KEK EEK", "EEK EEK", "KYE", "جح جح", "OOS:", "عوجححوصحوعوصحمومصي--", "@Jjlp", "٠ YEDOEDOE", "KXEEK", "| ! '")
        val real = listOf("الكيمياء الحراريسة : هي فرع من فروع علم الكيمياء يختص بدراسة التغيرات", "أشكال الطاقن الأخرى » ويمكن تحويل جميع الطاقات", "CO2(g) + 2H2O(l)")
        var y = 100f
        val gs = ArrayList<Glyph>()
        for (t in junk + real) {
            if (t.any { it.code in 0x0600..0x06FF }) gs += rtlLine(t.split(' '), 540f, y, ocr = true)
            else { var x = 60f; for (w in t.split(' ')) { gs += ltrWord(w, x, y, ocr = true); x += (w.length + 1) * 6f } }
            y += 30f
        }
        val lines = LayoutAnalyzer.buildLines(gs).map { it.text }
        for (t in real) assertTrue("kept: $t", lines.any { it.contains(t.split(' ').first()) })
        for (t in junk) assertFalse("dropped: $t", lines.any { it.trim() == t || it.contains("EEK") || it.contains("YEDOEDOE") || it.contains("Jjlp") })
        assertTrue(OcrText.junkWord("el—sasSJI"))
        assertFalse(OcrText.junkWord("Thermochemistry"))
        assertFalse(OcrText.junkWord("CH4"))
        assertFalse(OcrText.junkWord("KOH"))
    }

    @Test fun lonePiecesAreNoParagraphs() {
        fun para(t: String, ocr: Boolean) = LayoutAnalyzer.buildLines(rtlLine(t.split(' '), 540f, 100f, ocr = ocr))
        assertTrue(OcrText.isFragmentParagraph(para(":", false)))
        assertTrue(OcrText.isFragmentParagraph(para("١٢", true)))
        assertFalse(OcrText.isFragmentParagraph(para("م", false)))
        assertFalse(OcrText.isFragmentParagraph(para("حرارة احتراق", true)))
    }

    @Test fun oldRebuildIsRecognisedByItsStretchedWords() {
        val stretched = List(40) { OcrText.Mark(ourFont = true, tz = if (it % 5 == 0) 100f else 137f, invisible = false) }
        assertTrue(OcrText.ourVisibleLayer(stretched))
        val typeset = List(40) { OcrText.Mark(ourFont = true, tz = 100f, invisible = false) }
        assertFalse(OcrText.ourVisibleLayer(typeset))
        assertTrue(OcrText.isOcrFont("AAKBSF+NotoNaskhArabic-Regular", true))
        assertFalse(OcrText.isOcrFont("ABCDEE+PT Bold Heading", true))
    }

    @Test fun watermarkUnderTextIsNotExported() {
        val body = ArrayList<Glyph>()
        for (i in 0 until 20) body += rtlLine("هذه التحويلات سوف تقودنا إلى قانون بقاء الطاقة الذي ينص".split(' '), 540f, 220f + i * 18f)
        val watermark = PageImage(150f, 200f, 300f, 300f, ByteArray(4), "png")
        val photo = PageImage(150f, 650f, 200f, 150f, ByteArray(4), "png")
        val l = LayoutAnalyzer.analyze(page(body, listOf(watermark, photo)))
        val imgs = l.blocks.filterIsInstance<ImageBlock>()
        assertEquals(1, imgs.size)
        assertEquals(650f, imgs[0].image.top, 0.01f)
    }

    // ---------------------------------------------------------------- Word markup

    @Test fun scriptRunsSplitArabicFromLatin() {
        val r = BidiText.scriptRuns("المعطيات : كتلة CO2(g) + H2 = ١٤ جم", true)
        assertEquals(listOf(true, false, true), r.map { it.second })
        assertEquals("CO2(g) + H2", r[1].first.trim())
        assertEquals(listOf("abc def" to false), BidiText.scriptRuns("abc def", false))
    }

    private fun docx(flowing: Boolean, vararg pages: PageLayout): String = docxWith(flowing, false, *pages)

    private fun docxWith(flowing: Boolean, exact: Boolean, vararg pages: PageLayout): String {
        val bos = ByteArrayOutputStream()
        DocxBuilder(bos, "t", PageFurniture.NONE, flowing, exact = exact).use { b -> pages.forEach { b.addPage(it) }; b.finish() }
        ZipInputStream(bos.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "word/document.xml") return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("no document.xml")
    }

    @Test fun wordMarksArabicParagraphsAndOnlyArabicRunsRightToLeft() {
        val gs = rtlLine("المعطيات : كتلة".split(' '), 540f, 100f) + ltrWord("CO2(g)", 410f, 100f)
        val xml = docx(false, LayoutAnalyzer.analyze(page(gs)))
        val p = Regex("<w:p><w:pPr>.*?</w:p>").find(xml)!!.value
        assertTrue(p.contains("<w:bidi/>"))
        val runs = Regex("<w:r>(.*?)</w:r>").findAll(p).map { it.groupValues[1] }.toList()
        assertTrue(p, runs.any { it.contains("كتلة") })
        val arabic = runs.first { it.contains("كتلة") }
        assertTrue(p, runs.any { it.contains("CO2(g)") })
        val latin = runs.first { it.contains("CO2(g)") }
        assertTrue(arabic.contains("<w:rtl/>"))
        assertFalse(latin.contains("<w:rtl/>"))
    }

    @Test fun flowingWordHasNoPageBreaksAndLayoutWordHasOneBetweenPages() {
        val a = LayoutAnalyzer.analyze(page(rtlLine(listOf("صفحة", "أولى"), 540f, 100f)))
        val b = LayoutAnalyzer.analyze(page(rtlLine(listOf("صفحة", "ثانية"), 540f, 100f)))
        assertEquals(0, Regex("w:type=\"page\"").findAll(docx(true, a, b)).count())
        val keep = docx(false, a, b)
        assertEquals(1, Regex("w:type=\"page\"").findAll(keep).count())
        // no page break before the first page
        assertTrue(keep.indexOf("w:type=\"page\"") > keep.indexOf("أولى"))
    }

    @Test fun preciseLayoutPutsParagraphsInFramesAtTheirPlace() {
        // row 46: an English paragraph at (100, 200) and an Arabic one at the right
        val gs = ltrWord("Invoice", 100f, 210f) + rtlLine("المجموع".split(' '), 540f, 400f)
        val l = LayoutAnalyzer.analyze(page(gs))
        val xml = docxWith(true, true, l, l)       // exact wins over flowing: pages are kept
        val frames = Regex("""<w:framePr [^>]*w:x="(\d+)" w:y="(\d+)"""").findAll(xml).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        assertTrue(frames.size >= 4)                                       // two blocks on each of the two pages
        assertTrue(frames.any { (x, _) -> x in 1900..2100 })                // x = 100 pt = 2000 twips
        assertTrue(xml.contains("<w:br w:type=\"page\"/>"))
        // pPr children keep the schema order: framePr before bidi before spacing
        val ar = Regex("<w:p><w:pPr>(.*?)</w:pPr>").findAll(xml).map { it.groupValues[1] }.first { it.contains("<w:bidi/>") }
        assertTrue(ar, ar.indexOf("framePr") < ar.indexOf("<w:bidi/>") && ar.indexOf("<w:bidi/>") < ar.indexOf("<w:spacing"))
        assertFalse(docx(false, l).contains("framePr"))
    }
}
