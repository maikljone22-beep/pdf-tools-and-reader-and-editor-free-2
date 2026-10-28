package com.smartdocs.editor.ui.nve.imgedit

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Brush / layer blend modes of the image editor. */
enum class Blend { NORMAL, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN }

/** One filter of the stack; [amount] meaning depends on [type] (see [ImageOps.applyFilters]). */
enum class FilterType { BRIGHTNESS, CONTRAST, SATURATION, GRAYSCALE, SEPIA, INVERT, BLUR, SHARPEN, THRESHOLD }
data class FilterStep(val type: FilterType, val amount: Float)

/** A layer of the image editor. Bitmaps are ARGB_8888, mutable, all the size of the canvas. */
class Layer(var bmp: Bitmap, var opacity: Float = 1f, var blend: Blend = Blend.NORMAL, var visible: Boolean = true) {
    fun copy(): Layer = Layer(bmp.copy(Bitmap.Config.ARGB_8888, true), opacity, blend, visible)
}

/** Pixel operations of the image editor (flood fill, masks, filters, retouching, compositing). */
object ImageOps {

    fun paintFor(blend: Blend, alpha: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        if (blend != Blend.NORMAL) {
            if (Build.VERSION.SDK_INT >= 29) blendMode = when (blend) {
                Blend.MULTIPLY -> BlendMode.MULTIPLY; Blend.SCREEN -> BlendMode.SCREEN; Blend.OVERLAY -> BlendMode.OVERLAY
                Blend.DARKEN -> BlendMode.DARKEN; Blend.LIGHTEN -> BlendMode.LIGHTEN; Blend.NORMAL -> BlendMode.SRC_OVER
            } else xfermode = PorterDuffXfermode(when (blend) {
                Blend.MULTIPLY -> PorterDuff.Mode.MULTIPLY; Blend.SCREEN -> PorterDuff.Mode.SCREEN; Blend.OVERLAY -> PorterDuff.Mode.OVERLAY
                Blend.DARKEN -> PorterDuff.Mode.DARKEN; Blend.LIGHTEN -> PorterDuff.Mode.LIGHTEN; Blend.NORMAL -> PorterDuff.Mode.SRC_OVER
            })
        }
    }

    /** Draw the visible layers (opacity + blend) onto [c] (already transformed to image space). */
    fun drawLayers(c: Canvas, layers: List<Layer>, replace: Pair<Int, Bitmap>? = null) {
        val first = layers.firstOrNull() ?: return
        val save = c.saveLayer(0f, 0f, first.bmp.width.toFloat(), first.bmp.height.toFloat(), null)
        layers.forEachIndexed { i, l ->
            if (!l.visible) return@forEachIndexed
            val b = if (replace?.first == i) replace.second else l.bmp
            c.drawBitmap(b, 0f, 0f, paintFor(if (i == 0) Blend.NORMAL else l.blend, l.opacity))
        }
        c.restoreToCount(save)
    }

    /** All visible layers flattened into one new bitmap. */
    fun flatten(layers: List<Layer>): Bitmap {
        val f = layers.first().bmp
        val out = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
        drawLayers(Canvas(out), layers)
        return out
    }

    // ------------------------------------------------------------------ colour distance / flood

