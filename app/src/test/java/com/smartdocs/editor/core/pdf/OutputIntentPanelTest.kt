package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-compliance: the Standards panel lists the output intents (FINAL_CHECK). */
class OutputIntentPanelTest {
    @Test fun outputIntentsAreListed() {
        PDDocument().use { d ->
            d.addPage(PDPage())
            assertEquals(emptyList<String>(), NpfDocPanels.outputIntents(d))
            val icc = d.document.createCOSStream().apply { setInt(COSName.N, 3) }
            val a = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("OutputIntent")); setName(COSName.S, "GTS_PDFA1")
                setItem(COSName.getPDFName("OutputConditionIdentifier"), COSString("sRGB IEC61966-2.1")); setItem(COSName.getPDFName("DestOutputProfile"), icc)
            }
            val x = COSDictionary().apply { setName(COSName.S, "GTS_PDFX"); setItem(COSName.getPDFName("Info"), COSString("FOGRA39")) }
            d.documentCatalog.cosObject.setItem(COSName.getPDFName("OutputIntents"), COSArray().apply { add(a); add(x) })
            assertEquals(listOf("GTS_PDFA1 · sRGB IEC61966-2.1 · ICC (3)", "GTS_PDFX · FOGRA39 · no ICC"), NpfDocPanels.outputIntents(d))
            assertEquals(2, NpfDocPanels.standards(d).outputIntents.size)
        }
    }
}
