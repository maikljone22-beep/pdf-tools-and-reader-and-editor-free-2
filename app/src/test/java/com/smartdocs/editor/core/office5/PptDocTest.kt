package com.smartdocs.editor.core.office5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PptDocTest {
    private val P = "xmlns:a=\"${PptDoc.A_NS}\" xmlns:r=\"${OPackage.R_NS}\" xmlns:p=\"${PptDoc.P_NS}\""
    private val RELS = "xmlns=\"${OPackage.REL_NS}\""
    private val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun slideXml(text: String) = "<p:sld $P><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>" +
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/>" +
        "<p:txBody><a:bodyPr/><a:p><a:r><a:rPr lang=\"en-US\" b=\"1\" sz=\"3200\"><a:solidFill><a:schemeClr val=\"accent1\"/></a:solidFill></a:rPr><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp>" +
        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"

    private fun sample(): PptDoc {
        val parts = linkedMapOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/ppt/slides/slide1.xml\" ContentType=\"${PptDoc.CT_SLIDE}\"/><Override PartName=\"/ppt/slides/slide2.xml\" ContentType=\"${PptDoc.CT_SLIDE}\"/>" +
                "<Override PartName=\"/ppt/notesSlides/notesSlide1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml\"/></Types>",
            "_rels/.rels" to "<Relationships $RELS><Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>",
            "ppt/presentation.xml" to "<p:presentation $P><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/><p:sldId id=\"257\" r:id=\"rId3\"/></p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to "<Relationships $RELS><Relationship Id=\"rId1\" Type=\"$REL/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/><Relationship Id=\"rId2\" Type=\"${PptDoc.REL_SLIDE}\" Target=\"slides/slide1.xml\"/><Relationship Id=\"rId3\" Type=\"${PptDoc.REL_SLIDE}\" Target=\"slides/slide2.xml\"/></Relationships>",
            "ppt/slides/slide1.xml" to slideXml("One"),
            "ppt/slides/_rels/slide1.xml.rels" to "<Relationships $RELS><Relationship Id=\"rId1\" Type=\"${PptDoc.REL_LAYOUT}\" Target=\"../slideLayouts/slideLayout1.xml\"/><Relationship Id=\"rId2\" Type=\"$REL/notesSlide\" Target=\"../notesSlides/notesSlide1.xml\"/></Relationships>",
            "ppt/notesSlides/notesSlide1.xml" to "<p:notes $P/>",
            "ppt/slides/slide2.xml" to slideXml("Two"),
            "ppt/slides/_rels/slide2.xml.rels" to "<Relationships $RELS><Relationship Id=\"rId1\" Type=\"${PptDoc.REL_LAYOUT}\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>",
            "ppt/slideLayouts/slideLayout1.xml" to "<p:sldLayout $P><p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"T\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"100\" y=\"200\"/><a:ext cx=\"3000\" cy=\"400\"/></a:xfrm></p:spPr></p:sp>" +
                "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"B\"/><p:cNvSpPr/><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"100\" y=\"900\"/><a:ext cx=\"3000\" cy=\"2000\"/></a:xfrm></p:spPr></p:sp></p:spTree></p:cSld></p:sldLayout>",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to "<Relationships $RELS><Relationship Id=\"rId1\" Type=\"$REL/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>",
            "ppt/slideMasters/slideMaster1.xml" to "<p:sldMaster $P><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"102030\"/></a:solidFill></p:bgPr></p:bg><p:spTree/></p:cSld></p:sldMaster>",
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to "<Relationships $RELS><Relationship Id=\"rId1\" Type=\"$REL/theme\" Target=\"../theme/theme1.xml\"/></Relationships>",
            "ppt/theme/theme1.xml" to "<a:theme xmlns:a=\"${PptDoc.A_NS}\"><a:themeElements><a:clrScheme name=\"x\"><a:dk1><a:srgbClr val=\"111111\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:accent1><a:srgbClr val=\"FF0000\"/></a:accent1></a:clrScheme></a:themeElements></a:theme>",
        )
        return PptDoc.of(OPackage.create(parts.mapValues { it.value.toByteArray() }))
    }

    private fun titles(d: PptDoc) = (0 until d.slideCount).map { i -> PptView.build(d, i).shapes.first { it.elem != null }.paragraphs.first().text }

    @Test fun reorderUpdatesSldIdList() {
        val d = sample()
        assertEquals(listOf("One", "Two"), titles(d))
        d.change { d.moveSlide(0, 1) }
        assertEquals(listOf("Two", "One"), titles(d))
        val xml = String(d.pkg.bytes(d.presPath)!!)
        assertTrue(xml.indexOf("id=\"257\"") < xml.indexOf("id=\"256\""))
        d.undo()
        assertEquals(listOf("One", "Two"), titles(d))
    }

    @Test fun deleteRemovesPartsRelsAndNotes() {
        val d = sample()
        assertTrue(d.change { d.deleteSlide(0) })
        assertEquals(listOf("Two"), titles(d))
        assertFalse(d.pkg.has("ppt/slides/slide1.xml")); assertFalse(d.pkg.has("ppt/slides/_rels/slide1.xml.rels")); assertFalse(d.pkg.has("ppt/notesSlides/notesSlide1.xml"))
        assertFalse(String(d.pkg.bytes("[Content_Types].xml")!!).contains("slide1.xml"))
        assertFalse(String(d.pkg.bytes("ppt/_rels/presentation.xml.rels")!!).contains("slides/slide1.xml"))
        assertFalse(d.deleteSlide(0)) // last slide stays
    }

    @Test fun duplicateAndNewSlide() {
        val d = sample()
        assertEquals(1, d.change { d.duplicateSlide(0) })
        assertEquals(listOf("One", "One", "Two"), titles(d))
        assertTrue(d.pkg.has("ppt/slides/slide3.xml"))
        assertFalse(String(d.pkg.bytes("ppt/slides/_rels/slide3.xml.rels")!!).contains("notesSlide"))
        assertTrue(String(d.pkg.bytes("[Content_Types].xml")!!).contains("/ppt/slides/slide3.xml"))
        val at = d.change { d.newSlide(2) }
        assertEquals(3, at); assertEquals(4, d.slideCount)
        val ids = d.slideIds().map { it.attr("id")!!.toInt() }
        assertEquals(ids.size, ids.toSet().size)
        // the new slide's placeholders take their positions from the layout
        val sh = PptView.build(d, 3).shapes.filter { it.elem != null }
        assertEquals(listOf(100L to 200L, 100L to 900L), sh.map { it.x to it.y })
    }

    @Test fun editTextShapesAndRender() {
        val d = sample()
        val s0 = PptView.build(d, 0)
        assertEquals("102030", s0.background)
        val title = s0.shapes.first { it.elem != null }
        assertEquals(100L, title.x); assertEquals(3000L, title.w)          // inherited from the layout
        assertEquals("FF0000", title.paragraphs[0].runs[0].color)          // theme colour
        assertTrue(title.paragraphs[0].runs[0].bold)
        d.changeSlide(0) { d.setShapeText(title.elem!!, "First line\nSecond") }
        val t2 = PptView.build(d, 0).shapes.first { it.elem != null }
        assertEquals(listOf("First line", "Second"), t2.paragraphs.map { it.text })
        assertTrue(t2.paragraphs[1].runs[0].bold)                          // run formatting kept on new lines
        d.changeSlide(0) { d.formatShapeText(t2.elem!!) { it.set("i", "1") } }
        assertTrue(PptView.build(d, 0).shapes.first { it.elem != null }.paragraphs.all { p -> p.runs.all { it.italic } })
        val box = d.change { d.addTextBox(0, 10, 20, 300, 100, "\u0645\u0631\u062D\u0628\u0627", rtl = true) }
        assertNotNull(box)
        d.changeSlide(0) { d.setBounds(box!!, 50, 60, 400, 200) }
        val b = PptView.build(d, 0).shapes.last()
        assertEquals(listOf(50L, 60L, 400L, 200L), listOf(b.x, b.y, b.w, b.h))
        assertEquals("r", b.paragraphs[0].align); assertTrue(b.paragraphs[0].rtl)
        val shp = d.change { d.addShape(0, "ellipse", 0, 0, 100, 100, "00FF00", "000000") }!!
        assertEquals("ellipse", PptView.build(d, 0).shapes.last().geom); assertEquals("00FF00", PptView.build(d, 0).shapes.last().fill)
        d.changeSlide(0) { d.setFill(shp, null) }
        assertEquals(null, PptView.build(d, 0).shapes.last().fill)
        d.change { d.setTransition(0, "fade") }
        assertEquals("fade", d.transition(0))
        val xml = String(d.pkg.bytes("ppt/slides/slide1.xml")!!)
        assertTrue(xml.indexOf("<p:transition") > xml.indexOf("</p:clrMapOvr>"))
    }
}
