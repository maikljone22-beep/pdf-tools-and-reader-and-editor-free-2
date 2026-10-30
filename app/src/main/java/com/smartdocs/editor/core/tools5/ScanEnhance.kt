package com.smartdocs.editor.core.tools5

import kotlin.math.max
import kotlin.math.min

/**
 * "Smart enhance" for scanned pages (pure Kotlin on ARGB pixels, JVM-tested): the paper becomes clean white, the
 * text becomes darker and sharper, and pictures on the page keep their colours and tones.
 *
 * 1. [PixelOps.normalizeBackground] evens out the light (shadows, lamp gradients) so the paper is ~white.
 * 2. The page is split into blocks; a block that is mostly mid-tones or coloured is a *picture* block.
 * 3. Text areas: near-white noise → pure white, ink pushed darker with an S-curve, then an unsharp mask on the
 *    strokes. Picture areas: only a gentle level stretch, no whitening, no extra sharpening.
 * Block edges are blended so pictures do not get hard seams.
 */
object ScanEnhance {

    /** In place. [strength] 0..1 scales the darkening and sharpening of the text. */
    fun enhance(px: IntArray, w: Int, h: Int, strength: Float = 0.7f) {
        if (w < 8 || h < 8) return
        val bg = paperMap(px, w, h)
        val pic = pictureWeight(px, w, h)             // 0 = text/paper, 1 = picture, per pixel (blended); on the raw picture
        // light evened out (text areas): every pixel scaled so its local paper becomes white
        val nrm = IntArray(px.size) { i ->
            val c = px[i]; val f = 255f / bg[i]
            argb((((c shr 16) and 0xFF) * f).toInt(), (((c shr 8) and 0xFF) * f).toInt(), ((c and 0xFF) * f).toInt())
        }
        val sharp = ScanFilters.sharpen(nrm, w, h, 0.6f + 0.8f * strength)
        val k = strength.coerceIn(0f, 1f)
        for (i in px.indices) {
            val p = pic[i]
            val text = if (p < 0.99f) textTone(sharp[i], k) else 0
            px[i] = if (p <= 0.01f) text else if (p >= 0.99f) gentle(px[i]) else blend(text, gentle(px[i]), p)
        }
    }

    /**
     * Local paper brightness per pixel: the page's background map, never darker than 70 % of the lit paper so a
     * picture's own tones are not mistaken for shadow (which would wash the picture out).
     */
    fun paperMap(px: IntArray, w: Int, h: Int): IntArray {
        val bg = PixelOps.background(px, w, h)
        val lum = IntArray(bg.size) { bg[it] and 0xFF }
        val sorted = lum.copyOf().also { it.sort() }
        val paper = sorted[sorted.size * 9 / 10].coerceAtLeast(1)
        val floor = (paper * 0.7f).toInt()
        return IntArray(lum.size) { max(floor, lum[it]).coerceAtLeast(1) }
    }

    /** Text / paper tone curve: paper and faint noise → white, ink → darker; colour hue kept (stamps, blue ink). */
    private fun textTone(c: Int, k: Float): Int {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val l = PixelOps.lum(c)
        val white = 215 - (20 * k).toInt()            // above this the pixel is paper
        if (l >= white) return 0xFFFFFFFF.toInt()
        // gamma curve on the luminance: ink much darker, anti-aliased edges stay smooth
        val t = l / white.toFloat()
        val target = Math.pow(t.toDouble(), 1.0 + 1.2 * k).toFloat() * white * (1 - 0.25f * k)
        val f = if (l == 0) 0f else target / l
        return argb((r * f).toInt(), (g * f).toInt(), (b * f).toInt())
    }

    /** Picture tones: a light stretch only (keeps photos natural). */
    private fun gentle(c: Int): Int {
        fun one(v: Int) = ((v - 8) * 255 / 239).coerceIn(0, 255)
        return argb(one((c shr 16) and 0xFF), one((c shr 8) and 0xFF), one(c and 0xFF))
    }

