package com.smartdocs.editor.core.office5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** office6: find / replace in sheets and slides, the Office command catalogue and read-aloud chunking. */
class OfficeFindTest {

    @Test fun textMatchWholeWordAndCase() {
        assertEquals(listOf(0, 5, 10), TextMatch.indices("cat, Cat; cats", "cat"))
        assertEquals(listOf(0, 5), TextMatch.indices("cat, Cat; cats", "cat", FindOptions(whole = true)))
        assertEquals(listOf(0), TextMatch.indices("cat, Cat; cats", "cat", FindOptions(matchCase = true, whole = true)))
        assertEquals("dog, dog; cats" to 2, TextMatch.replaceAll("cat, Cat; cats", "cat", "dog", FindOptions(whole = true)))
        assertEquals(emptyList<Int>(), TextMatch.indices("abc", ""))
        assertEquals(listOf(0, 4), TextMatch.indices("قلم قلم", "قلم"))
    }

    @Test fun sheetFindAndReplaceWithUndo() {
        val w = XlsxIO5.blank(rtl = false); val s = w.book.sheets[0]
        w.change(0) { w.setInput(s, 0, 0, "apple pie"); w.setInput(s, 1, 0, "Apple"); w.setInput(s, 0, 1, "=1+2") }
        assertEquals(2, SheetFind.find(w.book, s, "apple").size)
        assertEquals(1, SheetFind.find(w.book, s, "apple", FindOptions(matchCase = true)).size)
        assertEquals(0, SheetFind.find(w.book, s, "apple", FindOptions(whole = true, matchCase = true)).size)
        assertEquals(listOf(1 to 0), SheetFind.find(w.book, s, "apple", FindOptions(whole = true)).map { it.row to it.col })
        // the computed value and the formula are both searchable
        assertEquals(listOf(0 to 1), SheetFind.find(w.book, s, "3").map { it.row to it.col })
        assertEquals(listOf(0 to 1), SheetFind.find(w.book, s, "=1+").map { it.row to it.col })

        assertEquals(2, SheetFind.replaceAll(w, 0, s, "apple", "pear", FindOptions()))
        assertEquals("pear pie", SheetFind.sourceText(s.cell(0, 0)!!))
        assertEquals("pear", SheetFind.sourceText(s.cell(1, 0)!!))
        w.undo(0)
        assertEquals("apple pie", SheetFind.sourceText(w.book.sheets[0].cell(0, 0)!!))
    }

    @Test fun replacedInputKeepsTextCellsText() {
        val text = Cell5(null, FV.Str("x1"))
        assertEquals("'1", SheetFind.replacedInput(text, "x", "", FindOptions()))
        assertNull(SheetFind.replacedInput(text, "zzz", "y", FindOptions()))
        assertEquals("new", SheetFind.replacedInput(text, "X1", "new", FindOptions(whole = true)))
        assertNull(SheetFind.replacedInput(text, "X1", "new", FindOptions(matchCase = true, whole = true)))
    }

    private val P = "xmlns:a=\"${PptDoc.A_NS}\" xmlns:r=\"${OPackage.R_NS}\" xmlns:p=\"${PptDoc.P_NS}\""
    private val RELS = "xmlns=\"${OPackage.REL_NS}\""
    private val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun slideXml(a: String, b: String) = "<p:sld $P><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>" +
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"T\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:rPr b=\"1\"/><a:t>$a</a:t></a:r><a:r><a:t>$b</a:t></a:r></a:p></p:txBody></p:sp>" +
        "</p:spTree></p:cSld></p:sld>"

