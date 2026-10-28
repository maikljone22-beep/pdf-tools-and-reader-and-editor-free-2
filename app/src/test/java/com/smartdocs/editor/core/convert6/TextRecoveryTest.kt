package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.ocr.MixedPageOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-convert: PDF → Word text recovery modes (row 51). */
class TextRecoveryTest {
    @Test fun allPagesModeRecognisesTheWholeRange() {
        val plan = MixedPageOcr.Plan(listOf(2), emptyMap())
        assertEquals(listOf(0, 1, 2, 3), PdfFlow6.recoverAll(plan, listOf(3, 0, 1, 2, 2)).pages)
        val docx = Cv6Catalog.byId("pdf_docx")!!
        assertTrue(Opt.TEXT_RECOVERY in docx.opts && Opt.OCR_LANG in docx.opts)
        // modes: off = no OCR, problem pages = autoOcr, all pages = autoOcr + recoverAllPages (kept with the card's defaults)
        val all = Cv6Opts(autoOcr = true, recoverAllPages = true)
        assertTrue(all.recoverAllPages && all.autoOcr)
    }
}
