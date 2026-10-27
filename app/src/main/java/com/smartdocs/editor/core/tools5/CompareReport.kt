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
    val kindText: String = "Text", val kindImage: String = "Image", val kindAnnot: String = "Annotation", val kindFormat: String = "Formatting",
    val kindPage: String = "Page", val kindTable: String = "Table",
    val pageInserted: String = "Page inserted", val pageDeleted: String = "Page deleted", val pageMoved: String = "Page moved",
    val pageRotated: String = "Page rotated", val pageCropped: String = "Page size / crop changed",
    val oldOnly: String = "Only in old", val newOnly: String = "Only in new",
) {
    fun pageChange(pc: PageChange): String = when (pc.type) {
        PageChangeType.INSERTED -> pageInserted; PageChangeType.DELETED -> pageDeleted; PageChangeType.MOVED -> pageMoved
        PageChangeType.ROTATED -> pageRotated; PageChangeType.CROPPED -> pageCropped
    } + " — " + listOfNotNull(pc.old?.let { "$old ${it + 1}" }, pc.new?.let { "$new ${it + 1}" }).joinToString(" / ")
}

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
        style: CompareStyle = CompareStyle(), kinds: Set<ChangeKind> = ChangeKind.values().toSet(),
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
            if (ChangeKind.PAGE in kinds && r.pageChanges.isNotEmpty()) {
                w.y -= 6f; w.line(labels.kindPage, 14f)
                r.pageChanges.forEach { pc -> w.line(labels.pageChange(pc) + if (pc.detail.isNotBlank()) " (${pc.detail})" else "", 9f, style.replace) }
            }
            val objs = r.objectChanges.filter { it.kind in kinds }
            if (objs.isNotEmpty()) {
                w.y -= 6f; w.line(labels.kindImage + " / " + labels.kindAnnot, 14f)
                objs.forEach { c ->
                    val where = listOfNotNull(c.oldPage?.let { "${labels.old} ${it + 1}" }, c.newPage?.let { "${labels.new} ${it + 1}" }).joinToString(" / ")
                    w.line("${CompareOutput.kindLabel(c.kind, labels)} — ${typeLabel(c.type, labels)}: ${c.label} ($where)", 9f, style.colorOf(c.type))
                }
            }
            if (ChangeKind.FORMAT in kinds && r.formatChanges.isNotEmpty()) {
                w.y -= 6f; w.line(labels.kindFormat, 14f)
                r.formatChanges.take(200).forEach { f ->
                    w.line("${f.text}: ${f.oldFont} ${f.oldSize} → ${f.newFont} ${f.newSize}", 9f, style.replace)
                }
            }
            if (ChangeKind.TABLE in kinds && r.tableChanges.isNotEmpty()) {
                w.y -= 6f; w.line(labels.kindTable, 14f)
                r.tableChanges.take(300).forEach { t ->
                    val cell = if (t.col < 0) "#${t.row + 1}" else "#${t.row + 1}:${t.col + 1}"
                    w.line("$cell ${typeLabel(t.type, labels)}: ${t.old} → ${t.new}", 9f, style.colorOf(t.type))
                }
            }
            if (ChangeKind.TEXT in kinds) {
                w.y -= 6f; w.line(labels.changes, 14f)
                r.describe(400).lines().filter { it.isNotBlank() }.forEach { l ->
                    val color = when (l.firstOrNull()) { '+' -> style.insert; '-' -> style.delete; else -> style.replace }
                    w.line(l, 9f, color)
                }
            }
            w.close()
            // side-by-side pages
            val so = PdfSession.open(ctx, old, oldPw)
            val sn = PdfSession.open(ctx, new, newPw)
            try {
                val pairs = (if (r.pairs.isNotEmpty()) r.changedPairs(kinds) else r.changedPages.map { PagePair(it, it) }).take(maxPages)
                val alpha = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = style.opacity.coerceIn(0.1f, 1f); strokingAlphaConstant = style.opacity.coerceIn(0.1f, 1f) }
                pairs.forEach { pair ->
                    val sheet = PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)); doc.addPage(sheet)
                    val sw = sheet.mediaBox.width; val sh = sheet.mediaBox.height
                    val colW = (sw - 60f) / 2f; val colH = sh - 60f
                    PDPageContentStream(doc, sheet).use { cs ->
                        listOf(Triple(so, r.oldMarks, 20f) to pair.old, Triple(sn, r.newMarks, 40f + colW) to pair.new).forEach { (side, pi0) ->
                            val (s, marks, x0) = side
                            val pi = pi0 ?: return@forEach
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
                            marks.boxes[pi].orEmpty().filter { it.kind in kinds }.forEach { m ->
                                val b = m.box
                                val c = style.colorOf(m.type)
                                val f = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
                                cs.addRect(x0 + b.left * k, sh - 30f - b.bottom * k, maxOf(2f, b.width() * k), maxOf(2f, b.height() * k))
                                if (style.line > 0f) { cs.setStrokingColor(f[0], f[1], f[2]); cs.setLineWidth(style.line); cs.stroke() }
                                else { cs.setNonStrokingColor(f[0], f[1], f[2]); cs.fill() }
                            }
                            cs.restoreGraphicsState()
                        }
                        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 9f); cs.newLineAtOffset(20f, 12f)
                        cs.showText("Page ${pair.old?.plus(1) ?: "-"} / ${pair.new?.plus(1) ?: "-"}"); cs.endText()
                    }
                }
            } finally { so.closeAsync(); sn.closeAsync() }
            doc.documentInformation.title = labels.title
            doc.save(out)
        }
        out
    }

    private fun typeLabel(t: ChangeType, l: CompareLabels) = when (t) { ChangeType.INSERT -> l.inserted; ChangeType.DELETE -> l.deleted; else -> l.replaced }
}
