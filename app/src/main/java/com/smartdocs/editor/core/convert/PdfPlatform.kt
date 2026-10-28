package com.smartdocs.editor.core.convert

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream

/*
 * Android side of the platform seam used by PdfContentExtractor.kt (the desktop harness has its own copy
 * with java.awt types). Keep the names identical.
 */

internal typealias P2 = android.graphics.PointF
internal typealias Winding = android.graphics.Path.FillType

internal fun newPoint(x: Float, y: Float): P2 = P2(x, y)

/**
 * Encoded bytes + extension ("jpeg" / "png") of a PDF image, downscaled to at most 2000 px per side, turned by
 * [rotate] degrees clockwise (after a horizontal flip when [mirror]) so it is upright on the converted page.
 */
internal fun encodePdfImage(img: PDImage, rotate: Int = 0, mirror: Boolean = false): Pair<ByteArray, String>? {
    if (img is PDImageXObject && rotate == 0 && !mirror) {
        val cs = runCatching { img.colorSpace?.name }.getOrNull()
        val smask = img.cosObject.containsKey(COSName.SMASK)
        if (PdfContentExtractor.isPlainJpeg(img.suffix, cs, smask) && maxOf(img.width, img.height) <= 4000) {
            val raw = runCatching { img.stream.createInputStream(listOf(COSName.DCT_DECODE.name)).use { it.readBytes() } }.getOrNull()
            if (raw != null && raw.size > 4 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()) return raw to "jpeg"
        }
    }
    var bmp: Bitmap = img.image ?: return null
    // one colour (or fully transparent) carries no picture: blank tiles, empty masks, white-on-white shading
    if (isUniform(bmp)) { bmp.recycle(); return null }
    val max = maxOf(bmp.width, bmp.height)
    if (max > 2000) {
        val s = 2000f / max
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        if (scaled !== bmp) bmp.recycle()
        bmp = scaled
    }
    if (rotate != 0 || mirror) {
        val m = android.graphics.Matrix()
        if (mirror) m.postScale(-1f, 1f)
        if (rotate != 0) m.postRotate(rotate.toFloat())
        val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (turned !== bmp) bmp.recycle()
        bmp = turned
    }
    val png = bmp.hasAlpha()
    val bos = ByteArrayOutputStream()
    bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, bos)
    bmp.recycle()
    return bos.toByteArray() to (if (png) "png" else "jpeg")
}

/** The colour (0xRRGGBB) of a tiny opaque image (a 2x2 shading / highlight tile), or null when it is transparent. */
internal fun solidColor(img: PDImage): Int? = runCatching {
    val bmp = img.image ?: return null
    try {
        val p = bmp.getPixel(0, 0)
        if ((p ushr 24) < 0x80) null else p and 0xFFFFFF
    } finally { bmp.recycle() }
}.getOrNull()

/**
 * Every sampled pixel (a grid of up to 48x48) looks the same — one colour, or nothing visible at all. A picture of text
 * (one colour through a glyph mask) is not uniform: its transparent and coloured pixels differ.
 */
internal fun isUniform(bmp: Bitmap): Boolean {
    val w = bmp.width; val h = bmp.height
    if (w <= 0 || h <= 0) return true
    val nx = minOf(48, w); val ny = minOf(48, h)
    var first = 0
    for (j in 0 until ny) for (i in 0 until nx) {
        val p0 = bmp.getPixel(i * (w - 1) / maxOf(1, nx - 1), j * (h - 1) / maxOf(1, ny - 1))
        val p = if ((p0 ushr 24) < 16) 0 else p0                // all transparent pixels are alike
        if (i == 0 && j == 0) { first = p; continue }
        if ((p == 0) != (first == 0) || (p != 0 && OcrText.colourDistance(p, first) > 24)) return false
    }
    return true
}
