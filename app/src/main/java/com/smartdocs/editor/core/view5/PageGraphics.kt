package com.smartdocs.editor.core.view5

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Vector geometry of a page read with PdfBox: stroked / filled path segments (for measure snapping) and
 * image placements (for the reflow view). Coordinates are PDF user space of the page (y up), the same
 * space annotations use. Curves are flattened to their chord (enough for snapping end points).
 */
object PageGraphics {

    class Result(val segments: List<Pair<P, P>>, val images: List<RImage>)

    private class Engine(page: PDPage, private val maxSegments: Int) : PDFGraphicsStreamEngine(page) {
        val segs = ArrayList<Pair<P, P>>()
        val images = ArrayList<RImage>()
        private val path = ArrayList<Pair<P, P>>()
        private var cur = PointF(0f, 0f)
        private var start = PointF(0f, 0f)

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            add(p0, p1); add(p1, p2); add(p2, p3); add(p3, p0)
            cur = PointF(p0.x, p0.y); start = PointF(p0.x, p0.y)
        }
        private fun add(a: PointF, b: PointF) { if (path.size < maxSegments) path += P(a.x.toDouble(), a.y.toDouble()) to P(b.x.toDouble(), b.y.toDouble()) }
        override fun drawImage(pdImage: PDImage) {
            val m = graphicsState.currentTransformationMatrix
            val pts = listOf(PointF(0f, 0f), PointF(1f, 0f), PointF(0f, 1f), PointF(1f, 1f)).map { p -> PointF(p.x, p.y).also { m.transform(it) } }
            images += RImage(pts.maxOf { it.y }, pts.minOf { it.y }, pts.minOf { it.x }, pts.maxOf { it.x })
        }
        override fun clip(windingRule: Path.FillType) {}
        override fun moveTo(x: Float, y: Float) { cur = PointF(x, y); start = PointF(x, y) }
        override fun lineTo(x: Float, y: Float) { val n = PointF(x, y); add(cur, n); cur = n }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { val n = PointF(x3, y3); add(cur, n); cur = n }
        override fun getCurrentPoint(): PointF = cur
        override fun closePath() { add(cur, start); cur = PointF(start.x, start.y) }
        override fun endPath() { path.clear() }
        override fun strokePath() { flush() }
        override fun fillPath(windingRule: Path.FillType) { flush() }
        override fun fillAndStrokePath(windingRule: Path.FillType) { flush() }
        override fun shadingFill(shadingName: COSName) {}
        private fun flush() {
            if (segs.size < maxSegments) segs.addAll(path.take(maxSegments - segs.size))
            path.clear()
        }
    }

    fun scan(doc: PDDocument, pageIndex: Int, maxSegments: Int = 20000): Result {
        val page = doc.getPage(pageIndex)
        val e = Engine(page, maxSegments)
        runCatching { e.processPage(page) }
        // drop tiny segments (glyph-like noise) and page-sized frames
        val box = page.cropBox
        val segs = e.segs.filter { (a, b) -> Geom.dist(a, b) > 1.5 }
            .filterNot { (a, b) -> Geom.dist(a, b) > maxOf(box.width, box.height) * 0.98 }
        return Result(segs, e.images.filter { (it.right - it.left) > 4f && (it.top - it.bottom) > 4f })
    }

    suspend fun scan(src: File, pw: String?, pageIndex: Int): Result = withContext(Dispatchers.IO) {
        (if (pw.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, pw)).use { scan(it, pageIndex) }
    }

    /** Images of several pages at once (one document load). */
    suspend fun images(src: File, pw: String?, pages: IntRange): Map<Int, List<RImage>> = withContext(Dispatchers.IO) {
        // tab-view-core V-13: big streams spill to temp files instead of the heap
        PDDocument.load(src, pw ?: "", com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(32L shl 20)).use { d ->
            pages.filter { it in 0 until d.numberOfPages }.associateWith { p -> scan(d, p, maxSegments = 0).images }
        }
    }
}
