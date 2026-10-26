package com.smartdocs.editor.core.pageflip

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Cylinder-projection page curl (pure maths, unit-tested).
 *
 * Leaf space: pixels, origin at the top of the bound edge (spine), x towards the free edge (0..w), y down (0..h).
 * The leaf is grabbed at C0 = (w, gy·h) and that point is moved to the finger F. The fold axis is the line through
 * A with unit normal n (pointing to the free side); a point at signed distance d = (p − A)·n > 0 is wrapped around a
 * cylinder of radius r lying on the page: θ = d / r, d' = r·sinθ, z = r(1 − cosθ); beyond half a turn (d > πr) it lies
 * flat on the back, d' = −(d − πr), z = 2r. With r → 0 this is a plain fold (reflection about the axis).
 * A is placed so the grabbed point lands on the finger ((L − πr) / 2 from F along n) and is then pushed towards the
 * free edge if needed so the bound edge never lifts (the page stays attached to the spine).
 */
object CurlMath {
    /** Fold axis point (ax, ay), unit normal (nx, ny) towards the free edge, cylinder radius r (px). */
    class Fold(val ax: Float, val ay: Float, val nx: Float, val ny: Float, val r: Float) {
        /** Signed distance of a leaf point from the axis. */
        fun d(x: Float, y: Float): Float = (x - ax) * nx + (y - ay) * ny
    }

    enum class Side { FRONT, BACK, BOTH }

    private const val PIF = PI.toFloat()

    /** Turn progress 0..1 of a finger at leaf x [fx] (0 = flat, 1 = turned onto the other side of the spine). */
    fun progressOf(fx: Float, w: Float): Float = ((w - fx) / (2f * w)).coerceIn(0f, 1f)

    /** Cylinder radius: [curvature] 0..1 maps to 4..24 % of the leaf width, shrinking to 0 at both ends of the turn. */
    fun radiusFor(t: Float, w: Float, curvature: Float): Float {
        val base = w * (0.04f + 0.2f * curvature.coerceIn(0f, 1f))
        return base * (4f * min(t, 1f - t)).coerceIn(0f, 1f)
    }

    /**
     * Finger position on an automatic turn (tap, key, settle) at progress [t]: straight along the leaf with a small
     * arc ([lift] in leaf heights) towards the middle when a corner is grabbed.
     */
    fun autoPath(t: Float, gy: Float, w: Float, h: Float, lift: Float, out: FloatArray) {
        val x = w - 2f * w * t
        val arc = when {
            gy > 0.7f -> -1f
            gy < 0.3f -> 1f
            else -> 0f
        }
        out[0] = x
        out[1] = gy * h + arc * lift * h * sin(PIF * t.coerceIn(0f, 1f))
    }

    /**
     * Keep the finger where the leaf can physically be: no farther from either spine corner than the grabbed point is
     * (otherwise the leaf would have to tear off the spine). Result in [out].
     */
    fun constrain(fx: Float, fy: Float, cx: Float, cy: Float, h: Float, out: FloatArray) {
        var x = min(fx, cx); var y = fy
        repeat(2) {
            for (sy in floatArrayOf(0f, h)) {
                val r = hypot(cx, cy - sy)
                val dx = x; val dy = y - sy
                val d = hypot(dx, dy)
                if (d > r && d > 0f) { x = dx * r / d; y = sy + dy * r / d }
            }
        }
        out[0] = x; out[1] = y
    }

    /** Fold for grabbed point (cx, cy) moved to (fx, fy); null when the leaf is flat. */
    fun fold(cx: Float, cy: Float, fx: Float, fy: Float, r: Float, h: Float): Fold? {
        val dx = cx - fx; val dy = cy - fy
        val len = hypot(dx, dy)
        if (len < 0.5f) return null
        val nx = dx / len; val ny = dy / len
        val rr = max(0f, r)
        val a = (len - PIF * rr) / 2f
        var ax = fx + nx * a; var ay = fy + ny * a
        // the bound edge (x = 0) must stay on the flat side
        val dTop = (0f - ax) * nx + (0f - ay) * ny
        val dBot = (0f - ax) * nx + (h - ay) * ny
        val m = max(dTop, dBot)
        if (m > 0f) { ax += nx * m; ay += ny * m }
        return Fold(ax, ay, nx, ny, rr)
    }

