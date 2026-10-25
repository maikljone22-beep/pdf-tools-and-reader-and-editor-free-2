package com.smartdocs.editor.core.convert

import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Extracts positioned glyphs, ruling-line segments and placed images of a page with PdfBox.
 *
 * This file is shared verbatim with the desktop test harness: only the `com.tom_roush.pdfbox` →
 * `org.apache.pdfbox` package prefix differs. Platform types (P2 point, Winding fill rule, image
 * encoding) come from PdfPlatform.kt.
 */
class PdfContentExtractor(private val doc: PDDocument) {

    val pageCount: Int get() = doc.numberOfPages

    /** Crop-box size in points with the page rotation applied. */
    fun pageSize(index: Int): Pair<Float, Float> {
        val p = doc.getPage(index)
        val cb = p.cropBox
        val w = cb.width.takeIf { it > 1f } ?: 595f
        val h = cb.height.takeIf { it > 1f } ?: 842f
        return if ((p.rotation % 180 + 180) % 180 == 90) h to w else w to h
    }

    fun extract(index: Int, withImages: Boolean): PageContent {
        val page = doc.getPage(index)
        val (w, h) = pageSize(index)
        val glyphs = GlyphCollector().collect(doc, index)
        // segment / image coordinates are in unrotated user space; only usable when the page is upright
        val upright = page.rotation % 360 == 0
        val gfx = if (upright) runCatching { GraphicsCollector(page, withImages).also { it.processPage(page) } }.getOrNull() else null
        return PageContent(index, w, h, glyphs, gfx?.segments.orEmpty(), gfx?.images.orEmpty())
    }

    private class FontInfo(val bold: Boolean, val italic: Boolean)

    private class GlyphCollector : PDFTextStripper() {
        val out = ArrayList<Glyph>()
        private val fonts = HashMap<PDFont, FontInfo>()

        fun collect(doc: PDDocument, index: Int): List<Glyph> {
            setSortByPosition(false)
            setStartPage(index + 1)
            setEndPage(index + 1)
            getText(doc)
            return out
        }

        private fun info(f: PDFont?): FontInfo {
            if (f == null) return FontInfo(false, false)
            return fonts.getOrPut(f) {
                val name = (runCatching { f.name }.getOrNull() ?: "").lowercase()
                val d = runCatching { f.fontDescriptor }.getOrNull()
                val bold = listOf("bold", "black", "heavy", "semibold", "demi", ",b").any { it in name } ||
                    (d != null && (d.isForceBold || d.fontWeight >= 600f))
                val italic = "italic" in name || "oblique" in name || (d != null && d.isItalic)
                FontInfo(bold, italic)
            }
        }

        override fun processTextPosition(text: TextPosition) {
            val u = text.unicode ?: return
            if (u.isEmpty()) return
            val x = text.xDirAdj
            val base = text.yDirAdj
            val w = text.widthDirAdj
            var fs = text.fontSizeInPt
            val hh = text.heightDir.takeIf { it.isFinite() && it > 0f } ?: (fs * 0.7f)
            if (!fs.isFinite() || fs <= 0.5f) fs = hh / 0.7f
            if (!x.isFinite() || !base.isFinite() || fs > 1000f) return
            val fi = info(text.font)
            out += Glyph(x, base - hh, w.coerceAtLeast(0f), hh, base, u, fs, fi.bold, fi.italic)
        }
    }

    /** Collects stroked lines / thin filled rectangles (table rules) and image placements. */
    private class GraphicsCollector(page: PDPage, private val withImages: Boolean) : PDFGraphicsStreamEngine(page) {
        val segments = ArrayList<Segment>()
        val images = ArrayList<PageImage>()
        private val llx = page.cropBox.lowerLeftX
        private val ury = page.cropBox.upperRightY
        private val pending = ArrayList<FloatArray>()        // straight path pieces x1,y1,x2,y2 (PDF space)
        private val rects = ArrayList<FloatArray>()          // x0,y0,x1,y1 (PDF space)
        private var cx = 0f; private var cy = 0f
        private var sx = 0f; private var sy = 0f
        private var imageBytes = 0L

