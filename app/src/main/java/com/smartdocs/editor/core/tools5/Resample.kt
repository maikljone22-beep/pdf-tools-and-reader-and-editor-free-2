package com.smartdocs.editor.core.tools5

/** Foxit / Acrobat downsampling methods. OFF keeps the resolution (only re-compression applies). */
enum class Downsample { OFF, AVERAGE, SUBSAMPLE, BICUBIC }

/**
 * Image resampling on ARGB pixel arrays (pure Kotlin, unit-tested): box average, nearest-pixel subsampling and
 * separable bicubic (Catmull-Rom, a = -0.5) whose kernel widens with the reduction so it does not alias.
 */
object Resample {

    fun resize(px: IntArray, w: Int, h: Int, nw: Int, nh: Int, method: Downsample): IntArray = when (method) {
        Downsample.OFF -> px
        Downsample.SUBSAMPLE -> subsample(px, w, h, nw, nh)
        Downsample.AVERAGE -> average(px, w, h, nw, nh)
        Downsample.BICUBIC -> bicubic(px, w, h, nw, nh)
    }

    fun subsample(px: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray = IntArray(nw * nh) { i ->
        val x = i % nw; val y = i / nw
        px[minOf(h - 1, (y * h + h / 2) / nh) * w + minOf(w - 1, (x * w + w / 2) / nw)]
    }

    fun average(px: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = y * h / nh; val y1 = maxOf(y0 + 1, (y + 1) * h / nh)
            for (x in 0 until nw) {
                val x0 = x * w / nw; val x1 = maxOf(x0 + 1, (x + 1) * w / nw)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val c = px[yy * w + xx]
                    a += (c ushr 24); r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                out[y * nw + x] = ((a / n).toInt() shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return out
    }

    private fun cubic(x0: Double): Double {
        val x = kotlin.math.abs(x0); val a = -0.5
        return when {
            x <= 1 -> (a + 2) * x * x * x - (a + 3) * x * x + 1
            x < 2 -> a * x * x * x - 5 * a * x * x + 8 * a * x - 4 * a
            else -> 0.0
        }
    }

    /** Weights of one axis: for each output position, the first source index and the normalised weights. */
    private class Axis(val start: IntArray, val weights: Array<DoubleArray>)

    private fun axis(src: Int, dst: Int): Axis {
        val scale = dst.toDouble() / src
        val support = if (scale < 1) 2.0 / scale else 2.0
        val start = IntArray(dst)
        val weights = Array(dst) { o ->
            val center = (o + 0.5) / scale - 0.5
            val lo = kotlin.math.floor(center - support).toInt() + 1
            val hi = kotlin.math.floor(center + support).toInt()
            val ws = DoubleArray(hi - lo + 1) { k -> val d = (lo + k - center) * (if (scale < 1) scale else 1.0); cubic(d) }
            val sum = ws.sum().takeIf { it != 0.0 } ?: 1.0
            for (k in ws.indices) ws[k] /= sum
            start[o] = lo
            ws
        }
        return Axis(start, weights)
    }

    fun bicubic(px: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val ax = axis(w, nw); val ay = axis(h, nh)
        // one channel at a time (horizontal pass into one float plane, then vertical): memory nw×h floats, not 4×
        val tmp = FloatArray(nw * h)
        val out = IntArray(nw * nh)
        for (shift in intArrayOf(24, 16, 8, 0)) {
            for (y in 0 until h) for (x in 0 until nw) {
                val ws = ax.weights[x]; val s = ax.start[x]
                var v = 0.0
                for (k in ws.indices) v += ((px[y * w + (s + k).coerceIn(0, w - 1)] ushr shift) and 0xFF) * ws[k]
                tmp[y * nw + x] = v.toFloat()
            }
            for (y in 0 until nh) {
                val ws = ay.weights[y]; val s = ay.start[y]
                for (x in 0 until nw) {
                    var v = 0.0
                    for (k in ws.indices) v += tmp[(s + k).coerceIn(0, h - 1) * nw + x] * ws[k]
                    out[y * nw + x] = out[y * nw + x] or ((v + 0.5).toInt().coerceIn(0, 255) shl shift)
                }
            }
        }
        return out
    }
}
