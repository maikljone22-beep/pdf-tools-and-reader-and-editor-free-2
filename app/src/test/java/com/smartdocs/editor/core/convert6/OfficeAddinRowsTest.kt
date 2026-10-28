package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.office.PptxNotes
import com.smartdocs.editor.core.office.PresentationModel
import com.smartdocs.editor.core.office.SlideModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-convert-2: Office add-in settings (row 103): speaker notes, hidden slides, slide range, cell comments. */
class OfficeAddinRowsTest {
    @Test fun pptxNotesAndHiddenFlag() {
        val notes = """<p:notes><p:cSld><p:spTree>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="sldImg"/></p:nvPr></p:nvSpPr></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Say hello</a:t></a:r></a:p><a:p><a:r><a:t>then &amp; demo</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="sldNum"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>3</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:notes>"""
        assertEquals("Say hello\nthen & demo", PptxNotes.text(notes))
        assertNull(PptxNotes.text("<p:notes/>"))
        assertTrue(PptxNotes.hidden("""<?xml version="1.0"?><p:sld xmlns:p="x" show="0"><p:cSld/></p:sld>"""))
        assertFalse(PptxNotes.hidden("""<p:sld xmlns:p="x"><p:cSld/></p:sld>"""))
    }

    @Test fun slideRangeAndHiddenSlides() {
        val m = PresentationModel(listOf(SlideModel(1, 1, emptyList(), notes = "a"), SlideModel(1, 1, emptyList(), hidden = true, notes = "b"), SlideModel(1, 1, emptyList(), notes = "c")))
        assertEquals(listOf("a", "c"), SlideSelect.select(m, "", includeHidden = false).slides.map { it.notes })
        assertEquals(listOf("a", "b", "c"), SlideSelect.select(m, "", includeHidden = true).slides.map { it.notes })
        assertEquals(listOf("c"), SlideSelect.select(m, "2-3", includeHidden = false).slides.map { it.notes })
        assertEquals(listOf("b"), SlideSelect.select(m, "2", includeHidden = false).slides.map { it.notes })     // only a hidden slide asked for: kept
    }

    @Test fun cellCommentsParse() {
        val xml = """<comments xmlns="x"><authors><author>Sara</author><author>Ali</author></authors><commentList>
            <comment ref="B3" authorId="1"><text><r><rPr/><t>Ali:</t></r><r><t xml:space="preserve"> check &lt;total&gt;</t></r></text></comment>
            <comment ref="AA10" authorId="0"><text><t>ok</t></text></comment>
            <comment ref="C1" authorId="0"><text><t></t></text></comment></commentList></comments>"""
        val c = XlsxComments.parse(xml)
        assertEquals(2, c.size)
        assertEquals(XlsxComments.Comment("B3", 2, 1, "Ali", "Ali: check <total>"), c[0])
        assertEquals(26, c[1].col); assertEquals(9, c[1].row); assertEquals("Sara", c[1].author)
        assertEquals(0 to 0, XlsxComments.cellRef("\$A\$1")); assertNull(XlsxComments.cellRef("1A"))
        assertEquals(listOf("Sales" to "rId1", "R&D" to "rId2"),
            XlsxComments.sheets("""<sheets><sheet name="Sales" sheetId="1" r:id="rId1"/><sheet name="R&amp;D" sheetId="2" r:id="rId2"/></sheets>"""))
    }
}
