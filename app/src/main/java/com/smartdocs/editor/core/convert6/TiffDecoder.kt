package com.smartdocs.editor.core.convert6

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Baseline TIFF decoder for "image(s) → PDF": every page (IFD) of a multi-page TIFF, strips or tiles,
 * no compression / LZW / Deflate / PackBits, predictor 2, bilevel / grey / palette / RGB(A) / CMYK, 1-16 bits.
 * CCITT G3/G4 pages are reported as [TiffPage.ccitt] (the Android side embeds them with PdfBox CCITTFactory);
 * JPEG-in-TIFF is not decoded. Pure.
 */
class TiffPage(val width: Int, val height: Int, val argb: IntArray?, val ccitt: Boolean = false, val unsupported: Int = 0, val dpiX: Float = 0f)

object TiffDecoder {

    private class Ifd(val tags: Map<Int, LongArray>, val next: Long)

    private class Buf(val b: ByteArray, val le: Boolean) {
        fun u8(o: Long) = b[o.toInt()].toInt() and 0xFF
        fun u16(o: Long): Int = if (le) u8(o) or (u8(o + 1) shl 8) else (u8(o) shl 8) or u8(o + 1)
        fun u32(o: Long): Long = if (le) (u16(o).toLong() or (u16(o + 2).toLong() shl 16)) else ((u16(o).toLong() shl 16) or u16(o + 2).toLong())
    }

