package com.smartdocs.editor.core.office5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The function library beyond the basic set covered by [FormulaTest]. Expected values are the ones Microsoft's
 * function reference gives for the same arguments (or plain arithmetic); each function also has its main error.
 */
class FxLibraryTest {
    /** A sheet filled column by column; formulas are evaluated from K31, away from the data. */
    private class Grid(vararg cols: Pair<String, List<String>>) {
        val w: Workbook5 = XlsxIO5.blank("Sheet1", rtl = false)
        val s: Sheet5 = w.book.sheets[0]

        init { for ((col, vals) in cols) vals.forEachIndexed { r, v -> if (v.isNotEmpty()) set(col + (r + 1), v) } }

        fun set(a1: String, v: String) { val r = A1.parseCell(a1)!!; assertNull(w.setInput(s, r.row, r.col, v)) }
        fun at(a1: String): FV { val r = A1.parseCell(a1)!!; return w.book.value(s, r.row, r.col) }
        fun calc(f: String): FV = w.book.evalFormula(s, 30, 10, f)
        fun num(expected: Double, f: String, delta: Double = 1e-9) { val v = calc(f); assertTrue("$f = $v", v is FV.Num); assertEquals(f, expected, (v as FV.Num).v, delta) }
        fun eq(expected: FV, f: String) = assertEquals(f, expected, calc(f))
        fun str(expected: String, f: String) = eq(FV.Str(expected), f)
    }

    private val blank = Grid()
    private fun num(expected: Double, f: String, delta: Double = 1e-9) = blank.num(expected, f, delta)
    private fun eq(expected: FV, f: String) = blank.eq(expected, f)
    private fun str(expected: String, f: String) = blank.str(expected, f)

    @Test fun registry() {
        val names = FxFunctions.all.map { it.name }.toSet()
        val added = ("SIN COS TAN ASIN ACOS ATAN ATAN2 SINH COSH TANH EXP LN LOG LOG10 FLOOR CEILING FLOOR.MATH CEILING.MATH TRUNC EVEN ODD FACT COMBIN " +
            "PERMUT GCD LCM QUOTIENT MROUND RAND RANDBETWEEN RADIANS DEGREES SUMPRODUCT SUMSQ SUMIFS " +
            "MEDIAN MODE MODE.SNGL STDEV STDEV.S STDEV.P STDEVP VAR VAR.S VAR.P VARP LARGE SMALL RANK RANK.EQ PERCENTILE PERCENTILE.INC QUARTILE " +
            "QUARTILE.INC COUNTIFS AVERAGEIFS MAXIFS MINIFS AVERAGEA MAXA MINA GEOMEAN HARMEAN CORREL SLOPE INTERCEPT FORECAST FORECAST.LINEAR " +
            "IFS SWITCH XOR FIND SEARCH SUBSTITUTE REPLACE REPT PROPER EXACT TEXTJOIN CHAR CODE UNICHAR UNICODE CLEAN T N FIXED DOLLAR NUMBERVALUE " +
            "EDATE EOMONTH DATEDIF DAYS DAYS360 WEEKDAY WEEKNUM ISOWEEKNUM HOUR MINUTE SECOND TIME DATEVALUE TIMEVALUE NETWORKDAYS WORKDAY YEARFRAC " +
            "INDEX MATCH XLOOKUP XMATCH LOOKUP ROW COLUMN ROWS COLUMNS ISEVEN ISODD ISLOGICAL ISNONTEXT ISNA ISERR TYPE ERROR.TYPE " +
            "PMT PV FV NPER RATE NPV IRR IPMT PPMT SLN").split(' ')
        assertEquals(emptyList<String>(), added.filter { it !in names })
        assertEquals(54 + added.size, names.size)
        // every category has a chip in the function list (SheetEditor) and a string in both languages
        assertEquals(setOf("math", "stat", "logical", "text", "date", "lookup", "info", "fin"), FxFunctions.all.map { it.category }.toSet())
        assertTrue(FxFunctions.isVolatile("RAND")); assertTrue(FxFunctions.isVolatile("RANDBETWEEN")); assertFalse(FxFunctions.isVolatile("SIN"))
        // newer functions are stored with a prefix in xlsx files
        num(1.0, "_xlfn.IFS(TRUE,1)"); num(1.0, "_xlfn.STDEV.S(1,2,3)"); num(6.0, "_xlfn.FLOOR.MATH(6.7)")
    }

    @Test fun trigonometryAndLogarithms() {
        num(1.0, "SIN(PI()/2)"); num(0.0, "SIN(0)"); num(-1.0, "COS(PI())"); num(1.0, "TAN(PI()/4)")
        num(Math.PI / 2, "ASIN(1)"); num(0.0, "ACOS(1)"); num(Math.PI / 4, "ATAN(1)")
        num(Math.PI / 4, "ATAN2(1,1)"); num(Math.PI, "ATAN2(-1,0)"); num(-3 * Math.PI / 4, "ATAN2(-1,-1)")
        num(1.175201194, "SINH(1)"); num(27.308233, "COSH(4)", 1e-6); num(0.462117, "TANH(0.5)", 1e-6)
        num(Math.E, "EXP(1)"); num(1.0, "EXP(0)")
        num(4.4543473, "LN(86)", 1e-7); num(0.0, "LN(1)")
        num(1.0, "LOG(10)"); num(2.0, "LOG(100)"); num(3.0, "LOG(1000)", 0.0); num(3.0, "LOG(8,2)"); num(4.4543473, "LOG(86,2.7182818)", 1e-7)
        num(1.9345, "LOG10(86)", 1e-4); num(5.0, "LOG10(1E5)")
        num(4.712389, "RADIANS(270)", 1e-6); num(180.0, "DEGREES(PI())")
        // errors
        for (f in listOf("ASIN(2)", "ACOS(-2)", "EXP(1000)", "LN(0)", "LN(-1)", "LOG(0)", "LOG(8,-2)", "LOG10(0)")) eq(FV.NUM, f)
        eq(FV.DIV0, "ATAN2(0,0)"); eq(FV.DIV0, "LOG(8,1)")
        for (f in listOf("SIN", "COS", "TAN", "ASIN", "ACOS", "ATAN", "SINH", "COSH", "TANH", "EXP", "LN", "LOG", "LOG10", "RADIANS", "DEGREES")) eq(FV.VALUE, "$f(\"a\")")
        eq(FV.VALUE, "ATAN2(\"a\",1)")
    }

