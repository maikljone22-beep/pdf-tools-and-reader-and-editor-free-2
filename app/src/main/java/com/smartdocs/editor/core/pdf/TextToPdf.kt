package com.smartdocs.editor.core.pdf

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders plain text into a paginated PDF with PdfBox. Arabic paragraphs are shaped with
 * [ArabicShaper] and right-aligned; Latin paragraphs are left-aligned. Long lines wrap on words.
 */
object TextToPdf {

    suspend fun convert(context: Context, text: String, out: File, fontSize: Float = 12f, pageSize: PDRectangle = PDRectangle.A4, title: String? = null) = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            val font = PdfOps.arabicFont(context, doc)
            val margin = 56f
            val lineHeight = fontSize * 1.7f
            val maxWidth = pageSize.width - 2 * margin
            val writer = PageWriter(doc, pageSize, margin, lineHeight)
            fun width(s: String): Float = runCatching { font.getStringWidth(s) / 1000f * fontSize }.getOrDefault(s.length * fontSize * 0.5f)

            text.replace("\r\n", "\n").replace("\r", "\n").replace("\t", "    ").split('\n').forEach { raw ->
                val paragraph = raw.trimEnd()
                if (paragraph.isBlank()) { writer.blankLine(); return@forEach }
                val rtl = ArabicShaper.isArabic(paragraph)
                wrap(paragraph, maxWidth) { width(ArabicShaper.shape(it)) }.forEach { line ->
                    val shaped = ArabicShaper.shape(line)
                    val w = width(shaped)
                    val x = if (rtl) pageSize.width - margin - w else margin
                    writer.line(font, fontSize, x) { cs ->
                        runCatching { cs.showArabic(line, shaped) }.onFailure { cs.showText(sanitize(line)) }
                    }
                }
            }
            writer.finish()
            if (doc.numberOfPages == 0) doc.addPage(PDPage(pageSize))
            if (!title.isNullOrBlank()) doc.documentInformation.title = title
            doc.save(out)
        }
    }

    /** Greedy word wrap; words longer than the line are broken by character. */
    fun wrap(paragraph: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val lines = ArrayList<String>()
        var current = StringBuilder()
        paragraph.split(' ').filter { it.isNotEmpty() }.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate) <= maxWidth) { current = StringBuilder(candidate); return@forEach }
            if (current.isNotEmpty()) { lines += current.toString(); current = StringBuilder() }
            if (measure(word) <= maxWidth) { current.append(word); return@forEach }
            var piece = StringBuilder()
            word.forEach { ch ->
                if (measure(piece.toString() + ch) > maxWidth && piece.isNotEmpty()) { lines += piece.toString(); piece = StringBuilder() }
                piece.append(ch)
            }
            current = piece
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /** Replace glyphs the fallback font cannot encode. */
    private fun sanitize(s: String): String = s.map { if (it.code < 256) it else '?' }.joinToString("")

    private class PageWriter(private val doc: PDDocument, private val size: PDRectangle, private val margin: Float, private val lineHeight: Float) {
        private var cs: PDPageContentStream? = null
        private var y = 0f

        private fun ensurePage() {
            if (cs == null || y < margin) {
                cs?.close()
                val page = PDPage(size); doc.addPage(page)
                cs = PDPageContentStream(doc, page); y = size.height - margin - lineHeight
            }
        }

        fun blankLine() { y -= lineHeight }

        fun line(font: PDFont, fontSize: Float, x: Float, draw: (PDPageContentStream) -> Unit) {
            ensurePage()
            val c = cs!!
            c.beginText(); c.setFont(font, fontSize); c.newLineAtOffset(x, y)
            draw(c)
            c.endText()
            y -= lineHeight
        }

        fun finish() { cs?.close(); cs = null }
    }
}
