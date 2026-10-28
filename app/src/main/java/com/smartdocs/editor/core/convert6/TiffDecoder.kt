package com.smartdocs.editor.core.convert6

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * Baseline TIFF decoder for "image(s) → PDF" and the image tools: every page (IFD) of a multi-page TIFF, strips or tiles,
 * no compression / LZW / Deflate / PackBits, predictor 2, bilevel / grey / palette / RGB(A) / CMYK, 1-16 bits.
 * CCITT G3/G4 pages are reported as [TiffPage.ccitt] (the Android side embeds them with PdfBox CCITTFactory).
 * JPEG-in-TIFF (compression 7, and old-style 6 with an interchange stream) is returned as complete JPEG streams
 * ([TiffPage.jpeg]) that BitmapFactory / PdfBox JPEGFactory read as they are (convert audit A-M16 / C23).
 *
 * Memory (audit A-H1 / C7): the file is read through a [Source] (a RandomAccessFile on Android) and ONE page is decoded
 * at a time ([decodePage]), row by row, straight into a picture that is subsampled while decoding to at most [maxDim]
 * pixels on the long side — a 12-page 300-dpi scan no longer needs every page in memory at once. Pure.
 */
class TiffPage(
    val width: Int, val height: Int, val argb: IntArray?, val ccitt: Boolean = false, val unsupported: Int = 0, val dpiX: Float = 0f,
    /** JPEG-compressed page: complete JPEG streams with their top-left position in the page (strips or tiles). */
    val jpeg: List<JpegPiece>? = null,
    /** Size of the source page (width/height above are after subsampling). */
    val srcWidth: Int = width, val srcHeight: Int = height,
)

class JpegPiece(val bytes: ByteArray, val x: Int, val y: Int)

object TiffDecoder {

    /** Random access to the TIFF bytes. */
    interface Source : Closeable {
        val size: Long
        /** Up to [len] bytes at [off] (fewer at the end of the file). */
        fun read(off: Long, len: Int): ByteArray
        override fun close() {}
    }

    class BytesSource(private val b: ByteArray) : Source {
        override val size: Long get() = b.size.toLong()
        override fun read(off: Long, len: Int): ByteArray {
            if (off < 0 || off >= b.size) return ByteArray(0)
            val end = minOf(b.size.toLong(), off + len).toInt()
            return b.copyOfRange(off.toInt(), end)
        }
    }

    class FileSource(f: File) : Source {
        private val raf = RandomAccessFile(f, "r")
        override val size: Long = raf.length()
        override fun read(off: Long, len: Int): ByteArray {
            if (off < 0 || off >= size) return ByteArray(0)
            val n = minOf(size - off, len.toLong()).toInt()
            val out = ByteArray(n)
            raf.seek(off); raf.readFully(out)
            return out
        }
        override fun close() = raf.close()
    }

    internal class Ifd(val tags: Map<Int, LongArray>, val next: Long)

    internal class Buf(val src: Source, val le: Boolean) {
        private var cacheOff = -1L
        private var cache = ByteArray(0)
        private fun ensure(o: Long, n: Int) {
            if (o >= cacheOff && o + n <= cacheOff + cache.size) return
            cacheOff = o; cache = src.read(o, maxOf(n, 64 * 1024))
        }
        fun u8(o: Long): Int { ensure(o, 1); val i = (o - cacheOff).toInt(); return if (i < cache.size) cache[i].toInt() and 0xFF else 0 }
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
            if (count > 4_000_000 || dataOff + size > buf.src.size) continue
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

    /** An opened TIFF: the page directories (no pixel data read yet). */
    class TiffFile internal constructor(internal val src: Source, internal val buf: Buf, internal val ifds: List<Ifd>) : Closeable {
        val pageCount: Int get() = ifds.size
        override fun close() = src.close()
    }

    fun open(src: Source): TiffFile {
        require(src.size >= 8) { "not a TIFF" }
        val head = src.read(0, 8)
        val le = head[0] == 'I'.code.toByte()
        val buf = Buf(src, le)
        require(buf.u16(2) == 42) { "not a TIFF (BigTIFF unsupported)" }
        val list = ArrayList<Ifd>()
        var off = buf.u32(4); val seen = HashSet<Long>()
        while (off in 8 until src.size && seen.add(off) && list.size < 2000) { val ifd = readIfd(buf, off); list += ifd; off = ifd.next }
        return TiffFile(src, buf, list)
    }

