package com.smartdocs.editor.core.convert6

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Small pure image codecs so the document writers can keep pictures they could not store as they are (convert audit
 * C36 / A-M5): BMP and TIFF become PNG. A platform decoder ([PortableImage.platform], set by the app from BitmapFactory)
 * handles the rest (WebP for DOCX, odd BMP variants). Pure.
 */
object PngEncoder {
    /** RGBA PNG of [argb] ([w]×[h]); alpha is kept only when some pixel is not opaque. */
    fun encode(w: Int, h: Int, argb: IntArray): ByteArray {
        val alpha = argb.any { (it ushr 24) != 0xFF }
        val bpp = if (alpha) 4 else 3
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw, Deflater(6)).use { z ->
            val row = ByteArray(1 + w * bpp)
            for (y in 0 until h) {
                row[0] = 0
                var o = 1
                for (x in 0 until w) {
                    val c = argb[y * w + x]
                    row[o++] = (c shr 16).toByte(); row[o++] = (c shr 8).toByte(); row[o++] = c.toByte()
                    if (alpha) row[o++] = (c ushr 24).toByte()
                }
                z.write(row)
            }
        }
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            d.writeInt(data.size); val t = type.toByteArray(Charsets.US_ASCII); d.write(t); d.write(data)
            d.writeInt(CRC32().apply { update(t); update(data) }.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { b -> DataOutputStream(b).apply { writeInt(w); writeInt(h); writeByte(8); writeByte(if (alpha) 6 else 2); writeByte(0); writeByte(0); writeByte(0) } }.toByteArray()
        chunk("IHDR", ihdr); chunk("IDAT", raw.toByteArray()); chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}

object BmpDecoder {
    /** Uncompressed 1/4/8/24/32-bit Windows BMP (BI_RGB / BI_BITFIELDS 32) → (w, h, argb), or null. */
    fun decode(b: ByteArray): Triple<Int, Int, IntArray>? = runCatching {
        if (b.size < 54 || b[0] != 'B'.code.toByte() || b[1] != 'M'.code.toByte()) return null
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun s32(o: Int) = CfbReader.le32(b, o)
        val dataOff = s32(10); val hdr = s32(14)
        val w = s32(18); val hRaw = s32(22); val bpp = u16(28); val comp = s32(30)
        if (w <= 0 || hRaw == 0 || w.toLong() * kotlin.math.abs(hRaw) > 40_000_000L) return null
        if (comp != 0 && !(comp == 3 && bpp == 32)) return null
        val h = kotlin.math.abs(hRaw); val bottomUp = hRaw > 0
        val palOff = 14 + hdr
        val colors = s32(46).let { if (it == 0 && bpp <= 8) 1 shl bpp else it }
        val pal = IntArray(if (bpp <= 8) colors else 0) { i -> val o = palOff + i * 4; if (o + 2 < b.size) (0xFF shl 24) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF) else 0xFF000000.toInt() }
        val stride = ((w * bpp + 31) / 32) * 4
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = dataOff + (if (bottomUp) h - 1 - y else y) * stride
            for (x in 0 until w) {
                px[y * w + x] = when (bpp) {
                    24 -> { val o = row + x * 3; (0xFF shl 24) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF) }
                    32 -> { val o = row + x * 4; (0xFF shl 24) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF) }
                    8 -> pal.getOrElse(b[row + x].toInt() and 0xFF) { 0xFF000000.toInt() }
                    4 -> pal.getOrElse((b[row + x / 2].toInt() shr (if (x % 2 == 0) 4 else 0)) and 0x0F) { 0xFF000000.toInt() }
                    1 -> pal.getOrElse((b[row + x / 8].toInt() shr (7 - x % 8)) and 1) { 0xFF000000.toInt() }
                    else -> return null
                }
            }
        }
        Triple(w, h, px)
    }.getOrNull()
}

/** Pictures in a form a document format can store. */
object PortableImage {

    /** Platform decoder (Android: BitmapFactory → PNG/JPEG); null on the JVM. Returns (bytes, ext) or null. */
    @Volatile var platform: ((ByteArray) -> Pair<ByteArray, String>?)? = null

    /** Pictures larger than this are not converted (memory). */
    private const val MAX_PIXELS = 30_000_000L

    /**
     * [bytes] as one of [allowed] extensions: kept when it already is one, else BMP / TIFF (first page) → PNG, else the
     * platform decoder; null = cannot be kept (the caller counts it as dropped).
     */
    fun normalize(bytes: ByteArray, ext: String, allowed: Set<String>): Pair<ByteArray, String>? {
        val e = (Images6.sniffExt(bytes) ?: ext).lowercase().let { if (it == "jpeg") "jpg" else if (it == "tiff") "tif" else it }
        if (e in allowed) return bytes to e
        if ("png" in allowed) {
            when (e) {
                "bmp" -> BmpDecoder.decode(bytes)?.let { (w, h, px) -> return PngEncoder.encode(w, h, px) to "png" }
                "tif" -> runCatching {
                    TiffDecoder.open(TiffDecoder.BytesSource(bytes)).use { t ->
                        if (t.pageCount == 0) return@runCatching null
                        val p = TiffDecoder.decodePage(t, 0, 4000, MAX_PIXELS)
                        val argb = p.argb
                        when {
                            argb != null -> PngEncoder.encode(p.width, p.height, argb) to "png"
                            p.jpeg?.size == 1 && "jpg" in allowed -> p.jpeg[0].bytes to "jpg"
                            else -> null
                        }
                    }
                }.getOrNull()?.let { return it }
            }
        }
        return runCatching { platform?.invoke(bytes) }.getOrNull()?.takeIf { it.second in allowed }
    }
}
