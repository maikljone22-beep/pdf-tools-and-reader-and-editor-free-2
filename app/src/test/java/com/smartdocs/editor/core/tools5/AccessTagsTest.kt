package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage F (Foxit 34 / Nitro 19): structure recognition, tags editor, PDF/UA marking, checker options. */
class AccessTagsTest {

    // ---------------- auto-tag classification ----------------
    @Test fun classifiesHeadingsListsParagraphs() {
        val b = listOf(
            AccessAutoTag.Blk(0, "Annual report", 24f), AccessAutoTag.Blk(1, "Body text of the report goes here", 11f),
            AccessAutoTag.Blk(2, "Results", 16f), AccessAutoTag.Blk(3, "• first point", 11f), AccessAutoTag.Blk(4, "2) second point", 11f),
            AccessAutoTag.Blk(5, "More body text that is long enough to be the body size", 11f), AccessAutoTag.Blk(6, "Details", 13f),
            AccessAutoTag.Blk(7, "١. البند الأول", 11f), AccessAutoTag.Blk(8, "2024", 30f),
        )
        val k = AccessAutoTag.classify(b)
        assertEquals(listOf(AccessAutoTag.Kind.H1, AccessAutoTag.Kind.P, AccessAutoTag.Kind.H2, AccessAutoTag.Kind.LI, AccessAutoTag.Kind.LI,
            AccessAutoTag.Kind.P, AccessAutoTag.Kind.H3, AccessAutoTag.Kind.LI, AccessAutoTag.Kind.P), k)
        assertTrue(AccessAutoTag.classify(emptyList()).isEmpty())
    }

