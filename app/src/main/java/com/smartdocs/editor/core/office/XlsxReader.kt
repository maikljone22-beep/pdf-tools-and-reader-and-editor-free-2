package com.smartdocs.editor.core.office

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * SpreadsheetML (.xlsx) reader: workbook sheet list, shared strings, cells (s/str/inlineStr/b/n/e/d),
 * merged ranges, column widths, basic number formats (dates, percent, grouping, decimals),
 * basic cell styles (bold/italic/font color/fill/h-align/wrap) and right-to-left sheet views.
 */
internal class XlsxReader(private val pkg: OoxmlPackage, private val maxRows: Int = 5000, private val maxCols: Int = 200) {
    private val wbPath: String = pkg.rels("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "xl/workbook.xml"
    private val shared = ArrayList<String>()
    private val styles = ArrayList<CellStyle>()
    private val customFmts = HashMap<Int, String>()
    private var date1904 = false

    data class SheetRef(val name: String, val path: String)

    fun sheetRefs(): List<SheetRef> {
        val p = pkg.parser(wbPath) ?: throw OfficeFormatException("xl/workbook.xml missing")
        val rels = pkg.rels(wbPath)
        val out = ArrayList<SheetRef>()
        while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (p.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            when (p.name) {
                "workbookPr" -> date1904 = p.attr("date1904").let { it == "1" || it == "true" }
                "sheet" -> {
                    val rid = p.rAttr("id")
                    val path = rid?.let { rels[it]?.target } ?: continue
                    out.add(SheetRef(p.attr("name") ?: "Sheet${out.size + 1}", path))
                }
            }
        }
        return out
    }

    fun read(): WorkbookModel {
        val refs = sheetRefs()
        loadSharedStrings(); loadStyles()
        return WorkbookModel(refs.map { readSheet(it) }, styles.toList())
    }

    private fun loadSharedStrings() {
        val path = pkg.rels(wbPath).values.firstOrNull { it.type.endsWith("/sharedStrings") }?.target ?: "xl/sharedStrings.xml"
        val p = pkg.parser(path) ?: return
        if (!p.seek("sst")) return
        p.forEachChild { n ->
            if (n == "si") shared.add(readRichText(p))
        }
    }

    /** Text of an `si`/`is` element: plain `t` or rich-text runs, ignoring phonetic runs. */
    private fun readRichText(p: org.xmlpull.v1.XmlPullParser): String {
        val sb = StringBuilder()
        p.forEachChild { c ->
            when (c) {
                "t" -> sb.append(p.readText())
                "r" -> p.forEachChild { k -> if (k == "t") sb.append(p.readText()) }
            }
        }
        return sb.toString()
    }

    private fun loadStyles() {
        val path = pkg.rels(wbPath).values.firstOrNull { it.type.endsWith("/styles") }?.target ?: "xl/styles.xml"
        val p = pkg.parser(path) ?: return
        if (!p.seek("styleSheet")) return
        data class Font(val bold: Boolean, val italic: Boolean, val color: String?)
        val fonts = ArrayList<Font>(); val fills = ArrayList<String?>()
        p.forEachChild { n ->
            when (n) {
                "numFmts" -> p.forEachChild { c -> if (c == "numFmt") { val id = p.attr("numFmtId")?.toIntOrNull(); val code = p.attr("formatCode"); if (id != null && code != null) customFmts[id] = code } }
                "fonts" -> p.forEachChild { c ->
                    if (c == "font") {
                        var b = false; var i = false; var col: String? = null
                        p.forEachChild { k -> when (k) { "b" -> b = p.onOff(); "i" -> i = p.onOff(); "color" -> col = parseColor(p.attr("rgb")) } }
                        fonts.add(Font(b, i, col))
                    }
                }
                "fills" -> p.forEachChild { c ->
                    if (c == "fill") {
                        var col: String? = null
                        p.forEachChild { k -> if (k == "patternFill") p.forEachChild { q -> if (q == "fgColor" && col == null) col = parseColor(p.attr("rgb")) } }
                        fills.add(col)
                    }
                }
                "cellXfs" -> p.forEachChild { c ->
                    if (c == "xf") {
                        val fmt = p.attr("numFmtId")?.toIntOrNull() ?: 0
                        val font = fonts.getOrNull(p.attr("fontId")?.toIntOrNull() ?: 0)
                        val fill = fills.getOrNull(p.attr("fillId")?.toIntOrNull() ?: 0)
                        var h: String? = null; var wrap = false
                        p.forEachChild { k -> if (k == "alignment") { h = p.attr("horizontal"); wrap = p.attr("wrapText") == "1" } }
                        styles.add(CellStyle(font?.bold == true, font?.italic == true, font?.color?.takeIf { it != "000000" }, fill, h, wrap, fmt, customFmts[fmt]))
                    }
                }
            }
        }
    }

    private fun readSheet(ref: SheetRef): SheetModel {
        val p = pkg.parser(ref.path) ?: return SheetModel(ref.name, emptyList(), emptyList(), emptyMap(), false, ref.path)
        val rows = ArrayList<SheetRow>(); val merges = ArrayList<MergeRange>(); val widths = HashMap<Int, Float>()
        var rtl = false; var defW = 8.43f; var truncated = false
        if (!p.seek("worksheet")) return SheetModel(ref.name, rows, merges, widths, false, ref.path)
        p.forEachChild { n ->
            when (n) {
                "sheetViews" -> p.forEachChild { c -> if (c == "sheetView") rtl = rtl || p.attr("rightToLeft").let { it == "1" || it == "true" } }
                "sheetFormatPr" -> p.attr("defaultColWidth")?.toFloatOrNull()?.let { defW = it }
                "cols" -> p.forEachChild { c ->
                    if (c == "col") {
                        val min = (p.attr("min")?.toIntOrNull() ?: 1) - 1; val max = (p.attr("max")?.toIntOrNull() ?: 1) - 1
                        val w = if (p.attr("hidden") == "1") 0f else p.attr("width")?.toFloatOrNull() ?: defW
                        for (i in min..minOf(max, maxCols)) widths[i] = w
                    }
                }
                "sheetData" -> {
                    var nextRow = 0
                    p.forEachChild { c ->
                        if (c != "row") return@forEachChild
                        val ri = (p.attr("r")?.toIntOrNull()?.minus(1)) ?: nextRow
                        nextRow = ri + 1
                        if (ri >= maxRows) { truncated = true; return@forEachChild }
                        val ht = p.attr("ht")?.toFloatOrNull()
                        val cells = ArrayList<SheetCell>(); var nextCol = 0
                        p.forEachChild { k ->
                            if (k != "c") return@forEachChild
                            val ci = p.attr("r")?.let { CellRef.parse(it)?.second } ?: nextCol
                            nextCol = ci + 1
                            if (ci > maxCols) { truncated = true; return@forEachChild }
                            readCell(p, ci)?.let { cells.add(it) }
                        }
                        rows.add(SheetRow(ri, cells, ht))
                    }
                }
                "mergeCells" -> p.forEachChild { c -> if (c == "mergeCell") p.attr("ref")?.let { CellRef.range(it) }?.let { merges.add(it) } }
            }
        }
        return SheetModel(ref.name, rows, merges, widths, rtl, ref.path, defW, truncated)
    }

    private fun readCell(p: org.xmlpull.v1.XmlPullParser, col: Int): SheetCell? {
        val t = p.attr("t") ?: "n"
        val s = p.attr("s")?.toIntOrNull() ?: 0
        var v: String? = null; var f: String? = null; var inline: String? = null
        p.forEachChild { n ->
            when (n) { "v" -> v = p.readText(); "f" -> f = p.readText(); "is" -> inline = readRichText(p) }
        }
        val raw: String; val type: String; val display: String
        when (t) {
            "s" -> { raw = v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""; type = "s"; display = raw }
            "inlineStr" -> { raw = inline ?: v ?: ""; type = "s"; display = raw }
            "str" -> { raw = v ?: ""; type = "s"; display = raw }
            "b" -> { raw = v ?: "0"; type = "b"; display = if (raw.trim() == "1") "TRUE" else "FALSE" }
            "e" -> { raw = v ?: "#ERR"; type = "e"; display = raw }
            "d" -> { raw = v ?: ""; type = "d"; display = raw.replace('T', ' ').removeSuffix("Z") }
            else -> {
                raw = v ?: ""
                if (raw.isEmpty() && f == null && s == 0) return null
                type = if (raw.isEmpty()) "" else "n"
                display = if (raw.isEmpty()) f?.let { "=$it" } ?: "" else NumFormat.format(raw, styles.getOrNull(s), date1904)
            }
        }
        return SheetCell(col, display, raw, type, s, f)
    }
}

/** A1 references. */
object CellRef {
    /** "B3" -> (row 2, col 1), 0-based. */
    fun parse(ref: String): Pair<Int, Int>? {
        var i = 0; var col = 0
        val r = ref.replace("$", "")
        while (i < r.length && r[i].isLetter()) { col = col * 26 + (r[i].uppercaseChar() - 'A' + 1); i++ }
        val row = r.substring(i).toIntOrNull() ?: return null
        if (col == 0) return null
        return (row - 1) to (col - 1)
    }

