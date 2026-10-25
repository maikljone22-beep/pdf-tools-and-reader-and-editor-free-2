package com.smartdocs.editor.core.voice

import android.content.Context
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/** User choices for AI voices, kept in SharedPreferences "ai_voice" (AppSettings is owned by another module). */
data class VoicePrefs(
    val readAloudAi: Boolean = false,   // ReadAloud uses the AI voice instead of the system TTS
    val voice: String = "",             // voice for read aloud / audio summary ("" = provider default)
    val hostVoice: String = "",         // podcast host
    val guestVoice: String = "",        // podcast guest
    val model: String = "",             // speech model override ("" = automatic per provider)
) {
    companion object {
        private const val FILE = "ai_voice"
        fun load(ctx: Context): VoicePrefs {
            val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return VoicePrefs(p.getBoolean("ai", false), p.getString("voice", "").orEmpty(), p.getString("host", "").orEmpty(), p.getString("guest", "").orEmpty(), p.getString("model", "").orEmpty())
        }
        fun save(ctx: Context, v: VoicePrefs) {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("ai", v.readAloudAi).putString("voice", v.voice).putString("host", v.hostVoice).putString("guest", v.guestVoice).putString("model", v.model).apply()
        }
    }
}

/** What the speech provider supports: model, voices, audio format and characters per request. */
data class TtsProfile(val model: String, val voices: List<String>, val format: String, val maxChars: Int) {
    val ext: String get() = if (format == "wav") "wav" else "mp3"
    fun voiceOr(v: String): String = v.takeIf { it in voices } ?: voices.firstOrNull() ?: v
}

/**
 * OpenAI-compatible `/audio/speech` client ("صوت بشري (يحتاج إنترنت)"). Goes through the same endpoint / key /
 * proxy as [AiClient]; each synthesized chunk is cached in `cacheDir/aivoice/<sha1>.<ext>` so replays are free.
 */
object AiSpeech {

