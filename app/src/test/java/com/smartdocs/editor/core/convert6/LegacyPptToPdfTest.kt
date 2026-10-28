package com.smartdocs.editor.core.convert6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-convert: PowerPoint 97-2003 (.ppt) input for PowerPoint → PDF (row 19). */
class LegacyPptToPdfTest {
    @Test fun pptIsAcceptedByTheSlideCards() {
        assertTrue(Kind6.PPT in Cv6Catalog.byId("ppt_pdf")!!.inputs)
        assertTrue(Kind6.PPT in Cv6Catalog.byId("any_pdf")!!.inputs)
        assertTrue(Opt.BOOKMARKS in Cv6Catalog.byId("ppt_pdf")!!.opts)
    }

    @Test fun slideTextsBecomeOnePagePerSlide() {
        // the shape PptText.slides returns: (title, body lines) per slide
        val doc = ToPdf6.slidesFlow("deck", listOf("Agenda" to listOf("one", "two"), "الخلاصة" to listOf("نقطة")))
        val breaks = doc.blocks.count { it is FBlock.PageBreak }
        assertEquals(1, breaks)
        val headings = doc.blocks.filterIsInstance<FBlock.Heading>()
        assertEquals(2, headings.size)
        assertTrue(headings[1].rtl)
    }
}
