package com.smartdocs.editor.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.showArabic
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Editable text" OCR: every recognised page is rebuilt so its text is real, visible PDF text.
 *
 * For each page: the scanned glyphs of every confidently recognised word are painted over with the colour
 * sampled around the word (median of the box border), the cleaned bitmap becomes the page background, and
 * each word is written as visible text (rendering mode FILL) in its box, at the measured size, fitted to the
 * box width with horizontal scaling and in the ink colour sampled from the scan. Arabic goes through
 * [ArabicShaper] + [showArabic] so search / copy return logical text. Words recognised with low confidence
 * keep their scanned glyphs and only get an invisible (searchable) text run.
 */
class EditableTextPdfWriter(private val context: Context, src: File, password: String? = null) : AutoCloseable {

    private val doc: PDDocument = PdfOps.load(src, password)
    private val arabic: PDFont by lazy { PdfOps.arabicFont(context, doc) }
    private val latin: PDFont = PDType1Font.HELVETICA
    private val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF; textSize = 100f }
    val pageCount: Int get() = doc.numberOfPages

    /**
     * Replace page [pageIndex] by [image] (the page rendered in display orientation, must be mutable — it is
     * modified) cleaned of the recognised words, plus the words of [page] as real text.
     */
    suspend fun replacePage(pageIndex: Int, image: Bitmap, page: OcrPage, minConfidence: Float = 45f) = withContext(Dispatchers.Default) {
        val words = page.words.filter { it.text.isNotBlank() && it.box.width() > 1 && it.box.height() > 1 }
        if (words.isEmpty()) return@withContext
        val bmp = if (image.isMutable) image else image.copy(Bitmap.Config.ARGB_8888, true)
        val pdPage = doc.getPage(pageIndex)
        val box = pdPage.cropBox ?: pdPage.mediaBox
        val rot = pdPage.rotation.mod(360)
        val dispW = if (rot == 90 || rot == 270) box.height else box.width
        val dispH = if (rot == 90 || rot == 270) box.width else box.height
        val s = dispW / bmp.width                                   // points per pixel

        // 1. colours + erase the scanned glyphs of confident words
        val canvas = Canvas(bmp)
        val fill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }
        val ink = HashMap<OcrWord, Int>()
        val strong = HashSet<OcrWord>()
        words.forEach { w ->
            val r = Rect(w.box).apply { intersect(0, 0, bmp.width, bmp.height) }
            if (r.isEmpty) return@forEach
            val bg = borderMedian(bmp, r)
            ink[w] = inkColor(bmp, r, bg)
            if (w.confidence >= minConfidence) {
                strong += w
                fill.color = bg
                canvas.drawRect((r.left - 1).toFloat(), (r.top - 1).toFloat(), (r.right + 1).toFloat(), (r.bottom + 1).toFloat(), fill)
            }
        }

        // 2. background image at ~200 dpi
        val pxPerPt = bmp.width / dispW
        val target = 200f / 72f
        val bg = if (pxPerPt > target * 1.05f) Bitmap.createScaledBitmap(bmp, (dispW * target).toInt().coerceAtLeast(1), (dispH * target).toInt().coerceAtLeast(1), true) else bmp

        withContext(Dispatchers.IO) {
            val img = JPEGFactory.createFromImage(doc, bg, 0.82f)
            if (bg !== bmp) bg.recycle()
            val rect = PDRectangle(dispW, dispH)
            pdPage.rotation = 0
            pdPage.mediaBox = rect
            pdPage.cropBox = rect
            runCatching { pdPage.trimBox = rect; pdPage.bleedBox = rect; pdPage.artBox = rect }
            pdPage.resources = PDResources()
            PDPageContentStream(doc, pdPage, PDPageContentStream.AppendMode.OVERWRITE, true, true).use { cs ->
                cs.drawImage(img, 0f, 0f, dispW, dispH)
                cs.beginText()
                words.forEach { w -> runCatching { writeWord(cs, w, w in strong, ink[w] ?: 0x000000, s, dispH) } }
                cs.endText()
            }
        }
    }

    private fun writeWord(cs: PDPageContentStream, w: OcrWord, visible: Boolean, color: Int, s: Float, dispH: Float) {
        val text = w.text
        val ar = ArabicShaper.isArabic(text)
        val shaped = if (ar) ArabicShaper.shape(text) else text
        val font = if (ar) arabic else latin
        // size + baseline from the glyph bounds of the same text in a comparable font
        val b = Rect(); measure.getTextBounds(text, 0, text.length, b)
        val ratio = (b.height() / 100f).takeIf { it > 0.05f } ?: 0.7f
        val fontPx = w.box.height() / ratio
        val baselinePx = w.box.bottom - (b.bottom / 100f) * fontPx
        val fontSize = (fontPx * s).coerceIn(3f, 200f)
        val x = w.box.left * s
        val y = dispH - baselinePx * s
        cs.setFont(font, fontSize)
        cs.setRenderingMode(if (visible) RenderingMode.FILL else RenderingMode.NEITHER)
        cs.setNonStrokingColor((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
        val natural = runCatching { font.getStringWidth(shaped) / 1000f * fontSize }.getOrDefault(0f)
        val targetW = w.box.width() * s
        cs.setHorizontalScaling(if (natural > 0.5f) (targetW / natural * 100f).coerceIn(20f, 500f) else 100f)
        cs.setTextMatrix(Matrix.getTranslateInstance(x, y))
        cs.showArabic(text, shaped)
    }

    /** Median colour of a 2 px ring just outside [r] — the paper around the word. */
    private fun borderMedian(b: Bitmap, r: Rect): Int {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        fun add(c: Int) { rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF }
        val l = (r.left - 3).coerceAtLeast(0); val rt = (r.right + 2).coerceAtMost(b.width - 1)
        val step = maxOf(1, (rt - l) / 60)
        for (y in intArrayOf(r.top - 3, r.top - 2, r.bottom + 1, r.bottom + 2)) {
            if (y !in 0 until b.height) continue
            var x = l; while (x <= rt) { add(b.getPixel(x, y)); x += step }
        }
        val vstep = maxOf(1, r.height() / 30)
        for (x in intArrayOf(r.left - 3, r.left - 2, r.right + 1, r.right + 2)) {
            if (x !in 0 until b.width) continue
            var y = r.top.coerceAtLeast(0); while (y < r.bottom.coerceAtMost(b.height)) { add(b.getPixel(x, y)); y += vstep }
        }
        if (rs.isEmpty()) return 0xFFFFFFFF.toInt()
        fun med(l: MutableList<Int>) = l.sorted()[l.size / 2]
        return (0xFF shl 24) or (med(rs) shl 16) or (med(gs) shl 8) or med(bs)
    }

    /** Average of the pixels inside [r] clearly darker than the background: the ink colour (RGB). */
    private fun inkColor(b: Bitmap, r: Rect, bg: Int): Int {
        val w = r.width(); val h = r.height()
        if (w <= 0 || h <= 0) return 0
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, r.left, r.top, w, h)
        fun lum(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        val limit = lum(bg) - 60
        var n = 0; var sr = 0L; var sg = 0L; var sb = 0L
        for (c in px) if (lum(c) < limit) { n++; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
        if (n < 5) return 0x000000
        // darken slightly: anti-aliased edges make the plain average look washed out
        val k = 0.8
        return (((sr / n) * k).toInt() shl 16) or (((sg / n) * k).toInt() shl 8) or ((sb / n) * k).toInt()
    }

    suspend fun save(out: File) = withContext(Dispatchers.IO) { doc.save(out) }

    override fun close() { runCatching { doc.close() } }
}
