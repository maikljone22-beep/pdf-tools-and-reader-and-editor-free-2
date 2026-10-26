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
