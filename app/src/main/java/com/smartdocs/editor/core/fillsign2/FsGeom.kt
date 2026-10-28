package com.smartdocs.editor.core.fillsign2

/**
 * Page geometry of Fill & Sign marks on rotated / cropped pages (tab audit D9 / D10). Pure math, unit-tested.
 *
 * Annotation rects are PDF user space (origin bottom-left, y up). The user sees the crop box turned clockwise by the
 * page's /Rotate. "Display" space here is that view in points: origin top-left, y down, size [PageBox.dispW] x
 * [PageBox.dispH]. Marks are laid out upright in display space and mapped back to user space, so a typed name, a
 * check mark or a signature reads upright on a /Rotate 90 page and is clamped to the visible crop box.
 */
object FsGeom {

    /** Crop box of a page in user space ([x], [y] = lower-left corner, [w] x [h]) and its /Rotate (0, 90, 180, 270). */
    data class PageBox(val rotate: Int, val x: Float, val y: Float, val w: Float, val h: Float) {
        val dispW: Float get() = if (rotate % 180 == 0) w else h
        val dispH: Float get() = if (rotate % 180 == 0) h else w

        companion object {
            /** From the displayed page size (PDFium reports it with /Rotate applied) and the crop box origin. */
            fun fromDisplayed(dispW: Float, dispH: Float, rotate: Int, cropX: Float = 0f, cropY: Float = 0f): PageBox {
                val r = norm(rotate)
                return if (r % 180 == 0) PageBox(r, cropX, cropY, dispW, dispH) else PageBox(r, cropX, cropY, dispH, dispW)
            }

            /** An unrotated page of [w] x [h] at (0, 0) (the old callers). */
            fun plain(w: Float, h: Float) = PageBox(0, 0f, 0f, w, h)
        }
    }

    fun norm(r: Int): Int = (((r / 90) * 90) % 360 + 360) % 360

    /** User space point -> display point (y down). */
    fun toDisplay(b: PageBox, x: Float, y: Float): Pair<Float, Float> {
        val u = x - b.x; val v = y - b.y
        return when (b.rotate) {
            90 -> v to u
            180 -> (b.w - u) to v
            270 -> (b.h - v) to (b.w - u)
            else -> u to (b.h - v)
        }
    }

    /** Display point (y down) -> user space point. */
    fun fromDisplay(b: PageBox, dx: Float, dy: Float): Pair<Float, Float> {
        val (u, v) = when (b.rotate) {
            90 -> dy to dx
            180 -> (b.w - dx) to dy
            270 -> (b.w - dy) to (b.h - dx)
            else -> dx to (b.h - dy)
        }
        return (u + b.x) to (v + b.y)
    }

    /** A display offset (right [dx], down [dy]) as a user space offset on a page turned by [rotate]. */
    fun userDelta(rotate: Int, dx: Float, dy: Float): Pair<Float, Float> = when (norm(rotate)) {
        90 -> dy to dx
        180 -> -dx to dy
        270 -> -dy to -dx
        else -> dx to -dy
    }

    /** Display rect (left, top, right, bottom; y down) -> user space box. */
    fun displayRectToUser(b: PageBox, l: Float, t: Float, r: Float, bt: Float): MultiPage.Box {
        val (x1, y1) = fromDisplay(b, l, t); val (x2, y2) = fromDisplay(b, r, bt)
        return MultiPage.Box(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    /** User space box -> display rect [left, top, right, bottom] (y down). */
    fun userToDisplayRect(b: PageBox, box: MultiPage.Box): FloatArray {
        val (x1, y1) = toDisplay(b, box.left, box.bottom); val (x2, y2) = toDisplay(b, box.right, box.top)
        return floatArrayOf(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    private fun clampStart(v: Float, size: Float, total: Float, margin: Float): Float =
        v.coerceAtMost(total - size - margin).coerceAtLeast(margin)

    /**
     * A text box [w] x [h] (display points) whose top edge is at the tap ([x], [y] user space): it starts at the tap
     * (ends there for right-to-left text) and is kept inside the visible crop box with a 2 pt margin.
     */
    fun textBox(b: PageBox, x: Float, y: Float, w: Float, h: Float, rtl: Boolean): MultiPage.Box {
        val (dx, dy) = toDisplay(b, x, y)
        val ww = w.coerceAtMost(b.dispW * 0.9f)
        val left = clampStart(if (rtl) dx - ww else dx, ww, b.dispW, 2f)
        val top = dy.coerceAtMost(b.dispH - h).coerceAtLeast(0f)
        return displayRectToUser(b, left, top, left + ww, top + h)
    }

    /** Comb box [w] x [h] (display points): left edge at the tap, centred vertically on it, inside the crop box. */
    fun combBox(b: PageBox, x: Float, y: Float, w: Float, h: Float): MultiPage.Box {
        val (dx, dy) = toDisplay(b, x, y)
        val left = clampStart(dx, w, b.dispW, 0f)
        val top = clampStart(dy - h / 2f, h, b.dispH, 0f)
        return displayRectToUser(b, left, top, left + w, top + h)
    }

    /** Image (signature / stamp) box [w] x [h] (display points, the image's upright size) centred on the tap. */
    fun stampBox(b: PageBox, x: Float, y: Float, w: Float, h: Float): MultiPage.Box {
        val (dx, dy) = toDisplay(b, x, y)
        return displayRectToUser(b, dx - w / 2f, dy - h / 2f, dx + w / 2f, dy + h / 2f)
    }

    /**
     * fix/selmenu-rest: [stampBox] for a point picked from a menu ("Sign here" / "Insert image here"): centred on the
     * point but moved (not shrunk, unless larger than the page) so the whole image stays on the visible crop box.
     */
    fun stampBoxInside(b: PageBox, x: Float, y: Float, w: Float, h: Float): MultiPage.Box {
        val (dx, dy) = toDisplay(b, x, y)
        val ww = w.coerceAtMost(b.dispW); val hh = h.coerceAtMost(b.dispH)
        val left = (dx - ww / 2f).coerceIn(0f, b.dispW - ww)
        val top = (dy - hh / 2f).coerceIn(0f, b.dispH - hh)
        return displayRectToUser(b, left, top, left + ww, top + hh)
    }

    /** Upright (display) size of an appearance for a user space rect of [w] x [h] on a page turned by [rotate]. */
    fun uprightSize(rotate: Int, w: Float, h: Float): Pair<Float, Float> = if (norm(rotate) % 180 == 0) w to h else h to w

    /** The appearance /Matrix [a b c d e f] applied to a form point (as PDF does before fitting the BBox to /Rect). */
    fun apply(m: FloatArray?, x: Float, y: Float): Pair<Float, Float> =
        if (m == null) x to y else (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])
}
