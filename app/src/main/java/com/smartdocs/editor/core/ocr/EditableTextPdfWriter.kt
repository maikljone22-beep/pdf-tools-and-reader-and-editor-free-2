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
    private val layer by lazy { SearchablePdfWriter(context, doc) }

    /**
     * For pages that already have real text: keep the page exactly as it is and only add the recognised words as
     * an invisible, searchable layer (rebuilding such a page would turn its real text into a picture).
     */
    suspend fun addInvisible(pageIndex: Int, page: OcrPage) = layer.addLayer(pageIndex, page)

    /**
     * Replace page [pageIndex] by [image] (the page rendered in display orientation, must be mutable — it is
     * modified) cleaned of the recognised words, plus the words of [page] as real text.
     */
    suspend fun replacePage(pageIndex: Int, image: Bitmap, page: OcrPage, minConfidence: Float = 75f, vectorRules: Boolean = true) = withContext(Dispatchers.Default) {
        val all = page.words.filter { it.text.isNotBlank() && it.box.width() > 1 && it.box.height() > 1 && it.text.any { c -> c.isLetterOrDigit() } }
        if (all.isEmpty()) return@withContext
        // drop junk "words": far taller than a text line (decorations, borders, big margin marks)
        val medianH = all.map { it.box.height() }.sorted()[all.size / 2].coerceAtLeast(1)
        val words = all.filter { it.box.height() <= medianH * 3 && it.box.width() < image.width * 0.95f }
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
            // Tesseract's word box often leaves out the letter dots above/below Arabic letters; erase a taller box so
            // no stray dots stay between the rewritten words
            val padY = (w.box.height() * 0.28f).toInt().coerceAtLeast(2); val padX = (w.box.height() * 0.08f).toInt().coerceAtLeast(1)
            val r = Rect(w.box.left - padX, w.box.top - padY, w.box.right + padX, w.box.bottom + padY)
            // Rect.intersect leaves the rect unchanged when it misses the bitmap: skip such a box (reading it would throw)
            if (!r.intersect(0, 0, bmp.width, bmp.height) || r.isEmpty) return@forEach
            val bg = borderMedian(bmp, r)
            val wordInk = inkColor(bmp, r, bg)
            ink[w] = wordInk ?: 0x000000
            // rewrite a word visibly only when it is reliable, sits on plain paper, has visible ink (light text on a dark
            // box included) and fits its box without stretching; otherwise its scanned glyphs stay and it only gets
            // invisible searchable text
            if (wordInk != null && w.confidence >= minConfidence && plainBackground(bmp, r, bg) && fitsBox(w, s)) {
                strong += w
                fill.color = bg
                canvas.drawRect((r.left - 1).toFloat(), (r.top - 1).toFloat(), (r.right + 1).toFloat(), (r.bottom + 1).toFloat(), fill)
            }
        }

        // FINAL_CHECK_2 Low: table rules / ruled lines become vector lines (erased from the picture, drawn sharp)
        val rules = if (!vectorRules) emptyList() else runCatching {
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            TableRules.detect(px, bmp.width, bmp.height).also { list ->
                list.forEach { r ->
                    val ry = if (r.horizontal) (r.y0 - 2).coerceAtLeast(0) else (r.y0 + r.y1) / 2
                    val rx = if (r.horizontal) (r.x0 + r.x1) / 2 else (r.x0 - 2).coerceAtLeast(0)
                    fill.color = px[ry * bmp.width + rx]
                    canvas.drawRect(r.x0 - 1f, r.y0 - 1f, r.x1 + 2f, r.y1 + 2f, fill)
                }
            }
        }.getOrDefault(emptyList())

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
                rules.forEach { r ->
                    cs.setStrokingColor(((r.color shr 16) and 0xFF) / 255f, ((r.color shr 8) and 0xFF) / 255f, (r.color and 0xFF) / 255f)
                    cs.setLineWidth((r.thick * s).coerceAtLeast(0.3f))
                    if (r.horizontal) { val y = dispH - (r.y0 + r.y1 + 1) / 2f * s; cs.moveTo(r.x0 * s, y); cs.lineTo((r.x1 + 1) * s, y) }
                    else { val x = (r.x0 + r.x1 + 1) / 2f * s; cs.moveTo(x, dispH - r.y0 * s); cs.lineTo(x, dispH - (r.y1 + 1) * s) }
                    cs.stroke()
                }
                cs.beginText()
                // visual line order (left to right), as SearchablePdfWriter writes its invisible words, so PDFium keeps lines together
                SearchablePdfWriter.visualLines(words).flatten().forEach { w -> runCatching { writeWord(cs, w, w in strong, ink[w] ?: 0x000000, s, dispH) } }
                cs.endText()
            }
        }
    }

    private fun writeWord(cs: PDPageContentStream, w: OcrWord, visible: Boolean, color: Int, s: Float, dispH: Float) {
        val text = w.text
        val ar = ArabicShaper.isArabic(text)
        val font = if (ar) arabic else latin
        // visible words are shaped for display (+ /ActualText). Invisible words are only extracted: unshaped base letters in
        // visual order, which PDFium turns back into logical text (see SearchablePdfWriter.addLayer)
        val shaped = when {
            !ar -> text
            visible -> ArabicShaper.shape(text)
            else -> SearchablePdfWriter.encodable(font, SearchablePdfWriter.visualWord(SearchablePdfWriter.invisibleArabic(text)))
        }
        if (shaped.isBlank()) return
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
        // visible words were checked to fit (fitsBox); invisible ones may stretch freely to cover their scan for selection
        cs.setHorizontalScaling(if (natural > 0.5f) (targetW / natural * 100f).coerceIn(if (visible) 75f else 10f, if (visible) 135f else 1000f) else 100f)
        cs.setTextMatrix(Matrix.getTranslateInstance(x, y))
        if (visible) cs.showArabic(text, shaped) else cs.showText(shaped)
    }

    /** Natural width of the word in our font is within 0.75..1.35 of its box (no visible stretching). */
    private fun fitsBox(w: OcrWord, s: Float): Boolean = runCatching {
        val text = w.text
        val ar = ArabicShaper.isArabic(text)
        val shaped = if (ar) ArabicShaper.shape(text) else text
        val font = if (ar) arabic else latin
        val b = Rect(); measure.getTextBounds(text, 0, text.length, b)
        val ratio = (b.height() / 100f).takeIf { it > 0.05f } ?: 0.7f
        val fontSize = (w.box.height() / ratio * s).coerceIn(3f, 200f)
        val natural = font.getStringWidth(shaped) / 1000f * fontSize
        val k = w.box.width() * s / natural
        natural > 0.5f && k in 0.75f..1.35f
    }.getOrDefault(false)

    /** The ring around the word is nearly one colour (paper), so painting over the word leaves no patch. */
    private fun plainBackground(b: Bitmap, r: Rect, bg: Int): Boolean {
        fun lum(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        val ref = lum(bg)
        var n = 0; var off = 0
        val l = (r.left - 3).coerceAtLeast(0); val rt = (r.right + 2).coerceAtMost(b.width - 1)
        val step = maxOf(1, (rt - l) / 40)
        for (y in intArrayOf(r.top - 3, r.bottom + 2)) {
            if (y !in 0 until b.height) continue
            var x = l; while (x <= rt) { n++; if (kotlin.math.abs(lum(b.getPixel(x, y)) - ref) > 28) off++; x += step }
        }
        return n == 0 || off * 100 / n <= 12
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

    /**
     * Ink colour (RGB) of the word in [r]: the average of the pixels whose luminance differs clearly from the background
     * [bg] — darker ink on paper, or lighter ink on a dark box. Null when there is no clear ink (the word could not be
     * redrawn in a visible colour; it used to come out black on a black box, i.e. erased).
     */
    private fun inkColor(b: Bitmap, r: Rect, bg: Int): Int? {
        val w = r.width(); val h = r.height()
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, r.left, r.top, w, h)
        return inkOf(px, bg)
    }

    suspend fun save(out: File) = withContext(Dispatchers.IO) { doc.save(out) }

    override fun close() { runCatching { doc.close() } }

    companion object {
        /** See [inkColor]; [px] are ARGB pixels of the word box. Plain Kotlin (unit-tested). */
        fun inkOf(px: IntArray, bg: Int): Int? {
            fun lum(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            val ref = lum(bg)
            var nD = 0; var nL = 0
            for (c in px) { val l = lum(c); if (l < ref - 60) nD++ else if (l > ref + 60) nL++ }
            val dark = nD >= nL
            val n = if (dark) nD else nL
            if (n < 5) return null
            var sr = 0L; var sg = 0L; var sb = 0L
            for (c in px) {
                val l = lum(c)
                if (if (dark) l < ref - 60 else l > ref + 60) { sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
            }
            // push away from the background: anti-aliased edges make the plain average look washed out
            val k = 0.8
            fun ch(sum: Long) = (sum / n).toInt().let { v -> if (dark) (v * k).toInt() else (255 - (255 - v) * k).toInt() }.coerceIn(0, 255)
            return (ch(sr) shl 16) or (ch(sg) shl 8) or ch(sb)
        }
    }

}