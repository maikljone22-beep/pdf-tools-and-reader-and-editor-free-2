package com.smartdocs.editor.core.convert

import com.smartdocs.editor.core.convert.OoxmlZip.Companion.REL_DOC
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.XML_HEAD
import java.io.Closeable
import java.io.OutputStream

/**
 * Streaming XLSX writer for analysed PDF pages: tables become bordered cell ranges (merged cells kept,
 * numbers written as numeric cells, header row bold), other text one row per line (headings bold).
 * Sheets whose text is mostly Arabic are right-to-left and their table columns mirrored so the visual
 * order matches the PDF. Cell fills and text colours of the PDF become generated cell styles.
 */
class XlsxBuilder(out: OutputStream, private val title: String) : Closeable {

    private val zip = OoxmlZip(out)
    private val strings = LinkedHashMap<String, Int>()
    private var stringRefs = 0
    private val sheetNames = ArrayList<String>()
    private val styles = StyleBook()

    private sealed class Cell { abstract val style: Int }
    private class S(val idx: Int, override val style: Int) : Cell()
    private class N(val v: Double, override val style: Int) : Cell()

    private class SheetData {
        val rows = ArrayList<HashMap<Int, Cell>>()
        val heights = HashMap<Int, Float>()
        val merges = ArrayList<String>()
        val widths = HashMap<Int, Float>()
        fun row(i: Int): HashMap<Int, Cell> { while (rows.size <= i) rows += HashMap(); return rows[i] }
    }

    /** Adds one worksheet holding [pages] (one page per sheet, or all pages). */
    fun addSheet(name: String, pages: List<PageLayout>) {
        val rtl = run {
            var r = 0; var l = 0
            pages.forEach { p -> val k = p.strongCounts(); r += k[0]; l += k[1] }
            r > l
        }
        val sd = SheetData()
        var r = 0
        pages.forEachIndexed { pi, page ->
            if (pi > 0 && r > 0) r++ // blank row between pages
            var prevWasTable = false
            for (b in page.blocks) {
                when (b) {
                    is ParagraphBlock -> {
                        if (prevWasTable) r++
                        val style = if (b.headingLevel in 1..3) 1 else 0
                        b.lines.forEach { l ->
                            val base = if (style == 0 && l.bold) 2 else style
                            val color = l.words.firstOrNull()?.color ?: 0
                            sd.row(r)[0] = S(str(l.text), if (color != 0) styles.styled(base, null, color) else base); r++
                        }
                        prevWasTable = false
                    }
                    is TableBlock -> {
                        if (r > 0) r++
                        r = writeTable(sd, b, r, rtl, page.hasFills)
                        prevWasTable = true
                    }
                    else -> Unit
                }
            }
        }
        writeSheet(sheetNames.size + 1, sd, rtl)
        sheetNames += uniqueName(name)
    }

    private fun writeTable(sd: SheetData, t: TableBlock, startRow: Int, rtl: Boolean, pdfFills: Boolean): Int {
        val header = LayoutAnalyzer.headerLikely(t)
        val nc = t.cols
        fun col(c: Int, span: Int) = if (rtl) nc - c - span else c
        // one number format per column: "1,200" and "980" in the same column both get #,##0
        val colFmt = IntArray(nc)
        for (cell in t.cells) {
            if (header && cell.row == 0) continue
            val f = BidiText.parseNumber(cell.text)?.format ?: continue
            if (f == 1 || f == 2) colFmt[cell.col] = maxOf(colFmt[cell.col], f)
        }
        for (cell in t.cells) {
            val rr = startRow + cell.row
            val cc = col(cell.col, cell.colSpan)
            val text = cell.text.take(32000)
            val isHeader = header && cell.row == 0
            val num = if (isHeader) null else BidiText.parseNumber(text)
            // PDF cell fill and text colour; the synthetic header shading only when the PDF has no fills of its own
            val color = cell.lines.firstOrNull()?.words?.firstOrNull()?.color ?: 0
            val fill: Int? = cell.fill ?: if (isHeader && pdfFills) StyleBook.NO_FILL else null
            fun st(base: Int) = if (fill == null && color == 0) base else styles.styled(base, fill, color)
            val textStyle = st(if (isHeader) 4 else 3)
            sd.row(rr)[cc] = when {
                num != null -> N(num.value, st(5 + (if (num.format == 0 && cell.colSpan == 1) colFmt[cell.col] else num.format)))
                text.isEmpty() -> S(-1, textStyle)
                else -> S(str(text), textStyle)
            }
            // borders on every covered cell of a merge, and the merge range itself
            for (dr in 0 until cell.rowSpan) for (dc in 0 until cell.colSpan) {
                if (dr == 0 && dc == 0) continue
                sd.row(rr + dr)[cc + dc] = S(-1, textStyle)
            }
            if (cell.rowSpan > 1 || cell.colSpan > 1) sd.merges += ref(rr, cc) + ":" + ref(rr + cell.rowSpan - 1, cc + cell.colSpan - 1)
            val lines = text.split('\n')
            if (cell.colSpan == 1) {
                val w = (lines.maxOf { it.length } * 1.15f + 2.5f).coerceIn(6f, 60f)
                sd.widths[cc] = maxOf(sd.widths[cc] ?: 0f, w)
            }
            if (lines.size > 1 && cell.rowSpan == 1) sd.heights[rr] = maxOf(sd.heights[rr] ?: 0f, lines.size * 15f)
        }
        return startRow + t.rows
    }

