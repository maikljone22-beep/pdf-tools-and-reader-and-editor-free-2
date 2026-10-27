package com.smartdocs.editor.core.fillsign2

import com.smartdocs.editor.core.pdf.FillSignKeys
import com.smartdocs.editor.core.sign.PagingSeal
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** nitro-fillsign2: "Apply All Signatures" flattens only signature stamps; a standalone paging seal can be removed again. */
class FillSign2PdfTest {

    private fun stamp(doc: PDDocument, signature: Boolean): PDAnnotationRubberStamp = PDAnnotationRubberStamp().apply {
        rectangle = PDRectangle(100f, 100f, 80f, 30f)
        val ap = PDAppearanceStream(doc).apply { bBox = PDRectangle(0f, 0f, 80f, 30f); resources = PDResources() }
        PDPageContentStream(doc, ap).use { cs -> cs.addRect(0f, 0f, 80f, 30f); cs.fill() }
        appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }
        if (signature) cosObject.setBoolean(FillSignKeys.SIG, true)
    }

    @Test fun applyAllFlattensOnlySignatureStamps() {
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            page.annotations = mutableListOf(stamp(doc, true), stamp(doc, false), stamp(doc, true))
            assertEquals(2, SignatureFlatten.flattenSignatures(doc))
            val left = page.annotations
            assertEquals(1, left.size)
            assertFalse(SignatureFlatten.isSignatureStamp(left[0]))
            // the page now draws the two appearances (one appended content stream)
            assertTrue(page.cosObject.getDictionaryObject(COSName.CONTENTS) != null)
            assertEquals(0, SignatureFlatten.flattenSignatures(doc))
        }
    }

    private fun contentStreams(page: PDPage): List<COSStream> = when (val c = page.cosObject.getDictionaryObject(COSName.CONTENTS)) {
        is COSStream -> listOf(c)
        is COSArray -> (0 until c.size()).mapNotNull { c.getObject(it) as? COSStream }
        else -> emptyList()
    }

    @Test fun pagingSealIsMarkedAndRemovable() {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { doc ->
            repeat(3) { doc.addPage(PDPage()) }
            // existing content on page 1
            PDPageContentStream(doc, doc.getPage(0)).use { cs -> cs.addRect(10f, 10f, 50f, 50f); cs.fill() }
            for (i in 0 until 2) PagingSeal.appendMarked(doc, doc.getPage(i)) { cs -> cs.addRect(500f, 300f, 20f, 60f); cs.fill() }
            assertEquals(listOf(0, 1), PagingSeal.sealedPages(doc))
            doc.save(bytes)
        }
        // the marks survive a save / load round trip, and removing them restores the original streams
        PDDocument.load(bytes.toByteArray()).use { doc ->
            val first = doc.getPage(0)
            assertEquals(3, contentStreams(first).size)              // q + original + seal
            assertEquals(2, PagingSeal.removeFrom(doc))
            assertEquals(1, contentStreams(first).size)
            assertFalse(contentStreams(first)[0].getBoolean(PagingSeal.MARK, false))
            assertEquals(0, contentStreams(doc.getPage(1)).size)
            assertEquals(emptyList<Int>(), PagingSeal.sealedPages(doc))
            assertEquals(0, PagingSeal.removeFrom(doc))
        }
    }
}