    @Test fun rounding() {
        num(2.0, "FLOOR(3.7,2)"); num(-2.0, "FLOOR(-2.5,-2)"); num(-4.0, "FLOOR(-2.5,2)"); num(1.5, "FLOOR(1.58,0.1)", 0.0); num(0.23, "FLOOR(0.234,0.01)", 0.0)
        num(0.3, "FLOOR(0.3,0.1)", 0.0)                       // 0.3 / 0.1 is 2.9999999999999996 in binary
        eq(FV.NUM, "FLOOR(2.5,-2)"); eq(FV.DIV0, "FLOOR(5,0)")
        num(3.0, "CEILING(2.5,1)"); num(-4.0, "CEILING(-2.5,-2)"); num(-2.0, "CEILING(-2.5,2)"); num(1.5, "CEILING(1.5,0.1)", 0.0); num(0.24, "CEILING(0.234,0.01)", 0.0)
        eq(FV.NUM, "CEILING(2.5,-2)"); num(0.0, "CEILING(2.5,0)")
        num(20.0, "FLOOR.MATH(24.3,5)"); num(6.0, "FLOOR.MATH(6.7)"); num(-10.0, "FLOOR.MATH(-8.1,2)"); num(-4.0, "FLOOR.MATH(-5.5,2,-1)")
        num(25.0, "CEILING.MATH(24.3,5)"); num(7.0, "CEILING.MATH(6.7)"); num(-8.0, "CEILING.MATH(-8.1,2)"); num(-6.0, "CEILING.MATH(-5.5,2,-1)")
        num(8.0, "TRUNC(8.9)"); num(-8.0, "TRUNC(-8.9)"); num(0.0, "TRUNC(0.45)"); num(3.14, "TRUNC(3.14159,2)", 0.0); num(1200.0, "TRUNC(1299,-2)")
        num(2.0, "EVEN(1.5)"); num(4.0, "EVEN(3)"); num(2.0, "EVEN(2)"); num(-2.0, "EVEN(-1)")
        num(3.0, "ODD(1.5)"); num(3.0, "ODD(3)"); num(3.0, "ODD(2)"); num(-1.0, "ODD(-1)"); num(-3.0, "ODD(-2)")
        num(9.0, "MROUND(10,3)"); num(-9.0, "MROUND(-10,-3)"); num(1.4, "MROUND(1.3,0.2)", 0.0); eq(FV.NUM, "MROUND(5,-2)")
        num(2.0, "QUOTIENT(5,2)"); num(1.0, "QUOTIENT(4.5,3.1)"); num(-3.0, "QUOTIENT(-10,3)"); eq(FV.DIV0, "QUOTIENT(1,0)")
        for (f in listOf("FLOOR(\"a\",1)", "CEILING(\"a\",1)", "FLOOR.MATH(\"a\")", "CEILING.MATH(\"a\")", "TRUNC(\"a\")", "EVEN(\"a\")", "ODD(\"a\")", "MROUND(\"a\",1)", "QUOTIENT(\"a\",1)")) eq(FV.VALUE, f)
    }

    @Test fun integersAndRandom() {
        num(120.0, "FACT(5)"); num(1.0, "FACT(1.9)"); num(1.0, "FACT(0)"); eq(FV.NUM, "FACT(-1)"); eq(FV.NUM, "FACT(171)")
        num(28.0, "COMBIN(8,2)"); eq(FV.NUM, "COMBIN(2,8)"); eq(FV.NUM, "COMBIN(-1,1)")
        num(970200.0, "PERMUT(100,3)"); num(6.0, "PERMUT(3,2)"); eq(FV.NUM, "PERMUT(2,3)")
        num(1.0, "GCD(5,2)"); num(12.0, "GCD(24,36)"); num(1.0, "GCD(7,1)"); num(5.0, "GCD(5,0)"); eq(FV.NUM, "GCD(-1,2)")
        num(10.0, "LCM(5,2)"); num(72.0, "LCM(24,36)"); eq(FV.NUM, "LCM(-1,2)")
        repeat(50) {
            val r = (blank.calc("RAND()") as FV.Num).v; assertTrue(r >= 0.0 && r < 1.0)
            val d = (blank.calc("RANDBETWEEN(1,6)") as FV.Num).v; assertTrue(d in 1.0..6.0 && d == Math.floor(d))
        }
        num(4.0, "RANDBETWEEN(4,4)"); eq(FV.NUM, "RANDBETWEEN(5,1)"); eq(FV.VALUE, "RANDBETWEEN(\"a\",1)")
        // a volatile cell is recalculated by an unrelated edit
        val g = Grid(); g.set("A1", "=RAND()")
        val seen = HashSet<FV>(); repeat(5) { g.set("B1", "$it"); seen.add(g.at("A1")) }
        assertTrue(seen.size > 1)
    }

