package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.ui.reader.InitialViewMap
import com.smartdocs.editor.ui.reader.ViewMode
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stage A (Nitro 4 / Foxit 23a, old plan A5/A6): page organize orderings, bookmark-aware delete / insert / split, initial view. */
class PageOrganizeTest {

    private fun tmp(name: String) = File.createTempFile("org_$name", ".pdf").also { it.deleteOnExit() }

    private fun item(title: String, page: PDPage) = PDOutlineItem().apply {
        this.title = title; destination = PDPageFitDestination().apply { setPage(page) }
    }

    /** 4 pages; outline: A→p1 { B→p2 }, C→p3; page 4 has a link to page 2. */
    private fun sample(): PDDocument {
        val d = PDDocument(); repeat(4) { d.addPage(PDPage(PDRectangle.A4)) }
        val outline = PDDocumentOutline(); d.documentCatalog.documentOutline = outline
        val a = item("A", d.getPage(0)); outline.addLast(a); a.addLast(item("B", d.getPage(1)))
        outline.addLast(item("C", d.getPage(2)))
        val link = PDAnnotationLink().apply { rectangle = PDRectangle(10f, 10f, 50f, 20f); action = PDActionGoTo().apply { destination = PDPageFitDestination().apply { setPage(d.getPage(1)) } } }
        d.getPage(3).annotations = listOf(link)
        return d
    }

    private fun titles(d: PDDocument): List<String> {
        val out = ArrayList<String>()
        fun walk(n: Iterable<PDOutlineItem>, depth: Int) { n.forEach { out += "  ".repeat(depth) + it.title; walk(it.children(), depth + 1) } }
        d.documentCatalog.documentOutline?.let { walk(it.children(), 0) }
        return out
    }

    // ---------------- pure orderings ----------------
    @Test fun moveAndSwap() {
        assertEquals(listOf(0, 3, 4, 1, 2, 5), PageOrganize.moveOrder(6, listOf(3, 4), 1))
        assertEquals(listOf(1, 2, 0), PageOrganize.moveOrder(3, listOf(0), 3))
        assertEquals(listOf(0, 1, 2), PageOrganize.moveOrder(3, listOf(9), 0))
        assertEquals(listOf(2, 1, 0, 3), PageOrganize.swapOrder(4, 0, 2))
        val l = mutableListOf("a", "b", "c")
        PageOrganize.moveItem(l, 2, 0); assertEquals(listOf("c", "a", "b"), l)
        PageOrganize.moveItem(l, 0, 5); assertEquals(listOf("c", "a", "b"), l)
    }

    @Test fun bookmarkOrderPutsSectionsInOutlineOrder() {
        // bookmarks in outline order point at pages 4, 1; page 0 has none and stays first
        assertEquals(listOf(0, 4, 5, 1, 2, 3), PageOrganize.bookmarkOrder(6, listOf(4, 1)))
        assertEquals(listOf(0, 1, 2), PageOrganize.bookmarkOrder(3, emptyList()))
    }

    @Test fun partNames() {
        assertEquals("doc_3", PageOrganize.partName("doc", PartName.NUMBER, 2, 12, listOf(4)))
        assertEquals("doc_03", PageOrganize.partName("doc", PartName.PADDED, 2, 12, listOf(4)))
        assertEquals("doc_AB", PageOrganize.partName("doc", PartName.LETTER, 27, 30, listOf(0)))
        assertEquals("doc_p5-7", PageOrganize.partName("doc", PartName.RANGE, 0, 1, listOf(4, 5, 6)))
        assertEquals("doc_p2", PageOrganize.partName("doc", PartName.RANGE, 0, 1, listOf(1)))
    }

