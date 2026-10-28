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
        /** Marks written back as /Redact annotations after applying (Foxit "apply the selected mark" keeps the others pending). */
        val keepPending: List<RedactMark> = emptyList(),
        val author: String = "",
    )

    data class Report(
        val marks: Int, val pages: Int, val glyphs: Int, val objects: Int, val images: Int, val annots: Int,
        val wholePages: Int, val rasterPages: List<Int>, val sanitized: Map<SanitizeItem, Int>,
        /** audit 5 R-8: hidden content the sanitising step could not remove (reported, never silent). */
        val sanitizeSkipped: List<SanitizeSkip> = emptyList(),
        /** audit 5 R-4: signature values removed from the output (a rewritten file is no longer validly signed). */
        val signaturesRemoved: Int = 0,
    )

    suspend fun apply(context: Context, src: File, out: File, password: String?, marks: List<RedactMark>, options: Options = Options()): Report = withContext(Dispatchers.IO) {
        require(out.canonicalPath != src.canonicalPath) { "out must differ from src" }
        val tmp = File(out.absoluteFile.parentFile, ".${out.nameWithoutExtension}.${System.nanoTime()}.redact.pdf")
        var glyphs = 0; var objects = 0; var images = 0; var annots = 0; var whole = 0
        val raster = sortedSetOf<Int>()
        var sanitized: Map<SanitizeItem, Int> = emptyMap()
        val skipped = ArrayList<SanitizeSkip>()
        var sigs = 0
        try {
            PdfOps.load(src, password).use { doc ->
                val byPage = marks.filter { it.page in 0 until doc.numberOfPages }.groupBy { it.page }
                var font: PDFont? = null
                val fontFor: () -> PDFont = { font ?: PdfOps.arabicFont(context, doc).also { font = it } }
                val chosen = com.smartdocs.editor.core.edit5.E5Fonts(context, doc)
                for ((pi, list) in byPage) {
                    val page = doc.getPage(pi)
                    if (list.any { it.wholePage }) {
                        PageScrub.wipePage(doc, page); whole++   // audit 5 R-3: also form values and structure text
                    } else {
                        val boxes = list.flatMap { it.boxes() }
                        val fill = list.groupingBy { it.style.fill }.eachCount().maxByOrNull { it.value }?.key ?: 0
                        val r = TrueRedactor.redactPage(doc, pi, boxes, fill)
                        glyphs += r.glyphsRemoved; objects += r.objectsRemoved; images += r.imagesBlanked; annots += r.annotsRemoved
                        if (r.needsRaster) raster += pi
                        RedactCleanup.stripStructure(doc, page, r.touchedMcids)
                    }
                    if (options.maxSecurity) raster += pi
                }
                RedactMarks.removeAll(doc)
                for ((pi, list) in byPage) {
                    val page = doc.getPage(pi)
                    val frame = PageFrame.of(page)
                    list.forEach { m -> runCatching { drawMark(doc, page, frame, m, fontFor, chosen) } }
                }
                if (options.sanitize) sanitized = Sanitizer.clean(doc, options.sanitizeItems, skipped)
                // after sanitising, which would drop the review replies of the marks kept pending. audit 5 R-6: when
                // pages are rasterised, the pending marks are written after that (else they are burned into the image)
                pendingBeforeRaster(options.keepPending, raster).takeIf { it.isNotEmpty() }?.let { RedactMarks.writeAll(doc, it, options.author) }
                sigs = PageScrub.stripSignatures(doc)   // audit 5 R-4
                // audit AN-07: removed / blanked images and forms must not stay in /Resources (and so in the file)
                RedactCleanup.pruneUnusedXObjects(doc)
                doc.save(tmp)
            }
            if (raster.isNotEmpty()) rasterize(context, tmp, out, password, raster.toList(), options.rasterDpi, options.keepPending, options.author)
            else RedactIo.moveInto(tmp, out)   // audit 5b D10: never a half-copied output
        } finally { if (tmp.exists()) tmp.delete() }
        Report(marks.size, marks.map { it.page }.distinct().size, glyphs, objects, images, annots, whole, raster.toList(), sanitized, skipped, sigs)
    }

    /**
     * audit 5 R-6 / 5b D6: [pending] marks written after the raster step. A mark on a replaced page is moved into the
     * new page's space ([moved]: page -> user-to-display matrix of the old page; the new page is unrotated display
     * space), so it lands on the replacement page as a pending /Redact annotation and is never burned into the image.
     */
    internal fun writePending(doc: PDDocument, pending: List<RedactMark>, moved: Map<Int, FloatArray>, author: String) {
        if (pending.isEmpty()) return
        val marks = pending.map { m ->
            val t = moved[m.page] ?: return@map m
            m.copy(rects = m.rects.map { r -> RedactMath.map(t, Box.of(r.left, r.bottom, r.right, r.top)).let { b -> android.graphics.RectF(b.l, b.t, b.r, b.b) } })
        }
        RedactMarks.writeAll(doc, marks, author)
    }

    /** audit 5b D6: pending marks go into the first save only when no page is rasterised (else after [rasterize]). */
    internal fun pendingBeforeRaster(pending: List<RedactMark>, raster: Collection<Int>): List<RedactMark> = if (raster.isEmpty()) pending else emptyList()

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    private fun drawMark(doc: PDDocument, page: PDPage, frame: PageFrame, m: RedactMark, arabic: () -> PDFont, chosen: com.smartdocs.editor.core.edit5.E5Fonts) {
        val boxes = if (m.wholePage || m.rects.isEmpty()) page.cropBox.let { listOf(Box(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY)) } else m.boxes()
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            val f = rgb(m.style.fill)
            cs.setNonStrokingColor(f[0], f[1], f[2])
            boxes.forEach { b -> cs.addRect(b.l, b.b, b.w, b.h) }
            cs.fill()
            val text = m.style.shownOverlay()
            if (text.isEmpty()) return@use
            val (font, shown) = fontAndText(text, arabic, chosenFont(m.style.font), chosen)
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

    private fun chosenFont(name: String) = com.smartdocs.editor.core.edit5.E5Font.values().firstOrNull { it.name == name && it.available() }

    /**
     * The chosen font ([pick]: its Arabic face for Arabic text, its Latin face otherwise) or, automatic,
     * Helvetica-Bold for text it can encode, otherwise the bundled Arabic font with shaped text.
     */
    private fun fontAndText(text: String, arabic: () -> PDFont, pick: com.smartdocs.editor.core.edit5.E5Font?, fonts: com.smartdocs.editor.core.edit5.E5Fonts): Pair<PDFont, String> {
        if (pick != null) {
            val isAr = ArabicNormalizer.hasArabic(text)
            val f = if (isAr) fonts.arabic(pick, true) else fonts.latin(pick, true)
            val shaped = if (isAr) ArabicShaper.shape(text) else text
            val safe = shaped.filter { ch -> runCatching { f.encode(ch.toString()); true }.getOrDefault(false) }
            if (safe.isNotBlank()) return f to safe
        }
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

    /**
     * Replaces [pages] of [src] by images rendered with PDFium (boxes already drawn), then saves to [out].
     * audit 5 R-1: the old pages become unreachable ([PageScrub.replacePage]). audit 5 R-6: [pending] marks are
     * written afterwards (moved into the new page's space on rasterised pages), so they stay pending marks.
     */
    internal suspend fun rasterize(context: Context, src: File, out: File, password: String?, pages: List<Int>, dpi: Int,
                                   pending: List<RedactMark> = emptyList(), author: String = "") {
        val scale = dpi / 72f
        // one page at a time: render → JPEG-encode → recycle. Keeping every page bitmap in memory (up to 6000² px each)
        // ran out of memory on multi-page documents (OutOfMemoryError is not caught by the tool runner → app crash).
        val session = PdfSession.open(context, src, password)
        try {
            PdfOps.load(src, password).use { doc ->
                val moved = HashMap<Int, FloatArray>()   // page → user-to-display matrix of the replaced page
                for (i in pages) {
                    if (i !in 0 until session.pageCount || i >= doc.numberOfPages) continue
                    val (w, h) = session.pageSize(i)
                    if (w <= 0f || h <= 0f) continue
                    // cap at ~24 MP so a large page at high dpi still fits in memory
                    val s = minOf(scale, kotlin.math.sqrt(24_000_000f / (w * h)))
                    val bmp = session.render(i, (w * s).toInt().coerceIn(1, 6000), (h * s).toInt().coerceIn(1, 6000), annotations = true)
                    session.invalidateCache()
                    try {
                        val old = doc.getPage(i)
                        val toDisplay = Affine.values(PageFrame.of(old).toDisplay)
                        val np = PDPage(PDRectangle(w, h))
                        val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                        PDPageContentStream(doc, np).use { it.drawImage(img, 0f, 0f, w, h) }
                        PageScrub.replacePage(doc, old, np)
                        moved[i] = toDisplay
                    } finally { bmp.recycle() }
                }
                RedactCleanup.pruneUnusedXObjects(doc)   // audit AN-07: inherited resources of the replaced pages
                writePending(doc, pending, moved, author)
                RedactIo.saveAtomic(doc, out)   // audit 5 R-10
            }
        } finally { session.closeAsync() }
    }
}
