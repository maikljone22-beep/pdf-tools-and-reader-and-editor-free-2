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

/** What the OCR sheet asks for. [pages] null = all pages (zero-based otherwise). */
data class OcrOptions(val pages: List<Int>?, val langs: List<String>, val editable: Boolean)

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
 * text). Pages that already have text are skipped. Returns the number of pages recognised; throws [QuickInfo]
 * when no page in the range needs OCR. [onProgress] gets (message, fraction or null).
 */
suspend fun runOcrToFile(
    ctx: Context, engine: OcrEngine, src: File, out: File, password: String?, opt: OcrOptions,
    onProgress: (String, Float?) -> Unit,
): Int {
    val langs = opt.langs.joinToString("+")
    try {
        engine.ensureLanguages(langs) { lang, f ->
            onProgress(ctx.getString(R.string.qk_ocr_downloading, OcrEngine.label(lang), (f * 100).toInt()), f)
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { throw QuickInfo(ctx.getString(R.string.qk_ocr_download_failed)) }
    onProgress(ctx.getString(R.string.qk_working), null)
    if (!engine.init(langs)) throw QuickInfo(ctx.getString(R.string.qk_ocr_init_failed))

    val session = PdfSession.open(ctx, src, password)
    try {
        val range = (opt.pages ?: (0 until session.pageCount).toList()).filter { it in 0 until session.pageCount }
        // only pages without a text layer: OCR on a page that already has text would duplicate it
        val todo = range.filter { runCatching { session.text(it) }.getOrDefault("").isBlank() }
        if (todo.isEmpty()) throw QuickInfo(ctx.getString(R.string.qk_ocr_nothing))
        val searchable = if (!opt.editable) withContext(Dispatchers.IO) { SearchablePdfWriter(ctx, src, password) } else null
        val editable = if (opt.editable) withContext(Dispatchers.IO) { EditableTextPdfWriter(ctx, src, password) } else null
        try {
            todo.forEachIndexed { n, pi ->
                coroutineContext.ensureActive()
                onProgress(ctx.getString(R.string.qk_ocr_page, n + 1, todo.size), n.toFloat() / todo.size)
                val (w, h) = session.pageSize(pi)
                var scale = 300f / 72f
                if (maxOf(w, h) * scale > 3500f) scale = 3500f / maxOf(w, h)
                val bmp = session.render(pi, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), annotations = false)
                session.invalidateCache()            // the bitmap is ours now (the editable writer paints on it)
                val page = engine.recognize(bmp, wantWords = true)
                coroutineContext.ensureActive()
                searchable?.addLayer(pi, page)
                editable?.replacePage(pi, bmp, page)
                bmp.recycle()
            }
            onProgress(ctx.getString(R.string.qk_ocr_saving), 1f)
            searchable?.save(out); editable?.save(out)
        } finally { searchable?.close(); editable?.close() }
        return todo.size
    } finally { session.closeAsync() }
}
