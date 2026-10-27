package com.smartdocs.editor.ui.reader

/**
 * Edge tap zones of the reader (audit RD-05), measured on the screen viewport, not on the tapped page: in facing mode
 * the inner edges of both pages are the middle of the screen, and a zoomed page is wider than the screen. Pure.
 */
object TapZones {
    enum class Zone { PREV, NEXT, CENTER }

    const val EDGE = 0.18f

    /**
     * @param x tap position in viewport px; [width] the viewport width.
     * @param zoomed the page is zoomed in: taps near the screen edge then belong to the content (no flip).
     */
    fun zone(x: Float, width: Float, rtl: Boolean, zoomed: Boolean): Zone {
        if (zoomed || width <= 0f) return Zone.CENTER
        val frac = x / width
        val left = frac < EDGE; val right = frac > 1f - EDGE
        return when {
            left -> if (rtl) Zone.NEXT else Zone.PREV
            right -> if (rtl) Zone.PREV else Zone.NEXT
            else -> Zone.CENTER
        }
    }
}
