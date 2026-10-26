package com.smartdocs.editor.ui.pageflip

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.smartdocs.editor.core.pageflip.FlipMath
import com.smartdocs.editor.core.pageflip.PageSide
import com.smartdocs.editor.core.pageflip.PfAxis
import com.smartdocs.editor.core.pageflip.PfEffect
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Draws transition frames on an android.graphics.Canvas (used inside Compose through `drawIntoCanvas { it.nativeCanvas }`
 * by the reader host and by the live previews). Flat / 3D effects use Canvas transforms and [Camera] perspective;
 * paper effects are delegated to [CurlRenderer] (drawBitmapMesh). One instance per canvas; not thread-safe.
 */
class FlipRenderer {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val camera = Camera()
    private val matrix = Matrix()
    private val tmp = RectF()
    internal val curl = CurlRenderer(this)
    private var tintFor: Long = 0xFFFFFFFFL
    var tint: ColorFilter? = null; private set
    internal var washFilter: ColorFilter = wash(0xFFFFFFFFL); private set

    private fun prepare(style: FlipStyle) {
        if (style.pageColor != tintFor) {
            tintFor = style.pageColor
            tint = tintFilter(style.pageColor)
            washFilter = wash(style.pageColor)
        }
        paint.colorFilter = tint
        paint.alpha = 255
    }

