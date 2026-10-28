package com.smartdocs.editor.ui.nve.imgedit

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * §14c spot healing by texture synthesis (patch copy): the round spot is filled from the best-matching patch of its
 * surroundings instead of one average colour. Candidate patches lie around the spot (16 directions, three distances, clear of the spot);
 * the one whose border ring looks most like the spot's own border ring (sum of squared differences) wins, its pixels
 * are copied in with the colour difference of the two rings added (so the patch blends into the local tone) and the
 * edge feathered. Works on a plain ARGB [IntArray] (pure, unit tested); [ImageOps.healDab] calls it on a bitmap region.
 */
object HealSynth {

    private const val DIRECTIONS = 16
    private val DISTANCES = floatArrayOf(2.5f, 3.0f, 3.6f)

    /** Region of a bitmap the synthesis needs around a spot of radius [r]: half its side. */
    fun reach(r: Int): Int = (r * (DISTANCES.last() + 1.5f)).roundToInt() + 2

    /**
     * Heals the spot of radius [r] at ([cx], [cy]) in [px] ([w] × [h], row-major ARGB), in place. [allow] (x, y) says
     * which pixels may change (the selection mask). Returns false when no candidate patch fits inside the image (the
     * caller then falls back to the ring average).
     */
    fun heal(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Int, allow: (Int, Int) -> Boolean = { _, _ -> true }): Boolean {
        if (r < 2 || w <= 0 || h <= 0) return false
        val ring = ringOffsets(r)
        if (ring.isEmpty()) return false
        val icx = cx.roundToInt(); val icy = cy.roundToInt()
        val outer = (r * 1.4f).roundToInt() + 1
        var best: Pair<Int, Int>? = null
        var bestScore = Long.MAX_VALUE
        for (d in DISTANCES) for (k in 0 until DIRECTIONS) {
            val a = 2.0 * Math.PI * k / DIRECTIONS
            val ox = (cos(a) * d * r).roundToInt(); val oy = (sin(a) * d * r).roundToInt()
            val sx = icx + ox; val sy = icy + oy
            // the whole source patch with its ring must lie inside the image
            if (sx - outer < 0 || sy - outer < 0 || sx + outer >= w || sy + outer >= h) continue
            var score = 0L
            var n = 0
            for ((dx, dy) in ring) {
                val tx = icx + dx; val ty = icy + dy
                if (tx !in 0 until w || ty !in 0 until h) continue
                score += dist2(px[ty * w + tx], px[(sy + dy) * w + sx + dx]); n++
                if (score >= bestScore) break
            }
            if (n == 0) continue
            if (score < bestScore) { bestScore = score; best = ox to oy }
        }
        val (ox, oy) = best ?: return false
        // tone correction: mean colour of the target ring minus the source ring, per channel
        val diff = IntArray(3); var n = 0
        for ((dx, dy) in ring) {
            val tx = icx + dx; val ty = icy + dy
            if (tx !in 0 until w || ty !in 0 until h) continue
            val t = px[ty * w + tx]; val s = px[(ty + oy) * w + tx + ox]
            diff[0] += ch(t, 16) - ch(s, 16); diff[1] += ch(t, 8) - ch(s, 8); diff[2] += ch(t, 0) - ch(s, 0); n++
        }
        val dr = if (n > 0) diff[0] / n else 0; val dg = if (n > 0) diff[1] / n else 0; val db = if (n > 0) diff[2] / n else 0
        val src = px.copyOf()     // read the source patch as it was (the spot may be near it)
        for (y in (icy - r).coerceAtLeast(0)..(icy + r).coerceAtMost(h - 1)) for (x in (icx - r).coerceAtLeast(0)..(icx + r).coerceAtMost(w - 1)) {
            val dd = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()).toFloat()
            if (dd > r || !allow(x, y)) continue
            val sx = x + ox; val sy = y + oy
            if (sx !in 0 until w || sy !in 0 until h) continue
            val s = src[sy * w + sx]
            val patch = (s and 0xFF000000.toInt()) or (clamp(ch(s, 16) + dr) shl 16) or (clamp(ch(s, 8) + dg) shl 8) or clamp(ch(s, 0) + db)
            val alpha = if (dd < r * 0.75f) 1f else (1f - (dd - r * 0.75f) / (r * 0.25f)).coerceIn(0f, 1f)
            px[y * w + x] = mix(src[y * w + x], patch, alpha)
        }
        return true
    }

    /** Offsets of the border ring (r < d ≤ 1.4 r) around a spot, sampled sparser for big spots. */
    internal fun ringOffsets(r: Int): List<Pair<Int, Int>> {
        val outer = r * 1.4f
        val step = (r / 12).coerceAtLeast(1)
        val out = ArrayList<Pair<Int, Int>>()
        val m = outer.toInt() + 1
        var y = -m
        while (y <= m) {
            var x = -m
            while (x <= m) {
                val d = sqrt((x * x + y * y).toDouble())
                if (d > r && d <= outer) out += x to y
                x += step
            }
            y += step
        }
        return out
    }

    private fun ch(c: Int, s: Int) = (c shr s) and 0xFF
    private fun clamp(v: Int) = v.coerceIn(0, 255)
    private fun dist2(a: Int, b: Int): Long {
        val r = ch(a, 16) - ch(b, 16); val g = ch(a, 8) - ch(b, 8); val bl = ch(a, 0) - ch(b, 0)
        return (r * r + g * g + bl * bl).toLong()
    }
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun c(s: Int) = (((a shr s) and 0xFF) * (1f - t) + ((b shr s) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return (c(24) shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }
}
