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

    fun write(rows: List<List<String>>, delim: Char = ','): String = buildString {
        rows.forEach { r -> append(r.joinToString(delim.toString()) { quote(it, delim) }).append("\r\n") }
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
}
