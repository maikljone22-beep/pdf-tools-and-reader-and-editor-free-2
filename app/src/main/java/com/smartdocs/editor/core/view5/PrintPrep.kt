package com.smartdocs.editor.core.view5

import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Print options chosen in the print sheet. */
data class PrintOptions(
    val range: String = "",
    val subset: PrintLayout.Subset = PrintLayout.Subset.ALL,
    val reverse: Boolean = false,
    val perSheet: Int = 1,               // 1, 2, 4, 6, 9, 16
    val booklet: Boolean = false,
    val rightBinding: Boolean = true,    // Arabic books bind on the right
    val paper: PDRectangle = PDRectangle.A4,
    val landscape: Boolean = false,
    val borders: Boolean = false,
    val annotations: Boolean = true,
    val rtlOrder: Boolean = true,
    // NVE-file: custom grid, booklet sides, scaling, markups subset, grey, comment summary
    /** Columns x rows when [perSheet] is [PrintLayout.CUSTOM_GRID]. */
    val cols: Int = 2,
    val rows: Int = 2,
    val bookletSides: PrintLayout.BookletSides = PrintLayout.BookletSides.BOTH,
    val scaling: PrintLayout.Scaling = PrintLayout.Scaling.NONE,
    val customScale: Int = 100,
    val autoRotate: Boolean = true,
    val center: Boolean = true,
    /** Only pages that carry markup annotations (comments, highlights, drawings, stamps). */
    val markupsOnly: Boolean = false,
    /** Grey-scale output (vector, blend-mode overlay) and a monochrome default in the system dialog. */
    val grayscale: Boolean = false,
    /** Append summary pages listing the comments of the printed pages. */
    val commentSummary: Boolean = false,
    /** Copies requested by the document (/ViewerPreferences /NumCopies); 1 = not set. */
    val copies: Int = 1,
    /** Duplex wanted by the document or the user: "" / Simplex / DuplexFlipShortEdge / DuplexFlipLongEdge (system dialog default). */
    val duplex: String = "",
    /** /PickTrayByPDFSize: the paper follows the page size (system dialog default). */
    val paperFromPage: Boolean = false,
)

/**
 * Builds the PDF that is sent to the system print dialog: page range / odd / even / reverse, then N pages
 * per sheet or a folded booklet (2 pages per side, imposed for duplex printing). Pages are imported as
 * Form XObjects (vector, nothing rasterised). With [PrintOptions.annotations] off the annotations are dropped.
 */
object PrintPrep {

    /** True when the options need no re-imposition (the original file can be printed as-is). */
    fun isPlain(o: PrintOptions, pageCount: Int) = o.range.isBlank() && o.subset == PrintLayout.Subset.ALL && !o.reverse && o.perSheet == 1 && !o.booklet && o.annotations && pageCount > 0 &&
        o.scaling == PrintLayout.Scaling.NONE && !o.markupsOnly && !o.grayscale && !o.commentSummary && o.copies <= 1   // NVE-file

