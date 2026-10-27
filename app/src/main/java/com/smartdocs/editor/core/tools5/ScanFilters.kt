package com.smartdocs.editor.core.tools5

/**
 * Foxit "Optimize scanned PDF" filters on ARGB pixels (pure Kotlin, unit-tested): descreen (removes the halftone dot
 * pattern of printed pictures with a small blur), text sharpening (unsharp mask) and black-and-white conversion
 * (adaptive threshold → packed 1-bit rows, 0 = black, for a CCITT G4 image). Background removal is
 * [PixelOps.normalizeBackground].
 */
object ScanFilters {
    private fun ch(c: Int, s: Int) = (c shr s) and 0xFF

    /** 3×3 binomial blur (1-2-1), per channel. */
    fun descreen(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(px.size)
        val k = intArrayOf(1, 2, 1)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (dy in -1..1) for (dx in -1..1) {
                val c = px[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]; val wt = k[dy + 1] * k[dx + 1]
                r += ch(c, 16) * wt; g += ch(c, 8) * wt; b += ch(c, 0) * wt
            }
            out[y * w + x] = (0xFF shl 24) or ((r / 16) shl 16) or ((g / 16) shl 8) or (b / 16)
        }
        return out
    }

    /** Unsharp mask: original + [amount] × (original − blur). */
    fun sharpen(px: IntArray, w: Int, h: Int, amount: Float = 0.8f): IntArray {
        val blur = descreen(px, w, h)
        return IntArray(px.size) { i ->
            val c = px[i]; val bl = blur[i]
            fun one(s: Int) = (ch(c, s) + amount * (ch(c, s) - ch(bl, s))).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (one(16) shl 16) or (one(8) shl 8) or one(0)
        }
    }

    /** Black and white: Sauvola threshold, packed 1-bit rows (MSB first, rows padded to a byte), 0 = black. */
    fun toBilevel(px: IntArray, w: Int, h: Int): ByteArray {
        val p = px.copyOf()
        PixelOps.sauvola(p, w, h)
        val rb = (w + 7) / 8
        val out = ByteArray(rb * h) { 0xFF.toByte() }
        for (y in 0 until h) for (x in 0 until w) if (PixelOps.lum(p[y * w + x]) < 128) out[y * rb + (x shr 3)] = (out[y * rb + (x shr 3)].toInt() and (0x80 ushr (x and 7)).inv()).toByte()
        return out
    }
}
