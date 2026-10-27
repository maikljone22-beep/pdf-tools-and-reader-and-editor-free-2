package com.smartdocs.editor.core.view5

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.smartdocs.editor.core.annot.CommentSummary
import com.smartdocs.editor.core.annot.SummaryLabels
import com.smartdocs.editor.core.convert6.PdfTransforms6
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Print choices beyond the page layout: true greyscale, print as image (dpi), comment summary at the end. */
data class PrintExtras(val grayscale: Boolean = false, val asImage: Boolean = false, val dpi: Int = 150, val summary: Boolean = false)

/**
 * The whole print preparation of one file (print sheet, batch print, Organize / outline print): optional comment
 * summary → [PrintPrep] layout → greyscale (vector colours rewritten) → raster ("print as image"). Returns the file to
 * hand to the print framework and its page count. The source is never changed.
 */
object PrintPipeline {

    suspend fun prepare(ctx: Context, src: File, pw: String?, o: PrintOptions, x: PrintExtras, tempDir: File, labels: SummaryLabels? = null): Pair<File, Int> {
        tempDir.mkdirs()
        fun tmp(tag: String) = File(tempDir, "nbt_print_${tag}_${System.nanoTime()}.pdf")
        val summary = if (x.summary && labels != null) tmp("summary").takeIf { out ->
            runCatching { commentSummary(ctx, src, pw, out, labels) > 0 }.getOrDefault(false)
        } else null
        val opts = if (summary != null) o.copy(appendix = summary) else o
        var cur = src
        var count = withContext(Dispatchers.IO) { runCatching { PdfOps.pageCount(src, pw) }.getOrDefault(0) }
        if (!PrintPrep.isPlain(opts, count)) {
            val out = tmp("layout")
            count = PrintPrep.build(src, pw, opts, out) { text, w -> noteBitmap(ctx, text, w) }
            cur = out
        }
        summary?.delete()
        val curPw = if (cur === src) pw else null
        if (x.grayscale && !x.asImage) {
            val out = tmp("gray")
            PdfTransforms6.grayscale(cur, curPw, out, tempDir) { _, _ -> }
            if (cur !== src) cur.delete()
            cur = out
        }
        if (x.asImage) {
            val out = tmp("image")
            count = rasterize(ctx, cur, if (cur === src) pw else null, out, x.dpi, x.grayscale)
            if (cur !== src) cur.delete()
            cur = out
        }
        return cur to count
    }

    /** Several prepared files as one print job (Foxit batch print "merge"). */
    suspend fun merge(files: List<File>, out: File): Int = withContext(Dispatchers.IO) {
        val m = PDFMergerUtility()
        files.forEach { m.addSource(it) }
        m.destinationFileName = out.absolutePath
        m.mergeDocuments(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(48L shl 20))
        PDDocument.load(out).use { it.numberOfPages }
    }

    /** Comment summary PDF of [src] (thumbnails with numbered markers and the list). Returns the number of comments. */
    suspend fun commentSummary(ctx: Context, src: File, pw: String?, out: File, labels: SummaryLabels): Int {
        val session = runCatching { PdfSession.open(ctx, src, pw) }.getOrNull()
        try {
            return CommentSummary.build(ctx, src, pw, out, labels,
                pageSize = { p -> session?.pageSize(p) ?: (595f to 842f) },
                thumb = { p, w, h -> runCatching { session?.render(p, w, h, false, true) }.getOrNull() })
        } finally { session?.closeAsync() }
    }

    /** Every page rendered at [dpi] (optionally grey) into a new image-only PDF of the same page sizes. */
    suspend fun rasterize(ctx: Context, src: File, pw: String?, out: File, dpi: Int, gray: Boolean): Int {
        val session = PdfSession.open(ctx, src, pw)
        try {
            return withContext(Dispatchers.IO) {
                PDDocument().use { dst ->
                    for (i in 0 until session.pageCount) {
                        val (w, h) = session.pageSize(i)
                        val k = dpi.coerceIn(72, 300) / 72f
                        val bw = (w * k).toInt().coerceIn(1, 4000); val bh = (h * k).toInt().coerceIn(1, 4000)
                        val page = session.render(i, bw, bh, false, true)
                        // white backdrop (transparent areas print white) and the optional grey filter
                        val flat = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        Canvas(flat).apply {
                            drawColor(Color.WHITE)
                            drawBitmap(page, null, android.graphics.Rect(0, 0, bw, bh), Paint(Paint.FILTER_BITMAP_FLAG).apply {
                                if (gray) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                            })
                        }
                        val p = PDPage(PDRectangle(w, h)); dst.addPage(p)
                        val img = JPEGFactory.createFromImage(dst, flat, 0.85f)
                        PDPageContentStream(dst, p).use { it.drawImage(img, 0f, 0f, w, h) }
                        flat.recycle()
                    }
                    dst.save(out)
                    dst.numberOfPages
                }
            }
        } finally { session.closeAsync() }
    }

    /** A sticky note as it pops up: wrapped text (any script, bidi) on a pale yellow card, [widthPt] wide. */
    fun noteBitmap(ctx: Context, text: String, widthPt: Float): Bitmap? = runCatching {
        val scale = 2f
        val w = (widthPt * scale).toInt().coerceAtLeast(40)
        val pad = (6 * scale).toInt()
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 33, 33); textSize = 8.5f * scale }
        val layout = StaticLayout.Builder.obtain(text.trim().take(2000), 0, text.trim().take(2000).length, tp, w - 2 * pad)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val h = (layout.height + 2 * pad).coerceAtMost(4000)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawColor(Color.rgb(255, 249, 196))
            c.drawRect(0f, 0f, w - 1f, h - 1f, Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(200, 170, 60); strokeWidth = scale })
            c.save(); c.translate(pad.toFloat(), pad.toFloat()); layout.draw(c); c.restore()
        }
    }.getOrNull()
}
