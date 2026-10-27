package com.smartdocs.editor.ui.signature

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SignatureMonoTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun inkBecomesBlackKeepingAlpha() {
        val px = intArrayOf(
            argb(255, 13, 71, 161),    // blue ink
            argb(128, 183, 28, 28),    // half-transparent red edge
            argb(255, 250, 250, 245),  // paper leftover
            0,                         // transparent
            argb(40, 255, 255, 255),   // faint white halo
        )
        assertArrayEquals(intArrayOf(argb(255, 0, 0, 0), argb(128, 0, 0, 0), 0, 0, 0), SignatureMono.toBlackAndWhite(px))
    }

    @Test fun thresholdDecidesGreys() {
        val grey = argb(255, 150, 150, 150)
        assertArrayEquals(intArrayOf(argb(255, 0, 0, 0)), SignatureMono.toBlackAndWhite(intArrayOf(grey), 200))
        assertArrayEquals(intArrayOf(0), SignatureMono.toBlackAndWhite(intArrayOf(grey), 120))
    }
}
