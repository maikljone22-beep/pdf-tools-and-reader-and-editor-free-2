package com.smartdocs.editor.core.redact

import android.content.Context
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.PageFrame
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.search.ArabicNormalizer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Applies redaction marks: true content removal ([TrueRedactor]), the fill box with optional overlay text,
 * whole-page wipes, removal of every `/Redact` annotation, optional sanitising, and a per-page raster fallback
 * only where the content could not be rewritten (or for every marked page with [Options.maxSecurity]).
 * The result is always a full save (no earlier revisions survive).
 */
object RedactApplier {

    data class Options(
        val sanitize: Boolean = false,
        val sanitizeItems: Set<SanitizeItem> = SanitizeItem.DEFAULTS,
        val maxSecurity: Boolean = false,
        val rasterDpi: Int = 150,
    )

    data class Report(
        val marks: Int, val pages: Int, val glyphs: Int, val objects: Int, val images: Int, val annots: Int,
        val wholePages: Int, val rasterPages: List<Int>, val sanitized: Map<SanitizeItem, Int>,
    )

    suspend fun apply(context: Context, src: File, out: File, password: String?, marks: List<RedactMark>, options: Options = Options()): Report = withContext(Dispatchers.IO) {
        require(out.canonicalPath != src.canonicalPath) { "out must differ from src" }
        val tmp = File(out.absoluteFile.parentFile, ".${out.nameWithoutExtension}.${System.nanoTime()}.redact.pdf")
        var glyphs = 0; var objects = 0; var images = 0; var annots = 0; var whole = 0
        val raster = sortedSetOf<Int>()
        var sanitized: Map<SanitizeItem, Int> = emptyMap()
        try {
            PdfOps.load(src, password).use { doc ->
                val byPage = marks.filter { it.page in 0 until doc.numberOfPages }.groupBy { it.page }
                var font: PDFont? = null
                val fontFor: () -> PDFont = { font ?: PdfOps.arabicFont(context, doc).also { font = it } }
                for ((pi, list) in byPage) {
                    val page = doc.getPage(pi)
                    if (list.any { it.wholePage }) {
                        wipe(doc, page); whole++
                    } else {
                        val boxes = list.flatMap { it.boxes() }
                        val fill = list.groupingBy { it.style.fill }.eachCount().maxByOrNull { it.value }?.key ?: 0
                        val r = TrueRedactor.redactPage(doc, pi, boxes, fill)
                        glyphs += r.glyphsRemoved; objects += r.objectsRemoved; images += r.imagesBlanked; annots += r.annotsRemoved
                        if (r.needsRaster) raster += pi
                    }
                    if (options.maxSecurity) raster += pi
                }
                RedactMarks.removeAll(doc)
                for ((pi, list) in byPage) {
                    val page = doc.getPage(pi)
                    val frame = PageFrame.of(page)
                    list.forEach { m -> runCatching { drawMark(doc, page, frame, m, fontFor) } }
                }
                if (options.sanitize) sanitized = Sanitizer.clean(doc, options.sanitizeItems)
                doc.save(tmp)
            }
            if (raster.isNotEmpty()) rasterize(context, tmp, out, password, raster.toList(), options.rasterDpi)
            else if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true) }
        } finally { if (tmp.exists()) tmp.delete() }
        Report(marks.size, marks.map { it.page }.distinct().size, glyphs, objects, images, annots, whole, raster.toList(), sanitized)
    }

    /** Empties a page: no content, no resources, no annotations. */
    private fun wipe(doc: PDDocument, page: PDPage) {
        page.setContents(PDStream(doc))
        page.resources = PDResources()
        page.annotations = emptyList()
        page.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("Thumb"))
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    private fun drawMark(doc: PDDocument, page: PDPage, frame: PageFrame, m: RedactMark, arabic: () -> PDFont) {
        val boxes = if (m.wholePage || m.rects.isEmpty()) page.cropBox.let { listOf(Box(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY)) } else m.boxes()
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            val f = rgb(m.style.fill)
            cs.setNonStrokingColor(f[0], f[1], f[2])
            boxes.forEach { b -> cs.addRect(b.l, b.b, b.w, b.h) }
            cs.fill()
            val text = m.style.overlay.trim()
            if (text.isEmpty()) return@use
            val (font, shown) = fontAndText(text, arabic)
            val w1 = runCatching { font.getStringWidth(shown) / 1000f }.getOrDefault(0f)
            if (w1 <= 0f) return@use
            val toDisplay = Affine.values(frame.toDisplay)
            val c = rgb(m.style.textColor)
            cs.saveGraphicsState()
            cs.transform(frame.toUser)   // draw upright in display space, also on rotated pages
            for (b in boxes) {
                val d = RedactMath.map(toDisplay, b)
                val size = if (m.style.fontSize > 0f) m.style.fontSize else RedactMath.autoFontSize(w1, d.w, d.h)
                val unit = w1 * size
                cs.saveGraphicsState()
                cs.addRect(d.l, d.b, d.w, d.h); cs.clip()
                cs.setNonStrokingColor(c[0], c[1], c[2])
                val y = d.cy - size * 0.33f
                val xs: List<Float> = if (m.style.repeat) {
                    val gap = size * 0.6f
                    val n = RedactMath.repeatCount(unit, d.w, gap)
                    val total = n * unit + (n - 1) * gap
                    val start = d.l + (d.w - total) / 2f
                    (0 until n).map { start + it * (unit + gap) }
                } else listOf(when (m.style.align) { 0 -> d.l + 2f; 2 -> d.r - 2f - unit; else -> d.cx - unit / 2f })
                for (x in xs) {
                    cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(x, y); cs.showText(shown); cs.endText()
                }
                cs.restoreGraphicsState()
            }
            cs.restoreGraphicsState()
        }
    }

    /** Helvetica-Bold for text it can encode, otherwise the bundled Arabic font with shaped text. */
    private fun fontAndText(text: String, arabic: () -> PDFont): Pair<PDFont, String> {
        if (!ArabicNormalizer.hasArabic(text)) {
            val ok = runCatching { PDType1Font.HELVETICA_BOLD.encode(text); true }.getOrDefault(false)
            if (ok) return PDType1Font.HELVETICA_BOLD to text
        }
        val f = arabic()
        val shaped = if (ArabicNormalizer.hasArabic(text)) ArabicShaper.shape(text) else text
        // drop characters the font cannot encode instead of failing the whole overlay
        val safe = shaped.filter { ch -> runCatching { f.encode(ch.toString()); true }.getOrDefault(false) }
        return f to safe
    }

    /** Replaces [pages] of [src] by images rendered with PDFium (boxes already drawn), then saves to [out]. */
    private suspend fun rasterize(context: Context, src: File, out: File, password: String?, pages: List<Int>, dpi: Int) {
        val scale = dpi / 72f
        val images = HashMap<Int, Pair<android.graphics.Bitmap, Pair<Float, Float>>>()
        val session = PdfSession.open(context, src, password)
        try {
            for (i in pages) {
                if (i !in 0 until session.pageCount) continue
                val (w, h) = session.pageSize(i)
                val bmp = session.render(i, (w * scale).toInt().coerceIn(1, 6000), (h * scale).toInt().coerceIn(1, 6000), annotations = true)
                session.invalidateCache()
                images[i] = bmp to (w to h)
            }
        } finally { session.closeAsync() }
        PdfOps.load(src, password).use { doc ->
            for ((i, pair) in images) {
                val (bmp, size) = pair
                val old = doc.getPage(i)
                val np = PDPage(PDRectangle(size.first, size.second))
                val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                PDPageContentStream(doc, np).use { it.drawImage(img, 0f, 0f, size.first, size.second) }
                doc.pages.insertAfter(np, old); doc.removePage(old)
                bmp.recycle()
            }
            doc.save(out)
        }
    }
}