    /**
     * Per-pixel picture weight. A block is a picture when its tones are *continuous* (many pixels in the middle of
     * its tonal range, unlike text, which is paper + ink with little in between) or clearly coloured. Smooth blocks
     * (plain or shadowed paper) are never pictures. The block grid is cleaned (a picture block needs picture
     * neighbours), grown by one block so picture edges are included, and bilinearly interpolated so edges blend.
     * [bg] is accepted for API symmetry and unused.
     */
    fun pictureWeight(px: IntArray, w: Int, h: Int, @Suppress("UNUSED_PARAMETER") bg: IntArray? = null): FloatArray {
        val bs = max(16, max(w, h) / 48)
        val bw = (w + bs - 1) / bs; val bh = (h + bs - 1) / bs
        val grid = FloatArray(bw * bh)
        val lums = IntArray(bs * bs)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var n = 0; var satSum = 0L
            val y1 = min(h, (by + 1) * bs); val x1 = min(w, (bx + 1) * bs)
            var y = by * bs
            while (y < y1) {
                var x = bx * bs
                while (x < x1) {
                    val c = px[y * w + x]
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    lums[n++] = PixelOps.lum(c)
                    satSum += max(r, max(g, b)) - min(r, min(g, b))
                    x += 2
                }
                y += 2
            }
            if (n < 8) continue
            java.util.Arrays.sort(lums, 0, n)
            val p5 = lums[n / 20]; val p95 = lums[n - 1 - n / 20]
            val range = p95 - p5
            val meanSat = satSum / n
            val coloured = meanSat > 45 && range > 20
            if (range < 40 && !coloured) continue                      // smooth: paper (lit or shadowed)
            val lo = p5 + range / 4; val hi = p5 + range * 3 / 4
            var mid = 0
            for (i in 0 until n) if (lums[i] in lo..hi) mid++
            if (coloured || mid > n * 0.4f) grid[by * bw + bx] = 1f
        }
        // a picture block needs picture neighbours (lone blocks are bold headings or stamps → text), then grow by one
        val solid = FloatArray(grid.size)
        for (by in 0 until bh) for (bx in 0 until bw) {
            if (grid[by * bw + bx] == 0f) continue
            var nb = 0
            for (dy in -1..1) for (dx in -1..1) if (dx != 0 || dy != 0) {
                val yy = by + dy; val xx = bx + dx
                if (yy in 0 until bh && xx in 0 until bw && grid[yy * bw + xx] > 0f) nb++
            }
            solid[by * bw + bx] = if (nb >= 2) 1f else 0f
        }
        val grown = FloatArray(grid.size)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var any = false
            for (dy in -1..1) for (dx in -1..1) { val yy = by + dy; val xx = bx + dx; if (yy in 0 until bh && xx in 0 until bw && solid[yy * bw + xx] > 0f) any = true }
            grown[by * bw + bx] = if (any) 1f else 0f
        }
        // bilinear interpolation of the block centres
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val gy = ((y + 0.5f) / bs - 0.5f).coerceIn(0f, (bh - 1).toFloat())
            val y0 = gy.toInt(); val y1 = min(bh - 1, y0 + 1); val fy = gy - y0
            for (x in 0 until w) {
                val gx = ((x + 0.5f) / bs - 0.5f).coerceIn(0f, (bw - 1).toFloat())
                val x0 = gx.toInt(); val x1 = min(bw - 1, x0 + 1); val fx = gx - x0
                val top = grown[y0 * bw + x0] * (1 - fx) + grown[y0 * bw + x1] * fx
                val bot = grown[y1 * bw + x0] * (1 - fx) + grown[y1 * bw + x1] * fx
                out[y * w + x] = top * (1 - fy) + bot * fy
            }
        }
        return out
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun one(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt()
        return argb(one(16), one(8), one(0))
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
