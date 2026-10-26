package com.smartdocs.editor.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** One recognised word: text, box in image pixels, confidence 0..100. */
data class OcrWord(val text: String, val box: Rect, val confidence: Float)

/** Result of one page. */
data class OcrPage(val text: String, val words: List<OcrWord>, val imageWidth: Int, val imageHeight: Int)

/**
 * Tesseract wrapper. Language packs (`tessdata_best`) are downloaded on first use into
 * `filesDir/tessdata/<lang>.traineddata`.
 */
class OcrEngine(private val context: Context) : AutoCloseable {

    private var api: TessBaseAPI? = null
    private var initialisedLangs: String? = null
    @Volatile private var cancelled = false

    // high-accuracy LSTM models (tessdata_best): much better Arabic than tessdata_fast; kept apart from old fast packs
    val tessdataDir: File get() = File(context.filesDir, "ocr_best/tessdata").apply { mkdirs() }

    fun isLanguageInstalled(lang: String): Boolean = File(tessdataDir, "$lang.traineddata").let { it.exists() && it.length() > 100_000 }

    /** Make sure every language in [langs] ("ara+eng") is downloaded. [onProgress] gets (lang, fraction 0..1). */
    suspend fun ensureLanguages(langs: String, onProgress: (String, Float) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        langs.split('+').map { it.trim() }.filter { it.isNotEmpty() }.forEach { lang ->
            if (!isLanguageInstalled(lang)) download(lang, onProgress)
        }
    }

    private suspend fun download(lang: String, onProgress: (String, Float) -> Unit) {
        val target = File(tessdataDir, "$lang.traineddata")
        val tmp = File(tessdataDir, "$lang.traineddata.part")
        val url = "https://github.com/tesseract-ocr/tessdata_best/raw/main/$lang.traineddata"
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).followRedirects(true).build()
        val ctx = coroutineContext
        onProgress(lang, 0f)
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $lang")
            val body = resp.body ?: throw IllegalStateException("empty body")
            val total = body.contentLength()
            var read = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); read += n
                        if (total > 0) onProgress(lang, (read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (tmp.length() < 100_000) { tmp.delete(); throw IllegalStateException("download incomplete for $lang") }
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        onProgress(lang, 1f)
    }

    /** Initialise Tesseract for [langs]; safe to call repeatedly. Returns false when init failed. */
    suspend fun init(langs: String): Boolean = withContext(Dispatchers.Default) {
        synchronized(this@OcrEngine) {
            if (api != null && initialisedLangs == langs) return@withContext true
            closeLocked()
            // Tesseract wants the PARENT of the "tessdata" folder: filesDir/tessdata/<lang>.traineddata
            val parent = tessdataDir.parentFile ?: context.filesDir
            val t = TessBaseAPI()
            val ok = runCatching { t.init(parent.absolutePath, langs, TessBaseAPI.OEM_LSTM_ONLY) }.getOrDefault(false)
            if (!ok) { runCatching { t.recycle() }; return@withContext false }
            t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api = t; initialisedLangs = langs
            true
        }
    }

    /** Recognise one bitmap. Must be called after [init]. Runs on Dispatchers.Default. */
    suspend fun recognize(bitmap: Bitmap, wantWords: Boolean = true): OcrPage = withContext(Dispatchers.Default) {
        cancelled = false
        synchronized(this@OcrEngine) {
            // read the handle under the lock: close() may have recycled it meanwhile
            val t = api ?: throw IllegalStateException("engine not initialised")
            t.setImage(bitmap)
            val text = t.utF8Text ?: ""
            val words = ArrayList<OcrWord>()
            if (wantWords && !cancelled) {
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                val it = t.resultIterator
                if (it != null) {
                    try {
                        it.begin()
                        do {
                            val w = it.getUTF8Text(level)
                            if (!w.isNullOrBlank()) {
                                val r = it.getBoundingRect(level)
                                words += OcrWord(w.trim(), Rect(r), it.confidence(level))
                            }
                        } while (it.next(level))
                    } catch (_: Exception) { } finally { runCatching { it.delete() } }
                }
            }
            runCatching { t.clear() }
            OcrPage(text, words, bitmap.width, bitmap.height)
        }
    }

    /** Ask Tesseract to abort the current recognition. */
    fun cancel() { cancelled = true; runCatching { api?.stop() } }

    /** Thread-safe: waits for an in-flight [recognize] (which holds the same monitor) before freeing native memory. */
    override fun close() { synchronized(this) { closeLocked() } }

    private fun closeLocked() {
        runCatching { api?.recycle() }
        api = null; initialisedLangs = null
    }

    companion object {
        /** Human label for a language code. */
        fun label(lang: String): String = when (lang) {
            "ara" -> "العربية"; "eng" -> "English"; "fra" -> "Français"; "deu" -> "Deutsch"; "spa" -> "Español"
            "tur" -> "Türkçe"; "fas" -> "فارسی"; "urd" -> "اردو"; else -> lang
        }
    }
}
