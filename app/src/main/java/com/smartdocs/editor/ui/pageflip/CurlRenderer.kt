package com.smartdocs.editor.ui.pageflip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.smartdocs.editor.core.pageflip.CurlMath
import com.smartdocs.editor.core.pageflip.FlipMath
import com.smartdocs.editor.core.pageflip.PageSide
import com.smartdocs.editor.core.pageflip.PfEffect
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometry of the turning leaf on screen: spine x, top, size, and +1 (spine on the left) / −1 (spine on the right). */
class Leaf(val spineX: Float, val top: Float, val w: Float, val h: Float, val dir: Float) {
    val rect: RectF get() = if (dir > 0) RectF(spineX, top, spineX + w, top + h) else RectF(spineX - w, top, spineX, top + h)
    /** Viewport point -> leaf space (x from the spine towards the free edge). */
    fun toLeaf(x: Float, y: Float, out: FloatArray) { out[0] = dir * (x - spineX); out[1] = y - top }

    companion object {
        /**
         * The leaf turned when going from [a] to [b]: the page of [a] on the "next" side (right in LTR, left in RTL) of
         * a spread, or the single page. Falls back to the mirrored page on the other side, then to [b].
         */
        fun of(a: ItemSnap, b: ItemSnap, rtl: Boolean): Leaf? {
            val next = if (rtl) PageSide.LEFT else PageSide.RIGHT
            val prev = if (rtl) PageSide.RIGHT else PageSide.LEFT
            val dir = if (rtl) -1f else 1f
            a.onSide(PageSide.CENTER)?.let { p -> return Leaf(if (rtl) p.rect.right else p.rect.left, p.rect.top, p.rect.width(), p.rect.height(), dir) }
            a.onSide(next)?.let { p -> return Leaf(if (rtl) p.rect.right else p.rect.left, p.rect.top, p.rect.width(), p.rect.height(), dir) }
            a.onSide(prev)?.let { p -> return Leaf(if (rtl) p.rect.left else p.rect.right, p.rect.top, p.rect.width(), p.rect.height(), dir) }
            val q = b.onSide(next) ?: b.onSide(PageSide.CENTER) ?: b.onSide(prev) ?: return null
            return Leaf(if (rtl) q.rect.right else q.rect.left, q.rect.top, q.rect.width(), q.rect.height(), dir)
        }
    }
}

/**
 * Paper effects: cylinder curl with drawBitmapMesh (front + back meshes, per-vertex lighting), shadow cast on the
 * revealed page, rigid hard cover (Camera), and stack turns of several leaves.
 */