    @Test fun sums() {
        num(25.0, "SUMSQ(3,4)"); eq(FV.VALUE, "SUMSQ(\"a\")")
        val g = Grid("A" to listOf("3", "8", "1"), "B" to listOf("4", "6", "9"), "D" to listOf("2", "6", "5"), "E" to listOf("7", "7", "3"))
        g.num(156.0, "SUMPRODUCT(A1:B3,D1:E3)"); g.num(19.0, "SUMPRODUCT(B1:B3)"); g.num(6.0, "SUMPRODUCT(2,3)")
        g.eq(FV.VALUE, "SUMPRODUCT(A1:B3,D1:D3)")
        // an argument is evaluated like an array formula: operators over ranges work element by element
        g.num(10.0, "SUMPRODUCT((A1:A3>2)*B1:B3)"); g.num(2.0, "SUMPRODUCT(--(A1:A3>2))"); g.num(0.0, "SUMPRODUCT(A1:A3>2)")
        g.num(138.0, "SUMPRODUCT(A1:A3*2,B1:B3)"); g.num(0.12, "SUMPRODUCT(A1:A3%)"); g.num(-12.0, "SUMPRODUCT(-A1:A3)"); g.eq(FV.DIV0, "SUMPRODUCT(A1:A3/0)")
        // …but a function applied to every element is not: the cell keeps the value Excel saved
        g.s.cells[Sheet5.key(9, 0)] = Cell5("SUMPRODUCT(LEN(A1:A3))", FV.Blank, 0, -1, FV.Num(42.0))
        g.w.book.recalcAll()
        assertEquals(FV.Num(42.0), g.at("A10"))
        // SUMIFS: quantities of products that start with A and were sold by Tom
        val q = Grid("A" to listOf("5", "4", "15", "3", "22", "12", "10", "33"),
            "B" to listOf("Apples", "Apples", "Artichokes", "Artichokes", "Bananas", "Bananas", "Carrots", "Carrots"),
            "C" to listOf("Tom", "Sarah", "Tom", "Sarah", "Tom", "Sarah", "Tom", "Sarah"))
        q.num(20.0, "SUMIFS(A1:A8,B1:B8,\"=A*\",C1:C8,\"Tom\")"); q.num(30.0, "SUMIFS(A1:A8,B1:B8,\"<>Bananas\",C1:C8,\"Tom\")")
        q.num(0.0, "SUMIFS(A1:A8,B1:B8,\"Kiwis\")"); q.eq(FV.VALUE, "SUMIFS(A1:A8,B1:B7,\"=A*\")")
        q.num(2.0, "COUNTIFS(B1:B8,\"=A*\",C1:C8,\"Tom\")"); q.num(4.0, "COUNTIFS(A1:A8,\">=10\",A1:A8,\"<30\")"); q.eq(FV.VALUE, "COUNTIFS(B1:B8,\"=A*\",C1:C7,\"Tom\")")
        q.num(10.0, "AVERAGEIFS(A1:A8,B1:B8,\"=A*\",C1:C8,\"Tom\")"); q.eq(FV.DIV0, "AVERAGEIFS(A1:A8,B1:B8,\"Kiwis\")"); q.eq(FV.VALUE, "AVERAGEIFS(A1:A8,B1:B7,\"x\")")
        q.num(22.0, "MAXIFS(A1:A8,C1:C8,\"Tom\")"); q.num(0.0, "MAXIFS(A1:A8,C1:C8,\"Ann\")"); q.eq(FV.VALUE, "MAXIFS(A1:A8,C1:C7,\"Tom\")")
        q.num(3.0, "MINIFS(A1:A8,C1:C8,\"Sarah\")"); q.num(0.0, "MINIFS(A1:A8,C1:C8,\"Ann\")"); q.eq(FV.VALUE, "MINIFS(A1:A8,C1:C7,\"Tom\")")
    }

    @Test fun statistics() {
        num(3.0, "MEDIAN(1,2,3,4,5)"); num(3.5, "MEDIAN(1,2,3,4,5,6)"); eq(FV.NUM, "MEDIAN(Z1:Z9)")
        num(4.0, "MODE(5.6,4,4,3,2,4)"); num(4.0, "MODE.SNGL(5.6,4,4,3,2,4)"); eq(FV.NA, "MODE(1,2,3)"); eq(FV.NA, "MODE.SNGL(1,2,3)")
        val g = Grid("A" to listOf("1345", "1301", "1368", "1322", "1310", "1370", "1318", "1350", "1303", "1299"))
        g.num(27.46391572, "STDEV(A1:A10)", 1e-8); g.num(27.46391572, "STDEV.S(A1:A10)", 1e-8)
        g.num(26.05455814, "STDEV.P(A1:A10)", 1e-8); g.num(26.05455814, "STDEVP(A1:A10)", 1e-8)
        g.num(754.2666667, "VAR(A1:A10)", 1e-7); g.num(754.2666667, "VAR.S(A1:A10)", 1e-7)
        g.num(678.84, "VAR.P(A1:A10)"); g.num(678.84, "VARP(A1:A10)")
        for (f in listOf("STDEV", "STDEV.S", "VAR", "VAR.S")) eq(FV.DIV0, "$f(5)")
        for (f in listOf("STDEV.P", "STDEVP", "VAR.P", "VARP")) { eq(FV.DIV0, "$f(Z1:Z9)"); num(0.0, "$f(5)") }
        // LARGE / SMALL
        val l = Grid("A" to listOf("3", "5", "3", "5", "4"), "B" to listOf("4", "2", "4", "6", "7"),
            "D" to listOf("3", "4", "5", "2", "3", "4", "6", "4", "7"), "E" to listOf("1", "4", "8", "3", "7", "12", "54", "8", "23"))
        l.num(5.0, "LARGE(A1:B5,3)"); l.num(4.0, "LARGE(A1:B5,7)"); l.eq(FV.NUM, "LARGE(A1:B5,0)"); l.eq(FV.NUM, "LARGE(A1:B5,11)")
        l.num(4.0, "SMALL(D1:D9,4)"); l.num(3.0, "SMALL(E1:E9,2)"); l.eq(FV.NUM, "SMALL(D1:D9,0)"); l.eq(FV.NUM, "SMALL(D1:D9,10)")
        // RANK
        val r = Grid("A" to listOf("7", "3.5", "3.5", "1", "2"))
        r.num(3.0, "RANK(A2,A1:A5,1)"); r.num(5.0, "RANK(A1,A1:A5,1)"); r.num(1.0, "RANK(A1,A1:A5)"); r.eq(FV.NA, "RANK(99,A1:A5)")
        r.num(5.0, "RANK.EQ(A1,A1:A5,1)"); r.num(4.0, "RANK.EQ(A5,A1:A5)"); r.num(3.0, "RANK.EQ(A2,A1:A5,1)"); r.eq(FV.NA, "RANK.EQ(99,A1:A5)")
        // PERCENTILE / QUARTILE
        val p = Grid("A" to listOf("1", "2", "3", "4"), "B" to listOf("1", "2", "4", "7", "8", "9", "10", "12"))
        p.num(1.9, "PERCENTILE(A1:A4,0.3)"); p.num(1.9, "PERCENTILE.INC(A1:A4,0.3)"); p.num(4.0, "PERCENTILE(A1:A4,1)"); p.num(1.0, "PERCENTILE(A1:A4,0)")
        p.eq(FV.NUM, "PERCENTILE(A1:A4,1.5)"); p.eq(FV.NUM, "PERCENTILE.INC(A1:A4,-0.1)"); p.eq(FV.NUM, "PERCENTILE(Z1:Z4,0.5)")
        p.num(3.5, "QUARTILE(B1:B8,1)"); p.num(3.5, "QUARTILE.INC(B1:B8,1)"); p.num(7.5, "QUARTILE(B1:B8,2)"); p.num(12.0, "QUARTILE(B1:B8,4)")
        p.eq(FV.NUM, "QUARTILE(B1:B8,5)"); p.eq(FV.NUM, "QUARTILE.INC(B1:B8,-1)")
        // the "A" aggregates count text in cells as 0 and booleans as 1 / 0
        val a = Grid("A" to listOf("10", "7", "9", "2", "Not available"), "B" to listOf("0", "0.2", "0.5", "0.4", "TRUE"), "C" to listOf("FALSE", "0.2", "0.5", "0.4", "0.8"))
        a.num(5.6, "AVERAGEA(A1:A5)"); a.num(7.0, "AVERAGE(A1:A5)"); a.eq(FV.DIV0, "AVERAGEA(Z1:Z5)"); a.eq(FV.VALUE, "AVERAGEA(\"a\")")
        a.num(1.0, "MAXA(B1:B5)"); a.num(0.5, "MAX(B1:B5)"); a.eq(FV.VALUE, "MAXA(\"a\")")
        a.num(0.0, "MINA(C1:C5)"); a.num(0.2, "MIN(C1:C5)"); a.eq(FV.VALUE, "MINA(\"a\")")
        num(5.476987, "GEOMEAN(4,5,8,7,11,4,3)", 1e-6); eq(FV.NUM, "GEOMEAN(1,0)"); eq(FV.NUM, "GEOMEAN(1,-2)")
        num(5.028376, "HARMEAN(4,5,8,7,11,4,3)", 1e-6); eq(FV.NUM, "HARMEAN(1,0)"); eq(FV.NUM, "HARMEAN(1,-2)")
    }