    private fun deck(): PptDoc {
        val parts = linkedMapOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/ppt/slides/slide1.xml\" ContentType=\"${PptDoc.CT_SLIDE}\"/><Override PartName=\"/ppt/slides/slide2.xml\" ContentType=\"${PptDoc.CT_SLIDE}\"/></Types>",
            "_rels/.rels" to "<Relationships $RELS><Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>",
            "ppt/presentation.xml" to "<p:presentation $P><p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/><p:sldId id=\"257\" r:id=\"rId3\"/></p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"6858000\"/></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to "<Relationships $RELS><Relationship Id=\"rId2\" Type=\"${PptDoc.REL_SLIDE}\" Target=\"slides/slide1.xml\"/><Relationship Id=\"rId3\" Type=\"${PptDoc.REL_SLIDE}\" Target=\"slides/slide2.xml\"/></Relationships>",
            "ppt/slides/slide1.xml" to slideXml("Hello ", "world &amp; co"),
            "ppt/slides/_rels/slide1.xml.rels" to "<Relationships $RELS><Relationship Id=\"rId2\" Type=\"$REL/notesSlide\" Target=\"../notesSlides/notesSlide1.xml\"/></Relationships>",
            "ppt/notesSlides/notesSlide1.xml" to "<p:notes $P><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Speaker note</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:notes>",
            "ppt/slides/slide2.xml" to slideXml("hello ", "again"),
        )
        return PptDoc.of(OPackage.create(parts.mapValues { it.value.toByteArray() }))
    }

    @Test fun slideFindReplaceKeepsRunsAndUndoes() {
        val d = deck()
        assertEquals(2, SlideFind.find(d, "hello").size)
        assertEquals(1, SlideFind.find(d, "hello", FindOptions(matchCase = true)).size)
        assertEquals(1, SlideFind.find(d, "& co").size)
        val h = SlideFind.find(d, "world")[0]
        assertTrue(SlideFind.replace(d, h, "there"))
        assertTrue(SlideFind.slideText(d, 0).startsWith("Hello there & co"))
        assertTrue(SlideFind.slideText(d, 0).contains("Speaker note"))
        assertTrue(SlideFind.slideText(d, 1, withNotes = false).contains("hello again"))
        assertEquals(2, SlideFind.replaceAll(d, "hello", "bye", FindOptions()))
        assertTrue(SlideFind.slideText(d, 1).contains("bye again"))
        // the bold run property survives the replacement
        assertTrue(d.slide(0)!!.root.findAll("rPr").any { it.attr("b") == "1" })
        assertTrue(d.undo())
        assertEquals(2, SlideFind.find(d, "hello").size)
    }

    @Test fun commandCatalogueParsesAiAnswers() {
        val calls = OfficeCommandCatalog.parse("Sure:\n```json\n[{\"id\":\"word.bold\"},{\"id\":\"word.font_size\",\"args\":{\"size\":\"18\"}},{\"id\":\"nope\"},{\"id\":\"sheet.sum\"}]\n```", OfficeKind.WORD)
        assertEquals(listOf("word.bold", "word.font_size"), calls.map { it.id })
        assertEquals(18, calls[1].int("size"))
        val wrapped = OfficeCommandCatalog.parse("{\"commands\":[{\"command\":\"slide.new\"},{\"id\":\"print\"}]}", OfficeKind.SLIDE)
        assertEquals(listOf("slide.new", "print"), wrapped.map { it.id })
        assertEquals(emptyList<OfficeCall>(), OfficeCommandCatalog.parse("no json here"))
        val prompt = OfficeCommandCatalog.prompt(OfficeKind.SHEET)
        assertTrue(prompt.contains("sheet.set (cell, value)"))
        assertTrue(!prompt.contains("word.bold"))
        assertTrue(OfficeCommandCatalog.all.map { it.id }.toSet().size == OfficeCommandCatalog.all.size)
        assertEquals("FF0000", OfficeCommandCatalog.hex("#ff0000"))
        assertEquals("0070C0", OfficeCommandCatalog.hex("أزرق"))
        assertNull(OfficeCommandCatalog.hex("teal-ish"))
    }

    @Test fun speechChunksAndSheetRows() {
        val long = (1..400).joinToString(" ") { "جملة رقم $it." }
        val chunks = OfficeSpeech.chunks(long, 500)
        assertTrue(chunks.size > 1 && chunks.all { it.length <= 500 })
        assertEquals(long.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertEquals(emptyList<String>(), OfficeSpeech.chunks("  "))
        val w = XlsxIO5.blank(rtl = false); val s = w.book.sheets[0]
        w.change(0) { w.setInput(s, 0, 0, "Name"); w.setInput(s, 0, 1, "Total"); w.setInput(s, 1, 0, "Ali"); w.setInput(s, 1, 1, "5") }
        assertEquals(listOf("Name، Total", "Ali، 5"), OfficeSpeech.sheetRows(w.book, s, CRange(0, 0, 10, 10)))
        assertTrue(OfficeSpeech.hasArabic("مرحبا")); assertTrue(!OfficeSpeech.hasArabic("hi"))
    }
}
