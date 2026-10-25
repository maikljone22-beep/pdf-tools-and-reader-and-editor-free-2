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
                w.write("<$tag dir=\"${if (b.rtl) "rtl" else "ltr"}\"$cls>")
                TextRuns.ofParagraph(b.lines).forEach { r ->
                    var t = esc(r.text)
                    if (r.italic) t = "<i>$t</i>"
                    if (r.bold && b.headingLevel == 0) t = "<b>$t</b>"
                    w.write(t)
                }
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
                        w.write("<$tag$span${if (num) " class=\"n\"" else ""}>")
                        w.write(c.lines.joinToString("<br>") { esc(it.text) })
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

    /** Plain text of a page: paragraphs separated by blank lines, table rows as tab-separated cells. */
    fun text(page: PageLayout): String {
        val sb = StringBuilder()
        for (b in page.blocks) when (b) {
            is ParagraphBlock -> { sb.append(TextRuns.plain(b.lines)).append("\n\n") }
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
}
