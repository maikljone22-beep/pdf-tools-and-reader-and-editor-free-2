package com.smartdocs.editor.core.view5

import android.graphics.Bitmap
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Foxit "Print What": the page with its comments, the page only, the page with stamps, form fields only, comments only. */
enum class PrintContent { DOC_AND_MARKUPS, DOCUMENT, DOC_AND_STAMPS, FORM_FIELDS, MARKUPS }

/** Print options chosen in the print sheet. */
data class PrintOptions(
    val range: String = "",
    val subset: PrintLayout.Subset = PrintLayout.Subset.ALL,
    val reverse: Boolean = false,
    val markedOnly: Boolean = false,      // NVE-file: only pages with markups
    val perSheet: Int = 1,               // 1, 2, 4, 6, 9, 16
    val booklet: Boolean = false,
    val rightBinding: Boolean = true,    // Arabic books bind on the right
    val paper: PDRectangle = PDRectangle.A4,
    val landscape: Boolean = false,
    val borders: Boolean = false,
    val annotations: Boolean = true,
    val rtlOrder: Boolean = true,
    // nav-batch (Foxit print dialog)
    val scale: PrintLayout.Scale = PrintLayout.Scale.AUTO,
    val scalePct: Int = 100,
    val autoRotate: Boolean = true,
    val margin: Float = 18f,
    val cols: Int = 0, val rows: Int = 0,      // custom N×M (both > 0) instead of [perSheet]
    val vertical: Boolean = false,             // N-up order: down the columns
    val sides: PrintLayout.Sides = PrintLayout.Sides.BOTH,
    val sheets: String = "",                   // booklet sheet range
    val content: PrintContent = PrintContent.DOC_AND_MARKUPS,
    val poster: Boolean = false, val posterZoom: Int = 200, val overlap: Float = 18f, val posterLabels: Boolean = true,
    val cutMarks: Boolean = false,
    // fc-settings: printer marks for normal printing (registration targets, bleed marks at [bleed] points) and the hairline fix
    val regMarks: Boolean = false,
    val bleedMarks: Boolean = false,
    val bleed: Float = 9f,
    /** Minimum stroke width in points (0 = off): thinner lines (and 0-width hairlines) are widened before printing. */
    val hairline: Float = 0f,
    val notes: Boolean = false,                // sticky-note texts drawn beside their icons
    val appendix: File? = null,                // pages added at the end (the comment summary)
) {
    val what: PrintContent get() = if (!annotations) PrintContent.DOCUMENT else content
    val nUp: Boolean get() = perSheet > 1 || cols > 0 && rows > 0
    /** Registration or bleed marks are wanted (one page per sheet, placed with room for the marks). */
    val printerMarks: Boolean get() = regMarks || bleedMarks
}

/**
 * Builds the PDF that is sent to the system print dialog: page range / odd / even / reverse, then N pages
 * per sheet (or N×M, row or column order), a folded booklet (2 pages per side, imposed for duplex printing; fronts
 * or backs only), poster tiles, or one page per sheet at a chosen scale. Pages are imported as Form XObjects
 * (vector, nothing rasterised). [PrintOptions.what] decides which annotations are printed.
 */
object PrintPrep {

    /** True when the options need no re-imposition (the original file can be printed as-is). */
    fun isPlain(o: PrintOptions, pageCount: Int) = o.range.isBlank() && o.subset == PrintLayout.Subset.ALL && !o.reverse && !o.markedOnly && o.perSheet == 1 && !o.booklet &&
        o.what == PrintContent.DOC_AND_MARKUPS && pageCount > 0 && o.scale == PrintLayout.Scale.AUTO && !o.nUp && !o.poster && !o.notes && o.appendix == null && !o.cutMarks &&
        !o.printerMarks && o.hairline <= 0f

