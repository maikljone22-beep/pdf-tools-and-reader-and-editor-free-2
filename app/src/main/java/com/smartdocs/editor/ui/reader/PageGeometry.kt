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
    val pageW: Float,          // points, as displayed (the page's /Rotate applied, like PDFium reports it)
    val pageH: Float,          // points, as displayed
    val scale: Float,          // px per point
    val rotation: Int = 0,     // 0, 90, 180, 270 clockwise (view rotation)
    /**
     * Audit AN-06: the page's own /Rotate (0, 90, 180, 270) and the lower-left corner of its crop box. Text boxes,
     * search hits and annotation rects are in PDF user space; the rendered page is the crop box turned by /Rotate.
     */
    val pageRotate: Int = 0,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
) {
    /** Crop box size in user space (before /Rotate). */
    private val cropW: Float get() = if (pageRotate % 180 == 0) pageW else pageH
    private val cropH: Float get() = if (pageRotate % 180 == 0) pageH else pageW

    /** Tab audit D9 / D10: the crop box in user space with the page's /Rotate (Fill & Sign layout, FsGeom). */
    fun pageBox(): com.smartdocs.editor.core.fillsign2.FsGeom.PageBox =
        com.smartdocs.editor.core.fillsign2.FsGeom.PageBox(pageRotate, cropX, cropY, cropW, cropH)

    val bitmapW: Float get() = pageW * scale
    val bitmapH: Float get() = pageH * scale

    /** Size of the page as laid out on screen (after rotation). */
    val layoutSize: Size get() = if (rotation % 180 == 0) Size(bitmapW, bitmapH) else Size(bitmapH, bitmapW)

    // ---- PDF <-> unrotated screen ----

    /** PDF user space -> rendered page (crop box turned by /Rotate, y down), in px. */
    fun pdfToScreen(x: Float, y: Float): Offset {
        val u = x - cropX; val v = y - cropY
        return when (pageRotate) {
            90 -> Offset(v * scale, u * scale)
            180 -> Offset((cropW - u) * scale, v * scale)
            270 -> Offset((cropH - v) * scale, (cropW - u) * scale)
            else -> Offset(u * scale, (cropH - v) * scale)
        }
    }

    fun screenToPdf(sx: Float, sy: Float): Pair<Float, Float> {
        val dx = sx / scale; val dy = sy / scale
        val (u, v) = when (pageRotate) {
            90 -> dy to dx
            180 -> (cropW - dx) to dy
            270 -> (cropW - dy) to (cropH - dx)
            else -> dx to (cropH - dy)
        }
        return (u + cropX) to (v + cropY)
    }

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
