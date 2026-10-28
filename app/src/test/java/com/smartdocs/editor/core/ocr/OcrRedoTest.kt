package com.smartdocs.editor.core.ocr

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FINAL_CHECK K2 "redo OCR / replace the text layer": only invisible text objects go, visible content stays. */
class OcrRedoTest {

    private fun op(n: String) = Operator.getOperator(n)
    private fun i(v: Int) = COSInteger.get(v.toLong())
    private fun s(t: String) = COSString(t)
    private val f1 = COSName.getPDFName("F1")

    private fun shown(tokens: List<Any?>) = tokens.filterIsInstance<COSString>().map { it.string }

    @Test fun anInvisibleTextObjectGoesItsTextStateStays() {
        val tokens = listOf(
            op("BT"), f1, i(12), op("Tf"), i(3), op("Tr"), i(10), i(10), op("Td"), s("old ocr"), op("Tj"), op("ET"),
            op("BT"), i(0), op("Tr"), i(20), i(20), op("Td"), s("visible"), op("Tj"), op("ET"),
        )
        val (out, n) = OcrRedo.stripInvisible(tokens)
        assertEquals(1, n)
        assertEquals(listOf("visible"), shown(out))
        // the font and rendering mode set inside the removed object are still set for what follows
        val names = out.filterIsInstance<Operator>().map { it.name }
        assertEquals(listOf("Tf", "Tr", "BT", "Tr", "Td", "Tj", "ET"), names)
    }

    @Test fun anObjectMixingVisibleAndInvisibleTextIsKept() {
        val tokens = listOf(op("BT"), i(3), op("Tr"), s("a"), op("Tj"), i(0), op("Tr"), s("b"), op("Tj"), op("ET"))
        val (out, n) = OcrRedo.stripInvisible(tokens)
        assertEquals(0, n); assertEquals(tokens, out)
    }

    @Test fun renderingModeFollowsSaveAndRestore() {
        // «q 3 Tr Q»: the mode is restored to 0, so the text after it is visible and stays
        val tokens = listOf(op("q"), i(3), op("Tr"), op("Q"), op("BT"), s("vis"), op("Tj"), op("ET"))
        val (out, n) = OcrRedo.stripInvisible(tokens)
        assertEquals(0, n); assertEquals(listOf("vis"), shown(out))
        // and an invisible mode set before an object of a later content stream is carried over
        val st = OcrRedo.State()
        OcrRedo.stripInvisible(listOf(i(3), op("Tr")), st)
        val (out2, n2) = OcrRedo.stripInvisible(listOf(op("BT"), s("layer"), op("TJ"), op("ET")), st)
        assertEquals(1, n2); assertTrue(shown(out2).isEmpty())
    }

    @Test fun aPageLosesItsInvisibleLayerAndKeepsItsDrawing() {
        PDDocument().use { d ->
            val page = PDPage(); d.addPage(page)
            val drawing = "q 1 0 0 RG 10 10 100 50 re S Q BT /F1 12 Tf 72 700 Td (visible) Tj ET\n"
            val layer = "BT 3 Tr /F1 10 Tf 72 600 Td (ocr words) Tj ET\n"
            page.setContents(listOf(PDStream(d, drawing.byteInputStream()), PDStream(d, layer.byteInputStream())))
            assertEquals(1, OcrRedo.removeInvisibleText(page))
            val text = page.contentStreams.asSequence().joinToString("\n") { it.createInputStream().readBytes().toString(Charsets.ISO_8859_1) }
            assertTrue(text.contains("(visible) Tj"))
            assertTrue(text.contains("re"))
            assertFalse(text.contains("ocr words"))
            assertEquals(0, OcrRedo.removeInvisibleText(page))
        }
    }
}