    /** [note] renders a sticky note's text as an image (width in points → bitmap), for [PrintOptions.notes]. */
    suspend fun build(src: File, pw: String?, o: PrintOptions, out: File, note: ((String, Float) -> Bitmap?)? = null): Int = withContext(Dispatchers.IO) {
        com.smartdocs.editor.core.pdf.PdfOps.load(src, pw).use { doc ->
            if (o.notes && note != null) drawNotes(doc, note)
            // NVE-file: pages with markups, taken before the content filter removes annotations
            val nveMarked = if (o.markedOnly) com.smartdocs.editor.core.nve.PrintExtras.markupPages(doc) else null
            when (o.what) {
                PrintContent.DOCUMENT -> keepAnnots(doc) { it == "Widget" || it == "Link" }
                PrintContent.DOC_AND_STAMPS -> { keepAnnots(doc) { it == "Widget" || it == "Link" || it == "Stamp" }; flattenAppearances(doc) }
                PrintContent.FORM_FIELDS -> { keepAnnots(doc) { it == "Widget" }; clearContent(doc); flattenAppearances(doc) }
                PrintContent.MARKUPS -> { keepAnnots(doc) { it != "Widget" && it != "Link" }; clearContent(doc); flattenAppearances(doc) }
                PrintContent.DOC_AND_MARKUPS -> flattenAppearances(doc)
            }
            if (o.hairline > 0f) fixHairlines(doc, o.hairline)   // fc-settings: G8 hairline fix
            var pages = PrintLayout.subset(PrintLayout.parseRange(o.range, doc.numberOfPages), o.subset)
            if (nveMarked != null) pages = pages.filter { it in nveMarked }
            if (o.reverse) pages = pages.reversed()
            if (pages.isEmpty()) throw IllegalArgumentException("empty range")
            val extra = o.appendix?.takeIf { it.exists() }?.let { runCatching { PDDocument.load(it) }.getOrNull() }
            try {
                PDDocument().use { dst ->
                    val lu = LayerUtility(dst)
                    val forms = HashMap<Int, PDFormXObject>()
                    fun form(i: Int) = forms.getOrPut(i) { lu.importPageAsForm(doc, i) }
                    fun size(i: Int): Pair<Float, Float> {
                        val b = doc.getPage(i).cropBox
                        val rot = doc.getPage(i).rotation % 180 != 0
                        return if (rot) b.height to b.width else b.width to b.height
                    }
                    val base = o.paper
                    val portraitW = minOf(base.width, base.height); val portraitH = maxOf(base.width, base.height)
                    if (o.booklet) {
                        // each side: two pages on a landscape sheet
                        val sw = portraitH; val sh = portraitW
                        PrintLayout.bookletSides(PrintLayout.booklet(pages, o.rightBinding), o.sides, o.sheets).forEach { (l, r) ->
                            val sheet = PDPage(PDRectangle(sw, sh)); dst.addPage(sheet)
                            PDPageContentStream(dst, sheet).use { cs ->
                                listOf(l, r).forEachIndexed { k, p ->
                                    if (p < 0) return@forEachIndexed
                                    val (pw0, ph0) = size(p)
                                    val cw = sw / 2; val s = minOf(cw / pw0, sh / ph0)
                                    val x = k * cw + (cw - pw0 * s) / 2; val y = (sh - ph0 * s) / 2
                                    place(cs, form(p), x, y, s, o.borders, pw0 * s, ph0 * s)
                                }
                            }
                        }
                    } else if (o.poster) {
                        val sw = if (o.landscape) portraitH else portraitW; val sh = if (o.landscape) portraitW else portraitH
                        val m = o.margin.coerceIn(0f, minOf(sw, sh) / 4f)
                        val z = o.posterZoom.coerceIn(10, 2000) / 100f
                        pages.forEach { p ->
                            val (pw0, ph0) = size(p)
                            val tiles = PrintLayout.tiles(pw0, ph0, sw - 2 * m, sh - 2 * m, o.posterZoom, o.overlap)
                            tiles.forEach { t ->
                                val sheet = PDPage(PDRectangle(sw, sh)); dst.addPage(sheet)
                                PDPageContentStream(dst, sheet).use { cs ->
                                    cs.saveGraphicsState()
                                    cs.addRect(m, m, sw - 2 * m, sh - 2 * m); cs.clip()
                                    cs.transform(Matrix.concatenate(Matrix.getTranslateInstance(m - t.x * z, m - t.y * z), Matrix.getScaleInstance(z, z)))
                                    cs.drawForm(form(p))
                                    cs.restoreGraphicsState()
                                    if (o.cutMarks) cutMarks(cs, m, m, sw - 2 * m, sh - 2 * m, m)
                                    if (o.posterLabels && m >= 8f) label(cs, "${p + 1}  (${t.row + 1}, ${t.col + 1})  ${tiles.size}", m, m / 2f - 3f)
                                }
                            }
                        }
                    } else if (o.nUp) {
                        val land = o.landscape || o.cols <= 0 && (o.perSheet == 2 || o.perSheet == 6)
                        val sw = if (land) portraitH else portraitW
                        val sh = if (land) portraitW else portraitH
                        val per = if (o.cols > 0 && o.rows > 0) o.cols * o.rows else o.perSheet
                        pages.chunked(per).forEach { chunk ->
                            val sheet = PDPage(PDRectangle(sw, sh)); dst.addPage(sheet)
                            PDPageContentStream(dst, sheet).use { cs ->
                                PrintLayout.place(chunk, ::size, o.perSheet, sw, sh, margin = o.margin, rtl = o.rtlOrder, cols0 = o.cols, rows0 = o.rows, vertical = o.vertical).forEach { c ->
                                    place(cs, form(c.page), c.x, c.y, c.scale, o.borders, c.w, c.h)
                                }
                            }
                        }
                    } else if (o.scale == PrintLayout.Scale.AUTO && !o.cutMarks && !o.printerMarks) {
                        pages.forEach { p -> dst.importPage(doc.getPage(p)) }
                    } else {
                        // one page per sheet: fit / shrink / 100 % / custom %, auto-rotated and centred on the paper
                        val sw = if (o.landscape) portraitH else portraitW; val sh = if (o.landscape) portraitW else portraitH
                        pages.forEach { p ->
                            val (pw0, ph0) = size(p)
                            val room = PrinterMarks.room(o.bleed, o.regMarks, o.bleedMarks)
                            val (s, rot) = PrintLayout.fitScale(pw0, ph0, sw, sh, o.scale, o.scalePct, maxOf(o.margin, room), o.autoRotate)
                            val dw = (if (rot) ph0 else pw0) * s; val dh = (if (rot) pw0 else ph0) * s
                            val x0 = (sw - dw) / 2; val y0 = (sh - dh) / 2
                            val sheet = PDPage(PDRectangle(sw, sh)); dst.addPage(sheet)
                            PDPageContentStream(dst, sheet).use { cs ->
                                cs.saveGraphicsState()
                                val m = if (rot) Matrix.concatenate(Matrix.getTranslateInstance(x0 + dw, y0), Matrix.concatenate(Matrix.getRotateInstance(Math.PI / 2, 0f, 0f), Matrix.getScaleInstance(s, s)))
                                else Matrix.concatenate(Matrix.getTranslateInstance(x0, y0), Matrix.getScaleInstance(s, s))
                                cs.transform(m)
                                cs.drawForm(form(p))
                                cs.restoreGraphicsState()
                                if (o.borders) { cs.setLineWidth(0.5f); cs.setStrokingColor(0.6f, 0.6f, 0.6f); cs.addRect(x0, y0, dw, dh); cs.stroke() }
                                if (o.cutMarks) cutMarks(cs, x0, y0, dw, dh, minOf(x0, y0))
                                if (o.printerMarks) printerMarks(cs, PrinterMarks.layout(x0, y0, dw, dh, o.bleed, o.regMarks, o.bleedMarks))
                            }
                        }
                    }
                    extra?.pages?.forEach { dst.importPage(it) }
                    dst.save(out)
                    dst.numberOfPages
                }
            } finally { extra?.close() }
        }
    }