    private fun typeSize(t: Int) = when (t) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; 16, 17, 18 -> 8; else -> 1 }

    private fun readIfd(buf: Buf, off: Long): Ifd {
        val n = buf.u16(off)
        val tags = HashMap<Int, LongArray>()
        for (i in 0 until n) {
            val e = off + 2 + i * 12L
            val tag = buf.u16(e); val type = buf.u16(e + 2); val count = buf.u32(e + 4)
            val size = typeSize(type) * count
            val dataOff = if (size <= 4) e + 8 else buf.u32(e + 8)
            if (count > 10_000_000 || dataOff + size > buf.b.size) continue
            val vals = LongArray(count.toInt()) { k ->
                when (type) {
                    3 -> buf.u16(dataOff + k * 2L).toLong()
                    4 -> buf.u32(dataOff + k * 4L)
                    5 -> { val num = buf.u32(dataOff + k * 8L); val den = buf.u32(dataOff + k * 8L + 4); if (den == 0L) 0 else num / den }
                    else -> buf.u8(dataOff + k).toLong()
                }
            }
            tags[tag] = vals
        }
        return Ifd(tags, buf.u32(off + 2 + n * 12L))
    }

    fun pageCount(b: ByteArray): Int = ifds(b).second.size

    private fun ifds(b: ByteArray): Pair<Buf, List<Ifd>> {
        require(b.size >= 8) { "not a TIFF" }
        val le = b[0] == 'I'.code.toByte()
        val buf = Buf(b, le)
        require(buf.u16(2) == 42) { "not a TIFF (BigTIFF unsupported)" }
        val list = ArrayList<Ifd>()
        var off = buf.u32(4); val seen = HashSet<Long>()
        while (off in 8 until b.size && seen.add(off) && list.size < 2000) { val ifd = readIfd(buf, off); list += ifd; off = ifd.next }
        return buf to list
    }

    fun decode(b: ByteArray, maxPixels: Long = 60_000_000L): List<TiffPage> {
        val (_, list) = ifds(b)
        return list.map { runCatching { page(b, it, maxPixels) }.getOrElse { e -> TiffPage(0, 0, null, unsupported = -1).also { if (e is OutOfMemoryError) throw e } } }
    }

    private fun Ifd.v(tag: Int, def: Long = 0) = tags[tag]?.firstOrNull() ?: def

    private fun page(b: ByteArray, ifd: Ifd, maxPixels: Long): TiffPage {
        val w = ifd.v(256).toInt(); val h = ifd.v(257).toInt()
        val comp = ifd.v(259, 1).toInt()
        val dpi = ifd.v(282).toFloat()
        if (comp == 2 || comp == 3 || comp == 4) return TiffPage(w, h, null, ccitt = true, dpiX = dpi)
        if (comp !in setOf(1, 5, 8, 32946, 32773)) return TiffPage(w, h, null, unsupported = comp, dpiX = dpi)
        require(w > 0 && h > 0 && w.toLong() * h <= maxPixels) { "image too large" }
        val spp = ifd.v(277, 1).toInt().coerceIn(1, 8)
        val bps = ifd.v(258, 1).toInt()
        val photo = ifd.v(262, if (spp >= 3) 2 else 1).toInt()
        val predictor = ifd.v(317, 1).toInt()
        val planar = ifd.v(284, 1).toInt()
        if (planar != 1 && spp > 1) return TiffPage(w, h, null, unsupported = 284, dpiX = dpi)
        val tiled = ifd.tags.containsKey(322)
        val offsets = ifd.tags[if (tiled) 324 else 273] ?: throw IllegalArgumentException("no data")
        val counts = ifd.tags[if (tiled) 325 else 279] ?: LongArray(offsets.size) { (b.size - offsets[it]).coerceAtLeast(0) }
        val tw = if (tiled) ifd.v(322).toInt() else w
        val th = if (tiled) ifd.v(323).toInt() else ifd.v(278, h.toLong()).toInt().coerceIn(1, h)
        val rowBytes = (tw * spp * bps + 7) / 8
        val out = IntArray(w * h)
        val cmap = ifd.tags[320]
        val tilesAcross = if (tiled) (w + tw - 1) / tw else 1
        offsets.forEachIndexed { k, off ->
            val cnt = counts.getOrElse(k) { 0 }
            if (off < 0 || off >= b.size) return@forEachIndexed
            val raw = b.copyOfRange(off.toInt(), minOf(b.size.toLong(), off + cnt).toInt())
            val expected = rowBytes * th
            val data = when (comp) { 5 -> lzw(raw, expected); 8, 32946 -> inflate(raw, expected); 32773 -> packBits(raw, expected); else -> raw }
            if (predictor == 2 && bps == 8) for (r in 0 until th) { val base = r * rowBytes; for (i in spp until rowBytes) if (base + i < data.size) data[base + i] = (data[base + i] + data[base + i - spp]).toByte() }
            val x0 = if (tiled) (k % tilesAcross) * tw else 0
            val y0 = if (tiled) (k / tilesAcross) * th else k * th
            for (r in 0 until th) {
                val y = y0 + r; if (y >= h) break
                val bits = BitReader(data, r * rowBytes)
                for (c in 0 until tw) {
                    val x = x0 + c
                    val s = IntArray(spp) { bits.read(bps) }
                    if (x >= w) continue
                    out[y * w + x] = pixel(s, bps, photo, cmap)
                }
            }
        }
        return TiffPage(w, h, out, dpiX = dpi)
    }

    private class BitReader(val d: ByteArray, var byte: Int) {
        var bit = 0
        fun read(n: Int): Int {
            if (n == 8) { val v = if (byte < d.size) d[byte].toInt() and 0xFF else 0; byte++; return v }
            if (n == 16) { val v = if (byte + 1 < d.size) ((d[byte].toInt() and 0xFF) shl 8) or (d[byte + 1].toInt() and 0xFF) else 0; byte += 2; return v }
            var v = 0
            repeat(n) {
                val b = if (byte < d.size) (d[byte].toInt() shr (7 - bit)) and 1 else 0
                v = (v shl 1) or b
                bit++; if (bit == 8) { bit = 0; byte++ }
            }
            return v
        }
    }

    private fun to8(v: Int, bps: Int): Int = when (bps) {
        8 -> v
        16 -> v shr 8
        1 -> if (v != 0) 255 else 0
        else -> v * 255 / ((1 shl bps) - 1)
    }

    /** Note: 16-bit samples are read big-endian by [BitReader]; little-endian 16-bit files lose only low-order detail. */
    private fun pixel(s: IntArray, bps: Int, photo: Int, cmap: LongArray?): Int {
        fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
        return when (photo) {
            0 -> { val g = 255 - to8(s[0], bps); argb(255, g, g, g) }
            1 -> { val g = to8(s[0], bps); argb(if (s.size > 1) to8(s[1], bps) else 255, g, g, g) }
            3 -> {
                val n = 1 shl bps; val i = s[0]
                if (cmap != null && cmap.size >= 3 * n) argb(255, (cmap[i] shr 8).toInt(), (cmap[n + i] shr 8).toInt(), (cmap[2 * n + i] shr 8).toInt()) else { val g = to8(i, bps); argb(255, g, g, g) }
            }
            5 -> { val c = to8(s[0], bps); val m = to8(s.getOrElse(1) { 0 }, bps); val y = to8(s.getOrElse(2) { 0 }, bps); val k = to8(s.getOrElse(3) { 0 }, bps)
                argb(255, (255 - c) * (255 - k) / 255, (255 - m) * (255 - k) / 255, (255 - y) * (255 - k) / 255) }
            else -> if (s.size >= 3) argb(if (s.size >= 4) to8(s[3], bps) else 255, to8(s[0], bps), to8(s[1], bps), to8(s[2], bps)) else { val g = to8(s[0], bps); argb(255, g, g, g) }
        }
    }

    fun packBits(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected)
        var i = 0
        while (i < src.size && out.size() < expected) {
            val n = src[i++].toInt()
            when {
                n in 0..127 -> { val len = minOf(n + 1, src.size - i); out.write(src, i, len); i += len }
                n in -127..-1 -> { if (i < src.size) { val v = src[i++].toInt(); repeat(1 - n) { out.write(v) } } }
                else -> {}
            }
        }
        return out.toByteArray()
    }

    fun inflate(src: ByteArray, expected: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(src)
        val out = ByteArrayOutputStream(maxOf(expected, 1024)); val buf = ByteArray(65536)
        try { while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) } } finally { inf.end() }
        return out.toByteArray()
    }

    /** TIFF LZW (MSB-first codes, early change). */
    fun lzw(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(maxOf(expected, 1024))
        val table = arrayOfNulls<ByteArray>(4096)
        for (i in 0 until 256) table[i] = byteArrayOf(i.toByte())
        var next = 258; var width = 9
        var bitPos = 0L
        fun code(): Int {
            var v = 0
            for (k in 0 until width) {
                val byteIx = (bitPos shr 3).toInt()
                if (byteIx >= src.size) return 257
                val bit = (src[byteIx].toInt() shr (7 - (bitPos and 7).toInt())) and 1
                v = (v shl 1) or bit; bitPos++
            }
            return v
        }
        var prev: ByteArray? = null
        while (true) {
            val c = code()
            if (c == 257) break
            if (c == 256) { next = 258; width = 9; prev = null; continue }
            val entry = when {
                c < next && table[c] != null -> table[c]!!
                c == next && prev != null -> prev + prev[0]
                else -> break
            }
            out.write(entry)
            if (prev != null && next < 4096) { table[next++] = prev + entry[0] }
            prev = entry
            width = when { next + 1 >= 2048 -> 12; next + 1 >= 1024 -> 11; next + 1 >= 512 -> 10; else -> 9 }
            if (out.size() >= expected && expected > 0) break
        }
        return out.toByteArray()
    }
}
