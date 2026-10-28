package com.smartdocs.editor.core.rpf

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in fractions (0..1) of a page box, y down. */
data class Frac(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Pure geometry of reader-prefs (unit-tested): print "current view", deep zoom, page size label. */
object ViewMath {
    /**
     * The visible part of a page shown with a total clockwise rotation of [rotation] degrees (page /Rotate plus the
     * view rotation), as fractions of the unrotated crop box (y down).
     */
    fun unrotate(f: Frac, rotation: Int): Frac {
        fun pt(x: Float, y: Float): Pair<Float, Float> = when (((rotation % 360) + 360) % 360) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
        val a = pt(f.left, f.top); val b = pt(f.right, f.bottom)
        return Frac(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
    }

    /** [f] (unrotated fractions, y down) in PDF user space of a crop box: [left, bottom, right, top]. */
    fun toUserSpace(f: Frac, cropLeft: Float, cropBottom: Float, cropW: Float, cropH: Float): FloatArray {
        val top = cropBottom + cropH
        return floatArrayOf(cropLeft + f.left * cropW, top - f.bottom * cropH, cropLeft + f.right * cropW, top - f.top * cropH)
    }

    /** Visible part of a page rect [pl, pt, pr, pb] inside a viewport [vw] × [vh], as fractions of the page (null: off screen). */
    fun visibleFrac(pl: Float, pt: Float, pr: Float, pb: Float, vw: Float, vh: Float): Frac? {
        val w = pr - pl; val h = pb - pt
        if (w <= 0f || h <= 0f) return null
        val l = ((0f - pl) / w).coerceIn(0f, 1f); val r = ((vw - pl) / w).coerceIn(0f, 1f)
        val t = ((0f - pt) / h).coerceIn(0f, 1f); val b = ((vh - pt) / h).coerceIn(0f, 1f)
        return if (r <= l || b <= t) null else Frac(l, t, r, b)
    }

    // ---------------------------------------------------------------- deep zoom (above the layout zoom, up to 6400 %)

    const val DEEP_MAX_PCT = 6400f

    /**
     * Window of a deep-zoom view: the page ([pageW] × [pageH] points) at [scale] px per point, centred on ([cx], [cy])
     * (page fractions), in a viewport of [vw] × [vh] px. The centre is kept so the window stays on the page; returns
     * the clamped centre and the clip in page fractions.
     */
    fun deepWindow(pageW: Float, pageH: Float, scale: Float, cx: Float, cy: Float, vw: Float, vh: Float): Pair<Pair<Float, Float>, Frac> {
        val fw = pageW * scale; val fh = pageH * scale
        val hx = (vw / 2f / fw).coerceAtMost(0.5f); val hy = (vh / 2f / fh).coerceAtMost(0.5f)
        val x = cx.coerceIn(hx, 1f - hx); val y = cy.coerceIn(hy, 1f - hy)
        return (x to y) to Frac(x - hx, y - hy, x + hx, y + hy)
    }

    // ---------------------------------------------------------------- page size

    /** "210 × 297 mm" / "8.27 × 11.69 in" / "595 × 842 pt" / "21 × 29.7 cm" for a page of [w] × [h] points. */
    fun pageSizeLabel(w: Float, h: Float, units: String): String {
        fun r(v: Float, d: Int): String {
            val p = Math.pow(10.0, d.toDouble()); val x = (v * p).roundToInt() / p
            return if (x == Math.floor(x)) x.toLong().toString() else x.toString()
        }
        return when (units) {
            "in" -> "${r(w / 72f, 2)} × ${r(h / 72f, 2)} in"
            "pt" -> "${r(w, 0)} × ${r(h, 0)} pt"
            "cm" -> "${r(w / 72f * 2.54f, 1)} × ${r(h / 72f * 2.54f, 1)} cm"
            else -> "${r(w / 72f * 25.4f, 0)} × ${r(h / 72f * 25.4f, 0)} mm"
        }
    }
}
