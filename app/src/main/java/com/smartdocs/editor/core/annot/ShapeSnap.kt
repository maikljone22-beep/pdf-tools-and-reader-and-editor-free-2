package com.smartdocs.editor.core.annot

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Snap ink to shapes" (OLD_PLAN D2): a freehand stroke that looks like a straight line, a rectangle or an
 * ellipse is recognised so the reader can replace it by a clean Line / Square / Circle annotation.
 * Pure Kotlin (PDF points as pairs) so it is unit tested on the JVM.
 */
object ShapeSnap {

    enum class Kind { NONE, LINE, RECT, OVAL }

    /** [box] = left, bottom, right, top; [line] = x1, y1, x2, y2. */
    data class Result(val kind: Kind, val box: FloatArray = FloatArray(4), val line: FloatArray = FloatArray(4))

    private fun distToSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    fun classify(pts: List<Pair<Float, Float>>): Result {
        if (pts.size < 5) return Result(Kind.NONE)
        val l = pts.minOf { it.first }; val r = pts.maxOf { it.first }
        val b = pts.minOf { it.second }; val t = pts.maxOf { it.second }
        val w = r - l; val h = t - b
        val diag = hypot(w, h)
        if (diag < 12f) return Result(Kind.NONE)
        val (sx, sy) = pts.first(); val (ex, ey) = pts.last()
        val chord = hypot(ex - sx, ey - sy)
        // straight line: every point close to the start-end segment
        if (chord > diag * 0.8f) {
            val dev = pts.maxOf { (x, y) -> distToSeg(x, y, sx, sy, ex, ey) }
            if (dev < max(4f, chord * 0.06f)) return Result(Kind.LINE, line = floatArrayOf(sx, sy, ex, ey))
            return Result(Kind.NONE)
        }
        // closed shape: the stroke comes back near its start
        if (chord > diag * 0.3f || min(w, h) < 8f) return Result(Kind.NONE)
        val cx = (l + r) / 2; val cy = (b + t) / 2; val rx = w / 2; val ry = h / 2
        var ellErr = 0f; var rectErr = 0f
        pts.forEach { (x, y) ->
            val nx = (x - cx) / rx; val ny = (y - cy) / ry
            ellErr += abs(sqrt(nx * nx + ny * ny) - 1f)
            // distance to the box outline, normalised like the ellipse error
            rectErr += min(min(abs(x - l) / rx, abs(x - r) / rx), min(abs(y - b) / ry, abs(y - t) / ry))
        }
        ellErr /= pts.size; rectErr /= pts.size
        val box = floatArrayOf(l, b, r, t)
        return when {
            rectErr < ellErr && rectErr < 0.12f -> Result(Kind.RECT, box)
            ellErr <= rectErr && ellErr < 0.15f -> Result(Kind.OVAL, box)
            else -> Result(Kind.NONE)
        }
    }
}
