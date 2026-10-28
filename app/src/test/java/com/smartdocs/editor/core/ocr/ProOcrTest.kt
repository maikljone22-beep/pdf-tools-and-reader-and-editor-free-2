package com.smartdocs.editor.core.ocr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRO_OCR_r5: PP-OCRv5 post-processing, the Tesseract + AI word choice, the lexicon and the cloud answer. */
class ProOcrTest {

    private fun chars(s: String) = s.mapIndexed { i, c -> ProOcrCore.Ch(c.toString(), i * 10f, 0.9f) }
    private fun text(l: List<ProOcrCore.Ch>) = l.joinToString("") { it.c }

    @Test fun ctcCollapsesRepeatsAndBlanksAndMapsTheSpaceClass() {
        val dict = listOf("a", "b")                // classes: 0 blank, 1 a, 2 b, 3 space
        // steps: a a blank a b space b
        val rows = listOf(1, 1, 0, 1, 2, 3, 2)
        val p = FloatArray(rows.size * 4).also { f -> rows.forEachIndexed { t, k -> f[t * 4 + k] = 0.8f } }
        val out = ProOcrCore.ctcDecode(p, rows.size, 4, dict, 100, 70)
        assertEquals("aab b", text(out))
        assertEquals(105f, out.first().x, 0.01f)     // x centre of step 0 in a 70-px crop at x 100
    }

    @Test fun dictParsingKeepsEveryLine() {
        assertEquals(listOf("!", "٠", "ا"), ProOcrCore.parseDict("﻿!\r\n٠\nا\n"))
    }

    @Test fun visualArabicBecomesLogicalNumbersStayLeftToRight() {
        // pixels left to right of «مول ٢٨٣»: the number, a space, then the letters of مول drawn right to left
        assertEquals("مول ٢٨٣", text(ProOcrCore.visualToLogical(chars("٢٨٣ لوم"))))
        // «(س) كيلوجول» : brackets are mirrored, Latin keeps its order
        assertEquals("(س)", text(ProOcrCore.visualToLogical(chars("(س)"))))
        assertEquals("من CO", text(ProOcrCore.visualToLogical(chars("CO نم"))))
        // decimal number with its point
        assertEquals("قيمة = ٢٢٢.٥", text(ProOcrCore.visualToLogical(chars("٢٢٢.٥ = ةميق"))))
        // no Arabic letter: the run is left as read (formulas, numbers)
        assertEquals("٢٢٢.٥ =", text(ProOcrCore.visualToLogical(chars("٢٢٢.٥ ="))))
        // Latin-only text is untouched
        assertEquals("KJ/mole", text(ProOcrCore.visualToLogical(chars("KJ/mole"))))
    }

    @Test fun dbBoxesFindsComponentsGrowsAndScalesThem() {
        val w = 20; val h = 10
        val p = FloatArray(w * h)
        for (y in 2..5) for (x in 2..7) p[y * w + x] = 0.9f      // 6×4 blob
        for (y in 3..6) for (x in 12..17) p[y * w + x] = 0.4f    // mean below box threshold 0.5 -> dropped
        val b = ProOcrCore.dbBoxes(p, w, h, 40, 20)
        assertEquals(1, b.size)
        // area 24, perimeter 2*(6+4)=20, unclip offset 24*1.6/20 = 1.92 px of the map, map->image scale 2
        assertEquals(0, b[0].x0); assertEquals(0, b[0].y0)
        assertEquals(20, b[0].x1); assertEquals(16, b[0].y1)
    }

    @Test fun detectionSizeIsLimitedAndAlignedTo32() {
        assertEquals(1696 to 2400, ProOcrCore.detSize(2474, 3500, 2400))
        assertEquals(992 to 704, ProOcrCore.detSize(1000, 700, 2400))   // never scaled up
        assertEquals(48, ProOcrCore.recWidth(100, 100))
        assertEquals(3200, ProOcrCore.recWidth(100000, 50))
    }

    private fun seg(x0: Int, y0: Int, x1: Int, y1: Int, visual: String) = ProOcrCore.Seg(x0, y0, x1, y1,
        visual.mapIndexed { i, c -> ProOcrCore.Ch(c.toString(), x0 + (i + 0.5f) * (x1 - x0) / visual.length, 0.95f) })

