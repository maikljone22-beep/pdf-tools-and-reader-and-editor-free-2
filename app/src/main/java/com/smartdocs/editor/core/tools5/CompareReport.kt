package com.smartdocs.editor.core.tools5

import android.content.Context
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.showArabic
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Localised labels of the compare report (filled by the UI from string resources). */
data class CompareLabels(
    val title: String = "Comparison report", val old: String = "Old", val new: String = "New",
    val inserted: String = "Inserted", val deleted: String = "Deleted", val replaced: String = "Replaced",
    val pageN: String = "Page %1\$d", val changes: String = "Changes", val summary: String = "Summary",
)

/** PDF report: a summary page (files, counts, AI summary, change list) and side-by-side pages with the changes marked. */
object CompareReport {

    private class Writer(val doc: PDDocument, val arabic: PDFont) {
        var page: PDPage? = null; var cs: PDPageContentStream? = null; var y = 0f
        val margin = 40f
        fun newPage() { cs?.close(); val p = PDPage(PDRectangle.A4); doc.addPage(p); page = p; cs = PDPageContentStream(doc, p); y = p.mediaBox.height - margin }
        fun font(s: String): PDFont = if (ArabicShaper.isArabic(s) && arabic !is PDType1Font) arabic else PDType1Font.HELVETICA
        fun line(text0: String, size: Float, color: Int = 0x000000) {
            if (cs == null || y < margin + size) newPage()
            val text = text0.replace('\n', ' ').replace('\t', ' ')
            val f = font(text)
            val width = page!!.mediaBox.width - 2 * margin
            val ar = ArabicShaper.isArabic(text)
            var shown = if (ar) ArabicShaper.shape(text) else text
            if (f is PDType1Font) shown = shown.map { if (it.code in 32..126) it else '?' }.joinToString("")
            fun w(s: String) = runCatching { f.getStringWidth(s) / 1000f * size }.getOrDefault(s.length * size * 0.5f)
            // shaped Arabic is in visual order: cut from the start (the left = the end of the sentence) when too long
            while (shown.length > 4 && w(shown) > width) shown = if (ar) shown.drop(1) else shown.dropLast(1)
            val c = cs!!
            c.beginText(); c.setFont(f, size)
            c.setNonStrokingColor(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f)
            c.newLineAtOffset(if (ar) page!!.mediaBox.width - margin - w(shown) else margin, y)
            runCatching { c.showArabic(text, shown) }
            c.endText()
            y -= size * 1.5f
        }
        fun close() { cs?.close(); cs = null }
    }

    suspend fun write(
        ctx: Context, old: File, oldPw: String?, new: File, newPw: String?, r: CompareResult, out: File,
        labels: CompareLabels, aiSummary: String? = null, maxPages: Int = 40,
    ): File = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            val arabic = runCatching { PdfOps.arabicFont(ctx, doc) }.getOrDefault(PDType1Font.HELVETICA)
            val w = Writer(doc, arabic)
            w.newPage()
            w.line(labels.title, 20f)
            w.line(labels.old + ": " + old.name, 11f)
            w.line(labels.new + ": " + new.name, 11f)
            w.line("${labels.inserted}: ${r.stats.inserted}   ${labels.deleted}: ${r.stats.deleted}   ${labels.replaced}: ${r.stats.replaced}", 12f)
            if (!aiSummary.isNullOrBlank()) {
                w.y -= 6f; w.line(labels.summary, 14f)
                aiSummary.lines().filter { it.isNotBlank() }.take(40).forEach { w.line(it.trim(), 10f) }
            }
            w.y -= 6f; w.line(labels.changes, 14f)
            r.describe(400).lines().filter { it.isNotBlank() }.forEach { l ->
                val color = when (l.firstOrNull()) { '+' -> com.smartdocs.editor.core.data.NpfPrefs.compareInserted; '-' -> com.smartdocs.editor.core.data.NpfPrefs.compareDeleted; else -> com.smartdocs.editor.core.data.NpfPrefs.compareChanged }   // nav-prefs
                w.line(l, 9f, color)
            }
            w.close()
            // side-by-side pages
            val so = PdfSession.open(ctx, old, oldPw)
            val sn = PdfSession.open(ctx, new, newPw)
            try {
                val pages = r.changedPages.take(maxPages)
                val alpha = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.35f }
                pages.forEach { pi ->
                    val sheet = PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)); doc.addPage(sheet)
                    val sw = sheet.mediaBox.width; val sh = sheet.mediaBox.height
                    val colW = (sw - 60f) / 2f; val colH = sh - 60f
                    PDPageContentStream(doc, sheet).use { cs ->
                        listOf(Triple(so, r.oldMarks, 20f), Triple(sn, r.newMarks, 40f + colW)).forEach { (s, marks, x0) ->
                            if (pi !in 0 until s.pageCount) return@forEach
                            val (pw, ph) = s.pageSize(pi)
                            val k = minOf(colW / pw, colH / ph)
                            val dw = pw * k; val dh = ph * k
                            val px = 1400f / maxOf(pw, ph)
                            val bmp = s.render(pi, (pw * px).toInt().coerceAtLeast(1), (ph * px).toInt().coerceAtLeast(1), annotations = true)
                            val img = JPEGFactory.createFromImage(doc, bmp, 0.8f)
                            s.invalidateCache(); bmp.recycle()
                            val top = 30f + dh
                            cs.drawImage(img, x0, sh - top, dw, dh)
                            cs.saveGraphicsState()
                            cs.setGraphicsStateParameters(alpha)
                            marks.boxes[pi].orEmpty().forEach { (b, t) ->
                                when (t) { ChangeType.INSERT -> cs.setNonStrokingColor(0.12f, 0.53f, 0.9f); ChangeType.DELETE -> cs.setNonStrokingColor(0.9f, 0.22f, 0.21f); else -> cs.setNonStrokingColor(1f, 0.6f, 0f) }
                                cs.addRect(x0 + b.left * k, sh - 30f - b.bottom * k, maxOf(2f, b.width() * k), maxOf(2f, b.height() * k))
                                cs.fill()
                            }
                            cs.restoreGraphicsState()
                        }
                        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 9f); cs.newLineAtOffset(20f, 12f)
                        cs.showText("Page ${pi + 1}"); cs.endText()
                    }
                }
            } finally { so.closeAsync(); sn.closeAsync() }
            doc.documentInformation.title = labels.title
            doc.save(out)
        }
        out
    }
}
