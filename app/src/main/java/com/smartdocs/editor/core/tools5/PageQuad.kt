package com.smartdocs.editor.core.tools5

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Page edge detection and perspective correction for the SmartDocs camera (pure Kotlin on ARGB pixels, JVM-tested).
 *
 * [detect] finds the four corners of the sheet in a (small) camera frame: the frame is split into "paper" and
 * "background" by an Otsu threshold on a blurred luminance (the side under the frame centre is the paper, so a dark
 * page on a light desk works too), the connected region under the centre is traced, and its extreme boundary points
 * give the corners. The quad is then refined onto the strongest luminance edges nearby. Corners are returned
 * normalised to 0..1 so the same quad applies to the preview, the analysis frame and the full-resolution photo.
 *
 * [warp] maps the quad of a full-resolution photo onto an upright rectangle (homography, bilinear sampling).
 */
object PageQuad {

    /** Corners in order top-left, top-right, bottom-right, bottom-left; coordinates 0..1 of the frame. */
    class Quad(val x: FloatArray, val y: FloatArray) {
        init { require(x.size == 4 && y.size == 4) }
        fun area(): Float {
            var s = 0f
            for (i in 0 until 4) { val j = (i + 1) % 4; s += x[i] * y[j] - x[j] * y[i] }
            return abs(s) / 2f
        }
        /** Largest corner movement against [o] (0..1 units). */
        fun distance(o: Quad): Float { var d = 0f; for (i in 0 until 4) d = max(d, hypot(x[i] - o.x[i], y[i] - o.y[i])); return d }
        fun scaled(w: Int, h: Int): Quad = Quad(FloatArray(4) { x[it] * w }, FloatArray(4) { y[it] * h })
        companion object {
            val FULL = Quad(floatArrayOf(0f, 1f, 1f, 0f), floatArrayOf(0f, 0f, 1f, 1f))
        }
    }

    /** Minimum share of the frame a page must cover to count as found. */
    const val MIN_AREA = 0.12f

    /**
     * The page in a frame [w]×[h] (ARGB [px]; ~300–640 px on the long side is plenty), or null when no sheet-like
     * region is found.
     */
    fun detect(px: IntArray, w: Int, h: Int): Quad? {
        if (w < 32 || h < 32) return null
        val g = blur(PixelOps.gray(px), w, h)
        val t = otsu(g)
        // the side the centre belongs to is the paper
        var bright = 0; var n = 0
        for (y in h * 2 / 5 until h * 3 / 5 step 2) for (x in w * 2 / 5 until w * 3 / 5 step 2) { if (g[y * w + x] > t) bright++; n++ }
        val paperBright = bright * 2 >= n
        val mask = BooleanArray(w * h) { (g[it] > t) == paperBright }
        // connected region under the centre (flood fill), with a margin so the outer frame of a desk is not taken
        val region = fill(mask, w, h, w / 2, h / 2) ?: return null
        val quad = extremes(region, w, h) ?: return null
        if (quad.area() < MIN_AREA) return null
        val refined = refine(quad, g, w, h)
        return if (refined.area() >= MIN_AREA && convex(refined)) refined else null
    }

    /**
     * Perspective-correct [quad] (0..1 of the [w]×[h] source) to an upright picture. The output size is the quad's
     * longest sides in source pixels, so nothing is lost; [maxDim] caps the long side. Returns the pixels and size.
     */
    fun warp(src: IntArray, w: Int, h: Int, quad: Quad, maxDim: Int = 4096): Triple<IntArray, Int, Int> {
        val q = quad.scaled(w, h)
        val top = hypot(q.x[1] - q.x[0], q.y[1] - q.y[0]); val bottom = hypot(q.x[2] - q.x[3], q.y[2] - q.y[3])
        val left = hypot(q.x[3] - q.x[0], q.y[3] - q.y[0]); val right = hypot(q.x[2] - q.x[1], q.y[2] - q.y[1])
        var ow = max(top, bottom).roundToInt().coerceAtLeast(8); var oh = max(left, right).roundToInt().coerceAtLeast(8)
        val k = min(1f, maxDim.toFloat() / max(ow, oh))
        ow = (ow * k).roundToInt().coerceAtLeast(8); oh = (oh * k).roundToInt().coerceAtLeast(8)
        // homography from the output rectangle to the source quad
        val hm = homography(floatArrayOf(0f, ow.toFloat(), ow.toFloat(), 0f), floatArrayOf(0f, 0f, oh.toFloat(), oh.toFloat()), q.x, q.y)
        val out = IntArray(ow * oh)
        for (v in 0 until oh) {
            val fy = v + 0.5
            for (u in 0 until ow) {
                val fx = u + 0.5
                val d = hm[6] * fx + hm[7] * fy + 1.0
                val sx = (hm[0] * fx + hm[1] * fy + hm[2]) / d - 0.5
                val sy = (hm[3] * fx + hm[4] * fy + hm[5]) / d - 0.5
                out[v * ow + u] = bilinear(src, w, h, sx, sy)
            }
        }
        return Triple(out, ow, oh)
    }

