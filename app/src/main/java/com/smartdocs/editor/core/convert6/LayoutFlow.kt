package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.convert.Align
import com.smartdocs.editor.core.convert.ImageBlock
import com.smartdocs.editor.core.convert.LayoutAnalyzer
import com.smartdocs.editor.core.convert.PageLayout
import com.smartdocs.editor.core.convert.ParagraphBlock
import com.smartdocs.editor.core.convert.TableBlock
import com.smartdocs.editor.core.convert.TextRuns

/** Analysed PDF pages ([PageLayout], from core/convert) → [FlowDoc] / tables. Pure JVM. */
object LayoutFlow {

    private val bullet = Regex("""^\s*([•●▪◦‣⁃·*\-–])\s+""")
    private val numbered = Regex("""^\s*([0-9٠-٩۰-۹]{1,3})[.)\-٫]\s+""")

    /** Rows of a detected table in reading order (RTL tables reversed so column 0 is the start side). */
    fun rows(t: TableBlock): List<List<String>> {
        val grid = Array(t.rows) { Array(t.cols) { "" } }
        t.cells.forEach { c -> if (c.row in 0 until t.rows && c.col in 0 until t.cols) grid[c.row][c.col] = c.text }
        return grid.map { r -> if (t.rtl) r.reversed() else r.toList() }
    }

    /**
     * [chapters] maps a zero-based page index to the outline titles starting on it (EPUB chapters).
     * [pageBreaks] puts an explicit page break between source pages (DOCX/ODT), off for reflowable outputs.
     */
    fun fromLayouts(title: String, layouts: List<PageLayout>, chapters: Map<Int, List<String>> = emptyMap(), includeImages: Boolean = true, pageBreaks: Boolean = false, author: String = ""): FlowDoc {
        val out = ArrayList<FBlock>()
        layouts.forEachIndexed { k, page ->
            if (k > 0 && pageBreaks) out += FBlock.PageBreak
            chapters[page.index]?.forEach { out += FBlock.Chapter(it) }
            page.blocks.forEach { b ->
                when (b) {
                    is ParagraphBlock -> {
                        val runs = TextRuns.ofParagraph(b.lines).map { FRun(it.text, it.bold && b.headingLevel == 0, it.italic) }
                        val text = Text6.plain(runs)
                        if (text.isBlank()) return@forEach
                        val bm = bullet.find(text); val nm = numbered.find(text)
                        when {
                            b.headingLevel in 1..6 -> out += FBlock.Heading(b.headingLevel, runs, b.rtl)
                            bm != null -> out += FBlock.Item(strip(runs, bm.value.length), b.rtl)
                            nm != null -> out += FBlock.Item(strip(runs, nm.value.length), b.rtl, number = digit(nm.groupValues[1]))
                            else -> out += FBlock.Para(runs, b.rtl, when (b.align) { Align.CENTER -> FAlign.CENTER; Align.END -> FAlign.END; else -> FAlign.START })
                        }
                    }
                    is TableBlock -> out += FBlock.Table(rows(b), LayoutAnalyzer.headerLikely(b), b.rtl)
                    is ImageBlock -> if (includeImages && b.image.w >= 8f && b.image.h >= 8f) {
                        out += FBlock.Image(b.image.bytes, if (b.image.ext == "jpeg") "jpg" else b.image.ext, b.image.w, b.image.h)
                    }
                    else -> {}
                }
            }
        }
        return FlowDoc(title, out, author)
    }

    private fun strip(runs: List<FRun>, n: Int): List<FRun> {
        var left = n
        val out = ArrayList<FRun>()
        runs.forEach { r ->
            if (left <= 0) { out += r; return@forEach }
            if (r.text.length <= left) { left -= r.text.length } else { out += r.copy(text = r.text.substring(left)); left = 0 }
        }
        return out
    }