    @Test fun regression() {
        val g = Grid("A" to listOf("3", "2", "4", "5", "6"), "B" to listOf("9", "7", "12", "15", "17"),
            "D" to listOf("2", "3", "9", "1", "8", "7", "5"), "E" to listOf("6", "5", "11", "7", "5", "4", "4"),
            "G" to listOf("6", "7", "9", "15", "21"), "H" to listOf("20", "28", "31", "38", "40"), "I" to listOf("1", "1", "1", "1", "1"))
        g.num(0.997054486, "CORREL(A1:A5,B1:B5)"); g.eq(FV.NA, "CORREL(A1:A5,B1:B4)"); g.eq(FV.DIV0, "CORREL(A1:A5,I1:I5)")
        g.num(0.305556, "SLOPE(D1:D7,E1:E7)", 1e-6); g.eq(FV.NA, "SLOPE(D1:D7,E1:E6)"); g.eq(FV.DIV0, "SLOPE(A1:A5,I1:I5)")
        g.num(0.048387097, "INTERCEPT(D1:D5,E1:E5)"); g.eq(FV.NA, "INTERCEPT(D1:D5,E1:E4)"); g.eq(FV.DIV0, "INTERCEPT(A1:A5,I1:I5)")
        g.num(10.607253, "FORECAST(30,G1:G5,H1:H5)", 1e-6); g.num(10.607253, "FORECAST.LINEAR(30,G1:G5,H1:H5)", 1e-6)
        g.eq(FV.NA, "FORECAST(30,G1:G5,H1:H4)"); g.eq(FV.DIV0, "FORECAST(30,G1:G5,I1:I5)"); g.eq(FV.NA, "FORECAST.LINEAR(30,G1:G5,H1:H4)"); g.eq(FV.VALUE, "FORECAST(\"a\",G1:G5,H1:H5)")
    }

    @Test fun logical() {
        num(2.0, "IFS(FALSE,1,TRUE,2)"); eq(FV.NA, "IFS(FALSE,1)"); eq(FV.VALUE, "IFS(\"a\",1)"); eq(FV.DIV0, "IFS(1/0,1,TRUE,2)")
        val g = Grid("A" to listOf("85"))
        g.str("B", "IFS(A1>89,\"A\",A1>79,\"B\",TRUE,\"F\")")
        num(1.0, "IFS(TRUE,1,1/0,2)")                       // later conditions are not evaluated
        str("two", "SWITCH(2,1,\"one\",2,\"two\",\"other\")"); str("other", "SWITCH(9,1,\"one\",\"other\")"); eq(FV.NA, "SWITCH(9,1,\"one\")")
        str("one", "SWITCH(1,1,\"one\",1/0,\"never\")"); eq(FV.DIV0, "SWITCH(1/0,1,\"one\")")
        eq(FV.TRUE, "XOR(TRUE,FALSE)"); eq(FV.FALSE, "XOR(TRUE,TRUE)"); eq(FV.FALSE, "XOR(3>0,2<9)"); eq(FV.FALSE, "XOR(3>12,4>6)"); eq(FV.TRUE, "XOR(1,1,1)")
        eq(FV.VALUE, "XOR(\"a\")"); eq(FV.VALUE, "XOR(Z1:Z3)")
        // AND / OR are unchanged by sharing their loop with XOR
        eq(FV.FALSE, "AND(TRUE,FALSE)"); eq(FV.TRUE, "OR(FALSE,TRUE)"); eq(FV.VALUE, "AND(Z1:Z3)")
    }

