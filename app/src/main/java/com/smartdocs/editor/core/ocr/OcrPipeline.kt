package com.smartdocs.editor.core.ocr

import android.content.Context
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.tools5.OptimizeOptions
import com.smartdocs.editor.core.tools5.Optimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The steps [OcrTuning] adds around a recognition run (used by the reader OCR, OCR plus and the batch). */
object OcrPipeline {

    /** [engine] when its quality matches [t], otherwise a new engine of the right quality (the caller closes it). */
    fun engineFor(ctx: Context, engine: OcrEngine?, t: OcrTuning): OcrEngine {
        val e = if (engine != null && engine.fast == t.fast) engine else OcrEngine(ctx.applicationContext, t.fast)
        e.setCharWhitelist(if (t.numericOnly) OcrTuning.NUMERIC_CHARS else null)
        return e
    }

    /**
     * When [t] straightens or turns pages: a corrected copy of [src] in [tempDir] (null when nothing needed a change).
     * [engine] must be initialised with the languages of the run (orientation is judged by recognition).
     */
    suspend fun correctedCopy(ctx: Context, src: File, password: String?, pages: List<Int>, t: OcrTuning, engine: OcrEngine, tempDir: File,
                              onPage: (Int, Int) -> Unit = { _, _ -> }): File? {
        if (!t.changesPages) return null
        val out = File(tempDir, "ocr_fix_${System.nanoTime()}.pdf")
        val s = PdfSession.open(ctx, src, password)
        val n = try {
            OcrPrep.correctPages(src, password, out, pages.filter { it in 0 until s.pageCount }, t,
                render = { pi ->
                    runCatching {
                        val (w, h) = s.pageSize(pi)
                        val k = 1100f / maxOf(w, h).coerceAtLeast(1f)
                        s.render(pi, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), annotations = false)
                            .copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { s.invalidateCache() }
                    }.getOrNull()
                },
                score = { b -> engine.score(b) }, onPage = onPage)
        } finally { s.closeAsync() }
        if (n == 0) { out.delete(); return null }
        return out
    }

    /** Nitro OCR output options: images downsampled to [OcrTuning.outputDpi] at [OcrTuning.outputQuality] (in place). */
    suspend fun shrinkOutput(out: File, password: String?, t: OcrTuning) = withContext(Dispatchers.IO) {
        if (t.outputDpi <= 0 || !out.exists()) return@withContext
        val tmp = File(out.absoluteFile.parentFile, ".${out.nameWithoutExtension}.${System.nanoTime()}.shrink.pdf")
        try {
            Optimizer.optimize(out, password, tmp, OptimizeOptions(imageDpi = t.outputDpi, jpegQuality = t.outputQuality.coerceIn(30, 95),
                stripMetadata = false, stripJavaScript = false, stripThumbnails = false, stripPieceInfo = false, onlyIfSmaller = false, keepProtection = true))
            if (tmp.length() in 1 until out.length()) tmp.copyTo(out, overwrite = true)
        } finally { tmp.delete() }
    }
}
