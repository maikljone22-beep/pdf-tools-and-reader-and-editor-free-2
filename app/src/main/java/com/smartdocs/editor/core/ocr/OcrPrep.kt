package com.smartdocs.editor.core.ocr

import android.content.Context
import android.graphics.Bitmap
import com.smartdocs.editor.core.tools5.PixelOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File

/** Binarisation before recognition (Nitro "fixed threshold"). */
enum class OcrThreshold { NONE, FIXED, ADAPTIVE }

/**
 * OCR settings shared by the reader OCR, OCR plus and batch OCR (Nitro OCR options): recognition quality ([fast] =
 * tessdata_fast), pre-processing ([deskew] the page, [orientation] = detect text direction and turn the page,
 * [threshold] with [thresholdValue], [smooth] colour images), special recognition ([numericOnly]) and the output
 * images ([outputDpi] 0 = keep, [outputQuality] JPEG quality).
 */
data class OcrTuning(
    val fast: Boolean = false,
    val deskew: Boolean = false,
    val orientation: Boolean = false,
    val threshold: OcrThreshold = OcrThreshold.NONE,
    val thresholdValue: Int = 160,
    val smooth: Boolean = false,
    val numericOnly: Boolean = false,
    val outputDpi: Int = 0,
    val outputQuality: Int = 75,
) {
    val changesPages: Boolean get() = deskew || orientation
    val filtersImage: Boolean get() = threshold != OcrThreshold.NONE || smooth

    fun toJson(): String = JSONObject().put("fast", fast).put("deskew", deskew).put("orient", orientation).put("thr", threshold.name)
        .put("thrV", thresholdValue).put("smooth", smooth).put("num", numericOnly).put("outDpi", outputDpi).put("outQ", outputQuality).toString()

    companion object {
        /** Characters Tesseract may return in numeric-only mode. */
        const val NUMERIC_CHARS = "0123456789٠١٢٣٤٥٦٧٨٩.,-+/:%()"

        fun fromJson(s: String?): OcrTuning = runCatching {
            val o = JSONObject(s ?: return OcrTuning())
            OcrTuning(o.optBoolean("fast"), o.optBoolean("deskew"), o.optBoolean("orient"),
                runCatching { OcrThreshold.valueOf(o.optString("thr")) }.getOrDefault(OcrThreshold.NONE), o.optInt("thrV", 160),
                o.optBoolean("smooth"), o.optBoolean("num"), o.optInt("outDpi"), o.optInt("outQ", 75))
        }.getOrDefault(OcrTuning())

        fun load(c: Context) = fromJson(c.getSharedPreferences("ocr", Context.MODE_PRIVATE).getString("tuning", null))
        fun save(c: Context, t: OcrTuning) = c.getSharedPreferences("ocr", Context.MODE_PRIVATE).edit().putString("tuning", t.toJson()).apply()
    }
}

/** Pre-processing maths on grey / ARGB pixel arrays (pure Kotlin, unit-tested) and the page-level corrections. */
object OcrPrep {