    @Test fun text() {
        num(1.0, "FIND(\"M\",\"Miriam McGovern\")"); num(6.0, "FIND(\"m\",\"Miriam McGovern\")"); num(8.0, "FIND(\"M\",\"Miriam McGovern\",3)")
        eq(FV.VALUE, "FIND(\"z\",\"abc\")"); eq(FV.VALUE, "FIND(\"a\",\"abc\",0)"); eq(FV.VALUE, "FIND(\"a\",\"abc\",9)")
        num(7.0, "SEARCH(\"e\",\"Statements\",6)"); num(8.0, "SEARCH(\"margin\",\"Profit Margin\")"); num(8.0, "SEARCH(\"m?r*n\",\"Profit Margin\")")
        eq(FV.VALUE, "SEARCH(\"z\",\"abc\")"); eq(FV.VALUE, "SEARCH(\"a\",\"abc\",0)")
        str("Cost Data", "SUBSTITUTE(\"Sales Data\",\"Sales\",\"Cost\")"); str("Quarter 2, 2008", "SUBSTITUTE(\"Quarter 1, 2008\",\"1\",\"2\",1)")
        str("Quarter 1, 2012", "SUBSTITUTE(\"Quarter 1, 2011\",\"1\",\"2\",3)"); str("b-b-b", "SUBSTITUTE(\"a-a-a\",\"a\",\"b\")"); str("a-a", "SUBSTITUTE(\"a-a\",\"A\",\"b\")")
        eq(FV.VALUE, "SUBSTITUTE(\"a\",\"a\",\"b\",0)")
        str("abcde*k", "REPLACE(\"abcdefghijk\",6,5,\"*\")"); str("2010", "REPLACE(\"2009\",3,2,\"10\")"); str("@456", "REPLACE(\"123456\",1,3,\"@\")")
        eq(FV.VALUE, "REPLACE(\"abc\",0,1,\"x\")"); eq(FV.VALUE, "REPLACE(\"abc\",1,-1,\"x\")")
        str("*-*-*-", "REPT(\"*-\",3)"); str("", "REPT(\"a\",0)"); eq(FV.VALUE, "REPT(\"a\",-1)"); eq(FV.VALUE, "REPT(\"ab\",20000)")
        str("This Is A Title", "PROPER(\"this is a TITLE\")"); str("2-Way Street", "PROPER(\"2-way street\")"); str("76Budget", "PROPER(\"76BudGet\")"); eq(FV.DIV0, "PROPER(1/0)")
        eq(FV.TRUE, "EXACT(\"word\",\"word\")"); eq(FV.FALSE, "EXACT(\"Word\",\"word\")"); eq(FV.FALSE, "EXACT(\"w ord\",\"word\")"); eq(FV.DIV0, "EXACT(1/0,\"a\")")
        val g = Grid("A" to listOf("a", "", "c"))
        g.str("a, c", "TEXTJOIN(\", \",TRUE,A1:A3)"); g.str("a, , c", "TEXTJOIN(\", \",FALSE,A1:A3)"); g.str("x-1-y", "TEXTJOIN(\"-\",TRUE,\"x\",1,\"y\")")
        g.eq(FV.DIV0, "TEXTJOIN(\",\",TRUE,A1:A3,1/0)")
        str("Monthly report", "CLEAN(CHAR(9)&\"Monthly report\"&CHAR(10))"); eq(FV.DIV0, "CLEAN(1/0)")
        str("Rainfall", "T(\"Rainfall\")"); str("", "T(19)"); str("", "T(TRUE)"); eq(FV.DIV0, "T(1/0)")
    }

    @Test fun characters() {
        str("A", "CHAR(65)"); str("!", "CHAR(33)"); eq(FV.VALUE, "CHAR(0)"); eq(FV.VALUE, "CHAR(256)")
        num(65.0, "CODE(\"A\")"); num(33.0, "CODE(\"!\")"); num(65.0, "CODE(\"Alphabet\")"); eq(FV.VALUE, "CODE(\"\")")
        str("B", "UNICHAR(66)"); str(" ", "UNICHAR(32)"); str("س", "UNICHAR(1587)"); eq(FV.VALUE, "UNICHAR(0)")
        num(32.0, "UNICODE(\" \")"); num(66.0, "UNICODE(\"B\")"); num(1587.0, "UNICODE(\"سلام\")"); eq(FV.VALUE, "UNICODE(\"\")")
    }

    @Test fun numbersAsText() {
        str("1,234.6", "FIXED(1234.567,1)"); str("1,230", "FIXED(1234.567,-1)"); str("-1230", "FIXED(-1234.567,-1,TRUE)"); str("44.33", "FIXED(44.332)"); eq(FV.VALUE, "FIXED(\"a\")")
        str("\$1,234.57", "DOLLAR(1234.567,2)"); str("\$1,200", "DOLLAR(1234.567,-2)"); str("(\$1,200)", "DOLLAR(-1234.567,-2)"); str("(\$0.1230)", "DOLLAR(-0.123,4)")
        str("\$99.89", "DOLLAR(99.888)"); eq(FV.VALUE, "DOLLAR(\"a\")")
        num(2500.27, "NUMBERVALUE(\"2.500,27\",\",\",\".\")"); num(0.035, "NUMBERVALUE(\"3.5%\")"); num(1234.5, "NUMBERVALUE(\" 1,234.5 \")")
        eq(FV.VALUE, "NUMBERVALUE(\"abc\")"); eq(FV.VALUE, "NUMBERVALUE(\"1,5\",\",\",\",\")"); eq(FV.VALUE, "NUMBERVALUE(\"1.5.2\")")
        num(7.0, "N(7)"); num(0.0, "N(\"Even\")"); num(1.0, "N(TRUE)"); num(40544.0, "N(DATE(2011,1,1))"); eq(FV.DIV0, "N(1/0)")
    }

