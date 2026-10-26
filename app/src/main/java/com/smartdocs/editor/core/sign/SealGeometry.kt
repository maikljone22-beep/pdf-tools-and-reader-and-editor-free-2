package com.smartdocs.editor.core.sign

/**
 * Page geometry for drawing upright content on the page as it is DISPLAYED (crop box, /Rotate applied). Pure Kotlin.
 *
 * Display space here: origin at the bottom-left of the displayed page, y up, size [displayWidth] × [displayHeight].
 */
class SealGeometry(
    private val cropLlx: Float, private val cropLly: Float, private val cropW: Float, private val cropH: Float, rotateDeg: Int,
) {
    /** Quarter turns of /Rotate (0..3, clockwise). */
    val rotation: Int = (((rotateDeg / 90) % 4) + 4) % 4
    val displayWidth: Float get() = if (rotation % 2 == 1) cropH else cropW
    val displayHeight: Float get() = if (rotation % 2 == 1) cropW else cropH

    /**
     * PDF matrix [a, b, c, d, e, f] that draws an image (unit square) upright into the display-space rectangle
     * ([x], [y], [w], [h]).
     */
    fun uprightImageMatrix(x: Float, y: Float, w: Float, h: Float): FloatArray {
        val m = when (rotation) {
            1 -> floatArrayOf(0f, w, -h, 0f, cropW - y, x)
            2 -> floatArrayOf(-w, 0f, 0f, -h, cropW - x, cropH - y)
            3 -> floatArrayOf(0f, -w, h, 0f, y, cropH - x)
            else -> floatArrayOf(w, 0f, 0f, h, x, y)
        }
        m[4] += cropLlx; m[5] += cropLly
        return m
    }

    /** Display-space point → PDF user space (for checks). */
    fun toUser(x: Float, y: Float): FloatArray {
        val (a, b) = when (rotation) {
            1 -> (cropW - y) to x
            2 -> (cropW - x) to (cropH - y)
            3 -> y to (cropH - x)
            else -> x to y
        }
        return floatArrayOf(a + cropLlx, b + cropLly)
    }
}
