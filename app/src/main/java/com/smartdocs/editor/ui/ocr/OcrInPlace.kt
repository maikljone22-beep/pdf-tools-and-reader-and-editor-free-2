package com.smartdocs.editor.ui.ocr

import android.content.Context
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ocr.EditableTextPdfWriter
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.ocr.SearchablePdfWriter
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.quick.QuickInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * What the OCR sheet asks for. [pages] null = all pages (zero-based otherwise). [tuning]: Nitro OCR options. [redo]:
 * recognise every chosen page again, even with text, after removing the old invisible OCR text on them
 * ([com.smartdocs.editor.core.ocr.OcrRedo]; visible content is kept).
 */
data class OcrOptions(val pages: List<Int>?, val langs: List<String>, val editable: Boolean,
                      val tuning: com.smartdocs.editor.core.ocr.OcrTuning = com.smartdocs.editor.core.ocr.OcrTuning(),
                      val redo: Boolean = false)

/** Languages offered in the sheet (Tesseract codes) with their label resource. */
val OCR_LANGUAGES = listOf(
    "ara" to R.string.qk_lang_ara, "eng" to R.string.qk_lang_eng, "fra" to R.string.qk_lang_fra, "deu" to R.string.qk_lang_deu,
    "spa" to R.string.qk_lang_spa, "tur" to R.string.qk_lang_tur, "fas" to R.string.qk_lang_fas, "urd" to R.string.qk_lang_urd,
)

/** Last choices of the OCR sheet, remembered in SharedPreferences "ocr". */
object OcrPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("ocr", Context.MODE_PRIVATE)
    fun languages(ctx: Context): List<String> =
        (p(ctx).getString("languages", null) ?: "ara+eng").split('+').filter { it.isNotBlank() }.ifEmpty { listOf("ara", "eng") }
    fun editable(ctx: Context): Boolean = p(ctx).getBoolean("editable", false)
    fun save(ctx: Context, langs: List<String>, editable: Boolean) {
        p(ctx).edit().putString("languages", langs.joinToString("+")).putBoolean("editable", editable).apply()
    }
}

/**
 * Recognise the scanned pages of [src] and write the whole document to [out] (searchable layer or editable
 * text). Pages that are pure digital text are skipped; mixed pages get only the words that are not selectable yet. Returns the number of pages recognised; throws [QuickInfo]
 * when no page in the range needs OCR. [onProgress] gets (message, fraction or null).
 */
