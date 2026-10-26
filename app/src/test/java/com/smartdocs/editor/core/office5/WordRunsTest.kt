package com.smartdocs.editor.core.office5

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WordRunsTest {
    private val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\""

    private fun para(inner: String): XElem = XDom.parse("<w:document $ns><w:body><w:p>$inner</w:p></w:body></w:document>").root.find("p")!!

    @Test fun domRoundTripIsExact() {
        val src = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n<w:document $ns><!-- c --><w:body><w:p w14:paraId=\"1A\">" +
            "<w:r><w:rPr><w:b/><w14:glow w14:rad=\"1\"/></w:rPr><w:t xml:space=\"preserve\">a &amp; b </w:t></w:r></w:p><w:sectPr/></w:body></w:document>"
        assertEquals(src, XDom.parse(src).toXml())
    }

    @Test fun textOfRunsIncludingHyperlinksAndTabs() {
        val p = para("<w:r><w:t>Hello</w:t><w:tab/></w:r><w:hyperlink><w:r><w:t>link</w:t></w:r></w:hyperlink><w:r><w:br/><w:t>x</w:t></w:r>")
        assertEquals("Hello\tlink\nx", WordRuns.text(p))
    }

    @Test fun splitRunKeepsUnknownProperties() {
        val p = para("<w:r w:rsidR=\"00A1\"><w:rPr><w:b/><w14:glow w14:rad=\"1\"/></w:rPr><w:t>abcdef</w:t></w:r>")
        WordRuns.splitAt(p, 2)
        val runs = WordRuns.runs(p)
        assertEquals(2, runs.size)
        assertEquals("ab", WordRuns.runText(runs[0])); assertEquals("cdef", WordRuns.runText(runs[1]))
        assertTrue(runs[1].child("rPr")!!.toXml().contains("w14:glow"))
    }

    @Test fun formatRangeSplitsThenMergesBack() {
        val p = para("<w:r><w:rPr><w:sz w:val=\"24\"/></w:rPr><w:t>Hello world</w:t></w:r>")
        WordRuns.formatRange(p, 6, 11) { WordFormat.setBold(it, true) }
        var runs = WordRuns.runs(p)
        assertEquals(2, runs.size)
        assertEquals("world", WordRuns.runText(runs[1]))
        assertEquals(true, WordFormat.read(runs[1].child("rPr")).bold)
        assertEquals(12f, WordFormat.read(runs[1].child("rPr")).sizePt)
        // rPr children are written in schema order (b before sz)
        assertTrue(runs[1].child("rPr")!!.toXml().indexOf("w:b") < runs[1].child("rPr")!!.toXml().indexOf("w:sz"))
        // removing the extra property makes the runs equal again: they merge
        WordRuns.formatRange(p, 6, 11) { it.removeChildren("b"); it.removeChildren("bCs") }
        runs = WordRuns.runs(p)
        assertEquals(1, runs.size)
        assertEquals("Hello world", WordRuns.text(p))
        assertEquals(1, runs[0].children("t").size)
    }

    @Test fun formatMiddleMakesThreeRuns() {
        val p = para("<w:r><w:t>abcdef</w:t></w:r>")
        WordRuns.formatRange(p, 2, 4) { WordFormat.setItalic(it, true) }
        assertEquals(listOf("ab", "cd", "ef"), WordRuns.runs(p).map { WordRuns.runText(it) })
    }

    @Test fun setTextKeepsFormattingOfUnchangedParts() {
        val p = para("<w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r><w:r><w:t xml:space=\"preserve\"> plain</w:t></w:r>")
        WordRuns.setText(p, "Bold!! plain")
        WordRuns.setText(p, "Bold!! plain text")
        val runs = WordRuns.runs(p)
        assertEquals("Bold!! plain text", WordRuns.text(p))
        assertEquals("Bold!!", WordRuns.runText(runs[0]))   // typed after "Bold" takes its formatting
        assertEquals(true, WordFormat.read(runs[0].child("rPr")).bold)
        assertEquals(" plain text", WordRuns.runText(runs[1]))
    }

    @Test fun deleteAcrossRunsKeepsFieldMarkup() {
        val p = para("<w:r><w:t>ab</w:t></w:r><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:t>cd</w:t></w:r>")
        WordRuns.deleteRange(p, 1, 3)
        assertEquals("ad", WordRuns.text(p))
        assertNotNull(p.find("fldChar"))
    }

    @Test fun insertIntoEmptyParagraphUsesMarkProps() {
        val p = para("<w:pPr><w:bidi/><w:rPr><w:rtl/><w:sz w:val=\"28\"/></w:rPr></w:pPr>")
        WordRuns.insertText(p, 0, "\u0645\u0631\u062D\u0628\u0627")
        val r = WordRuns.runs(p).single()
        assertEquals(14f, WordFormat.read(r.child("rPr")).sizePt)
        assertEquals("\u0645\u0631\u062D\u0628\u0627", WordRuns.text(p))
    }

    @Test fun newlinesBecomeBreaks() {
        val p = para("")
        WordRuns.insertText(p, 0, "a\nb\tc")
        assertEquals("a\nb\tc", WordRuns.text(p))
        assertNotNull(p.find("br")); assertNotNull(p.find("tab"))
    }

    @Test fun splitAndJoinParagraphs() {
        val body = XDom.parse("<w:document $ns><w:body><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t>Hello</w:t></w:r><w:hyperlink><w:r><w:t>World</w:t></w:r></w:hyperlink></w:p></w:body></w:document>").root.find("body")!!
        val p = body.child("p")!!
        val second = WordRuns.splitParagraph(body, p, 7)
        assertEquals("HelloWo", WordRuns.text(p)); assertEquals("rld", WordRuns.text(second))
        assertEquals(PAlign.CENTER, WordFormat.align(second))
        assertNotNull(second.child("hyperlink"))
        WordRuns.joinParagraphs(body, p, second)
        assertEquals(1, body.children("p").size)
        assertEquals("HelloWorld", WordRuns.text(p))
    }

    @Test fun paragraphPropertiesInSchemaOrder() {
        val p = para("<w:pPr><w:jc w:val=\"left\"/></w:pPr><w:r><w:t>x</w:t></w:r>")
        WordFormat.setBidi(p, true)
        WordFormat.setStyleId(p, "Heading1")
        val names = p.child("pPr")!!.elems().map { it.local }
        assertEquals(listOf("pStyle", "bidi", "jc"), names)
        assertEquals(PAlign.START, WordFormat.align(p))
    }

    private fun zip(entries: List<Pair<String, String>>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return bo.toByteArray()
    }

    private fun unzip(b: ByteArray): LinkedHashMap<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(b.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes() } }
        return m
    }

    private val docXml = "<?xml version=\"1.0\"?>\n<w:document $ns><w:body><w:p><w:r><w:t>one</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"
    private fun sample() = zip(listOf(
        "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>",
        "_rels/.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
        "word/document.xml" to docXml,
        "word/custom/odd.bin" to "  keep   me  <exactly>",
        "customXml/item1.xml" to "<a  b = 'c' >x</a >",
    ))

    @Test fun untouchedPackageRoundTripsByteForByte() {
        val src = sample()
        val out = WordDoc.of(OPackage.read(src)).pkg.toBytes()
        val a = unzip(src); val b = unzip(out)
        assertEquals(a.keys.toList(), b.keys.toList())
        a.forEach { (k, v) -> assertArrayEquals(k, v, b[k]) }
    }

    @Test fun editedPackageKeepsOtherPartsAndAddsNew() {
        val d = WordDoc.of(OPackage.read(sample()))
        d.change { WordRuns.setText(d.blocks().first(), "one two") }
        d.change { d.toggleList(listOf(d.blocks().first()), bullet = true) }
        d.change { d.applyStyle(d.blocks().first(), WordParts.StyleKind.HEADING1) }
        val b = unzip(d.pkg.toBytes())
        assertArrayEquals("  keep   me  <exactly>".toByteArray(), b["word/custom/odd.bin"])
        assertArrayEquals("<a  b = 'c' >x</a >".toByteArray(), b["customXml/item1.xml"])
        assertTrue(b.containsKey("word/numbering.xml")); assertTrue(b.containsKey("word/styles.xml"))
        val doc = String(b["word/document.xml"]!!)
        assertTrue(doc.contains("one two")); assertTrue(doc.contains("w:numPr")); assertTrue(doc.contains("w:pStyle w:val=\"Heading1\""))
        assertTrue(String(b["[Content_Types].xml"]!!).contains("/word/numbering.xml"))
        assertTrue(String(b["word/_rels/document.xml.rels"]!!).contains("numbering.xml"))
        // undo goes back step by step
        assertTrue(d.undo()); assertTrue(d.undo())
        assertFalse(String(d.pkg.bytes("word/document.xml")!!).contains("w:numPr"))
        assertTrue(d.redo())
        assertTrue(String(d.pkg.bytes("word/document.xml")!!).contains("w:numPr"))
    }

    @Test fun findReplaceAndStats() {
        val d = WordDoc.blank(listOf("cat and cat", "a dog"))
        assertEquals(2, d.find("CAT").size)
        assertEquals(2, d.replaceAll("cat", "lion"))
        assertEquals("lion and lion", WordRuns.text(d.blocks()[0]))
        val s = d.stats()
        assertEquals(5, s.words); assertEquals(2, s.paragraphs)
    }

    @Test fun tableRowsAndColumns() {
        val d = WordDoc.blank()
        val t = XDom.elem(WordParts.tableXml("w", 2, 2, 9000, false))
        d.body.add(0, t)
        WordTables.setCellText(WordTables.cells(WordTables.rows(t)[0])[0], "A1\nsecond line")
        assertEquals("A1\nsecond line", WordTables.cellText(WordTables.cells(WordTables.rows(t)[0])[0]))
        WordTables.insertRow(t, 0, below = true)
        assertEquals(3, WordTables.rows(t).size)
        assertEquals("", WordTables.cellText(WordTables.cells(WordTables.rows(t)[1])[0]))
        WordTables.insertColumn(t, 1, after = true)
        assertEquals(3, t.child("tblGrid")!!.children("gridCol").size)
        assertTrue(WordTables.rows(t).all { WordTables.cells(it).size == 3 })
        WordTables.deleteColumn(t, 0)
        assertTrue(WordTables.rows(t).all { WordTables.cells(it).size == 2 })
        assertTrue(WordTables.deleteRow(t, 2))
        assertEquals(2, WordTables.rows(t).size)
    }

    @Test fun insertImageAddsMediaRelAndContentType() {
        val d = WordDoc.blank()
        // 1x1 PNG header is enough for the size reader
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 100, 0, 0, 0, 50, 8, 2, 0, 0, 0)
        val p = d.change { d.insertImage(d.blocks().first(), png) }
        val dr = d.drawings(p).single()
        assertEquals(100L * 9525 to 50L * 9525, d.drawingSize(dr))
        assertArrayEquals(png, d.drawingImage(dr))
        d.resizeDrawing(dr, 2000, 1000)
        assertEquals(2000L to 1000L, d.drawingSize(dr))
        assertTrue(d.pkg.has("word/media/image1.png"))
        assertTrue(String(d.pkg.bytes("[Content_Types].xml")!!).contains("Extension=\"png\""))
        assertEquals(WordRuns.OBJ.toString(), WordRuns.text(p))
    }
}
