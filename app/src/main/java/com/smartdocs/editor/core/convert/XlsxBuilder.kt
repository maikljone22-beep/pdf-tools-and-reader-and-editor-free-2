package com.smartdocs.editor.core.convert

import com.smartdocs.editor.core.convert.OoxmlZip.Companion.REL_DOC
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.XML_HEAD
import java.io.Closeable
import java.io.OutputStream

/**
 * Streaming XLSX writer for analysed PDF pages: tables become bordered cell ranges (merged cells kept,
 * numbers written as numeric cells, header row bold), other text one row per line (headings bold).
 * Sheets whose text is mostly Arabic are right-to-left and their table columns mirrored so the visual
 * order matches the PDF.
 */
class XlsxBuilder(out: OutputStream, private val title: String) : Closeable {

    private val zip = OoxmlZip(out)
    private val strings = LinkedHashMap<String, Int>()
    private var stringRefs = 0
    private val sheetNames = ArrayList<String>()

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
                        b.lines.forEach { l -> sd.row(r)[0] = S(str(l.text), if (style == 0 && l.bold) 2 else style); r++ }
                        prevWasTable = false
                    }
                    is TableBlock -> {
                        if (r > 0) r++
                        r = writeTable(sd, b, r, rtl)
                        prevWasTable = true
                    }
                    else -> Unit
                }
            }
        }
        writeSheet(sheetNames.size + 1, sd, rtl)
        sheetNames += uniqueName(name)
    }

    private fun writeTable(sd: SheetData, t: TableBlock, startRow: Int, rtl: Boolean): Int {
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
            sd.row(rr)[cc] = when {
                num != null -> N(num.value, 5 + (if (num.format == 0 && cell.colSpan == 1) colFmt[cell.col] else num.format))
                text.isEmpty() -> S(-1, if (isHeader) 4 else 3)
                else -> S(str(text), if (isHeader) 4 else 3)
            }
            // borders on every covered cell of a merge, and the merge range itself
            for (dr in 0 until cell.rowSpan) for (dc in 0 until cell.colSpan) {
                if (dr == 0 && dc == 0) continue
                sd.row(rr + dr)[cc + dc] = S(-1, if (isHeader) 4 else 3)
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
        zip.text("xl/styles.xml", STYLES)
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

        // cellXfs: 0 normal, 1 heading, 2 bold, 3 table text, 4 table header,
        //          5 number, 6 #,##0, 7 #,##0.00, 8 0%, 9 0.00%   (5 + BidiText.Num.format)
        private const val STYLES = XML_HEAD +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"3\">" +
            "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
            "<font><b/><sz val=\"14\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
            "</fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCE6F1\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
            "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
            "<border><left style=\"thin\"><color rgb=\"FF9E9E9E\"/></left><right style=\"thin\"><color rgb=\"FF9E9E9E\"/></right>" +
            "<top style=\"thin\"><color rgb=\"FF9E9E9E\"/></top><bottom style=\"thin\"><color rgb=\"FF9E9E9E\"/></bottom><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"10\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"4\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"9\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>" +
            "<xf numFmtId=\"10\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>" +
            "</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"
    }
}
