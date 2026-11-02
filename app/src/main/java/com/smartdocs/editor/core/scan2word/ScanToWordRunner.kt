package com.smartdocs.editor.core.scan2word

import android.graphics.Bitmap
import com.smartdocs.editor.core.convert.DocxBuilder
import com.smartdocs.editor.core.convert.PageLayout
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.tools5.ScanOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext

/** Android side of [ScanToWord]: recognise each page picture and write one .docx (pages in order). */
object ScanToWordRunner {

    class Result(val pages: Int, val text: String)

    /** [engine] already initialised with the page languages. Each picture is recycled after use. */
    suspend fun convert(pictures: Sequence<ScanOut>, engine: OcrEngine, out: File, title: String, arabicFont: String, latinFont: String,
                        onPage: (Int) -> Unit = {}): Result = withContext(Dispatchers.Default) {
        val layouts = ArrayList<PageLayout>(); val text = StringBuilder(); var n = 0
        for (o in pictures) {
            coroutineContext.ensureActive()
            val bmp = o.bitmap
            try {
                val w = bmp.width; val h = bmp.height
                val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
                // rules found once: long ones painted out for OCR, all of them used for the regions and the Word tables
                val rules = ScanToWord.rules(px, w, h)
                val clean = ScanToWord.forOcr(px, w, h, ScanToWord.longRules(rules, w, h))
                // each band / table cell recognised alone, so lines of neighbouring cells never mix
                val boxes = ArrayList<ScanToWord.OcrBox>()
                val regions = ScanToWord.ocrRegions(rules, w, h)
                regions.forEachIndexed { ri, r ->
                    val rw = r[2] - r[0]; val rh = r[3] - r[1]
                    if (rw < 8 || rh < 8) return@forEachIndexed
                    val ob = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                    ob.setPixels(clean, r[1] * w + r[0], w, 0, 0, rw, rh)
                    val ocr = try { engine.recognize(ob, wantWords = true, singleBlock = true) } finally { ob.recycle() }
                    if (ocr.text.isNotBlank()) { if (text.isNotEmpty()) text.append("\n"); text.append(ocr.text.trim()) }
                    // OCR boxes are in the recognised picture's pixels; it may have been scaled for recognition
                    val kx = rw.toFloat() / ocr.imageWidth.coerceAtLeast(1); val ky = rh.toFloat() / ocr.imageHeight.coerceAtLeast(1)
                    ocr.words.forEach { wd ->
                        boxes += ScanToWord.OcrBox(wd.text, r[0] + (wd.box.left * kx).toInt(), r[1] + (wd.box.top * ky).toInt(),
                            r[0] + (wd.box.right * kx).toInt(), r[1] + (wd.box.bottom * ky).toInt(), wd.confidence, ri * 100000 + wd.line)
                    }
                }
                // second reading: printed lines the region reading skipped (a title, a short line), one line at a time
                ScanToWord.missedLines(clean, w, h, regions, boxes).forEachIndexed { mi, band ->
                    coroutineContext.ensureActive()
                    val (pp, pw, ph) = ScanToWord.padded(clean, w, band)
                    val ob = Bitmap.createBitmap(pp, pw, ph, Bitmap.Config.ARGB_8888)
                    val ocr = try { engine.recognize(ob, wantWords = true, singleLine = true) } finally { ob.recycle() }
                    val kx = pw.toFloat() / ocr.imageWidth.coerceAtLeast(1); val ky = ph.toFloat() / ocr.imageHeight.coerceAtLeast(1)
                    ocr.words.forEach { wd ->
                        val ox = band[0] - 20; val oy = band[1] - 20
                        boxes += ScanToWord.OcrBox(wd.text, ox + (wd.box.left * kx).toInt(), oy + (wd.box.top * ky).toInt(),
                            ox + (wd.box.right * kx).toInt(), oy + (wd.box.bottom * ky).toInt(), wd.confidence, 90_000_000 + mi * 100 + wd.line.coerceAtLeast(0))
                    }
                }
                layouts += ScanToWord.layout(n, px, w, h, boxes, arabicFont, latinFont, rules) { f ->
                    runCatching {
                        val c = Bitmap.createBitmap(bmp, f[0], f[1], (f[2] - f[0]).coerceAtLeast(1), (f[3] - f[1]).coerceAtLeast(1))
                        val bos = ByteArrayOutputStream(); c.compress(Bitmap.CompressFormat.JPEG, 88, bos); if (c !== bmp) c.recycle()
                        bos.toByteArray() to "jpeg"
                    }.getOrNull()
                }
            } finally { bmp.recycle() }
            n++; onPage(n)
        }
        withContext(Dispatchers.IO) { out.outputStream().use { os -> DocxBuilder(os, title, tight = true).use { b -> layouts.forEach { b.addPage(it) }; b.finish() } } }
        Result(n, text.toString())
    }
}
