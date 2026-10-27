package com.smartdocs.editor.core.tools5

import java.io.ByteArrayOutputStream

/**
 * CCITT fax encoder for 1-bit images (Nitro optimizer "CCITT G3 / G4"): Group 4 (T.6, PDF /K -1) and Group 3
 * one-dimensional (T.4 Modified Huffman, PDF /K 0) with an EOL before every row (/EndOfLine true).
 *
 * Input rows are packed 1-bit samples, most significant bit first, each row padded to a byte (the layout of a PDF
 * image with /BitsPerComponent 1). With /BlackIs1 false (the default) a 0 sample is "black", so the decoder gives
 * back exactly the input samples and the image keeps its /Decode and colour space.
 */
object Ccitt {

    private class Bits {
        private val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun put(code: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                cur = (cur shl 1) or ((code shr i) and 1); n++
                if (n == 8) { out.write(cur); cur = 0; n = 0 }
            }
        }
        fun put(code: String) = put(code.toInt(2), code.length)
        fun bytes(): ByteArray { if (n > 0) { out.write(cur shl (8 - n)); cur = 0; n = 0 }; return out.toByteArray() }
    }

    private val WHITE_TERM = arrayOf(
        "00110101", "000111", "0111", "1000", "1011", "1100", "1110", "1111", "10011", "10100", "00111", "01000", "001000", "000011", "110100", "110101",
        "101010", "101011", "0100111", "0001100", "0001000", "0010111", "0000011", "0000100", "0101000", "0101011", "0010011", "0100100", "0011000", "00000010", "00000011", "00011010",
        "00011011", "00010010", "00010011", "00010100", "00010101", "00010110", "00010111", "00101000", "00101001", "00101010", "00101011", "00101100", "00101101", "00000100", "00000101", "00001010",
        "00001011", "01010010", "01010011", "01010100", "01010101", "00100100", "00100101", "01011000", "01011001", "01011010", "01011011", "01001010", "01001011", "00110010", "00110011", "00110100",
    )
    private val BLACK_TERM = arrayOf(
        "0000110111", "010", "11", "10", "011", "0011", "0010", "00011", "000101", "000100", "0000100", "0000101", "0000111", "00000100", "00000111", "000011000",
        "0000010111", "0000011000", "0000001000", "00001100111", "00001101000", "00001101100", "00000110111", "00000101000", "00000010111", "00000011000", "000011001010", "000011001011", "000011001100", "000011001101", "000001101000", "000001101001",
        "000001101010", "000001101011", "000011010010", "000011010011", "000011010100", "000011010101", "000011010110", "000011010111", "000001101100", "000001101101", "000011011010", "000011011011", "000001010100", "000001010101", "000001010110", "000001010111",
        "000001100100", "000001100101", "000001010010", "000001010011", "000000100100", "000000110111", "000000111000", "000000100111", "000000101000", "000001011000", "000001011001", "000000101011", "000000101100", "000001011010", "000001100110", "000001100111",
    )
    /** Make-up codes for 64, 128 … 1728. */
    private val WHITE_MAKEUP = arrayOf(
        "11011", "10010", "010111", "0110111", "00110110", "00110111", "01100100", "01100101", "01101000", "01100111", "011001100", "011001101", "011010010", "011010011",
        "011010100", "011010101", "011010110", "011010111", "011011000", "011011001", "011011010", "011011011", "010011000", "010011001", "010011010", "011000", "010011011",
    )
    private val BLACK_MAKEUP = arrayOf(
        "0000001111", "000011001000", "000011001001", "000001011011", "000000110011", "000000110100", "000000110101", "0000001101100", "0000001101101", "0000001001010",
        "0000001001011", "0000001001100", "0000001001101", "0000001110010", "0000001110011", "0000001110100", "0000001110101", "0000001110110", "0000001110111", "0000001010010",
        "0000001010011", "0000001010100", "0000001010101", "0000001011010", "0000001011011", "0000001100100", "0000001100101",
    )
    /** Extended make-up codes (both colours) for 1792, 1856 … 2560. */
    private val EXT_MAKEUP = arrayOf(
        "00000001000", "00000001100", "00000001101", "000000010010", "000000010011", "000000010100", "000000010101", "000000010110", "000000010111", "000000011100",
        "000000011101", "000000011110", "000000011111",
    )
    private const val EOL = "000000000001"

    private fun run(b: Bits, length: Int, white: Boolean) {
        var n = length
        while (n >= 2560) { b.put(EXT_MAKEUP.last()); n -= 2560 }
        if (n >= 1792) { b.put(EXT_MAKEUP[(n - 1792) / 64]); n %= 64 }
        else if (n >= 64) { b.put((if (white) WHITE_MAKEUP else BLACK_MAKEUP)[n / 64 - 1]); n %= 64 }
        b.put((if (white) WHITE_TERM else BLACK_TERM)[n])
    }

    /** True when sample [x] of a row is "white" (bit 1). */
    private fun white(data: ByteArray, off: Int, x: Int) = (data[off + (x shr 3)].toInt() shr (7 - (x and 7)) and 1) == 1

    /**
     * Positions where the colour changes along a row, starting from white before pixel 0: even entries turn black,
     * odd entries turn white. Followed by [width] twice as the imaginary ends.
     */
    internal fun changes(data: ByteArray, off: Int, width: Int): IntArray {
        val list = ArrayList<Int>()
        var w = true
        for (x in 0 until width) { val c = white(data, off, x); if (c != w) { list += x; w = c } }
        list += width; list += width
        return list.toIntArray()
    }

    fun encodeG4(data: ByteArray, width: Int, height: Int): ByteArray {
        val rowBytes = (width + 7) / 8
        require(data.size >= rowBytes * height) { "short image data" }
        val b = Bits()
        var ref = intArrayOf(width, width)
        for (y in 0 until height) {
            val cur = changes(data, y * rowBytes, width)
            var a0 = -1
            var white = true
            var ci = 0     // index into cur of the first change > a0
            var r = 0      // index into ref of the first change > a0 (a0 only grows, so r only moves forward)
            while (a0 < width) {
                while (ci < cur.size - 1 && cur[ci] <= a0) ci++
                val a1 = cur[ci]
                // b1: first change on the reference line right of a0 into the opposite colour (black for white a0: even index)
                while (r < ref.size - 1 && ref[r] <= a0) r++
                val ri = if ((r % 2 == 0) == white) r else (r + 1).coerceAtMost(ref.size - 1)
                val b1 = ref[ri]
                val b2 = if (ri + 1 < ref.size) ref[ri + 1] else width
                when {
                    b2 < a1 -> { b.put("0001"); a0 = b2 }
                    kotlin.math.abs(a1 - b1) <= 3 -> {
                        b.put(when (a1 - b1) { 0 -> "1"; 1 -> "011"; 2 -> "000011"; 3 -> "0000011"; -1 -> "010"; -2 -> "000010"; else -> "0000010" })
                        a0 = a1; white = !white
                    }
                    else -> {
                        val a2 = if (ci + 1 < cur.size) cur[ci + 1] else width
                        b.put("001")
                        run(b, a1 - maxOf(a0, 0), white)
                        run(b, a2 - a1, !white)
                        a0 = a2
                    }
                }
            }
            ref = cur
        }
        b.put(EOL); b.put(EOL)   // EOFB
        return b.bytes()
    }

    /** Group 3 1-D with an EOL in front of every row (decode with /K 0 /EndOfLine true). */
    fun encodeG3(data: ByteArray, width: Int, height: Int): ByteArray {
        val rowBytes = (width + 7) / 8
        require(data.size >= rowBytes * height) { "short image data" }
        val b = Bits()
        for (y in 0 until height) {
            b.put(EOL)
            val ch = changes(data, y * rowBytes, width)
            var pos = 0; var white = true; var i = 0
            while (pos < width) {
                val next = minOf(ch[i], width)
                run(b, next - pos, white)
                pos = next; white = !white; i++
            }
            if (width == 0) run(b, 0, true)
        }
        repeat(6) { b.put(EOL) }   // RTC
        return b.bytes()
    }

    /**
     * Box-filter downsample of a packed 1-bit image to [nw]×[nh]: a target pixel is black when at least half of the
     * source pixels it covers are black.
     */
    fun downsample(data: ByteArray, width: Int, height: Int, nw: Int, nh: Int): ByteArray {
        val src = (width + 7) / 8; val dst = (nw + 7) / 8
        val out = ByteArray(dst * nh)
        for (y in 0 until nh) {
            val y0 = y * height / nh; val y1 = maxOf(y0 + 1, (y + 1) * height / nh)
            for (x in 0 until nw) {
                val x0 = x * width / nw; val x1 = maxOf(x0 + 1, (x + 1) * width / nw)
                var black = 0; var all = 0
                for (yy in y0 until y1) for (xx in x0 until x1) { all++; if (!white(data, yy * src, xx)) black++ }
                if (black * 2 < all) out[y * dst + (x shr 3)] = (out[y * dst + (x shr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
            }
        }
        return out
    }
}