    @Test fun rangeGroupsAndMix() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(4), listOf(7, 8)), PageOrganize.rangeGroups("1-3, 5، ٨-9", 10))
        assertEquals(listOf(0 to 0, 1 to 2, 0 to 1, 1 to 1, 1 to 0), PageOrganize.mixOrder(listOf(2, 3), listOf(false, true)))
        assertEquals(mapOf(0 to 0, 2 to 1, 3 to 2), PageOrganize.remap(4, setOf(1)))
    }

    @Test fun initialViewNames() {
        assertEquals(true, InitialViewMap.rtl("R2L")); assertEquals(false, InitialViewMap.rtl("L2R")); assertNull(InitialViewMap.rtl(""))
        assertEquals(ViewMode.FACING, InitialViewMap.viewMode("TwoPageLeft"))
        assertEquals(ViewMode.SINGLE, InitialViewMap.viewMode("SinglePage"))
        assertNull(InitialViewMap.viewMode("Weird"))
        assertEquals("OUTLINE", InitialViewMap.panelOf("UseOutlines"))
        assertEquals("full", InitialViewMap.panelOf("FullScreen"))
        assertNull(InitialViewMap.panelOf("UseNone"))
    }

    // ---------------- bookmark-aware page operations ----------------
    @Test fun deleteRemovesDeadBookmarksAndLinks() {
        sample().use { d ->
            PageOrganize.deleteIn(d, listOf(1))
            assertEquals(3, d.numberOfPages)
            assertEquals(listOf("A", "C"), titles(d))
            assertTrue(d.getPage(2).annotations.isEmpty())
        }
        // deleting a parent's page promotes its children
        sample().use { d ->
            PageOrganize.deleteIn(d, listOf(0))
            assertEquals(listOf("B", "C"), titles(d))
            assertEquals(1, d.getPage(2).annotations.size)
        }
    }

    @Test fun outlineStartsAndLevels() {
        sample().use { d ->
            assertEquals(listOf(0, 1, 2), PageOrganize.outlineStarts(d))
            assertEquals(listOf(0, 2), PageOrganize.levelStarts(d, 0))
            assertEquals(listOf(1), PageOrganize.levelStarts(d, 1))
        }
    }

    @Test fun insertFileKeepsBookmarksUnderHolder() = runBlocking {
        val main = tmp("main"); val other = tmp("other"); val out = tmp("out")
        PDDocument().use { d -> repeat(2) { d.addPage(PDPage()) }; E5Io.save(d, main) }
        sample().use { E5Io.save(it, other) }
        PageOrganize.insertFile(main, out, null, other, null, 1, title = "Other")
        E5Io.load(out).use { d ->
            assertEquals(6, d.numberOfPages)
            assertEquals(listOf("Other", "  A", "    B", "  C"), titles(d))
            // A points at the first inserted page (index 1)
            val a = d.documentCatalog.documentOutline.firstChild.firstChild
            assertEquals(1, d.pages.indexOf(PageOrganize.itemPage(d, a.cosObject)?.let { PDPage(it) }))
        }
    }

    @Test fun splitByBookmarksKeepsNavigation() = runBlocking {
        val src = tmp("split"); sample().use { E5Io.save(it, src) }
        val dir = File(src.parentFile, "org_parts_${System.nanoTime()}").apply { mkdirs(); deleteOnExit() }
        val parts = PageOrganize.splitByBookmarks(src, null, 0, dir, "doc", PartName.NUMBER, true) { d, b -> File(d, "$b.pdf") }
        assertEquals(listOf("doc_1.pdf", "doc_2.pdf"), parts.map { it.name })
        E5Io.load(parts[0]).use { d -> assertEquals(2, d.numberOfPages); assertEquals(listOf("A", "  B"), titles(d)) }
        E5Io.load(parts[1]).use { d ->
            assertEquals(2, d.numberOfPages); assertEquals(listOf("C"), titles(d))
            // the link on page 4 jumped to page 2, which is in the other part: dropped
            assertTrue(d.getPage(1).annotations.none { it.subtype == "Link" })
        }
        parts.forEach { it.delete() }; dir.delete()
    }
}
