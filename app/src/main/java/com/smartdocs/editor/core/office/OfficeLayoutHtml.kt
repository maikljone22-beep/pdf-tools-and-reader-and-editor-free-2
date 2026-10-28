package com.smartdocs.editor.core.office

import com.smartdocs.editor.core.office.OfficeHtml.esc
import com.smartdocs.editor.core.office.OfficeHtml.fmt

/** Spreadsheet → HTML grid with sticky column letters / row numbers and gridlines. */
internal object OfficeSheetHtml {
    private const val CSS = """
body{background:#fff;color:#111;font-size:13px;}
.wrap{overflow:auto;}
table.g{border-collapse:separate;border-spacing:0;table-layout:fixed;}
table.g td,table.g th{border-right:1px solid #d0d4d9;border-bottom:1px solid #d0d4d9;padding:2px 4px;height:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
table.g[dir=rtl] td,table.g[dir=rtl] th{border-right:0;border-left:1px solid #d0d4d9;}
table.g td.w{white-space:pre-wrap;overflow-wrap:anywhere;}
table.g thead th{position:sticky;top:0;background:#f1f3f4;color:#555;font-weight:normal;z-index:2;text-align:center;}
table.g th.rn{position:sticky;inset-inline-start:0;background:#f1f3f4;color:#555;font-weight:normal;z-index:1;text-align:center;min-width:38px;width:38px;}
table.g thead th.rn{z-index:3;}
td.n{text-align:right;} table.g[dir=rtl] td.n{text-align:left;} td.b,td.e{text-align:center;}
.trunc{padding:8px;color:#a33;font-size:12px;}
.tabs{position:fixed;bottom:0;left:0;right:0;display:flex;overflow-x:auto;background:#f1f3f4;border-top:1px solid #ccc;}
.tabs button{border:0;background:none;padding:10px 16px;font:inherit;white-space:nowrap;}
.tabs button.on{background:#fff;color:#137333;font-weight:bold;border-bottom:3px solid #137333;}
.sheet{display:none;padding-bottom:48px;} .sheet.on{display:block;}
@media print{.wrap{overflow:visible;} table.g thead th,table.g th.rn{position:static;} .tabs{display:none;} .sheet{display:block;page-break-after:always;padding:0;} table.g td{white-space:normal;}}
"""

    fun sheet(book: WorkbookModel, index: Int, forPrint: Boolean): String {
        val s = book.sheets[index]
        return buildString {
            append(OfficeHtml.head(s.name, "width=device-width, initial-scale=1", CSS))
            append("<body><div class=\"wrap\">")
            grid(s, book.styles, this, forPrint)
            append("</div></body></html>")
        }
    }

    fun workbook(book: WorkbookModel): String = buildString {
        append(OfficeHtml.head(book.sheets.firstOrNull()?.name ?: "", "width=device-width, initial-scale=1", CSS))
        append("<body>")
        book.sheets.forEachIndexed { i, s ->
            append("<div class=\"sheet").append(if (i == 0) " on" else "").append("\" id=\"s").append(i).append("\"><div class=\"wrap\">")
            grid(s, book.styles, this, false)
            append("</div></div>")
        }
        append("<div class=\"tabs\">")
        book.sheets.forEachIndexed { i, s ->
            append("<button dir=\"auto\" class=\"").append(if (i == 0) "on" else "").append("\" onclick=\"show(").append(i).append(")\">").append(esc(s.name)).append("</button>")
        }
        append("</div><script>function show(i){var a=document.querySelectorAll('.sheet'),b=document.querySelectorAll('.tabs button');for(var k=0;k<a.length;k++){a[k].className='sheet'+(k==i?' on':'');b[k].className=k==i?'on':'';}}</script>")
        append("</body></html>")
    }

    // ---------------- print (Excel → PDF, convert audit A-H9) ----------------

    private const val PRINT_CSS = """
body{background:#fff;color:#111;font-size:10pt;}
h2.sn{font-size:12pt;margin:0 0 6px 0;}
table.p{border-collapse:collapse;table-layout:fixed;}
table.p td,table.p th{border:0.5pt solid #b8bcc2;padding:1px 4px;vertical-align:bottom;overflow:hidden;white-space:pre-wrap;overflow-wrap:anywhere;}
table.p thead th{background:#eef1f5;font-weight:bold;}
table.p tr{break-inside:avoid;page-break-inside:avoid;}
td.n{text-align:right;} table.p[dir=rtl] td.n{text-align:left;} td.b,td.e{text-align:center;}
.trunc{padding:6px;color:#a33;font-size:9pt;}
.sheet{break-after:page;page-break-after:always;} .sheet:last-child{break-after:auto;page-break-after:auto;}
"""