    /**
     * Draw an imported page at (x, y) scaled by [s]. LayerUtility.importPageAsForm already applies the page's
     * /Rotate and moves the crop box to the origin (form matrix), so only translate + scale are needed.
     */
    private fun place(cs: PDPageContentStream, f: PDFormXObject, x: Float, y: Float, s: Float, border: Boolean, w: Float, h: Float) {
        cs.saveGraphicsState()
        cs.transform(Matrix.concatenate(Matrix.getTranslateInstance(x, y), Matrix.getScaleInstance(s, s)))
        cs.drawForm(f)
        cs.restoreGraphicsState()
        if (border) { cs.setLineWidth(0.5f); cs.setStrokingColor(0.6f, 0.6f, 0.6f); cs.addRect(x, y, w, h); cs.stroke() }
    }

    /** Crop (cut) marks: short hairlines just outside the corners of the box, at most [room] points long. */
    private fun cutMarks(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float, room: Float) {
        val len = minOf(14f, room - 2f); if (len < 3f) return
        val gap = 2f
        cs.setLineWidth(0.4f); cs.setStrokingColor(0f, 0f, 0f)
        for ((cx, cy, dx, dy) in listOf(floatArrayOf(x, y, -1f, -1f), floatArrayOf(x + w, y, 1f, -1f), floatArrayOf(x, y + h, -1f, 1f), floatArrayOf(x + w, y + h, 1f, 1f)).map { listOf(it[0], it[1], it[2], it[3]) }) {
            cs.moveTo(cx + dx * gap, cy); cs.lineTo(cx + dx * (gap + len), cy)
            cs.moveTo(cx, cy + dy * gap); cs.lineTo(cx, cy + dy * (gap + len))
        }
        cs.stroke()
    }