    private fun dist(a: Int, b: Int): Int {
        val da = abs((a ushr 24) - (b ushr 24)); val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)); val db = abs((a and 0xFF) - (b and 0xFF))
        return max(max(da, dr), max(dg, db))
    }

    /**
     * Pixels similar to (x, y) within [tolerance] (0..255): the connected region when [contiguous], otherwise every
     * matching pixel. Returned as a mask (1 = selected).
     */
    fun floodMask(px: IntArray, w: Int, h: Int, x: Int, y: Int, tolerance: Int, contiguous: Boolean): ByteArray {
        val mask = ByteArray(w * h)
        if (x !in 0 until w || y !in 0 until h) return mask
        val seed = px[y * w + x]
        if (!contiguous) { for (i in px.indices) if (dist(px[i], seed) <= tolerance) mask[i] = 1; return mask }
        val stack = IntArray(w * h); var sp = 0
        stack[sp++] = y * w + x; mask[y * w + x] = 1
        while (sp > 0) {
            val i = stack[--sp]; val cx = i % w; val cy = i / w
            fun push(j: Int) { if (mask[j].toInt() == 0 && dist(px[j], seed) <= tolerance) { mask[j] = 1; stack[sp++] = j } }
            if (cx > 0) push(i - 1); if (cx < w - 1) push(i + 1); if (cy > 0) push(i - w); if (cy < h - 1) push(i + w)
        }
        return mask
    }

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    fun setPixels(b: Bitmap, px: IntArray) = b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)

    /** Mask of a closed [path] (image space). */
    fun pathMask(path: Path, w: Int, h: Int): ByteArray {
        val a = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(a).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; style = Paint.Style.FILL })
        val px = pixels(a); a.recycle()
        return ByteArray(w * h) { i -> if ((px[i] ushr 24) >= 128) 1 else 0 }
    }

    fun invert(mask: ByteArray): ByteArray = ByteArray(mask.size) { if (mask[it].toInt() == 0) 1 else 0 }

    /** Bounding box [l, t, r, b) of a mask, or null when empty. */
    fun bounds(mask: ByteArray, w: Int, h: Int): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x].toInt() != 0) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    /** Blue tint overlay showing a selection. */
    fun maskOverlay(mask: ByteArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h) { if (mask[it].toInt() != 0) 0x402196F3 else 0x30000000 }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Keep [edited] only inside [mask]; outside, [original] is restored (both same size). */
    fun maskMerge(original: Bitmap, edited: Bitmap, mask: ByteArray?): Bitmap {
        if (mask == null) return edited
        val o = pixels(original); val e = pixels(edited)
        for (i in o.indices) if (mask[i].toInt() == 0) e[i] = o[i]
        val out = edited.copy(Bitmap.Config.ARGB_8888, true); setPixels(out, e); return out
    }

    /** Pixels of [src] inside [mask], cropped to the mask's box: (piece, left, top). */
    fun extract(src: Bitmap, mask: ByteArray?): Triple<Bitmap, Int, Int>? {
        val w = src.width; val h = src.height
        val bb = if (mask == null) intArrayOf(0, 0, w, h) else bounds(mask, w, h) ?: return null
        val pw = bb[2] - bb[0]; val ph = bb[3] - bb[1]
        val px = IntArray(pw * ph)
        src.getPixels(px, 0, pw, bb[0], bb[1], pw, ph)
        if (mask != null) for (y in 0 until ph) for (x in 0 until pw) if (mask[(y + bb[1]) * w + x + bb[0]].toInt() == 0) px[y * pw + x] = 0
        return Triple(Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true), bb[0], bb[1])
    }

    /** Make the selected pixels of [b] transparent (cut out). */
    fun clear(b: Bitmap, mask: ByteArray?) {
        val px = pixels(b)
        for (i in px.indices) if (mask == null || mask[i].toInt() != 0) px[i] = 0
        setPixels(b, px)
    }

    /** Fill the selected / flooded pixels of [b] with [color]. */
    fun fill(b: Bitmap, mask: ByteArray, color: Int) {
        val px = pixels(b)
        for (i in px.indices) if (mask[i].toInt() != 0) px[i] = color
        setPixels(b, px)
    }

    fun and(a: ByteArray, b: ByteArray?): ByteArray = if (b == null) a else ByteArray(a.size) { if (a[it].toInt() != 0 && b[it].toInt() != 0) 1 else 0 }

    // ------------------------------------------------------------------ geometry

    fun flipped(b: Bitmap, horizontal: Boolean): Bitmap {
        val m = Matrix().apply { if (horizontal) setScale(-1f, 1f, b.width / 2f, b.height / 2f) else setScale(1f, -1f, b.width / 2f, b.height / 2f) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).copy(Bitmap.Config.ARGB_8888, true)
    }

    fun flipMask(mask: ByteArray, w: Int, h: Int, horizontal: Boolean): ByteArray = ByteArray(mask.size) { i ->
        val x = i % w; val y = i / w
        if (horizontal) mask[y * w + (w - 1 - x)] else mask[(h - 1 - y) * w + x]
    }

    /** Flip only the selected part of [b] in place, mirrored inside the selection's box. */
    fun flipSelection(b: Bitmap, mask: ByteArray, horizontal: Boolean) {
        val (piece, l, t) = extract(b, mask) ?: return
        clear(b, mask)
        Canvas(b).drawBitmap(flipped(piece, horizontal), l.toFloat(), t.toFloat(), null)
    }

    // ------------------------------------------------------------------ retouching dabs

    /** Burn (darker, [burn] true) or dodge (lighter) a soft round dab of [radius] at (cx, cy), [strength] 0..1. */
    fun burnDodge(b: Bitmap, cx: Float, cy: Float, radius: Float, strength: Float, burn: Boolean, mask: ByteArray?) {
        val r = radius.roundToInt().coerceAtLeast(1)
        val x0 = (cx - r).toInt().coerceAtLeast(0); val y0 = (cy - r).toInt().coerceAtLeast(0)
        val x1 = (cx + r).toInt().coerceAtMost(b.width - 1); val y1 = (cy + r).toInt().coerceAtMost(b.height - 1)
        if (x1 < x0 || y1 < y0) return
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        val px = IntArray(w * h); b.getPixels(px, 0, w, x0, y0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val d = sqrt(((x + x0 - cx) * (x + x0 - cx) + (y + y0 - cy) * (y + y0 - cy)).toDouble()).toFloat()
            if (d > r) continue
            if (mask != null && mask[(y + y0) * b.width + x + x0].toInt() == 0) continue
            val k = strength * (1f - d / r) * 0.25f
            val c = px[y * w + x]
            fun ch(v: Int) = if (burn) (v * (1f - k)).roundToInt().coerceIn(0, 255) else (v + (255 - v) * k).roundToInt().coerceIn(0, 255)
            px[y * w + x] = (c and 0xFF000000.toInt()) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
        }
        b.setPixels(px, 0, w, x0, y0, w, h)
    }

    /** Clone stamp dab: copy a soft round area of [source] around (cx + ox, cy + oy) to (cx, cy) of [b]. */
    fun cloneDab(b: Bitmap, source: Bitmap, cx: Float, cy: Float, ox: Float, oy: Float, radius: Float, mask: ByteArray?) {
        val r = radius.roundToInt().coerceAtLeast(1)
        val x0 = (cx - r).toInt().coerceAtLeast(0); val y0 = (cy - r).toInt().coerceAtLeast(0)
        val x1 = (cx + r).toInt().coerceAtMost(b.width - 1); val y1 = (cy + r).toInt().coerceAtMost(b.height - 1)
        if (x1 < x0 || y1 < y0) return
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        val px = IntArray(w * h); b.getPixels(px, 0, w, x0, y0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val ix = x + x0; val iy = y + y0
            val d = sqrt(((ix - cx) * (ix - cx) + (iy - cy) * (iy - cy)).toDouble()).toFloat()
            if (d > r) continue
            if (mask != null && mask[iy * b.width + ix].toInt() == 0) continue
            val sx = (ix + ox).roundToInt(); val sy = (iy + oy).roundToInt()
            if (sx !in 0 until source.width || sy !in 0 until source.height) continue
            val a = (1f - d / r).coerceIn(0f, 1f).let { min(1f, it * 2f) }
            px[y * w + x] = mix(px[y * w + x], source.getPixel(sx, sy), a)
        }
        b.setPixels(px, 0, w, x0, y0, w, h)
    }

    /**
     * Spot healing: §14c texture synthesis ([HealSynth]: the best-matching patch of the surroundings, tone-corrected
     * and feathered); near the image edges, where no patch fits, the ring average.
     */
    fun healDab(b: Bitmap, cx: Float, cy: Float, radius: Float, mask: ByteArray?) {
        val r = radius.roundToInt().coerceAtLeast(2)
        val reach = HealSynth.reach(r)
        val x0 = (cx - reach).toInt().coerceAtLeast(0); val y0 = (cy - reach).toInt().coerceAtLeast(0)
        val x1 = (cx + reach).toInt().coerceAtMost(b.width - 1); val y1 = (cy + reach).toInt().coerceAtMost(b.height - 1)
        if (x1 >= x0 && y1 >= y0) {
            val w = x1 - x0 + 1; val h = y1 - y0 + 1
            val px = IntArray(w * h); b.getPixels(px, 0, w, x0, y0, w, h)
            val ok = HealSynth.heal(px, w, h, cx - x0, cy - y0, r) { x, y -> mask == null || mask[(y + y0) * b.width + x + x0].toInt() != 0 }
            if (ok) { b.setPixels(px, 0, w, x0, y0, w, h); return }
        }
        healAverage(b, cx, cy, radius, mask)
    }

    /** Spot healing (simple): fill a round spot with the average colour of the ring around it, feathered at the edge. */
    private fun healAverage(b: Bitmap, cx: Float, cy: Float, radius: Float, mask: ByteArray?) {
        val r = radius.roundToInt().coerceAtLeast(2); val ring = (r * 1.5f).roundToInt()
        var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        for (y in (cy - ring).toInt()..(cy + ring).toInt()) for (x in (cx - ring).toInt()..(cx + ring).toInt()) {
            if (x !in 0 until b.width || y !in 0 until b.height) continue
            val d = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble())
            if (d < r || d > ring) continue
            val c = b.getPixel(x, y); sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
        }
        if (n == 0) return
        val avg = ((sa / n).toInt() shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
        val x0 = (cx - r).toInt().coerceAtLeast(0); val y0 = (cy - r).toInt().coerceAtLeast(0)
        val x1 = (cx + r).toInt().coerceAtMost(b.width - 1); val y1 = (cy + r).toInt().coerceAtMost(b.height - 1)
        if (x1 < x0 || y1 < y0) return
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        val px = IntArray(w * h); b.getPixels(px, 0, w, x0, y0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val ix = x + x0; val iy = y + y0
            val d = sqrt(((ix - cx) * (ix - cx) + (iy - cy) * (iy - cy)).toDouble()).toFloat()
            if (d > r) continue
            if (mask != null && mask[iy * b.width + ix].toInt() == 0) continue
            val a = if (d < r * 0.7f) 0.92f else 0.92f * (1f - (d - r * 0.7f) / (r * 0.3f))
            px[y * w + x] = mix(px[y * w + x], avg, a.coerceIn(0f, 1f))
        }
        b.setPixels(px, 0, w, x0, y0, w, h)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1f - t) + ((b shr s) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ------------------------------------------------------------------ filters

    private fun colorMatrix(src: Bitmap, cm: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        if (r < 1) return px.copyOf()
        val tmp = IntArray(px.size); val out = IntArray(px.size)
        fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
            val len = if (horizontal) w else h; val lines = if (horizontal) h else w
            for (line in 0 until lines) {
                var a = 0; var rr = 0; var g = 0; var b = 0
                fun idx(i: Int) = if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line
                for (i in -r..r) { val c = src[idx(i)]; a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
                val n = 2 * r + 1
                for (i in 0 until len) {
                    dst[idx(i)] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                    val add = src[idx(i + r + 1)]; val rem = src[idx(i - r)]
                    a += (add ushr 24) - (rem ushr 24); rr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                    g += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF); b += (add and 0xFF) - (rem and 0xFF)
                }
            }
        }
        pass(px, tmp, true); pass(tmp, out, false)
        return out
    }

    /** Apply the filter stack to [src] (a new bitmap). */
    fun applyFilters(src: Bitmap, steps: List<FilterStep>): Bitmap {
        var cur = src.copy(Bitmap.Config.ARGB_8888, true)
        for (s in steps) {
            val a = s.amount
            cur = when (s.type) {
                FilterType.BRIGHTNESS -> colorMatrix(cur, ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, a * 255f, 0f, 1f, 0f, 0f, a * 255f, 0f, 0f, 1f, 0f, a * 255f, 0f, 0f, 0f, 1f, 0f)))
                FilterType.CONTRAST -> { val c = 1f + a; val t = 128f * (1f - c); colorMatrix(cur, ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))) }
                FilterType.SATURATION -> colorMatrix(cur, ColorMatrix().apply { setSaturation((1f + a).coerceAtLeast(0f)) })
                FilterType.GRAYSCALE -> colorMatrix(cur, ColorMatrix().apply { setSaturation(1f - a.coerceIn(0f, 1f)) })
                FilterType.SEPIA -> {
                    val k = a.coerceIn(0f, 1f)
                    val sep = floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
                    val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
                    colorMatrix(cur, ColorMatrix(FloatArray(20) { id[it] + (sep[it] - id[it]) * k }))
                }
                FilterType.INVERT -> colorMatrix(cur, ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
                FilterType.BLUR -> {
                    val r = a.roundToInt().coerceIn(1, 25); val w = cur.width; val h = cur.height
                    var p = pixels(cur); repeat(2) { p = boxBlur(p, w, h, r) }
                    Bitmap.createBitmap(p, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
                }
                FilterType.SHARPEN -> {
                    val w = cur.width; val h = cur.height; val o = pixels(cur); val bl = boxBlur(o, w, h, 1)
                    val out = IntArray(o.size) { i ->
                        val c = o[i]; val d = bl[i]
                        fun ch(s: Int) = (((c shr s) and 0xFF) + a * (((c shr s) and 0xFF) - ((d shr s) and 0xFF))).roundToInt().coerceIn(0, 255)
                        (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                    }
                    Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
                }
                FilterType.THRESHOLD -> {
                    val w = cur.width; val h = cur.height; val o = pixels(cur); val t = a.coerceIn(0f, 255f)
                    val out = IntArray(o.size) { i ->
                        val c = o[i]; val l = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                        (c and 0xFF000000.toInt()) or (if (l >= t) 0xFFFFFF else 0)
                    }
                    Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
                }
            }
        }
        return cur
    }

    /** Default amount and slider range of each filter. */
    fun range(t: FilterType): Pair<Float, ClosedFloatingPointRange<Float>> = when (t) {
        FilterType.BRIGHTNESS -> 0.15f to -1f..1f
        FilterType.CONTRAST -> 0.25f to -0.9f..2f
        FilterType.SATURATION -> 0.4f to -1f..2f
        FilterType.GRAYSCALE, FilterType.SEPIA -> 1f to 0f..1f
        FilterType.INVERT -> 1f to 1f..1f
        FilterType.BLUR -> 3f to 1f..25f
        FilterType.SHARPEN -> 1f to 0.2f..4f
        FilterType.THRESHOLD -> 128f to 1f..254f
    }
}
