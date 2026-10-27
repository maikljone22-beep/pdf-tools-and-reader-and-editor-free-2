package com.smartdocs.editor.core.ocr

import com.smartdocs.editor.core.pdf.showArabic
import android.content.Context
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes an invisible text layer (rendering mode NEITHER) over existing pages so the PDF becomes searchable.
 * Word boxes are in pixels of the rendered page image; they are mapped back to PDF user space, taking the
 * page /Rotate into account.
 */
class SearchablePdfWriter private constructor(private val context: Context, private val doc: PDDocument, private val ownsDoc: Boolean) : AutoCloseable {

    // PdfOps.load re-protects encrypted input so PdfBox 2.0 can save it again
    constructor(context: Context, src: File, password: String? = null) : this(context, PdfOps.load(src, password), true)

    /** Adds invisible layers to a document another writer owns (it saves and closes it). */
    internal constructor(context: Context, doc: PDDocument) : this(context, doc, false)

    private val arabic: PDFont by lazy { PdfOps.arabicFont(context, doc) }
    private val latin: PDFont = PDType1Font.HELVETICA
    val pageCount: Int get() = doc.numberOfPages

    /** Rotation (0/90/180/270) of a page, needed by the caller to size the render. */
    fun rotation(page: Int): Int = doc.getPage(page).rotation.mod(360)

    /** Unrotated media box size in points. */
    fun mediaBox(page: Int): Pair<Float, Float> = doc.getPage(page).mediaBox.let { it.width to it.height }

    private fun isArabic(s: String) = s.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' || it in 'ﭐ'..'﷿' || it in 'ﹰ'..'\uFEFF' }

    /**
     * Add the words of [page] to [pageIndex]. [page].imageWidth/Height is the size of the rendered (rotated) image.
     */
    suspend fun addLayer(pageIndex: Int, page: OcrPage) = withContext(Dispatchers.IO) {
        if (page.words.isEmpty()) return@withContext
        val pdPage = doc.getPage(pageIndex)
        // the OCR image is the page as PDFium renders it: the CropBox (PdfBox clips it to the MediaBox, as PDFium does),
        // so word boxes must be mapped from the CropBox — the MediaBox puts the layer off its words on cropped pages
        val box = pdPage.cropBox ?: pdPage.mediaBox
        val rot = pdPage.rotation.mod(360)
        // displayed (rotated) size in points
        val dispW = if (rot == 90 || rot == 270) box.height else box.width
        val dispH = if (rot == 90 || rot == 270) box.width else box.height
        val s = dispW / page.imageWidth                      // points per pixel (uniform)
        val angle = Math.toRadians(rot.toDouble())
        PDPageContentStream(doc, pdPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.beginText()
            cs.setRenderingMode(RenderingMode.NEITHER)
            page.words.forEach { w ->
                val ar = isArabic(w.text)
                val text = if (ar) ArabicShaper.shape(w.text) else w.text
                val font = if (ar) arabic else latin
                if (text.isBlank()) return@forEach
                val fontSize = (w.box.height() * s).coerceIn(4f, 72f)
                // word origin = bottom-left of its box in display space (px, py with py measured from the top)
                val px = w.box.left.toFloat(); val py = w.box.bottom.toFloat()
                val dx = px * s; val dy = dispH - py * s           // display space, origin bottom-left
                // display -> user space for the page rotation
                // (/Rotate 90 = page shown rotated clockwise: user x runs down the display, user y runs along it)
                val (ux, uy) = when (rot) {
                    90 -> (box.lowerLeftX + (dispH - dy)) to (box.lowerLeftY + dx)
                    180 -> (box.upperRightX - dx) to (box.upperRightY - dy)
                    270 -> (box.lowerLeftX + dy) to (box.upperRightY - dx)
                    else -> (box.lowerLeftX + dx) to (box.lowerLeftY + dy)
                }
                runCatching {
                    cs.setFont(font, fontSize)
                    val natural = runCatching { font.getStringWidth(text) / 1000f * fontSize }.getOrDefault(0f)
                    val target = w.box.width() * s
                    val hs = if (natural > 0.5f) (target / natural * 100f).coerceIn(10f, 1000f) else 100f
                    cs.setHorizontalScaling(hs)
                    cs.setTextMatrix(Matrix.getRotateInstance(angle, ux, uy))
                    cs.showArabic(w.text, text)
                }
            }
            cs.endText()
        }
    }

    suspend fun save(out: File) = withContext(Dispatchers.IO) { doc.save(out) }

    override fun close() { if (ownsDoc) runCatching { doc.close() } }
}
