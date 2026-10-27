package com.smartdocs.editor.ui.signature

/**
 * "Convert QuickSign signature to black and white" (nitro-quicksign). Pure ARGB pixel logic (no android.graphics)
 * so it is unit-tested. Each pixel keeps its alpha; ink darker than [threshold] (luma 0..255) becomes pure black,
 * lighter leftovers (paper, halo) become transparent. Anti-aliased edges keep their partial alpha.
 */
object SignatureMono {
    const val DEFAULT_THRESHOLD = 200

    fun toBlackAndWhite(px: IntArray, threshold: Int = DEFAULT_THRESHOLD): IntArray {
        val out = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val luma = (299 * r + 587 * g + 114 * b) / 1000
            out[i] = if (luma >= threshold) 0 else a shl 24   // black with the original alpha
        }
        return out
    }
}
