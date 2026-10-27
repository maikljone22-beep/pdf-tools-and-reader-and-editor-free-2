package com.smartdocs.editor.core.tools5

import java.io.ByteArrayOutputStream

/**
 * JBIG2 encoder for 1-bit images (Foxit optimizer "JBIG2 lossless"): one generic region (template 0, default
 * adaptive pixels, arithmetic MQ coding) in the embedded organisation a PDF /JBIG2Decode stream expects (page
 * information segment + immediate generic region segment, no file header, no globals).
 *
 * Input rows as for [Ccitt]: packed samples, MSB first, rows padded to a byte, 0 = black. JBIG2 codes black as 1,
 * so samples are inverted while coding; the decoded PDF image is the original.
 */
object Jbig2 {

    private val QE = intArrayOf(0x5601, 0x3401, 0x1801, 0x0AC1, 0x0521, 0x0221, 0x5601, 0x5401, 0x4801, 0x3801, 0x3001, 0x2401, 0x1C01, 0x1601,
        0x5601, 0x5401, 0x5101, 0x4801, 0x3801, 0x3401, 0x3001, 0x2801, 0x2401, 0x2201, 0x1C01, 0x1801, 0x1601, 0x1401, 0x1201, 0x1101,
        0x0AC1, 0x09C1, 0x08A1, 0x0521, 0x0441, 0x02A1, 0x0221, 0x0141, 0x0111, 0x0085, 0x0049, 0x0025, 0x0015, 0x0009, 0x0005, 0x0001, 0x5601)
    private val NMPS = intArrayOf(1, 2, 3, 4, 5, 38, 7, 8, 9, 10, 11, 12, 13, 29, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
        32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46)
    private val NLPS = intArrayOf(1, 6, 9, 12, 29, 33, 6, 14, 14, 14, 17, 18, 20, 21, 14, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
        29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 46)
    private val SWITCH = intArrayOf(1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0)

    /** The MQ arithmetic encoder of T.88 Annex E. */
    internal class Mq(contexts: Int) {
        private val index = IntArray(contexts)
        private val mps = IntArray(contexts)
        private var a = 0x8000
        private var c = 0L
        private var ct = 12
        private var b = 0
        private var started = false
        private val out = ByteArrayOutputStream()

        private fun next(x: Int) { if (started) out.write(b); started = true; b = x }

        private fun byteOut() {
            if (b == 0xFF) { next((c shr 20).toInt()); c = c and 0xFFFFF; ct = 7 }
            else if (c < 0x8000000L) { next((c shr 19).toInt()); c = c and 0x7FFFF; ct = 8 }
            else {
                b++
                if (b == 0xFF) { c = c and 0x7FFFFFF; next((c shr 20).toInt()); c = c and 0xFFFFF; ct = 7 }
                else { next((c shr 19).toInt()); c = c and 0x7FFFF; ct = 8 }
            }
        }

        private fun renorm() { do { a = a shl 1; c = c shl 1; ct--; if (ct == 0) byteOut() } while (a and 0x8000 == 0) }

        fun encode(cx: Int, d: Int) {
            val i = index[cx]; val qe = QE[i]
            if (d == mps[cx]) {
                a -= qe
                if (a and 0x8000 == 0) {
                    if (a < qe) a = qe else c += qe
                    index[cx] = NMPS[i]
                    renorm()
                } else c += qe
            } else {
                a -= qe
                if (a < qe) c += qe else a = qe
                if (SWITCH[i] == 1) mps[cx] = 1 - mps[cx]
                index[cx] = NLPS[i]
                renorm()
            }
        }

        fun flush(): ByteArray {
            val temp = c + a
            c = c or 0xFFFF
            if (c >= temp) c -= 0x8000
            c = c shl ct; byteOut()
            c = c shl ct; byteOut()
            if (b != 0xFF) next(0xFF)
            next(0xAC)
            out.write(b)
            return out.toByteArray()
        }
    }

    /** Template 0 with the default adaptive pixels, in decoding order (y, then x): the first is the high bit. */
    private val TEMPLATE = arrayOf(
        intArrayOf(-2, -2), intArrayOf(-1, -2), intArrayOf(0, -2), intArrayOf(1, -2), intArrayOf(2, -2),
        intArrayOf(-3, -1), intArrayOf(-2, -1), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1), intArrayOf(2, -1), intArrayOf(3, -1),
        intArrayOf(-4, 0), intArrayOf(-3, 0), intArrayOf(-2, 0), intArrayOf(-1, 0),
    )

    /** Arithmetic-coded generic region data (JBIG2 pixels: 1 = black). */
    internal fun genericRegion(data: ByteArray, w: Int, h: Int): ByteArray {
        val rb = (w + 7) / 8
        fun px(x: Int, y: Int): Int = if (x < 0 || y < 0 || x >= w) 0 else 1 - ((data[y * rb + (x shr 3)].toInt() shr (7 - (x and 7))) and 1)
        val mq = Mq(1 shl 16)
        for (y in 0 until h) for (x in 0 until w) {
            var cx = 0
            for (t in TEMPLATE) cx = (cx shl 1) or px(x + t[0], y + t[1])
            mq.encode(cx, px(x, y))
        }
        return mq.flush()
    }

    private fun u32(o: ByteArrayOutputStream, v: Int) { o.write(v ushr 24); o.write((v ushr 16) and 0xFF); o.write((v ushr 8) and 0xFF); o.write(v and 0xFF) }

    private fun segment(o: ByteArrayOutputStream, number: Int, type: Int, data: ByteArray) {
        u32(o, number); o.write(type and 0x3F); o.write(0); o.write(1); u32(o, data.size); o.write(data)
    }

    /** A complete /JBIG2Decode stream for the image. */
    fun encode(data: ByteArray, w: Int, h: Int): ByteArray {
        val rb = (w + 7) / 8
        require(data.size >= rb * h) { "short image data" }
        val out = ByteArrayOutputStream()
        val page = ByteArrayOutputStream().apply { u32(this, w); u32(this, h); u32(this, 0); u32(this, 0); write(0); write(0); write(0) }
        segment(out, 0, 48, page.toByteArray())
        val region = ByteArrayOutputStream().apply {
            u32(this, w); u32(this, h); u32(this, 0); u32(this, 0); write(0)          // region info: size, place, OR
            write(0)                                                                // MMR 0, template 0, TPGDON 0
            byteArrayOf(3, -1, -3, -1, 2, -2, -2, -2).forEach { write(it.toInt() and 0xFF) }
            write(genericRegion(data, w, h))
        }
        segment(out, 1, 38, region.toByteArray())
        return out.toByteArray()
    }
}

/** PDF /RunLengthDecode encoder (literal runs up to 128 bytes, repeats up to 128, EOD 128). */
object RunLength {
    fun encode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            var run = 1
            while (i + run < data.size && run < 128 && data[i + run] == data[i]) run++
            if (run >= 2) { out.write(257 - run); out.write(data[i].toInt() and 0xFF); i += run; continue }
            val start = i
            var lit = 0
            while (i < data.size && lit < 128 && !(i + 1 < data.size && data[i + 1] == data[i])) { i++; lit++ }
            if (lit == 0) { i++; lit = 1 }
            out.write(lit - 1); out.write(data, start, lit)
        }
        out.write(128)
        return out.toByteArray()
    }
}
