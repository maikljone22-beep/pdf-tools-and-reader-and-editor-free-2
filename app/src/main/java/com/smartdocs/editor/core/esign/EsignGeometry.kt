package com.smartdocs.editor.core.esign

/**
 * Maps field fractions on the displayed page (top-left origin, /Rotate applied, crop box) to PDF user space.
 * Pure math so it can be unit-tested: [llx]/[lly]/[w]/[h] = crop box, [rotate] = page /Rotate (0, 90, 180, 270).
 */
class PageGeom(val llx: Float, val lly: Float, val w: Float, val h: Float, rotate: Int) {
    val rotate: Int = ((rotate % 360) + 360) % 360 / 90 * 90

    /** Displayed size in points. */
    val displayW: Float get() = if (rotate % 180 == 0) w else h
    val displayH: Float get() = if (rotate % 180 == 0) h else w

    /** Displayed point (points, top-left origin) to user space. */
    fun toUser(dx: Float, dy: Float): Pair<Float, Float> = when (rotate) {
        90 -> (llx + dy) to (lly + dx)
        180 -> (llx + w - dx) to (lly + dy)
        270 -> (llx + w - dy) to (lly + h - dx)
        else -> (llx + dx) to (lly + h - dy)
    }

    /**
     * Affine matrix [a, b, c, d, e, f] that maps the unit square of an image onto the displayed rectangle
     * (fractions fx, fy, fw, fh) so that the image appears upright to the reader whatever the page rotation.
     */
    fun uprightMatrix(fx: Float, fy: Float, fw: Float, fh: Float): FloatArray {
        val x0 = fx * displayW; val y0 = fy * displayH; val x1 = (fx + fw) * displayW; val y1 = (fy + fh) * displayH
        val o = toUser(x0, y1)          // displayed bottom-left = image origin
        val xe = toUser(x1, y1)         // displayed bottom-right = image x axis end
        val ye = toUser(x0, y0)         // displayed top-left = image y axis end
        return floatArrayOf(xe.first - o.first, xe.second - o.second, ye.first - o.first, ye.second - o.second, o.first, o.second)
    }

    companion object {
        /** Clamps a field into the page and enforces a minimum size (fractions). */
        fun clampField(x: Float, y: Float, w: Float, h: Float, min: Float = 0.02f): FloatArray {
            val cw = w.coerceIn(min, 1f); val ch = h.coerceIn(min, 1f)
            return floatArrayOf(x.coerceIn(0f, 1f - cw), y.coerceIn(0f, 1f - ch), cw, ch)
        }

        /** Default field size for a type, corrected for the page aspect ratio (A4 proportions assumed for the base size). */
        fun defaultSize(type: FieldType, pageAspect: Float): Pair<Float, Float> {
            val (bw, bh) = when (type) {
                FieldType.SIGNATURE -> 0.30f to 0.06f
                FieldType.INITIALS -> 0.12f to 0.05f
                FieldType.DATE -> 0.22f to 0.035f
                FieldType.TEXT -> 0.30f to 0.035f
            }
            val a4 = 595f / 842f
            val k = if (pageAspect > 0f) pageAspect / a4 else 1f   // wider page -> same physical height is a smaller fraction
            return bw / k.coerceIn(0.4f, 2.5f) * 1f to bh
        }
    }
}
