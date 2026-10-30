package com.smartdocs.editor.core.tools5

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Flattens a curved page (a book or notebook photographed open): the text lines are found, a smooth vertical
 * displacement field D(x, y) is fitted to their bends, and the picture is resampled so every line becomes straight.
 * Pure Kotlin on ARGB pixels (JVM-tested). It also removes a small overall skew.
 *
 * Model: D(x, y) = Σ_{k=1..3} (a_k + b_k·v + c_k·v²)·u^k with u = (x − centre) / width, v = y / height, so the page
 * centre column is never moved. It is fitted by least squares to points sampled along the detected lines (each line
 * gets its own height at the centre column, refined in a few passes).
 *
 * Nothing changes (returns false) when fewer than [MIN_LINES] text lines are found, when the page is already flat,
 * or when the fit does not explain the bends well enough — a photo or a page without text stays as it was.
 * Not handled: the horizontal squeeze near a book's spine, and pages whose lines are not roughly horizontal.
 */
object PageDewarp {

    private const val MIN_LINES = 3
    private const val WORK = 1000          // analysis resolution (long side, px)

    /** Fitted warp in normalised units; [eval] gives the displacement as a fraction of the page height. */
    class Warp(private val c: DoubleArray) {
        fun eval(u: Double, v: Double): Double {
            var d = 0.0; var uk = 1.0
            for (k in 0 until 3) { uk *= u; d += (c[k * 3] + c[k * 3 + 1] * v + c[k * 3 + 2] * v * v) * uk }
            return d
        }
    }

    /** Flattens [px] (w × h ARGB) in place; true when it changed the picture. */
    fun flatten(px: IntArray, w: Int, h: Int): Boolean {
        if (w < 64 || h < 64) return false
        val warp = detect(px, w, h) ?: return false
        remap(px, w, h, warp)
        return true
    }

    /** The warp of the page, or null when no reliable curvature was found. */
    fun detect(px: IntArray, w: Int, h: Int): Warp? {
        val s = min(1.0, WORK.toDouble() / max(w, h))
        val sw = max(1, (w * s).toInt()); val sh = max(1, (h * s).toInt())
        // grey at the analysis size
        val g = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val c = px[min(h - 1, (y / s).toInt()) * w + min(w - 1, (x / s).toInt())]
            g[y * sw + x] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        val ink = binarize(g, sw, sh)
        val smear = smearRows(ink, sw, sh, max(4, sw / 60))
        val lines = textLines(smear, ink, sw, sh)
        if (lines.size < MIN_LINES) return null
        return fit(lines, sw, sh)
    }

    // ------------------------------------------------------------------ analysis

