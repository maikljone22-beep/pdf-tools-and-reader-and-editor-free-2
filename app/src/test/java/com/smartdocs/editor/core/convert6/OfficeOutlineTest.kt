package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.office.Block
import com.smartdocs.editor.core.office.DocModel
import com.smartdocs.editor.core.office.Inline
import com.smartdocs.editor.core.office.Paragraph
import com.smartdocs.editor.core.office.PresentationModel
import com.smartdocs.editor.core.office.RunStyle
import com.smartdocs.editor.core.office.SlideModel
import com.smartdocs.editor.core.office.SlideParagraph
import com.smartdocs.editor.core.office.SlideShape
import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-convert: Office → PDF bookmarks (Word headings, sheets, slides). */
class OfficeOutlineTest {
    private fun para(text: String, heading: Int) = Block.Para(Paragraph(listOf(Inline.Text(text, RunStyle())), heading = heading))

    @Test fun wordHeadingsAreFoundInOrder() {
        val model = DocModel(listOf(para("Report", -1), para("body", 0), para("Introduction", 1), para("Scope", 3), para("Results", 1), para("Empty", 2)))
        val titles = OfficeOutline.docxTitles(model)
        assertEquals(listOf(1 to "Report", 1 to "Introduction", 3 to "Scope", 1 to "Results", 2 to "Empty"), titles.map { it.level to it.text })
        // "Report" and "Introduction" on page 0, "Scope" on page 1, "Results" on page 2; "Empty" never printed
        val pages = listOf("Report\nIntroduction\ntext", "more text\nScope\n…", "Results\nend")
        val found = OfficeOutline.locate(pages, titles)
        assertEquals(listOf("Report" to 0, "Introduction" to 0, "Scope" to 1, "Results" to 2), found.map { it.first.text to it.second })
        // levels made contiguous: Scope (3) under Introduction (1) becomes level 1
        assertEquals(listOf(0, 0, 1, 0), OfficeOutline.bookmarks(found).map { it.level })
    }

    @Test fun matchingIgnoresSpacesDiacriticsAndPresentationForms() {
        assertEquals(OfficeOutline.norm("المقدمة"), OfficeOutline.norm("ﺍﻟﻤﻘﺪﻣﺔ"))          // presentation forms (shaped by the printer)
        assertEquals(OfficeOutline.norm("الْمُقَدِّمَة"), OfficeOutline.norm("المقدمة"))   // harakat
        assertEquals("annualreport", OfficeOutline.norm("Annual  Report"))
        val found = OfficeOutline.locate(listOf("x", "ﺍﻟﻤﻘﺪﻣﺔ y"), listOf(OfficeOutline.Title(1, "المقدمة")))
        assertEquals(1, found.single().second)
    }

    @Test fun slideTitlesTakeTheTopTextBox() {
        fun tb(y: Long, text: String) = SlideShape.TextBox(0, y, 100, 10, listOf(SlideParagraph(listOf(Inline.Text(text, RunStyle())))))
        val model = PresentationModel(listOf(
            SlideModel(100, 100, listOf(tb(50, "Body"), tb(5, "Agenda"))),
            SlideModel(100, 100, emptyList()),
        ))
        assertEquals(listOf("Agenda", "Slide 2"), OfficeOutline.slideTitles(model) { "Slide $it" })
    }

    @Test fun bookmarkSwitchOnTheOfficeAndWebCards() {
        listOf("word_pdf", "excel_pdf", "ppt_pdf", "url_pdf", "html_pdf").forEach { id ->
            assertEquals(id, true, Opt.BOOKMARKS in Cv6Catalog.byId(id)!!.opts)
        }
        assertEquals(true, Cv6Opts().bookmarks)
    }
}
