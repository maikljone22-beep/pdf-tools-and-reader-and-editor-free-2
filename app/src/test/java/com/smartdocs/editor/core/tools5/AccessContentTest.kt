package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** FINAL_CHECK_2 #20: the Content panel items and "create tag from selection". */
class AccessContentTest {

    private fun n(s: String) = COSName.getPDFName(s)

    /** A Type 3 font (no standard-14 AFM data in JVM tests) with the glyphs A, B, H, e, x: empty procs, width 500. */
    private fun type3(doc: PDDocument): COSDictionary {
        val proc = com.tom_roush.pdfbox.pdmodel.common.PDStream(doc).also { st -> st.createOutputStream().use { it.write("500 0 d0".toByteArray()) } }.cosObject
        val names = listOf("A", "B", "H", "e", "x", "space")
        val codes = listOf(65, 66, 72, 101, 120, 32)
        return COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT); setItem(COSName.SUBTYPE, n("Type3"))
            setItem(n("FontBBox"), COSArray().apply { listOf(0, 0, 500, 700).forEach { add(COSInteger.get(it.toLong())) } })
            setItem(n("FontMatrix"), COSArray().apply { listOf(0.001f, 0f, 0f, 0.001f, 0f, 0f).forEach { add(com.tom_roush.pdfbox.cos.COSFloat(it)) } })
            setItem(n("CharProcs"), COSDictionary().apply { names.forEach { setItem(n(it), proc) } })
            setItem(n("Encoding"), COSDictionary().apply {
                setItem(COSName.TYPE, n("Encoding"))
                setItem(n("Differences"), COSArray().apply { codes.zip(names).forEach { (c, g) -> add(COSInteger.get(c.toLong())); add(n(g)) } })
            })
            setInt(n("FirstChar"), 32); setInt(n("LastChar"), 120)
            setItem(n("Widths"), COSArray().apply { repeat(120 - 32 + 1) { add(COSInteger.get(500)) } })
            setItem(COSName.RESOURCES, COSDictionary())
        }
    }

    /** A page with two text runs ("Hex" and "ABBA") and no marked content at all. */
    private fun plain(): PDDocument {
        val doc = PDDocument()
        val page = PDPage(); doc.addPage(page)
        page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.FONT, COSDictionary().apply { setItem(n("F1"), type3(doc)) }) })
        val s = com.tom_roush.pdfbox.pdmodel.common.PDStream(doc)
        s.createOutputStream().use { it.write("BT /F1 14 Tf 72 700 Td (Hex) Tj ET\nBT /F1 11 Tf 72 670 Td (ABBA) Tj ET".toByteArray()) }
        page.setContents(s)
        return doc
    }

    private fun reload(doc: PDDocument): PDDocument { val bos = ByteArrayOutputStream(); doc.save(bos); doc.close(); return PDDocument.load(bos.toByteArray()) }

    /** MCIDs of the page's content stream (the text stripper cannot run in JVM tests: standard-14 fonts). */
    private fun mcids(doc: PDDocument): List<Int> = com.tom_roush.pdfbox.pdfparser.PDFStreamParser(doc.getPage(0)).let { it.parse(); it.tokens }
        .filterIsInstance<COSDictionary>().mapNotNull { (it.getDictionaryObject(n("MCID")) as? COSInteger)?.intValue() }

    @Test fun markPageGivesUnmarkedRunsMcids() {
        var doc = plain()
        assertTrue(mcids(doc).isEmpty())
        assertTrue(AccessContent.markPage(doc, 0))
        doc = reload(doc)
        assertEquals(listOf(0, 1), mcids(doc))
        assertTrue(AccessContent.owners(doc, 0).isEmpty())
        // marking again numbers after the existing ones and does not duplicate
        AccessContent.markPage(doc, 0)
        doc = reload(doc)
        assertEquals(listOf(0, 1), mcids(doc))
        doc.close()
    }

    @Test fun createTagOwnsTheItemsAndUpdatesTheParentTree() {
        var doc = plain()
        AccessContent.markPage(doc, 0)
        doc = reload(doc)
        val path = AccessContent.createTag(doc, 0, listOf(0), "H1")
        assertEquals(listOf(0), path)
        doc = reload(doc)
        assertEquals("H1", AccessContent.owners(doc, 0)[0]?.first)
        assertNull(AccessContent.owners(doc, 0)[1])
        // parent tree: StructParents 0 → [H1 elem, null]
        val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("StructTreeRoot")) as COSDictionary
        val nums = (root.getDictionaryObject(COSName.getPDFName("ParentTree")) as COSDictionary).getDictionaryObject(COSName.getPDFName("Nums")) as COSArray
        assertEquals(0, (nums.getObject(0) as COSInteger).intValue())
        val arr = nums.getObject(1) as COSArray
        assertEquals("H1", (arr.getObject(0) as COSDictionary).getNameAsString(COSName.S))
        assertEquals(0, (doc.getPage(0).cosObject.getDictionaryObject(COSName.getPDFName("StructParents")) as COSInteger).intValue())
        // the second run becomes a P under the same document element; the first keeps its H1
        AccessContent.createTag(doc, 0, listOf(1), "P")
        doc = reload(doc)
        assertEquals(listOf("H1", "P"), listOf(0, 1).map { AccessContent.owners(doc, 0)[it]?.first })
        assertNotNull(AccessTags.tree(doc).firstOrNull { it.type == "P" })
        // moving MCID 0 into a new Figure takes it away from the H1 (which is then empty)
        AccessContent.createTag(doc, 0, listOf(0), "Figure")
        doc = reload(doc)
        assertEquals(listOf("Figure", "P"), listOf(0, 1).map { AccessContent.owners(doc, 0)[it]?.first })
        assertEquals(0, AccessTags.tree(doc).first { it.type == "H1" }.contentItems)
        doc.close()
    }
}