    /**
     * [summaryLabels] / [arabicFont]: comment summary texts and the font for Arabic text (NVE-file; the UI passes
     * PdfOps.arabicFont, tests leave it null).
     */
    suspend fun build(src: File, pw: String?, o: PrintOptions, out: File,
                      summaryLabels: com.smartdocs.editor.core.nve.PrintExtras.Labels = com.smartdocs.editor.core.nve.PrintExtras.Labels(),
                      arabicFont: ((PDDocument) -> com.tom_roush.pdfbox.pdmodel.font.PDFont)? = null): Int = withContext(Dispatchers.IO) {
        com.smartdocs.editor.core.pdf.PdfOps.load(src, pw).use { doc ->
            val markups = if (o.markupsOnly) com.smartdocs.editor.core.nve.PrintExtras.markupPages(doc) else emptySet()
            var pages = PrintLayout.subset(PrintLayout.parseRange(o.range, doc.numberOfPages), o.subset)
            if (o.markupsOnly) pages = pages.filter { it in markups }
            if (o.reverse) pages = pages.reversed()
            if (pages.isEmpty()) throw IllegalArgumentException("empty range")
            val summary = if (o.commentSummary) com.smartdocs.editor.core.nve.PrintExtras.comments(doc, pages.sorted()) else emptyList()
            if (!o.annotations) doc.pages.forEach { p -> runCatching { p.annotations = p.annotations.filter { it.subtype == "Widget" || it.subtype == "Link" } } }
            else flattenAppearances(doc)
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
                if (o.booklet) {
                    // each side: two pages on a landscape sheet
                    val sw = maxOf(base.width, base.height); val sh = minOf(base.width, base.height)
                    PrintLayout.bookletSides(PrintLayout.booklet(pages, o.rightBinding), o.bookletSides).forEach { (l, r) ->
                        val sheet = PDPage(PDRectangle(sw, sh)); dst.addPage(sheet)
                        PDPageContentStream(dst, sheet).use { cs ->
                            listOf(l, r).forEachIndexed { k, p ->
                                if (p < 0) return@forEachIndexed
                                val (pw0, ph0) = size(p)
                                val cw = sw / 2; val s = minOf(cw / pw0, sh / ph0)
                                val x = k * cw + (cw - pw0 * s) / 2; val y = (sh - ph0 * s) / 2
                                place(cs, form(p), doc.getPage(p), x, y, s, o.borders, pw0 * s, ph0 * s)
                            }
                        }
                    }
                } else if (o.perSheet == 1 && o.scaling == PrintLayout.Scaling.NONE) {
                    pages.forEach { p -> dst.importPage(doc.getPage(p)) }
                } else if (o.perSheet == 1) {
                    // NVE-file: one page per sheet on the chosen paper: fit / actual size / shrink / custom %, auto-rotate, centre
                    pages.forEach { p ->
                        val (pw0, ph0) = size(p)
                        val (sw, sh) = PrintLayout.sheetFor(pw0, ph0, base.width, base.height, o.landscape, o.autoRotate)
                        val c = PrintLayout.single(pw0, ph0, sw, sh, o.scaling, o.customScale, o.center, rtl = o.rtlOrder)
                        val sheet = PDPage(PDRectangle(sw, sh)); dst.addPage(sheet)
                        PDPageContentStream(dst, sheet).use { cs -> place(cs, form(p), doc.getPage(p), c.x, c.y, c.scale, o.borders, c.w, c.h) }
                    }
                } else {
                    val custom = o.perSheet == PrintLayout.CUSTOM_GRID
                    val land = o.landscape || (!custom && (o.perSheet == 2 || o.perSheet == 6))
                    val sw = if (land) maxOf(base.width, base.height) else minOf(base.width, base.height)
                    val sh = if (land) minOf(base.width, base.height) else maxOf(base.width, base.height)
                    val (cols, rows) = PrintLayout.gridOf(o.perSheet, o.cols, o.rows, sw > sh)
                    pages.chunked(cols * rows).forEach { chunk ->
                        val sheet = PDPage(PDRectangle(sw, sh)); dst.addPage(sheet)
                        PDPageContentStream(dst, sheet).use { cs ->
                            PrintLayout.placeGrid(chunk, ::size, cols, rows, sw, sh, rtl = o.rtlOrder).forEach { c ->
                                place(cs, form(c.page), doc.getPage(c.page), c.x, c.y, c.scale, o.borders, c.w, c.h)
                            }
                        }
                    }
                }
                if (o.grayscale) dst.pages.forEach { com.smartdocs.editor.core.nve.PrintExtras.greyOverlay(dst, it) }
                if (o.copies > 1) { val n0 = dst.numberOfPages; repeat((o.copies - 1).coerceAtMost(98)) { for (i in 0 until n0) dst.addPage(PDPage(dst.getPage(i).cosObject.let { c -> com.tom_roush.pdfbox.cos.COSDictionary(c) })) } }
                if (o.commentSummary) com.smartdocs.editor.core.nve.PrintExtras.appendSummary(dst, summary, summaryLabels,
                    PDRectangle(minOf(base.width, base.height), maxOf(base.width, base.height)), arabicFont?.let { f -> runCatching { f(dst) }.getOrNull() })
                dst.save(out)
                dst.numberOfPages
            }
        }
    }

    /**
     * Draw an imported page at (x, y) scaled by [s]. LayerUtility.importPageAsForm already applies the page's
     * /Rotate and moves the crop box to the origin (form matrix), so only translate + scale are needed.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun place(cs: PDPageContentStream, f: PDFormXObject, page: PDPage, x: Float, y: Float, s: Float, border: Boolean, w: Float, h: Float) {
        cs.saveGraphicsState()
        cs.transform(Matrix.concatenate(Matrix.getTranslateInstance(x, y), Matrix.getScaleInstance(s, s)))
        cs.drawForm(f)
        cs.restoreGraphicsState()
        if (border) { cs.setLineWidth(0.5f); cs.setStrokingColor(0.6f, 0.6f, 0.6f); cs.addRect(x, y, w, h); cs.stroke() }
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