class CurlRenderer(private val r: FlipRenderer) {
    private val cols = 32
    private val rows = 24
    private val n = (cols + 1) * (rows + 1)
    private val verts = FloatArray(n * 2)
    private val light = FloatArray(n)
    private val colors = IntArray(n)
    private val pt = FloatArray(2)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas, sc: FlipScene, style: FlipStyle) {
        val rtl = sc.rtl
        val next = if (rtl) PageSide.LEFT else PageSide.RIGHT
        val prev = if (rtl) PageSide.RIGHT else PageSide.LEFT
        val spread = sc.a.pages.none { it.side == PageSide.CENTER } && sc.b.pages.none { it.side == PageSide.CENTER }
        val book = sc.effect == PfEffect.BOOK
        val leaf = Leaf.of(sc.a, sc.b, rtl) ?: run { r.drawItem(c, if (sc.t < 0.5f) sc.a else sc.b, style); return }
        // ---- bases: the page under the leaf and the facing page that stays ----
        if (book) r.bookUnder(c, if (spread) mergedBase(sc, prev, next) else sc.b, style, rtl)
        if (spread) {
            r.drawPage(c, sc.a.onSide(prev), style)
            r.drawPage(c, sc.b.onSide(next), style)
            if (book) r.bookOver(c, mergedBase(sc, prev, next), style, rtl)
        } else {
            r.drawItem(c, sc.b, style)
            if (book) r.bookOver(c, sc.b, style, rtl)
        }
        // ---- the turning leaf (or leaves) ----
        val chain = listOf(sc.a) + sc.stack + listOf(sc.b)
        val leaves = chain.size - 1
        if (leaves <= 1) {
            drawLeaf(c, leaf, sc.a, sc.b, sc.t, sc.finger, sc.grabY, spread, rtl, style, sc.hardCover)
            return
        }
        val prog = FloatArray(leaves) { FlipMath.stackLeaf(sc.t, it, leaves) }
        for (j in leaves - 1 downTo 0) if (prog[j] < 0.5f) drawLeaf(c, leaf, chain[j], chain[j + 1], prog[j], null, 0.5f + 0.1f * j, spread, rtl, style, false)
        for (j in 0 until leaves) if (prog[j] >= 0.5f) drawLeaf(c, leaf, chain[j], chain[j + 1], prog[j], null, 0.5f + 0.1f * j, spread, rtl, style, false)
    }

    /** Pages of the spread as seen at rest under the leaf: the facing page of [FlipScene.a] and the next page of [FlipScene.b]. */
    private fun mergedBase(sc: FlipScene, prev: PageSide, next: PageSide): ItemSnap =
        ItemSnap(sc.b.item, listOfNotNull(sc.a.onSide(prev), sc.b.onSide(next)))

    private fun drawLeaf(
        c: Canvas, leaf: Leaf, from: ItemSnap, to: ItemSnap, t: Float, finger: FloatArray?, grabY: Float,
        spread: Boolean, rtl: Boolean, style: FlipStyle, hard: Boolean,
    ) {
        if (t <= 0.0005f && finger == null) { drawFlatLeaf(c, leaf, from, spread, rtl, style); return }
        if (t >= 0.9995f && finger == null) { if (spread) r.drawPage(c, to.onSide(if (rtl) PageSide.RIGHT else PageSide.LEFT), style); return }
        val next = if (rtl) PageSide.LEFT else PageSide.RIGHT
        val prev = if (rtl) PageSide.RIGHT else PageSide.LEFT
        val front = if (spread) from.onSide(next) else from.onSide(PageSide.CENTER) ?: from.pages.firstOrNull()
        val back = if (spread) to.onSide(prev) else front
        if (hard) { drawCover(c, leaf, front, back, t, style); return }
        val w = leaf.w; val h = leaf.h
        val cy = grabY.coerceIn(0f, 1f) * h
        if (finger != null) { pt[0] = finger[0]; pt[1] = finger[1] }
        else CurlMath.autoPath(t, grabY, w, h, 0.05f + 0.06f * style.intensity, pt)
        CurlMath.constrain(pt[0], pt[1], w, cy, h, pt)
        val tp = CurlMath.progressOf(pt[0], w)
        val radius = CurlMath.radiusFor(tp, w, style.curvature)
        val fold = CurlMath.fold(w, cy, pt[0], pt[1], radius, h)
        if (fold == null) { drawFlatLeaf(c, leaf, from, spread, rtl, style); return }
        // shadow cast by the lifted part on the revealed page
        if (style.shadows) castShadow(c, leaf, fold, tp, style)
        val frontFlip = leaf.dir < 0
        val fb = front?.bitmap?.takeUnless { it.isRecycled } ?: FlipRenderer.blank()
        CurlMath.buildMesh(w, h, cols, rows, fold, CurlMath.Side.FRONT, frontFlip, leaf.spineX, leaf.dir, leaf.top, style.intensity, verts, light)
        mesh(c, fb, style, wash = false)
        // back: in a spread the next screen's facing page (texture mirrored), else the same sheet seen from behind
        val backFlip = if (spread) !frontFlip else frontFlip
        val bb = (if (spread) back?.bitmap else fb)?.takeUnless { it.isRecycled } ?: FlipRenderer.blank()
        if (CurlMath.buildMesh(w, h, cols, rows, fold, CurlMath.Side.BACK, backFlip, leaf.spineX, leaf.dir, leaf.top, style.intensity, verts, light)) {
            mesh(c, bb, style, wash = !spread)
        }
    }

    private fun drawFlatLeaf(c: Canvas, leaf: Leaf, from: ItemSnap, spread: Boolean, rtl: Boolean, style: FlipStyle) {
        val next = if (rtl) PageSide.LEFT else PageSide.RIGHT
        val p = if (spread) from.onSide(next) else from.onSide(PageSide.CENTER) ?: from.pages.firstOrNull()
        r.drawPage(c, p, style, rect = leaf.rect)
    }

    private fun mesh(c: Canvas, bmp: Bitmap, style: FlipStyle, wash: Boolean) {
        for (i in 0 until n) {
            val v = (light[i].coerceIn(0f, 1f) * 255).toInt()
            colors[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val p = r.paint
        val old = p.colorFilter
        if (wash) p.colorFilter = r.washFilter
        p.alpha = 255
        c.drawBitmapMesh(bmp, cols, rows, verts, 0, colors, 0, p)
        p.colorFilter = old
    }

    private fun castShadow(c: Canvas, leaf: Leaf, fold: CurlMath.Fold, tp: Float, style: FlipStyle) {
        val lx = fold.ax + fold.nx * fold.r; val ly = fold.ay + fold.ny * fold.r
        val sx = leaf.spineX + leaf.dir * lx; val sy = leaf.top + ly
        val nx = leaf.dir * fold.nx; val ny = fold.ny
        val sw = (leaf.w * 0.12f + fold.r * 1.4f) * sqrt(sin(PI.toFloat() * tp).coerceAtLeast(0f)) + 2f * style.density
        val a = (120 * style.intensity).toInt().coerceIn(0, 255)
        shadow.shader = LinearGradient(sx, sy, sx + nx * sw, sy + ny * sw, android.graphics.Color.argb(a, 0, 0, 0), 0, Shader.TileMode.CLAMP)
        c.save()
        c.clipRect(leaf.rect)
        c.drawRect(leaf.rect, shadow)
        c.restore()
        shadow.shader = null
    }

    /** Hard cover: a rigid board rotating around the spine with perspective (covers do not bend). */
    private fun drawCover(c: Canvas, leaf: Leaf, front: PageSnap?, back: PageSnap?, t: Float, style: FlipStyle) {
        val phi = 180f * t.coerceIn(0f, 1f)
        val cw = (leaf.rect.right + leaf.rect.left).let { max(leaf.w * 2f, it) }
        val shade = sin(PI.toFloat() * t.coerceIn(0f, 1f))
        if (style.shadows) {
            // shadow of the raised board on the pages beside the spine
            val sw = leaf.w * 0.5f * shade
            val a = (110 * style.intensity * shade).toInt().coerceIn(0, 255)
            val side = if (phi <= 90f) 1f else -1f
            shadow.shader = LinearGradient(leaf.spineX, 0f, leaf.spineX + leaf.dir * side * max(1f, sw), 0f, android.graphics.Color.argb(a, 0, 0, 0), 0, Shader.TileMode.CLAMP)
            val x0 = leaf.spineX; val x1 = leaf.spineX + leaf.dir * side * max(1f, sw)
            c.drawRect(minOf(x0, x1), leaf.top, maxOf(x0, x1), leaf.top + leaf.h, shadow)
            shadow.shader = null
        }
        if (phi <= 90f) {
            r.withCamera(c, cw, leaf.h, rx = 0f, ry = -phi * leaf.dir, px = leaf.spineX, py = leaf.top + leaf.h / 2) {
                r.drawPage(c, front ?: PageSnap(-1, leaf.rect, PageSide.CENTER, null), style, rect = leaf.rect)
            }
        } else {
            val mirrored = if (leaf.dir > 0) RectF(leaf.spineX - leaf.w, leaf.top, leaf.spineX, leaf.top + leaf.h)
            else RectF(leaf.spineX, leaf.top, leaf.spineX + leaf.w, leaf.top + leaf.h)
            r.withCamera(c, cw, leaf.h, rx = 0f, ry = (180f - phi) * leaf.dir, px = leaf.spineX, py = leaf.top + leaf.h / 2) {
                r.drawPage(c, back ?: PageSnap(-1, mirrored, PageSide.CENTER, null), style, rect = mirrored)
            }
        }
    }
}