    fun range(ref: String): MergeRange? {
        val parts = ref.split(':')
        val a = parse(parts[0]) ?: return null
        val b = parts.getOrNull(1)?.let { parse(it) } ?: a
        return MergeRange(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    fun colName(c: Int): String {
        var n = c + 1; val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
        return sb.toString()
    }

    fun name(row: Int, col: Int) = colName(col) + (row + 1)
}

/** Basic Excel number format rendering. */
internal object NumFormat {
    private val DATE_IDS = setOf(14, 15, 16, 17, 18, 19, 20, 21, 22, 45, 46, 47) + (27..36) + (50..58)

    fun format(raw: String, style: CellStyle?, date1904: Boolean): String {
        val d = raw.trim().toDoubleOrNull() ?: return raw
        val id = style?.numFmtId ?: 0
        val code = style?.numFmtCode ?: builtin(id)
        if (id in DATE_IDS || (code != null && isDateCode(code))) return date(d, code ?: "yyyy-mm-dd", date1904)
        if (code == null || code.equals("General", true)) return general(d)
        val section = code.split(';').first()
        val pct = section.contains('%')
        val v = if (pct) d * 100 else d
        val decimals = Regex("""0\.(0+)""").find(section)?.groupValues?.get(1)?.length ?: 0
        val group = section.contains("#,##") || section.contains("#,#0") || section.contains("0,0")
        val pattern = (if (group) "#,##0" else "0") + if (decimals > 0) "." + "0".repeat(decimals) else ""
        val df = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
        val prefix = Regex("""^"([^"]*)"|^\[\$([^\]-]*)""").find(section)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""
        return prefix + df.format(v) + (if (pct) "%" else "")
    }

    private fun general(d: Double): String {
        if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        val s = BigDecimal(d).round(java.math.MathContext(11)).stripTrailingZeros().toPlainString()
        return s
    }

    private fun builtin(id: Int): String? = when (id) {
        1 -> "0"; 2 -> "0.00"; 3 -> "#,##0"; 4 -> "#,##0.00"; 9 -> "0%"; 10 -> "0.00%"; 11 -> "0.00E+00"
        37, 38 -> "#,##0"; 39, 40 -> "#,##0.00"; 14 -> "yyyy-mm-dd"; 22 -> "yyyy-mm-dd hh:mm"
        18, 19, 20, 21, 45, 46, 47 -> "hh:mm:ss"
        else -> null
    }

    fun isDateCode(code: String): Boolean {
        val c = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("""\[[^\]]*\]"""), "").lowercase()
        if (c == "general") return false
        return c.any { it == 'd' || it == 'y' || it == 'h' || it == 's' } || (c.contains('m') && !c.contains('0') && !c.contains('#'))
    }

    private fun date(serial: Double, code: String, date1904: Boolean): String {
        val days = Math.floor(serial).toLong()
        val secs = Math.round((serial - days) * 86400).toInt()
        val epoch = if (date1904) java.time.LocalDate.of(1904, 1, 1) else java.time.LocalDate.of(1899, 12, 30)
        val date = epoch.plusDays(days)
        val lc = code.lowercase()
        val hasDate = lc.contains('d') || lc.contains('y')
        val hasTime = lc.contains('h') || lc.contains("s")
        val sb = StringBuilder()
        if (hasDate || !hasTime) sb.append(String.format(Locale.US, "%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth))
        if (hasTime) { if (sb.isNotEmpty()) sb.append(' '); sb.append(String.format(Locale.US, "%02d:%02d", secs / 3600, (secs / 60) % 60)); if (lc.contains('s')) sb.append(String.format(Locale.US, ":%02d", secs % 60)) }
        return sb.toString()
    }
}
