package com.smartdocs.editor.core.ai6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/**
 * aor: draws a [Craft] with Android text layout (Arabic shaping and bidi for free) as a tall PNG poster and as an
 * A4 PDF. The page is a list of blocks (header, figure tiles, section cards, takeaway, footer); the PDF breaks
 * between blocks. Same colours as [Crafts.html].
 */
object CraftRender {
    private const val ACCENT = 0xFF1F4E79.toInt()
    private const val ACCENT2 = 0xFF2E75B6.toInt()
    private const val ORANGE = 0xFFC55A11.toInt()
    private const val LIGHT = 0xFFEAF2FA.toInt()
    private const val BG = 0xFFF6F8FB.toInt()
    /** Tallest poster (px at 1080 wide); longer crafts are cut and the PDF holds the whole text. */
    const val MAX_HEIGHT = 7000

    private class Block(val h: Float, val draw: (Canvas, Float) -> Unit)

    private fun layout(text: String, paint: TextPaint, width: Int, center: Boolean = false): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10))
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()

    private fun tp(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Blocks for a page [w] wide; [u] = scale (1 at 1080 px). */
    private fun blocks(c: Craft, labels: CraftLabels, footer: String, w: Int, u: Float): List<Block> {
        val out = ArrayList<Block>()
        val pad = 48 * u
        val inner = (w - 2 * pad).toInt()
        val info = c.kind == CraftKind.INFOGRAPHIC
        // header band
        run {
            val t = layout(c.title, tp(56 * u, Color.WHITE, true), inner, center = true)
            val s = if (c.subtitle.isNotBlank()) layout(c.subtitle, tp(30 * u, 0xFFDDEBF7.toInt()), inner, center = true) else null
            val h = pad + t.height + (s?.let { 16 * u + it.height } ?: 0f) + pad
            out += Block(h) { cv, y ->
                cv.drawRect(0f, y, w.toFloat(), y + h, Paint().apply { color = ACCENT })
                cv.save(); cv.translate(pad, y + pad); t.draw(cv)
                if (s != null) { cv.translate(0f, t.height + 16 * u); s.draw(cv) }
                cv.restore()
            }
        }
        // key figures: tiles, two per row
        if (c.stats.isNotEmpty()) {
            if (!info) {
                val hl = layout(labels.keyFigures, tp(36 * u, ACCENT, true), inner)
                out += Block(hl.height + 40 * u) { cv, y -> cv.save(); cv.translate(pad, y + 28 * u); hl.draw(cv); cv.restore() }
            }
            val gap = 20 * u
            val tileW = ((inner - gap) / 2f)
            c.stats.chunked(2).forEach { row ->
                val ls = row.map { st -> layout(st.value, tp(54 * u, ACCENT, true), (tileW - 32 * u).toInt(), true) to layout(st.label, tp(26 * u, 0xFF444444.toInt()), (tileW - 32 * u).toInt(), true) }
                val th = ls.maxOf { (v, l) -> v.height + 8 * u + l.height } + 40 * u
                out += Block(th + gap) { cv, y ->
                    ls.forEachIndexed { k, (v, l) ->
                        // tiles follow the reading direction
                        val col = if (c.rtl) 1 - k else k
                        val x = pad + col * (tileW + gap)
                        cv.drawRoundRect(RectF(x, y + gap, x + tileW, y + gap + th), 18 * u, 18 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIGHT })
                        cv.save(); cv.translate(x + 16 * u, y + gap + 20 * u); v.draw(cv); cv.translate(0f, v.height + 8 * u); l.draw(cv); cv.restore()
                    }
                }
            }
        }
        // section cards
        c.sections.forEachIndexed { i, s ->
            val badge = if (info) 64 * u else 0f
            val textW = (inner - 2 * 24 * u - (if (info) badge + 20 * u else 0f)).toInt()
            val head = if (s.heading.isNotBlank()) layout(s.heading + (if (s.pages.isNotEmpty()) "  (" + s.pages.joinToString(", ") { "p. $it" } + ")" else ""), tp(36 * u, ACCENT, true), textW) else null
            val body = if (s.text.isNotBlank()) layout(s.text, tp(28 * u, 0xFF222222.toInt()), textW) else null
            val bul = if (s.bullets.isNotEmpty()) layout(s.bullets.joinToString("\n") { "• $it" }, tp(28 * u, 0xFF333333.toInt()), textW) else null
            val parts = listOfNotNull(head, body, bul)
            val contentH = parts.sumOf { it.height.toDouble() }.toFloat() + 12 * u * (parts.size - 1).coerceAtLeast(0)
            val cardH = maxOf(contentH, badge) + 48 * u
            val stripe = if (i % 2 == 1 && info) ORANGE else ACCENT2
            out += Block(cardH + 24 * u) { cv, y ->
                val top = y + 24 * u
                val r = RectF(pad, top, w - pad, top + cardH)
                cv.drawRoundRect(r, 18 * u, 18 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stripe }
                // accent stripe on the start edge
                if (c.rtl) cv.drawRect(r.right - 10 * u, r.top, r.right, r.bottom, sp) else cv.drawRect(r.left, r.top, r.left + 10 * u, r.bottom, sp)
                var x = r.left + 24 * u
                if (info) {
                    val cx = if (c.rtl) r.right - 24 * u - badge / 2 else r.left + 24 * u + badge / 2
                    val cy = top + 24 * u + badge / 2
                    cv.drawCircle(cx, cy, badge / 2, sp)
                    val np = tp(30 * u, Color.WHITE, true).apply { textAlign = Paint.Align.CENTER }
                    cv.drawText("${i + 1}", cx, cy - (np.descent() + np.ascent()) / 2, np)
                    if (!c.rtl) x += badge + 20 * u
                }
                cv.save(); cv.translate(x, top + 24 * u)
                parts.forEachIndexed { k, l -> if (k > 0) cv.translate(0f, 12 * u); l.draw(cv); cv.translate(0f, l.height.toFloat()) }
                cv.restore()
            }
        }
        if (c.takeaway.isNotBlank()) {
            val hl = layout(labels.takeaway, tp(32 * u, Color.WHITE, true), inner - (48 * u).toInt())
            val tl = layout(c.takeaway, tp(30 * u, Color.WHITE), inner - (48 * u).toInt())
            val h = hl.height + 10 * u + tl.height + 48 * u
            out += Block(h + 24 * u) { cv, y ->
                val top = y + 24 * u
                cv.drawRoundRect(RectF(pad, top, w - pad, top + h), 18 * u, 18 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
                cv.save(); cv.translate(pad + 24 * u, top + 24 * u); hl.draw(cv); cv.translate(0f, hl.height + 10 * u); tl.draw(cv); cv.restore()
            }
        }
        if (footer.isNotBlank()) {
            val fl = layout(footer, tp(22 * u, Color.GRAY), inner, center = true)
            out += Block(fl.height + 56 * u) { cv, y -> cv.save(); cv.translate(pad, y + 28 * u); fl.draw(cv); cv.restore() }
        }
        return out
    }

    /** The whole craft as one poster [w] px wide (at most [MAX_HEIGHT] tall); the caller recycles it. */
    fun bitmap(c: Craft, labels: CraftLabels, footer: String = "", w: Int = 1080): Bitmap {
        val u = w / 1080f
        val bs = blocks(c, labels, footer, w, u)
        val h = (bs.sumOf { it.h.toDouble() } + 24 * u).toInt().coerceIn(200, MAX_HEIGHT)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(BG)
        var y = 0f
        for (b in bs) { if (y >= h) break; b.draw(cv, y); y += b.h }
        return bmp
    }

    fun png(c: Craft, labels: CraftLabels, footer: String, out: File) {
        val bmp = bitmap(c, labels, footer)
        try { out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bmp.recycle() }
    }

    /** A4 portrait PDF; blocks never split (a block taller than a page is drawn on its own page). */
    fun pdf(c: Craft, labels: CraftLabels, footer: String, out: File) {
        val w = 595; val h = 842
        val u = w / 1080f
        val bs = blocks(c, labels, footer, w, u)
        val doc = PdfDocument()
        try {
            var n = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n++).create())
            page.canvas.drawColor(BG)
            var y = 0f
            for (b in bs) {
                if (y > 0f && y + b.h > h - 24 * u) {
                    doc.finishPage(page)
                    page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n++).create())
                    page.canvas.drawColor(BG)
                    y = 0f
                }
                b.draw(page.canvas, y); y += b.h
            }
            doc.finishPage(page)
            out.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
    }
}
