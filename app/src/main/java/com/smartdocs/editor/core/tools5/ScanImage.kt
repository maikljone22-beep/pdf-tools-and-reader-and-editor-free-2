package com.smartdocs.editor.core.tools5

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Scan filters of the studio. */
enum class ScanFilter { ORIGINAL, AUTO, GRAY, BW, WHITEBOARD, NO_SHADOW, RECEIPT, ENHANCE }

/** Per-page edits; [rotation] is clockwise 0/90/180/270. */
/** Edits of one scanned picture; [erase] rects are fractions (left, top, right, bottom) of the rotated picture. */
data class PageEdit(val filter: ScanFilter = ScanFilter.AUTO, val brightness: Float = 0f, val contrast: Float = 1f, val rotation: Int = 0,
                    val despeckle: Boolean = false, val erase: List<FloatArray> = emptyList(),
                    /** Straighten the text lines of a curved page (book / notebook), see [PageDewarp]. */
                    val flatten: Boolean = false)

/** Default filter for a scan kind. */
fun ScanKind.defaultFilter(): ScanFilter = when (this) {
    ScanKind.WHITEBOARD -> ScanFilter.WHITEBOARD
    ScanKind.RECEIPT -> ScanFilter.RECEIPT
    ScanKind.ID_CARD, ScanKind.BUSINESS_CARD -> ScanFilter.AUTO
    // text pages: white paper, darker sharper text, pictures kept in colour
    ScanKind.DOCUMENT, ScanKind.BOOK, ScanKind.BATCH, ScanKind.FORM -> ScanFilter.ENHANCE
    else -> ScanFilter.AUTO
}

/** Bitmap side of the scan studio: decoding with EXIF, filters, rotation, ID-card page, book split. */
object ScanImage {