    // ------------------------------------------------------------------ internals

    private fun blur(g: IntArray, w: Int, h: Int): IntArray {
        val t = IntArray(g.size); val o = IntArray(g.size)
        for (y in 0 until h) for (x in 0 until w) {
            val a = g[y * w + max(0, x - 1)]; val b = g[y * w + x]; val c = g[y * w + min(w - 1, x + 1)]
            t[y * w + x] = (a + 2 * b + c) / 4
        }
        for (y in 0 until h) for (x in 0 until w) {
            val a = t[max(0, y - 1) * w + x]; val b = t[y * w + x]; val c = t[min(h - 1, y + 1) * w + x]
            o[y * w + x] = (a + 2 * b + c) / 4
        }
        return o
    }

    private fun otsu(g: IntArray): Int {
        val hist = IntArray(256); g.forEach { hist[it]++ }
        val n = g.size.toDouble(); var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var wb = 0.0; var sb = 0.0; var best = 0.0; var t = 128
        for (i in 0..255) {
            wb += hist[i]; if (wb == 0.0) continue
            val wf = n - wb; if (wf == 0.0) break
            sb += i.toDouble() * hist[i]
            val mb = sb / wb; val mf = (sum - sb) / wf
            val v = wb * wf * (mb - mf) * (mb - mf)
            if (v > best) { best = v; t = i }
        }
        return t
    }

    /** Flood fill of [mask] from (sx, sy); returns the region, or null when the start is not on the mask. */
    private fun fill(mask: BooleanArray, w: Int, h: Int, sx: Int, sy: Int): BooleanArray? {
        if (!mask[sy * w + sx]) return null
        val out = BooleanArray(w * h)
        val stack = IntArray(w * h); var sp = 0
        stack[sp++] = sy * w + sx; out[sy * w + sx] = true
        while (sp > 0) {
            val i = stack[--sp]; val x = i % w; val y = i / w
            if (x > 0 && mask[i - 1] && !out[i - 1]) { out[i - 1] = true; stack[sp++] = i - 1 }
            if (x < w - 1 && mask[i + 1] && !out[i + 1]) { out[i + 1] = true; stack[sp++] = i + 1 }
            if (y > 0 && mask[i - w] && !out[i - w]) { out[i - w] = true; stack[sp++] = i - w }
            if (y < h - 1 && mask[i + w] && !out[i + w]) { out[i + w] = true; stack[sp++] = i + w }
        }
        return out
    }

