package com.smartdocs.editor.core.office

import java.util.Base64

/**
 * Renders the office models to self-contained HTML (inline CSS, images as data URIs, Arabic font
 * stack, per-paragraph direction). Pure Kotlin so it can run off-device.
 */
object OfficeHtml {
    const val FONT_URL = "file:///android_asset/fonts/NotoNaskhArabic-Regular.ttf"
    const val FONT_STACK = "'Noto Naskh Arabic', 'Noto Sans Arabic', 'Segoe UI', Tahoma, sans-serif"

    /** Max cells emitted for one sheet to keep the WebView responsive. */
    var maxSheetCells = 120_000

    internal fun head(title: String, viewport: String, css: String, fontUrl: String = FONT_URL): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"").append(viewport).append("\">")
        append("<title>").append(esc(title)).append("</title><style>")
        append("@font-face{font-family:'Noto Naskh Arabic';src:url('").append(fontUrl).append("') format('truetype');}")
        append("html,body{margin:0;padding:0;font-family:").append(FONT_STACK).append(";-webkit-text-size-adjust:100%;}")
        append(css)
        append("</style></head>")
    }

    fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); else -> append(c) }
    }

    internal fun dataUri(img: ImageData): String = "data:" + img.mime + ";base64," + Base64.getEncoder().encodeToString(img.bytes)

    internal fun browserImage(img: ImageData?): Boolean = img != null && img.mime.startsWith("image/") && img.mime != "image/x-emf" && img.mime != "image/x-wmf" && img.mime != "image/tiff"

    /** Localised placeholder labels (set by the app from string resources; English on the JVM). */
    @Volatile var labels: Map<String, String> = emptyMap()
    private fun label(key: String, def: String) = labels[key] ?: def

    /** A picture the WebView can show: as it is, or TIFF / BMP converted to PNG (convert audit A-M5). */
    internal fun printable(img: ImageData?): ImageData? {
        if (img == null) return null
        if (browserImage(img)) return img
        if (img.mime == "image/tiff" || img.mime == "image/bmp")
            com.smartdocs.editor.core.convert6.PortableImage.normalize(img.bytes, if (img.mime == "image/tiff") "tif" else "bmp", setOf("png", "jpg"))?.let { (b, e) -> return ImageData(b, if (e == "png") "image/png" else "image/jpeg") }
        return null
    }

    // ======================= DOCX =======================

    private const val DOCX_CSS = """
body{background:#e9ebee;color:#111;}
.page{background:#fff;width:100%;max-width:794px;min-height:400px;margin:16px auto;padding:64px 60px;box-sizing:border-box;box-shadow:0 1px 4px rgba(0,0,0,.25);overflow-wrap:break-word;}
@media (max-width:820px){.page{margin:0 0 10px 0;padding:22px 16px;box-shadow:none;}}
p{margin:0 0 .45em 0;line-height:1.55;min-height:1em;}
h1,h2,h3,h4,h5,h6{margin:.6em 0 .35em 0;line-height:1.35;}
h1.title{font-size:2.1em;border-bottom:1px solid #ccc;padding-bottom:.2em;}
table{border-collapse:collapse;margin:.4em 0 .8em 0;max-width:100%;}
td,th{border:1px solid #9aa0a6;padding:4px 6px;vertical-align:top;}
td p,th p{margin:0 0 .15em 0;}
.tblwrap{overflow-x:auto;max-width:100%;}
img{max-width:100%;height:auto;}
.tab{display:inline-block;min-width:2.5em;}
.lm{display:inline-block;min-width:1.4em;margin-inline-end:.35em;text-align:center;}
.noimg{display:inline-block;border:1px dashed #999;color:#777;padding:6px;font-size:12px;}
.secbrk{border:0;border-top:1px dotted #bbb;margin:1em 0;}
a{color:#1a5fb4;}
@media print{body{background:#fff;}.page{box-shadow:none;margin:0;padding:0;max-width:none;break-after:page;page-break-after:always;}.page:last-child{break-after:auto;page-break-after:auto;}.secbrk{display:none;}.tblwrap{overflow:visible;}}
"""

    private const val PRINT_CSS = """
@media print{.page{padding:0!important;}}
table.docwrap{width:100%;border:0;border-collapse:collapse;margin:0;}
table.docwrap>thead>tr>td,table.docwrap>tfoot>tr>td,table.docwrap>tbody>tr>td{border:0;padding:0;}
.hdrf{color:#444;font-size:.9em;}
.hdrf.h{border-bottom:1px solid #ccc;margin-bottom:8px;}
.hdrf.f{border-top:1px solid #ccc;margin-top:8px;}
.notes{border-top:1px solid #999;margin-top:1.2em;padding-top:.4em;font-size:.9em;}
.notes .nm{vertical-align:super;font-size:.75em;margin-inline-end:.3em;}
"""

    /**
     * Word document → HTML. [forPrint] (Word → PDF): the default header and footer repeat on every printed page (a
     * table whose thead / tfoot Chromium repeats per page), footnotes / endnotes follow the text, equations are linear
     * text (convert audit A-H10); the page size and margins are given to the printer by the caller ([DocModel.page]).
     */
    fun docx(model: DocModel, title: String = model.title ?: "", forPrint: Boolean = false): String = buildString {
        append(head(title, "width=device-width, initial-scale=1", DOCX_CSS + (if (forPrint) PRINT_CSS else "")))
        append("<body>")
        val wrap = forPrint && (model.header.isNotEmpty() || model.footer.isNotEmpty())
        if (wrap) {
            append("<table class=\"docwrap\">")
            if (model.header.isNotEmpty()) { append("<thead><tr><td><div class=\"hdrf h\">"); blocks(model.header, this); append("</div></td></tr></thead>") }
            if (model.footer.isNotEmpty()) { append("<tfoot><tr><td><div class=\"hdrf f\">"); blocks(model.footer, this); append("</div></td></tr></tfoot>") }
            append("<tbody><tr><td>")
        } else if (!forPrint && model.header.isNotEmpty()) { append("<div class=\"page hdrf h\">"); blocks(model.header, this); append("</div>") }
        append("<div class=\"page\">")
        blocks(model.blocks, this)
        if (model.notes.isNotEmpty()) {
            append("<div class=\"notes\">")
            model.notes.forEach { (m, bs) -> append("<div><span class=\"nm\">").append(esc(m)).append("</span>"); blocks(bs, this); append("</div>") }
            append("</div>")
        }
        append("</div>")
        if (wrap) append("</td></tr></tbody></table>")
        else if (!forPrint && model.footer.isNotEmpty()) { append("<div class=\"page hdrf f\">"); blocks(model.footer, this); append("</div>") }
        append("</body></html>")
    }

    private fun blocks(bs: List<Block>, sb: StringBuilder) {
        for (b in bs) when (b) {
            is Block.Para -> paragraph(b.p, sb)
            is Block.Table -> table(b, sb)
            Block.PageBreak -> sb.append("</div><div class=\"page\">")
            Block.SectionBreak -> sb.append("<hr class=\"secbrk\">")
        }
    }

    private fun alignCss(a: Align?, rtl: Boolean): String? = when (a) {
        null -> null
        Align.CENTER -> "center"; Align.JUSTIFY -> "justify"; Align.START -> "start"; Align.END -> "end"
        // WordprocessingML transitional: left/right are logical (leading/trailing) in bidi paragraphs
        Align.LEFT -> if (rtl) "right" else "left"
        Align.RIGHT -> if (rtl) "left" else "right"
    }

    private fun paragraph(p: Paragraph, sb: StringBuilder) {
        val tag = when { p.heading == -1 -> "h1"; p.heading in 1..6 -> "h${p.heading}"; else -> "p" }
        val css = StringBuilder()
        alignCss(p.align, p.rtl)?.let { css.append("text-align:").append(it).append(';') }
        val indentPx = p.indentTwips / 15 + if (p.listLevel >= 0) 18 * p.listLevel else 0
        if (indentPx > 0) css.append("padding-inline-start:").append(indentPx.coerceAtMost(300)).append("px;")
        p.shading?.let { css.append("background:#").append(it).append(';') }
        sb.append('<').append(tag)
        if (p.heading == -1) sb.append(" class=\"title\"")
        sb.append(" dir=\"").append(if (p.rtl) "rtl" else "auto").append('"')
        if (css.isNotEmpty()) sb.append(" style=\"").append(css).append('"')
        sb.append('>')
        if (!p.listMarker.isNullOrEmpty()) sb.append("<span class=\"lm\">").append(esc(p.listMarker)).append("</span>")
        var any = false
        var openPage = false
        for (i in p.inlines) {
            when (i) {
                is Inline.Text -> { if (i.text.isNotEmpty()) any = true; run(i.text, i.style, i.link, sb) }
                Inline.Tab -> sb.append("<span class=\"tab\"></span>")
                Inline.LineBreak -> sb.append("<br>")
                Inline.PageBreak -> { sb.append("</").append(tag).append("></div><div class=\"page\"><").append(tag).append(" dir=\"auto\">"); openPage = true }
                is Inline.Image -> { any = true; image(i, sb) }
            }
        }
        if (!any && !openPage) sb.append("<br>")
        sb.append("</").append(tag).append('>')
    }

    internal fun runCss(s: RunStyle, scale: Float = 1f, ptUnit: String = "pt"): String {
        val css = StringBuilder()
        if (s.bold) css.append("font-weight:bold;")
        if (s.italic) css.append("font-style:italic;")
        if (s.underline || s.strike) css.append("text-decoration:").append(if (s.underline) "underline " else "").append(if (s.strike) "line-through" else "").append(';')
        s.color?.let { css.append("color:#").append(it).append(';') }
        s.background?.let { css.append("background:#").append(it).append(';') }
        s.sizePt?.let { css.append("font-size:").append(fmt(it * scale)).append(ptUnit).append(';') }
        s.font?.let { f -> if (f.isNotBlank() && !f.contains('\'') && !f.contains('"')) css.append("font-family:'").append(esc(f)).append("',").append(FONT_STACK).append(';') }
        return css.toString()
    }

    internal fun run(text: String, s: RunStyle, link: String?, sb: StringBuilder, scale: Float = 1f, ptUnit: String = "pt") {
        val css = runCss(s, scale, ptUnit)
        val safeLink = link?.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("mailto:") || it.startsWith("tel:") }
        if (safeLink != null) sb.append("<a href=\"").append(esc(safeLink)).append("\">")
        if (s.vertAlign == "superscript") sb.append("<sup>") else if (s.vertAlign == "subscript") sb.append("<sub>")
        if (css.isNotEmpty() || s.rtl) {
            sb.append("<span")
            if (s.rtl) sb.append(" dir=\"rtl\"")
            if (css.isNotEmpty()) sb.append(" style=\"").append(css).append('"')
            sb.append('>').append(esc(text)).append("</span>")
        } else sb.append(esc(text))
        if (s.vertAlign == "superscript") sb.append("</sup>") else if (s.vertAlign == "subscript") sb.append("</sub>")
        if (safeLink != null) sb.append("</a>")
    }

    private fun image(i: Inline.Image, sb: StringBuilder) {
        val img = printable(i.image)
        val wPx = (i.widthEmu / 9525).toInt()
        if (img == null) {
            val what = when { i.image == null -> label("chart", "[chart]"); else -> label("image", "[image]") + " (" + i.image.mime.substringAfter('/').removePrefix("x-").uppercase() + ")" }
            sb.append("<span class=\"noimg\">").append(esc(listOfNotNull(i.alt?.takeIf { it.isNotBlank() }, what).joinToString(" · "))).append("</span>")
            return
        }
        sb.append("<img src=\"").append(dataUri(img)).append('"')
        if (wPx > 0) sb.append(" style=\"width:").append(wPx).append("px\"")
        i.alt?.let { sb.append(" alt=\"").append(esc(it)).append('"') }
        sb.append('>')
    }

    private fun table(t: Block.Table, sb: StringBuilder) {
        sb.append("<div class=\"tblwrap\"><table dir=\"").append(if (t.rtl) "rtl" else "auto").append("\">")
        if (t.colWidthsTwips.isNotEmpty() && t.colWidthsTwips.all { it > 0 }) {
            sb.append("<colgroup>")
            t.colWidthsTwips.forEach { sb.append("<col style=\"width:").append(it / 15).append("px\">") }
            sb.append("</colgroup>")
        }
        for (r in t.rows) {
            sb.append("<tr>")
            for (c in r.cells) {
                if (c.mergedAway) continue
                val tag = if (r.header) "th" else "td"
                sb.append('<').append(tag)
                if (c.colSpan > 1) sb.append(" colspan=\"").append(c.colSpan).append('"')
                if (c.rowSpan > 1) sb.append(" rowspan=\"").append(c.rowSpan).append('"')
                c.shading?.let { sb.append(" style=\"background:#").append(it).append('"') }
                sb.append('>')
                blocks(c.blocks.filter { it !is Block.PageBreak }, sb)
                sb.append("</").append(tag).append('>')
            }
            sb.append("</tr>")
        }
        sb.append("</table></div>")
    }

    // ======================= TXT =======================

    fun text(content: String, title: String = ""): String = buildString {
        append(head(title, "width=device-width, initial-scale=1", "body{background:#fff;color:#111;padding:14px;}.l{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.6;min-height:1.2em;font-size:15px;}"))
        append("<body>")
        content.split('\n').forEach { line -> append("<div class=\"l\" dir=\"auto\">").append(esc(line.removeSuffix("\r"))).append("</div>") }
        append("</body></html>")
    }

    // ======================= XLSX / PPTX =======================

    fun xlsx(book: WorkbookModel): String = OfficeSheetHtml.workbook(book)
    fun xlsxSheet(book: WorkbookModel, index: Int, forPrint: Boolean = false): String = OfficeSheetHtml.sheet(book, index, forPrint)
    fun pptx(model: PresentationModel, forPrint: Boolean = false, pageWidthPx: Int = 0): String = OfficeSlideHtml.render(model, forPrint, pageWidthPx)

    /** Printable sheet (Excel → PDF): see [OfficeSheetHtml.printSheet]. */
    fun xlsxPrint(book: WorkbookModel, index: Int, rows: IntRange?, pageWidthPx: Int, title: String?, truncatedNote: String?,
                  band: IntRange? = null, zoom: Float? = null): String =
        OfficeSheetHtml.printSheet(book, index, rows, pageWidthPx, title, truncatedNote, band, zoom)
    fun xlsxUsedRows(s: SheetModel): IntRange = OfficeSheetHtml.usedRange(s).first
    fun xlsxLandscape(book: WorkbookModel): Boolean = OfficeSheetHtml.wantsLandscape(book)
    fun xlsxFit(s: SheetModel, pageW: Int, pageH: Int, mode: Int): ExcelFit.Plan = OfficeSheetHtml.fitPlan(s, pageW, pageH, mode)

    internal fun fmt(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else String.format(java.util.Locale.US, "%.2f", f)
}