    private fun str(s: String): Int {
        stringRefs++
        return strings.getOrPut(s) { strings.size }
    }

    private fun writeSheet(no: Int, sd: SheetData, rtl: Boolean) {
        val sb = StringBuilder(XML_HEAD)
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"").append(REL_DOC).append("\">")
        val maxCol = sd.rows.maxOfOrNull { it.keys.maxOrNull() ?: 0 } ?: 0
        val lastRow = sd.rows.size.coerceAtLeast(1)
        sb.append("<dimension ref=\"A1:").append(ref(lastRow - 1, maxCol)).append("\"/>")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"")
        if (rtl) sb.append(" rightToLeft=\"1\"")
        if (no == 1) sb.append(" tabSelected=\"1\"")
        sb.append("/></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/>")
        if (sd.widths.isNotEmpty()) {
            sb.append("<cols>")
            sd.widths.keys.sorted().forEach { c -> sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append("%.2f".format(java.util.Locale.US, sd.widths[c])).append("\" customWidth=\"1\"/>") }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        sd.rows.forEachIndexed { r, cells ->
            if (cells.isEmpty()) return@forEachIndexed
            sb.append("<row r=\"").append(r + 1).append('"')
            sd.heights[r]?.let { sb.append(" ht=\"").append("%.1f".format(java.util.Locale.US, it)).append("\" customHeight=\"1\"") }
            sb.append('>')
            cells.keys.sorted().forEach { c ->
                val cell = cells[c]!!
                sb.append("<c r=\"").append(ref(r, c)).append('"')
                if (cell.style != 0) sb.append(" s=\"").append(cell.style).append('"')
                when (cell) {
                    is S -> if (cell.idx >= 0) sb.append(" t=\"s\"><v>").append(cell.idx).append("</v></c>") else sb.append("/>")
                    is N -> sb.append("><v>").append(numText(cell.v)).append("</v></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (sd.merges.isNotEmpty()) {
            sb.append("<mergeCells count=\"").append(sd.merges.size).append("\">")
            sd.merges.forEach { sb.append("<mergeCell ref=\"").append(it).append("\"/>") }
            sb.append("</mergeCells>")
        }
        sb.append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>")
        zip.text("xl/worksheets/sheet$no.xml", sb)
    }

    private fun numText(v: Double): String =
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun uniqueName(name: String): String {
        val base = name.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().trim('\'').ifEmpty { "Sheet" }.take(31)
        var n = base; var k = 2
        while (sheetNames.any { it.equals(n, ignoreCase = true) }) { val suf = " ($k)"; n = base.take(31 - suf.length) + suf; k++ }
        return n
    }

    /** Writes the shared parts and closes the package. */
    fun finish() {
        if (sheetNames.isEmpty()) { writeSheet(1, SheetData(), false); sheetNames += "Sheet1" }
        val n = sheetNames.size
        val ct = StringBuilder(XML_HEAD).append("<Types xmlns=\"").append(OoxmlZip.NS_CT).append("\">")
            .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            .append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>")
            .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"").append(OoxmlZip.CT_CORE).append("\"/>")
        for (i in 1..n) ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        ct.append("</Types>")
        zip.text("[Content_Types].xml", ct)
        zip.text("_rels/.rels", OoxmlZip.rels(
            Triple("rId1", "$REL_DOC/officeDocument", "xl/workbook.xml"),
            Triple("rId2", OoxmlZip.REL_CORE, "docProps/core.xml"),
        ))
        zip.text("docProps/core.xml", OoxmlZip.coreProps(title))
        val wb = StringBuilder(XML_HEAD)
            .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"").append(REL_DOC).append("\">")
            .append("<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>")
        sheetNames.forEachIndexed { i, nm -> wb.append("<sheet name=\"").append(BidiText.xml(nm)).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>") }
        wb.append("</sheets></workbook>")
        zip.text("xl/workbook.xml", wb)
        val rels = ArrayList<Triple<String, String, String>>()
        for (i in 1..n) rels += Triple("rId$i", "$REL_DOC/worksheet", "worksheets/sheet$i.xml")
        rels += Triple("rId${n + 1}", "$REL_DOC/styles", "styles.xml")
        rels += Triple("rId${n + 2}", "$REL_DOC/sharedStrings", "sharedStrings.xml")
        zip.text("xl/_rels/workbook.xml.rels", OoxmlZip.rels(*rels.toTypedArray()))
        zip.text("xl/styles.xml", styles.xml())
        val ss = StringBuilder(XML_HEAD).append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
            .append(stringRefs).append("\" uniqueCount=\"").append(strings.size).append("\">")
        strings.keys.forEach { s ->
            val esc = BidiText.xml(s)
            val pre = s.isNotEmpty() && (s.first().isWhitespace() || s.last().isWhitespace() || s.contains('\n'))
            ss.append(if (pre) "<si><t xml:space=\"preserve\">" else "<si><t>").append(esc).append("</t></si>")
        }
        ss.append("</sst>")
        zip.text("xl/sharedStrings.xml", ss)
        zip.close()
    }

    override fun close() = runCatching { zip.close() }.let { }

    companion object {
        fun colName(c: Int): String {
            var n = c + 1; val sb = StringBuilder()
            while (n > 0) { val m = (n - 1) % 26; sb.append('A' + m); n = (n - 1) / 26 }
            return sb.reverse().toString()
        }

        fun ref(r: Int, c: Int) = colName(c) + (r + 1)

    }
}

/**
 * styles.xml with the 10 fixed cell formats (0 normal, 1 heading, 2 bold, 3 table text, 4 table header, 5 number,
 * 6 #,##0, 7 #,##0.00, 8 0%, 9 0.00% = 5 + BidiText.Num.format) plus formats generated for PDF fills / text colours.
 */
internal class StyleBook {
    private class Xf(val numFmt: Int, val font: Int, val fill: Int, val border: Int, val align: String?)

    private val fonts = arrayListOf(
        "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
        "<font><b/><sz val=\"14\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
    )
    private val fills = arrayListOf(
        "<fill><patternFill patternType=\"none\"/></fill>", "<fill><patternFill patternType=\"gray125\"/></fill>",
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCE6F1\"/><bgColor indexed=\"64\"/></patternFill></fill>",
    )
    private val top = "<alignment vertical=\"top\" wrapText=\"1\"/>"
    private val center = "<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/>"
    private val xfs = arrayListOf(
        Xf(0, 0, 0, 0, null), Xf(0, 2, 0, 0, null), Xf(0, 1, 0, 0, null), Xf(0, 0, 0, 1, top), Xf(0, 1, 2, 1, center),
        Xf(0, 0, 0, 1, null), Xf(3, 0, 0, 1, null), Xf(4, 0, 0, 1, null), Xf(9, 0, 0, 1, null), Xf(10, 0, 0, 1, null),
    )
    private val fontCache = HashMap<String, Int>()
    private val fillCache = HashMap<Int, Int>()
    private val xfCache = HashMap<String, Int>()

    companion object { const val NO_FILL = -1 }

    private fun fontWith(base: Int, color: Int): Int {
        if (color == 0) return base
        return fontCache.getOrPut("$base/$color") {
            val bold = base == 1 || base == 2
            val sz = if (base == 2) 14 else 11
            fonts += "<font>" + (if (bold) "<b/>" else "") + "<sz val=\"$sz\"/><color rgb=\"FF" + Decorations.hex(color) + "\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            fonts.size - 1
        }
    }

    private fun fillOf(color: Int): Int = if (color == NO_FILL) 0 else fillCache.getOrPut(color) {
        fills += "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF" + Decorations.hex(color) + "\"/><bgColor indexed=\"64\"/></patternFill></fill>"
        fills.size - 1
    }

    /** Cell format [base] with [fill] (null = keep, [NO_FILL] = none) and text [color] (0 = keep). */
    fun styled(base: Int, fill: Int?, color: Int): Int {
        if (fill == null && color == 0) return base
        return xfCache.getOrPut("$base/$fill/$color") {
            val b = xfs[base]
            xfs += Xf(b.numFmt, fontWith(b.font, color), if (fill == null) b.fill else fillOf(fill), b.border, b.align)
            xfs.size - 1
        }
    }

    fun xml(): String {
        val sb = StringBuilder(XML_HEAD).append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<fonts count=\"").append(fonts.size).append("\">"); fonts.forEach { sb.append(it) }; sb.append("</fonts>")
        sb.append("<fills count=\"").append(fills.size).append("\">"); fills.forEach { sb.append(it) }; sb.append("</fills>")
        sb.append("<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>")
            .append("<border><left style=\"thin\"><color rgb=\"FF9E9E9E\"/></left><right style=\"thin\"><color rgb=\"FF9E9E9E\"/></right>")
            .append("<top style=\"thin\"><color rgb=\"FF9E9E9E\"/></top><bottom style=\"thin\"><color rgb=\"FF9E9E9E\"/></bottom><diagonal/></border></borders>")
            .append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        sb.append("<cellXfs count=\"").append(xfs.size).append("\">")
        for (x in xfs) {
            sb.append("<xf numFmtId=\"").append(x.numFmt).append("\" fontId=\"").append(x.font).append("\" fillId=\"").append(x.fill)
                .append("\" borderId=\"").append(x.border).append("\" xfId=\"0\"")
            if (x.numFmt != 0) sb.append(" applyNumberFormat=\"1\"")
            if (x.font != 0) sb.append(" applyFont=\"1\"")
            if (x.fill != 0) sb.append(" applyFill=\"1\"")
            if (x.border != 0) sb.append(" applyBorder=\"1\"")
            if (x.align != null) sb.append(" applyAlignment=\"1\">").append(x.align).append("</xf>") else sb.append("/>")
        }
        sb.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
        return sb.toString()
    }
}
