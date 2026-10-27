package com.smartdocs.editor.core.ai6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/**
 * Draws [SlideDeck] slides with Android text layout (Arabic shaping and bidi for free): to bitmaps for the
 * narrated video and to a PDF through [PdfDocument]. Same look as [SlidesPptx]: dark-blue title band, bullets.
 */
object SlideRender {
    private const val ACCENT = 0xFF1F4E79.toInt()

    private fun layout(text: String, paint: TextPaint, width: Int, rtl: Boolean, center: Boolean = false): StaticLayout {
        val align = when {
            center -> Layout.Alignment.ALIGN_CENTER
            else -> Layout.Alignment.ALIGN_NORMAL   // NORMAL follows the paragraph direction (right for Arabic)
        }
        // rtl: StaticLayout resolves the paragraph direction from the text itself (first strong character)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10)).setAlignment(align).setLineSpacing(0f, 1.1f).build()
    }

    /** Draw slide [s] on [c] of size [w]×[h]. [cover] draws the title slide. */
    fun draw(c: Canvas, w: Int, h: Int, s: SlideSpec, rtl: Boolean, cover: Boolean, index: Int = 0, total: Int = 0) {
        c.drawColor(Color.WHITE)
        val unit = h / 720f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        if (cover) {
            fill.color = ACCENT; c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 60f * unit; typeface = Typeface.DEFAULT_BOLD }
            val title = layout(s.title, tp, (w - 160 * unit).toInt(), rtl, center = true)
            val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDDEBF7.toInt(); textSize = 28f * unit }
            val subL = layout(s.bullets.take(2).joinToString("\n"), sub, (w - 160 * unit).toInt(), rtl, center = true)
            val total2 = title.height + 24 * unit + subL.height
            c.save(); c.translate(80 * unit, (h - total2) / 2f); title.draw(c); c.translate(0f, title.height + 24 * unit); subL.draw(c); c.restore()
            return
        }
        val band = 130f * unit
        fill.color = ACCENT; c.drawRect(0f, 0f, w.toFloat(), band, fill)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f * unit; typeface = Typeface.DEFAULT_BOLD }
        var title = layout(s.title, tp, (w - 100 * unit).toInt(), rtl)
        while (title.height > band - 16 * unit && tp.textSize > 18f * unit) { tp.textSize -= 2f * unit; title = layout(s.title, tp, (w - 100 * unit).toInt(), rtl) }
        c.save(); c.translate(50 * unit, (band - title.height) / 2f); title.draw(c); c.restore()
        val bp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt(); textSize = 30f * unit }
        val avail = h - band - 80 * unit
        fun bodyLayout(): StaticLayout = layout(s.bullets.joinToString("\n") { "• $it" }, bp, (w - 120 * unit).toInt(), rtl)
        var body = bodyLayout()
        while (body.height > avail && bp.textSize > 14f * unit) { bp.textSize -= 2f * unit; body = bodyLayout() }
        c.save(); c.translate(60 * unit, band + 40 * unit); body.draw(c); c.restore()
        if (total > 0) {
            val np = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 18f * unit }
            val label = "$index / $total"
            c.drawText(label, if (rtl) 24 * unit else w - 24 * unit - np.measureText(label), h - 20 * unit, np)
        }
    }

    /** Every slide of the deck (cover first) as a [w]×[h] bitmap; the caller recycles them. */
    fun bitmap(deck: SlideDeck, i: Int, w: Int = 1280, h: Int = 720): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val all = listOf(SlideSpec(deck.title, emptyList())) + deck.slides
        draw(Canvas(bmp), w, h, all[i], deck.rtl, i == 0, i, all.size - 1)
        return bmp
    }

    fun count(deck: SlideDeck) = deck.slides.size + 1

    /** PDF handout: one 16:9 page per slide; with [notes] a second half-page of speaker notes follows each slide. */
    fun pdf(deck: SlideDeck, out: File, notes: Boolean = true) {
        val doc = PdfDocument()
        try {
            val w = 960; val h = 540
            val all = listOf(SlideSpec(deck.title, emptyList())) + deck.slides
            var n = 1
            all.forEachIndexed { i, s ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n++).create())
                draw(page.canvas, w, h, s, deck.rtl, i == 0, i, all.size - 1)
                doc.finishPage(page)
                if (notes && i > 0 && s.notes.isNotBlank()) {
                    val np = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n++).create())
                    val c = np.canvas
                    c.drawColor(Color.WHITE)
                    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textSize = 18f }
                    val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
                    val hl = layout(s.title, head, w - 96, deck.rtl)
                    val bl = layout(s.notes, tp, w - 96, deck.rtl)
                    c.save(); c.translate(48f, 48f); hl.draw(c); c.translate(0f, hl.height + 16f); bl.draw(c); c.restore()
                    doc.finishPage(np)
                }
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
    }
}
