package com.smartdocs.editor.core.convert

import com.smartdocs.editor.core.convert6.Cv6Catalog
import com.smartdocs.editor.core.convert6.Opt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-convert-2: PDF → Text line width / paragraph mode and header / footer handling (s6). */
class TextOutputOptionsTest {
    @Test fun wrapKeepsWordsWhole() {
        assertEquals("one two\nthree\nfour five", HtmlTxtBuilder.wrap("one two three four five", 9))
        assertEquals("a\nverylongword\nb", HtmlTxtBuilder.wrap("a verylongword b", 4))
        assertEquals("x y\nz", HtmlTxtBuilder.wrap("x y\nz", 80))                 // existing breaks stay
    }

    @Test fun optionsOnTheCards() {
        listOf("pdf_docx", "ocr_pdf_docx", "pdf_html", "pdf_txt", "ocr_pdf_txt").forEach { assertTrue(it, Opt.HEADERS in Cv6Catalog.byId(it)!!.opts) }
        assertTrue(Opt.TEXT_LAYOUT in Cv6Catalog.byId("pdf_txt")!!.opts)
        val o = ConvertOptions(format = TargetFormat.TXT, pages = listOf(0), txtKeepLines = true, txtWrap = 72, dropFurniture = true)
        assertTrue(o.txtKeepLines && o.dropFurniture); assertEquals(72, o.txtWrap)
    }
}
