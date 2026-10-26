package com.smartdocs.editor.core.office5

import java.io.File

/** CSV/TSV as a one-sheet [Workbook5]: delimiter sniffing, quotes, BOM and Windows-1256 fallback kept on save. */
object CsvIO {

    fun load(file: File, rtl: Boolean = true): Workbook5 {
        val b = file.readBytes()
        val bom = b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(b, if (bom) 3 else 0, b.size - if (bom) 3 else 0)).toString()
        }.getOrElse { String(b, runCatching { charset("windows-1256") }.getOrDefault(Charsets.ISO_8859_1)) }
        val delim = if (file.extension.equals("tsv", true)) '\t' else sniff(text)
        return fromText(text, delim, file.nameWithoutExtension.take(31).ifBlank { "Sheet1" }, rtl).also {
            it.meta = XlsxIO5.Meta("", emptyList(), ArrayList(), bom, delim)
        }
    }

    fun fromText(text: String, delim: Char, name: String, rtl: Boolean): Workbook5 {
        val book = SheetBook()
        val s = Sheet5(name).also { it.rtl = rtl }
        book.sheets.add(s)
        val styles = XStyles(null, null, null)
        val w = Workbook5(book, styles, null, Workbook5.Kind.CSV)
        parse(text, delim).forEachIndexed { r, row ->
            row.forEachIndexed { c, v ->
                if (v.isEmpty() || r >= MAX_ROWS || c >= MAX_COLS) return@forEachIndexed
                val p = book.parseInput(v)
                s.cells[Sheet5.key(r, c)] = Cell5(p.formula, if (p.formula == null) p.literal else FV.Blank)
            }
        }
        book.recalcAll()
        return w
    }

    fun sniff(text: String): Char {
        val head = text.lineSequence().take(10).toList()
        return listOf(',', ';', '\t', '|').maxByOrNull { d -> head.sumOf { line -> line.count { it == d } } }?.takeIf { d -> head.any { it.contains(d) } } ?: ','
    }

    /** RFC 4180 parser (quotes, doubled quotes, newlines inside quotes, CRLF). */
    fun parse(text: String, delim: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val f = StringBuilder()
        var q = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(c)
            } else when (c) {
                '"' -> if (f.isEmpty()) q = true else f.append(c)
                delim -> { row.add(f.toString()); f.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(f.toString()); f.setLength(0); rows.add(row); row = ArrayList() }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows
    }

    fun quote(v: String, delim: Char): String =
        if (v.any { it == delim || it == '"' || it == '\n' || it == '\r' } || v.startsWith(" ") || v.endsWith(" ")) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Text of the first sheet: formulas are saved as their results (CSV has no formulas). */
    fun toText(w: Workbook5, s: Sheet5 = w.book.sheets.first(), delim: Char = w.meta?.csvDelimiter ?: ','): String {
        val used = s.usedRange() ?: return ""
        val sb = StringBuilder()
        for (r in 0..used.r2) {
            val cols = (0..used.c2).map { c ->
                when (val v = w.book.value(s, r, c)) {
                    is FV.Num -> if (NumFmt5.isDateCode(w.styles.numFmtOfStyle(s.cell(r, c)?.style ?: 0))) w.book.display(s, r, c).text else FxCoerce.general(v.v)
                    is FV.Blank -> ""
                    is FV.Err -> v.code
                    else -> runCatching { FxCoerce.str(v) }.getOrDefault("")
                }
            }
            val last = cols.indexOfLast { it.isNotEmpty() }
            sb.append(cols.take(last + 1).joinToString(delim.toString()) { quote(it, delim) }).append("\r\n")
        }
        return sb.toString()
    }

    fun save(w: Workbook5, target: File) {
        val bytes = toText(w).toByteArray(Charsets.UTF_8)
        val tmp = File(target.absoluteFile.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        tmp.outputStream().use { o -> if (w.meta?.csvBom == true) o.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())); o.write(bytes) }
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
    }

    /** Converts a CSV workbook into a new .xlsx workbook (for "save as xlsx"). */
    fun toXlsx(w: Workbook5, rtl: Boolean): Workbook5 {
        val x = XlsxIO5.blank(w.book.sheets.first().name, rtl)
        val dst = x.book.sheets.first()
        for ((k, c) in w.book.sheets.first().cells) dst.cells[k] = Cell5(c.formula, c.literal)
        dst.dirty = true
        x.book.recalcAll()
        return x
    }
}
