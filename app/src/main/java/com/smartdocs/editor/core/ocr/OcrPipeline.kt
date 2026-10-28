package com.smartdocs.editor.core.ocr

import android.content.Context
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.tools5.OptimizeOptions
import com.smartdocs.editor.core.tools5.Optimizer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The steps [OcrTuning] adds around a recognition run (used by the reader OCR, OCR plus and the batch). */
object OcrPipeline {

    /**
     * audit 5 OCR-4: largest side (px) of a page rendered for recognition — one cap for the page OCR and the zone OCR,
     * so their stored words share the image size.
     */
    const val MAX_RENDER_PX = 3500f

    /** Render scale (px per point) of a page [w]×[h] points at [dpi], capped at [MAX_RENDER_PX]. */
    fun renderScale(w: Float, h: Float, dpi: Int = 300): Float {
        var k = dpi / 72f
        if (maxOf(w, h) * k > MAX_RENDER_PX) k = MAX_RENDER_PX / maxOf(w, h)
        return k
    }

    /**
     * audit 5 OCR-5: runs [block]; when the calling coroutine is cancelled meanwhile, [engine] is asked to stop at once
     * ([OcrEngine.cancel]) — a recognition already running is native code that a coroutine cancel alone does not reach
     * (the "fast" engine a run creates for itself, or the engine of OCR plus, kept recognising for 10–30 s).
     */
    suspend fun <T> cancelling(engine: OcrEngine, block: suspend () -> T): T = coroutineScope {
        var finished = false
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished) engine.cancel() }
        }
        try { block() } finally { finished = true; watcher.cancel() }
    }

    /** Closes an engine off the caller's thread (close waits for a running recognition) and even when cancelled. */
    suspend fun closeQuietly(engine: OcrEngine) = withContext(NonCancellable + Dispatchers.IO) { runCatching { engine.close() } }

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
                    try {
                        val (w, h) = s.pageSize(pi)
                        val k = 1100f / maxOf(w, h).coerceAtLeast(1f)
                        val b = s.render(pi, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), annotations = false)
                        // audit 5 OCR-2: the bitmap copy is done off the main thread
                        withContext(Dispatchers.Default) { b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.also { s.invalidateCache() }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                },
                score = { b -> engine.score(b) }, onPage = onPage)
        } catch (e: Throwable) { out.delete(); throw e
        } finally { withContext(NonCancellable) { s.closeAsync() } }
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
