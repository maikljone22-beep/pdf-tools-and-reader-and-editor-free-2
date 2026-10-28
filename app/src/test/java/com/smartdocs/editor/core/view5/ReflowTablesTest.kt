package com.smartdocs.editor.core.view5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OLD A1 / §14d: table rebuilding, column order and tagged tables of the reflow reader (synthetic boxes). */
class ReflowTablesTest {

    private fun seg(t: String, l: Float, r: Float) = RSeg(t, l, r)
    private fun line(top: Float, vararg s: RSeg, size: Float = 10f) =
        RLine(s.joinToString(" ") { it.text }, size, top, top - size, s.minOf { it.left }, s.maxOf { it.right }, if (s.size >= 2) s.toList() else emptyList())
    private fun prose(top: Float, text: String, l: Float = 50f, r: Float = 540f) = RLine(text, 10f, top, top - 10f, l, r)

    private fun row(top: Float, a: String, b: String, c: String) = line(top, seg(a, 50f, 100f), seg(b, 200f, 230f), seg(c, 300f, 340f))

    @Test fun segmentsSplitAtWideGapsInLogicalOrder() {
        val nan = Float.NaN
        val s = ReflowTables.segment("Ab  Cd", floatArrayOf(0f, 5f, nan, nan, 40f, 45f), floatArrayOf(5f, 10f, nan, nan, 45f, 50f), 10f)
        assertEquals(listOf("Ab", "Cd"), s.map { it.text })
        assertEquals(40f, s[1].left, 0.01f)
        // Hebrew / Arabic letters drawn right to left stay one segment in logical order
        val r = ReflowTables.segment("אב", floatArrayOf(50f, 45f), floatArrayOf(55f, 50f), 10f)
        assertEquals(listOf("אב"), r.map { it.text })
        // two cells of an Arabic row: the right cell comes first in the text, segments go left to right
        val ar = ReflowTables.segment("بب جج", floatArrayOf(300f, 295f, nan, 105f, 100f), floatArrayOf(305f, 300f, nan, 110f, 105f), 20f)
        assertEquals(listOf("جج", "بب"), ar.map { it.text })
    }

    @Test fun alignedRowsBecomeATable() {
        val lines = listOf(
            prose(760f, "Some text before the table."),
            row(700f, "Name", "Qty", "Price"), row(685f, "Apple", "2", "1.20"), row(670f, "Pear", "5", "0.80"), row(655f, "Fig", "1", "3.00"),
            prose(600f, "Some text after."),
        )
        val t = ReflowTables.detect(lines)
        assertEquals(1, t.size)
        assertEquals(setOf(1, 2, 3, 4), t[0].lines)
        assertEquals(listOf("Name", "Qty", "Price"), t[0].rows[0])
        assertEquals(listOf("Fig", "1", "3.00"), t[0].rows[3])
        val b = ReflowBlocks.build(0, lines, emptyList(), 842f)
        val tb = b.filterIsInstance<RBlock.Table>().single()
        assertEquals(4, tb.rows.size)
        assertEquals("Some text before the table.", (b[1] as RBlock.Para).text)
        assertTrue(b[2] is RBlock.Table)
        assertEquals("Some text after.", (b[3] as RBlock.Para).text)
    }

    @Test fun wrappedCellJoinsTheRowAbove() {
        val lines = listOf(
            row(700f, "A", "long cell", "1"), line(688f, seg("continued", 200f, 240f)), row(674f, "B", "x", "2"), row(660f, "C", "y", "3"),
        )
        val t = ReflowTables.detect(lines).single()
        assertEquals(3, t.rows.size)
        assertEquals("long cell continued", t.rows[0][1])
    }

    @Test fun twoColumnProseIsNotATable() {
        val l = "This is a long line of body text in a column"
        val lines = (0 until 6).map { k -> line(700f - k * 12f, seg(l, 50f, 280f), seg(l, 320f, 550f)) }
        assertTrue(ReflowTables.detect(lines).isEmpty())
    }

