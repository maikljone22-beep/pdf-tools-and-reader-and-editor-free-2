package com.smartdocs.editor.core.ocr

import com.smartdocs.editor.core.pdf.OcrLayerCleanup
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The invisible OCR layer (ARABIC_COPY_r5): what is written for PDFium, line grouping, and recognising our old layer. */
class OcrTextLayerTest {

    @Test fun invisibleWordsAreBaseLettersWithoutControls() {
        // presentation forms from the recogniser are folded, tatweel and bidi marks dropped
        assertEquals("محمد", SearchablePdfWriter.invisibleArabic("ﻣﺤﻤﺪ"))
        assertEquals("الحرارة", SearchablePdfWriter.invisibleArabic("الحـــرارة\u200F"))
    }

    @Test fun visualOrderForPdfium() {
        assertEquals("ةدحاو", SearchablePdfWriter.visualWord("واحدة"))
        assertEquals("(ةددحم)", SearchablePdfWriter.visualWord("(محددة)"))   // reversed + brackets mirrored
        assertEquals("م٢٠٢٥", SearchablePdfWriter.visualWord("٢٠٢٥م"))        // digits keep their order
    }

    /** Tesseract word boxes (left, top, right, bottom px) of the header line of page 59, in Tesseract's reading order. */
    private val HEADER = listOf(
        intArrayOf(2240, 148, 2331, 188), intArrayOf(2198, 147, 2227, 194), intArrayOf(2061, 148, 2185, 187),
        intArrayOf(2037, 168, 2048, 171), intArrayOf(1938, 148, 2024, 179), intArrayOf(1825, 148, 1925, 180),
        intArrayOf(1700, 148, 1813, 194), intArrayOf(1184, 139, 1343, 179), intArrayOf(1007, 139, 1169, 184),
        intArrayOf(648, 142, 775, 189), intArrayOf(512, 141, 633, 189), intArrayOf(485, 141, 498, 183),
        intArrayOf(308, 137, 470, 190), intArrayOf(141, 138, 293, 190),
        // next line («السعة الحرارية Capacity Heat …»)
        intArrayOf(2186, 262, 2290, 300), intArrayOf(2019, 262, 2170, 300),
    )

    @Test fun wordsAreGroupedIntoLinesWrittenLeftToRight() {
        val lines = SearchablePdfWriter.lineIndexes(HEADER)
        assertEquals(2, lines.size)
        assertEquals(listOf(13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0), lines[0])   // left to right = reverse reading order
        assertEquals(listOf(15, 14), lines[1])
    }

    @Test fun lineBaselineKeepsSmallMarksOnTheLine() {
        // the 3-px «-» (bottom 171) on a line whose median bottom is 187 and median height 40: written on the line,
        // else PDFium breaks the line there («المرشد في الكيمياء» ended up after the rest of the header)
        assertEquals(187, SearchablePdfWriter.lineBaseline(168, 171, 187, 40))
        assertEquals(187, SearchablePdfWriter.lineBaseline(147, 194, 187, 40))
        assertEquals(340, SearchablePdfWriter.lineBaseline(300, 340, 187, 40))   // clearly another line: own bottom
    }

    private fun tokens(s: String) = PDFStreamParser(s.toByteArray(Charsets.ISO_8859_1)).also { it.parse() }.tokens
    private val kind: (COSName) -> Int = { n -> when (n.name) { "F1" -> 1; "F2" -> 0; else -> -1 } }

    @Test fun recognisesOnlyOurInvisibleLayer() {
        // SearchablePdfWriter's appended stream (PDPageContentStream APPEND + resetContext starts with Q)
        val ours = "Q\nBT\n3 Tr\n/F1 12.5 Tf\n140 Tz\n1 0 0 1 120 700 Tm\n<00410042> Tj\n/F2 10 Tf\n1 0 0 1 60 700 Tm\n(Heat) Tj\nET\n"
        assertTrue(OcrLayerCleanup.isOurLayer(tokens(ours), kind))
        // older builds: shaped run inside an /ActualText span
        val old = "Q\nBT\n3 Tr\n/F1 12 Tf\n1 0 0 1 120 700 Tm\n/Span <</ActualText (x)>> BDC\n<0041> Tj\nEMC\nET\n"
        assertTrue(OcrLayerCleanup.isOurLayer(tokens(old), kind))
        assertFalse(OcrLayerCleanup.isOurLayer(tokens(ours.replace("3 Tr", "0 Tr")), kind))            // visible text
        assertFalse(OcrLayerCleanup.isOurLayer(tokens(ours.replace("/F1 12.5", "/F9 12.5")), kind))    // another font
        assertFalse(OcrLayerCleanup.isOurLayer(tokens("q 0 0 1 rg 10 10 100 20 re f Q\n$ours"), kind)) // paints
        assertFalse(OcrLayerCleanup.isOurLayer(tokens("Q\nBT\n3 Tr\n/F2 10 Tf\n(Heat) Tj\nET\n"), kind))  // Helvetica only
    }
}