    /** Otsu threshold of grey values 0..255: the first value of the light class (dark pixels are `< threshold`). */
    fun otsu(gray: IntArray): Int {
        val hist = IntArray(256); gray.forEach { hist[it.coerceIn(0, 255)]++ }
        val total = gray.size.toDouble()
        var sum = 0.0; for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var t = 128
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = i }
        }
        return (t + 1).coerceAtMost(255)
    }

    /**
     * Skew of text lines in degrees (positive = the lines rise to the right), from the projection profile: the angle
     * whose horizontal ink profile has the largest variance. [gray] 0..255, searched in [-maxDeg, maxDeg].
     */
    fun skewAngle(gray: IntArray, w: Int, h: Int, maxDeg: Float = 10f): Float {
        val t = otsu(gray)
        val ink = ArrayList<Int>()   // packed y * w + x of dark pixels (sampled)
        val step = maxOf(1, (w.toLong() * h / 400_000L).toInt())
        var i = 0
        while (i < gray.size) { if (gray[i] < t) ink += i; i += step }
        if (ink.size < 50) return 0f
        fun score(deg: Double): Double {
            val r = Math.toRadians(deg); val s = Math.sin(r); val c = Math.cos(r)
            val bins = IntArray(h * 2 + 2)
            for (p in ink) {
                val x = p % w; val y = p / w
                val yy = (y * c + x * s).toInt() + h
                if (yy in bins.indices) bins[yy]++
            }
            var sum = 0.0; var sq = 0.0
            for (b in bins) { sum += b; sq += b.toDouble() * b }
            val n = bins.size.toDouble()
            return sq / n - (sum / n) * (sum / n)
        }
        var bestDeg = 0.0; var bestScore = -1.0
        var d = -maxDeg.toDouble()
        while (d <= maxDeg) { val sc = score(d); if (sc > bestScore) { bestScore = sc; bestDeg = d }; d += 0.5 }
        var f = bestDeg - 0.5
        while (f <= bestDeg + 0.5) { val sc = score(f); if (sc > bestScore) { bestScore = sc; bestDeg = f }; f += 0.1 }
        // y·cos d + x·sin d is constant along a line y = y0 − x·tan d: d is the rise angle of the lines
        return bestDeg.toFloat()
    }

    /** 3×3 median of grey values (Nitro "smooth colour image": removes dithering and specks before recognition). */
    fun median3(gray: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(gray.size); val win = IntArray(9)
        for (y in 0 until h) for (x in 0 until w) {
            var k = 0
            for (dy in -1..1) for (dx in -1..1) win[k++] = gray[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
            win.sort(); out[y * w + x] = win[4]
        }
        return out
    }

    /** Grey → black / white at [t]. */
    fun binarize(gray: IntArray, t: Int): IntArray = IntArray(gray.size) { if (gray[it] < t) 0 else 255 }

    private fun argbGray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** The bitmap after the image filters of [t] (a new bitmap; the input is not changed). */
    fun filter(src: Bitmap, t: OcrTuning): Bitmap {
        if (!t.filtersImage) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        var g = PixelOps.gray(px)
        if (t.smooth) g = median3(g, w, h)
        when (t.threshold) {
            OcrThreshold.FIXED -> g = binarize(g, t.thresholdValue.coerceIn(1, 254))
            OcrThreshold.ADAPTIVE -> {
                val p2 = IntArray(w * h) { argbGray(g[it]) }
                PixelOps.sauvola(p2, w, h)
                return Bitmap.createBitmap(p2, w, h, Bitmap.Config.ARGB_8888)
            }
            OcrThreshold.NONE -> {}
        }
        return Bitmap.createBitmap(IntArray(w * h) { argbGray(g[it]) }, w, h, Bitmap.Config.ARGB_8888)
    }

    /** audit 5 OCR-2: [filter] on Dispatchers.Default (callers may run on the main thread); [src] itself when nothing filters. */
    suspend fun filterAsync(src: Bitmap, t: OcrTuning): Bitmap =
        if (!t.filtersImage) src else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { filter(src, t) }

    /** Grey values of a bitmap scaled to at most [maxSide] px. Returns (gray, w, h). */
    fun smallGray(bmp: Bitmap, maxSide: Int = 1200): Triple<IntArray, Int, Int> {
        val k = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val s = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
        val px = IntArray(s.width * s.height); s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
        val r = Triple(PixelOps.gray(px), s.width, s.height)
        if (s !== bmp) s.recycle()
        return r
    }

    /**
     * Nitro "correct image skew" / "auto de-skew" for an existing PDF page whose text lines rise by [deg]: the content
     * is turned back by [deg] about the centre of the crop box (lossless: a transform around the content, nothing is
     * re-encoded). Plane rotations commute, so /Rotate does not change the direction.
     */
    fun straighten(doc: PDDocument, page: PDPage, deg: Float) {
        if (kotlin.math.abs(deg) < 0.05f) return
        val cb = page.cropBox
        val cx = cb.lowerLeftX + cb.width / 2f; val cy = cb.lowerLeftY + cb.height / 2f
        val m = com.smartdocs.editor.core.edit.Affine.rotateAround(-deg, cx, cy)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true).use { cs -> cs.saveGraphicsState(); cs.transform(m) }
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs -> cs.restoreGraphicsState() }
    }

    /** Best of the four orientations by recognition score (mean confidence × words found); ties keep 0°. */
    fun bestOrientation(scores: Map<Int, Float>): Int = scores.entries.maxWithOrNull(compareBy<Map.Entry<Int, Float>> { it.value }.thenBy { -it.key })?.key ?: 0

    /**
     * A copy of [src] with every page in [pages] straightened and/or turned upright, decided from a render of the page
     * ([render] gives a small bitmap of a page with its current rotation; [score] the recognition score of a bitmap).
     * Returns the corrected file and the number of pages changed. [onPage] reports progress.
     */
    suspend fun correctPages(src: File, password: String?, out: File, pages: List<Int>, t: OcrTuning,
                             render: suspend (Int) -> Bitmap?, score: suspend (Bitmap) -> Float, onPage: (Int, Int) -> Unit = { _, _ -> }): Int =
        // audit 5 OCR-2: rotations, de-skew measuring (CPU) on Default, the PdfBox load / save on IO — never the main thread
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val turns = HashMap<Int, Int>(); val angles = HashMap<Int, Float>()
        pages.forEachIndexed { n, pi ->
            onPage(n, pages.size)
            ensureActive()
            val b = render(pi) ?: return@forEachIndexed
            try {
                if (t.orientation) {
                    val scores = HashMap<Int, Float>()
                    for (rot in intArrayOf(0, 90, 180, 270)) {
                        val r = if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true)
                        scores[rot] = runCatching { score(r) }.getOrDefault(0f)
                        if (r !== b) r.recycle()
                    }
                    // clearly better only: a small edge is noise
                    val best = bestOrientation(scores)
                    if (best != 0 && (scores[best] ?: 0f) > (scores[0] ?: 0f) * 1.3f + 1f) turns[pi] = best
                }
                // a page turned by a quarter has vertical lines in this render: its skew is not measured
                if (t.deskew && (turns[pi] ?: 0) % 180 == 0) {
                    val (g, w, h) = smallGray(b)
                    val a = skewAngle(g, w, h)
                    if (kotlin.math.abs(a) >= 0.2f) angles[pi] = a
                }
            } finally { b.recycle() }
        }
        if (turns.isEmpty() && angles.isEmpty()) return@withContext 0
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.smartdocs.editor.core.pdf.PdfOps.load(src, password).use { doc ->
                angles.forEach { (pi, a) -> straighten(doc, doc.getPage(pi), a) }
                turns.forEach { (pi, rot) -> val p = doc.getPage(pi); p.rotation = ((p.rotation + rot) % 360 + 360) % 360 }
                doc.save(out)
            }
        }
        (turns.keys + angles.keys).size
    }
}
