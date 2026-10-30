package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertTrue
import org.junit.Test

/** Smart enhance: grey paper → white, text darker, a colour picture keeps its colour. */
class ScanEnhanceTest {
    private val w = 800; private val h = 1000
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun page(): IntArray {
        val px = IntArray(w * h) { i -> val shade = 190 - (i % w) * 30 / w; rgb(shade, shade, shade - 5) }   // greyish paper with a gradient
        for (line in 0 until 8) for (y in 100 + line * 50 until 112 + line * 50) for (x in 60 until 740) if ((x / 9) % 3 != 0) px[y * w + x] = rgb(80, 80, 85)
        for (y in 600 until 900) for (x in 200 until 600) { val t = (x * 7 + y * 13) % 60; px[y * w + x] = rgb(40 + (x - 200) / 3 + t, 90 + t, 170 - (y - 600) / 3 + t / 2) }   // colour photo with texture
        return px
    }
    private fun sat(c: Int): Int { val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; return maxOf(r, g, b) - minOf(r, g, b) }

    @Test fun paperWhiteTextDarkPictureKept() {
        val px = page()
        val photoBefore = sat(px[750 * w + 400])
        ScanEnhance.enhance(px, w, h)
        val paper = PixelOps.lum(px[60 * w + 400]); val paperRight = PixelOps.lum(px[60 * w + 780])
        val text = PixelOps.lum(px[105 * w + 70])
        assertTrue("paper $paper / $paperRight", paper >= 250 && paperRight >= 250)
        assertTrue("text $text", text < 60)
        assertTrue("photo saturation ${sat(px[750 * w + 400])} vs $photoBefore", sat(px[750 * w + 400]) >= photoBefore * 0.8)
    }

    @Test fun picturesAreDetected() {
        val weight = ScanEnhance.pictureWeight(page(), w, h)
        assertTrue(weight[750 * w + 400] > 0.9f)
        assertTrue(weight[105 * w + 300] < 0.1f)
    }
}