    /** Used area of a sheet: rows / columns that hold a value or a merge (no empty padding rows / columns). */
    fun usedRange(s: SheetModel): Pair<IntRange, IntRange> {
        var r0 = Int.MAX_VALUE; var r1 = -1; var c0 = Int.MAX_VALUE; var c1 = -1
        s.rows.forEach { row -> row.cells.forEach { c -> if (c.display.isNotBlank()) { r0 = minOf(r0, row.index); r1 = maxOf(r1, row.index); c0 = minOf(c0, c.col); c1 = maxOf(c1, c.col) } } }
        s.merges.forEach { m -> r0 = minOf(r0, m.r1); r1 = maxOf(r1, m.r2); c0 = minOf(c0, m.c1); c1 = maxOf(c1, m.c2) }
        if (r1 < 0) return IntRange.EMPTY to IntRange.EMPTY
        return r0..r1 to c0..c1
    }

    /** FINAL_CHECK_2 #9: scale and column bands of a sheet printed on a [pageW] × [pageH] px page in fit [mode]. */
    fun fitPlan(s: SheetModel, pageW: Int, pageH: Int, mode: Int): ExcelFit.Plan {
        val (rows, cols) = usedRange(s)
        val byRow = s.rows.associateBy { it.index }
        val defPx = s.defaultRowPt * 4 / 3
        val height = if (rows.isEmpty()) 0 else rows.sumOf { r -> ((byRow[r]?.heightPt?.let { it * 4 / 3 } ?: defPx) + 1).toDouble() }.toInt()
        return ExcelFit.plan(cols, cols.map { colPx(s, it) }, height, pageW, pageH, mode, s.print)
    }

    /** Printed width of the used columns in CSS px. */
    fun printWidthPx(s: SheetModel): Int { val (_, cols) = usedRange(s); return cols.sumOf { colPx(s, it) } }

    /** Landscape when a sheet is wider than a portrait page (≈ 700 px of printable width). */
    fun wantsLandscape(book: WorkbookModel): Boolean = book.sheets.any { printWidthPx(it) > 700 }

