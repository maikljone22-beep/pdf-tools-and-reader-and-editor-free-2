package com.smartdocs.editor.core.fillsign2

/**
 * Pure pixel / image-header helpers for QuickSign signatures (nitro-fillsign2), unit-tested: recolouring a saved
 * signature (Manage Signatures, Edit) and the resolution of an imported image ("Keep original size").
 */
object SignatureImages {
    /** Every visible pixel becomes [rgb]; its alpha (the ink coverage) is kept. */
    fun recolor(px: IntArray, rgb: Int): IntArray {
        val c = rgb and 0xFFFFFF
        return IntArray(px.size) { i -> val a = px[i] ushr 24; if (a == 0) 0 else (a shl 24) or c }
    }

    /** Resolution assumed for images that carry none (screenshots, most PNGs). */
    const val DEFAULT_DPI = 96f

    /**
     * Horizontal resolution (dots per inch) stored in a PNG (pHYs chunk, metre unit) or a JPEG (JFIF APP0 density),
     * or null when the header carries none.
     */
    fun dpi(bytes: ByteArray): Float? {
        fun u16(i: Int) = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
        fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        if (bytes.size > 8 && bytes.copyOfRange(0, 4).contentEquals(png)) {
            var i = 8
            while (i + 8 <= bytes.size) {
                val len = u32(i); val type = String(bytes, i + 4, 4, Charsets.ISO_8859_1)
                if (type == "pHYs" && i + 17 <= bytes.size) {
                    val ppu = u32(i + 8); val unit = bytes[i + 16].toInt()
                    return if (unit == 1 && ppu > 0) (ppu * 0.0254f).takeIf { it in 10f..10000f } else null
                }
                if (type == "IDAT" || type == "IEND" || len > bytes.size) return null
                i += 12 + len.toInt()
            }
            return null
        }
        val jfif = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        if (bytes.size > 18 && bytes.copyOfRange(0, 4).contentEquals(jfif) && String(bytes, 6, 4, Charsets.ISO_8859_1) == "JFIF") {
            val unit = bytes[13].toInt(); val x = u16(14)
            return when (unit) { 1 -> x.toFloat(); 2 -> x * 2.54f; else -> null }?.takeIf { it in 10f..10000f }
        }
        return null
    }

    /** Natural width in points of [pixels] image pixels at [dpi] (72 points per inch). */
    fun widthPt(pixels: Int, dpi: Float?): Float = pixels * 72f / (dpi ?: DEFAULT_DPI).coerceAtLeast(1f)
}