    private fun digit(s: String): Int = s.map { c ->
        when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); else -> c }
    }.joinToString("").toIntOrNull()?.coerceAtLeast(1) ?: 1

    /**
     * Sheets of a PDF → ODS / Excel 2003 export: one per page that has tables (convert audit B21: pages without a table
     * became text-dump sheets between the real ones); only a document without any table gives its text as one sheet.
     */
    fun sheetsPerPage(layouts: List<PageLayout>, sheetName: (Int) -> String): List<Pair<String, List<List<String>>>> {
        val withTables = layouts.mapNotNull { l ->
            val rows = tables(listOf(l), fallbackText = false).flatMap { t -> t + listOf(emptyList()) }
            if (rows.isEmpty()) null else sheetName(l.index + 1) to rows.dropLast(1)
        }
        if (withTables.isNotEmpty()) return withTables
        val text = tables(layouts, fallbackText = true).flatten()
        return if (text.isEmpty()) emptyList() else listOf(sheetName(layouts.firstOrNull()?.index?.plus(1) ?: 1) to text)
    }

    /** All tables of the pages; pages without a table contribute their text lines as one-column rows when [fallbackText]. */
    fun tables(layouts: List<PageLayout>, fallbackText: Boolean = true): List<List<List<String>>> {
        val out = ArrayList<List<List<String>>>()
        layouts.forEach { p -> p.blocks.filterIsInstance<TableBlock>().forEach { out += rows(it) } }
        if (out.isEmpty() && fallbackText) {
            val lines = layouts.flatMap { p -> p.blocks.filterIsInstance<ParagraphBlock>().flatMap { b -> b.lines.map { listOf(it.text) } } }
            if (lines.isNotEmpty()) out += lines
        }
        return out
    }
}

/** CSV helpers (RFC 4180; UTF-8 with BOM so Excel shows Arabic). Pure. */
object Csv6 {

    fun quote(v: String, delim: Char = ','): String =
        if (v.any { it == delim || it == '"' || it == '\n' || it == '\r' } || v.startsWith(" ") || v.endsWith(" ")) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /**
     * CSV / formula injection guard (convert audit B22 / C18): a cell starting with = + - @ (or a tab / CR) that is not
     * a plain number is written with a leading apostrophe, so Excel / LibreOffice show it as text instead of running
     * a formula or a DDE command.
     */
    fun defuse(v: String): String {
        if (v.isEmpty()) return v
        val c = v[0]
        if (c != '=' && c != '+' && c != '-' && c != '@' && c != '\t' && c != '\r') return v
        if (number(v) != null) return v
        return "'$v"
    }

    fun write(rows: List<List<String>>, delim: Char = ','): String = buildString {
        rows.forEach { r -> append(r.joinToString(delim.toString()) { quote(defuse(it), delim) }).append("\r\n") }
    }

    /** Several tables in one CSV, separated by an empty line. */
    fun writeTables(tables: List<List<List<String>>>, delim: Char = ','): String = tables.joinToString("\r\n") { write(it, delim) }

    val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** A plain number (Arabic-Indic digits, thousands separators and % accepted) as a canonical string, or null. */
    fun number(s0: String): String? {
        val s = s0.trim().map { c ->
            when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); '٫' -> '.'; '٬' -> ','; '−' -> '-'; else -> c }
        }.joinToString("")
        if (s.isEmpty() || s.length > 24) return null
        if (!Regex("""^[-+]?(\d{1,3}(,\d{3})+|\d+)(\.\d+)?$""").matches(s)) return null
        val clean = s.replace(",", "").removePrefix("+")
        return clean.toDoubleOrNull()?.let { clean }
    }

    /**
     * A cell value that a spreadsheet should store as a number, as a canonical string, or null = keep it as text
     * (convert audit B13 / C17): only plain decimal numbers ([number]: no NaN / Infinity / hex / "12d" / "1e400" /
     * "1,5"), and never values with a leading zero ("007", "0501234567": IDs and phone numbers) or more than 15
     * significant digits (national IDs, account numbers lose precision as doubles).
     */
    fun cellNumber(s: String): String? {
        val n = number(s) ?: return null
        val digits = n.removePrefix("-")
        val intPart = digits.substringBefore('.')
        if (intPart.length > 1 && intPart.startsWith("0")) return null
        if (digits.count { it.isDigit() } - digits.takeWhile { it == '0' || it == '.' }.count { it == '0' } > 15) return null
        return n
    }
}