    fun draw(c: Canvas, w: Float, h: Float, sc: FlipScene, style: FlipStyle) {
        prepare(style)
        c.drawColor(style.background)
        val t = sc.t
        val dir = if (sc.rtl) -1f else 1f
        val vertical = sc.axis == PfAxis.VERTICAL && sc.effect.vertical
        val d = if (vertical) h else w
        fun move(f: Float) { if (vertical) c.translate(0f, f * d) else c.translate(f * d * dir, 0f) }
        when (sc.effect) {
            PfEffect.CURL, PfEffect.BOOK -> curl.draw(c, sc, style)
            PfEffect.NONE -> drawItem(c, if (t < 0.5f) sc.a else sc.b, style)
            PfEffect.FADE -> { drawItem(c, sc.a, style); drawItem(c, sc.b, style, t.coerceIn(0f, 1f)) }
            PfEffect.SLIDE -> {
                c.save(); move(-t); drawItem(c, sc.a, style); c.restore()
                c.save(); move(1f - t); drawItem(c, sc.b, style); c.restore()
            }
            PfEffect.REVEAL -> {
                drawItem(c, sc.b, style); dim(c, w, h, (1f - t) * 0.35f * style.intensity)
                c.save(); move(-t); drawItem(c, sc.a, style)
                if (style.shadows) edgeShadow(c, sc.a.bounds, vertical, dir, trailing = true, style, 1f - t)
                c.restore()
            }
            PfEffect.PARALLAX -> {
                val depth = 0.15f + 0.25f * style.intensity
                c.save(); move(-t * depth); drawItem(c, sc.a, style); c.restore()
                dim(c, w, h, t * 0.45f * style.intensity)
                c.save(); move(1f - t)
                if (style.shadows) edgeShadow(c, sc.b.bounds, vertical, dir, trailing = false, style, t)
                drawItem(c, sc.b, style); c.restore()
            }
            PfEffect.SCALE_FADE -> {
                val out = (t / 0.45f).coerceIn(0f, 1f); val inn = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                if (out < 1f) { c.save(); c.scale(1f - 0.08f * out, 1f - 0.08f * out, w / 2, h / 2); drawItem(c, sc.a, style, 1f - out); c.restore() }
                if (inn > 0f) { c.save(); c.scale(0.92f + 0.08f * inn, 0.92f + 0.08f * inn, w / 2, h / 2); drawItem(c, sc.b, style, inn); c.restore() }
            }
            PfEffect.ZOOM -> {
                val k = t.coerceIn(0f, 1f)
                c.save(); c.scale(0.8f + 0.2f * k, 0.8f + 0.2f * k, w / 2, h / 2); drawItem(c, sc.b, style, k); c.restore()
                c.save(); c.scale(1f + 0.25f * k, 1f + 0.25f * k, w / 2, h / 2); drawItem(c, sc.a, style, 1f - k); c.restore()
            }
            PfEffect.WIPE -> {
                drawItem(c, sc.a, style)
                val k = t.coerceIn(0f, 1f)
                c.save()
                val edge: Float
                if (vertical) { edge = h * (1f - k); c.clipRect(0f, edge, w, h) }
                else if (dir > 0) { edge = w * (1f - k); c.clipRect(edge, 0f, w, h) }
                else { edge = w * k; c.clipRect(0f, 0f, edge, h) }
                c.drawColor(style.background)
                drawItem(c, sc.b, style)
                c.restore()
                if (style.shadows && k in 0.001f..0.999f) softEdge(c, w, h, edge, vertical, dir, style)
            }
            PfEffect.ROTATE -> {
                drawItem(c, sc.b, style); dim(c, w, h, (1f - t) * 0.3f * style.intensity)
                val bd = sc.a.bounds
                val px = if (dir > 0) bd.left else bd.right
                c.save()
                c.translate(-t * w * 0.6f * dir, 0f)
                c.rotate(-28f * t * dir, px, bd.bottom)
                drawItem(c, sc.a, style, (1f - t * t).coerceIn(0f, 1f))
                c.restore()
            }
            PfEffect.ROTATE_3D -> {
                drawItem(c, sc.b, style); dim(c, w, h, (1f - t) * 0.4f * style.intensity)
                val bd = sc.a.bounds
                val alpha = if (t > 0.8f) ((1f - t) / 0.2f).coerceIn(0f, 1f) else 1f
                if (vertical) withCamera(c, w, h, rx = 88f * t * ROT_X_SIGN, ry = 0f, px = w / 2, py = bd.top) { drawItem(c, sc.a, style, alpha) }
                else withCamera(c, w, h, rx = 0f, ry = -88f * t * dir, px = if (dir > 0) bd.left else bd.right, py = h / 2) { drawItem(c, sc.a, style, alpha) }
            }
            PfEffect.CUBE -> {
                for ((item, p) in listOf(sc.a to -t, sc.b to 1f - t)) {
                    if (p <= -1f || p >= 1f) continue
                    c.save()
                    if (vertical) {
                        c.translate(0f, p * h)
                        withCamera(c, w, h, rx = -90f * p * ROT_X_SIGN, ry = 0f, px = w / 2, py = if (p < 0) h else 0f) { drawItem(c, item, style); dim(c, w, h, kotlin.math.abs(p) * 0.5f * style.intensity) }
                    } else {
                        val pp = p * dir
                        c.translate(pp * w, 0f)
                        withCamera(c, w, h, rx = 0f, ry = 90f * pp, px = if (pp < 0) w else 0f, py = h / 2) { drawItem(c, item, style); dim(c, w, h, kotlin.math.abs(p) * 0.5f * style.intensity) }
                    }
                    c.restore()
                }
            }
            PfEffect.FLIP_CARD -> {
                val k = t.coerceIn(0f, 1f)
                val s = 1f - 0.1f * sin(PI.toFloat() * k)
                c.save(); c.scale(s, s, w / 2, h / 2)
                if (k < 0.5f) {
                    val a = 180f * k
                    if (vertical) withCamera(c, w, h, rx = a * ROT_X_SIGN, ry = 0f, px = w / 2, py = h / 2) { drawItem(c, sc.a, style) }
                    else withCamera(c, w, h, rx = 0f, ry = -a * dir, px = w / 2, py = h / 2) { drawItem(c, sc.a, style) }
                } else {
                    val a = 180f * (1f - k)
                    if (vertical) withCamera(c, w, h, rx = -a * ROT_X_SIGN, ry = 0f, px = w / 2, py = h / 2) { drawItem(c, sc.b, style) }
                    else withCamera(c, w, h, rx = 0f, ry = a * dir, px = w / 2, py = h / 2) { drawItem(c, sc.b, style) }
                }
                c.restore()
            }
        }
    }

    /** A static screen (the final frame / previews / settled book decorations). */
    fun drawStatic(c: Canvas, item: ItemSnap, style: FlipStyle, rtl: Boolean) {
        prepare(style)
        c.drawColor(style.background)
        if (style.book) bookUnder(c, item, style, rtl)
        drawItem(c, item, style)
        if (style.book) bookOver(c, item, style, rtl)
    }

    fun drawItem(c: Canvas, item: ItemSnap, style: FlipStyle, alpha: Float = 1f) {
        for (p in item.pages) drawPage(c, p, style, alpha)
    }