    /**
     * One sheet (rows [rows] of its used range) as a clean printable table: no column letters, no row numbers, no
     * padding rows / columns, gridlines as thin borders; the first row repeats on every page when it looks like a header;
     * a sheet wider than the page is zoomed to fit its width ([pageWidthPx]). [title] is shown above the first part.
     */
    fun printSheet(book: WorkbookModel, index: Int, rows: IntRange?, pageWidthPx: Int, title: String?, truncatedNote: String?,
                   band: IntRange? = null, fixedZoom: Float? = null): String = buildString {
        val s = book.sheets[index]
        val (used, allCols) = usedRange(s)
        // FINAL_CHECK_2 #9: one column band of the sheet, at the scale of its fit plan
        val cols = band?.let { maxOf(it.first, allCols.first)..minOf(it.last, allCols.last) } ?: allCols
        append(OfficeHtml.head(s.name, "width=device-width, initial-scale=1", PRINT_CSS))
        append("<body><div class=\"sheet\">")
        if (title != null) append("<h2 class=\"sn\" dir=\"auto\">").append(esc(title)).append("</h2>")
        if (used.isEmpty()) { append("</div></body></html>"); return@buildString }
        val range = rows?.let { maxOf(it.first, used.first)..minOf(it.last, used.last) } ?: used
        val width = cols.sumOf { colPx(s, it) }.coerceAtLeast(1)
        val zoom = fixedZoom ?: minOf(1f, pageWidthPx.toFloat() / width)
        val byRow = s.rows.associateBy { it.index }
        val covered = HashSet<Long>(); val spans = HashMap<Long, Pair<Int, Int>>()
        for (m0 in s.merges) {
            // a merge crossing the band's edge is cut to the band
            if (cols.isEmpty() || m0.c2 < cols.first || m0.c1 > cols.last) continue
            val m = m0.copy(c1 = maxOf(m0.c1, cols.first), c2 = minOf(m0.c2, cols.last))
            spans[key(m.r1, m.c1)] = (m.r2 - m.r1 + 1) to (m.c2 - m.c1 + 1)
            for (r in m.r1..m.r2) for (c in m.c1..m.c2) if (r != m.r1 || c != m.c1) covered.add(key(r, c))
        }
        val first = byRow[used.first]
        val headerLike = first != null && first.cells.count { it.display.isNotBlank() } >= 2 && first.cells.all { it.type == "s" || it.display.isBlank() } &&
            (used.first + 1..minOf(used.last, used.first + 5)).any { r -> byRow[r]?.cells?.any { it.type == "n" } == true }
        append("<table class=\"p\" dir=\"").append(if (s.rtl) "rtl" else "ltr").append('"')
        if (zoom < 0.999f) append(" style=\"zoom:").append(fmt(zoom)).append('"')
        append("><colgroup>")
        for (c in cols) append("<col style=\"width:").append(colPx(s, c)).append("px\">")
        append("</colgroup>")
        fun row(r: Int, tag: String) {
            val rw = byRow[r]
            append("<tr")
            rw?.heightPt?.let { append(" style=\"height:").append((it * 4 / 3).toInt()).append("px\"") }
            append('>')
            val cells = rw?.cells?.associateBy { it.col } ?: emptyMap()
            for (c in cols) {
                if (key(r, c) in covered) continue
                val cell = cells[c]
                val st = cell?.let { book.styles.getOrNull(it.styleIndex) }
                append('<').append(tag)
                val cls = when (cell?.type) { "n" -> if (st?.hAlign == null) "n" else ""; "b" -> "b"; "e" -> "e"; else -> "" }
                if (cls.isNotBlank()) append(" class=\"").append(cls).append('"')
                spans[key(r, c)]?.let { (rs, cs) -> if (rs > 1) append(" rowspan=\"").append(rs).append('"'); if (cs > 1) append(" colspan=\"").append(cs).append('"') }
                if (cell?.type == "s") append(" dir=\"auto\"")
                if (st != null) {
                    val css = StringBuilder()
                    if (st.bold) css.append("font-weight:bold;")
                    if (st.italic) css.append("font-style:italic;")
                    st.color?.let { css.append("color:#").append(it).append(';') }
                    st.fill?.let { css.append("background:#").append(it).append(';') }
                    when (st.hAlign) { "center", "centerContinuous" -> css.append("text-align:center;"); "left" -> css.append("text-align:left;"); "right" -> css.append("text-align:right;"); "justify" -> css.append("text-align:justify;") }
                    if (css.isNotEmpty()) append(" style=\"").append(css).append('"')
                }
                append('>')
                if (cell != null) append(esc(cell.display))
                append("</").append(tag).append('>')
            }
            append("</tr>")
        }
        if (headerLike) { append("<thead>"); row(used.first, "th"); append("</thead>") }
        append("<tbody>")
        for (r in range) { if (headerLike && r == used.first) continue; row(r, "td") }
        append("</tbody></table>")
        if (truncatedNote != null) append("<div class=\"trunc\" dir=\"auto\">").append(esc(truncatedNote)).append("</div>")
        append("</div></body></html>")
    }

    private fun colPx(s: SheetModel, c: Int): Int {
        val w = s.colWidths[c] ?: s.defaultColWidth
        return if (w <= 0f) 0 else (w * 7 + 5).toInt().coerceIn(16, 900)
    }