    /** Corners from the region's extreme points: min/max of x+y and x−y. */
    private fun extremes(r: BooleanArray, w: Int, h: Int): Quad? {
        var tl = Int.MAX_VALUE; var br = Int.MIN_VALUE; var tr = Int.MIN_VALUE; var bl = Int.MAX_VALUE
        var tlx = 0; var tly = 0; var brx = 0; var bry = 0; var trx = 0; var try_ = 0; var blx = 0; var bly = 0
        var count = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!r[y * w + x]) continue
            count++
            val s = x + y; val d = x - y
            if (s < tl) { tl = s; tlx = x; tly = y }
            if (s > br) { br = s; brx = x; bry = y }
            if (d > tr) { tr = d; trx = x; try_ = y }
            if (d < bl) { bl = d; blx = x; bly = y }
        }
        if (count < w * h / 20) return null
        return Quad(floatArrayOf(tlx / w.toFloat(), (trx + 1) / w.toFloat(), (brx + 1) / w.toFloat(), blx / w.toFloat()),
            floatArrayOf(tly / h.toFloat(), try_ / h.toFloat(), (bry + 1) / h.toFloat(), (bly + 1) / h.toFloat()))
    }

    /**
     * Move each corner (within a small window) to the point of strongest gradient along the directions of its two
     * sides — the mask edge can sit a pixel or two inside the real paper edge.
     */
    private fun refine(q: Quad, g: IntArray, w: Int, h: Int): Quad {
        val win = max(2, min(w, h) / 60)
        val x = q.x.copyOf(); val y = q.y.copyOf()
        for (i in 0 until 4) {
            val cx = (q.x[i] * w).roundToInt(); val cy = (q.y[i] * h).roundToInt()
            var best = -1; var bx = cx; var by = cy
            for (dy in -win..win) for (dx in -win..win) {
                val px = cx + dx; val py = cy + dy
                if (px < 1 || py < 1 || px >= w - 1 || py >= h - 1) continue
                val gx = abs(g[py * w + px + 1] - g[py * w + px - 1]); val gy = abs(g[(py + 1) * w + px] - g[(py - 1) * w + px])
                val m = gx + gy - (abs(dx) + abs(dy))   // prefer the strong edge, nearer wins ties
                if (m > best) { best = m; bx = px; by = py }
            }
            x[i] = bx / w.toFloat(); y[i] = by / h.toFloat()
        }
        return Quad(x, y)
    }

    private fun convex(q: Quad): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4; val k = (i + 2) % 4
            val c = (q.x[j] - q.x[i]) * (q.y[k] - q.y[j]) - (q.y[j] - q.y[i]) * (q.x[k] - q.x[j])
            val s = if (c > 0) 1 else if (c < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** 3×3 homography (8 values, h33 = 1) mapping (sx, sy)[i] → (dx, dy)[i] for the four corner pairs. */
    fun homography(sx: FloatArray, sy: FloatArray, dx: FloatArray, dy: FloatArray): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = sx[i].toDouble(); val y = sy[i].toDouble(); val u = dx[i].toDouble(); val v = dy[i].toDouble()
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        // Gaussian elimination with partial pivoting
        for (c in 0 until 8) {
            var p = c
            for (r in c + 1 until 8) if (abs(a[r][c]) > abs(a[p][c])) p = r
            val tmp = a[c]; a[c] = a[p]; a[p] = tmp
            val d = a[c][c]; if (abs(d) < 1e-12) continue
            for (k in c..8) a[c][k] /= d
            for (r in 0 until 8) if (r != c) { val f = a[r][c]; if (f != 0.0) for (k in c..8) a[r][k] -= f * a[c][k] }
        }
        return DoubleArray(8) { a[it][8] }
    }

    private fun bilinear(src: IntArray, w: Int, h: Int, fx: Double, fy: Double): Int {
        val x0 = kotlin.math.floor(fx).toInt(); val y0 = kotlin.math.floor(fy).toInt()
        val tx = (fx - x0).toFloat(); val ty = (fy - y0).toFloat()
        fun at(x: Int, y: Int) = src[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        val c00 = at(x0, y0); val c10 = at(x0 + 1, y0); val c01 = at(x0, y0 + 1); val c11 = at(x0 + 1, y0 + 1)
        fun ch(s: Int): Int {
            val a = ((c00 shr s) and 0xFF) * (1 - tx) + ((c10 shr s) and 0xFF) * tx
            val b = ((c01 shr s) and 0xFF) * (1 - tx) + ((c11 shr s) and 0xFF) * tx
            return (a * (1 - ty) + b * ty).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Sharpness score of a frame (variance of the Laplacian on a grid): higher = sharper; used for the "hold still" hint. */
    fun sharpness(px: IntArray, w: Int, h: Int): Double {
        var s = 0.0; var s2 = 0.0; var n = 0
        var y = 2
        while (y < h - 2) {
            var x = 2
            while (x < w - 2) {
                val c = PixelOps.lum(px[y * w + x])
                val l = 4 * c - PixelOps.lum(px[y * w + x - 1]) - PixelOps.lum(px[y * w + x + 1]) - PixelOps.lum(px[(y - 1) * w + x]) - PixelOps.lum(px[(y + 1) * w + x])
                s += l; s2 += l.toDouble() * l; n++
                x += 3
            }
            y += 3
        }
        if (n == 0) return 0.0
        val m = s / n
        return sqrt(max(0.0, s2 / n - m * m))
    }
}