    /** fc-settings: bleed marks and registration targets (0.3 pt, registration black). */
    private fun printerMarks(cs: PDPageContentStream, l: PrinterMarks.Layout) {
        cs.setLineWidth(0.3f); cs.setStrokingColor(0f, 0f, 0f)
        l.bleed.forEach { g -> cs.moveTo(g.x1, g.y1); cs.lineTo(g.x2, g.y2) }
        l.reg.forEach { t ->
            // circle from four Bézier arcs, then the cross
            val k = 0.5523f * t.r
            cs.moveTo(t.cx + t.r, t.cy)
            cs.curveTo(t.cx + t.r, t.cy + k, t.cx + k, t.cy + t.r, t.cx, t.cy + t.r)
            cs.curveTo(t.cx - k, t.cy + t.r, t.cx - t.r, t.cy + k, t.cx - t.r, t.cy)
            cs.curveTo(t.cx - t.r, t.cy - k, t.cx - k, t.cy - t.r, t.cx, t.cy - t.r)
            cs.curveTo(t.cx + k, t.cy - t.r, t.cx + t.r, t.cy - k, t.cx + t.r, t.cy)
            cs.moveTo(t.cx - t.r - 2f, t.cy); cs.lineTo(t.cx + t.r + 2f, t.cy)
            cs.moveTo(t.cx, t.cy - t.r - 2f); cs.lineTo(t.cx, t.cy + t.r + 2f)
        }
        cs.stroke()
    }

    /**
     * fc-settings (G8 hairline fix): every `w` in the page content and in the page's own form XObjects (this includes
     * the flattened comment appearances) is raised to at least [min] points. Widths set through ExtGState /LW or in
     * nested forms are left as they are.
     */
    private fun fixHairlines(doc: PDDocument, min: Float) {
        doc.pages.forEach { page ->
            runCatching {
                val p = com.tom_roush.pdfbox.pdfparser.PDFStreamParser(page); p.parse()
                val tokens: MutableList<Any?> = ArrayList(p.tokens)
                if (PrinterMarks.fixLineWidths(tokens, min) > 0) {
                    val st = PDStream(doc)
                    st.createOutputStream(com.tom_roush.pdfbox.cos.COSName.FLATE_DECODE).use { com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter(it).writeTokens(tokens) }
                    page.setContents(st)
                }
            }
            val res = page.resources ?: return@forEach
            runCatching {
                res.xObjectNames.forEach { n ->
                    val f = runCatching { res.getXObject(n) }.getOrNull() as? PDFormXObject ?: return@forEach
                    runCatching {
                        val p = com.tom_roush.pdfbox.pdfparser.PDFStreamParser(f); p.parse()
                        val tokens: MutableList<Any?> = ArrayList(p.tokens)
                        if (PrinterMarks.fixLineWidths(tokens, min) > 0)
                            f.cosObject.createOutputStream(com.tom_roush.pdfbox.cos.COSName.FLATE_DECODE).use { com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter(it).writeTokens(tokens) }
                    }
                }
            }
        }
    }

