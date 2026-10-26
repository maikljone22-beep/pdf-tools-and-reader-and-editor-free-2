package com.smartdocs.editor.core.edit5

import android.content.Context
import android.graphics.RectF
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Which page box a crop writes. CROP changes what viewers show and print; the others are print-production boxes. */
enum class BoxKind { CROP, TRIM, BLEED, ART }

/** Margins in points (display orientation: left/top/right/bottom as the user sees the page). */
data class Margins(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

object PageBoxOps {

    suspend fun pageInfos(src: File, password: String?): List<PageInfo> = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc -> doc.pages.map { PageInfo.of(it) } }
    }

    /**
     * Real crop: set the page box from a display-space rect (points, origin bottom-left of the current visible area)
     * on [pages]; null [rect] uses [margins] instead. The content is not removed (other apps can reset the crop).
     */
    suspend fun crop(src: File, out: File, password: String?, pages: List<Int>, rect: RectF?, margins: Margins?, box: BoxKind = BoxKind.CROP) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            for (i in pages) {
                if (i !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(i)
                val info = PageInfo.of(page)
                val r = rect ?: margins?.let { m -> RectF(m.left, info.displayH - m.top, info.displayW - m.right, m.bottom) } ?: continue
                if (kotlin.math.abs(r.width()) < 4f || kotlin.math.abs(r.top - r.bottom) < 4f) continue
                setBox(page, box, clampToMedia(page, E5Geom.rect(info.rectToUser(r))))
            }
        }
    }

    /** Remove white margins automatically (rendered with PDFium per page), keeping [pad] points around the content. */
    suspend fun autoCrop(context: Context, src: File, out: File, password: String?, pages: List<Int>, pad: Float = 6f, sameForAll: Boolean = false, onProgress: (Float) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val found = HashMap<Int, RectF>()   // display rects
        val session = PdfSession.open(context, src, password)
        try {
            pages.forEachIndexed { k, i ->
                if (i !in 0 until session.pageCount) return@forEachIndexed
                val (w, h) = session.pageSize(i)
                val scale = (900f / maxOf(w, h)).coerceAtMost(2f)
                val bw = (w * scale).toInt().coerceAtLeast(1); val bh = (h * scale).toInt().coerceAtLeast(1)
                val bmp = session.render(i, bw, bh, annotations = true)
                val px = IntArray(bw * bh); bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
                session.invalidateCache()
                MarginDetect.contentBounds(px, bw, bh, ignore = 2)?.let { b ->
                    found[i] = RectF(b[0] / scale - pad, h - b[1] / scale + pad, (b[2] + 1) / scale + pad, h - (b[3] + 1) / scale - pad)
                }
                onProgress((k + 1f) / pages.size)
            }
        } finally { session.closeAsync() }
        if (found.isEmpty()) { if (out.absolutePath != src.absolutePath) src.copyTo(out, overwrite = true); return@withContext 0 }
        if (sameForAll) {
            val u = RectF(found.values.minOf { it.left }, found.values.maxOf { it.top }, found.values.maxOf { it.right }, found.values.minOf { it.bottom })
            found.keys.toList().forEach { found[it] = u }
        }
        E5Io.edit(src, out, password) { doc ->
            found.forEach { (i, r) ->
                val page = doc.getPage(i)
                val info = PageInfo.of(page)
                val c = RectF(r.left.coerceAtLeast(0f), r.top.coerceAtMost(info.displayH), r.right.coerceAtMost(info.displayW), r.bottom.coerceAtLeast(0f))
                setBox(page, BoxKind.CROP, clampToMedia(page, E5Geom.rect(info.rectToUser(c))))
            }
        }
        found.size
    }

    /** Reset crop (and optionally trim/bleed/art) boxes to the media box. */
    suspend fun resetBoxes(src: File, out: File, password: String?, pages: List<Int>, all: Boolean = true) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            for (i in pages) {
                val d = doc.getPage(i).cosObject
                d.removeItem(COSName.CROP_BOX)
                if (all) { d.removeItem(COSName.TRIM_BOX); d.removeItem(COSName.BLEED_BOX); d.removeItem(COSName.ART_BOX) }
            }
        }
    }

    private fun setBox(page: PDPage, box: BoxKind, r: PDRectangle) = when (box) {
        BoxKind.CROP -> page.cropBox = r
        BoxKind.TRIM -> page.trimBox = r
        BoxKind.BLEED -> page.bleedBox = r
        BoxKind.ART -> page.artBox = r
    }

    private fun clampToMedia(page: PDPage, r: PDRectangle): PDRectangle {
        val m = page.mediaBox
        val x0 = r.lowerLeftX.coerceIn(m.lowerLeftX, m.upperRightX); val y0 = r.lowerLeftY.coerceIn(m.lowerLeftY, m.upperRightY)
        val x1 = r.upperRightX.coerceIn(m.lowerLeftX, m.upperRightX); val y1 = r.upperRightY.coerceIn(m.lowerLeftY, m.upperRightY)
        return PDRectangle(x0, y0, (x1 - x0).coerceAtLeast(1f), (y1 - y0).coerceAtLeast(1f))
    }

    // ---------------- resize ----------------

    /** Paper sizes in points (portrait). */
    val PAPERS: List<Pair<String, Pair<Float, Float>>> = listOf(
        "A3" to (841.89f to 1190.55f), "A4" to (595.28f to 841.89f), "A5" to (419.53f to 595.28f), "A6" to (297.64f to 419.53f),
        "B5" to (498.9f to 708.66f), "Letter" to (612f to 792f), "Legal" to (612f to 1008f), "Tabloid" to (792f to 1224f),
    )

    enum class ResizeMode { FIT, FILL, CENTER, STRETCH }

    /**
     * Resize pages to [w]×[h] points (display orientation follows each page unless [forceOrientation]).
     * The page content (and annotations) is scaled into the new size: FIT keeps everything visible, FILL covers
     * the page, CENTER keeps the original scale, STRETCH distorts to fill.
     */
    suspend fun resize(src: File, out: File, password: String?, pages: List<Int>, w: Float, h: Float, mode: ResizeMode = ResizeMode.FIT, forceOrientation: Boolean = false, margin: Float = 0f) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            for (i in pages) {
                if (i !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(i)
                val info = PageInfo.of(page)
                // target in the page's unrotated user orientation
                val wantLandscape = if (forceOrientation) w > h else info.landscape
                var tw = minOf(w, h); var th = maxOf(w, h)
                if (wantLandscape) { val t = tw; tw = th; th = t }
                if (info.rotation % 180 != 0) { val t = tw; tw = th; th = t }
                val sw = info.cropW; val sh = info.cropH
                val aw = tw - 2 * margin; val ah = th - 2 * margin
                var sx = aw / sw; var sy = ah / sh
                when (mode) {
                    ResizeMode.FIT -> { val s = minOf(sx, sy); sx = s; sy = s }
                    ResizeMode.FILL -> { val s = maxOf(sx, sy); sx = s; sy = s }
                    ResizeMode.CENTER -> { sx = 1f; sy = 1f }
                    ResizeMode.STRETCH -> Unit
                }
                val tx = (tw - sw * sx) / 2f - info.cropX * sx
                val ty = (th - sh * sy) / 2f - info.cropY * sy
                val m = Matrix(sx, 0f, 0f, sy, tx, ty)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true).use { cs -> cs.saveGraphicsState(); cs.transform(m) }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs -> cs.restoreGraphicsState() }
                runCatching {
                    page.annotations.forEach { a ->
                        val r = a.rectangle ?: return@forEach
                        a.rectangle = PDRectangle(r.lowerLeftX * sx + tx, r.lowerLeftY * sy + ty, r.width * sx, r.height * sy)
                        a.quadPointsOf()?.let { q -> for (k in q.indices) q[k] = if (k % 2 == 0) q[k] * sx + tx else q[k] * sy + ty; a.cosObject.setItem(COSName.getPDFName("QuadPoints"), com.tom_roush.pdfbox.cos.COSArray().apply { setFloatArray(q) }) }
                    }
                }
                val nb = PDRectangle(0f, 0f, tw, th)
                page.mediaBox = nb
                page.cosObject.removeItem(COSName.CROP_BOX); page.cosObject.removeItem(COSName.TRIM_BOX); page.cosObject.removeItem(COSName.BLEED_BOX); page.cosObject.removeItem(COSName.ART_BOX)
            }
        }
    }

    private fun com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation.quadPointsOf(): FloatArray? =
        (cosObject.getDictionaryObject(COSName.getPDFName("QuadPoints")) as? com.tom_roush.pdfbox.cos.COSArray)?.toFloatArray()

    // ---------------- page labels ----------------

    suspend fun readLabels(src: File, password: String?): Pair<List<LabelRange>, Int> = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            val pl = runCatching { doc.documentCatalog.pageLabels }.getOrNull()
            val ranges = ArrayList<LabelRange>()
            if (pl != null) for (start in pl.pageIndices) {
                val r = pl.getPageLabelRange(start) ?: continue
                val style = LabelStyle.values().firstOrNull { it.pdf != null && it.pdf == r.style } ?: LabelStyle.NONE
                ranges += LabelRange(start, style, r.prefix ?: "", r.start.coerceAtLeast(1))
            }
            ranges to doc.numberOfPages
        }
    }

    /** Write page labels ([ranges] empty removes them). The first range is forced to start at page 0 (required by the spec). */
    suspend fun writeLabels(src: File, out: File, password: String?, ranges: List<LabelRange>) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val sorted = ranges.filter { it.startPage in 0 until doc.numberOfPages }.sortedBy { it.startPage }.distinctBy { it.startPage }
            if (sorted.isEmpty()) { doc.documentCatalog.cosObject.removeItem(COSName.PAGE_LABELS); return@edit }
            val pl = PDPageLabels(doc)
            val list = if (sorted.first().startPage != 0) listOf(LabelRange(0)) + sorted else sorted
            list.forEach { r ->
                val pr = PDPageLabelRange()
                r.style.pdf?.let { pr.style = it }
                if (r.prefix.isNotEmpty()) pr.prefix = r.prefix
                pr.start = r.start.coerceAtLeast(1)
                pl.setLabelItem(r.startPage, pr)
            }
            doc.documentCatalog.pageLabels = pl
        }
    }
}