    @Test fun ruledTableWithTwoRowsAndVerticalRules() {
        val lines = listOf(line(700f, seg("Key", 60f, 90f), seg("Value", 210f, 250f)), line(684f, seg("a", 60f, 70f), seg("b", 210f, 220f)))
        // without rules two rows are too few
        assertTrue(ReflowTables.detect(lines).isEmpty())
        val rules = listOf(RRule(50f, 705f, 50f, 670f), RRule(200f, 705f, 200f, 670f), RRule(300f, 705f, 300f, 670f),
            RRule(50f, 705f, 300f, 705f), RRule(50f, 688f, 300f, 688f), RRule(50f, 670f, 300f, 670f))
        val t = ReflowTables.detect(lines, rules).single()
        assertEquals(listOf(listOf("Key", "Value"), listOf("a", "b")), t.rows)
        assertTrue(t.header)        // a rule under the first row
    }

    @Test fun columnsAreReadOneAfterTheOther() {
        val boxes = ArrayList<CBox>()
        boxes += CBox(50f, 550f, 800f, 780f, 20)        // title over both columns
        for (k in 0 until 4) boxes += CBox(50f, 280f, 760f - k * 14f, 750f - k * 14f, 40)       // left 1..4
        for (k in 0 until 4) boxes += CBox(320f, 550f, 760f - k * 14f, 750f - k * 14f, 40)      // right 5..8
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8), ReflowColumns.order(boxes, rtl = false))
        assertEquals(listOf(0, 5, 6, 7, 8, 1, 2, 3, 4), ReflowColumns.order(boxes, rtl = true))
        val g = ReflowColumns.groups(boxes, rtl = false)
        assertEquals(3, g.size)
        // a footer line over both columns closes the section
        boxes += CBox(50f, 550f, 600f, 590f, 30)
        assertEquals(9, ReflowColumns.order(boxes, rtl = false).last())
    }

    @Test fun listNumbersAreNotAColumn() {
        val boxes = (0 until 6).flatMap { k -> listOf(CBox(50f, 58f, 700f - k * 14f, 690f - k * 14f, 2), CBox(70f, 540f, 700f - k * 14f, 690f - k * 14f, 60)) }
        assertEquals(null, ReflowColumns.gutter(boxes, boxes.indices.toList()))
        assertEquals(1, ReflowColumns.groups(boxes, false).size)
    }

    @Test fun twoColumnPageReadsLeftColumnFirst() {
        val lines = ArrayList<RLine>()
        for (k in 0 until 4) {
            // PDFium gives one line over both columns: it is cut at the gutter
            val t = 700f - k * 12f
            lines += RLine("left $k right $k", 10f, t, t - 10f, 50f, 550f,
                listOf(RSeg("left words of the first column line $k", 50f, 280f), RSeg("right words of the second column $k", 320f, 550f)))
        }
        val text = ReflowBlocks.build(0, lines, emptyList(), 842f).filterIsInstance<RBlock.Para>().joinToString(" ") { it.text }
        assertTrue(text.indexOf("first column line 3") < text.indexOf("second column 0"))
    }

    // ---------------------------------------------------------------- tagged tables

    private fun elem(type: String, pg: COSDictionary?, vararg kids: COSBase) = COSDictionary().apply {
        setItem(COSName.getPDFName("S"), COSName.getPDFName(type))
        if (pg != null) setItem(COSName.getPDFName("Pg"), pg)
        setItem(COSName.getPDFName("K"), COSArray().also { a -> kids.forEach { a.add(it) } })
    }
    private fun mcid(n: Int) = COSInteger.get(n.toLong())

    @Test fun taggedTableCellsComeFromTheStructureTree() {
        val page = COSDictionary(); val other = COSDictionary()
        val mcr = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("MCR")); setInt(COSName.getPDFName("MCID"), 4) }
        val table = elem("MyTable", page,
            elem("THead", null, elem("TR", null, elem("TH", null, mcid(1)), elem("TH", null, mcid(2)))),
            elem("TBody", null, elem("TR", null, elem("TD", null, mcid(3)), elem("TD", null, mcr))))
        val root = COSDictionary().apply { setItem(COSName.getPDFName("K"), elem("Document", null, elem("P", page, mcid(0)), table)) }
        val roles = mapOf("MyTable" to "Table")
        assertTrue(ReflowTags.hasTables(root, roles))
        assertFalse(ReflowTags.hasTables(root))
        val t = ReflowTags.tables(root, page, roles).single()
        assertEquals(listOf(listOf(1), listOf(2)), t[0].map { it.mcids })
        assertTrue(t[0].all { it.header })
        assertEquals(listOf(listOf(3), listOf(4)), t[1].map { it.mcids })
        assertFalse(t[1].any { it.header })
        // the table draws nothing on another page
        assertTrue(ReflowTags.tables(root, other, roles).isEmpty())
    }
}
