package com.smartdocs.editor.core.nve

import com.smartdocs.editor.core.edit.Bidi
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState

/**
 * NVE-file: pieces of the print sheet beyond layout: pages with markups, grey-scale overlay (vector, nothing
 * rasterised) and the comment summary pages appended after the printed sheets. Pure PDFBox (JVM-testable).
 */
object PrintExtras {

    /** Annotation subtypes that are not "markups" (links, form fields, pop-up windows, printer marks). */
    private val notMarkup = setOf("Link", "Widget", "Popup", "PrinterMark", "TrapNet")

    /** Zero-based pages that carry at least one markup annotation (comment, highlight, drawing, stamp...). */
    fun markupPages(doc: PDDocument): Set<Int> {
        val out = HashSet<Int>()
        doc.pages.forEachIndexed { i, p ->
            val list = runCatching { p.annotations }.getOrNull() ?: return@forEachIndexed
            if (list.any { (it.subtype ?: "") !in notMarkup }) out += i
        }
        return out
    }

    /**
     * Turns everything painted so far on [page] grey: a neutral rectangle over the whole page with blend mode
     * Saturation keeps the luminosity of what is below and removes its saturation (PDF 1.4 blend modes).
     */
    fun greyOverlay(doc: PDDocument, page: PDPage) {
        val box = page.mediaBox
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            val gs = PDExtendedGraphicsState()
            gs.cosObject.setName(COSName.getPDFName("BM"), "Saturation")
            cs.saveGraphicsState()
            cs.setGraphicsStateParameters(gs)
            cs.setNonStrokingColor(0.5f, 0.5f, 0.5f)
            cs.addRect(box.lowerLeftX, box.lowerLeftY, box.width, box.height)
            cs.fill()
            cs.restoreGraphicsState()
        }
    }

    /** One comment for the summary. [date] is the raw PDF date string or "" */
    data class Comment(val page: Int, val type: String, val author: String, val date: String, val text: String)

    /** Labels of the summary (localised by the caller). %d placeholders: page number. */
    data class Labels(val title: String = "Comments summary", val page: String = "Page %d", val none: String = "No comments")

    /** Every markup annotation of [pages] (in order) with its author, date and text. */
    fun comments(doc: PDDocument, pages: List<Int>? = null): List<Comment> {
        val out = ArrayList<Comment>()
        (pages ?: (0 until doc.numberOfPages).toList()).forEach { i ->
            if (i !in 0 until doc.numberOfPages) return@forEach
            val list = runCatching { doc.getPage(i).annotations }.getOrNull() ?: return@forEach
            list.filter { (it.subtype ?: "") !in notMarkup }.forEach { a ->
                val d = a.cosObject
                val author = d.getString(COSName.T) ?: ""
                val date = d.getString(COSName.M) ?: d.getString(COSName.getPDFName("CreationDate")) ?: ""
                val text = (a.contents ?: d.getString(COSName.getPDFName("Subj")) ?: "").trim()
                out += Comment(i, a.subtype ?: "", author, prettyDate(date), text)
            }
        }
        return out
    }

    /** "D:20240501102030+03'00'" -> "2024-05-01 10:20". */
    fun prettyDate(raw: String): String {
        val m = Regex("""D?:?(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?""").find(raw) ?: return raw
        val g = m.groupValues
        val sb = StringBuilder(g[1])
        if (g[2].isNotEmpty()) sb.append('-').append(g[2])
        if (g[3].isNotEmpty()) sb.append('-').append(g[3])
        if (g[4].isNotEmpty()) sb.append(' ').append(g[4]).append(':').append(g[5].ifEmpty { "00" })
        return sb.toString()
    }

    /**
     * Append summary pages ([paper], portrait) listing [comments] grouped by page. [arabic] draws Arabic letters
     * (shaped and reordered); [latin] draws the rest. Returns the number of pages added.
     */
    fun appendSummary(dst: PDDocument, comments: List<Comment>, labels: Labels, paper: PDRectangle = PDRectangle.A4,
                      arabic: PDFont? = null, latin: PDFont = PDType1Font.HELVETICA): Int {
        val w = minOf(paper.width, paper.height); val h = maxOf(paper.width, paper.height)
        val margin = 42f; val size = 10f; val lh = size * 1.45f
        val fonts = Fonts(latin, arabic)
        var added = 0
        var cs: PDPageContentStream? = null
        var y = 0f
        fun newPage() {
            cs?.close()
            val p = PDPage(PDRectangle(w, h)); dst.addPage(p); added++
            cs = PDPageContentStream(dst, p)
            y = h - margin
        }
        fun line(text: String, sz: Float = size, indent: Float = 0f, bold: Boolean = false) {
            if (cs == null || y < margin + lh) newPage()
            val rtl = text.firstOrNull { it.isLetter() }?.let { ArabicShaper.isArabic(it.toString()) } ?: false
            val visual = if (ArabicShaper.isArabic(text)) Bidi.shapeVisual(text, rtl) else text
            val runs = fonts.runs(visual)
            val total = runs.sumOf { (f, s) -> (fonts.width(f, s) * sz).toDouble() }.toFloat()
            var x = if (rtl) w - margin - indent - total else margin + indent
            runs.forEach { (f, s) ->
                cs!!.beginText(); cs!!.setFont(f, sz); cs!!.newLineAtOffset(x, y)
                if (bold) cs!!.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.FILL_STROKE)
                cs!!.showText(s); cs!!.endText()
                if (bold) cs!!.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.FILL)
                x += fonts.width(f, s) * sz
            }
            y -= sz * 1.45f
        }
        newPage()
        cs!!.setLineWidth(0.2f)
        line(labels.title, 16f, bold = true); y -= 6f
        if (comments.isEmpty()) line(labels.none)
        var lastPage = -1
        comments.forEach { c ->
            if (c.page != lastPage) { y -= 4f; line(String.format(java.util.Locale.US, labels.page, c.page + 1), 12f, bold = true); lastPage = c.page }
            line(listOf(c.type, c.author, c.date).filter { it.isNotBlank() }.joinToString("  ·  "), size, 8f, bold = true)
            wrap(c.text, w - 2 * margin - 16f, size, fonts).forEach { line(it, size, 16f) }
            y -= 3f
        }
        cs?.close()
        return added
    }

    /** Logical-order line wrapping of [text] by measured width. */
    internal fun wrap(text: String, maxW: Float, size: Float, fonts: Fonts): List<String> {
        val out = ArrayList<String>()
        text.replace("\r", "").split('\n').forEach { para ->
            if (para.isBlank()) return@forEach
            var cur = ""
            para.split(' ').forEach { word ->
                val cand = if (cur.isEmpty()) word else "$cur $word"
                if (cur.isNotEmpty() && fonts.measure(cand) * size > maxW) { out += cur; cur = word } else cur = cand
            }
            if (cur.isNotEmpty()) out += cur
        }
        return out
    }

    /** Latin + Arabic font pair: picks a font per character, drops characters neither can draw. */
    class Fonts(private val latin: PDFont, private val arabic: PDFont?) {
        private val cache = HashMap<Pair<PDFont, Char>, Boolean>()
        private fun can(f: PDFont, c: Char) = cache.getOrPut(f to c) { runCatching { f.encode(c.toString()); true }.getOrDefault(false) }
        private fun fontFor(c: Char): PDFont? = when {
            arabic != null && c in '؀'..'ۿ' || arabic != null && c in 'ﭐ'..'﻿' -> arabic
            can(latin, c) -> latin
            arabic != null && can(arabic, c) -> arabic
            else -> null
        }
        fun runs(s: String): List<Pair<PDFont, String>> {
            val out = ArrayList<Pair<PDFont, String>>()
            var f: PDFont? = null; val sb = StringBuilder()
            for (c in s) {
                val cf = (if (c == ' ' && f != null && can(f, ' ')) f else fontFor(c)) ?: continue
                if (cf !== f) { if (f != null && sb.isNotEmpty()) out += f to sb.toString(); sb.clear(); f = cf }
                sb.append(c)
            }
            if (f != null && sb.isNotEmpty()) out += f to sb.toString()
            return out
        }
        fun width(f: PDFont, s: String): Float = runCatching { f.getStringWidth(s) / 1000f }.getOrDefault(0f)
        fun measure(s: String): Float = runs(s).sumOf { (f, t) -> width(f, t).toDouble() }.toFloat()
    }
}