    fun open(f: File): TiffFile = FileSource(f).let { s -> try { open(s) } catch (e: Throwable) { s.close(); throw e } }

    fun pageCount(b: ByteArray): Int = open(BytesSource(b)).pageCount

    /** Every page at full size (small files and tests; the app decodes page by page with [decodePage]). */
    fun decode(b: ByteArray, maxPixels: Long = 60_000_000L): List<TiffPage> {
        val t = open(BytesSource(b))
        return (0 until t.pageCount).map { decodePage(t, it, Int.MAX_VALUE, maxPixels) }
    }

    private fun Ifd.v(tag: Int, def: Long = 0) = tags[tag]?.firstOrNull() ?: def

    /** Pixel size and resolution of page [index] without decoding it. */
    fun pageSize(t: TiffFile, index: Int): Triple<Int, Int, Float> { val ifd = t.ifds[index]; return Triple(ifd.v(256).toInt(), ifd.v(257).toInt(), ifd.v(282).toFloat()) }

    /** Subsampling step so a [w]×[h] page fits [maxDim] on its long side and [maxPixels] in total. */
    fun step(w: Int, h: Int, maxDim: Int, maxPixels: Long): Int {
        var s = 1
        while ((maxOf(w, h) + s - 1) / s > maxDim || ((w + s - 1L) / s) * ((h + s - 1L) / s) > maxPixels) s++
        return s
    }

    /**
     * Page [index]: an ARGB picture subsampled to at most [maxDim] px on the long side (and [maxPixels] in total),
     * or the JPEG streams of a JPEG-compressed page, or a CCITT / unsupported marker. A broken page gives
     * `unsupported = -1` instead of failing the whole file. [cancelled] is polled per strip.
     */
    fun decodePage(t: TiffFile, index: Int, maxDim: Int = Int.MAX_VALUE, maxPixels: Long = 60_000_000L, cancelled: () -> Boolean = { false }): TiffPage =
        try { page(t, t.ifds[index], maxDim, maxPixels, cancelled) }
        catch (e: OutOfMemoryError) { throw e }
        catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) { TiffPage(0, 0, null, unsupported = -1) }

    private val ADOBE_RGB = byteArrayOf(0xFF.toByte(), 0xEE.toByte(), 0, 14, 'A'.code.toByte(), 'd'.code.toByte(), 'o'.code.toByte(), 'b'.code.toByte(), 'e'.code.toByte(), 0, 100, 0, 0, 0, 0, 0)

    /** A complete JPEG from a TIFF strip / tile and the shared JPEGTables (both without their duplicate SOI / EOI). */
    fun mergeJpeg(tables: ByteArray?, strip: ByteArray, rgb: Boolean = false): ByteArray {
        fun soi(b: ByteArray) = b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8
        val out = ByteArrayOutputStream(strip.size + (tables?.size ?: 0) + 32)
        out.write(0xFF); out.write(0xD8)
        if (rgb) out.write(ADOBE_RGB)   // photometric RGB: tell the decoder not to convert from YCbCr
        if (tables != null && tables.size > 4 && soi(tables)) {
            var end = tables.size
            if ((tables[end - 2].toInt() and 0xFF) == 0xFF && (tables[end - 1].toInt() and 0xFF) == 0xD9) end -= 2
            out.write(tables, 2, end - 2)
        }
        if (soi(strip)) out.write(strip, 2, strip.size - 2) else out.write(strip)
        return out.toByteArray()
    }

