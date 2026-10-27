package com.smartdocs.editor.core.formsx

/**
 * Align / centre / distribute / match size of several boxes (Nitro 8.80, Foxit 26.71; the comment tools use the
 * same math for 7.76). The anchor is the box the others line up with (Nitro: the first selected).
 * Rects are PDF space (y up). Pure functions: the callers write the results.
 */
object FxLayout {

    enum class Align { LEFT, RIGHT, TOP, BOTTOM, CENTER_H, CENTER_V }
    enum class Size { WIDTH, HEIGHT, BOTH }

    fun align(rects: List<FxRect>, mode: Align, anchor: Int = 0): List<FxRect> {
        if (rects.size < 2) return rects
        val a = rects[anchor.coerceIn(rects.indices)]
        return rects.map { r ->
            when (mode) {
                Align.LEFT -> r.offset(a.left - r.left, 0f)
                Align.RIGHT -> r.offset(a.right - r.right, 0f)
                Align.TOP -> r.offset(0f, a.top - r.top)
                Align.BOTTOM -> r.offset(0f, a.bottom - r.bottom)
                Align.CENTER_H -> r.offset(a.cx - r.cx, 0f)     // same vertical axis
                Align.CENTER_V -> r.offset(0f, a.cy - r.cy)     // same horizontal axis
            }
        }
    }

    /** Equal spacing between the outermost boxes (by centres), keeping the input order of the result list. */
    fun distribute(rects: List<FxRect>, horizontal: Boolean): List<FxRect> {
        if (rects.size < 3) return rects
        val order = rects.indices.sortedBy { if (horizontal) rects[it].cx else -rects[it].cy }
        val first = rects[order.first()]; val last = rects[order.last()]
        val out = rects.toMutableList()
        val n = order.size - 1
        order.forEachIndexed { k, i ->
            val r = rects[i]
            out[i] = if (horizontal) r.offset(first.cx + (last.cx - first.cx) * k / n - r.cx, 0f)
            else r.offset(0f, first.cy + (last.cy - first.cy) * k / n - r.cy)
        }
        return out
    }

    /** Make every box as wide / high as the anchor (top-left corner kept). */
    fun matchSize(rects: List<FxRect>, mode: Size, anchor: Int = 0): List<FxRect> {
        if (rects.size < 2) return rects
        val a = rects[anchor.coerceIn(rects.indices)]
        return rects.map { r ->
            val w = if (mode != Size.HEIGHT) a.width else r.width
            val h = if (mode != Size.WIDTH) a.height else r.height
            r.withSize(w, h)
        }
    }

    /** Centre the group on the page horizontally and / or vertically (relative positions kept). */
    fun centerOnPage(rects: List<FxRect>, pageW: Float, pageH: Float, horizontal: Boolean, vertical: Boolean): List<FxRect> {
        if (rects.isEmpty()) return rects
        val l = rects.minOf { it.left }; val r = rects.maxOf { it.right }; val b = rects.minOf { it.bottom }; val t = rects.maxOf { it.top }
        val dx = if (horizontal) pageW / 2 - (l + r) / 2 else 0f
        val dy = if (vertical) pageH / 2 - (b + t) / 2 else 0f
        return rects.map { it.offset(dx, dy) }
    }

    /** Snap a value to a grid of [step] points. */
    fun snap(v: Float, step: Float): Float = if (step <= 0f) v else Math.round(v / step) * step
}
