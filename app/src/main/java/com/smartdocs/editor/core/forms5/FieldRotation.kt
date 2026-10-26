package com.smartdocs.editor.core.forms5

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rotation of a widget's text on screen. A widget appearance is drawn in PDF user space turned
 * counter-clockwise by `/MK /R` (the appearance /Matrix); the page `/Rotate` turns the page clockwise for
 * display and the reader may turn the view clockwise again. The pending value and the in-place editor are
 * drawn in an upright "frame" (width and height swapped for 90 / 270) and rotated clockwise by [textAngle]
 * about the widget's centre, so they read exactly like the saved appearance.
 */
object FieldRotation {

    fun norm(a: Int): Int = ((a % 360) + 360) % 360

    /** Clockwise screen angle of the text: page rotation - /MK /R + view rotation, normalised to 0/90/180/270. */
    fun textAngle(pageRotate: Int, mkRotate: Int, viewRotate: Int): Int = (norm(pageRotate - mkRotate + viewRotate) + 45) / 90 % 4 * 90

    /**
     * Upright frame (left, top, right, bottom; y down) of the text for an axis-aligned screen rect: same centre,
     * width and height swapped when the text is turned a quarter.
     */
    fun frame(l: Float, t: Float, r: Float, b: Float, angle: Int): FloatArray {
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val quarter = norm(angle) % 180 == 90
        val w = if (quarter) b - t else r - l; val h = if (quarter) r - l else b - t
        return floatArrayOf(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** A point of the frame turned clockwise by [angle] about the frame centre ([cx], [cy]); screen space, y down. */
    fun frameToScreen(x: Float, y: Float, cx: Float, cy: Float, angle: Int): Pair<Float, Float> {
        val dx = x - cx; val dy = y - cy
        return when (norm(angle)) {
            90 -> (cx - dy) to (cy + dx)
            180 -> (cx - dx) to (cy - dy)
            270 -> (cx + dy) to (cy - dx)
            else -> x to y
        }
    }

    /** Rotation part (a, b, c, d) of the appearance /Matrix for `/MK /R` = [mkRotate] (counter-clockwise). */
    fun matrixFor(mkRotate: Int): FloatArray {
        val rad = Math.toRadians(norm(mkRotate).toDouble())
        val c = cos(rad).roundToInt().toFloat(); val s = sin(rad).roundToInt().toFloat()
        return floatArrayOf(c, s, -s, c)
    }

    /** Quarter-turn angle (counter-clockwise) of an appearance matrix, or null when it is not a multiple of 90. */
    fun angleOf(a: Float, b: Float): Int? {
        if (abs(a) < 1e-6f && abs(b) < 1e-6f) return null
        val deg = Math.toDegrees(atan2(b.toDouble(), a.toDouble()))
        val q = (deg / 90.0).roundToInt()
        if (abs(deg - q * 90.0) > 1.0) return null
        return norm(q * 90)
    }

    /**
     * True when an existing appearance stream does not fit `/MK /R` = [mkRotate]: its matrix turns by another
     * angle or its bounding box is not the widget rect turned by the same angle. Such a stream must be
     * regenerated (PdfBox reuses a stream's BBox and Matrix when it fills in a new value).
     */
    fun appearanceMismatch(mkRotate: Int, rectW: Float, rectH: Float, bboxW: Float, bboxH: Float, ma: Float, mb: Float): Boolean {
        val want = norm(mkRotate)
        val have = angleOf(ma, mb) ?: return true
        if (have != want) return true
        val quarter = want % 180 == 90
        val ew = if (quarter) rectH else rectW; val eh = if (quarter) rectW else rectH
        fun near(x: Float, y: Float) = abs(x - y) <= maxOf(1.5f, 0.02f * maxOf(abs(x), abs(y)))
        return !(near(bboxW, ew) && near(bboxH, eh))
    }
}
