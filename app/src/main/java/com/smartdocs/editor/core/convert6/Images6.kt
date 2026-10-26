package com.smartdocs.editor.core.convert6

/** Image output formats of the hub. */
enum class ImgFmt(val ext: String, val mime: String) { JPG("jpg", "image/jpeg"), PNG("png", "image/png"), WEBP("webp", "image/webp") }

enum class PageSize6(val w: Float, val h: Float) { A4(595.28f, 841.89f), LETTER(612f, 792f), LEGAL(612f, 1008f), A3(841.89f, 1190.55f), A5(419.53f, 595.28f), IMAGE(0f, 0f) }
enum class Orient6 { AUTO, PORTRAIT, LANDSCAPE }
enum class Fit6 { CONTAIN, COVER, STRETCH, ORIGINAL }

/** Placement of a picture on a page, PDF points, origin bottom-left. */
data class Place(val pageW: Float, val pageH: Float, val x: Float, val y: Float, val w: Float, val h: Float)

/** Pure image decisions: formats, sizes, page placement, N-up grids. */
object Images6 {

    fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; "bmp" -> "image/bmp"
        "tif", "tiff" -> "image/tiff"; "jp2", "jpx" -> "image/jp2"; "heic", "heif" -> "image/heif"; "emf" -> "image/x-emf"; "wmf" -> "image/x-wmf"
        else -> "image/jpeg"
    }

    /** Extension from the magic bytes of an embedded picture, or null. */
    fun sniffExt(b: ByteArray): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E -> "png"
            at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "gif"
            at(0) == 'R'.code && at(1) == 'I'.code && at(8) == 'W'.code && at(9) == 'E'.code -> "webp"
            at(0) == 'B'.code && at(1) == 'M'.code -> "bmp"
            (at(0) == 0x49 && at(1) == 0x49 && at(2) == 0x2A) || (at(0) == 0x4D && at(1) == 0x4D && at(3) == 0x2A) -> "tif"
            at(0) == 0x00 && at(1) == 0x00 && at(2) == 0x00 && at(3) == 0x0C && at(4) == 'j'.code -> "jp2"
            at(0) == 0xFF && at(1) == 0x4F && at(2) == 0xFF && at(3) == 0x51 -> "jp2"
            at(0) == 0x01 && at(1) == 0x00 && at(2) == 0x00 && at(3) == 0x00 && at(40) == 0x20 && at(41) == 0x45 -> "emf"
            at(0) == 0xD7 && at(1) == 0xCD && at(2) == 0xC6 && at(3) == 0x9A -> "wmf"
            at(0) == '<'.code && String(b, 0, minOf(b.size, 256), Charsets.ISO_8859_1).contains("<svg") -> "svg"
            else -> null
        }
    }

    /**
     * Output format of an image conversion. [target] null = "keep": JPG stays JPG, pictures with alpha become PNG,
     * HEIC/TIFF/BMP/GIF (not writable on Android) become JPG, or PNG when they have alpha.
     */
    fun outFormat(srcExt: String, hasAlpha: Boolean, target: ImgFmt?): ImgFmt {
        if (target != null) return target
        return when (srcExt.lowercase()) {
            "jpg", "jpeg", "jfif" -> ImgFmt.JPG
            "png" -> ImgFmt.PNG
            "webp" -> ImgFmt.WEBP
            else -> if (hasAlpha) ImgFmt.PNG else ImgFmt.JPG
        }
    }

    /** Size after "fit within [maxW]×[maxH]" (never enlarges; 0 = no limit on that side). */
    fun fitWithin(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        var k = 1.0
        if (maxW > 0 && w > maxW) k = minOf(k, maxW.toDouble() / w)
        if (maxH > 0 && h > maxH) k = minOf(k, maxH.toDouble() / h)
        return maxOf(1, Math.round(w * k).toInt()) to maxOf(1, Math.round(h * k).toInt())
    }

    /** Size after scaling by [percent] (1..100). */
    fun scale(w: Int, h: Int, percent: Int): Pair<Int, Int> {
        val p = percent.coerceIn(1, 100) / 100.0
        return maxOf(1, Math.round(w * p).toInt()) to maxOf(1, Math.round(h * p).toInt())
    }

    /** Power-of-two sample size so a decode of [w]×[h] stays at or above [targetMax] on its long side. */
    fun sampleSize(w: Int, h: Int, targetMax: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= targetMax && s < 64) s *= 2
        return s
    }

    /**
     * Page and picture rectangle for an image of [imgW]×[imgH] px (at [dpi] for [PageSize6.IMAGE] / [Fit6.ORIGINAL]).
     * [margin] in points.
     */
    fun place(imgW: Int, imgH: Int, size: PageSize6, orient: Orient6, fit: Fit6, margin: Float, dpi: Float = 96f): Place {
        val natW = imgW * 72f / dpi; val natH = imgH * 72f / dpi
        if (size == PageSize6.IMAGE) return Place(natW + 2 * margin, natH + 2 * margin, margin, margin, natW, natH)
        val landscape = when (orient) { Orient6.LANDSCAPE -> true; Orient6.PORTRAIT -> false; Orient6.AUTO -> imgW > imgH }
        val pw = if (landscape) maxOf(size.w, size.h) else minOf(size.w, size.h)
        val ph = if (landscape) minOf(size.w, size.h) else maxOf(size.w, size.h)
        val bw = (pw - 2 * margin).coerceAtLeast(1f); val bh = (ph - 2 * margin).coerceAtLeast(1f)
        val (w, h) = when (fit) {
            Fit6.STRETCH -> bw to bh
            Fit6.COVER -> { val k = maxOf(bw / imgW, bh / imgH); imgW * k to imgH * k }
            Fit6.ORIGINAL -> { val k = minOf(1f, minOf(bw / natW, bh / natH)); natW * k to natH * k }
            Fit6.CONTAIN -> { val k = minOf(bw / imgW, bh / imgH); imgW * k to imgH * k }
        }
        return Place(pw, ph, margin + (bw - w) / 2f, margin + (bh - h) / 2f, w, h)
    }

    /** Columns × rows of an N-up sheet (2 → 1×2, 4 → 2×2, 6 → 2×3, 9 → 3×3); landscape sheets swap. */
    fun grid(n: Int, landscapeSheet: Boolean): Pair<Int, Int> {
        val (c, r) = when (n) { 1 -> 1 to 1; 2 -> 1 to 2; 3 -> 1 to 3; 4 -> 2 to 2; 6 -> 2 to 3; 8 -> 2 to 4; 9 -> 3 to 3; 16 -> 4 to 4; else -> {
            val c0 = Math.ceil(Math.sqrt(n.toDouble())).toInt(); c0 to Math.ceil(n / c0.toDouble()).toInt() } }
        return if (landscapeSheet) r to c else c to r
    }

    /**
     * Cells of an N-up sheet (PDF points, origin bottom-left), in reading order: left→right, or right→left for [rtl];
     * top row first. Each cell is (x, y, w, h).
     */
    fun nUpCells(n: Int, sheetW: Float, sheetH: Float, margin: Float, gap: Float, rtl: Boolean): List<FloatArray> {
        val (cols, rows) = grid(n, sheetW > sheetH)
        val cw = (sheetW - 2 * margin - gap * (cols - 1)) / cols
        val ch = (sheetH - 2 * margin - gap * (rows - 1)) / rows
        val out = ArrayList<FloatArray>()
        for (r in 0 until rows) for (c0 in 0 until cols) {
            if (out.size >= n) break
            val c = if (rtl) cols - 1 - c0 else c0
            val x = margin + c * (cw + gap)
            val y = sheetH - margin - (r + 1) * ch - r * gap
            out += floatArrayOf(x, y, cw, ch)
        }
        return out
    }

    /** Fit a [w]×[h] box inside a cell, centred: (x, y, w, h). */
    fun fitInCell(w: Float, h: Float, cell: FloatArray): FloatArray {
        val k = minOf(cell[2] / w, cell[3] / h)
        val fw = w * k; val fh = h * k
        return floatArrayOf(cell[0] + (cell[2] - fw) / 2, cell[1] + (cell[3] - fh) / 2, fw, fh)
    }

    /** "Long image" stitch: pages scaled to a common width (≤ [width]) so the total stays under [maxPixels]. Returns (width, per-page heights). */
    fun stitch(sizes: List<Pair<Float, Float>>, width: Int, maxPixels: Long): Pair<Int, List<Int>> {
        var w = width
        fun heights(wd: Int) = sizes.map { (pw, ph) -> maxOf(1, Math.round(ph * wd / pw)) }
        var hs = heights(w)
        while (hs.sum().toLong() * w > maxPixels && w > 64) { w = (w * 0.8f).toInt(); hs = heights(w) }
        return w to hs
    }
}