    @Test fun dates() {
        num(40589.0, "EDATE(DATE(2011,1,15),1)"); num(40527.0, "EDATE(DATE(2011,1,15),-1)"); num(40602.0, "EDATE(DATE(2011,1,31),1)")
        eq(FV.VALUE, "EDATE(\"x\",1)"); eq(FV.NUM, "EDATE(DATE(2011,1,15),-99999)")
        num(40602.0, "EOMONTH(DATE(2011,1,1),1)"); num(40482.0, "EOMONTH(DATE(2011,1,1),-3)"); eq(FV.VALUE, "EOMONTH(\"x\",1)"); eq(FV.NUM, "EOMONTH(-1,1)")
        num(2.0, "DATEDIF(DATE(2001,1,1),DATE(2003,1,1),\"Y\")"); num(440.0, "DATEDIF(DATE(2001,6,1),DATE(2002,8,15),\"D\")"); num(75.0, "DATEDIF(DATE(2001,6,1),DATE(2002,8,15),\"YD\")")
        num(14.0, "DATEDIF(DATE(2001,6,1),DATE(2002,8,15),\"M\")"); num(2.0, "DATEDIF(DATE(2001,6,1),DATE(2002,8,15),\"ym\")"); num(14.0, "DATEDIF(DATE(2001,6,1),DATE(2002,8,15),\"MD\")")
        eq(FV.NUM, "DATEDIF(DATE(2003,1,1),DATE(2001,1,1),\"Y\")"); eq(FV.NUM, "DATEDIF(DATE(2001,1,1),DATE(2003,1,1),\"W\")")
        num(42.0, "DAYS(DATE(2011,3,15),DATE(2011,2,1))"); num(-42.0, "DAYS(DATE(2011,2,1),DATE(2011,3,15))"); eq(FV.VALUE, "DAYS(\"x\",1)")
        num(1.0, "DAYS360(DATE(2011,1,30),DATE(2011,2,1))"); num(360.0, "DAYS360(DATE(2011,1,1),DATE(2011,12,31))"); num(30.0, "DAYS360(DATE(2011,1,1),DATE(2011,2,1))")
        eq(FV.VALUE, "DAYS360(\"x\",1)")
        num(0.58055556, "YEARFRAC(DATE(2012,1,1),DATE(2012,7,30))", 1e-8); num(0.57650273, "YEARFRAC(DATE(2012,1,1),DATE(2012,7,30),1)", 1e-8)
        num(0.57808219, "YEARFRAC(DATE(2012,1,1),DATE(2012,7,30),3)", 1e-8); num(0.5, "YEARFRAC(DATE(2011,1,1),DATE(2011,7,1))")
        eq(FV.NUM, "YEARFRAC(DATE(2012,1,1),DATE(2012,7,30),9)"); eq(FV.VALUE, "YEARFRAC(\"x\",1)")
        num(40777.0, "DATEVALUE(\"2011-08-22\")"); num(40777.0, "DATEVALUE(\"2011-08-22 13:30\")"); eq(FV.VALUE, "DATEVALUE(\"abc\")"); eq(FV.VALUE, "DATEVALUE(40777)")
    }

    @Test fun weeks() {
        num(5.0, "WEEKDAY(DATE(2008,2,14))"); num(4.0, "WEEKDAY(DATE(2008,2,14),2)"); num(3.0, "WEEKDAY(DATE(2008,2,14),3)")
        num(4.0, "WEEKDAY(DATE(2008,2,14),11)"); num(6.0, "WEEKDAY(DATE(2008,2,14),16)"); num(5.0, "WEEKDAY(DATE(2008,2,14),17)")
        eq(FV.NUM, "WEEKDAY(DATE(2008,2,14),9)"); eq(FV.NUM, "WEEKDAY(-1)"); eq(FV.VALUE, "WEEKDAY(\"x\")")
        num(10.0, "WEEKNUM(DATE(2012,3,9))"); num(11.0, "WEEKNUM(DATE(2012,3,9),2)"); num(10.0, "WEEKNUM(DATE(2012,3,9),21)"); num(1.0, "WEEKNUM(DATE(2012,1,1))")
        eq(FV.NUM, "WEEKNUM(DATE(2012,3,9),5)"); eq(FV.VALUE, "WEEKNUM(\"x\")")
        num(10.0, "ISOWEEKNUM(DATE(2012,3,9))"); num(53.0, "ISOWEEKNUM(DATE(2021,1,1))"); eq(FV.VALUE, "ISOWEEKNUM(\"x\")"); eq(FV.NUM, "ISOWEEKNUM(-1)")
        // working days: 1 October 2012 to 1 March 2013, then with public holidays
        val g = Grid("A" to listOf("2012-11-22", "2012-12-04", "2013-01-21"), "B" to listOf("2008-11-26", "2008-12-04", "2009-01-21"))
        g.num(110.0, "NETWORKDAYS(DATE(2012,10,1),DATE(2013,3,1))"); g.num(109.0, "NETWORKDAYS(DATE(2012,10,1),DATE(2013,3,1),A1)")
        g.num(107.0, "NETWORKDAYS(DATE(2012,10,1),DATE(2013,3,1),A1:A3)"); g.num(-110.0, "NETWORKDAYS(DATE(2013,3,1),DATE(2012,10,1))")
        g.num(1.0, "NETWORKDAYS(DATE(2012,10,1),DATE(2012,10,1))"); g.eq(FV.VALUE, "NETWORKDAYS(\"x\",1)")
        g.num(39933.0, "WORKDAY(DATE(2008,10,1),151)"); g.num(39938.0, "WORKDAY(DATE(2008,10,1),151,B1:B3)"); g.num(39721.0, "WORKDAY(DATE(2008,10,1),-1)")
        g.eq(FV.VALUE, "WORKDAY(\"x\",1)"); g.eq(FV.NUM, "WORKDAY(1,-10)")
    }

    @Test fun times() {
        num(18.0, "HOUR(0.75)"); num(7.0, "HOUR(DATE(2011,7,18)+TIME(7,45,0))"); num(13.0, "HOUR(\"13:30\")"); eq(FV.NUM, "HOUR(-1)"); eq(FV.VALUE, "HOUR(\"x\")")
        num(45.0, "MINUTE(TIME(12,45,0))"); num(30.0, "MINUTE(\"13:30\")"); eq(FV.NUM, "MINUTE(-1)"); eq(FV.VALUE, "MINUTE(\"x\")")
        num(18.0, "SECOND(TIME(16,48,18))"); num(0.0, "SECOND(TIME(16,48,0))"); eq(FV.NUM, "SECOND(-1)"); eq(FV.VALUE, "SECOND(\"x\")")
        num(0.5, "TIME(12,0,0)"); num(0.7001157, "TIME(16,48,10)", 1e-7); num(0.125, "TIME(27,0,0)"); eq(FV.NUM, "TIME(-1,0,0)"); eq(FV.NUM, "TIME(40000,0,0)"); eq(FV.VALUE, "TIME(\"x\",0,0)")
        num(0.1, "TIMEVALUE(\"2:24 AM\")"); num(0.774305556, "TIMEVALUE(\"6:35 PM\")"); num(0.5, "TIMEVALUE(\"12:00\")"); num(0.5, "TIMEVALUE(\"12:00 PM\")"); num(0.0, "TIMEVALUE(\"12:00 AM\")")
        num(0.274305556, "TIMEVALUE(\"2011-08-22 6:35\")"); eq(FV.VALUE, "TIMEVALUE(\"abc\")"); eq(FV.VALUE, "TIMEVALUE(0.5)")
    }

