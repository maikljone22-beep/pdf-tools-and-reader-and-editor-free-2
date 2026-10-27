package com.smartdocs.editor.ui.reader

import kotlin.math.hypot

/** Eraser hit tests on saved ink (audit AN-13). Pure: strokes are point lists in PDF space. */
object EraseLogic {
    /** A stroke set [paths] drawn [strokeWidth] wide is touched by the eraser at any of [pts] with [radius]. */
    fun touches(paths: List<List<Pair<Float, Float>>>, strokeWidth: Float, pts: List<Pair<Float, Float>>, radius: Float): Boolean =
        pts.any { (x, y) -> paths.any { s -> s.any { (px, py) -> hypot(px - x, py - y) <= radius + strokeWidth } } }

    /** Cheap pre-filter on the stored annotation's rect (l, b, r, t in PDF space, any corner order). */
    fun nearRect(l: Float, b: Float, r: Float, t: Float, pts: List<Pair<Float, Float>>, pad: Float): Boolean {
        val x0 = minOf(l, r) - pad; val x1 = maxOf(l, r) + pad; val y0 = minOf(b, t) - pad; val y1 = maxOf(b, t) + pad
        return pts.any { (x, y) -> x in x0..x1 && y in y0..y1 }
    }
}