    private fun grid(s: SheetModel, styles: List<CellStyle>, sb: StringBuilder, forPrint: Boolean) {
        val maxCol = maxOf(s.maxCol, s.merges.maxOfOrNull { it.c2 } ?: -1)
        val maxRow = maxOf(s.maxRow, s.merges.maxOfOrNull { it.r2 } ?: -1)
        val cols = (maxCol + 1 + if (forPrint) 0 else 3).coerceIn(1, 200)
        var rows = (maxRow + 1 + if (forPrint) 0 else 10).coerceAtLeast(1)
        var truncated = s.truncated
        if (rows.toLong() * cols > OfficeHtml.maxSheetCells) { rows = OfficeHtml.maxSheetCells / cols; truncated = true }
        val byRow = s.rows.associateBy { it.index }
        // covered cells of merges -> skip; top-left -> spans
        val covered = HashSet<Long>(); val spans = HashMap<Long, Pair<Int, Int>>()
        for (m in s.merges) {
            spans[key(m.r1, m.c1)] = (m.r2 - m.r1 + 1) to (m.c2 - m.c1 + 1)
            for (r in m.r1..m.r2) for (c in m.c1..m.c2) if (r != m.r1 || c != m.c1) covered.add(key(r, c))
        }
        sb.append("<table class=\"g\" dir=\"").append(if (s.rtl) "rtl" else "ltr").append("\"><colgroup><col style=\"width:38px\">")
        for (c in 0 until cols) sb.append("<col style=\"width:").append(colPx(s, c)).append("px\">")
        sb.append("</colgroup><thead><tr><th class=\"rn\"></th>")
        for (c in 0 until cols) sb.append("<th>").append(CellRef.colName(c)).append("</th>")
        sb.append("</tr></thead><tbody>")
        for (r in 0 until rows) {
            val row = byRow[r]
            sb.append("<tr")
            row?.heightPt?.let { sb.append(" style=\"height:").append((it * 4 / 3).toInt()).append("px\"") }
            sb.append("><th class=\"rn\">").append(r + 1).append("</th>")
            val cells = row?.cells?.associateBy { it.col } ?: emptyMap()
            for (c in 0 until cols) {
                if (key(r, c) in covered) continue
                val cell = cells[c]
                val st = cell?.let { styles.getOrNull(it.styleIndex) }
                sb.append("<td")
                val cls = when (cell?.type) { "n" -> if (st?.hAlign == null) "n" else ""; "b" -> "b"; "e" -> "e"; else -> "" } + if (st?.wrap == true) " w" else ""
                if (cls.isNotBlank()) sb.append(" class=\"").append(cls.trim()).append('"')
                spans[key(r, c)]?.let { (rs, cs) -> if (rs > 1) sb.append(" rowspan=\"").append(rs).append('"'); if (cs > 1) sb.append(" colspan=\"").append(cs).append('"') }
                if (cell?.type == "s") sb.append(" dir=\"auto\"") else if (cell != null) sb.append(" dir=\"ltr\"")
                if (st != null) {
                    val css = StringBuilder()
                    if (st.bold) css.append("font-weight:bold;")
                    if (st.italic) css.append("font-style:italic;")
                    st.color?.let { css.append("color:#").append(it).append(';') }
                    st.fill?.let { css.append("background:#").append(it).append(';') }
                    when (st.hAlign) { "center", "centerContinuous" -> css.append("text-align:center;"); "left" -> css.append("text-align:left;"); "right" -> css.append("text-align:right;"); "justify" -> css.append("text-align:justify;") }
                    if (css.isNotEmpty()) sb.append(" style=\"").append(css).append('"')
                }
                sb.append('>')
                if (cell != null) sb.append(esc(cell.display))
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        if (truncated) sb.append("<div class=\"trunc\" dir=\"auto\">… ").append(rows).append(" × ").append(cols).append("</div>")
    }

    private fun key(r: Int, c: Int): Long = (r.toLong() shl 20) or c.toLong()
}

/** Presentation → absolutely positioned slides, 960 CSS px wide, stacked vertically. */
internal object OfficeSlideHtml {
    const val SLIDE_PX = 960

    private const val CSS = """
body{background:#5f6368;}
.sl{position:relative;margin:18px auto;background:#fff;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.4);}
.num{width:960px;margin:0 auto;color:#e8eaed;font-size:13px;text-align:center;}
.sh{position:absolute;box-sizing:border-box;display:flex;flex-direction:column;overflow:visible;padding:4px 8px;}
.sh p{margin:0;line-height:1.2;overflow-wrap:break-word;}
.pic{position:absolute;}
.pic img{width:100%;height:100%;object-fit:fill;display:block;}
.tb{position:absolute;}
.tb table{border-collapse:collapse;width:100%;height:100%;}
.tb td{border:1px solid #888;padding:4px 6px;vertical-align:top;}
.noimg{width:100%;height:100%;border:1px dashed #999;box-sizing:border-box;}
@media print{body{background:#fff;}.sl{margin:0 auto;box-shadow:none;page-break-after:always;break-after:page;border:1px solid #ddd;}.num{display:none;}}
"""

    /** Print layout: one slide per page of the slide's own size (the caller sets that paper), no frame or gap (audit A-M3). */
    private const val PRINT_CSS = "@media print{.sl{margin:0!important;border:0!important;box-shadow:none!important;}}html,body{margin:0;padding:0;background:#fff;}"

    /** [pageWidthPx] (print): the printable width in CSS px; each 960-px slide is zoomed to it. */
    fun render(model: PresentationModel, forPrint: Boolean, pageWidthPx: Int = 0): String = buildString {
        append(OfficeHtml.head("", "width=1000", CSS + if (forPrint && pageWidthPx > 0) PRINT_CSS else ""))
        append("<body>")
        val zoom = if (forPrint && pageWidthPx > 0) pageWidthPx.toFloat() / SLIDE_PX else 0f
        model.slides.forEachIndexed { i, s ->
            val scale = SLIDE_PX.toDouble() / s.width.coerceAtLeast(1)
            val hPx = (s.height * scale).toInt()
            if (!forPrint) append("<div class=\"num\">").append(i + 1).append(" / ").append(model.slides.size).append("</div>")
            append("<div class=\"sl\" id=\"slide").append(i + 1).append("\" style=\"width:").append(SLIDE_PX).append("px;height:").append(hPx).append("px;")
            if (zoom > 0f) append("zoom:").append(fmt(zoom)).append(';')
            s.background?.let { append("background:#").append(it).append(';') }
            append("\">")
            s.shapes.forEach { shape(it, scale, this) }
            append("</div>")
        }
        append("</body></html>")
    }

    private fun box(sh: SlideShape, scale: Double): String =
        "left:${(sh.x * scale).toInt()}px;top:${(sh.y * scale).toInt()}px;width:${(sh.w * scale).toInt()}px;height:${(sh.h * scale).toInt()}px;"

    private fun shape(sh: SlideShape, scale: Double, sb: StringBuilder) {
        // 1pt = 12700 EMU; css px per pt at this scale
        val pxPerPt = (12700 * scale).toFloat()
        when (sh) {
            is SlideShape.Picture -> {
                sb.append("<div class=\"pic\" style=\"").append(box(sh, scale)).append("\">")
                if (OfficeHtml.browserImage(sh.image)) sb.append("<img src=\"").append(OfficeHtml.dataUri(sh.image!!)).append("\">") else sb.append("<div class=\"noimg\"></div>")
                sb.append("</div>")
            }
            is SlideShape.TextBox -> {
                sb.append("<div class=\"sh\" style=\"").append(box(sh, scale))
                when (sh.anchor) { "ctr" -> sb.append("justify-content:center;"); "b" -> sb.append("justify-content:flex-end;") }
                sh.fill?.let { sb.append("background:#").append(it).append(';') }
                sh.line?.let { sb.append("border:1px solid #").append(it).append(';') }
                if (sh.rotation != 0f) sb.append("transform:rotate(").append(fmt(sh.rotation)).append("deg);")
                sb.append("font-size:").append(fmt(sh.defaultSizePt * pxPerPt)).append("px;\">")
                sh.paragraphs.forEach { paragraph(it, pxPerPt, sb) }
                sb.append("</div>")
            }
            is SlideShape.Table -> {
                sb.append("<div class=\"tb\" style=\"").append(box(sh, scale)).append("font-size:").append(fmt(14 * pxPerPt)).append("px;\"><table dir=\"auto\">")
                if (sh.colWidths.isNotEmpty()) {
                    sb.append("<colgroup>"); sh.colWidths.forEach { sb.append("<col style=\"width:").append((it * scale).toInt()).append("px\">") }; sb.append("</colgroup>")
                }
                sh.rows.forEach { r ->
                    sb.append("<tr>")
                    r.forEach { c -> sb.append("<td>"); c.forEach { paragraph(it, pxPerPt, sb) }; sb.append("</td>") }
                    sb.append("</tr>")
                }
                sb.append("</table></div>")
            }
        }
    }

    private fun paragraph(p: SlideParagraph, pxPerPt: Float, sb: StringBuilder) {
        sb.append("<p dir=\"").append(if (p.rtl) "rtl" else "auto").append('"')
        val css = StringBuilder()
        when (p.align) {
            Align.CENTER -> css.append("text-align:center;"); Align.LEFT -> css.append("text-align:left;"); Align.RIGHT -> css.append("text-align:right;")
            Align.JUSTIFY -> css.append("text-align:justify;"); else -> {}
        }
        if (p.level > 0) css.append("padding-inline-start:").append((p.level * 24 * pxPerPt).toInt()).append("px;")
        if (css.isNotEmpty()) sb.append(" style=\"").append(css).append('"')
        sb.append('>')
        val hasText = p.runs.any { it is Inline.Text && it.text.isNotEmpty() }
        if (p.bullet != null && hasText) sb.append(esc(p.bullet)).append("&nbsp;")
        p.runs.forEach { r ->
            when (r) {
                is Inline.Text -> OfficeHtml.run(r.text, r.style, null, sb, pxPerPt, "px")
                Inline.LineBreak -> sb.append("<br>")
                else -> {}
            }
        }
        if (!hasText) sb.append("<br>")
        sb.append("</p>")
    }
}
