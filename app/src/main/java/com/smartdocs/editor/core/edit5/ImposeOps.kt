package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.PageFrame
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Imposition into a new document: N-up, saddle-stitch booklet and poster tiles. Every source page is imported once as
 * a form XObject (vector, shared by every placement) and drawn upright (its /Rotate and crop box applied).
 */
object ImposeOps {

    private class Src(val form: PDFormXObject, val frame: PageFrame)

    private class Importer(private val target: PDDocument, private val source: PDDocument) {
        private val lu = LayerUtility(target)
        private val cache = HashMap<Int, Src>()
        fun get(i: Int): Src = cache.getOrPut(i) {
            val page = source.getPage(i)
            val form = lu.importPageAsForm(source, page)
            // LayerUtility's own rotation matrix distorts non-square rotated pages: draw in user space and rotate ourselves
            form.cosObject.removeItem(COSName.MATRIX)
            form.bBox = page.cropBox
            Src(form, PageFrame.of(page))
        }
    }

    /** Draw [s] fitted into the cell (x, y, w, h) of the output page, centred. */
    private fun place(cs: PDPageContentStream, s: Src, x: Float, y: Float, w: Float, h: Float, border: Boolean) {
        val dw = s.frame.width; val dh = s.frame.height
        val k = minOf(w / dw, h / dh)
        val ox = x + (w - dw * k) / 2f; val oy = y + (h - dh * k) / 2f
        cs.saveGraphicsState()
        cs.transform(Affine.mul(Affine.mul(s.frame.toDisplay, Affine.scale(k, k)), Affine.translate(ox, oy)))
        cs.drawForm(s.form)
        cs.restoreGraphicsState()
        if (border) { cs.setStrokingColor(0.6f, 0.6f, 0.6f); cs.setLineWidth(0.5f); cs.addRect(ox, oy, dw * k, dh * k); cs.stroke() }
    }

    /** Output sheet size: [paper] (portrait w,h) oriented by [landscape], or null = derived from the first page. */
    private fun sheet(first: PageFrame, cols: Int, rows: Int, paper: Pair<Float, Float>?, landscape: Boolean?): Pair<Float, Float> {
        if (paper != null) {
            val lw = minOf(paper.first, paper.second); val lh = maxOf(paper.first, paper.second)
            val land = landscape ?: (cols > rows)
            return if (land) lh to lw else lw to lh
        }
        return first.width * cols to first.height * rows
    }