    // ---------------- tags editor ----------------
    private fun elem(type: String, parent: COSDictionary, page: PDPage?, vararg mcids: Int) = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setItem(COSName.S, COSName.getPDFName(type)); setItem(COSName.P, parent)
        if (page != null) setItem(COSName.getPDFName("Pg"), page.cosObject)
        setItem(COSName.K, COSArray().also { a -> mcids.forEach { a.add(COSInteger.get(it.toLong())) } })
    }
    private fun add(parent: COSDictionary, kid: COSDictionary) = (parent.getDictionaryObject(COSName.K) as COSArray).add(kid)

    /** Document > [H1(mcid 0, p1), P(mcid 1, p1), Div > [P(mcid 0, p2)], Span(empty)]. */
    private fun tagged(): PDDocument {
        val d = PDDocument(); repeat(2) { d.addPage(PDPage()) }
        val root = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot")) }
        val doc = elem("Document", root, null); root.setItem(COSName.K, COSArray().also { it.add(doc) })
        add(doc, elem("H1", doc, d.getPage(0), 0)); add(doc, elem("P", doc, d.getPage(0), 1))
        val div = elem("Div", doc, d.getPage(1)); add(doc, div); add(div, elem("P", div, d.getPage(1), 0))
        add(doc, elem("Span", doc, null))
        d.documentCatalog.cosObject.setItem(COSName.getPDFName("StructTreeRoot"), root)
        return d
    }

    @Test fun treeAndProperties() {
        tagged().use { d ->
            val t = AccessTags.tree(d)
            assertEquals(listOf("Document", "H1", "P", "Div", "P", "Span"), t.map { it.type })
            assertEquals(listOf(0, 1, 1, 1, 2, 1), t.map { it.depth })
            assertEquals(listOf(listOf(0), listOf(0, 0), listOf(0, 1), listOf(0, 2), listOf(0, 2, 0), listOf(0, 3)), t.map { it.path })
            assertEquals(1, t[4].page); assertEquals(1, t[1].contentItems)
            AccessTags.setType(d, listOf(0, 1), "Caption")
            AccessTags.setProps(d, listOf(0, 1), "Intro", "alt", "act", "ar", "Doctor", "p1")
            val n = AccessTags.tree(d)[2]
            assertEquals("Caption", n.type); assertEquals("Intro", n.title); assertEquals("ar", n.lang); assertEquals("Doctor", n.expansion); assertEquals("p1", n.id)
            AccessTags.setAttrs(d, listOf(0, 1), TagAttrs(scope = "Column", colSpan = 2, headers = "h1 h2", summary = "Totals"))
            assertEquals(TagAttrs("Column", null, 2, "h1 h2", "Totals", null), AccessTags.attrs(d, listOf(0, 1)))
            AccessTags.setAttrs(d, listOf(0, 1), TagAttrs())
            assertNull(AccessTags.at(d, listOf(0, 1))!!.getDictionaryObject(COSName.A))
            AccessTags.setRole(d, "Heading", "H1")
            assertEquals(mapOf("Heading" to "H1"), AccessTags.roleMap(d))
            AccessTags.setRole(d, "Heading", null); assertTrue(AccessTags.roleMap(d).isEmpty())
        }
    }

    @Test fun editStructure() {
        tagged().use { d ->
            assertEquals(1, AccessTags.deleteEmpty(d))                         // the empty Span
            AccessTags.delete(d, listOf(0, 2))                                 // Div: its P moves up
            assertEquals(listOf("Document", "H1", "P", "P"), AccessTags.tree(d).map { it.type })
            assertEquals(listOf(0, 0), AccessTags.move(d, listOf(0, 1), -1))
            assertEquals(listOf("P", "H1"), AccessTags.tree(d).filter { it.depth == 1 }.map { it.type }.take(2))
            assertEquals(listOf(0, 0, 1), AccessTags.indent(d, listOf(0, 1)))  // H1 under the first P, after its content (MCID)
            assertEquals(listOf(0, 1), AccessTags.outdent(d, listOf(0, 0, 1)))
            val c = AccessTags.newChild(d, listOf(0), "Note")!!
            assertEquals("Note", AccessTags.at(d, c)!!.getNameAsString(COSName.S))
            val s = AccessTags.newSibling(d, listOf(0, 0), "Sect")!!
            assertEquals(listOf(0, 1), s)
            assertFalse(AccessTags.isTaggedFlag(d)); AccessTags.setTaggedFlag(d, true); assertTrue(AccessTags.isTaggedFlag(d))
        }
    }

    @Test fun clearPageStructure() {
        tagged().use { d ->
            val n = AccessTags.clearPage(d, 0)
            assertEquals(2, n)
            // H1 and P of page 1 were left empty and went; Div > P of page 2 stays
            assertEquals(listOf("Document", "Div", "P"), AccessTags.tree(d).map { it.type })
        }
    }

    // ---------------- PDF/UA ----------------
    @Test fun uaPart() {
        val xmp = """<x:xmpmeta><rdf:RDF><rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:format>application/pdf</dc:format></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val ua = AccessUa.insertUa(xmp)!!
        assertTrue(ua.contains("xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\""))
        assertTrue(ua.contains("<pdfuaid:part>1</pdfuaid:part><dc:format>"))
        assertEquals(ua, AccessUa.insertUa(ua))                                // already there: unchanged
        assertTrue(AccessUa.insertUa("""<rdf:Description rdf:about=""/>""")!!.endsWith("<pdfuaid:part>1</pdfuaid:part></rdf:Description>"))
        assertNull(AccessUa.insertUa("<x/>"))
        assertTrue(AccessUa.packet("R&D").contains("R&amp;D"))
    }

    // ---------------- checker options ----------------
    @Test fun verdictsAndGroups() {
        val res = AccessRules.evaluate(AccessFacts(pageCount = 2, javascript = true))
        assertEquals(32, res.size)
        assertEquals(AccStatus.MANUAL, res.first { it.rule.id == "flicker" }.status)
        assertEquals(AccStatus.MANUAL, res.first { it.rule.id == "timed" }.status)
        val shown = AccessRules.apply(res, setOf(AccGroup.DOCUMENT), mapOf("contrast" to AccStatus.PASS), setOf("reading_order"))
        assertTrue(shown.all { it.rule.group == AccGroup.DOCUMENT })
        assertEquals(AccStatus.PASS, shown.first { it.rule.id == "contrast" }.status); assertTrue(shown.first { it.rule.id == "contrast" }.manual)
        assertEquals(AccStatus.SKIPPED, shown.first { it.rule.id == "reading_order" }.status)
        assertNotNull(AccessRules.htmlReport("f", shown, false, "R", { it }, { it.name }, { it.name }).takeIf { it.contains("✎") })
    }
}
