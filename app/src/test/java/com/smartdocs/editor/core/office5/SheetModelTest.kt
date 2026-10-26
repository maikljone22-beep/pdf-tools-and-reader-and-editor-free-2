package com.smartdocs.editor.core.office5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SheetModelTest {

    // ---------------- cell reference math ----------------

    @Test fun columnNames() {
        assertEquals("A", A1.colName(0)); assertEquals("Z", A1.colName(25)); assertEquals("AA", A1.colName(26)); assertEquals("XFD", A1.colName(16383))
        assertEquals(0, A1.colIndex("a")); assertEquals(27, A1.colIndex("AB")); assertEquals(16383, A1.colIndex("XFD")); assertEquals(-1, A1.colIndex("XFE"))
        assertEquals(CellRef(2, 1, absRow = true, absCol = false), A1.parseCell("B\$3"))
        assertEquals(CRange(0, 0, 3, 2), A1.parseRange("C4:A1"))
        assertNull(A1.parseCell("A0"))
    }

    @Test fun shiftMath() {
        assertEquals(7, A1.shiftIndex(5, 3, 2)); assertEquals(2, A1.shiftIndex(2, 3, 2))
        assertNull(A1.shiftIndex(4, 3, -2)); assertEquals(3, A1.shiftIndex(5, 3, -2))
        assertEquals(CRange(0, 0, 7, 0), A1.shiftRange(CRange(0, 0, 5, 0), true, 3, 2))
        assertEquals(CRange(0, 0, 3, 0), A1.shiftRange(CRange(0, 0, 5, 0), true, 2, -2))   // range shrinks
        assertNull(A1.shiftRange(CRange(2, 0, 3, 0), true, 2, -2))                          // fully deleted
    }

    @Test fun formulaTranslateAndShift() {
        val fx = FxParser.parse("A1+\$B\$2+C\$3+SUM(D1:D4)")
        assertEquals("B3+\$B\$2+D\$3+SUM(E3:E6)", FxPrinter.print(FxPrinter.translate(fx, 2, 1)))
        assertEquals("#REF!+\$B\$2+#REF!+SUM(#REF!)", FxPrinter.print(FxPrinter.translate(FxParser.parse("A1+\$B\$2+A\$3+SUM(A1:A2)"), -1, -1)))
        val sh = FxPrinter.shift(FxParser.parse("A5+SUM(A1:A10)+B2"), { it == null }, rows = true, at = 3, count = 2)!!
        assertEquals("A7+SUM(A1:A12)+B2", FxPrinter.print(sh))
        val del = FxPrinter.shift(FxParser.parse("A5+A2"), { it == null }, rows = true, at = 4, count = -1)!!
        assertEquals("#REF!+A2", FxPrinter.print(del))
        assertNull(FxPrinter.shift(FxParser.parse("Other!A5"), { it == null }, true, 0, 1))
    }

    @Test fun insertDeleteRowsRewritesFormulas() {
        val w = XlsxIO5.blank(rtl = false); val s = w.book.sheets[0]
        w.setInput(s, 0, 0, "1"); w.setInput(s, 1, 0, "2"); w.setInput(s, 2, 0, "=SUM(A1:A2)")
        w.shiftRows(s, 1, 1)
        assertEquals("SUM(A1:A3)", s.cell(3, 0)!!.formula)
        w.setInput(s, 1, 0, "10")
        assertEquals(FV.Num(13.0), w.book.value(s, 3, 0))
        w.shiftRows(s, 0, -1)
        assertEquals("SUM(A1:A2)", s.cell(2, 0)!!.formula)
        assertEquals(FV.Num(12.0), w.book.value(s, 2, 0))
        w.shiftCols(s, 0, 1)
        assertEquals("SUM(B1:B2)", s.cell(2, 1)!!.formula)
    }

    @Test fun sortAndFill() {
        val w = XlsxIO5.blank(rtl = false); val s = w.book.sheets[0]
        listOf("Name", "c", "a", "b").forEachIndexed { i, v -> w.setInput(s, i, 0, v) }
        listOf("N", "3", "1", "2").forEachIndexed { i, v -> w.setInput(s, i, 1, v) }
        w.sort(s, CRange(0, 0, 3, 1), 0, ascending = true, header = true)
        assertEquals(listOf("Name", "a", "b", "c"), (0..3).map { w.book.display(s, it, 0).text })
        assertEquals(listOf("N", "1", "2", "3"), (0..3).map { w.book.display(s, it, 1).text })
        // numeric series
        w.setInput(s, 0, 3, "2"); w.setInput(s, 1, 3, "4")
        w.fill(s, CRange(0, 3, 1, 3), CRange(0, 3, 4, 3))
        assertEquals(listOf("2", "4", "6", "8", "10"), (0..4).map { w.book.display(s, it, 3).text })
        // text with number, month names, formulas
        w.setInput(s, 0, 4, "Item 9"); w.fill(s, CRange(0, 4, 0, 4), CRange(0, 4, 2, 4))
        assertEquals("Item 11", w.book.display(s, 2, 4).text)
        w.setInput(s, 0, 5, "Jan"); w.fill(s, CRange(0, 5, 0, 5), CRange(0, 5, 2, 5))
        assertEquals("Mar", w.book.display(s, 2, 5).text)
        w.setInput(s, 0, 6, "=D1*2"); w.fill(s, CRange(0, 6, 0, 6), CRange(0, 6, 2, 6))
        assertEquals("D3*2", s.cell(2, 6)!!.formula)
        assertEquals(FV.Num(12.0), w.book.value(s, 2, 6))
    }

    @Test fun undoRedo() {
        val w = XlsxIO5.blank(rtl = false); val s = w.book.sheets[0]
        w.change(0) { w.setInput(s, 0, 0, "5") }
        w.change(0) { w.setInput(s, 0, 1, "=A1*2") }
        assertEquals(FV.Num(10.0), w.book.value(s, 0, 1))
        w.undo(0)
        assertEquals(FV.Blank, w.book.value(s, 0, 1))
        w.redo(0)
        assertEquals(FV.Num(10.0), w.book.value(s, 0, 1))
    }

    // ---------------- number formatting ----------------

    @Test fun numberFormats() {
        fun f(d: Double, c: String) = NumFmt5.format(d, c).text
        assertEquals("1234.5", f(1234.5, "General"))
        assertEquals("1,234.50", f(1234.5, "#,##0.00"))
        assertEquals("-1,235", f(-1234.5, "#,##0"))
        assertEquals("(1,235)", f(-1234.5, "#,##0_);(#,##0)").trim())
        assertEquals("12.5%", f(0.125, "0.0%"))
        assertEquals("\$1,234.50", f(1234.5, "\"\$\"#,##0.00"))
        assertEquals("1,234.50 SAR", f(1234.5, "#,##0.00 [\$SAR]"))
        assertEquals("1.23E+03", f(1234.5, "0.00E+00"))
        assertEquals("0.50", f(0.5, "0.00"))
        assertEquals(".5", f(0.5, "#.#"))
        assertEquals("1,235K", f(1234567.0, "#,##0,\"K\""))
        assertEquals("zero", f(0.0, "0;-0;\"zero\""))
        assertEquals(Formatted("5", "FF0000"), NumFmt5.format(-5.0, "0;[Red]0"))
        val d = FxDates.serial(java.time.LocalDate.of(2024, 3, 9))
        assertEquals("2024-03-09", f(d, "yyyy-mm-dd"))
        assertEquals("9 March 2024", f(d, "d mmmm yyyy"))
        assertEquals("Sat 09/03/24", f(d, "ddd dd/mm/yy"))
        assertEquals("13:05", f(d + (13 * 60 + 5) / 1440.0, "hh:mm"))
        assertEquals("1:05 PM", f(d + (13 * 60 + 5) / 1440.0, "h:mm AM/PM"))
        assertEquals("27:00", f(1.125, "[h]:mm"))
        assertEquals("text: hi", NumFmt5.format(FV.Str("hi"), "0;0;0;\"text: \"@").text)
        assertEquals(60.0, FxDates.serial(java.time.LocalDate.of(1900, 2, 28)) + 1, 0.0)
        assertEquals(java.time.LocalDate.of(1900, 3, 1), FxDates.date(61.0).toLocalDate())
    }

    @Test fun inputParsing() {
        val b = SheetBook()
        assertEquals(FV.Num(0.12), b.parseInput("12%").literal)
        assertEquals("0%", b.parseInput("12%").impliedFormat)
        assertEquals(FV.Num(1234.0), b.parseInput("1,234").literal)
        assertEquals(FV.Num(45.0), b.parseInput("\u0664\u0665").literal)   // Arabic-Indic digits
        assertEquals(FV.Str("007"), b.parseInput("'007").literal)
        assertEquals("yyyy-mm-dd", b.parseInput("2024-05-01").impliedFormat)
        assertEquals(FV.TRUE, b.parseInput("true").literal)
        assertEquals("A1+1", b.parseInput("=A1+1").formula)
    }

    // ---------------- xlsx round trip ----------------

    @Test fun xlsxSaveAndReload() {
        val w = XlsxIO5.blank("Data", rtl = true); val s = w.book.sheets[0]
        w.setInput(s, 0, 0, "\u0627\u0644\u0627\u0633\u0645"); w.setInput(s, 1, 0, "3"); w.setInput(s, 2, 0, "=A2*2")
        w.applyFormat(s, CRange.cell(1, 0)) { it.copy(bold = true, fill = "FFFF00", numFmt = "0.00", left = "thin", right = "thin", top = "thin", bottom = "thin") }
        w.setColWidth(s, 0..0, 20f); w.freeze(s, 1, 0); w.merge(s, CRange(5, 0, 5, 2))
        val s2 = w.addSheet("Two"); w.setInput(s2, 0, 0, "=Data!A3+1")
        val f = File.createTempFile("o5t", ".xlsx"); f.deleteOnExit()
        XlsxIO5.save(w, f)
        val r = XlsxIO5.load(f)
        val a = r.book.sheets[0]; val b = r.book.sheets[1]
        assertEquals(listOf("Data", "Two"), r.book.sheets.map { it.name })
        assertEquals("\u0627\u0644\u0627\u0633\u0645", r.book.display(a, 0, 0).text)
        assertEquals("3.00", r.book.display(a, 1, 0).text)
        assertEquals(FV.Num(6.0), r.book.value(a, 2, 0))
        assertEquals(FV.Num(7.0), r.book.value(b, 0, 0))
        val fmt = r.format(a, 1, 0)
        assertTrue(fmt.bold); assertEquals("FFFF00", fmt.fill); assertEquals("thin", fmt.left)
        assertEquals(20f, a.colWidth(0)); assertEquals(1, a.freezeRows); assertTrue(a.rtl)
        assertEquals(listOf(CRange(5, 0, 5, 2)), a.merges)
        // cached values are written for other apps
        val sheetXml = String(r.pkg!!.bytes(a.partPath!!)!!)
        assertTrue(sheetXml.contains("<f>A2*2</f><v>6</v>"))
        // delete a sheet and rename the other
        r.deleteSheet(b); r.renameSheet(a, "Main")
        val g = File.createTempFile("o5t", ".xlsx"); g.deleteOnExit()
        XlsxIO5.save(r, g)
        val q = XlsxIO5.load(g)
        assertEquals(listOf("Main"), q.book.sheets.map { it.name })
        assertTrue(q.pkg!!.names.none { it.endsWith("sheet2.xml") })
    }

    @Test fun sharedFormulasAreExpanded() {
        val w = XlsxIO5.blank(rtl = false)
        val path = w.book.sheets[0].partPath!!
        w.pkg!!.put(path, ("<?xml version=\"1.0\"?><worksheet xmlns=\"${XlsxIO5.NS}\"><sheetData>" +
            "<row r=\"1\"><c r=\"A1\"><v>1</v></c><c r=\"B1\"><f t=\"shared\" ref=\"B1:B3\" si=\"0\">A1*10</f><v>10</v></c></row>" +
            "<row r=\"2\"><c r=\"A2\"><v>2</v></c><c r=\"B2\"><f t=\"shared\" si=\"0\"/><v>20</v></c></row>" +
            "<row r=\"3\"><c r=\"A3\"><v>3</v></c><c r=\"B3\"><f t=\"shared\" si=\"0\"/><v>30</v></c></row></sheetData></worksheet>").toByteArray())
        val r = XlsxIO5.load(w.pkg)
        val s = r.book.sheets[0]
        assertEquals("A3*10", s.cell(2, 1)!!.formula)
        assertEquals(FV.Num(30.0), r.book.value(s, 2, 1))
    }

    @Test fun csvRoundTrip() {
        val w = CsvIO.fromText("name;qty\r\n\"a;b\";3\r\nc;=B2*2\r\n", ';', "t", rtl = false)
        val s = w.book.sheets[0]
        assertEquals("a;b", w.book.display(s, 1, 0).text)
        assertEquals(FV.Num(6.0), w.book.value(s, 2, 1))
        assertEquals("name;qty\r\n\"a;b\";3\r\nc;6\r\n", CsvIO.toText(w, s, ';'))
        assertEquals(';', CsvIO.sniff("a;b;c\n1;2;3"))
    }
}
