package com.smartdocs.editor.core.ocr

import android.content.Context
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

    companion object {
        /**
         * Arabic OCR word as written to the invisible layer: logical order, base letters (presentation forms from the
         * recogniser folded to letters), no tatweel, bidi controls (LRM/RLM/ALM...) dropped. Plain Kotlin (unit-tested).
         */
        fun invisibleArabic(word: String): String {
            val base = com.smartdocs.editor.core.search.ArabicTextFix.toBaseLetters(word)
            return base.filter { c -> c.code != 0x0640 && c.code !in 0x200B..0x200F && c.code !in 0x202A..0x202E && c.code != 0x061C && c.code !in 0x2066..0x2069 }
        }

        /**
         * Visual (left-to-right) order of one Arabic OCR word: runs of digits / Latin letters keep their order, everything
         * else is reversed with brackets mirrored, and the runs are reversed. «(محددة)» -> «(ةددحم)», «٢٠٢٥م» -> «م٢٠٢٥».
         */
        fun visualWord(word: String): String {
            val runs = ArrayList<StringBuilder>(); var ltrRun = false
            for (c in word) {
                val ltr = c.isDigit() || c in 'a'..'z' || c in 'A'..'Z'
                if (runs.isEmpty() || ltr != ltrRun) { runs += StringBuilder(); ltrRun = ltr }
                runs.last().append(c)
            }
            return runs.asReversed().joinToString("") { r ->
                if (r.first().let { it.isDigit() || it in 'a'..'z' || it in 'A'..'Z' }) r.toString()
                else r.reversed().map { MIRROR[it] ?: it }.joinToString("")
            }
        }

        /**
         * Baseline (bitmap y) a word is written on: the line's median word bottom [median] (line height [lineH], the median
         * word height) when the word lies within the line band — letters with and without descenders, a small «-» or «.»
         * in the middle of the line — else the word's own bottom (a word Tesseract put on the line although it sits clearly
         * higher or lower), so its selection box stays on the word.
         */
        fun lineBaseline(w: OcrWord, median: Int, lineH: Int): Int = lineBaseline(w.box.top, w.box.bottom, median, lineH)

        /** [lineBaseline] on plain numbers (bitmap y grows downwards). */
        fun lineBaseline(top: Int, bottom: Int, median: Int, lineH: Int): Int =
            if (bottom <= median + lineH / 2 && top >= median - lineH * 3 / 2) median else bottom

        /** Keeps only characters [font] can encode (an OCR word such as «(م“١)» may hold a quote the font lacks). */
        fun encodable(font: PDFont, text: String): String {
            if (runCatching { font.encode(text) }.isSuccess) return text
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i); val ch = String(Character.toChars(cp))
                if (runCatching { font.encode(ch) }.isSuccess) sb.append(ch)
                i += Character.charCount(cp)
            }
            return sb.toString()
        }

        private val MIRROR = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

        /**
         * Groups OCR words (in the recogniser's reading order) into text lines and returns each line left to right.
         * A word starts a new line when its vertical centre is outside the current line's band (rule: [lineIndexes]).
         */
        fun visualLines(words: List<OcrWord>): List<List<OcrWord>> {
            val usable = words.filter { !it.text.isBlank() && it.box.width() > 0 && it.box.height() > 0 }
            return lineIndexes(usable.map { intArrayOf(it.box.left, it.box.top, it.box.right, it.box.bottom) }).map { l -> l.map { usable[it] } }
        }

        /** [visualLines] on plain boxes (left, top, right, bottom; y down) in reading order: indexes per line, left to right. */
        fun lineIndexes(boxes: List<IntArray>): List<List<Int>> {
            val lines = ArrayList<MutableList<Int>>()
            var top = 0; var bottom = 0
            boxes.forEachIndexed { i, b ->
                val cy = (b[1] + b[3]) / 2
                val cur = lines.lastOrNull()
                if (cur != null && cy in top..bottom) {
                    cur += i; top = minOf(top, b[1]); bottom = maxOf(bottom, b[3])
                } else {
                    lines += mutableListOf(i); top = b[1]; bottom = b[3]
                }
            }
            return lines.map { l -> l.sortedBy { boxes[it][0] } }
        }
    }

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
            // PDFium (the app's text engine) builds lines from glyph positions and expects the content stream in VISUAL order,
            // as every PDF producer writes it: left to right along the line, each right-to-left run reversed. It then runs the
            // bidi algorithm and returns logical text. So each line is written left to right, and an Arabic word's base letters
            // are written in visual order (unshaped: invisible text is never seen, only extracted, and base letters map 1:1
            // through the font's ToUnicode). The old run (shaped presentation forms + /ActualText, which PDFium ignores, words
            // in reading order) came out reversed on copy. Words of a line share its baseline ([lineBaseline]) so PDFium keeps them together.
            for (line in visualLines(page.words)) {
                val median = line.map { it.box.bottom }.sorted()[line.size / 2]
                val lineH = line.map { it.box.height() }.sorted()[line.size / 2]
                for (w in line) {
                    val ar = isArabic(w.text)
                    val font = if (ar) arabic else latin
                    val text = encodable(font, if (ar) visualWord(invisibleArabic(w.text)) else w.text)
                    if (text.isBlank()) continue
                    val fontSize = (w.box.height() * s).coerceIn(4f, 72f)
                    // word origin = left end of its box on the line baseline, display space (py measured from the top)
                    val px = w.box.left.toFloat(); val py = lineBaseline(w, median, lineH).toFloat()
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
                        // horizontal scaling stretches the run over the word box, so selection rectangles cover the word
                        val natural = runCatching { font.getStringWidth(text) / 1000f * fontSize }.getOrDefault(0f)
                        val target = w.box.width() * s
                        val hs = if (natural > 0.5f) (target / natural * 100f).coerceIn(10f, 1000f) else 100f
                        cs.setHorizontalScaling(hs)
                        cs.setTextMatrix(Matrix.getRotateInstance(angle, ux, uy))
                        cs.showText(text)
                    }
                }
            }
            cs.endText()
        }
        // the words stay in the file for a later review of OCR suspects (OcrStore); a second layer on the page adds to them
        // (audit 5 OCR-3 / OCR-4: words recognised again replace the old ones; another render size is rescaled, not dropped)
        runCatching { OcrStore.put(doc, pdPage, OcrStore.merge(OcrStore.get(pdPage), page)) }
    }

    suspend fun save(out: File) = withContext(Dispatchers.IO) { doc.save(out) }

    override fun close() { if (ownsDoc) runCatching { doc.close() } }
}