    /** Small ASCII label (tile position) in the bottom margin. */
    private fun label(cs: PDPageContentStream, text: String, x: Float, y: Float) {
        runCatching {
            cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 7f); cs.setNonStrokingColor(0.35f, 0.35f, 0.35f)
            cs.newLineAtOffset(x, y.coerceAtLeast(1f)); cs.showText(text); cs.endText()
        }
    }

    private fun keepAnnots(doc: PDDocument, keep: (String) -> Boolean) {
        doc.pages.forEach { p -> runCatching { p.annotations = p.annotations.filter { keep(it.subtype ?: "") } } }
    }

    /** "Form fields only" / "comments only": the page's own content is left out (resources stay for the appearances). */
    private fun clearContent(doc: PDDocument) {
        doc.pages.forEach { p -> runCatching { p.setContents(PDStream(doc)) } }
    }

    /** Sticky-note texts printed as they would pop up: a note image beside each Text annotation that has contents. */
    private fun drawNotes(doc: PDDocument, note: (String, Float) -> Bitmap?) {
        doc.pages.forEach { page ->
            val notes = runCatching { page.annotations }.getOrNull()?.filter { it.subtype == "Text" && !it.contents.isNullOrBlank() && it.rectangle != null }.orEmpty()
            if (notes.isEmpty()) return@forEach
            val box = page.cropBox
            runCatching {
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    notes.forEach { an ->
                        val w = minOf(180f, box.width * 0.45f)
                        val bmp = note(an.contents, w) ?: return@forEach
                        val h = w * bmp.height / bmp.width.coerceAtLeast(1)
                        val r = an.rectangle
                        val x = (r.upperRightX + 4f).let { if (it + w > box.upperRightX) r.lowerLeftX - 4f - w else it }.coerceIn(box.lowerLeftX, box.upperRightX - w)
                        val y = (r.upperRightY - h).coerceIn(box.lowerLeftY, (box.upperRightY - h).coerceAtLeast(box.lowerLeftY))
                        val img = LosslessFactory.createFromImage(doc, bmp)
                        cs.drawImage(img, x, y, w, h)
                        bmp.recycle()
                    }
                }
            }
        }
    }

    /**
     * importPageAsForm keeps only the page content; annotations would be lost in N-up / booklet. Their normal
     * appearances are drawn into the page content first (printable, not hidden).
     */
    private fun flattenAppearances(doc: PDDocument) {
        doc.pages.forEach { page ->
            val list = runCatching { page.annotations }.getOrNull() ?: return@forEach
            val draw = list.filter { an -> !an.isHidden && an.isPrinted && an.subtype != "Popup" && an.normalAppearanceStream != null }
            if (draw.isEmpty()) return@forEach
            runCatching {
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    draw.forEach { an ->
                        val ap = an.normalAppearanceStream ?: return@forEach
                        val r = an.rectangle ?: return@forEach
                        val bb = ap.bBox ?: return@forEach
                        if (bb.width <= 0f || bb.height <= 0f) return@forEach
                        cs.saveGraphicsState()
                        // map the appearance bbox (after its own matrix) onto the annotation rect
                        val apm = ap.matrix
                        val pts = floatArrayOf(bb.lowerLeftX, bb.lowerLeftY, bb.upperRightX, bb.upperRightY)
                        val p0 = apm.transformPoint(pts[0], pts[1]); val p1 = apm.transformPoint(pts[2], pts[3])
                        val tw = (maxOf(p0.x, p1.x) - minOf(p0.x, p1.x)).coerceAtLeast(0.01f); val th = (maxOf(p0.y, p1.y) - minOf(p0.y, p1.y)).coerceAtLeast(0.01f)
                        cs.transform(Matrix(r.width / tw, 0f, 0f, r.height / th, r.lowerLeftX - minOf(p0.x, p1.x) * r.width / tw, r.lowerLeftY - minOf(p0.y, p1.y) * r.height / th))
                        cs.drawForm(PDFormXObject(ap.cosObject))
                        cs.restoreGraphicsState()
                    }
                }
                page.annotations = list.filter { it.subtype == "Link" || it.subtype == "Widget" && it.normalAppearanceStream == null || it.isHidden || !it.isPrinted }
            }
        }
    }
}