    @Test fun twoColumnsAreReadRightColumnFirst() {
        // right column (x 1200..1900) and left column (x 100..800), two lines each
        val segs = listOf(seg(100, 100, 800, 150, "١ رسيأ"), seg(1200, 100, 1900, 150, "١ نميأ"),
            seg(100, 200, 800, 250, "٢ رسيأ"), seg(1200, 200, 1900, 250, "٢ نميأ"))
        val w = ProOcrCore.words(segs)
        assertEquals(listOf("أيمن", "١", "أيمن", "٢", "أيسر", "١", "أيسر", "٢"), w.map { it.text })
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3), w.map { it.line })
        assertTrue(w.all { it.conf > 90f })
    }

    @Test fun segmentsOfOneRowJoinIntoOneLine() {
        val segs = listOf(seg(100, 100, 400, 150, "CO"), seg(450, 100, 900, 150, "ةلتك"))
        val w = ProOcrCore.words(segs)
        assertEquals(listOf("كتلة", "CO"), w.map { it.text })
        assertEquals(setOf(0), w.map { it.line }.toSet())
    }

    // ---- OcrEnsemble: real word boxes of book page 78 (Tesseract ara+eng + refine + cleanup vs PP-OCRv5) ----

    private val lex = OcrLexicon.fromText("كتلة ضرب طرفين وسطين جرام مول كيلوجول عند")
    private fun t(s: String, l: Int, tp: Int, r: Int, b: Int, c: Float, line: Int = 0) = OcrRefine.Tok(s, l, tp, r, b, c, line)

    @Test fun digitsReadAsLatinLettersTakeTheAiReading() {
        // Tesseract «YAY» (conf 43) where the page prints «٢٨٣»; PP-OCR reads ٢٨٣ (0.897)
        val out = OcrEnsemble.combine(listOf(t("YAY", 781, 1258, 842, 1287, 43.2f)), listOf(t("٢٨٣", 773, 1245, 855, 1306, 89.7f)), lex)
        assertEquals(listOf("٢٨٣"), out.map { it.text })
    }

    @Test fun lostTaMarbutaTakesTheKnownWord() {
        // Tesseract «: كلي» (44) for «كتلة»; PP-OCR «كتلة» (0.989)
        val out = OcrEnsemble.combine(listOf(t(": كلي", 1990, 416, 2128, 455, 44.4f)), listOf(t("كتلة", 1991, 407, 2098, 465, 98.9f)), lex)
        assertEquals(listOf("كتلة"), out.map { it.text })
    }

    @Test fun confusedDotsTakeTheKnownWord() {
        // the box of «ضرب» on page 78 (900,704 87×40); Tesseract read «صرب» on the device build (OCR_QUALITY_r5)
        val out = OcrEnsemble.combine(listOf(t("صرب", 900, 704, 987, 744, 79.8f)), listOf(t("ضرب", 910, 695, 987, 753, 99.8f)), lex)
        assertEquals(listOf("ضرب"), out.map { it.text })
    }

    @Test fun aConfidentKnownTesseractWordIsKept() {
        val out = OcrEnsemble.combine(listOf(t("وعند", 1003, 705, 1089, 742, 92f)), listOf(t("وعتد", 1000, 700, 1090, 745, 60f)), lex)
        assertEquals(listOf("وعند"), out.map { it.text })
    }

    @Test fun oneAiWordOverSeveralTesseractPiecesReplacesThemOnce() {
        val tess = listOf(t("كت", 1000, 100, 1040, 140, 30f), t("ل", 1042, 100, 1060, 140, 20f), t("جرام", 800, 100, 900, 140, 95f))
        val out = OcrEnsemble.combine(tess, listOf(t("كتلة", 995, 98, 1062, 142, 97f)), lex)
        assertEquals(listOf("كتلة", "جرام"), out.map { it.text })
    }

    @Test fun wordsOnlyTheAiSawAreAddedWhenItIsSure() {
        val tess = listOf(t("جرام", 800, 100, 900, 140, 95f, line = 0))
        val pp = listOf(t("١٦", 950, 100, 990, 140, 96f, line = 0), t("xx", 10, 500, 40, 540, 50f, line = 3))
        val out = OcrEnsemble.combine(tess, pp, lex)
        assertEquals(listOf("جرام", "١٦"), out.map { it.text })
        assertTrue(out[1].line > 0)
    }

    @Test fun withoutAiWordsTesseractIsUnchanged() {
        val tess = listOf(t("CH4(g)", 10, 10, 90, 40, 88f))
        assertEquals(tess, OcrEnsemble.combine(tess, emptyList(), lex))
    }

    @Test fun lexiconKnowsArticleAndClitics() {
        assertTrue(lex.known("الكتلة")); assertTrue(lex.known("وكتلة")); assertTrue(lex.known("كتلـة")); assertTrue(lex.known("كيلوجول"))
        assertFalse(lex.known("كتل")); assertFalse(lex.known(""))
        assertEquals(listOf("كيلوجول", "مول"), OcrLexicon.arabicWords("كيلوجول/مول"))
        assertEquals(1f, OcrEnsemble.knownShare(listOf("٢٨٣", "KJ/mole"), lex))
    }

    @Test fun cloudAnswerBecomesWordsInPagePixels() {
        val o = Json.parseToJsonElement("""{"provider":"google","width":1300,"height":1750,"words":[
            {"text":"كتلة","x0":100,"y0":50,"x1":160,"y1":80,"conf":97,"line":0},
            {"text":"۸۹۰","x0":20,"y0":50,"x1":60,"y1":80,"conf":91,"line":0},
            {"text":"  ","x0":0,"y0":0,"x1":1,"y1":1,"conf":10,"line":1}],"text":"كتلة ۸۹۰"}""").jsonObject
        val w = CloudOcr.parse(o, 2f)!!
        assertEquals(listOf("كتلة", "٨٩٠"), w.map { it.text })        // Persian digits folded
        assertEquals(200, w[0].left); assertEquals(160, w[0].bottom)
        assertNull(CloudOcr.parse(Json.parseToJsonElement("""{"provider":"vision","words":[],"text":"x"}""").jsonObject, 1f))
        assertEquals(listOf("ar", "en"), CloudOcr.languages("ara+eng"))
    }

    @Test fun engineChoiceIsSavedWithTheOcrOptions() {
        val t = OcrTuning(engine = OcrEngineMode.CLOUD)
        assertEquals(t, OcrTuning.fromJson(t.toJson()))
        assertEquals(OcrEngineMode.AUTO, OcrTuning.fromJson("""{"fast":false}""").engine)
    }
}