    /** Decode [f] scaled so the long side is ≤ [maxDim], EXIF orientation applied. */
    fun load(f: File, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val raw = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val deg = runCatching {
            when (ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
            }
        }.getOrDefault(0)
        var b = if (deg != 0) rotate(raw, deg) else raw
        val long = maxOf(b.width, b.height)
        if (long > maxDim) {
            val k = maxDim.toFloat() / long
            val s = Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
            if (s !== b) b.recycle()
            b = s
        }
        return b
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(d.toFloat()) }, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Apply [edit] (rotation, filter, brightness/contrast). May recycle [src]; returns a mutable ARGB bitmap. */
    fun apply(src: Bitmap, edit: PageEdit, dewarp: Boolean = edit.flatten): Bitmap {
        val b0 = rotate(src, edit.rotation)
        val b = if (b0.isMutable && b0.config == Bitmap.Config.ARGB_8888) b0 else b0.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== b0) b0.recycle() }
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        if (dewarp) PageDewarp.flatten(px, w, h)
        when (edit.filter) {
            ScanFilter.ORIGINAL -> {}
            // uneven light (a shadow, a lamp on one side) is evened out first
            ScanFilter.AUTO -> { if (PixelOps.unevenLight(px, w, h)) PixelOps.normalizeBackground(px, w, h); PixelOps.autoLevels(px) }
            ScanFilter.GRAY -> { PixelOps.toGray(px); PixelOps.autoLevels(px) }
            ScanFilter.BW -> {
                PixelOps.normalizeBackground(px, w, h)
                ScanFilters.sharpen(px, w, h, 0.7f).copyInto(px)     // crisper strokes before thresholding
                PixelOps.sauvola(px, w, h)
                ScanClean.despeckle(px, w, h)                        // no salt-and-pepper noise left
            }
            ScanFilter.ENHANCE -> ScanEnhance.enhance(px, w, h, (0.7f + (edit.contrast - 1f) * 0.6f).coerceIn(0.2f, 1f))
            ScanFilter.WHITEBOARD -> { PixelOps.normalizeBackground(px, w, h); PixelOps.saturate(px, 1.6f); PixelOps.brightnessContrast(px, 0f, 1.25f) }
            ScanFilter.NO_SHADOW -> { PixelOps.normalizeBackground(px, w, h); PixelOps.autoLevels(px, 0.005f, 0.995f) }
            ScanFilter.RECEIPT -> { PixelOps.toGray(px); PixelOps.normalizeBackground(px, w, h); PixelOps.brightnessContrast(px, 0f, 1.5f) }
        }
        // ENHANCE uses the contrast slider as its strength
        if (edit.filter != ScanFilter.BW) PixelOps.brightnessContrast(px, edit.brightness, if (edit.filter == ScanFilter.ENHANCE) 1f else edit.contrast)
        // old plan K12 / K8: dust removal and the eraser
        if (edit.despeckle) ScanClean.despeckle(px, w, h)
        if (edit.erase.isNotEmpty()) ScanClean.erase(px, w, h, edit.erase)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    /** Flattens one page picture (a half of a book spread) in place. */
    fun flatten(b: Bitmap): Bitmap {
        val m = if (b.isMutable && b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(m.width * m.height); m.getPixels(px, 0, m.width, 0, 0, m.width, m.height)
        if (PageDewarp.flatten(px, m.width, m.height)) m.setPixels(px, 0, m.width, 0, 0, m.width, m.height)
        return m
    }

    /** True for filters whose output is grey (stored as grey JPEG / lossless). */
    fun isGray(f: ScanFilter) = f == ScanFilter.GRAY || f == ScanFilter.BW || f == ScanFilter.RECEIPT

    /** Small preview with the edit applied (for thumbnails). */
    fun preview(f: File, edit: PageEdit, maxDim: Int = 480): Bitmap? = load(f, maxDim)?.let { apply(it, edit) }

    /**
     * ID card: front above back on one A4 page (200 dpi canvas), each at real ID-1 size (85.6 × 54 mm), centred.
     */
    fun idCardPage(front: Bitmap, back: Bitmap?): Bitmap {
        val dpi = 200f
        val pw = (210f / 25.4f * dpi).toInt(); val ph = (297f / 25.4f * dpi).toInt()
        val page = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        val cw = 85.6f / 25.4f * dpi; val ch = 54f / 25.4f * dpi
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        fun place(b: Bitmap, cy: Float) {
            val land = if (b.height > b.width) rotate(b.copy(Bitmap.Config.ARGB_8888, false), 90) else b
            val k = minOf(cw / land.width, ch / land.height)
            val w = land.width * k; val h = land.height * k
            val l = (pw - w) / 2f; val t = cy - h / 2f
            c.drawBitmap(land, Rect(0, 0, land.width, land.height), RectF(l, t, l + w, t + h), paint)
            if (land !== b) land.recycle()
        }
        if (back != null) { place(front, ph * 0.3f); place(back, ph * 0.62f) } else place(front, ph * 0.35f)
        return page
    }

    /**
     * Book spread → two pages split at the binding. [rightFirst] (Arabic books) returns the right page first.
     * A picture that is taller than wide is not a spread and is returned as is.
     */
    fun splitBook(src: Bitmap, rightFirst: Boolean): List<Bitmap> {
        if (src.width < src.height * 1.15f) return listOf(src)
        val w = src.width; val h = src.height
        val small = Bitmap.createScaledBitmap(src, minOf(w, 800), (h * minOf(w, 800).toFloat() / w).toInt().coerceAtLeast(1), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val cut = (PixelOps.bindingColumn(px, small.width, small.height) * w.toFloat() / small.width).toInt().coerceIn(w / 4, w * 3 / 4)
        if (small !== src) small.recycle()
        val left = Bitmap.createBitmap(src, 0, 0, cut, h)
        val right = Bitmap.createBitmap(src, cut, 0, w - cut, h)
        return if (rightFirst) listOf(right, left) else listOf(left, right)
    }
}