    suspend fun nUp(src: File, out: File, password: String?, cols: Int, rows: Int, paper: Pair<Float, Float>?, landscape: Boolean?, rtl: Boolean, columnFirst: Boolean,
                    margin: Float = 18f, gap: Float = 8f, borders: Boolean = false, pages: List<Int>? = null) = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            PDDocument().use { nd ->
                val imp = Importer(nd, doc)
                val list = pages ?: (0 until doc.numberOfPages).toList()
                if (list.isEmpty()) throw IllegalArgumentException("no pages")
                val (sw, sh) = sheet(PageFrame.of(doc.getPage(list[0])), cols, rows, paper, landscape)
                val cw = (sw - 2 * margin - (cols - 1) * gap) / cols
                val ch = (sh - 2 * margin - (rows - 1) * gap) / rows
                for (cells in ImposeOrder.nUp(list.size, cols, rows, rtl, columnFirst)) {
                    val page = PDPage(PDRectangle(sw, sh)); nd.addPage(page)
                    PDPageContentStream(nd, page).use { cs ->
                        for (c in cells) {
                            val x = margin + c.col * (cw + gap)
                            val y = sh - margin - (c.row + 1) * ch - c.row * gap
                            place(cs, imp.get(list[c.page]), x, y, cw, ch, borders)
                        }
                    }
                }
                nd.documentInformation.title = doc.documentInformation.title
                E5Io.save(nd, out)
            }
        }
    }

    /**
     * Saddle-stitch booklet: two pages per sheet side in fold order; print double-sided (flip on short edge),
     * stack, fold and staple. [rtl] = right binding (Arabic books).
     */
    suspend fun booklet(src: File, out: File, password: String?, rtl: Boolean, paper: Pair<Float, Float>?, margin: Float = 12f, creep: Float = 0f) = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            PDDocument().use { nd ->
                val imp = Importer(nd, doc)
                val n = doc.numberOfPages
                if (n == 0) throw IllegalArgumentException("no pages")
                val (sw, sh) = sheet(PageFrame.of(doc.getPage(0)), 2, 1, paper, true)
                val half = sw / 2f
                val sides = ImposeOrder.booklet(n, rtl)
                val sheets = sides.size / 2
                sides.forEachIndexed { k, side ->
                    val page = PDPage(PDRectangle(sw, sh)); nd.addPage(page)
                    // creep: inner sheets are pushed outward a little so the trimmed edge stays even
                    val shift = if (sheets > 1) creep * (1f - (k / 2).toFloat() / (sheets - 1)) else 0f
                    PDPageContentStream(nd, page).use { cs ->
                        if (side.left >= 0) place(cs, imp.get(side.left), margin - shift, margin, half - 1.5f * margin, sh - 2 * margin, false)
                        if (side.right >= 0) place(cs, imp.get(side.right), half + margin / 2f + shift, margin, half - 1.5f * margin, sh - 2 * margin, false)
                    }
                }
                E5Io.save(nd, out)
            }
        }
    }

    /**
     * Poster: each page in [pages] enlarged by [scale] and cut into tiles of [paper] with [overlap] points shared,
     * optional cut marks and a small "row-col" label on every tile.
     */
    suspend fun poster(src: File, out: File, password: String?, pages: List<Int>, scale: Float, paper: Pair<Float, Float>, landscape: Boolean, overlap: Float = 18f, marks: Boolean = true, margin: Float = 18f) = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            PDDocument().use { nd ->
                val imp = Importer(nd, doc)
                val pw = if (landscape) maxOf(paper.first, paper.second) else minOf(paper.first, paper.second)
                val ph = if (landscape) minOf(paper.first, paper.second) else maxOf(paper.first, paper.second)
                val tw = pw - 2 * margin; val th = ph - 2 * margin
                for (i in pages) {
                    if (i !in 0 until doc.numberOfPages) continue
                    val s = imp.get(i)
                    for (t in ImposeOrder.posterTiles(s.frame.width, s.frame.height, scale, tw, th, overlap)) {
                        val page = PDPage(PDRectangle(pw, ph)); nd.addPage(page)
                        PDPageContentStream(nd, page).use { cs ->
                            cs.saveGraphicsState()
                            cs.addRect(margin, margin, tw, th); cs.clip()
                            // tile (t.x, t.y) of the source (display) maps to the printable area's corner
                            val m = Affine.mul(Affine.mul(Affine.mul(s.frame.toDisplay, Affine.translate(-t.x, -t.y)), Affine.scale(scale, scale)), Affine.translate(margin, margin))
                            cs.transform(m); cs.drawForm(s.form)
                            cs.restoreGraphicsState()
                            if (marks) {
                                cs.setStrokingColor(0f, 0f, 0f); cs.setLineWidth(0.3f)
                                val l = 10f
                                for ((cx, cy) in listOf(margin to margin, margin + tw to margin, margin to margin + th, margin + tw to margin + th)) {
                                    cs.moveTo(cx - l, cy); cs.lineTo(cx + l, cy); cs.moveTo(cx, cy - l); cs.lineTo(cx, cy + l)
                                }
                                if (overlap > 0f) {
                                    cs.setLineDashPattern(floatArrayOf(2f, 2f), 0f)
                                    cs.moveTo(margin + tw - overlap, margin); cs.lineTo(margin + tw - overlap, margin + th)
                                    cs.moveTo(margin, margin + overlap); cs.lineTo(margin + tw, margin + overlap)
                                }
                                cs.stroke()
                                cs.setLineDashPattern(floatArrayOf(), 0f)
                                cs.beginText(); cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 7f); cs.setNonStrokingColor(0.3f, 0.3f, 0.3f)
                                cs.newLineAtOffset(margin, 6f); cs.showText("${i + 1}: ${t.row + 1}-${t.col + 1}"); cs.endText()
                            }
                        }
                    }
                }
                E5Io.save(nd, out)
            }
        }
    }
}