    fun drawPage(c: Canvas, p: PageSnap?, style: FlipStyle, alpha: Float = 1f, rect: RectF? = null) {
        p ?: return
        val r = rect ?: p.rect
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        if (a <= 0) return
        if (style.shadows) {
            fill.shader = null; fill.color = android.graphics.Color.argb((a * 0.16f).toInt(), 0, 0, 0)
            val e = style.density * 1.5f
            tmp.set(r.left - e * 0.5f, r.top, r.right + e * 0.5f, r.bottom + e)
            c.drawRect(tmp, fill)
        }
        val bmp = p.bitmap
        if (bmp == null || bmp.isRecycled) {
            fill.shader = null; fill.color = android.graphics.Color.argb(a, 255, 255, 255); c.drawRect(r, fill)
        } else {
            paint.alpha = a
            c.drawBitmap(bmp, null, r, paint)
            paint.alpha = 255
        }
    }

    // ---------------- book decorations ----------------

    /** Paper stack edges on the outer sides (thickness), proportional to the pages on each side. */
    fun bookUnder(c: Canvas, item: ItemSnap, style: FlipStyle, rtl: Boolean, before: Int = style.pagesBefore, after: Int = style.pagesAfter) {
        if (!style.thickness) return
        val maxPx = 6f * style.density
        val leftCount = if (rtl) after else before
        val rightCount = if (rtl) before else after
        val leftPage = item.onSide(PageSide.LEFT) ?: item.onSide(PageSide.CENTER)?.takeIf { rtl }
        val rightPage = item.onSide(PageSide.RIGHT) ?: item.onSide(PageSide.CENTER)?.takeIf { !rtl }
        leftPage?.let { stackEdge(c, it.rect.left, -1f, it.rect.top, it.rect.bottom, FlipMath.stackEdgePx(leftCount, maxPx), style) }
        rightPage?.let { stackEdge(c, it.rect.right, 1f, it.rect.top, it.rect.bottom, FlipMath.stackEdgePx(rightCount, maxPx), style) }
    }

    private fun stackEdge(c: Canvas, x: Float, sign: Float, top: Float, bottom: Float, px: Float, style: FlipStyle) {
        if (px <= 0.5f) return
        fill.shader = null
        val step = max(1f, style.density * 1.2f)
        var o = 0f
        var i = 0
        while (o < px) {
            fill.color = if (i % 2 == 0) 0xFFE9E5DC.toInt() else 0xFFCFCBC2.toInt()
            val x0 = x + sign * o; val x1 = x + sign * (o + step)
            c.drawRect(minOf(x0, x1), top + o * 0.6f, maxOf(x0, x1), bottom + o * 0.6f, fill)
            o += step; i++
        }
    }

    /** Binding shadow along the spine (and the bound edge of a single page). */
    fun bookOver(c: Canvas, item: ItemSnap, style: FlipStyle, rtl: Boolean) {
        if (!style.shadows) return
        val k = 0.18f + 0.22f * style.intensity
        item.pages.forEach { p ->
            val r = p.rect
            val inner = when (p.side) {
                PageSide.LEFT -> r.right to -1f
                PageSide.RIGHT -> r.left to 1f
                PageSide.CENTER -> (if (rtl) r.right else r.left) to (if (rtl) -1f else 1f)
            }
            val width = r.width() * (if (p.side == PageSide.CENTER) 0.04f else 0.08f)
            val (x, s) = inner
            fill.shader = LinearGradient(x, 0f, x + s * width, 0f, android.graphics.Color.argb((255 * k).toInt(), 0, 0, 0), 0, Shader.TileMode.CLAMP)
            c.drawRect(minOf(x, x + s * width), r.top, maxOf(x, x + s * width), r.bottom, fill)
            fill.shader = null
        }
    }

    // ---------------- helpers ----------------