    val OPENAI_VOICES = listOf("alloy", "ash", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer")
    val GROQ_AR_VOICES = listOf("noura", "abdullah", "fahad", "sultan", "lulwa", "aisha")
    private const val GROQ_AR_MODEL = "canopylabs/orpheus-arabic-saudi"

    private var cachedProfile: Pair<String, TtsProfile>? = null
    private val profileMutex = Mutex()

    suspend fun settings(ctx: Context): AppSettings = (ctx.applicationContext as SmartDocsApp).settings.settings.first()

    fun client(settings: AppSettings) = AiClient(settings)

    fun available(settings: AppSettings): Boolean = AiClient.available(settings)

    fun cacheDir(ctx: Context): File = File(ctx.cacheDir, "aivoice").apply { mkdirs() }

    fun clearCache(ctx: Context) { cacheDir(ctx).listFiles()?.forEach { it.delete() } }

    /** Resolve the provider profile (the proxy reports it at GET /audio/voices; otherwise by endpoint). */
    suspend fun profile(ctx: Context, settings: AppSettings? = null): TtsProfile {
        val s = settings ?: settings(ctx)
        val c = client(s)
        val prefs = VoicePrefs.load(ctx)
        val key = c.baseUrl + "|" + prefs.model
        cachedProfile?.let { if (it.first == key) return it.second }
        return profileMutex.withLock {
            cachedProfile?.let { if (it.first == key) return@withLock it.second }
            val p = when {
                c.viaProxy -> runCatching {
                    val o = c.getJson("/audio/voices")
                    val voices = (o["voices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().ifEmpty { OPENAI_VOICES }
                    TtsProfile(
                        (o["model"] as? JsonPrimitive)?.contentOrNull ?: "gpt-4o-mini-tts", voices,
                        (o["format"] as? JsonPrimitive)?.contentOrNull ?: "mp3", (o["max_chars"] as? JsonPrimitive)?.intOrNull ?: 1500,
                    )
                }.getOrElse { TtsProfile("gpt-4o-mini-tts", OPENAI_VOICES, "mp3", 1500) }
                c.baseUrl.contains("groq.com") -> TtsProfile(prefs.model.ifBlank { GROQ_AR_MODEL }, GROQ_AR_VOICES, "wav", 190)
                else -> TtsProfile(prefs.model.ifBlank { "gpt-4o-mini-tts" }, OPENAI_VOICES, "mp3", 1500)
            }
            cachedProfile = key to p
            p
        }
    }

    /** Split text into speakable pieces of at most [max] characters, on sentence then word boundaries. */
    fun split(text: String, max: Int): List<String> {
        val clean = text.replace('\r', '\n').replace(Regex("[ \t]+"), " ").replace(Regex("\n{2,}"), "\n").trim()
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<String>(); val sb = StringBuilder()
        fun flush() { if (sb.isNotBlank()) out += sb.toString().trim(); sb.clear() }
        clean.split(Regex("(?<=[.!?؟。\n؛:])\\s+")).forEach { sentence ->
            val st = sentence.trim(); if (st.isEmpty()) return@forEach
            if (st.length > max) {
                flush()
                st.split(' ').forEach { w ->
                    if (sb.length + w.length + 1 > max) flush()
                    if (w.length > max) w.chunked(max).forEach { out += it } else { if (sb.isNotEmpty()) sb.append(' '); sb.append(w) }
                }
                flush()
            } else {
                if (sb.length + st.length + 1 > max) flush()
                if (sb.isNotEmpty()) sb.append(' '); sb.append(st)
            }
        }
        flush()
        return out
    }

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Synthesize one piece (≤ profile.maxChars) and return the cached audio file. [voice] blank = default voice.
     * [instructions] (OpenAI gpt-4o-mini-tts only) steers tone, e.g. "تحدّث بالعربية الفصحى بنبرة ودودة".
     */
    suspend fun synthesize(ctx: Context, text: String, voice: String, speed: Float = 1f, instructions: String? = null, settings: AppSettings? = null): File = withContext(Dispatchers.IO) {
        val s = settings ?: settings(ctx)
        val prof = profile(ctx, s)
        val v = prof.voiceOr(voice)
        val key = sha1("${prof.model}|$v|$speed|${instructions.orEmpty()}|$text")
        cacheDir(ctx).listFiles { f -> f.nameWithoutExtension == key && f.length() > 0 }?.firstOrNull()?.let { return@withContext it }
        val body = buildJsonObject {
            put("model", prof.model); put("input", text); put("voice", v)
            put("response_format", prof.format)
            if (speed != 1f) put("speed", speed.toDouble())
            if (!instructions.isNullOrBlank() && prof.model.contains("gpt-4o")) put("instructions", instructions)
        }
        val (bytes, _) = client(s).postForBytes("/audio/speech", body)
        if (bytes.size < 64) throw com.smartdocs.editor.core.ai.AiException("empty audio")
        val ext = if (AudioConcat.isWav(bytes)) "wav" else "mp3"
        val out = File(cacheDir(ctx), "$key.$ext")
        val tmp = File(cacheDir(ctx), "$key.$ext.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        out
    }

    /**
     * Synthesize any length of text: split by the provider's limit, synthesize each piece and join them into one
     * file in [outDir] (or the cache). Returns the file and the duration in ms of each piece.
     */
    suspend fun synthesizeLong(ctx: Context, text: String, voice: String, speed: Float = 1f, instructions: String? = null, outFile: File? = null,
                               onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): File {
        val s = settings(ctx)
        val prof = profile(ctx, s)
        val parts = split(text, prof.maxChars)
        if (parts.isEmpty()) throw com.smartdocs.editor.core.ai.AiException("empty text")
        val files = parts.mapIndexed { i, p -> synthesize(ctx, p, voice, speed, instructions, s).also { onProgress(i + 1, parts.size) } }
        if (files.size == 1 && outFile == null) return files[0]
        val out = outFile ?: File(cacheDir(ctx), sha1(files.joinToString { it.name }) + "." + files[0].extension)
        AudioConcat.join(files, out)
        return out
    }
}
