package com.smartdocs.editor.ui.cloud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Minimal QR Code encoder: byte mode, error correction level M, versions 1–10 (up to 213 bytes).
 * Follows ISO/IEC 18004 (structure after Project Nayuki's reference implementation).
 */
class QrMatrix(val size: Int, private val modules: Array<BooleanArray>) {
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]
}

object QrEncoder {
    // per version (index = version): total codewords, EC codewords per block, number of blocks — level M
    private val TOTAL = intArrayOf(0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
    private val ECC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    private val ALIGN = arrayOf(intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
        intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50))

    private fun dataCodewords(v: Int) = TOTAL[v] - ECC_PER_BLOCK[v] * BLOCKS[v]

    /** Encodes UTF-8 text; null when it does not fit in version 10-M. */
    fun encode(text: String): QrMatrix? = encode(text.toByteArray(Charsets.UTF_8))

    fun encode(data: ByteArray): QrMatrix? {
        var version = 1
        while (true) {
            val countBits = if (version < 10) 8 else 16
            if (4 + countBits + data.size * 8 <= dataCodewords(version) * 8) break
            if (++version > 10) return null
        }
        // ---- bit stream ----
        val bits = ArrayList<Boolean>()
        fun append(value: Int, len: Int) { for (i in len - 1 downTo 0) bits += ((value ushr i) and 1) != 0 }
        append(0b0100, 4)
        append(data.size, if (version < 10) 8 else 16)
        data.forEach { append(it.toInt() and 0xFF, 8) }
        val capacity = dataCodewords(version) * 8
        append(0, minOf(4, capacity - bits.size))
        append(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacity) { append(pad, 8); pad = pad xor 0xEC xor 0x11 }
        val codewords = IntArray(bits.size / 8) { i -> var b = 0; for (j in 0 until 8) b = (b shl 1) or (if (bits[i * 8 + j]) 1 else 0); b }

        val all = addEccAndInterleave(codewords, version)
        val q = Builder(version)
        q.drawFunctionPatterns()
        q.drawCodewords(all)
        var best = 0; var bestPenalty = Int.MAX_VALUE
        for (m in 0 until 8) {
            q.applyMask(m); q.drawFormatBits(m)
            val p = q.penalty()
            if (p < bestPenalty) { bestPenalty = p; best = m }
            q.applyMask(m) // undo
        }
        q.applyMask(best); q.drawFormatBits(best)
        return QrMatrix(q.size, q.modules)
    }

    private fun addEccAndInterleave(data: IntArray, version: Int): IntArray {
        val numBlocks = BLOCKS[version]; val eccLen = ECC_PER_BLOCK[version]; val raw = TOTAL[version]
        val numShort = numBlocks - raw % numBlocks
        val shortLen = raw / numBlocks
        val divisor = rsDivisor(eccLen)
        val blocks = ArrayList<IntArray>()
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortLen - eccLen + (if (i < numShort) 0 else 1)
            val dat = data.copyOfRange(k, k + datLen); k += datLen
            val ecc = rsRemainder(dat, divisor)
            val block = IntArray(shortLen + 1)
            System.arraycopy(dat, 0, block, 0, dat.size)
            System.arraycopy(ecc, 0, block, block.size - eccLen, eccLen)
            blocks += block
        }
        val out = ArrayList<Int>(raw)
        for (i in 0 until shortLen + 1) for (j in blocks.indices) {
            if (i != shortLen - eccLen || j >= numShort) out += blocks[j][i]
        }
        return out.toIntArray()
    }

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }
        return z
    }

    private fun rsDivisor(degree: Int): IntArray {
        val r = IntArray(degree); r[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in r.indices) { r[j] = gfMul(r[j], root); if (j + 1 < r.size) r[j] = r[j] xor r[j + 1] }
            root = gfMul(root, 0x02)
        }
        return r
    }

    private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
        val r = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor r[0]
            System.arraycopy(r, 1, r, 0, r.size - 1); r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor gfMul(divisor[i], factor)
        }
        return r
    }

    private class Builder(val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; isFunction[y][x] = true }

        fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = ALIGN[version]; val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
            drawFormatBits(0)
            if (version >= 7) {
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (version shl 12) or rem
                for (i in 0 until 18) {
                    val bit = ((bits ushr i) and 1) != 0
                    val a = size - 11 + i % 3; val b = i / 3
                    set(a, b, bit); set(b, a, bit)
                }
            }
        }

        private fun finder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(abs(dx), abs(dy)); val xx = x + dx; val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) set(xx, yy, d != 2 && d != 4)
            }
        }

        fun drawFormatBits(mask: Int) {
            val data = (0 shl 3) or mask // level M = 0b00
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        fun drawCodewords(data: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = ((data[i ushr 3] ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
            }
        }

        fun penalty(): Int {
            var result = 0
            for (pass in 0..1) for (a in 0 until size) {
                var runColor = false; var run = 0
                val history = IntArray(7)
                for (b in 0 until size) {
                    val c = if (pass == 0) modules[a][b] else modules[b][a]
                    if (c == runColor) { run++; if (run == 5) result += 3 else if (run > 5) result++ }
                    else {
                        addHistory(run, history)
                        if (!runColor) result += countPatterns(history) * 40
                        runColor = c; run = 1
                    }
                }
                var len = run
                if (runColor) { addHistory(len, history); len = 0 }
                len += size
                addHistory(len, history)
                result += countPatterns(history) * 40
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            var dark = 0
            for (row in modules) for (m in row) if (m) dark++
            val total = size * size
            val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
            return result + k * 10
        }

        private fun addHistory(run: Int, h: IntArray) {
            var len = run
            if (h[0] == 0) len += size
            System.arraycopy(h, 0, h, 1, h.size - 1)
            h[0] = len
        }

        private fun countPatterns(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }
    }
}

/** Draws [text] as a QR code (dark modules on white, 4-module quiet zone). Square: give it a width. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrEncoder.encode(text) } ?: return
    Canvas(modifier.aspectRatio(1f)) {
        drawRect(Color.White)
        val cells = qr.size + 8
        val cell = size.minDimension / cells
        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x, y]) {
            drawRect(Color.Black, topLeft = Offset((x + 4) * cell, (y + 4) * cell), size = Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
