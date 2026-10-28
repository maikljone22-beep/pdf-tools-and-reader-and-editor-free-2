package com.smartdocs.editor.core.convert

import java.io.Writer
import java.util.Base64

/** HTML and plain-text writers over analysed pages (headings, paragraphs, tables, images). */
object HtmlTxtBuilder {

    private fun esc(s: String) = BidiText.xml(s)

    fun htmlHead(w: Writer, title: String, rtl: Boolean) {
        w.write("<!DOCTYPE html>\n<html lang=\"${if (rtl) "ar" else "en"}\" dir=\"${if (rtl) "rtl" else "ltr"}\">\n<head>\n<meta charset=\"utf-8\">\n")
        w.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>${esc(title)}</title>\n")
        w.write("<style>body{font-family:'Noto Naskh Arabic','Segoe UI',Tahoma,Arial,sans-serif;line-height:1.7;max-width:900px;margin:24px auto;padding:0 16px;color:#222}" +
            "p{margin:0 0 10px}table{border-collapse:collapse;margin:12px 0;max-width:100%}td,th{padding:4px 8px;vertical-align:top}" +
            "table.ruled td,table.ruled th{border:1px solid #999}th{background:#dce6f1}img{max-width:100%;height:auto;display:block;margin:10px auto}" +
            ".pg{border:0;border-top:1px dashed #bbb;margin:28px 0}.c{text-align:center}.e{text-align:end}td.n{text-align:right;direction:ltr}</style>\n</head>\n<body>\n")
    }

    fun htmlPage(w: Writer, page: PageLayout, first: Boolean, embedImages: Boolean = true) {
        if (!first) w.write("<hr class=\"pg\">\n")
        for (b in page.blocks) when (b) {
            is ParagraphBlock -> {
                val tag = if (b.headingLevel in 1..3) "h${b.headingLevel}" else "p"
                val cls = when (b.align) { Align.CENTER -> " class=\"c\""; Align.END -> " class=\"e\""; else -> "" }
                val shade = b.shade?.let { " style=\"background:#${Decorations.hex(it)};padding:6px 10px\"" } ?: ""
                w.write("<$tag dir=\"${if (b.rtl) "rtl" else "ltr"}\"$cls$shade>")
                TextRuns.ofParagraph(b.lines).forEach { r -> w.write(run(r, b.headingLevel == 0)) }
                w.write("</$tag>\n")
            }
            is TableBlock -> {
                val header = LayoutAnalyzer.headerLikely(b)
                val rtl = b.rtl
                w.write("<table dir=\"${if (rtl) "rtl" else "ltr"}\"${if (b.ruled) " class=\"ruled\"" else ""}>\n")
                for (r in 0 until b.rows) {
                    w.write("<tr>")
                    val rowCells = b.cells.filter { it.row == r }.let { cs -> if (rtl) cs.sortedByDescending { it.col } else cs.sortedBy { it.col } }
                    for (c in rowCells) {
                        val tag = if (header && r == 0) "th" else "td"
                        val span = (if (c.colSpan > 1) " colspan=\"${c.colSpan}\"" else "") + (if (c.rowSpan > 1) " rowspan=\"${c.rowSpan}\"" else "")
                        val num = tag == "td" && BidiText.parseNumber(c.text) != null
                        val bg = c.fill?.let { "#" + Decorations.hex(it) } ?: if (tag == "th" && page.hasFills) "transparent" else null
                        w.write("<$tag$span${if (num) " class=\"n\"" else ""}${if (bg != null) " style=\"background:$bg\"" else ""}>")
                        w.write(c.lines.joinToString("<br>") { l -> TextRuns.of(l).joinToString("") { run(it, true) } })
                        w.write("</$tag>")
                    }
                    w.write("</tr>\n")
                }
                w.write("</table>\n")
            }
            is ImageBlock -> if (embedImages && b.image.w >= 8f && b.image.h >= 8f) {
                val mime = if (b.image.ext == "png") "image/png" else "image/jpeg"
                w.write("<img alt=\"\" style=\"width:${Math.round(b.image.w)}px\" src=\"data:$mime;base64,")
                w.write(Base64.getEncoder().encodeToString(b.image.bytes))
                w.write("\">\n")
            }
        }
    }

    fun htmlTail(w: Writer) = w.write("</body>\n</html>\n")

    /** One formatted run: bold / italic tags, colour, family, decorations and highlight as inline style. */
    fun run(r: TextRun, allowBold: Boolean): String {
        var t = esc(r.text)
        if (r.italic) t = "<i>$t</i>"
        if (r.bold && allowBold) t = "<b>$t</b>"
        val st = StringBuilder()
        if (r.color != 0) st.append("color:#").append(Decorations.hex(r.color)).append(';')
        r.font?.let { if (it != FontMap.ARIAL) st.append("font-family:'").append(esc(it)).append("',sans-serif;") }
        val deco = listOfNotNull("underline".takeIf { r.underline }, "line-through".takeIf { r.strike })
        if (deco.isNotEmpty()) st.append("text-decoration:").append(deco.joinToString(" ")).append(';')
        r.bg?.let { st.append("background:#").append(Decorations.hex(it)).append(';') }
        return if (st.isEmpty()) t else "<span style=\"$st\">$t</span>"
    }

    /**
     * Plain text of a page: paragraphs separated by blank lines, table rows as tab-separated cells. [keepLines] keeps
     * the PDF's line breaks inside paragraphs; [wrapAt] > 0 word-wraps lines at that many characters.
     */
    fun text(page: PageLayout, keepLines: Boolean = false, wrapAt: Int = 0): String {
        val sb = StringBuilder()
        for (b in page.blocks) when (b) {
            is ParagraphBlock -> {
                val raw = if (keepLines) b.lines.joinToString("\n") { l -> TextRuns.of(l).joinToString("") { it.text }.trim() } else TextRuns.plain(b.lines)
                sb.append(if (wrapAt > 0) wrap(raw, wrapAt) else raw).append("\n\n")
            }
            is TableBlock -> {
                val rtl = b.rtl
                for (r in 0 until b.rows) {
                    val cs = b.cells.filter { it.row == r }.let { c -> if (rtl) c.sortedByDescending { it.col } else c.sortedBy { it.col } }
                    sb.append(cs.joinToString("\t") { it.text.replace('\n', ' ') }).append('\n')
                }
                sb.append('\n')
            }
            else -> Unit
        }
        return sb.toString().trimEnd() + "\n"
    }

    /** Word wrap of each line of [text] at [width] characters (a longer word stays whole on its own line). Pure. */
    fun wrap(text: String, width: Int): String = text.split('\n').joinToString("\n") { line ->
        val out = StringBuilder(); var len = 0
        line.split(' ').filter { it.isNotEmpty() }.forEach { w ->
            if (len > 0 && len + 1 + w.length > width) { out.append('\n'); len = 0 }
            if (len > 0) { out.append(' '); len++ }
            out.append(w); len += w.length
        }
        out.toString()
    }
}
