package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.office5.CsvIO

/**
 * Readers for ".xls" / ".doc" files that are not Office 97 binaries (convert audit A-H11 / C20): HTML tables ("Save as
 * Web Page", bank statements), Excel XML Spreadsheet 2003, Word 2003 XML and plain TSV / CSV text. Pure.
 */
object LegacyVariants {

    private fun attr(tag: String, name: String): String? = Regex("""(?i)\b$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)

    /** Excel 2003 XML (SpreadsheetML): worksheets → rows of cell texts (ss:Index gaps kept, merged cells not spread). */
    fun spreadsheetMl(xml: String, maxRows: Int = 200_000, maxCols: Int = 2000): List<Pair<String, List<List<String>>>> {
        val sheets = ArrayList<Pair<String, List<List<String>>>>()
        val ws = Regex("""(?is)<(?:ss:)?Worksheet\b([^>]*)>(.*?)</(?:ss:)?Worksheet>""")
        ws.findAll(xml).forEachIndexed { i, m ->
            val name = attr(m.groupValues[1], "ss:Name") ?: attr(m.groupValues[1], "Name") ?: "Sheet${i + 1}"
            val rows = ArrayList<List<String>>()
            Regex("""(?is)<(?:ss:)?Row\b([^>]*?)(?:/>|>(.*?)</(?:ss:)?Row>)""").findAll(m.groupValues[2]).forEach { r ->
                if (rows.size >= maxRows) return@forEach
                attr(r.groupValues[1], "ss:Index")?.toIntOrNull()?.let { idx -> while (rows.size < idx - 1 && rows.size < maxRows) rows += emptyList<String>() }
                val cells = ArrayList<String>()
                Regex("""(?is)<(?:ss:)?Cell\b([^>]*?)(?:/>|>(.*?)</(?:ss:)?Cell>)""").findAll(r.groupValues[2]).forEach { c ->
                    attr(c.groupValues[1], "ss:Index")?.toIntOrNull()?.let { idx -> while (cells.size < idx - 1 && cells.size < maxCols) cells += "" }
                    val data = Regex("""(?is)<(?:ss:)?Data\b[^>]*>(.*?)</(?:ss:)?Data>""").find(c.groupValues[2])?.groupValues?.get(1).orEmpty()
                    if (cells.size < maxCols) cells += HtmlFlow.decode(data.replace(Regex("<[^>]+>"), "")).trim()
                }
                rows += cells
            }
            sheets += name to rows
        }
        return sheets
    }

    /** Word 2003 XML (WordprocessingML): paragraphs → text lines. */
    fun wordMlText(xml: String): String =
        Regex("""(?is)<w:p\b[^>]*>(.*?)</w:p>""").findAll(xml).joinToString("\n") { p ->
            Regex("""(?is)<w:t\b[^>]*>(.*?)</w:t>|<w:(tab|br)\b[^>]*/>""").findAll(p.groupValues[1]).joinToString("") { t ->
                when (t.groupValues[2].lowercase()) { "tab" -> "\t"; "br" -> "\n"; else -> HtmlFlow.decode(t.groupValues[1]) }
            }
        }

    /** HTML tables → sheets (one per table); text without tables → one-column rows. */
    fun htmlSheets(html: String, name: String): List<Pair<String, List<List<String>>>> {
        val doc = HtmlFlow.parse(html, name)
        val tables = doc.blocks.filterIsInstance<FBlock.Table>()
        if (tables.isNotEmpty()) return tables.mapIndexed { i, t -> (if (tables.size > 1) "$name ${i + 1}" else name) to t.rows }
        val lines = doc.blocks.mapNotNull { FlowDoc.textOf(it)?.takeIf { s -> s.isNotBlank() } }.map { listOf(it) }
        return listOf(name to lines)
    }

    /** Sheets of a non-binary ".xls" by its [variant]; [text] = its decoded content. */
    fun sheets(variant: FileKind6.Legacy, text: String, name: String, tsv: Boolean = false): List<Pair<String, List<List<String>>>> = when (variant) {
        FileKind6.Legacy.HTML -> htmlSheets(text, name)
        FileKind6.Legacy.XML_SPREADSHEET -> spreadsheetMl(text).ifEmpty { listOf(name to emptyList()) }
        else -> listOf(name to CsvIO.parse(text, if (tsv || text.take(4000).count { it == '\t' } > text.take(4000).count { it == ',' }) '\t' else CsvIO.sniff(text)))
    }

    /** A Word-like document of a non-binary ".doc" by its [variant]. */
    fun wordDoc(variant: FileKind6.Legacy, text: String, title: String): FlowDoc = when (variant) {
        FileKind6.Legacy.HTML -> HtmlFlow.parse(text, title)
        FileKind6.Legacy.XML_WORD -> FlowDoc.fromText(wordMlText(text), title)
        else -> FlowDoc.fromText(text, title)
    }
}