    /** Dark ink on a lighter local background (mean filter on an integral image). */
    private fun binarize(g: IntArray, w: Int, h: Int): BooleanArray {
        val iw = w + 1
        val sum = LongArray(iw * (h + 1))
        for (y in 0 until h) { var rs = 0L; for (x in 0 until w) { rs += g[y * w + x]; sum[(y + 1) * iw + x + 1] = sum[y * iw + x + 1] + rs } }
        val r = max(8, max(w, h) / 40)
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val n = (y1 - y0) * (x1 - x0)
                val mean = (sum[y1 * iw + x1] - sum[y0 * iw + x1] - sum[y1 * iw + x0] + sum[y0 * iw + x0]).toDouble() / n
                val v = g[y * w + x]
                out[y * w + x] = v < mean * 0.82 && v < 200
            }
        }
        return out
    }

    /** Joins the letters and words of a row into one band (run-length smoothing with gaps up to [gap]). */
    private fun smearRows(ink: BooleanArray, w: Int, h: Int, gap: Int): BooleanArray {
        val out = ink.copyOf()
        for (y in 0 until h) {
            var last = -1
            for (x in 0 until w) if (ink[y * w + x]) {
                if (last >= 0 && x - last in 2..gap) for (k in last + 1 until x) out[y * w + k] = true
                last = x
            }
        }
        return out
    }

    /** A detected text line: points (x, y) along its centre, in analysis pixels. */
    private class Line(val xs: DoubleArray, val ys: DoubleArray)

    /** Long thin bands → their centre lines sampled in columns. */
    private fun textLines(mask: BooleanArray, ink: BooleanArray, w: Int, h: Int): List<Line> {
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Line>()
        var next = 0
        val bin = max(4, w / 50)
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            next++
            var sp = 0; stack[sp++] = start; label[start] = next
            var minX = w; var maxX = -1; var minY = h; var maxY = -1; var count = 0
            val members = ArrayList<Int>()
            while (sp > 0) {
                val p = stack[--sp]; members += p; count++
                val x = p % w; val y = p / w
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0) { val q = p - 1; if (mask[q] && label[q] == 0) { label[q] = next; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (mask[q] && label[q] == 0) { label[q] = next; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (mask[q] && label[q] == 0) { label[q] = next; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (mask[q] && label[q] == 0) { label[q] = next; stack[sp++] = q } }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            // a text line: wide, not too tall, much wider than tall, reasonably filled
            if (bw < w / 4 || bh > h / 12 || bw < bh * 6 || count < bw * 2) continue
            // centre of the ink in each column bin
            val sumY = DoubleArray((bw + bin - 1) / bin); val n = IntArray(sumY.size)
            for (p in members) {
                if (!ink[p]) continue
                val b = (p % w - minX) / bin
                sumY[b] += (p / w).toDouble(); n[b]++
            }
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (b in sumY.indices) if (n[b] >= 3) { xs += minX + (b + 0.5) * bin; ys += sumY[b] / n[b] }
            if (xs.size >= 5) out += Line(xs.toDoubleArray(), ys.toDoubleArray())
        }
        return out
    }

    /** Least-squares fit of the warp; null when the page is flat or the fit is poor. */
    private fun fit(lines: List<Line>, w: Int, h: Int): Warp? {
        val cx = w / 2.0
        val baseY = DoubleArray(lines.size) { i -> lines[i].ys.average() }
        var coef = DoubleArray(9)
        val terms = if (lines.size >= 4) 3 else 2                 // the v² terms need enough lines
        repeat(4) {
            // normal equations for the 9 (or 6) coefficients
            val m = terms * 3
            val ata = Array(m) { DoubleArray(m) }; val atb = DoubleArray(m)
            val row = DoubleArray(m)
            lines.forEachIndexed { i, l ->
                val v = baseY[i] / h
                for (j in l.xs.indices) {
                    val u = (l.xs[j] - cx) / w
                    var uk = 1.0; var r = 0
                    for (k in 0 until 3) { uk *= u; for (t in 0 until terms) { row[r++] = uk * pow(v, t) } }
                    val target = (l.ys[j] - baseY[i]) / h
                    for (a in 0 until m) { atb[a] += row[a] * target; for (b in 0 until m) ata[a][b] += row[a] * row[b] }
                }
            }
            for (a in 0 until m) ata[a][a] += 1e-9
            val sol = solve(ata, atb) ?: return null
            coef = DoubleArray(9)
            var r = 0
            for (k in 0 until 3) for (t in 0 until terms) coef[k * 3 + t] = sol[r++]
            // each line's height at the centre column, given the warp
            val warp = Warp(coef)
            lines.forEachIndexed { i, l ->
                var acc = 0.0
                for (j in l.xs.indices) acc += l.ys[j] - warp.eval((l.xs[j] - cx) / w, baseY[i] / h) * h
                baseY[i] = acc / l.xs.size
            }
        }
        val warp = Warp(coef)
        // quality: residual spread of the lines before and after
        var before = 0.0; var after = 0.0; var n = 0; var maxD = 0.0
        lines.forEachIndexed { i, l ->
            val mean = l.ys.average()
            for (j in l.xs.indices) {
                val d = warp.eval((l.xs[j] - cx) / w, baseY[i] / h) * h
                before += (l.ys[j] - mean) * (l.ys[j] - mean)
                after += (l.ys[j] - baseY[i] - d) * (l.ys[j] - baseY[i] - d)
                maxD = max(maxD, abs(d)); n++
            }
        }
        before = sqrt(before / n); after = sqrt(after / n)
        if (maxD < max(1.5, h * 0.004)) return null                 // already flat
        if (after > before * 0.6) return null                       // the model does not explain the bends
        return warp
    }

    private fun pow(v: Double, t: Int) = when (t) { 0 -> 1.0; 1 -> v; else -> v * v }

    /** Gaussian elimination with partial pivoting; null when singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-14) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                if (f != 0.0) for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    // ------------------------------------------------------------------ resampling

    /** Output pixel (x, y) takes the source pixel (x, y + D(x, y)), bilinear in y; edges are repeated. */
    private fun remap(px: IntArray, w: Int, h: Int, warp: Warp) {
        val src = px.copyOf()
        val cx = w / 2.0
        val dcol = DoubleArray(h)
        for (x in 0 until w) {
            val u = (x - cx) / w
            for (y in 0 until h) dcol[y] = warp.eval(u, y.toDouble() / h) * h
            for (y in 0 until h) {
                val sy = (y + dcol[y]).coerceIn(0.0, (h - 1).toDouble())
                val y0 = sy.toInt(); val y1 = min(h - 1, y0 + 1); val f = sy - y0
                px[y * w + x] = mix(src[y0 * w + x], src[y1 * w + x], f)
            }
        }
    }

    private fun mix(a: Int, b: Int, f: Double): Int {
        if (f < 1e-3) return a
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun one(s: Int) = (ch(a, s) * (1 - f) + ch(b, s) * f + 0.5).toInt()
        return (0xFF shl 24) or (one(16) shl 16) or (one(8) shl 8) or one(0)
    }
}
