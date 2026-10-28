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

/** One recognised word: text, box in image pixels, confidence 0..100, [line] index of its text line (-1 unknown). */
data class OcrWord(val text: String, val box: Rect, val confidence: Float, val line: Int = -1)

/** Result of one page. */
data class OcrPage(val text: String, val words: List<OcrWord>, val imageWidth: Int, val imageHeight: Int)

/**
 * audit 5 OCR-8: an OCR failure with a [kind] the UI turns into a string resource (no raw English text such as
 * "HTTP 404" or "ocr init" in the Arabic UI). Still an [IllegalStateException] for callers that catch that.
 */
class OcrException(val kind: Kind, val lang: String? = null, cause: Throwable? = null) :
    IllegalStateException(kind.name + (lang?.let { " $it" } ?: ""), cause) {
    enum class Kind { DOWNLOAD, VERIFY, NO_PACK, INIT, IMAGE }
}

/**
 * Tesseract wrapper. Language packs (`tessdata_best`, pinned commit, SHA-256 verified) are downloaded on first use into
 * `filesDir/ocr_best/tessdata/<lang>.traineddata`. Nitro additions: every language of tessdata_best / tessdata_fast
 * ([OcrModels], pinned by size + git blob id), recognition quality [fast] (tessdata_fast in `ocr_fast/tessdata`),
 * a numbers-only character list and a quick [score] for orientation detection.
 */
class OcrEngine(private val context: Context, val fast: Boolean = false) : AutoCloseable {

    private var api: TessBaseAPI? = null
    private var initialisedLangs: String? = null
    // Arabic script model for the second reading of doubtful words in Arabic lines (OcrRefine); null when not installed
    private var helper: TessBaseAPI? = null
    private var helperTried = false
    // the main engine runs the fine-tuned Arabic model: the second readings (made for ara+eng) are skipped, as measured
    private var usingFt = false
    @Volatile private var cancelled = false
    private var whitelist: String? = null

    /**
     * Recogniser choice (PRO_OCR_r5), from the OCR settings unless the caller sets it. [OcrEngineMode.AUTO] adds the
     * PP-OCRv5 AI model to Tesseract word by word ([OcrEnsemble]) when its optional models are installed.
     */
    @Volatile var mode: OcrEngineMode = runCatching { OcrTuning.load(context).engine }.getOrDefault(OcrEngineMode.AUTO)
    private var pro: ProOcr? = null
    private var proTried = false
    private var lexicon: OcrLexicon? = null

    /** Extra known words for choosing between the two engines' readings (for example the document's own text layer). */
    fun addKnownText(text: String) { synchronized(this) { lexiconLocked().addText(text) } }

