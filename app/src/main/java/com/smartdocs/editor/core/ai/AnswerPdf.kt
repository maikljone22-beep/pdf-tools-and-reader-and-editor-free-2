package com.smartdocs.editor.core.ai

import com.smartdocs.editor.core.pdf.showArabic
import android.content.Context
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Writes plain text (Arabic or Latin, mixed lines) into a simple A4 PDF with word wrapping and RTL alignment for Arabic lines. */
object AnswerPdf {

    suspend fun write(context: Context, title: String, text: String, out: File, fontSize: Float = 12f) = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            val font = PdfOps.arabicFont(context, doc)
            val margin = 48f
            val pageW = PDRectangle.A4.width; val pageH = PDRectangle.A4.height
            val maxW = pageW - margin * 2
            val leading = fontSize * 1.65f
            var page = PDPage(PDRectangle.A4); doc.addPage(page)
            var cs = PDPageContentStream(doc, page)
            var y = pageH - margin

            fun newPage() {
                cs.close()
                page = PDPage(PDRectangle.A4); doc.addPage(page)
                cs = PDPageContentStream(doc, page)
                y = pageH - margin
            }

            fun drawLine(line: String, size: Float, bold: Boolean) {
                if (y < margin) newPage()
                val rtl = ArabicShaper.isArabic(line)
                val shaped = ArabicShaper.shape(line)
                val w = width(font, shaped, size)
                val x = if (rtl) pageW - margin - w else margin
                cs.beginText(); cs.setFont(font, size)
                cs.setNonStrokingColor(if (bold) 20 else 40, if (bold) 20 else 40, if (bold) 20 else 40)
                cs.newLineAtOffset(x, y)
                cs.showArabic(line, shaped)
                cs.endText()
                y -= size * 1.65f
            }

            if (title.isNotBlank()) {
                wrap(font, title, fontSize + 6f, maxW).forEach { drawLine(it, fontSize + 6f, true) }
                y -= leading * 0.5f
            }
            text.lines().forEach { raw ->
                val clean = raw.replace("**", "").replace(Regex("^#+\\s*"), "").trimEnd()
                if (clean.isBlank()) { y -= leading * 0.6f; return@forEach }
                wrap(font, clean, fontSize, maxW).forEach { drawLine(it, fontSize, false) }
            }
            cs.close()
            doc.save(out)
        }
    }

    private fun width(font: PDFont, shaped: String, size: Float): Float =
        runCatching { font.getStringWidth(shaped) / 1000f * size }.getOrDefault(shaped.length * size * 0.5f)

    /** Greedy word wrap measured on shaped text so Arabic widths are right. */
    private fun wrap(font: PDFont, line: String, size: Float, maxW: Float): List<String> {
        val words = line.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var cur = StringBuilder()
        words.forEach { w ->
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (width(font, ArabicShaper.shape(candidate), size) <= maxW) { cur = StringBuilder(candidate) }
            else {
                if (cur.isNotEmpty()) out += cur.toString()
                // very long single word: hard split
                var rest = w
                while (width(font, ArabicShaper.shape(rest), size) > maxW && rest.length > 1) {
                    var n = rest.length - 1
                    while (n > 1 && width(font, ArabicShaper.shape(rest.substring(0, n)), size) > maxW) n--
                    out += rest.substring(0, n); rest = rest.substring(n)
                }
                cur = StringBuilder(rest)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
