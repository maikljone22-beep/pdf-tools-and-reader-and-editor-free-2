package com.smartdocs.editor.ui.signature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.io.File

/** One point of an ink stroke in canvas pixels, with pressure 0..1 (1 when the device reports none). */
data class InkPoint(val x: Float, val y: Float, val pressure: Float)

/** A single pen stroke: points, color and base width (px). */
data class InkStroke(val points: List<InkPoint>, val color: Int, val width: Float, val pressure: Boolean)

/** Pure drawing helpers shared by the Compose preview and the PNG export so both look identical. */
object InkRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    /** Draw a stroke as a chain of quadratic bezier segments through mid-points; width varies with pressure. */
    fun draw(canvas: Canvas, stroke: InkStroke, scale: Float = 1f, offsetX: Float = 0f, offsetY: Float = 0f) {
        val pts = stroke.points
        if (pts.isEmpty()) return
        paint.color = stroke.color
        if (pts.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(pts[0].x * scale + offsetX, pts[0].y * scale + offsetY, widthFor(stroke, pts[0].pressure) * scale / 2f, paint)
            paint.style = Paint.Style.STROKE
            return
        }
        if (!stroke.pressure) {
            paint.strokeWidth = stroke.width * scale
            canvas.drawPath(smoothPath(pts, scale, offsetX, offsetY), paint)
            return
        }
        // Pressure: draw each segment separately with its own width (short quadratic pieces keep it smooth).
        var prevMidX = pts[0].x; var prevMidY = pts[0].y
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val midX = (a.x + b.x) / 2f; val midY = (a.y + b.y) / 2f
            val p = Path()
            p.moveTo(prevMidX * scale + offsetX, prevMidY * scale + offsetY)
            p.quadTo(a.x * scale + offsetX, a.y * scale + offsetY, midX * scale + offsetX, midY * scale + offsetY)
            paint.strokeWidth = widthFor(stroke, (a.pressure + b.pressure) / 2f) * scale
            canvas.drawPath(p, paint)
            prevMidX = midX; prevMidY = midY
        }
        val last = pts.last()
        paint.strokeWidth = widthFor(stroke, last.pressure) * scale
        canvas.drawLine(prevMidX * scale + offsetX, prevMidY * scale + offsetY, last.x * scale + offsetX, last.y * scale + offsetY, paint)
    }

    private fun widthFor(stroke: InkStroke, pressure: Float): Float =
        if (!stroke.pressure) stroke.width else stroke.width * (0.45f + 0.9f * pressure.coerceIn(0.05f, 1f))

    private fun smoothPath(pts: List<InkPoint>, scale: Float, ox: Float, oy: Float): Path {
        val p = Path()
        p.moveTo(pts[0].x * scale + ox, pts[0].y * scale + oy)
        if (pts.size == 2) { p.lineTo(pts[1].x * scale + ox, pts[1].y * scale + oy); return p }
        for (i in 1 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            p.quadTo(a.x * scale + ox, a.y * scale + oy, (a.x + b.x) / 2f * scale + ox, (a.y + b.y) / 2f * scale + oy)
        }
        val last = pts.last(); p.lineTo(last.x * scale + ox, last.y * scale + oy)
        return p
    }

    /** Render strokes into a transparent bitmap cropped to their bounds (with padding). */
    fun toBitmap(strokes: List<InkStroke>, scale: Float = 2f, padding: Float = 12f): Bitmap? {
        val all = strokes.flatMap { it.points }
        if (all.isEmpty()) return null
        val maxW = strokes.maxOf { it.width } * 1.4f
        val minX = all.minOf { it.x } - maxW - padding; val minY = all.minOf { it.y } - maxW - padding
        val maxX = all.maxOf { it.x } + maxW + padding; val maxY = all.maxOf { it.y } + maxW + padding
        val w = ((maxX - minX) * scale).toInt().coerceAtLeast(1); val h = ((maxY - minY) * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        strokes.forEach { draw(canvas, it, scale, -minX * scale, -minY * scale) }
        return bmp
    }
}

/** Bitmap helpers for typed / imported signatures. */
object SignatureImage {

    /** The four "handwriting-like" styles available on every Android device (no bundled fonts needed). */
    val typefaces: List<Typeface> by lazy {
        listOf(
            Typeface.create("cursive", Typeface.ITALIC),
            Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC),
            Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC),
            Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC),
        )
    }

    /** Render [text] with [typeface] into a transparent bitmap. */
    fun renderText(text: String, typeface: Typeface, color: Int, sizePx: Float = 140f): Bitmap? {
        if (text.isBlank()) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.typeface = typeface; textSize = sizePx }
        val fm = paint.fontMetrics
        val w = (paint.measureText(text) + sizePx * 0.4f).toInt().coerceAtLeast(1)
        val h = (fm.descent - fm.ascent + sizePx * 0.3f).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, sizePx * 0.2f, -fm.ascent + sizePx * 0.15f, paint)
        return bmp
    }

    /** Make near-white pixels transparent. [threshold] 0..255: pixels whose min channel is above it become transparent; a soft band fades. */
    fun removeWhiteBackground(src: Bitmap, threshold: Int = 200): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val soft = 25
        for (i in px.indices) {
            val c = px[i]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val light = minOf(r, g, b)
            if (light >= threshold) px[i] = 0
            else if (light >= threshold - soft) {
                val a = ((threshold - light) * 255 / soft).coerceIn(0, 255)
                px[i] = Color.argb(a, r, g, b)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Crop to the bounds of non-transparent pixels (alpha > 16), with a small margin. Returns the input when empty. */
    fun cropToContent(src: Bitmap, margin: Int = 8): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(px[y * w + x]) > 16) {
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return src
        val l = (minX - margin).coerceAtLeast(0); val t = (minY - margin).coerceAtLeast(0)
        val r = (maxX + margin).coerceAtMost(w - 1); val b = (maxY + margin).coerceAtMost(h - 1)
        return Bitmap.createBitmap(src, l, t, r - l + 1, b - t + 1)
    }

    /** Scale down so the longest side is at most [maxDim]. */
    fun limit(src: Bitmap, maxDim: Int = 1600): Bitmap {
        val s = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
        return if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true) else src
    }

    fun savePng(bmp: Bitmap, file: File) { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
}
