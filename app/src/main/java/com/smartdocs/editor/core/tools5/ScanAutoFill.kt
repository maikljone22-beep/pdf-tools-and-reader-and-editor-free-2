package com.smartdocs.editor.core.tools5

/**
 * Audit H10: auto-capture when the page fills (or overflows) the camera frame, where no 4-corner quad is found.
 * Pure decisions on the 16x12 luminance grid the camera already computes for each analysis frame.
 */
object ScanAutoFill {
    /** A detected quad at least this large (fraction of the frame) counts as filling the frame. */
    const val FILL_AREA = 0.85f
    /** Max mean per-cell luminance change between two frames to count the image as steady. */
    const val STEADY_DIFF = 3

    /** A mostly uniform bright page: bright on average and almost every grid cell light (text averages out per cell). */
    fun brightPage(sig: IntArray): Boolean {
        if (sig.isEmpty()) return false
        var sum = 0L; var light = 0
        for (v in sig) { sum += v; if (v >= 120) light++ }
        return sum / sig.size >= 150 && light * 100 >= sig.size * 85
    }

    /** The page fills the frame: a (possibly rejected) quad covering ≥ [FILL_AREA], or no quad but a bright page. */
    fun fillsFrame(rawQuadArea: Float?, sig: IntArray): Boolean =
        if (rawQuadArea != null) rawQuadArea >= FILL_AREA else brightPage(sig)

    /** Next steadiness count for the full-frame mode (same window as the quad mode). */
    fun steadyStep(prev: Int, fills: Boolean, frameDiff: Int): Int = if (fills && frameDiff <= STEADY_DIFF) prev + 1 else 0
}