suspend fun runOcrToFile(
    ctx: Context, engine0: OcrEngine, src00: File, out: File, password: String?, opt: OcrOptions,
    onRepaired: (com.smartdocs.editor.core.pdf.ToUnicodeRepair.Result) -> Unit = {},
    onProgress: (String, Float?) -> Unit,
): Int {
    val t = opt.tuning
    val engine = com.smartdocs.editor.core.ocr.OcrPipeline.engineFor(ctx, engine0, t)
    var fixed: File? = null
    try {
    // audit 5 OCR-5: a cancel of the caller's coroutine (QuickController job.cancel) stops the engine actually running,
    // also the "fast" engine created here
    // audit 5 OCR-8: any other failure reaches the caller as a string resource (QuickInfo), not a raw English message
    return try { com.smartdocs.editor.core.ocr.OcrPipeline.cancelling(engine) {
    val langs = opt.langs.joinToString("+")
    try {
        engine.ensureLanguages(langs) { lang, f ->
            onProgress(ctx.getString(R.string.qk_ocr_downloading, OcrEngine.label(lang), (f * 100).toInt()), f)
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { throw QuickInfo(ctx.getString(R.string.qk_ocr_download_failed)) }
    onProgress(ctx.getString(R.string.qk_working), null)
    if (!engine.init(langs)) throw QuickInfo(ctx.getString(R.string.qk_ocr_init_failed))
    // Nitro pre-processing that changes the pages (de-skew, text orientation): done on a copy before anything else
    fixed = if (t.changesPages) {
        val n = com.smartdocs.editor.core.pdf.PdfOps.pageCount(src00, password)
        com.smartdocs.editor.core.ocr.OcrPipeline.correctedCopy(ctx, src00, password, opt.pages ?: (0 until n).toList(), t, engine, ctx.cacheDir) { a, b ->
            onProgress(ctx.getString(R.string.nt_ocr_correcting, a + 1, b), a.toFloat() / b.coerceAtLeast(1))
        }
    } else null
    val src0 = fixed ?: src00

    // Arabic fonts with a wrong ToUnicode map are repaired first: those pages then have good text and need no OCR layer
    // (which used to duplicate them, reversed, on copy). Everything below works on the repaired copy.
    val input = com.smartdocs.editor.core.ocr.RepairedInput.of(ctx.cacheDir, src0, password, opt.pages)
    var stripped: File? = null
    try {
    input.result?.takeIf { input.repaired }?.let(onRepaired)
    // redo: the old invisible OCR text of the chosen pages goes first (a copy; visible content untouched)
    if (opt.redo) stripped = com.smartdocs.editor.core.ocr.OcrRedo.strippedCopy(input.file, password, opt.pages, ctx.cacheDir).first
    val src = stripped ?: input.file
    val session = PdfSession.open(ctx, src, password)
    try {
        val range = (opt.pages ?: (0 until session.pageCount).toList()).filter { it in 0 until session.pageCount }
        // pages without text, and pages mixing text with scanned pictures (only the new words are added there)
        val plan = com.smartdocs.editor.core.ocr.MixedPageOcr.plan(session, src, password, range)
        // a single page the user picked is always recognised (on a digital page only words that are new get added)
        val todo = if (opt.redo || opt.pages?.size == 1) range else plan.pages
        if (todo.isEmpty()) {
            // nothing to recognise, but the text layer was repaired: that is the result
            if (input.repaired) { withContext(Dispatchers.IO) { src.copyTo(out, overwrite = true) }; return@cancelling 0 }
            throw QuickInfo(ctx.getString(R.string.qk_ocr_nothing))
        }
        val searchable = if (!opt.editable) withContext(Dispatchers.IO) { SearchablePdfWriter(ctx, src, password) } else null
        val editable = if (opt.editable) withContext(Dispatchers.IO) { EditableTextPdfWriter(ctx, src, password) } else null
        try {
            todo.forEachIndexed { n, pi ->
                coroutineContext.ensureActive()
                onProgress(ctx.getString(R.string.qk_ocr_page, n + 1, todo.size), n.toFloat() / todo.size)
                val (w, h) = session.pageSize(pi)
                // audit 5 OCR-4: the same render cap as the zone OCR (OcrPipeline.MAX_RENDER_PX)
                val scale = com.smartdocs.editor.core.ocr.OcrPipeline.renderScale(w, h)
                val bmp = session.render(pi, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), annotations = false)
                session.invalidateCache()            // the bitmap is ours now (the editable writer paints on it)
                // audit 5 OCR-8: recycled also when the run is cancelled or fails on this page
                try {
                    // audit 5 OCR-2: image filters (adaptive threshold…) on Dispatchers.Default, not on the caller's Main
                    val ocrBmp = com.smartdocs.editor.core.ocr.OcrPrep.filterAsync(bmp, t)
                    val page = try { engine.recognize(ocrBmp, wantWords = true) } finally { if (ocrBmp !== bmp) ocrBmp.recycle() }
                    coroutineContext.ensureActive()
                    searchable?.addLayer(pi, com.smartdocs.editor.core.ocr.MixedPageOcr.dropExisting(session, pi, page, plan))
                    // only a page with NO text objects (a scan / picture) is rebuilt. A page that has text — even a text layer
                    // that extracts as garbage or wrongly mapped Arabic — draws real glyphs: rebuilding it would replace them
                    // by a picture plus re-typed OCR words (lost style, duplicated / misplaced words). It gets invisible words.
                    if (editable != null) {
                        val txt = runCatching { session.text(pi) }.getOrDefault("")
                        if (txt.isNotBlank())
                            editable.addInvisible(pi, com.smartdocs.editor.core.ocr.MixedPageOcr.dropExisting(session, pi, page, plan))
                        else editable.replacePage(pi, bmp, page)
                    }
                } finally { bmp.recycle() }
            }
            onProgress(ctx.getString(R.string.qk_ocr_saving), 1f)
            searchable?.save(out); editable?.save(out)
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { searchable?.close(); editable?.close() } }
        com.smartdocs.editor.core.ocr.OcrPipeline.shrinkOutput(out, password, t)
        todo.size
    } finally { withContext(kotlinx.coroutines.NonCancellable) { session.closeAsync() } }   // closed also after a cancel
    } finally { input.close(); stripped?.delete() }
    } } catch (e: CancellationException) { throw e
    } catch (e: QuickInfo) { throw e
    } catch (e: Exception) { throw QuickInfo(ocrErrorText(ctx, e))
    } catch (e: OutOfMemoryError) { throw QuickInfo(ocrErrorText(ctx, e)) }
    } finally {
        fixed?.delete()
        if (engine !== engine0) com.smartdocs.editor.core.ocr.OcrPipeline.closeQuietly(engine)
    }
}
