package com.smartdocs.editor.core.convert

import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingDeviceRGBColor
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Extracts positioned glyphs (with colour and font family), ruling-line segments, filled boxes and placed images of a
 * page with PdfBox.
 *
 * Everything goes through one affine mapping ([PageFrame]): PDF user space → displayed page (crop box, /Rotate) →
 * turned so the dominant text direction reads left → right. This covers /Rotate 90/180/270, landscape content
 * printed sideways on a portrait page and crop boxes with a non-zero origin. Text outside the crop box is dropped;
 * text running in a minority direction (rotated labels) is kept as one horizontal run at its place.
 *
 * This file is shared verbatim with the desktop test harness: only the `com.tom_roush.pdfbox` → `org.apache.pdfbox`
 * package prefix differs. Platform types (P2 point, Winding fill rule, image encoding) come from PdfPlatform.kt.
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
        val cb = page.cropBox
        val collector = RawCollector()
        val raw0 = collector.collect(doc, index)
        // the visible words an older OCR build re-typed over a page picture count as recognised text too
        val rebuilt = OcrText.ourVisibleLayer(collector.marks)
        val raw = if (!rebuilt) raw0 else raw0.mapIndexed { i, g -> if (collector.marks[i].ourFont) g.copy(ocr = true) else g }
        // PDFTextStripper reports glyphs relative to the crop-box origin already
        val frame = PageFrame.of(cb.width.takeIf { it > 1f } ?: 595f, cb.height.takeIf { it > 1f } ?: 842f,
            page.rotation, raw)
        // invisible OCR words lying on real text are duplicates of it (an OCR layer over a digital page)
        val glyphs = OcrText.dropVertical(OcrText.dropDuplicates(frame.glyphs(raw)))
        val gfx = runCatching { GraphicsCollector(page, frame, withImages, glyphs).also { it.processPage(page) } }.getOrNull()
        return PageContent(index, frame.width, frame.height, glyphs, gfx?.segments.orEmpty(), gfx?.images.orEmpty(), gfx?.fills.orEmpty(), rebuilt)
    }

    private class FontInfo(val bold: Boolean, val italic: Boolean, val family: String)

    /** Glyphs in PDF user space (crop-box origin, before rotation). */
    private class RawCollector : PDFTextStripper() {
        val out = ArrayList<RawGlyph>()
        /** Per glyph of [out]: set in the app's OCR fonts, horizontal scaling ([OcrText.ourVisibleLayer]). */
        val marks = ArrayList<OcrText.Mark>()
        private val fonts = HashMap<PDFont, FontInfo>()

        init {
            addOperator(SetStrokingColorSpace()); addOperator(SetNonStrokingColorSpace())
            addOperator(SetStrokingColor()); addOperator(SetNonStrokingColor())
            addOperator(SetStrokingColorN()); addOperator(SetNonStrokingColorN())
            addOperator(SetStrokingDeviceGrayColor()); addOperator(SetNonStrokingDeviceGrayColor())
            addOperator(SetStrokingDeviceRGBColor()); addOperator(SetNonStrokingDeviceRGBColor())
            addOperator(SetStrokingDeviceCMYKColor()); addOperator(SetNonStrokingDeviceCMYKColor())
        }

        fun collect(doc: PDDocument, index: Int): List<RawGlyph> {
            setSortByPosition(false)
            setStartPage(index + 1)
            setEndPage(index + 1)
            getText(doc)
            return out
        }

        private fun info(f: PDFont?): FontInfo {
            if (f == null) return FontInfo(false, false, FontMap.ARIAL)
            return fonts.getOrPut(f) {
                val raw = runCatching { f.name }.getOrNull() ?: ""
                val name = raw.lowercase()
                val d = runCatching { f.fontDescriptor }.getOrNull()
                val bold = listOf("bold", "black", "heavy", "semibold", "demi", ",b").any { it in name } ||
                    (d != null && (d.isForceBold || d.fontWeight >= 600f))
                val italic = "italic" in name || "oblique" in name || (d != null && d.isItalic)
                val family = FontMap.family(raw, serif = d?.isSerif == true, monospace = d?.isFixedPitch == true)
                FontInfo(bold, italic, family)
            }
        }

        private fun rgb(c: PDColor?): Int = if (c == null) 0 else runCatching { c.toRGB() and 0xFFFFFF }.getOrDefault(0)

        override fun processTextPosition(text: TextPosition) {
            val u0 = text.unicode ?: return
            val u = FontMap.symbolText(runCatching { text.font?.name }.getOrNull(), u0) ?: u0
            if (u.isEmpty()) return
            val m = text.textMatrix
            var fs = text.fontSizeInPt
            val hh = text.heightDir.takeIf { it.isFinite() && it > 0f } ?: (fs * 0.7f)
            if (!fs.isFinite() || fs <= 0.5f) fs = hh / 0.7f
            if (!m.translateX.isFinite() || !m.translateY.isFinite() || fs > 1000f) return
            val gs = graphicsState
            val mode = runCatching { gs.textState.renderingMode }.getOrNull()
            val invisible = mode == RenderingMode.NEITHER || mode == RenderingMode.NEITHER_CLIP
            val color = if (mode != null && !mode.isFill() && mode.isStroke()) rgb(gs.strokingColor) else if (invisible) 0 else rgb(gs.nonStrokingColor)
            val fi = info(text.font)
            // invisible text is an OCR layer (ours or another app's): never typeset text
            out += RawGlyph(m.translateX, m.translateY, m.scaleX, m.shearY, text.widthDirAdj.coerceAtLeast(0f), hh, u, fs, fi.bold, fi.italic, color, fi.family, ocr = invisible)
            val tz = runCatching { gs.textState.horizontalScaling }.getOrDefault(100f)
            marks += OcrText.Mark(OcrText.isOcrFont(runCatching { text.font?.name }.getOrNull(), runCatching { text.font?.isEmbedded }.getOrNull() ?: true), tz, invisible)
        }
    }

    /** Collects stroked lines / thin filled rectangles (table rules), filled boxes and image placements. */
    private class GraphicsCollector(page: PDPage, private val frame: PageFrame, private val withImages: Boolean, glyphs: List<Glyph>) : PDFGraphicsStreamEngine(page) {
        private val text = OcrText.Coverage(glyphs)
        val segments = ArrayList<Segment>()
        val images = ArrayList<PageImage>()
        val fills = ArrayList<Fill>()
        private val llx = page.cropBox.lowerLeftX
        private val lly = page.cropBox.lowerLeftY
        private val pending = ArrayList<FloatArray>()        // straight path pieces x1,y1,x2,y2 (PDF space)
        private val rects = ArrayList<FloatArray>()          // x0,y0,x1,y1 (PDF space)
        private var cx = 0f; private var cy = 0f
        private var sx = 0f; private var sy = 0f
        private var imageBytes = 0L

        /** PDF user space → final page frame. */
        private fun map(x: Float, y: Float): FloatArray = frame.map(x - llx, y - lly)

        override fun appendRectangle(p0: P2, p1: P2, p2: P2, p3: P2) {
            val xs = floatArrayOf(p0.x.toFloat(), p1.x.toFloat(), p2.x.toFloat(), p3.x.toFloat())
            val ys = floatArrayOf(p0.y.toFloat(), p1.y.toFloat(), p2.y.toFloat(), p3.y.toFloat())
            rects += floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            cx = p0.x.toFloat(); cy = p0.y.toFloat(); sx = cx; sy = cy
        }

        override fun drawImage(pdImage: PDImage) {
            if (pdImage.isStencil) return
            val m = graphicsState.currentTransformationMatrix
            fun pt(u: Float, v: Float) = map(m.scaleX * u + m.shearX * v + m.translateX, m.shearY * u + m.scaleY * v + m.translateY)
            val o = pt(0f, 0f); val ex = pt(1f, 0f); val ey = pt(0f, 1f); val exy = pt(1f, 1f)
            val x0 = minOf(o[0], ex[0], ey[0], exy[0]); val x1 = maxOf(o[0], ex[0], ey[0], exy[0])
            val y0 = minOf(o[1], ex[1], ey[1], exy[1]); val y1 = maxOf(o[1], ex[1], ey[1], exy[1])
            if (x1 < 0f || y1 < 0f || x0 > frame.width || y0 > frame.height) return
            // a tiny image (Word draws 2x2 tiles) is either a highlight / shading tile (a fill) or, through a soft mask
            // holding glyphs, a picture of a word ("text with effects")
            val tiny = pdImage.width <= 4 && pdImage.height <= 4
            val masked = tiny && (pdImage as? com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject)?.cosObject?.containsKey(COSName.SMASK) == true
            if (tiny && !masked) {
                if (fills.size < 3000 && x1 - x0 > 3f && y1 - y0 > 3f) solidColor(pdImage)?.let { fills += Fill(Rect(x0, y0, x1, y1), it) }
                return
            }
            if (!withImages || images.size >= 60 || imageBytes > 40L * 1024 * 1024) return
            if (x1 - x0 < 8f || y1 - y0 < 8f) return
            // a picture of text that the page's (OCR) text already covers is not exported a second time
            if (masked && text.covered(Rect(x0, y0, x1, y1)) >= 0.3f) return
            // image rows run from the top (unit-square y = 1) down: its "up" vector decides the turn
            val ux = ey[0] - o[0]; val uy = ey[1] - o[1]
            val vx = ex[0] - o[0]; val vy = ex[1] - o[1]
            val orient = PageFrame.imageOrientation(vx, vy, ux, uy)
            val enc = runCatching { encodePdfImage(pdImage, orient.first, orient.second) }.getOrNull() ?: return
            imageBytes += enc.first.size
            images += PageImage(x0, y0, x1 - x0, y1 - y0, enc.first, enc.second, maskText = masked)
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
            // thin filled rectangles are how many generators draw table rules; bigger ones are shading
            val boxes = ArrayList(rects)
            if (pending.isNotEmpty() && pending.size <= 5) {
                val xs = pending.flatMap { listOf(it[0], it[2]) }; val ys = pending.flatMap { listOf(it[1], it[3]) }
                boxes += floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            }
            for (r in boxes) {
                val bw = r[2] - r[0]; val bh = r[3] - r[1]
                if (bh <= 3f && bw > 3f) { val y = (r[1] + r[3]) / 2f; add(r[0], y, r[2], y) }
                else if (bw <= 3f && bh > 3f) { val x = (r[0] + r[2]) / 2f; add(x, r[1], x, r[3]) }
                else if (bw > 3f && bh > 3f && fills.size < 3000) {
                    val color = runCatching { graphicsState.nonStrokingColor.toRGB() and 0xFFFFFF }.getOrNull() ?: continue
                    val a = map(r[0], r[1]); val b = map(r[2], r[3])
                    val rect = Rect(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[0], b[0]), maxOf(a[1], b[1]))
                    if (rect.w < 0.95f * frame.width || rect.h < 0.95f * frame.height) fills += Fill(rect, color)
                }
            }
            reset()
        }

        override fun fillAndStrokePath(windingRule: Winding) {
            val keep = ArrayList(pending); val keepR = ArrayList(rects)
            fillPath(windingRule)
            pending += keep; rects += keepR
            strokePath()
        }

        private fun add(x1: Float, y1: Float, x2: Float, y2: Float) {
            if (segments.size > 20000) return
            val a = map(x1, y1); val b = map(x2, y2)
            val s = Segment(a[0], a[1], b[0], b[1])
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
