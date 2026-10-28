package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.util.Matrix

/**
 * audit 5 T-10: coordinates on a page as it is DISPLAYED — the visible crop box, with /Rotate applied, origin at the
 * bottom-left of what the reader sees — mapped back to PDF user space. Stamps (quick watermark, page numbers) placed
 * with it land where they look right on rotated and cropped pages.
 */
class DisplayPlace(private val llx: Float, private val lly: Float, private val w: Float, private val h: Float, rotation: Int) {
    val rotation: Int = ((rotation % 360) + 360) % 360 / 90 * 90
    /** Displayed width / height (points). */
    val width: Float = if (this.rotation == 90 || this.rotation == 270) h else w
    val height: Float = if (this.rotation == 90 || this.rotation == 270) w else h

    /** Displayed point (dx, dy) → user space. Pure. */
    fun toUser(dx: Float, dy: Float): FloatArray = when (rotation) {
        90 -> floatArrayOf(llx + w - dy, lly + dx)
        180 -> floatArrayOf(llx + w - dx, lly + h - dy)
        270 -> floatArrayOf(llx + dy, lly + h - dx)
        else -> floatArrayOf(llx + dx, lly + dy)
    }

    /** Text matrix that starts text at displayed (dx, dy), running at [angle] degrees on the displayed page. */
    fun textMatrix(dx: Float, dy: Float, angle: Float): Matrix {
        val p = toUser(dx, dy)
        return Matrix.getRotateInstance(Math.toRadians((angle + rotation).toDouble()), p[0], p[1])
    }

    companion object {
        fun of(page: PDPage): DisplayPlace { val c = page.cropBox; return DisplayPlace(c.lowerLeftX, c.lowerLeftY, c.width, c.height, page.rotation) }
    }
}
