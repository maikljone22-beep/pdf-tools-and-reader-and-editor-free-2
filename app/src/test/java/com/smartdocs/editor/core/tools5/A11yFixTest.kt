package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.edit5.PageOrganize
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pages-a11y: a Fix for every checker rule, reading order, table auto-tagging, PDF/UA machine checks, page redaction. */
class A11yFixTest {
    private fun elem(type: String, parent: COSDictionary, vararg kids: Any) = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setItem(COSName.S, COSName.getPDFName(type)); setItem(COSName.P, parent)
        setItem(COSName.K, COSArray().also { a -> kids.forEach { k -> when (k) { is Int -> a.add(COSInteger.get(k.toLong())); is COSDictionary -> { k.setItem(COSName.P, this); a.add(k) } } } })
    }
    private fun doc(build: (COSDictionary) -> Unit): PDDocument {
        val d = PDDocument(); d.addPage(PDPage())
        val root = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot")) }
        val top = elem("Document", root); root.setItem(COSName.K, COSArray().also { it.add(top) })
        build(top)
        d.documentCatalog.cosObject.setItem(COSName.getPDFName("StructTreeRoot"), root)
        return d
    }
    private fun kids(e: COSDictionary) = (e.getDictionaryObject(COSName.K) as COSArray).let { a -> (0 until a.size()).map { a.getObject(it) } }
    private fun type(b: Any?) = (b as COSDictionary).getNameAsString(COSName.S)

    @Test fun altTextFixes() {
        doc { top ->
            val inner = elem("Figure", top, 0).apply { setItem(COSName.getPDFName("Alt"), COSString("inner")) }
            val outer = elem("Div", top, inner).apply { setItem(COSName.getPDFName("Alt"), COSString("outer")) }
            val empty = elem("Span", top).apply { setItem(COSName.getPDFName("Alt"), COSString("nothing")) }
            val formula = elem("Formula", top, 1).apply { setItem(COSName.getPDFName("ActualText"), COSString("E = mc2")) }
            (top.getDictionaryObject(COSName.K) as COSArray).apply { add(outer); add(empty); add(formula) }
        }.use { d ->
            assertEquals(1, AccessFix2.removeNestedAlt(d))
            assertEquals(1, AccessFix2.removeAltWithoutContent(d))
            assertEquals(1, AccessFix2.fillOtherAlt(d, "Formula"))
            val facts = AccessInspect.facts(d, PdfInspect.inspect(d))
            assertEquals(0, facts.nestedAlt); assertEquals(0, facts.altWithoutContent); assertEquals(0, facts.otherWithoutAlt)
        }
    }

    @Test fun orphansHeadersSummariesHeadings() {
        doc { top ->
            val table = elem("Table", top)
            // two orphan cells directly under Table, then a proper row
            (table.getDictionaryObject(COSName.K) as COSArray).apply { add(elem("TD", table, 0)); add(elem("TD", table, 1)) }
            val li = elem("LI", top, elem("LBody", top, 2))
            (top.getDictionaryObject(COSName.K) as COSArray).apply { add(table); add(li); add(elem("H1", top, 3)); add(elem("H3", top, 4)) }
        }.use { d ->
            assertEquals(1, AccessFix2.wrapOrphans(d, setOf("TH", "TD"), setOf("TR"), "TR"))
            assertEquals(1, AccessFix2.wrapOrphans(d, setOf("LI"), setOf("L"), "L"))
            assertEquals(1, AccessFix2.firstRowHeaders(d))
            assertEquals(1, AccessFix2.summaries(d) { r, c -> "T $r×$c" })
            assertEquals(1, AccessFix2.fixHeadingSkips(d))
            val f = AccessInspect.facts(d, PdfInspect.inspect(d))
            assertEquals(0, f.tablesBadCells); assertEquals(0, f.listItemsBad); assertEquals(0, f.tablesWithoutHeaders)
            assertEquals(0, f.tablesWithoutSummary); assertEquals(0, f.headingSkips)
        }
    }

    @Test fun readingOrderMoves() {
        doc { top -> (top.getDictionaryObject(COSName.K) as COSArray).apply { add(elem("P", top, 0)); add(elem("P", top, 1)); add(elem("H1", top, 2)) } }.use { d ->
            (AccessTags.at(d, listOf(0))!!.getDictionaryObject(COSName.K) as COSArray).forEach { (it as COSDictionary).setItem(COSName.getPDFName("Pg"), d.getPage(0).cosObject) }
            val order = AccessTags.readingOrder(AccessTags.tree(d), 0)
            assertEquals(listOf("P", "P", "H1"), order.map { it.type })
            assertEquals(listOf(0, 0), AccessTags.moveTo(d, order[2].path, 0))
            assertEquals(listOf("H1", "P", "P"), AccessTags.readingOrder(AccessTags.tree(d), 0).map { it.type })
        }
    }

    @Test fun tablesFromAlignedBlocks() {
        val b = listOf(
            AccessAutoTag.Blk(0, "Title of the page", 14f, x = 72f, y = 60f),
            AccessAutoTag.Blk(1, "Name", 10f, x = 72f, y = 100f), AccessAutoTag.Blk(2, "Qty", 10f, x = 300f, y = 100f),
            AccessAutoTag.Blk(3, "Pens", 10f, x = 72f, y = 114f), AccessAutoTag.Blk(4, "4", 10f, x = 301f, y = 114f),
            AccessAutoTag.Blk(5, "Ink", 10f, x = 73f, y = 128f), AccessAutoTag.Blk(6, "2", 10f, x = 300f, y = 128f),
            AccessAutoTag.Blk(7, "A closing paragraph of text", 10f, x = 72f, y = 160f),
        )
        assertEquals(listOf(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6))), AccessAutoTag.tables(b))
        assertTrue(AccessAutoTag.tables(b.take(3)).isEmpty())
    }

    @Test fun pdfUaMachineChecks() {
        PDDocument().use { d ->
            d.addPage(PDPage())
            val f = Standards.pdfUa(d, Standards.claims(d), PdfInspect.inspect(d)).map { it.code }
            assertTrue(f.containsAll(listOf("ua_claim", "ua_tagged", "ua_title", "ua_manual")))
        }
    }

    @Test fun redactWholePage() {
        PDDocument().use { d ->
            val p = PDPage(); d.addPage(p); p.annotations = listOf(PDAnnotationText())
            PageOrganize.redactPagesIn(d, listOf(0))
            assertTrue(d.getPage(0).annotations.isEmpty())
            assertFalse(d.getPage(0).resources.xObjectNames.iterator().hasNext())
            val text = String(d.getPage(0).contents.readBytes())
            assertTrue(text.contains("re") && text.contains("f"))
        }
    }
}
