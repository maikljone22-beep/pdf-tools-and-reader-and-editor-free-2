package com.smartdocs.editor.core.sign

import android.graphics.Bitmap
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * Paging seal ("ختم الصفحات المتعددة" / riding seal): the seal image is cut into N vertical strips and strip i
 * is drawn upright on the right edge of page i as displayed (crop box and /Rotate honoured), so when the pages are
 * fanned out the seal reassembles across them.
 * The strips are part of the page content, therefore a digital signature applied afterwards covers them.
 *
 * nitro-fillsign2: also used on its own for ink Fill & Sign (no digital signature). Every content stream the seal
 * adds (the strip and the `q` that PdfBox puts in front of the existing content) and every strip image carries the
 * private key /SDPagingSeal, so [remove] takes exactly those out again and leaves the page as it was.
 */
object PagingSeal {

    /** Marks the content streams and images a paging seal added. */
    val MARK: COSName = COSName.getPDFName("SDPagingSeal")

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
                img.cosObject.setBoolean(MARK, true)
                // right edge of the page as DISPLAYED (crop box + /Rotate), strip drawn upright
                val geom = SealGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, page.rotation)
                val y = geom.displayHeight * yFraction.coerceIn(0.05f, 0.95f) - heightPt / 2f
                val x = geom.displayWidth - stripPt
                val m = geom.uprightImageMatrix(x, y, stripPt, heightPt)
                appendMarked(doc, page) { cs -> cs.drawImage(img, Matrix(m[0], m[1], m[2], m[3], m[4], m[5])) }
                if (strip != seal) strip.recycle()
            }
            doc.save(out)
        }
    }

    /** Appends content drawn by [draw] to [page] and marks every content stream that added ([MARK]). */
    internal fun appendMarked(doc: PDDocument, page: PDPage, draw: (PDPageContentStream) -> Unit) {
        val before = streams(page).toSet()
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use(draw)
        streams(page).filter { s -> before.none { it === s } }.forEach { it.setBoolean(MARK, true) }
    }

    private fun streams(page: PDPage): List<COSStream> = when (val c = page.cosObject.getDictionaryObject(COSName.CONTENTS)) {
        is COSStream -> listOf(c)
        is COSArray -> (0 until c.size()).mapNotNull { c.getObject(it) as? COSStream }
        else -> emptyList()
    }

    /** Pages of [doc] that carry a paging seal. */
    fun sealedPages(doc: PDDocument): List<Int> = (0 until doc.numberOfPages).filter { i -> streams(doc.getPage(i)).any { it.getBoolean(MARK, false) } }

    /** Takes every paging seal out of [doc] (marked streams and images); returns the number of pages changed. */
    fun removeFrom(doc: PDDocument): Int {
        var n = 0
        for (i in 0 until doc.numberOfPages) {
            val page = doc.getPage(i)
            val dict = page.cosObject
            var changed = false
            when (val c = dict.getDictionaryObject(COSName.CONTENTS)) {
                is COSStream -> if (c.getBoolean(MARK, false)) { dict.setItem(COSName.CONTENTS, COSArray()); changed = true }
                is COSArray -> {
                    val keep = COSArray()
                    for (k in 0 until c.size()) {
                        val s = c.getObject(k)
                        if (s is COSStream && s.getBoolean(MARK, false)) changed = true else keep.add(c.get(k) as COSBase)
                    }
                    if (changed) dict.setItem(COSName.CONTENTS, keep)
                }
                else -> {}
            }
            // the strip images are no longer drawn: drop them from the page resources too
            (page.resources?.cosObject?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)?.let { xo ->
                xo.keySet().toList().forEach { k -> if ((xo.getDictionaryObject(k) as? COSStream)?.getBoolean(MARK, false) == true) xo.removeItem(k) }
            }
            if (changed) n++
        }
        return n
    }

    /** Standalone "Remove paging seal" (nitro-fillsign2): writes [out] only when a seal was found; returns pages changed. */
    fun remove(src: File, out: File, password: String? = null): Int =
        PdfOps.load(src, password).use { doc -> removeFrom(doc).also { if (it > 0) doc.save(out) } }

    /** Pages of [src] that carry a paging seal (empty when none, or the file cannot be read). */
    fun sealedPages(src: File, password: String? = null): List<Int> =
        runCatching { PdfOps.loadRaw(src, password).use { sealedPages(it) } }.getOrDefault(emptyList())
}
