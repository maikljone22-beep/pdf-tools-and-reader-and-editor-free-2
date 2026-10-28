package com.smartdocs.editor.core.nve.shapes

import kotlin.math.hypot
import kotlin.math.max

/**
 * §14d free-drag reference line of a gradient: the two handles shown on the selected shape. Linear: the start and end
 * points of the axis; radial: the centre and a point on the circle (to the right of the centre). Handles are in page
 * display space; [Gradient] keeps fractions of the shape's bounds [l, b, r, t]. Pure: unit tested.
 */
object GradientHandles {
    /** Fractions stay within this range (a reference line may reach past the shape). */
    private const val LO = -1f
    private const val HI = 2f

    /** The two handles of [g] over the bounds [bb] ([l, b, r, t]). */
    fun points(g: Gradient, bb: FloatArray): List<Pair<Float, Float>> {
        val w = bb[2] - bb[0]; val h = bb[3] - bb[1]
        val c = bb[0] + g.x0 * w to bb[1] + g.y0 * h
        return if (g.radial) listOf(c, c.first + g.r * max(w, h) to c.second)
        else listOf(c, bb[0] + g.x1 * w to bb[1] + g.y1 * h)
    }

    /** Index of the handle within [tol] of ([x], [y]) (the nearest one), or -1. */
    fun hit(g: Gradient, bb: FloatArray, x: Float, y: Float, tol: Float): Int {
        val pts = points(g, bb)
        val d = pts.map { (px, py) -> hypot(px - x, py - y) }
        val i = if (d[1] <= d[0]) 1 else 0
        return if (d[i] <= tol) i else -1
    }

    /** [g] with handle [which] dragged to ([x], [y]). */
    fun drag(g: Gradient, bb: FloatArray, which: Int, x: Float, y: Float): Gradient {
        val w = (bb[2] - bb[0]).takeIf { it > 1e-3f } ?: return g
        val h = (bb[3] - bb[1]).takeIf { it > 1e-3f } ?: return g
        val fx = ((x - bb[0]) / w).coerceIn(LO, HI); val fy = ((y - bb[1]) / h).coerceIn(LO, HI)
        return when {
            which == 0 -> g.copy(x0 = fx, y0 = fy)
            g.radial -> {
                val c = points(g, bb)[0]
                g.copy(r = (hypot(x - c.first, y - c.second) / max(w, h)).coerceIn(0.02f, 3f))
            }
            else -> g.copy(x1 = fx, y1 = fy)
        }
    }
}