    private fun page(t: TiffFile, ifd: Ifd, maxDim: Int, maxPixels: Long, cancelled: () -> Boolean): TiffPage {
        val src = t.src
        val w = ifd.v(256).toInt(); val h = ifd.v(257).toInt()
        val comp = ifd.v(259, 1).toInt()
        val dpi = ifd.v(282).toFloat()
        val spp = ifd.v(277, 1).toInt().coerceIn(1, 8)
        val photo = ifd.v(262, if (spp >= 3) 2 else 1).toInt()
        val tiled = ifd.tags.containsKey(322)
        if (comp == 2 || comp == 3 || comp == 4) return TiffPage(w, h, null, ccitt = true, dpiX = dpi)
        if (comp == 6 || comp == 7) {
            // old-style JPEG with a complete interchange stream, or new-style strips / tiles + JPEGTables
            ifd.tags[513]?.firstOrNull()?.let { off -> val len = ifd.v(514).toInt(); if (len > 0) return TiffPage(w, h, null, dpiX = dpi, jpeg = listOf(JpegPiece(src.read(off, len), 0, 0))) }
            if (comp == 6) return TiffPage(w, h, null, unsupported = 6, dpiX = dpi)
            val tables = ifd.tags[347]?.let { v -> ByteArray(v.size) { v[it].toByte() } }
            val offsets = ifd.tags[if (tiled) 324 else 273] ?: return TiffPage(w, h, null, unsupported = 7, dpiX = dpi)
            val counts = ifd.tags[if (tiled) 325 else 279] ?: return TiffPage(w, h, null, unsupported = 7, dpiX = dpi)
            val tw = if (tiled) ifd.v(322).toInt() else w
            val th = if (tiled) ifd.v(323).toInt() else ifd.v(278, h.toLong()).toInt().coerceIn(1, h)
            val across = if (tiled) (w + tw - 1) / tw else 1
            val pieces = offsets.mapIndexed { k, off ->
                if (cancelled()) throw java.util.concurrent.CancellationException()
                val data = src.read(off, counts.getOrElse(k) { 0L }.toInt())
                JpegPiece(mergeJpeg(tables, data, rgb = photo == 2), if (tiled) (k % across) * tw else 0, if (tiled) (k / across) * th else k * th)
            }
            return TiffPage(w, h, null, dpiX = dpi, jpeg = pieces)
        }
        if (comp !in setOf(1, 5, 8, 32946, 32773)) return TiffPage(w, h, null, unsupported = comp, dpiX = dpi)
        require(w > 0 && h > 0) { "empty page" }
        val bps = ifd.v(258, 1).toInt()
        val predictor = ifd.v(317, 1).toInt()
        val planar = ifd.v(284, 1).toInt()
        if (planar != 1 && spp > 1) return TiffPage(w, h, null, unsupported = 284, dpiX = dpi)
        val offsets = ifd.tags[if (tiled) 324 else 273] ?: throw IllegalArgumentException("no data")
        val counts = ifd.tags[if (tiled) 325 else 279] ?: LongArray(offsets.size) { (src.size - offsets[it]).coerceAtLeast(0) }
        val tw = if (tiled) ifd.v(322).toInt() else w
        val th = if (tiled) ifd.v(323).toInt() else ifd.v(278, h.toLong()).toInt().coerceIn(1, h)
        val rowBytes = (tw * spp * bps + 7) / 8
        val s = step(w, h, maxDim, maxPixels)
        val ow = (w + s - 1) / s; val oh = (h + s - 1) / s
        val out = IntArray(ow * oh)
        val cmap = ifd.tags[320]
        val tilesAcross = if (tiled) (w + tw - 1) / tw else 1
        val sample = IntArray(spp)
        offsets.forEachIndexed { k, off ->
            if (cancelled()) throw java.util.concurrent.CancellationException()
            val cnt = counts.getOrElse(k) { 0 }
            if (off < 0 || off >= src.size) return@forEachIndexed
            val x0 = if (tiled) (k % tilesAcross) * tw else 0
            val y0 = if (tiled) (k / tilesAcross) * th else k * th
            val rows = RowSink(rowBytes, th) { r, row ->
                val y = y0 + r
                if (y < h && y % s == 0) {
                    if (predictor == 2 && bps == 8) for (i in spp until rowBytes) row[i] = (row[i] + row[i - spp]).toByte()
                    val bits = BitReader(row, 0)
                    val oy = y / s
                    for (c in 0 until tw) {
                        val x = x0 + c
                        for (q in 0 until spp) sample[q] = bits.read(bps)
                        if (x >= w || x % s != 0) continue
                        out[oy * ow + x / s] = pixel(sample, bps, photo, cmap)
                    }
                }
            }
            when (comp) {
                5 -> lzwTo(src.read(off, cnt.toInt()), rows)
                8, 32946 -> inflateTo(src.read(off, cnt.toInt()), rows)
                32773 -> packBitsTo(src.read(off, cnt.toInt()), rows)
                else -> {   // uncompressed: read in chunks, never the whole strip
                    var pos = 0L
                    val total = minOf(cnt, rowBytes.toLong() * th)
                    while (pos < total && !rows.full) { val n = minOf(1L shl 20, total - pos).toInt(); val b = src.read(off + pos, n); if (b.isEmpty()) break; rows.write(b, 0, b.size); pos += n }
                }
            }
        }
        return TiffPage(ow, oh, out, dpiX = if (dpi > 0f) dpi / s else 0f, srcWidth = w, srcHeight = h)
    }

