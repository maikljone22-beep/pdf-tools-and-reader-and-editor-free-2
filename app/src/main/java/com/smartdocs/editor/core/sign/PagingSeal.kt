package com.smartdocs.editor.core.sign

import android.graphics.Bitmap
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File

/**
 * Paging seal ("ختم الصفحات المتعددة" / riding seal): the seal image is cut into N vertical strips and strip i
 * is drawn on the right edge of page i, so when the pages are fanned out the seal reassembles across them.
 * The strips are part of the page content, therefore a digital signature applied afterwards covers them.
 */
object PagingSeal {

    /**
     * @param sizePt     displayed seal diameter/height in points (the full seal is [sizePt] × [sizePt]·h/w)
     * @param yFraction  vertical centre of the strip as a fraction of the page height (0 = bottom, 1 = top)
     * @param pages      zero-based pages to include (null = all); with a single page the whole seal is drawn
     */
    fun apply(src: File, out: File, seal: Bitmap, sizePt: Float = 110f, yFraction: Float = 0.5f, pages: List<Int>? = null, password: String? = null) {
        PdfOps.load(src, password).use { doc ->
            val list = (pages ?: (0 until doc.numberOfPages).toList()).filter { it in 0 until doc.numberOfPages }
            if (list.isEmpty()) throw IllegalArgumentException("no pages")
            val n = list.size
            val aspect = seal.height.toFloat() / seal.width.coerceAtLeast(1)
            val heightPt = sizePt * aspect
            val stripPt = sizePt / n
            list.forEachIndexed { i, pageIndex ->
                val x0 = (seal.width * i) / n
                val x1 = if (i == n - 1) seal.width else (seal.width * (i + 1)) / n
                val strip = Bitmap.createBitmap(seal, x0, 0, (x1 - x0).coerceAtLeast(1), seal.height)
                val page = doc.getPage(pageIndex)
                val box = page.cropBox ?: page.mediaBox
                val img = LosslessFactory.createFromImage(doc, strip)
                val y = box.lowerLeftY + (box.height * yFraction.coerceIn(0.05f, 0.95f)) - heightPt / 2f
                val x = box.upperRightX - stripPt
                // drawn in unrotated page space (pages with /Rotate get the strip on their unrotated right edge)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.drawImage(img, x, y, stripPt, heightPt)
                }
                if (strip != seal) strip.recycle()
            }
            doc.save(out)
        }
    }
}
