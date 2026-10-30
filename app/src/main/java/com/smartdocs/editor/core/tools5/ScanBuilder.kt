package com.smartdocs.editor.core.tools5

import android.content.Context
import android.graphics.Bitmap
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.ocr.OcrPage
import com.smartdocs.editor.core.ocr.OcrWord
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.showArabic
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Invisible OCR text over a picture drawn at ([ox],[oy]) size [w]×[h] (PDF points, origin bottom-left) on an unrotated page. */
object TextLayer {

    fun write(cs: PDPageContentStream, words: List<OcrWord>, imgW: Int, imgH: Int, ox: Float, oy: Float, w: Float, h: Float, arabic: PDFont?, latin: PDFont = PDType1Font.HELVETICA) {
        if (words.isEmpty() || imgW <= 0 || imgH <= 0) return
        val sx = w / imgW; val sy = h / imgH
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        words.forEach { wd ->
            val ar = ArabicShaper.isArabic(wd.text)
            val font = if (ar) arabic ?: return@forEach else latin
            val shown = if (ar) ArabicShaper.shape(wd.text) else wd.text.filter { it.code in 32..255 }
            if (shown.isBlank()) return@forEach
            runCatching {
                val size = (wd.box.height() * sy).coerceIn(3f, 96f)
                cs.setFont(font, size)
                val natural = runCatching { font.getStringWidth(shown) / 1000f * size }.getOrDefault(0f)
                val target = wd.box.width() * sx
                cs.setHorizontalScaling(if (natural > 0.5f) (target / natural * 100f).coerceIn(10f, 1000f) else 100f)
                cs.setTextMatrix(Matrix.getTranslateInstance(ox + wd.box.left * sx, oy + (imgH - wd.box.bottom) * sy))
                cs.showArabic(wd.text, shown)
            }
        }
        cs.endText()
    }
}

/** Paper of a scan PDF; FIT = page sized to the picture (200 dpi). */
/** Output page size of a scan (Foxit 5 paper sizes; CARD = ID-1). */
enum class ScanPaper { FIT, A4, LETTER, LEGAL, A3, A5, B5, CARD }

/** One finished page picture (after filters / composition) plus whether it is grey. */
class ScanOut(val bitmap: Bitmap, val gray: Boolean)

data class ScanBuildOptions(
    val paper: ScanPaper = ScanPaper.FIT,
    val quality: Int = 82,
    /** Tesseract languages ("ara+eng") or null for no OCR. */
    val ocrLangs: String? = null,
)

class ScanBuildResult(val file: File, val pages: Int, val text: String)

/**
 * Scan studio output: page pictures → PDF (JPEG, or lossless for pure black/white pages) with an optional
 * invisible OCR text layer, so the scan is searchable right away.
 */
object ScanBuilder {

    /** Turn the captured images + edits of one document into page pictures, according to the scan [kind]. */
    fun pagePictures(images: List<Pair<File, PageEdit>>, kind: ScanKind, bookRightFirst: Boolean, maxDim: Int = 2400): Sequence<ScanOut> = sequence {
        if (kind == ScanKind.ID_CARD) {
            val shots = images.take(2).mapNotNull { (f, e) -> ScanImage.load(f, maxDim)?.let { ScanImage.apply(it, e) } }
            if (shots.isNotEmpty()) {
                val page = ScanImage.idCardPage(shots[0], shots.getOrNull(1))
                shots.forEach { it.recycle() }
                yield(ScanOut(page, images.firstOrNull()?.second?.filter?.let { ScanImage.isGray(it) } == true))
            }
            // more than two shots: the rest are ordinary pages
            images.drop(2).forEach { (f, e) -> ScanImage.load(f, maxDim)?.let { yield(ScanOut(ScanImage.apply(it, e), ScanImage.isGray(e.filter))) } }
            return@sequence
        }
        images.forEach { (f, e) ->
            val b = ScanImage.load(f, if (kind == ScanKind.BOOK) maxDim * 3 / 2 else maxDim) ?: return@forEach
            val done = ScanImage.apply(b, e, dewarp = e.flatten && kind != ScanKind.BOOK)
            if (kind == ScanKind.BOOK) {
                // a spread bends both ways from the spine: each page is flattened on its own
                val halves = ScanImage.splitBook(done, bookRightFirst).map { if (e.flatten) ScanImage.flatten(it) else it }
                halves.forEach { yield(ScanOut(it, ScanImage.isGray(e.filter))) }
                if (halves.size > 1) done.recycle()
            } else yield(ScanOut(done, ScanImage.isGray(e.filter)))
        }
    }

    suspend fun build(
        ctx: Context, pictures: Sequence<ScanOut>, out: File, opt: ScanBuildOptions, engine: OcrEngine?,
        onPage: (Int) -> Unit = {},
    ): ScanBuildResult = withContext(Dispatchers.IO) {
        val text = StringBuilder()
        var count = 0
        val ocrReady = engine != null && opt.ocrLangs != null && runCatching { engine.ensureLanguages(opt.ocrLangs); engine.init(opt.ocrLangs) }.getOrDefault(false)
        PDDocument().use { doc ->
            val arabic: PDFont? by lazy { runCatching { PdfOps.arabicFont(ctx, doc) }.getOrNull()?.takeIf { it !is PDType1Font } }
            for (p in pictures) {
                coroutineContext.ensureActive()
                onPage(count)
                val bmp = p.bitmap
                val rect = ScanExtras.pageRect(opt.paper, bmp.width, bmp.height)
                val page = PDPage(rect); doc.addPage(page)
                val k = minOf(rect.width / bmp.width, rect.height / bmp.height)
                val w = bmp.width * k; val h = bmp.height * k
                val ox = (rect.width - w) / 2; val oy = (rect.height - h) / 2
                val bilevel = run {
                    val sample = IntArray(minOf(bmp.width * bmp.height, 40_000))
                    val small = Bitmap.createScaledBitmap(bmp, 200, maxOf(1, 200 * bmp.height / bmp.width), false)
                    small.getPixels(sample, 0, small.width, 0, 0, small.width, small.height.coerceAtMost(sample.size / small.width))
                    if (small !== bmp) small.recycle()
                    PixelOps.isBilevel(sample)
                }
                val img = if (bilevel) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, opt.quality.coerceIn(30, 100) / 100f)
                var ocr: OcrPage? = null
                if (ocrReady) ocr = runCatching { engine!!.recognize(bmp, wantWords = true) }.getOrNull()
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, ox, oy, w, h)
                    ocr?.let { TextLayer.write(cs, it.words, it.imageWidth, it.imageHeight, ox, oy, w, h, arabic) }
                }
                ocr?.let { text.append(it.text).append('\n') }
                bmp.recycle()
                count++
            }
            if (count == 0) throw IllegalStateException("no pages")
            doc.documentInformation.creator = "SmartDocs"
            doc.save(out)
        }
        ScanBuildResult(out, count, text.toString())
    }
}
