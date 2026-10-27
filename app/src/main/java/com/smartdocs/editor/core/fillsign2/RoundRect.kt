package com.smartdocs.editor.core.fillsign2

/**
 * Rounded rectangle of the Fill & Sign tools (nitro-fillsign2): saved as a /Square annotation whose appearance
 * stream has rounded corners (the radius is kept in the private key /SDRound so it survives an edit round trip).
 * Pure path math used by the PDF writer; unit-tested.
 */
object RoundRect {
    /** Bezier handle factor of a quarter circle. */
    const val KAPPA = 0.5523f

    sealed interface Seg {
        data class Move(val x: Float, val y: Float) : Seg
        data class Line(val x: Float, val y: Float) : Seg
        data class Curve(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) : Seg
    }

    /** Radius actually drawn: never more than half of the shorter side. */
    fun radius(w: Float, h: Float, r: Float): Float = r.coerceIn(0f, (minOf(w, h) / 2f).coerceAtLeast(0f))

    /** Default radius for a box the user dragged out. */
    fun defaultRadius(w: Float, h: Float): Float = (minOf(w, h) * 0.2f).coerceIn(2f, 24f)

    /** Closed outline (counter-clockwise from the bottom edge) of the box (x, y, w, h) with corner radius [r]. */
    fun path(x: Float, y: Float, w: Float, h: Float, r: Float): List<Seg> {
        val rr = radius(w, h, r); val k = rr * KAPPA
        val l = x; val b = y; val rt = x + w; val t = y + h
        return listOf(
            Seg.Move(l + rr, b),
            Seg.Line(rt - rr, b),
            Seg.Curve(rt - rr + k, b, rt, b + rr - k, rt, b + rr),
            Seg.Line(rt, t - rr),
            Seg.Curve(rt, t - rr + k, rt - rr + k, t, rt - rr, t),
            Seg.Line(l + rr, t),
            Seg.Curve(l + rr - k, t, l, t - rr + k, l, t - rr),
            Seg.Line(l, b + rr),
            Seg.Curve(l, b + rr - k, l + rr - k, b, l + rr, b),
        )
    }
}
