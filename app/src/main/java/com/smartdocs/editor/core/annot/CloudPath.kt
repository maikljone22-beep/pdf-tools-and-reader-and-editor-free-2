package com.smartdocs.editor.core.annot

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/** One cubic Bezier segment: start (x0,y0), controls (x1,y1) (x2,y2), end (x3,y3). */
data class Cubic(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float)

/**
 * Scalloped ("cloudy", PDF /BE /S /C) outline of a closed polygon. Computed in PDF space (y up), so the
 * same segments feed the appearance stream and, mapped point by point, the on-screen preview.
 */
object CloudPath {

    /** Radius of one bump for a border intensity (1, 2 or 3) and a line width. */
    fun radius(intensity: Int, lineWidth: Float): Float = (when { intensity >= 3 -> 13f; intensity >= 2 -> 9f; else -> 5f }) + lineWidth * 0.5f

    /** Extra margin a cloudy outline needs outside the polygon (bump height + stroke). */
    fun margin(intensity: Int, lineWidth: Float): Float = radius(intensity, lineWidth) * 1.1f + lineWidth

    private fun signedArea(p: List<Pair<Float, Float>>): Float {
        var a = 0f
        for (i in p.indices) { val (x1, y1) = p[i]; val (x2, y2) = p[(i + 1) % p.size]; a += x1 * y2 - x2 * y1 }
        return a / 2f
    }

    /** Bumps along every edge of the closed polygon [pts], bulging outwards. */
    fun build(pts: List<Pair<Float, Float>>, intensity: Int, lineWidth: Float): List<Cubic> {
        if (pts.size < 2) return emptyList()
        val r = radius(intensity, lineWidth)
        val ccw = signedArea(pts) >= 0f            // y-up: counter-clockwise has positive area
        val out = ArrayList<Cubic>()
        for (i in pts.indices) {
            val (ax, ay) = pts[i]; val (bx, by) = pts[(i + 1) % pts.size]
            val len = hypot(bx - ax, by - ay)
            if (len < 0.01f) continue
            val n = max(1, ceil(len / (2f * r)).toInt())
            val dx = (bx - ax) / len; val dy = (by - ay) / len
            // outward normal: right-hand side of the direction for a CCW polygon in y-up space
            val nx = if (ccw) dy else -dy; val ny = if (ccw) -dx else dx
            val chord = len / n
            val k = chord / 2f * 4f / 3f           // one-cubic semicircle approximation
            for (j in 0 until n) {
                val sx = ax + dx * chord * j; val sy = ay + dy * chord * j
                val ex = ax + dx * chord * (j + 1); val ey = ay + dy * chord * (j + 1)
                out += Cubic(sx, sy, sx + nx * k, sy + ny * k, ex + nx * k, ey + ny * k, ex, ey)
            }
        }
        return out
    }

    /** Bounding box (minX, minY, maxX, maxY) of [pts] grown by [pad]. */
    fun bounds(pts: List<Pair<Float, Float>>, pad: Float): FloatArray {
        var l = Float.MAX_VALUE; var b = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var t = -Float.MAX_VALUE
        pts.forEach { (x, y) -> if (x < l) l = x; if (x > r) r = x; if (y < b) b = y; if (y > t) t = y }
        return floatArrayOf(l - pad, b - pad, r + pad, t + pad)
    }
}
