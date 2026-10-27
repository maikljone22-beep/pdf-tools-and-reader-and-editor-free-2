package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** nav-prefs: bookmark preferences (remove on delete, import on replace) and the extra reader panels. */
class NpfOutlineTest {
    private fun item(title: String, page: PDPage?) = PDOutlineItem().also { it.title = title; if (page != null) it.destination = PDPageFitDestination().also { d -> d.page = page } }

    private fun titles(doc: PDDocument): List<String> {
        val out = ArrayList<String>()
        fun walk(n: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode) { n.children().forEach { out += it.title; walk(it) } }
        doc.documentCatalog.documentOutline?.let { walk(it) }
        return out
    }

    @Test fun removesBookmarksOfDeletedPages() {
        PDDocument().use { d ->
            repeat(3) { d.addPage(PDPage()) }
            val o = PDDocumentOutline(); d.documentCatalog.documentOutline = o
            o.addLast(item("one", d.getPage(0)))
            val two = item("two", d.getPage(1)); o.addLast(two)
            two.addLast(item("two.child", d.getPage(2)))
            o.addLast(item("three", d.getPage(1)))
            o.addLast(item("four", d.getPage(2)))
            val n = NpfOutline.removeFor(d, setOf(d.getPage(1).cosObject))
            assertEquals(2, n)                                 // "two" and "three"; "two.child" moves up to the top level
            assertEquals(listOf("one", "two.child", "four"), titles(d))
            // removing at both ends keeps First / Last right
            NpfOutline.removeFor(d, setOf(d.getPage(0).cosObject))
            assertEquals("two.child", o.firstChild.title); assertEquals("four", o.lastChild.title)
            NpfOutline.removeFor(d, setOf(d.getPage(2).cosObject))
            assertTrue(titles(d).isEmpty())
        }
    }

    @Test fun importsBookmarksOfReplacementPages() {
        PDDocument().use { d ->
            PDDocument().use { other ->
                d.addPage(PDPage()); repeat(2) { other.addPage(PDPage()) }
                val o = PDDocumentOutline(); other.documentCatalog.documentOutline = o
                o.addLast(item("A", other.getPage(0))); o.addLast(item("B", other.getPage(1)))
                val copy = d.importPage(other.getPage(1))
                val n = NpfOutline.importFrom(d, other, mapOf(other.getPage(1).cosObject to copy), "src")
                assertEquals(1, n)
                assertEquals(listOf("src", "B"), titles(d))
            }
        }
    }

    @Test fun destinationsAndLayers() {
        PDDocument().use { d ->
            repeat(3) { d.addPage(PDPage()) }
            assertTrue(NpfDocPanels.putDestination(d, "intro", 2))
            assertTrue(NpfDocPanels.putDestination(d, "end", 1))
            assertEquals(mapOf("intro" to 2, "end" to 1), NpfDocPanels.destinations(d).associate { it.name to it.page })
            assertTrue(NpfDocPanels.removeDestination(d, "intro"))
            assertEquals(listOf("end"), NpfDocPanels.destinations(d).map { it.name })
            // one optional content group, then hide it
            val g = COSDictionary().also { it.setItem(COSName.TYPE, COSName.getPDFName("OCG")); it.setString(COSName.NAME, "Notes") }
            val props = COSDictionary().also { p -> p.setItem(COSName.getPDFName("OCGs"), COSArray().also { it.add(g) }); p.setItem(COSName.D, COSDictionary()) }
            d.documentCatalog.cosObject.setItem(COSName.getPDFName("OCProperties"), props)
            assertEquals(listOf(NpfDocPanels.Layer("Notes", true, 0)), NpfDocPanels.layers(d))
            assertTrue(NpfDocPanels.setLayer(d, 0, false))
            assertFalse(NpfDocPanels.layers(d).single().on)
            assertTrue(NpfDocPanels.setLayer(d, 0, true))
            assertTrue(NpfDocPanels.layers(d).single().on)
            assertEquals("1.4", NpfDocPanels.standards(d).pdfVersion)
            assertFalse(NpfDocPanels.security(d).encrypted)
        }
    }
}