    /**
     * Deform one leaf point. [side] FRONT clamps points of the back half onto the cylinder crest (and BACK the
     * reverse) so two meshes of the same grid draw front and back without overlapping each other.
     * out = [x', y', z, θ].
     */
    fun deform(x: Float, y: Float, f: Fold, side: Side, out: FloatArray) {
        val d = f.d(x, y)
        val half = if (f.r < 0.5f) 0f else PIF * f.r / 2f
        val dd = when (side) {
            Side.FRONT -> min(d, half)
            Side.BACK -> max(d, half)
            Side.BOTH -> d
        }
        val dPrime: Float; val z: Float; val th: Float
        if (dd <= 0f) { dPrime = dd; z = 0f; th = 0f }
        else if (f.r < 0.5f) { dPrime = -dd; z = 0f; th = PIF }
        else {
            val t = dd / f.r
            if (t <= PIF) { dPrime = f.r * sin(t); z = f.r * (1f - cos(t)); th = t }
            else { dPrime = -(dd - PIF * f.r); z = 2f * f.r; th = PIF }
        }
        out[0] = x + f.nx * (dPrime - d)
        out[1] = y + f.ny * (dPrime - d)
        out[2] = z
        out[3] = th
    }

    /** Lighting factor 0..1 of a vertex at angle [th] on the given side (1 = unlit page colour). */
    fun light(th: Float, back: Boolean, intensity: Float): Float {
        val i = intensity.coerceIn(0f, 1f)
        return if (!back) 1f - 0.5f * i * (1f - cos(min(th, PIF / 2f)))
        else 1f - 0.3f * i * (1f + cos(max(th, PIF / 2f)))
    }

    /**
     * Fill a drawBitmapMesh vertex array ((cols+1)·(rows+1)·2 floats) and a light array ((cols+1)·(rows+1)) for a
     * leaf of w×h px. Bitmap column fraction fx maps to leaf u = fx, or 1 − fx when [flipU] (the texture's left edge
     * is the free edge). Screen = (originX + dirX·x', originY + y'), plus a small perspective growth with z.
     * Returns false when nothing of this [side] is visible (e.g. BACK of a flat leaf).
     */
    fun buildMesh(
        w: Float, h: Float, cols: Int, rows: Int, fold: Fold?, side: Side, flipU: Boolean,
        originX: Float, dirX: Float, originY: Float, intensity: Float,
        verts: FloatArray, light: FloatArray,
    ): Boolean {
        val tmp = FloatArray(4)
        val half = if (fold == null || fold.r < 0.5f) 0f else PIF * fold.r / 2f
        var any = false
        var k = 0
        for (j in 0..rows) {
            val y = h * j / rows
            for (i in 0..cols) {
                val fx = i.toFloat() / cols
                val x = w * (if (flipU) 1f - fx else fx)
                var px = x; var py = y; var z = 0f; var th = 0f
                if (fold != null) {
                    deform(x, y, fold, side, tmp)
                    px = tmp[0]; py = tmp[1]; z = tmp[2]; th = tmp[3]
                    val d = fold.d(x, y)
                    if (side == Side.BACK && d > half) any = true
                    if (side != Side.BACK && d <= half) any = true
                } else if (side != Side.BACK) any = true
                val s = 1f + z / (w * 5f)
                px = w / 2f + (px - w / 2f) * s
                py = h / 2f + (py - h / 2f) * s
                verts[k * 2] = originX + dirX * px
                verts[k * 2 + 1] = originY + py
                light[k] = light(th, side == Side.BACK, intensity)
                k++
            }
        }
        return any
    }
}