    private fun lexiconLocked(): OcrLexicon = lexicon ?: OcrLexicon.fromText(runCatching {
        context.assets.open(OcrLexicon.GLOSSARY_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("")).also { lexicon = it }

    /** True when the AI ("Pro offline") models are downloaded. */
    fun isProInstalled(): Boolean = ProOcr.isInstalled(context)

    /**
     * Nitro special recognition "numbers only": restrict the characters Tesseract may return (null = all). Applies to
     * the next [init] of other languages and to the engine already initialised.
     */
    fun setCharWhitelist(chars: String?) {
        synchronized(this) {
            whitelist = chars
            api?.let { runCatching { it.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, chars ?: "") } }
        }
    }

    /**
     * Recognition score of [bitmap] for orientation detection: mean word confidence × letters found (more readable
     * text scores higher). Must be called after [init].
     */
    suspend fun score(bitmap: Bitmap): Float = withContext(Dispatchers.Default) {
        synchronized(this@OcrEngine) {
            val t = api ?: return@withContext 0f
            t.setImage(bitmap)
            val text = t.utF8Text ?: ""
            val conf = t.meanConfidence().toFloat()
            runCatching { t.clear() }
            conf * text.count { it.isLetterOrDigit() }.coerceAtMost(2000) / 100f
        }
    }

    // high-accuracy LSTM models (tessdata_best): much better Arabic than tessdata_fast; kept apart from old fast packs
    val tessdataDir: File get() = File(context.filesDir, if (fast) "ocr_fast/tessdata" else "ocr_best/tessdata").apply { mkdirs() }

    /** The best-quality folder: the Arabic script helper always comes from tessdata_best. */
    private val bestDir: File get() = File(context.filesDir, "ocr_best/tessdata").apply { mkdirs() }

    private fun fileOf(lang: String, dir: File = tessdataDir) = File(dir, "$lang.traineddata").also { it.parentFile?.mkdirs() }

    /** Expected size of [lang] in this engine's quality, or null when the pack is unknown. */
    private fun expectedSize(lang: String, fastPack: Boolean = fast): Long? =
        if (!fastPack) PINNED[lang]?.size ?: OcrModels.BEST[lang]?.size else OcrModels.FAST[lang]?.size

    fun isLanguageInstalled(lang: String): Boolean = isInstalledIn(lang, if (lang in BEST_ONLY) bestDir else tessdataDir, lang !in BEST_ONLY && fast)

    private fun isInstalledIn(lang: String, dir: File, fastPack: Boolean): Boolean = fileOf(lang, dir).let { f ->
        // a pinned pack must have its exact size (a truncated or swapped file is downloaded again)
        f.exists() && f.length() > 10_000 && (expectedSize(lang, fastPack)?.let { f.length() == it } ?: true)
    }

    /** True when [lang] can be downloaded (it is pinned for this quality). */
    fun isAvailable(lang: String): Boolean = expectedSize(lang) != null

    /** Make sure every language in [langs] ("ara+eng") is downloaded. [onProgress] gets (lang, fraction 0..1). */
    suspend fun ensureLanguages(langs: String, onProgress: (String, Float) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val list = langs.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        list.forEach { lang ->
            if (!isLanguageInstalled(lang)) download(lang, onProgress)
        }
        // optional: the Arabic script model reads Arabic-Indic digits (the ara model knows only ٠ and ١). OCR works
        // without it, so a failed download is not an error.
        if ("ara" in list && !isLanguageInstalled(ARABIC_SCRIPT)) {
            try { download(ARABIC_SCRIPT, onProgress) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { }
        }
        // optional: the script/Arabic model fine-tuned on the chemistry-book fonts (PRO_OCR_r5 §6), only while it is hosted
        if ("ara" in list && !fast && mode != OcrEngineMode.TESSERACT && FT_URL.isNotEmpty() && !isLanguageInstalled(ARABIC_FT)) {
            try { download(ARABIC_FT, onProgress) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { }
        }
        // optional too: the PP-OCRv5 AI models (12.8 MB) for the AUTO / AI recogniser choice
        if ("ara" in list && mode != OcrEngineMode.TESSERACT && !ProOcr.isInstalled(context)) {
            try { ProOcr.ensureModels(context) { f -> onProgress("AI", f) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }

    private suspend fun download(lang: String, onProgress: (String, Float) -> Unit) {
        // the Arabic script helper is a best model whatever the quality
        val fastPack = fast && lang !in BEST_ONLY
        val dir = if (lang in BEST_ONLY) bestDir else tessdataDir
        val target = fileOf(lang, dir)
        val tmp = File(target.parentFile, target.name + ".part")
        val sha = if (!fastPack) PINNED[lang] else null
        val git = when { sha != null -> null; fastPack -> OcrModels.FAST[lang]; else -> OcrModels.BEST[lang] }
        if (sha == null && git == null) throw OcrException(OcrException.Kind.NO_PACK, lang)
        val url = if (sha != null) modelUrl(lang) else OcrModels.url(fastPack, lang)
        val client = OkHttpClient.Builder().addInterceptor(com.smartdocs.editor.core.data.NpfNet.interceptor).connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).followRedirects(true).build()
        val ctx = coroutineContext
        onProgress(lang, 0f)
        // audit 5 OCR-8: a failed / cancelled download leaves no .part file behind
        try {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw OcrException(OcrException.Kind.DOWNLOAD, lang)
            val body = resp.body ?: throw OcrException(OcrException.Kind.DOWNLOAD, lang)
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
        if (tmp.length() < 10_000) throw OcrException(OcrException.Kind.DOWNLOAD, lang)
        // integrity: the file must be byte-for-byte the pinned one (size + SHA-256, or size + git blob id)
        val ok = if (sha != null) tmp.length() == sha.size && sha256(tmp).equals(sha.sha256, ignoreCase = true)
            else tmp.length() == git!!.size && OcrModels.gitBlobId(tmp).equals(git.blob, ignoreCase = true)
        if (!ok) throw OcrException(OcrException.Kind.VERIFY, lang)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } catch (e: OcrException) { tmp.delete(); throw e
        } catch (e: kotlinx.coroutines.CancellationException) { tmp.delete(); throw e
        } catch (e: java.io.IOException) { tmp.delete(); throw OcrException(OcrException.Kind.DOWNLOAD, lang, e)
        } catch (e: Throwable) { tmp.delete(); throw e }
        onProgress(lang, 1f)
    }

    /** Initialise Tesseract for [langs]; safe to call repeatedly. Returns false when init failed. */
    suspend fun init(langs: String): Boolean = withContext(Dispatchers.Default) {
        synchronized(this@OcrEngine) {
            if (api != null && initialisedLangs == langs) return@withContext true
            closeLocked()
            // Tesseract wants the PARENT of the "tessdata" folder: filesDir/tessdata/<lang>.traineddata
            val parent = tessdataDir.parentFile ?: context.filesDir
            val eff = effectiveLangs(langs, isLanguageInstalled(ARABIC_FT), mode, fast)
            val t = TessBaseAPI()
            var ok = runCatching { t.init(parent.absolutePath, eff, TessBaseAPI.OEM_LSTM_ONLY) }.getOrDefault(false)
            usingFt = ok && eff != langs
            if (!ok && eff != langs) ok = runCatching { t.init(parent.absolutePath, langs, TessBaseAPI.OEM_LSTM_ONLY) }.getOrDefault(false)
            if (!ok) { runCatching { t.recycle() }; return@withContext false }
            t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            whitelist?.let { runCatching { t.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, it) } }
            api = t; initialisedLangs = langs
            helperTried = false
            true
        }
    }

    /**
     * Recognise one bitmap. Must be called after [init]. Runs on Dispatchers.Default. With [wantWords], doubtful words of
     * Arabic lines get a second reading ([OcrRefine]) and decoration "words" are dropped ([OcrCleanup]).
     */
    suspend fun recognize(bitmap: Bitmap, wantWords: Boolean = true): OcrPage = withContext(Dispatchers.Default) {
        cancelled = false
        val m = mode
        // Pro OCR on the server first (user opt-in); any failure falls back to the offline engines below
        val cloud = if (m == OcrEngineMode.CLOUD && wantWords && CloudOcr.available()) runCatching { CloudOcr.recognize(bitmap, initialisedLangs) }.getOrNull() else null
        synchronized(this@OcrEngine) {
            if (!cloud.isNullOrEmpty()) {
                val c = OcrCleanup.clean(cloud)
                return@withContext OcrPage(OcrRefine.lines(c).joinToString("\n") { l -> l.joinToString(" ") { c[it].text } },
                    c.map { k -> OcrWord(k.text, Rect(k.left, k.top, k.right, k.bottom), k.conf, k.line) }, bitmap.width, bitmap.height)
            }
            // read the handle under the lock: close() may have recycled it meanwhile
            val t = api ?: throw OcrException(OcrException.Kind.INIT)
            val w0 = bitmap.width; val h0 = bitmap.height
            t.setImage(bitmap)
            var text = t.utF8Text ?: ""
            var words = ArrayList<OcrWord>()
            if (wantWords && !cancelled) {
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                val it = t.resultIterator
                if (it != null) {
                    try {
                        it.begin()
                        var line = -1
                        do {
                            if (line < 0 || it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) line++
                            val w = it.getUTF8Text(level)
                            if (!w.isNullOrBlank()) {
                                val r = it.getBoundingRect(level)
                                words += OcrWord(w.trim(), Rect(r), it.confidence(level), line)
                            }
                        } while (it.next(level))
                    } catch (_: Exception) { } finally { runCatching { it.delete() } }
                }
            }
            runCatching { t.clear() }
            if (words.isNotEmpty() && !cancelled) {
                val toks = words.map { OcrRefine.Tok(it.text, it.box.left, it.box.top, it.box.right, it.box.bottom, it.confidence, it.line) }
                var out = toks
                val hp = if (!usingFt && initialisedLangs?.split('+')?.contains("ara") == true) helperLocked() else null
                if (hp != null) {
                    runCatching {
                        hp.setImage(bitmap)
                        out = OcrRefine.refine(out) { l, tp, r, b ->
                            val x = l.coerceIn(0, w0 - 1); val y = tp.coerceIn(0, h0 - 1)
                            val rw = r.coerceAtMost(w0) - x; val rh = b.coerceAtMost(h0) - y
                            if (cancelled || rw < 4 || rh < 4) null
                            else {
                                hp.setRectangle(x, y, rw, rh)
                                val s = hp.utF8Text?.trim().orEmpty()
                                if (s.isEmpty()) null else s to hp.meanConfidence().toFloat()
                            }
                        }
                    }
                    runCatching { hp.clear() }
                }
                // the fine-tuned script model may answer with Persian digit / letter forms (۲, ی, ک): Arabic forms
                if (usingFt) out = out.map { k -> OcrRefine.foldScript(k.text).let { f -> if (f == k.text || f.isEmpty()) k else k.copy(text = f) } }
                out = OcrCleanup.clean(out)
                // the PP-OCRv5 AI model: alone (AI) or word by word with Tesseract (AUTO / CLOUD fallback)
                if (m != OcrEngineMode.TESSERACT && !cancelled && initialisedLangs?.split('+')?.contains("ara") == true) {
                    val p = proLocked()
                    if (p != null) {
                        val pw = runCatching { p.recognize(bitmap) }.getOrDefault(emptyList())
                        if (pw.isNotEmpty()) out = OcrCleanup.clean(if (m == OcrEngineMode.AI) pw else OcrEnsemble.combine(out, pw, lexiconLocked()))
                    }
                }
                if (out != toks) {
                    words = ArrayList(out.map { k -> OcrWord(k.text, Rect(k.left, k.top, k.right, k.bottom), k.conf, k.line) })
                    text = OcrRefine.lines(out).joinToString("\n") { l -> l.joinToString(" ") { out[it].text } }
                }
            }
            OcrPage(text, words, w0, h0)
        }
    }

    /** The Arabic script model, initialised once per [init] when its pack is installed; null otherwise. Holds the lock. */
    private fun helperLocked(): TessBaseAPI? {
        helper?.let { return it }
        if (helperTried) return null
        helperTried = true
        if (!isLanguageInstalled(ARABIC_SCRIPT)) return null
        val parent = tessdataDir.parentFile ?: context.filesDir
        val h = TessBaseAPI()
        // the helper lives in the best folder even when the main engine uses fast models
        val ok = runCatching { h.init((bestDir.parentFile ?: parent).absolutePath, ARABIC_SCRIPT, TessBaseAPI.OEM_LSTM_ONLY) }.getOrDefault(false)
        if (!ok) { runCatching { h.recycle() }; return null }
        h.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
        helper = h
        return h
    }

    /** The AI engine, opened once per engine when its models are installed; null otherwise. Holds the lock. */
    private fun proLocked(): ProOcr? {
        pro?.let { return it }
        if (proTried) return null
        proTried = true
        val p = ProOcr(context)
        if (!p.open()) { p.close(); return null }
        pro = p
        return p
    }

    /** Ask Tesseract to abort the current recognition. */
    fun cancel() { cancelled = true; runCatching { api?.stop() }; runCatching { pro?.cancel() } }

    /** Thread-safe: waits for an in-flight [recognize] (which holds the same monitor) before freeing native memory. */
    override fun close() { synchronized(this) { closeLocked() } }

    private fun closeLocked() {
        runCatching { api?.recycle() }
        runCatching { helper?.recycle() }
        runCatching { pro?.close() }
        api = null; initialisedLangs = null; helper = null; helperTried = false; pro = null; proTried = false; usingFt = false
    }

    companion object {
        /** Expected size + SHA-256 of a tessdata_best pack at [TESSDATA_COMMIT]. */
        data class Pin(val size: Long, val sha256: String)

        /**
         * tessdata_best pinned to a commit (not the mutable `main` branch). Hashes computed from the files at this
         * commit; their git blob ids matched the GitHub API listing (rootcause r5).
         */
        const val TESSDATA_COMMIT = "e12c65a915945e4c28e237a9b52bc4a8f39a0cec"

        /** tessdata_best/script/Arabic: Arabic script model with every Arabic-Indic digit (second readings, [OcrRefine]). */
        const val ARABIC_SCRIPT = "Arabic"

        /**
         * script/Arabic fine-tuned on synthetic lines in the books' fonts (PRO_OCR_r5 §6; mean bag char accuracy alone
         * 0.818 -> 0.854, in Auto with PP-OCR 0.866 -> 0.895; integer model, 10 MB). Hosted by the project as a release asset; until it is
         * uploaded the download fails quietly and Auto uses ara+eng.
         */
        const val ARABIC_FT = "ArabicFT"
        const val FT_URL = "https://raw.githubusercontent.com/maikljone22-beep/pdf-tools-and-reader-and-editor-free-2/cfb6e655066bcce5ea5b29c5cb5fd6d1103715bb/models/ocr/ArabicFT.traineddata"   // pinned commit; size + SHA-256 checked
        /** Models kept in the best folder whatever the recognition quality. */
        val BEST_ONLY = setOf(ARABIC_SCRIPT, ARABIC_FT)

        /** The languages Tesseract is started with: the fine-tuned model replaces «ara» (and «eng», which it reads too). */
        fun effectiveLangs(langs: String, ftInstalled: Boolean, mode: OcrEngineMode, fast: Boolean): String {
            val parts = langs.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (!ftInstalled || fast || mode == OcrEngineMode.TESSERACT || "ara" !in parts) return langs
            return parts.filter { it != "eng" }.map { if (it == "ara") ARABIC_FT else it }.distinct().joinToString("+")
        }

        fun modelUrl(lang: String) = if (lang == ARABIC_FT) FT_URL else if (lang == ARABIC_SCRIPT)
            "https://github.com/tesseract-ocr/tessdata_best/raw/$TESSDATA_COMMIT/script/$lang.traineddata"
        else "https://github.com/tesseract-ocr/tessdata_best/raw/$TESSDATA_COMMIT/$lang.traineddata"

        /** Every language the OCR sheet offers or [com.smartdocs.editor.core.tools5.LangDetect] can return. */
        val PINNED: Map<String, Pin> = mapOf(
            "ara" to Pin(12603724, "ab9d157d8e38ca00e7e39c7d5363a5239e053f5b0dbdb3167dde9d8124335896"),
            "eng" to Pin(15400601, "8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba"),
            "fra" to Pin(3972885, "907743d98915c91a3906dfbf6e48b97598346698fe53aaa797e1a064ffcac913"),
            "deu" to Pin(8628461, "8407331d6aa0229dc927685c01a7938fc5a641d1a9524f74838cdac599f0d06e"),
            "spa" to Pin(13570187, "e2c1ffdad8b30f26c45d4017a9183d3a7f9aa69e59918be4f88b126fac99ab2c"),
            "tur" to Pin(7456265, "e0c3338dc17503dc7d335a507c9ae01b2b46cfd07561171e1e1ac55d85e8e438"),
            "fas" to Pin(3325955, "99e420969b5ddd2cb135b416316a7ed417c59c4faf9e0d28941348f6448114df"),
            "urd" to Pin(7994323, "65af3f6e50a3c3df15d7b405aab5d467e53433207745c8f5be5b8ff1a58a67c8"),
            "ita" to Pin(8863635, "8df9c89176fb93f56bf4b2d4ede04c01c1f31d4b7697fbd76cc336df700f3f38"),
            "por" to Pin(8159939, "711de9dbb8052067bd42f16b9119967f30bada80d57e2ef24f65d09f531adb04"),
            "rus" to Pin(15301764, "b617eb6830ffabaaa795dd87ea7fd251adfe9cf0efe05eb9a2e8128b7728d6b6"),
            "ell" to Pin(8945021, "288b4ea00bab450cf39893d22f04a2835a8469b673b5b20dcb39b159d1bbc9b8"),
            "heb" to Pin(3704077, "dbaa827aea6bc21215638447f17783a1004987c2d0bf5573d111fee397abdae5"),
            "hin" to Pin(11895564, "bd2e65a2184af08a167b0be2439e91fa5edbc4394399ca2f692b843ae26e78d6"),
            "chi_sim" to Pin(13077423, "4fef2d1306c8e87616d4d3e4c6c67faf5d44be3342290cf8f2f0f6e3aa7e735b"),
            // script/Arabic.traineddata at the same commit (git blob 54a6fd7e612bf839169eca536c7680bd5069331a, checked with
            // git ls-tree), fetched with Arabic as a helper model (OCR_QUALITY_r5)
            ARABIC_SCRIPT to Pin(17095279, "a80454f72df35c6f52550551dd69b8521ea6a62d834396d1c7dce4c35493902e"),
            // the fine-tuned model of PRO_OCR_r5 §6 (scratchpad train/ArabicFT.traineddata)
            ARABIC_FT to Pin(10021428, "3620be6eba8cbee6049aa19125ae3e863a7c8639b0627820c05d32ccdc1ff72e"),
        )

        /** Lower-case hex SHA-256 of [f]. */
        fun sha256(f: File): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { b -> String.format(java.util.Locale.ROOT, "%02x", b) }
        }

        /** Human label for a language code. */
        fun label(lang: String): String = when (lang) {
            "ara" -> "العربية"; "eng" -> "English"; "fra" -> "Français"; "deu" -> "Deutsch"; "spa" -> "Español"
            "tur" -> "Türkçe"; "fas" -> "فارسی"; "urd" -> "اردو"; ARABIC_SCRIPT -> "العربية ٠١٢٣"; ARABIC_FT -> "العربية (كتب الكيمياء)"; else -> displayName(lang)
        }

        private val SPECIAL = mapOf("chi_sim" to "中文 (简体)", "chi_tra" to "中文 (繁體)", "chi_sim_vert" to "中文 (简体, ↓)", "chi_tra_vert" to "中文 (繁體, ↓)",
            "jpn_vert" to "日本語 (↓)", "kor_vert" to "한국어 (↓)", "deu_latf" to "Deutsch (Fraktur)", "aze_cyrl" to "Azərbaycan (Кирил)",
            "uzb_cyrl" to "Oʻzbek (Кирилл)", "srp_latn" to "Srpski (latinica)", "ita_old" to "Italiano (antico)", "spa_old" to "Español (antiguo)",
            "kat_old" to "ქართული (ძველი)", "frm" to "Français (moyen)", "enm" to "Middle English", "equ" to "Math / equations", "frk" to "Fraktur")

        /** The language's own name from ISO 639 (Java knows the 3-letter codes); scripts as "Script: <name>". */
        fun displayName(lang: String): String {
            SPECIAL[lang]?.let { return it }
            if (lang.startsWith("script/")) return "Script: " + lang.removePrefix("script/").replace('_', ' ')
            val base = lang.substringBefore('_')
            val l = java.util.Locale(base)
            val own = runCatching { l.getDisplayLanguage(l) }.getOrDefault("")
            return if (own.isBlank() || own == base) lang else own.replaceFirstChar { it.titlecase(l) }
        }
    }
}
