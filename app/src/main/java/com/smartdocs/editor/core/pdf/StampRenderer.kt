package com.smartdocs.editor.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Renders rubber-stamp style bitmaps (transparent background) with Android Canvas.
 * Arabic text shapes correctly because Android's text layout handles bidi/joining natively.
 */
object StampRenderer {

    enum class Border { ROUNDED, PLAIN, NONE }

    const val RED = 0xFFD32F2F.toInt()
    const val GREEN = 0xFF2E7D32.toInt()
    const val BLUE = 0xFF1565C0.toInt()
    const val ORANGE = 0xFFEF6C00.toInt()
    const val PURPLE = 0xFF6A1B9A.toInt()
    const val BLACK = 0xFF212121.toInt()

    val palette = listOf(RED, GREEN, BLUE, ORANGE, PURPLE, BLACK)

    /**
     * Draw a bordered stamp with a main [text] and an optional [subtitle] (smaller, second line).
     * Width is fixed to [widthPx]; height adapts to the content. Returns an ARGB bitmap with transparent background.
     */
    fun renderTextStamp(text: String, subtitle: String?, color: Int, widthPx: Int = 600, border: Border = Border.ROUNDED): Bitmap {
        val padding = widthPx * 0.06f
        val strokeW = widthPx * 0.018f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        // Fit the title to the available width.
        val maxTextW = widthPx - padding * 2 - strokeW * 2
        titlePaint.textSize = widthPx * 0.22f
        while (titlePaint.textSize > 12f && titlePaint.measureText(text) > maxTextW) titlePaint.textSize *= 0.92f
        val hasSub = !subtitle.isNullOrBlank()
        if (hasSub) {
            subPaint.textSize = titlePaint.textSize * 0.42f
            while (subPaint.textSize > 8f && subPaint.measureText(subtitle!!) > maxTextW) subPaint.textSize *= 0.92f
        }
        val titleFm = titlePaint.fontMetrics
        val titleH = titleFm.descent - titleFm.ascent
        val subH = if (hasSub) subPaint.fontMetrics.let { it.descent - it.ascent } else 0f
        val gap = if (hasSub) titleH * 0.12f else 0f
        val heightPx = (padding * 2 + strokeW * 2 + titleH + gap + subH).toInt().coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        if (border != Border.NONE) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = strokeW }
            val r = RectF(strokeW, strokeW, widthPx - strokeW, heightPx - strokeW)
            if (border == Border.ROUNDED) canvas.drawRoundRect(r, widthPx * 0.05f, widthPx * 0.05f, bp) else canvas.drawRect(r, bp)
        }
        val cx = widthPx / 2f
        var y = padding + strokeW - titleFm.ascent
        canvas.drawText(text, cx, y, titlePaint)
        if (hasSub) {
            y += titleFm.descent + gap - subPaint.fontMetrics.ascent
            canvas.drawText(subtitle!!, cx, y, subPaint)
        }
        return bmp
    }

    /** Stamp with a two-line bilingual label: Arabic on top, English beneath. */
    fun renderBilingual(arabic: String, english: String, color: Int, widthPx: Int = 600): Bitmap =
        renderTextStamp(arabic, english, color, widthPx)

    /** Dynamic stamp: label + "author · date" line. */
    fun renderDynamic(label: String, author: String, date: String, color: Int, widthPx: Int = 600): Bitmap {
        val line = listOf(author, date).filter { it.isNotBlank() }.joinToString("  •  ")
        return renderTextStamp(label, line.ifBlank { null }, color, widthPx)
    }
}
