package com.smartdocs.editor.core.office5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FormulaTest {
    private fun book(): Pair<Workbook5, Sheet5> { val w = XlsxIO5.blank("Sheet1", rtl = false); return w to w.book.sheets[0] }

    private fun Workbook5.set(s: Sheet5, a1: String, v: String) { val r = A1.parseCell(a1)!!; assertNull(setInput(s, r.row, r.col, v)) }
    private fun Workbook5.v(s: Sheet5, a1: String): FV { val r = A1.parseCell(a1)!!; return book.value(s, r.row, r.col) }
    private fun Workbook5.n(s: Sheet5, a1: String): Double = (v(s, a1) as FV.Num).v
    private fun calc(f: String): FV { val (w, s) = book(); return w.book.evalFormula(s, 0, 0, f) }

    @Test fun precedence() {
        assertEquals(FV.Num(7.0), calc("1+2*3"))
        assertEquals(FV.Num(9.0), calc("(1+2)*3"))
        assertEquals(FV.Num(4.0), calc("-2^2"))          // Excel: negation before power
        assertEquals(FV.Num(512.0), calc("2^(3*3)"))
        assertEquals(FV.Num(64.0), calc("2^3^2"))        // left associative like Excel
        assertEquals(FV.Num(0.05), calc("5%"))
        assertEquals(FV.Str("a3"), calc("\"a\"&1+2"))
        assertEquals(FV.TRUE, calc("1+1=2"))
        assertEquals(FV.FALSE, calc("\"abc\"<>\"ABC\""))   // text compares case-insensitively
        assertEquals(FV.Num(2.5), calc("10/4"))
    }

    @Test fun referencesAndRanges() {
        val (w, s) = book()
        w.set(s, "A1", "1"); w.set(s, "A2", "2"); w.set(s, "A3", "3"); w.set(s, "B1", "x")
        w.set(s, "C1", "=SUM(A1:A3)")
        w.set(s, "C2", "=A1+A2*A3")
        w.set(s, "C3", "=SUM(A:A)")
        w.set(s, "C4", "=COUNT(A1:B3)")
        w.set(s, "C5", "=COUNTA(A1:B3)")
        assertEquals(6.0, w.n(s, "C1"), 0.0); assertEquals(7.0, w.n(s, "C2"), 0.0); assertEquals(6.0, w.n(s, "C3"), 0.0)
        assertEquals(3.0, w.n(s, "C4"), 0.0); assertEquals(4.0, w.n(s, "C5"), 0.0)
        // dependency graph: changing an input recalculates dependents transitively
        w.set(s, "D1", "=C1*2")
        w.set(s, "A1", "10")
        assertEquals(15.0, w.n(s, "C1"), 0.0); assertEquals(30.0, w.n(s, "D1"), 0.0); assertEquals(15.0, w.n(s, "C3"), 0.0)
    }

    @Test fun crossSheetReferences() {
        val (w, s) = book()
        val t = w.addSheet("Data Sheet")
        w.set(t, "A1", "5")
        w.set(s, "A1", "='Data Sheet'!A1*3")
        assertEquals(15.0, w.n(s, "A1"), 0.0)
        w.set(t, "A1", "6")
        assertEquals(18.0, w.n(s, "A1"), 0.0)
        // Arabic sheet names, quoted and unquoted
        val ar = w.addSheet("\u0648\u0631\u0642\u0629")
        w.set(ar, "B2", "4")
        w.set(s, "A2", "=\u0648\u0631\u0642\u0629!B2+'\u0648\u0631\u0642\u0629'!B2")
        assertEquals(8.0, w.n(s, "A2"), 0.0)
        w.renameSheet(t, "Data")
        assertEquals("Data!A1*3", s.cell(0, 0)!!.formula)
        w.deleteSheet(t)
        assertEquals(FV.REF, w.v(s, "A1"))
    }

    @Test fun functions() {
        val (w, s) = book()
        listOf("A1" to "4", "A2" to "8", "A3" to "15", "B1" to "apple", "B2" to "banana", "B3" to "cherry").forEach { (a, v) -> w.set(s, a, v) }
        assertEquals(FV.Num(9.0), w.book.evalFormula(s, 5, 5, "AVERAGE(A1:A3)"))
        assertEquals(FV.Num(4.0), w.book.evalFormula(s, 5, 5, "MIN(A1:A3)"))
        assertEquals(FV.Num(15.0), w.book.evalFormula(s, 5, 5, "MAX(A1:A3,2)"))
        assertEquals(FV.Str("big"), w.book.evalFormula(s, 5, 5, "IF(A3>10,\"big\",\"small\")"))
        assertEquals(FV.TRUE, w.book.evalFormula(s, 5, 5, "AND(A1>1,A2>1)"))
        assertEquals(FV.FALSE, w.book.evalFormula(s, 5, 5, "OR(A1>100,FALSE)"))
        assertEquals(FV.Num(3.14), w.book.evalFormula(s, 5, 5, "ROUND(3.14159,2)"))
        assertEquals(FV.Num(1250.0), w.book.evalFormula(s, 5, 5, "ROUND(1249.5,-1)"))
        assertEquals(FV.Num(-3.0), w.book.evalFormula(s, 5, 5, "ROUND(-2.5,0)"))     // half away from zero
        assertEquals(FV.Num(5.0), w.book.evalFormula(s, 5, 5, "ABS(-5)"))
        assertEquals(FV.Str("applebanana1"), w.book.evalFormula(s, 5, 5, "CONCAT(B1:B2,1)"))
        assertEquals(FV.Num(6.0), w.book.evalFormula(s, 5, 5, "LEN(B2)"))
        assertEquals(FV.Str("ban"), w.book.evalFormula(s, 5, 5, "LEFT(B2,3)"))
        assertEquals(FV.Str("rry"), w.book.evalFormula(s, 5, 5, "RIGHT(B3,3)"))
        assertEquals(FV.Str("an"), w.book.evalFormula(s, 5, 5, "MID(B2,2,2)"))
        assertEquals(FV.Str("banana"), w.book.evalFormula(s, 5, 5, "VLOOKUP(8,A1:B3,2,FALSE)"))
        assertEquals(FV.Str("banana"), w.book.evalFormula(s, 5, 5, "VLOOKUP(9,A1:B3,2)"))    // approximate
        assertEquals(FV.NA, w.book.evalFormula(s, 5, 5, "VLOOKUP(99,A1:B3,2,FALSE)"))
        assertEquals(FV.Str("none"), w.book.evalFormula(s, 5, 5, "IFERROR(VLOOKUP(99,A1:B3,2,FALSE),\"none\")"))
        assertEquals(FV.Num(23.0), w.book.evalFormula(s, 5, 5, "SUMIF(A1:A3,\">5\")"))
        assertEquals(FV.Num(1.0), w.book.evalFormula(s, 5, 5, "COUNTIF(B1:B3,\"*an*\")"))
        assertEquals(FV.Num(2.0), w.book.evalFormula(s, 5, 5, "COUNTIF(A1:A3,\">=8\")"))
        assertTrue((w.book.evalFormula(s, 5, 5, "TODAY()") as FV.Num).v > 45000)
        assertTrue((w.book.evalFormula(s, 5, 5, "NOW()") as FV.Num).v > 45000)
    }

    @Test fun errors() {
        assertEquals(FV.DIV0, calc("1/0"))
        assertEquals(FV.VALUE, calc("\"a\"+1"))
        assertEquals(FV.NAME, calc("NOSUCHFN(1)"))
        assertEquals(FV.NAME, calc("undefinedName+1"))
        assertEquals(FV.DIV0, calc("AVERAGE(Z1:Z3)"))
        assertEquals(FV.DIV0, calc("SUM(1,1/0)"))      // errors propagate through functions
        assertEquals(FV.Num(1.0), calc("IFERROR(1/0,1)"))
        assertEquals(FV.REF, calc("Missing!A1"))
        try { FxParser.parse("SUM(1,"); fail() } catch (e: FxParseException) {}
        try { FxParser.parse("1+"); fail() } catch (e: FxParseException) {}
    }

    @Test fun circularReferences() {
        val (w, s) = book()
        w.set(s, "A1", "=B1+1")
        w.set(s, "B1", "=A1+1")
        assertEquals(FV.CIRC, w.v(s, "A1")); assertEquals(FV.CIRC, w.v(s, "B1"))
        w.set(s, "C1", "=C1")
        assertEquals(FV.CIRC, w.v(s, "C1"))
        // breaking the cycle recovers
        w.set(s, "B1", "5")
        assertEquals(6.0, w.n(s, "A1"), 0.0)
    }

    @Test fun longChainEvaluatesIteratively() {
        val (w, s) = book()
        w.set(s, "A1", "1")
        for (r in 1 until 3000) s.cells[Sheet5.key(r, 0)] = Cell5("A$r+1", FV.Blank)
        w.book.recalcAll()
        assertEquals(3000.0, w.n(s, "A3000"), 0.0)
        w.set(s, "A1", "2")
        assertEquals(3001.0, w.n(s, "A3000"), 0.0)
    }

    @Test fun printerRoundTrip() {
        for (f in listOf("SUM(A1:B2)*2", "IF(A1>=10,\"a\"\"b\",-B\$2)", "'My Sheet'!\$A\$1+Sheet2!C:C", "1:3", "ROUND(A1,)", "A1%")) {
            assertEquals(f, FxPrinter.print(FxParser.parse(f)))
        }
    }

    @Test fun unsupportedFunctionKeepsCachedValue() {
        val (w, s) = book()
        s.cells[0] = Cell5("XIRR(B1:B3,C1:C3)", FV.Blank, 0, -1, FV.Num(0.123))
        w.book.recalcAll()
        assertEquals(FV.Num(0.123), w.v(s, "A1"))
        assertTrue(w.book.usesUnsupported(s, 0, 0))
    }
}