    @Test fun lookup() {
        val g = Grid("A" to listOf("25", "38", "40", "41"), "B" to listOf("Bananas", "Oranges", "Apples", "Pears"), "D" to listOf("Apples", "Bananas"), "E" to listOf("Lemons", "Pears"))
        g.str("Pears", "INDEX(D1:E2,2,2)"); g.str("Bananas", "INDEX(D1:E2,2,1)"); g.num(38.0, "INDEX(A1:A4,2)"); g.str("Lemons", "INDEX(D1:E1,2)")
        g.num(144.0, "SUM(INDEX(A1:B4,0,1))"); g.num(2.0, "COUNTA(INDEX(D1:E2,2,0))"); g.num(80.0, "INDEX(A1:A4*2,3)")
        g.eq(FV.REF, "INDEX(D1:E2,3,1)"); g.eq(FV.REF, "INDEX(D1:E2,1,3)"); g.eq(FV.VALUE, "INDEX(D1:E2,-1,1)")
        g.num(2.0, "MATCH(39,A1:A4,1)"); g.num(2.0, "MATCH(39,A1:A4)"); g.num(4.0, "MATCH(41,A1:A4,0)"); g.eq(FV.NA, "MATCH(40,A1:A4,-1)"); g.eq(FV.NA, "MATCH(39,A1:A4,0)"); g.eq(FV.NA, "MATCH(39,A1:A4,)"); g.eq(FV.NA, "MATCH(1,A1:A4,1)")
        g.num(3.0, "MATCH(\"apples\",B1:B4,0)"); g.num(4.0, "MATCH(\"p*\",B1:B4,0)"); g.num(2.0, "MATCH(\"Pears\",D2:E2,0)"); g.eq(FV.NA, "MATCH(\"38\",A1:A4,0)"); g.eq(FV.NA, "MATCH(38,A1:B4,0)")
        g.str("Apples", "INDEX(B1:B4,MATCH(40,A1:A4,0))")
        // XLOOKUP / XMATCH
        g.num(40.0, "XLOOKUP(\"Apples\",B1:B4,A1:A4)"); g.eq(FV.NA, "XLOOKUP(\"Kiwis\",B1:B4,A1:A4)"); g.str("none", "XLOOKUP(\"Kiwis\",B1:B4,A1:A4,\"none\")")
        g.str("Oranges", "XLOOKUP(39,A1:A4,B1:B4,,-1)"); g.str("Apples", "XLOOKUP(39,A1:A4,B1:B4,,1)"); g.eq(FV.NA, "XLOOKUP(39,A1:A4,B1:B4)"); g.eq(FV.NA, "XLOOKUP(\"P*\",B1:B4,A1:A4)")
        g.num(41.0, "XLOOKUP(\"P*\",B1:B4,A1:A4,,2)"); g.str("Pears", "XLOOKUP(\"Lemons\",D1:E1,D2:E2)")
        g.eq(FV.VALUE, "XLOOKUP(\"Apples\",B1:B4,A1:A3)"); g.eq(FV.VALUE, "XLOOKUP(\"Apples\",B1:B4,A1:A4,,7)"); g.eq(FV.DIV0, "XLOOKUP(1/0,B1:B4,A1:A4)")
        g.num(3.0, "XMATCH(\"Apples\",B1:B4)"); g.num(3.0, "XMATCH(39,A1:A4,1)"); g.num(2.0, "XMATCH(39,A1:A4,-1)"); g.eq(FV.NA, "XMATCH(39,A1:A4)")
        // the first and the last of repeated keys
        val d = Grid("A" to listOf("x", "y", "x"), "B" to listOf("1", "2", "3"))
        d.num(1.0, "XLOOKUP(\"x\",A1:A3,B1:B3)"); d.num(3.0, "XLOOKUP(\"x\",A1:A3,B1:B3,,0,-1)"); d.num(3.0, "XMATCH(\"x\",A1:A3,0,-1)")
        // LOOKUP
        val l = Grid("A" to listOf("4.14", "4.19", "5.17", "5.77", "6.39"), "B" to listOf("red", "orange", "yellow", "green", "blue"), "D" to listOf("a", "", "c", "", ""))
        l.str("orange", "LOOKUP(4.19,A1:A5,B1:B5)"); l.str("yellow", "LOOKUP(5.75,A1:A5,B1:B5)"); l.str("blue", "LOOKUP(7.66,A1:A5,B1:B5)"); l.eq(FV.NA, "LOOKUP(0,A1:A5,B1:B5)")
        l.str("yellow", "LOOKUP(5.75,A1:B5)"); l.eq(FV.NA, "LOOKUP(0,A1:B5)")
        l.str("c", "LOOKUP(2,1/(D1:D5<>\"\"),D1:D5)")          // the last cell that is not empty
    }

    @Test fun references() {
        val g = Grid("A" to listOf("1", "2", "3"))
        g.num(31.0, "ROW()"); g.num(11.0, "COLUMN()"); g.num(5.0, "ROW(C5)"); g.num(3.0, "COLUMN(C5)"); g.num(2.0, "ROW(B2:D9)"); g.num(2.0, "COLUMN(B2:D9)")
        g.eq(FV.VALUE, "ROW(5)"); g.eq(FV.VALUE, "COLUMN(\"a\")"); g.eq(FV.REF, "ROW(Missing!A1)"); g.eq(FV.REF, "COLUMN(Missing!A1)")
        g.num(8.0, "ROWS(B2:D9)"); g.num(3.0, "COLUMNS(B2:D9)"); g.num(1.0, "ROWS(C5)"); g.num(1048576.0, "ROWS(A:A)"); g.num(16384.0, "COLUMNS(1:1)")
        g.eq(FV.REF, "ROWS(Missing!A1:A2)"); g.eq(FV.REF, "COLUMNS(Missing!A1:A2)")
        // in a cell: its own position, and a lookup that depends on it
        g.set("C4", "=ROW()*10+COLUMN()"); g.set("D4", "=INDEX(A1:A3,ROW()-2)")
        assertEquals(FV.Num(43.0), g.at("C4")); assertEquals(FV.Num(2.0), g.at("D4"))
        g.set("A2", "20")
        assertEquals(FV.Num(20.0), g.at("D4"))
    }