    /** Collects decompressed bytes into rows of [rowBytes] and hands each finished row to [onRow]. */
    private class RowSink(val rowBytes: Int, val rows: Int, val onRow: (Int, ByteArray) -> Unit) {
        private val row = ByteArray(maxOf(1, rowBytes))
        private var fill = 0
        private var r = 0
        val full get() = r >= rows
        fun write(b: ByteArray, off: Int, len: Int) {
            var i = off; val end = off + len
            while (i < end && r < rows) {
                val n = minOf(rowBytes - fill, end - i)
                System.arraycopy(b, i, row, fill, n); fill += n; i += n
                if (fill == rowBytes) { onRow(r++, row); fill = 0 }
            }
        }
        fun write(v: Int) { if (r >= rows) return; row[fill++] = v.toByte(); if (fill == rowBytes) { onRow(r++, row); fill = 0 } }
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

    private fun packBitsTo(src: ByteArray, out: RowSink) {
        var i = 0
        while (i < src.size && !out.full) {
            val n = src[i++].toInt()
            when {
                n in 0..127 -> { val len = minOf(n + 1, src.size - i); out.write(src, i, len); i += len }
                n in -127..-1 -> { if (i < src.size) { val v = src[i++].toInt(); repeat(1 - n) { out.write(v) } } }
                else -> {}
            }
        }
    }

    fun packBits(src: ByteArray, expected: Int): ByteArray {
        val bos = ByteArrayOutputStream(expected)
        val sink = RowSink(1, expected) { _, row -> bos.write(row[0].toInt()) }
        packBitsTo(src, sink)
        return bos.toByteArray()
    }

    private fun inflateTo(src: ByteArray, out: RowSink) {
        val inf = Inflater()
        inf.setInput(src)
        val buf = ByteArray(65536)
        try { while (!inf.finished() && !out.full) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) } } finally { inf.end() }
    }

    fun inflate(src: ByteArray, expected: Int): ByteArray {
        val bos = ByteArrayOutputStream(maxOf(expected, 1024))
        inflateTo(src, RowSink(1, if (expected > 0) expected else Int.MAX_VALUE) { _, row -> bos.write(row[0].toInt()) })
        return bos.toByteArray()
    }

    /** TIFF LZW (MSB-first codes, early change), streamed into [out]. */
    private fun lzwTo(src: ByteArray, out: RowSink) {
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
        while (!out.full) {
            val c = code()
            if (c == 257) break
            if (c == 256) { next = 258; width = 9; prev = null; continue }
            val entry = when {
                c < next && table[c] != null -> table[c]!!
                c == next && prev != null -> prev + prev[0]
                else -> break
            }
            out.write(entry, 0, entry.size)
            if (prev != null && next < 4096) { table[next++] = prev + entry[0] }
            prev = entry
            width = when { next + 1 >= 2048 -> 12; next + 1 >= 1024 -> 11; next + 1 >= 512 -> 10; else -> 9 }
        }
    }

    fun lzw(src: ByteArray, expected: Int): ByteArray {
        val bos = ByteArrayOutputStream(maxOf(expected, 1024))
        lzwTo(src, RowSink(1, if (expected > 0) expected else Int.MAX_VALUE) { _, row -> bos.write(row[0].toInt()) })
        return bos.toByteArray()
    }
}