    internal fun dim(c: Canvas, w: Float, h: Float, a: Float) {
        if (a <= 0.003f) return
        fill.shader = null; fill.color = android.graphics.Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, w, h, fill)
    }

    private fun edgeShadow(c: Canvas, bd: RectF, vertical: Boolean, dir: Float, trailing: Boolean, style: FlipStyle, amount: Float) {
        val sw = 18f * style.density
        val a = (90 * style.intensity * amount.coerceIn(0f, 1f)).toInt()
        if (a <= 0) return
        val col = android.graphics.Color.argb(a, 0, 0, 0)
        if (vertical) {
            val y = if (trailing) bd.bottom else bd.top; val s = if (trailing) 1f else -1f
            fill.shader = LinearGradient(0f, y, 0f, y + s * sw, col, 0, Shader.TileMode.CLAMP)
            c.drawRect(bd.left, minOf(y, y + s * sw), bd.right, maxOf(y, y + s * sw), fill)
        } else {
            // trailing edge of the outgoing screen = the side it moves away from
            val right = (dir > 0) == trailing
            val x = if (right) bd.right else bd.left; val s = if (right) 1f else -1f
            fill.shader = LinearGradient(x, 0f, x + s * sw, 0f, col, 0, Shader.TileMode.CLAMP)
            c.drawRect(minOf(x, x + s * sw), bd.top, maxOf(x, x + s * sw), bd.bottom, fill)
        }
        fill.shader = null
    }

    private fun softEdge(c: Canvas, w: Float, h: Float, edge: Float, vertical: Boolean, dir: Float, style: FlipStyle) {
        val sw = 24f * style.density
        val col = android.graphics.Color.argb((70 * style.intensity).toInt(), 0, 0, 0)
        fill.shader = if (vertical) LinearGradient(0f, edge, 0f, edge - sw, col, 0, Shader.TileMode.CLAMP)
        else LinearGradient(edge, 0f, edge - dir * sw, 0f, col, 0, Shader.TileMode.CLAMP)
        if (vertical) c.drawRect(0f, edge - sw, w, edge, fill)
        else c.drawRect(minOf(edge, edge - dir * sw), 0f, maxOf(edge, edge - dir * sw), h, fill)
        fill.shader = null
    }

    internal fun withCamera(c: Canvas, w: Float, h: Float, rx: Float, ry: Float, px: Float, py: Float, block: () -> Unit) {
        camera.save()
        camera.setLocation(0f, 0f, -max(w, h) * 2.4f / 72f)
        if (rx != 0f) camera.rotateX(rx)
        if (ry != 0f) camera.rotateY(ry)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-px, -py)
        matrix.postTranslate(px, py)
        c.save(); c.concat(matrix)
        block()
        c.restore()
    }

    companion object {
        /** Sign convention of Camera.rotateX (positive tilts the top edge away from the viewer). */
        const val ROT_X_SIGN = 1f

        /** The reader's page-colour mapping (white -> background, black -> text colour) as a ColorMatrix. */
        fun tintFilter(pageColor: Long): ColorFilter? {
            if ((pageColor and 0xFFFFFFL) == 0xFFFFFFL) return null
            val bg = pageColor or 0xFF000000L
            val fg = com.smartdocs.editor.ui.reader.view.pageTextColor(pageColor) or 0xFF000000L
            fun ch(v: Long, sh: Int) = ((v shr sh) and 0xFF).toFloat()
            val m = floatArrayOf(
                (ch(bg, 16) - ch(fg, 16)) / 255f, 0f, 0f, 0f, ch(fg, 16),
                0f, (ch(bg, 8) - ch(fg, 8)) / 255f, 0f, 0f, ch(fg, 8),
                0f, 0f, (ch(bg, 0) - ch(fg, 0)) / 255f, 0f, ch(fg, 0),
                0f, 0f, 0f, 1f, 0f,
            )
            return ColorMatrixColorFilter(ColorMatrix(m))
        }

        /** Back of a sheet: the page shows through faintly (18 %) on paper of the page colour. */
        fun wash(pageColor: Long): ColorFilter {
            val bg = pageColor or 0xFF000000L
            fun ch(sh: Int) = ((bg shr sh) and 0xFF).toFloat() * 0.82f
            val k = 0.18f
            return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                k, 0f, 0f, 0f, ch(16),
                0f, k, 0f, 0f, ch(8),
                0f, 0f, k, 0f, ch(0),
                0f, 0f, 0f, 1f, 0f,
            )))
        }

        private var blank: Bitmap? = null
        /** 2×2 white bitmap used as mesh texture while a snapshot is still rendering. */
        fun blank(): Bitmap = blank?.takeUnless { it.isRecycled } ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE); blank = it }
    }
}
