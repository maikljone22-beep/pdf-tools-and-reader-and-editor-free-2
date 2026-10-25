package com.smartdocs.editor.core.edit

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.util.Matrix

/**
 * Affine helpers on PdfBox [Matrix] (row-vector convention: a point is transformed as `p × M`, so
 * `mul(a, b)` applies [a] first, then [b]). Values: a=(0,0) b=(0,1) c=(1,0) d=(1,1) e=(2,0) f=(2,1).
 */
object Affine {
    fun of(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) = Matrix(a, b, c, d, e, f)

    fun values(m: Matrix): FloatArray = floatArrayOf(m.getValue(0, 0), m.getValue(0, 1), m.getValue(1, 0), m.getValue(1, 1), m.getValue(2, 0), m.getValue(2, 1))

    fun fromValues(v: FloatArray) = Matrix(v[0], v[1], v[2], v[3], v[4], v[5])

    fun identity() = Matrix(1f, 0f, 0f, 1f, 0f, 0f)

    /** a × b : apply [a] first, then [b]. */
    fun mul(a: Matrix, b: Matrix): Matrix {
        val x = values(a); val y = values(b)
        return Matrix(
            x[0] * y[0] + x[1] * y[2], x[0] * y[1] + x[1] * y[3],
            x[2] * y[0] + x[3] * y[2], x[2] * y[1] + x[3] * y[3],
            x[4] * y[0] + x[5] * y[2] + y[4], x[4] * y[1] + x[5] * y[3] + y[5],
        )
    }

    fun inverse(m: Matrix): Matrix {
        val (a, b, c, d) = values(m).let { listOf(it[0], it[1], it[2], it[3]) }
        val e = m.getValue(2, 0); val f = m.getValue(2, 1)
        val det = a * d - b * c
        if (kotlin.math.abs(det) < 1e-9f) return identity()
        return Matrix(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det)
    }

    fun translate(tx: Float, ty: Float) = Matrix(1f, 0f, 0f, 1f, tx, ty)
    fun scale(sx: Float, sy: Float) = Matrix(sx, 0f, 0f, sy, 0f, 0f)

    /** Counter-clockwise rotation (y up) by [deg] degrees around ([cx], [cy]). */
    fun rotateAround(deg: Float, cx: Float, cy: Float): Matrix {
        val r = Math.toRadians(deg.toDouble())
        val cos = Math.cos(r).toFloat(); val sin = Math.sin(r).toFloat()
        // snap tiny values so 90° steps stay exact
        val c = if (kotlin.math.abs(cos) < 1e-6f) 0f else cos
        val s = if (kotlin.math.abs(sin) < 1e-6f) 0f else sin
        return mul(mul(translate(-cx, -cy), Matrix(c, s, -s, c, 0f, 0f)), translate(cx, cy))
    }

    fun apply(m: Matrix, x: Float, y: Float): Pair<Float, Float> {
        val v = values(m)
        return (x * v[0] + y * v[2] + v[4]) to (x * v[1] + y * v[3] + v[5])
    }

    /** Bounding box (PDF convention: top > bottom) of the unit square transformed by [m]. */
    fun unitBox(m: Matrix): RectF = box(m, 0f, 0f, 1f, 1f)

    /** Bounding box of the rect (x0,y0)-(x1,y1) transformed by [m]. */
    fun box(m: Matrix, x0: Float, y0: Float, x1: Float, y1: Float): RectF {
        val pts = listOf(apply(m, x0, y0), apply(m, x1, y0), apply(m, x0, y1), apply(m, x1, y1))
        return RectF(pts.minOf { it.first }, pts.maxOf { it.second }, pts.maxOf { it.first }, pts.minOf { it.second })
    }
}

/**
 * "Display space" of a page: what the viewer shows. Origin at the bottom-left of the crop box after the page
 * /Rotate is applied, y up, in points — the same space as [com.smartdocs.editor.ui.reader.PageGeometry] uses.
 * [toDisplay] maps PDF user space to display space, [toUser] the other way.
 */
class PageFrame(val width: Float, val height: Float, val rotation: Int, val toDisplay: Matrix, val toUser: Matrix) {
    companion object {
        fun of(page: PDPage): PageFrame {
            val cb = page.cropBox
            val llx = cb.lowerLeftX; val lly = cb.lowerLeftY; val w = cb.width; val h = cb.height
            return when (((page.rotation % 360) + 360) % 360) {
                90 -> PageFrame(h, w, 90, Affine.of(0f, -1f, 1f, 0f, -lly, w + llx), Affine.of(0f, 1f, -1f, 0f, w + llx, lly))
                180 -> PageFrame(w, h, 180, Affine.of(-1f, 0f, 0f, -1f, w + llx, h + lly), Affine.of(-1f, 0f, 0f, -1f, w + llx, h + lly))
                270 -> PageFrame(h, w, 270, Affine.of(0f, 1f, -1f, 0f, h + lly, -llx), Affine.of(0f, -1f, 1f, 0f, llx, h + lly))
                else -> PageFrame(w, h, 0, Affine.translate(-llx, -lly), Affine.translate(llx, lly))
            }
        }
    }
}
