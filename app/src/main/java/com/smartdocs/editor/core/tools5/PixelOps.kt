package com.smartdocs.editor.core.tools5

/*
 * Pure pixel algorithms for the scan filters on ARGB IntArrays (Bitmap.getPixels layout). No Android imports, so
 * they run in desktop unit tests. Callers keep images ≤ ~3000 px on the long side.
 */
object PixelOps {

    fun lum(c: Int): Int = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun gray(px: IntArray): IntArray = IntArray(px.size) { lum(px[it]) }

    fun toGray(px: IntArray) { for (i in px.indices) { val l = lum(px[i]); px[i] = argb(l, l, l) } }

    /** Stretch levels so the [low]/[high] luminance percentiles map to 0 / 255 (same curve for all channels). */
    fun autoLevels(px: IntArray, low: Float = 0.01f, high: Float = 0.99f) {
        val hist = IntArray(256); px.forEach { hist[lum(it)]++ }
        val n = px.size.coerceAtLeast(1)
        var acc = 0; var lo = 0; var hi = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= n * low) { lo = i; break } }
        acc = 0
        for (i in 255 downTo 0) { acc += hist[i]; if (acc >= n * (1 - high)) { hi = i; break } }
        if (hi - lo < 16) return
        val lut = IntArray(256) { ((it - lo) * 255 / (hi - lo)).coerceIn(0, 255) }
        for (i in px.indices) { val c = px[i]; px[i] = argb(lut[(c shr 16) and 0xFF], lut[(c shr 8) and 0xFF], lut[c and 0xFF]) }
    }

    /** [brightness] −1..1 (adds ±128), [contrast] 0.3..3 around mid-grey. */
    fun brightnessContrast(px: IntArray, brightness: Float, contrast: Float) {
        if (brightness == 0f && contrast == 1f) return
        val lut = IntArray(256) { (((it - 128) * contrast) + 128 + brightness * 128).toInt().coerceIn(0, 255) }
        for (i in px.indices) { val c = px[i]; px[i] = argb(lut[(c shr 16) and 0xFF], lut[(c shr 8) and 0xFF], lut[c and 0xFF]) }
    }

    /** Multiply saturation by [k] (1 = unchanged). */
    fun saturate(px: IntArray, k: Float) {
        for (i in px.indices) {
            val c = px[i]; val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val l = lum(c)
            px[i] = argb((l + (r - l) * k).toInt(), (l + (g - l) * k).toInt(), (l + (b - l) * k).toInt())
        }
    }

    /**
     * Background (paper / board) estimate: maximum luminance per block, dilated once, bilinear-upsampled.
     * Returns one value per pixel (0..255).
     */
    fun background(px: IntArray, w: Int, h: Int, block: Int = maxOf(16, maxOf(w, h) / 40)): IntArray {
        val bw = (w + block - 1) / block; val bh = (h + block - 1) / block
        val grid = IntArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            // a high percentile instead of the max: robust against specks of glare
            val hist = IntArray(256); var n = 0
            val y1 = minOf(h, (by + 1) * block); val x1 = minOf(w, (bx + 1) * block)
            var y = by * block
            while (y < y1) { var x = bx * block; while (x < x1) { hist[lum(px[y * w + x])]++; n++; x += 2 }; y += 2 }
            var acc = 0; var v = 255
            for (i in 255 downTo 0) { acc += hist[i]; if (acc >= n / 10 + 1) { v = i; break } }
            grid[by * bw + bx] = v
        }
        val dil = IntArray(grid.size)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var m = 0
            for (dy in -1..1) for (dx in -1..1) { val yy = by + dy; val xx = bx + dx; if (yy in 0 until bh && xx in 0 until bw) m = maxOf(m, grid[yy * bw + xx]) }
            dil[by * bw + bx] = (m + grid[by * bw + bx]) / 2
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val gy = ((y + 0.5f) / block - 0.5f).coerceIn(0f, (bh - 1).toFloat()); val y0 = gy.toInt(); val y1 = minOf(bh - 1, y0 + 1); val fy = gy - y0
            for (x in 0 until w) {
                val gx = ((x + 0.5f) / block - 0.5f).coerceIn(0f, (bw - 1).toFloat()); val x0 = gx.toInt(); val x1 = minOf(bw - 1, x0 + 1); val fx = gx - x0
                val a = dil[y0 * bw + x0] * (1 - fx) + dil[y0 * bw + x1] * fx
                val b = dil[y1 * bw + x0] * (1 - fx) + dil[y1 * bw + x1] * fx
                out[y * w + x] = (a * (1 - fy) + b * fy).toInt().coerceIn(1, 255)
            }
        }
        return out
    }

    /** Divide by the background so paper becomes white: removes shadows and uneven light (shadow / whiteboard filters). */
    fun normalizeBackground(px: IntArray, w: Int, h: Int) {
        val bg = background(px, w, h)
        for (i in px.indices) {
            val c = px[i]; val k = 255f / bg[i]
            px[i] = argb((((c shr 16) and 0xFF) * k).toInt(), (((c shr 8) and 0xFF) * k).toInt(), ((c and 0xFF) * k).toInt())
        }
    }

    /** Sauvola adaptive threshold → pure black / white. [window] in px (default: 1/30 of the long side). */
    fun sauvola(px: IntArray, w: Int, h: Int, k: Double = 0.2, window: Int = maxOf(15, maxOf(w, h) / 30)) {
        val g = gray(px)
        // statistics on a reduced grid for big pages (keeps the two integral images small)
        val f = if (w.toLong() * h > 1_500_000L) 2 else 1
        val rw = maxOf(1, w / f); val rh = maxOf(1, h / f)
        val iw = rw + 1
        val sum = LongArray(iw * (rh + 1)); val sq = LongArray(iw * (rh + 1))
        for (y in 0 until rh) {
            var rs = 0L; var rq = 0L
            for (x in 0 until rw) {
                val v = g[minOf(h - 1, y * f) * w + minOf(w - 1, x * f)]; rs += v; rq += v.toLong() * v
                sum[(y + 1) * iw + x + 1] = sum[y * iw + x + 1] + rs
                sq[(y + 1) * iw + x + 1] = sq[y * iw + x + 1] + rq
            }
        }
        val r = maxOf(1, window / 2 / f)
        for (y in 0 until h) {
            val ry = minOf(rh - 1, y / f)
            val y0 = maxOf(0, ry - r); val y1 = minOf(rh, ry + r + 1)
            for (x in 0 until w) {
                val rx = minOf(rw - 1, x / f)
                val x0 = maxOf(0, rx - r); val x1 = minOf(rw, rx + r + 1)
                val n = ((y1 - y0) * (x1 - x0)).toDouble()
                val s = sum[y1 * iw + x1] - sum[y0 * iw + x1] - sum[y1 * iw + x0] + sum[y0 * iw + x0]
                val q = sq[y1 * iw + x1] - sq[y0 * iw + x1] - sq[y1 * iw + x0] + sq[y0 * iw + x0]
                val mean = s / n
                val sd = kotlin.math.sqrt(maxOf(0.0, q / n - mean * mean))
                val t = mean * (1 + k * (sd / 128.0 - 1))
                px[y * w + x] = if (g[y * w + x] > t) -1 else 0xFF000000.toInt()
            }
        }
    }

    /**
     * Book binding: the darkest smoothed column in the middle band (35..65 % of the width), from the luminance
     * profile of the central rows. Returns the x of the gutter.
     */
    fun bindingColumn(px: IntArray, w: Int, h: Int): Int {
        if (w < 8) return w / 2
        val prof = DoubleArray(w)
        val ys = (h / 5) until maxOf(h / 5 + 1, h * 4 / 5)
        for (x in 0 until w) { var s = 0L; var n = 0; var y = ys.first; while (y <= ys.last) { s += lum(px[y * w + x]); n++; y += 2 }; prof[x] = if (n > 0) s.toDouble() / n else 255.0 }
        val win = maxOf(1, w / 100)
        val from = (w * 0.35).toInt(); val to = (w * 0.65).toInt()
        var best = w / 2; var bestV = Double.MAX_VALUE
        for (x in from..to) {
            var s = 0.0; var n = 0
            for (d in -win..win) { val xx = x + d; if (xx in 0 until w) { s += prof[xx]; n++ } }
            val v = s / n
            if (v < bestV) { bestV = v; best = x }
        }
        return best
    }

    /** True when the picture is essentially two-tone (stored lossless 1-bit friendly). */
    fun isBilevel(px: IntArray): Boolean = px.all { val c = it and 0xFFFFFF; c == 0 || c == 0xFFFFFF }
}