    @Test fun information() {
        eq(FV.FALSE, "ISEVEN(-1)"); eq(FV.TRUE, "ISEVEN(2.5)"); eq(FV.FALSE, "ISEVEN(5)"); eq(FV.TRUE, "ISEVEN(0)"); eq(FV.VALUE, "ISEVEN(\"a\")")
        eq(FV.TRUE, "ISODD(-1)"); eq(FV.FALSE, "ISODD(2.5)"); eq(FV.TRUE, "ISODD(5)"); eq(FV.VALUE, "ISODD(\"a\")")
        eq(FV.TRUE, "ISLOGICAL(TRUE)"); eq(FV.FALSE, "ISLOGICAL(\"TRUE\")"); eq(FV.FALSE, "ISLOGICAL(1/0)")
        eq(FV.TRUE, "ISNONTEXT(1)"); eq(FV.FALSE, "ISNONTEXT(\"a\")"); eq(FV.TRUE, "ISNONTEXT(Z9)"); eq(FV.TRUE, "ISNONTEXT(1/0)")
        eq(FV.TRUE, "ISNA(NA())"); eq(FV.FALSE, "ISNA(1/0)"); eq(FV.FALSE, "ISNA(1)")
        eq(FV.TRUE, "ISERR(1/0)"); eq(FV.FALSE, "ISERR(NA())"); eq(FV.FALSE, "ISERR(1)")
        num(1.0, "TYPE(1)"); num(2.0, "TYPE(\"a\")"); num(4.0, "TYPE(TRUE)"); num(16.0, "TYPE(1/0)"); num(64.0, "TYPE(A1:A3)")
        num(2.0, "ERROR.TYPE(1/0)"); num(7.0, "ERROR.TYPE(NA())"); num(6.0, "ERROR.TYPE(SQRT(-1))"); num(3.0, "ERROR.TYPE(VALUE(\"a\"))"); eq(FV.NA, "ERROR.TYPE(1)")
    }

    @Test fun financial() {
        num(-1037.03, "PMT(0.08/12,10,10000)", 0.005); num(-1030.16, "PMT(0.08/12,10,10000,0,1)", 0.005); num(-129.08, "PMT(0.06/12,18*12,0,50000)", 0.005); num(-100.0, "PMT(0,10,1000)")
        eq(FV.VALUE, "PMT(\"a\",1,1)"); eq(FV.NUM, "PMT(0.1,0,100)")
        num(-59777.15, "PV(0.08/12,12*20,500,,0)", 0.005); num(-1000.0, "PV(0,10,100)"); eq(FV.VALUE, "PV(\"a\",1,1)")
        num(2581.40, "FV(0.06/12,10,-200,-500,1)", 0.005); num(12682.50, "FV(0.12/12,12,-1000)", 0.005); num(82846.25, "FV(0.11/12,35,-2000,,1)", 0.005)
        num(2301.40, "FV(0.06/12,12,-100,-1000,1)", 0.005); eq(FV.VALUE, "FV(\"a\",1,1)")
        num(59.6738657, "NPER(0.12/12,-100,-1000,10000,1)", 1e-7); num(60.0821229, "NPER(0.12/12,-100,-1000,10000)", 1e-7); num(-9.57859404, "NPER(0.12/12,-100,-1000)", 1e-8)
        eq(FV.NUM, "NPER(0.01,-0.5,100)"); eq(FV.VALUE, "NPER(\"a\",1,1)")          // the payment does not cover the interest
        num(0.00770147, "RATE(4*12,-200,8000)", 1e-8); num(0.0, "RATE(10,-100,1000)", 1e-9); eq(FV.NUM, "RATE(12,100,1000)"); eq(FV.VALUE, "RATE(\"a\",1,1)")
        num(1188.44, "NPV(0.1,-10000,3000,4200,6800)", 0.005); eq(FV.VALUE, "NPV(\"a\",1)"); eq(FV.DIV0, "NPV(-1,100)")
        val g = Grid("A" to listOf("-70000", "12000", "15000", "18000", "21000", "26000"), "B" to listOf("100", "200"))
        g.num(-0.021244848, "IRR(A1:A5)", 1e-8); g.num(0.086630948, "IRR(A1:A6)", 1e-8); g.num(-0.443506941, "IRR(A1:A3,-10%)", 1e-8)
        g.eq(FV.NUM, "IRR(B1:B2)"); g.eq(FV.NUM, "IRR(Z1:Z5)")
        num(-66.67, "IPMT(0.1/12,1,3*12,8000)", 0.005); num(-292.45, "IPMT(0.1,3,3,8000)", 0.005); eq(FV.NUM, "IPMT(0.1,4,3,8000)"); eq(FV.NUM, "IPMT(0.1,0,3,8000)"); eq(FV.VALUE, "IPMT(\"a\",1,3,1)")
        num(-75.62, "PPMT(0.1/12,1,2*12,2000)", 0.005); num(-27598.05, "PPMT(0.08,10,10,200000)", 0.005); eq(FV.NUM, "PPMT(0.08,11,10,200000)"); eq(FV.VALUE, "PPMT(\"a\",1,3,1)")
        num(2250.0, "SLN(30000,7500,10)"); eq(FV.DIV0, "SLN(1,1,0)"); eq(FV.VALUE, "SLN(\"a\",1,1)")
    }

    @Test fun newFunctionsRecalculate() {
        val g = Grid("A" to listOf("10", "20", "30"), "B" to listOf("x", "y", "z"))
        g.set("D1", "=XLOOKUP(\"y\",B1:B3,A1:A3)"); g.set("D2", "=SUMPRODUCT((B1:B3=\"z\")*A1:A3)"); g.set("D3", "=MEDIAN(A1:A3)+D1")
        assertEquals(FV.Num(20.0), g.at("D1")); assertEquals(FV.Num(30.0), g.at("D2")); assertEquals(FV.Num(40.0), g.at("D3"))
        g.set("A2", "25"); g.set("B1", "z")
        assertEquals(FV.Num(25.0), g.at("D1")); assertEquals(FV.Num(40.0), g.at("D2")); assertEquals(FV.Num(50.0), g.at("D3"))
        assertFalse(g.w.book.usesUnsupported(g.s, 0, 3))
    }
}