        private fun tx(x: Float) = x - llx
        private fun ty(y: Float) = ury - y

        override fun appendRectangle(p0: P2, p1: P2, p2: P2, p3: P2) {
            val xs = floatArrayOf(p0.x.toFloat(), p1.x.toFloat(), p2.x.toFloat(), p3.x.toFloat())
            val ys = floatArrayOf(p0.y.toFloat(), p1.y.toFloat(), p2.y.toFloat(), p3.y.toFloat())
            rects += floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            cx = p0.x.toFloat(); cy = p0.y.toFloat(); sx = cx; sy = cy
        }

        override fun drawImage(pdImage: PDImage) {
            if (!withImages || images.size >= 60 || imageBytes > 40L * 1024 * 1024) return
            if (pdImage.isStencil) return
            val m = graphicsState.currentTransformationMatrix
            val w = m.scalingFactorX; val h = m.scalingFactorY
            if (w < 8f || h < 8f) return
            val x0 = m.translateX; val y0 = m.translateY
            val enc = runCatching { encodePdfImage(pdImage) }.getOrNull() ?: return
            imageBytes += enc.first.size
            images += PageImage(tx(x0), ty(y0 + h), w, h, enc.first, enc.second)
        }

        override fun clip(windingRule: Winding) = Unit
        override fun moveTo(x: Float, y: Float) { cx = x; cy = y; sx = x; sy = y }
        override fun lineTo(x: Float, y: Float) { pending += floatArrayOf(cx, cy, x, y); cx = x; cy = y }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { cx = x3; cy = y3 }
        override fun getCurrentPoint(): P2 = newPoint(cx, cy)
        override fun closePath() { if (cx != sx || cy != sy) pending += floatArrayOf(cx, cy, sx, sy); cx = sx; cy = sy }
        override fun endPath() = reset()
        override fun shadingFill(shadingName: COSName) = Unit

        override fun strokePath() {
            for (p in pending) add(p[0], p[1], p[2], p[3])
            for (r in rects) { add(r[0], r[1], r[2], r[1]); add(r[2], r[1], r[2], r[3]); add(r[2], r[3], r[0], r[3]); add(r[0], r[3], r[0], r[1]) }
            reset()
        }

        override fun fillPath(windingRule: Winding) {
            // thin filled rectangles are how many generators draw table rules
            val boxes = ArrayList(rects)
            if (pending.isNotEmpty() && pending.size <= 5) {
                val xs = pending.flatMap { listOf(it[0], it[2]) }; val ys = pending.flatMap { listOf(it[1], it[3]) }
                boxes += floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            }
            for (r in boxes) {
                val bw = r[2] - r[0]; val bh = r[3] - r[1]
                if (bh <= 3f && bw > 3f) { val y = (r[1] + r[3]) / 2f; add(r[0], y, r[2], y) }
                else if (bw <= 3f && bh > 3f) { val x = (r[0] + r[2]) / 2f; add(x, r[1], x, r[3]) }
            }
            reset()
        }

        override fun fillAndStrokePath(windingRule: Winding) = strokePath()

        private fun add(x1: Float, y1: Float, x2: Float, y2: Float) {
            if (segments.size > 20000) return
            val s = Segment(tx(x1), ty(y1), tx(x2), ty(y2))
            if (s.horizontal || s.vertical) segments += s
        }

        private fun reset() { pending.clear(); rects.clear() }
    }

    companion object {
        /** True when the image may be copied as-is (baseline JPEG in RGB/gray without a soft mask). */
        fun isPlainJpeg(suffix: String?, colorSpace: String?, hasSMask: Boolean) =
            suffix == "jpg" && !hasSMask && (colorSpace == COSName.DEVICERGB.name || colorSpace == COSName.DEVICEGRAY.name)
    }
}
