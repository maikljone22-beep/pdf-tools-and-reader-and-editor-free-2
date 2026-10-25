package com.smartdocs.editor.ui.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class PaperSize { A4, LETTER, FIT }
enum class ColorMode { COLOR, GRAY, BW }

/** Turns scanned page images into a PDF with paper size / colour mode / JPEG quality options. */
object ScanPdfBuilder {

    fun colorMatrix(mode: ColorMode): ColorMatrix? = when (mode) {
        ColorMode.COLOR -> null
        ColorMode.GRAY -> ColorMatrix().apply { setSaturation(0f) }
        ColorMode.BW -> {
            // grayscale, then a strong contrast boost around mid-grey so the result is close to pure black/white
            val gray = ColorMatrix().apply { setSaturation(0f) }
            val c = 4f; val t = (1f - c) * 128f + 40f
            val contrast = ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
            ColorMatrix().apply { postConcat(gray); postConcat(contrast) }
        }
    }

    fun applyColorMode(src: Bitmap, mode: ColorMode): Bitmap {
        val m = colorMatrix(mode) ?: return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }

    /** Decode [images] (EXIF-rotated, downscaled), apply colour mode, and write one page per image into [out]. */
    suspend fun build(images: List<File>, out: File, paper: PaperSize, mode: ColorMode, quality: Int, onProgress: ((Int) -> Unit)? = null) = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            images.forEachIndexed { i, f ->
                onProgress?.invoke(i)
                val raw = PdfOps.decodeScaled(f, 2200) ?: return@forEachIndexed
                val bmp = applyColorMode(raw, mode)
                if (bmp !== raw) raw.recycle()
                val img = JPEGFactory.createFromImage(doc, bmp, (quality.coerceIn(30, 100)) / 100f)
                val portrait = bmp.height >= bmp.width
                val base = when (paper) { PaperSize.A4 -> PDRectangle.A4; PaperSize.LETTER -> PDRectangle.LETTER; PaperSize.FIT -> null }
                val rect = when {
                    base == null -> PDRectangle(bmp.width.toFloat() * 72f / 200f, bmp.height.toFloat() * 72f / 200f)
                    portrait -> base
                    else -> PDRectangle(base.height, base.width)
                }
                val page = PDPage(rect); doc.addPage(page)
                val scale = minOf(rect.width / bmp.width, rect.height / bmp.height)
                val w = bmp.width * scale; val h = bmp.height * scale
                PDPageContentStream(doc, page).use { it.drawImage(img, (rect.width - w) / 2, (rect.height - h) / 2, w, h) }
                bmp.recycle()
            }
            doc.save(out)
        }
    }

    /** Small preview of a page image with the colour mode applied (for the list). */
    fun preview(f: File, mode: ColorMode, maxDim: Int = 400): Bitmap? {
        val raw = PdfOps.decodeScaled(f, maxDim) ?: return null
        val out = applyColorMode(raw, mode)
        if (out !== raw) raw.recycle()
        return out
    }
}
