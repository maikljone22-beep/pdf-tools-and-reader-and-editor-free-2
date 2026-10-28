package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.edit5.OutlineStore
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-compliance: Find Element (34.49) and bookmarks from structure (34.18). */
class AccessFindTest {
    private fun node(path: List<Int>, type: String, mapped: String? = null, title: String? = null, alt: String? = null) =
        TagNode(path, path.size - 1, type, mapped, title, alt, null, null, null, null, 0, 0, 0)

    @Test fun findByTypeAndText() {
        val nodes = listOf(node(listOf(0), "H1", title = "Intro"), node(listOf(1), "Fig", mapped = "Figure", alt = "Chart of sales"), node(listOf(2), "P"))
        assertEquals(listOf(listOf(1)), AccessFind.find(nodes, setOf("Figure"), "").map { it.path })      // role-mapped type
        assertEquals(listOf(listOf(1)), AccessFind.find(nodes, emptySet(), "sales").map { it.path })
        assertEquals(3, AccessFind.find(nodes, emptySet(), "").size)
        assertEquals(2, AccessFind.headingLevel("H2")); assertEquals(1, AccessFind.headingLevel("H")); assertEquals(0, AccessFind.headingLevel("P"))
    }

    @Test fun bookmarksFromHeadings() {
        PDDocument().use { d ->
            d.addPage(PDPage()); d.addPage(PDPage())
            val root = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot")) }
            fun elem(type: String, title: String, page: Int) = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setItem(COSName.S, COSName.getPDFName(type)); setItem(COSName.P, root)
                setItem(COSName.T, COSString(title)); setItem(COSName.getPDFName("Pg"), d.getPage(page).cosObject)
            }
            root.setItem(COSName.K, COSArray().apply { add(elem("H1", "Chapter", 0)); add(elem("P", "body", 0)); add(elem("H2", "Section", 1)) })
            d.documentCatalog.cosObject.setItem(COSName.getPDFName("StructTreeRoot"), root)
            assertEquals(2, AccessFind.addBookmarks(d, setOf("H1", "H2"), "From structure") { "Untitled $it" })
            val bm = OutlineStore.read(d)
            assertEquals(listOf("From structure", "Chapter", "Section"), bm.map { it.title })
            assertEquals(listOf(0, 1, 2), bm.map { it.level })
            assertEquals(1, bm.last().page)
        }
    }
}
