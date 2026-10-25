package com.smartdocs.editor.ui.reader

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Converts between PDF page space (points, origin bottom-left, y up) and the on-screen
 * layout space of a rendered page (pixels, origin top-left, y down), including the
 * optional view rotation (0/90/180/270 clockwise).
 *
 * "unrotated" screen space is the rendered bitmap space: width = pageW * scale, height = pageH * scale.
 * "layout" space is what the user sees after rotation; for 90/270 the width and height are swapped.
 */
class PageGeometry(
    val pageW: Float,          // points
    val pageH: Float,          // points
    val scale: Float,          // px per point
    val rotation: Int = 0,     // 0, 90, 180, 270 clockwise
) {
    val bitmapW: Float get() = pageW * scale
    val bitmapH: Float get() = pageH * scale

    /** Size of the page as laid out on screen (after rotation). */
    val layoutSize: Size get() = if (rotation % 180 == 0) Size(bitmapW, bitmapH) else Size(bitmapH, bitmapW)

    // ---- PDF <-> unrotated screen ----

    fun pdfToScreen(x: Float, y: Float): Offset = Offset(x * scale, (pageH - y) * scale)

    fun screenToPdf(sx: Float, sy: Float): Pair<Float, Float> = (sx / scale) to (pageH - sy / scale)

    /** PDF rect (any corner order) -> unrotated screen rect (normalised). */
    fun pdfRectToScreen(r: RectF): Rect {
        val a = pdfToScreen(r.left, r.top); val b = pdfToScreen(r.right, r.bottom)
        return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    /** Unrotated screen rect -> PDF rect with left<right and top>bottom (PDF convention: top is the larger y). */
    fun screenRectToPdf(r: Rect): RectF {
        val (l, t) = screenToPdf(r.left, r.top)
        val (rr, b) = screenToPdf(r.right, r.bottom)
        return RectF(minOf(l, rr), maxOf(t, b), maxOf(l, rr), minOf(t, b))
    }

    // ---- rotation: layout <-> unrotated screen ----

    fun layoutToScreen(p: Offset): Offset = when (rotation) {
        90 -> Offset(p.y, bitmapH - p.x)
        180 -> Offset(bitmapW - p.x, bitmapH - p.y)
        270 -> Offset(bitmapW - p.y, p.x)
        else -> p
    }

    fun screenToLayout(p: Offset): Offset = when (rotation) {
        90 -> Offset(bitmapH - p.y, p.x)
        180 -> Offset(bitmapW - p.x, bitmapH - p.y)
        270 -> Offset(p.y, bitmapW - p.x)
        else -> p
    }

    // ---- convenience: layout <-> PDF ----

    fun layoutToPdf(p: Offset): Pair<Float, Float> { val s = layoutToScreen(p); return screenToPdf(s.x, s.y) }

    fun pdfToLayout(x: Float, y: Float): Offset = screenToLayout(pdfToScreen(x, y))

    fun pdfRectToLayout(r: RectF): Rect {
        val a = pdfToLayout(r.left, r.top); val b = pdfToLayout(r.right, r.bottom)
        return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    fun layoutRectToPdf(r: Rect): RectF {
        val a = layoutToPdf(Offset(r.left, r.top)); val b = layoutToPdf(Offset(r.right, r.bottom))
        return RectF(minOf(a.first, b.first), maxOf(a.second, b.second), maxOf(a.first, b.first), minOf(a.second, b.second))
    }

    /** Points -> physical length helpers (1 pt = 1/72 inch). */
    companion object {
        fun ptToCm(pt: Float) = pt / 72f * 2.54f
        fun ptToMm(pt: Float) = pt / 72f * 25.4f
        fun ptToInch(pt: Float) = pt / 72f

        /** Normalise an android RectF so that left<=right and top>=bottom (PDF convention). */
        fun normPdf(r: RectF) = RectF(minOf(r.left, r.right), maxOf(r.top, r.bottom), maxOf(r.left, r.right), minOf(r.top, r.bottom))
    }
}
