package com.smartdocs.editor.core.scan2word

import android.graphics.Bitmap
import com.smartdocs.editor.core.convert.DocxBuilder
import com.smartdocs.editor.core.convert.LayoutAnalyzer
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
                val ocr = engine.recognize(bmp, wantWords = true)
                if (ocr.text.isNotBlank()) { if (text.isNotEmpty()) text.append("\n\n"); text.append(ocr.text) }
                // OCR boxes are in the recognised picture's pixels; it may have been scaled for recognition
                val kx = w.toFloat() / ocr.imageWidth.coerceAtLeast(1); val ky = h.toFloat() / ocr.imageHeight.coerceAtLeast(1)
                val boxes = ocr.words.map { wd -> ScanToWord.OcrBox(wd.text, (wd.box.left * kx).toInt(), (wd.box.top * ky).toInt(), (wd.box.right * kx).toInt(), (wd.box.bottom * ky).toInt(), wd.confidence, wd.line) }
                val content = ScanToWord.page(n, px, w, h, boxes, arabicFont, latinFont) { f ->
                    runCatching {
                        val c = Bitmap.createBitmap(bmp, f[0], f[1], (f[2] - f[0]).coerceAtLeast(1), (f[3] - f[1]).coerceAtLeast(1))
                        val bos = ByteArrayOutputStream(); c.compress(Bitmap.CompressFormat.JPEG, 88, bos); if (c !== bmp) c.recycle()
                        bos.toByteArray() to "jpeg"
                    }.getOrNull()
                }
                layouts += LayoutAnalyzer.analyze(content, detectTables = true)
            } finally { bmp.recycle() }
            n++; onPage(n)
        }
        withContext(Dispatchers.IO) { out.outputStream().use { os -> DocxBuilder(os, title).use { b -> layouts.forEach { b.addPage(it) }; b.finish() } } }
        Result(n, text.toString())
    }
}
