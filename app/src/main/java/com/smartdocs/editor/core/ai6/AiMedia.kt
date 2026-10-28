package com.smartdocs.editor.core.ai6

import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.AiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * ai6: the AI routes beyond chat — vision (images in chat), image generation, audio transcription, embeddings,
 * live web search and server usage. They go to the user's own OpenAI-compatible endpoint, or to the app's proxy
 * (ai-proxy/worker.js), which may not have the key for a route: then the call throws [AiNotConfiguredException]
 * and the screen shows R.string.ai6_not_configured instead of a raw HTTP error.
 */
class AiNotConfiguredException(val feature: AiFeature, message: String = "not configured: ${feature.name}") : Exception(message)

enum class AiFeature { VISION, IMAGES, TRANSCRIPTION, EMBEDDINGS, WEB_SEARCH, USAGE }

/** One image attached to a chat message (JPEG/PNG bytes). */
class AiImage(val bytes: ByteArray, val mime: String = "image/jpeg") {
    fun dataUrl(): String = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
}

/** A chat turn that may carry images (OpenAI "content parts"). */
data class MediaMessage(val role: String, val text: String, val images: List<AiImage> = emptyList())

data class WebResult(val title: String, val url: String, val snippet: String)

/** Usage as the proxy counts it (per device, per day), or the local estimate when there is no proxy. */
data class AiUsage(val chatUsed: Int, val chatLimit: Int, val ttsUsed: Int = 0, val ttsLimit: Int = 0, val imagesUsed: Int = 0, val imagesLimit: Int = 0, val fromServer: Boolean = false)

object AiMedia {
    /** Default models for the user's own endpoint (the proxy ignores them and uses its own). */
    const val VISION_MODEL_OPENAI = "gpt-4o-mini"
    const val IMAGE_MODEL_OPENAI = "gpt-image-1"
    const val TRANSCRIBE_MODEL_OPENAI = "whisper-1"
    const val TRANSCRIBE_MODEL_GROQ = "whisper-large-v3-turbo"
    const val EMBED_MODEL_OPENAI = "text-embedding-3-small"
    /** Largest image side sent for vision (keeps the request small enough for the proxy). */
    const val MAX_IMAGE_SIDE = 1280

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().addInterceptor(com.smartdocs.editor.core.data.NpfNet.interceptor).connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    }

    // ---------------- pure JSON helpers (unit tested) ----------------

    /** The OpenAI chat body with image parts. Text-only messages keep a plain string content. */
    fun visionBody(model: String, messages: List<MediaMessage>, maxTokens: Int = 1500): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("messages", buildJsonArray {
            messages.forEach { m ->
                add(buildJsonObject {
                    put("role", m.role)
                    if (m.images.isEmpty()) put("content", m.text)
                    else put("content", buildJsonArray {
                        if (m.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", m.text) })
                        m.images.forEach { img -> add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", img.dataUrl()) }) }) }
                    })
                })
            }
        })
    }

    fun imageBody(model: String, prompt: String, size: String): JsonObject = buildJsonObject {
        put("model", model); put("prompt", prompt); put("size", size); put("n", 1)
        // dall-e-* need response_format; gpt-image-1 always answers b64_json and rejects the field
        if (model.startsWith("dall-e")) put("response_format", "b64_json")
    }

    /**
     * aor (AI-8): text fields of an `/images/edits` multipart request (the image goes in the "image" file part).
     * The proxy picks its own model, so [model] is blank there and left out.
     */
    fun editFields(model: String, prompt: String, size: String): List<Pair<String, String>> = buildList {
        if (model.isNotBlank()) add("model" to model)
        add("prompt" to prompt.take(4000)); add("size" to size); add("n" to "1")
        if (model.startsWith("dall-e")) add("response_format" to "b64_json")
    }

    /** Bytes of the first generated image (b64_json), or the URL when the server returned one. */
    fun parseImage(resp: JsonObject): Pair<ByteArray?, String?> {
        val first = (resp["data"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw AiException("no image")
        val b64 = (first["b64_json"] as? JsonPrimitive)?.contentOrNull
        if (!b64.isNullOrBlank()) return Base64.getDecoder().decode(b64) to null
        val url = (first["url"] as? JsonPrimitive)?.contentOrNull ?: throw AiException("no image")
        return null to url
    }

    fun embeddingsBody(model: String, input: List<String>): JsonObject = buildJsonObject {
        put("model", model); put("input", buildJsonArray { input.forEach { add(JsonPrimitive(it)) } })
    }

    /** Vectors in input order (the API returns {data:[{index, embedding:[…]}]}). */
    fun parseEmbeddings(resp: JsonObject): List<FloatArray> {
        val data = resp["data"] as? JsonArray ?: throw AiException("no embeddings")
        return data.mapNotNull { it as? JsonObject }
            .sortedBy { (it["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0 }
            .map { o -> (o["embedding"] as JsonArray).let { a -> FloatArray(a.size) { i -> a[i].jsonPrimitive.content.toFloat() } } }
    }

    /** Results of the proxy's /web/search ({results:[{title,url,snippet}]}). */
    fun parseWebResults(resp: JsonObject): List<WebResult> = (resp["results"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val url = str(o, "url") ?: return@mapNotNull null
        WebResult(str(o, "title").orEmpty(), url, str(o, "snippet") ?: str(o, "description").orEmpty())
    }

    /** DuckDuckGo instant-answer JSON (keyless fallback when there is no proxy): abstract + related topics. */
    fun parseDuckDuckGo(resp: JsonObject): List<WebResult> {
        val out = ArrayList<WebResult>()
        val abs = str(resp, "AbstractText")
        if (!abs.isNullOrBlank()) out += WebResult(str(resp, "Heading").orEmpty(), str(resp, "AbstractURL").orEmpty(), abs)
        fun topics(a: JsonArray?) {
            a.orEmpty().forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val nested = o["Topics"] as? JsonArray
                if (nested != null) { topics(nested); return@forEach }
                val t = str(o, "Text") ?: return@forEach
                out += WebResult(t.substringBefore(" - ").take(80), str(o, "FirstURL").orEmpty(), t)
            }
        }
        topics(resp["RelatedTopics"] as? JsonArray)
        return out.take(8)
    }

    /** Numbered web block for the prompt; the model cites [W1]… */
    fun webBlock(results: List<WebResult>): String = buildString {
        results.forEachIndexed { i, r -> append("[W").append(i + 1).append("] ").append(r.title).append(" — ").append(r.url).append('\n').append(r.snippet.trim()).append("\n\n") }
    }.trim()

    fun parseUsage(resp: JsonObject): AiUsage {
        fun n(k: String) = (resp[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        return AiUsage(n("chat_used"), n("chat_limit"), n("tts_used"), n("tts_limit"), n("images_used"), n("images_limit"), fromServer = true)
    }

    /** Which routes the proxy has keys for ({vision:true, images:false, …}). Missing keys count as false. */
    fun parseCapabilities(resp: JsonObject): Set<AiFeature> = AiFeature.values().filter { f ->
        (resp[f.name.lowercase()] as? JsonPrimitive)?.contentOrNull == "true"
    }.toSet()

    /** Cosine similarity of two vectors (0 when either is empty / zero). */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull

    /** HTTP status → exception: 404/501 (route missing) and the proxy's "not configured" answer become [AiNotConfiguredException]. */
    fun mapError(feature: AiFeature, code: Int, body: String): Exception =
        if (code == 404 || code == 501 || body.contains("not configured", true)) AiNotConfiguredException(feature)
        else AiException("HTTP $code: ${body.take(300)}")

    // ---------------- network ----------------

    private fun authed(client: AiClient, b: Request.Builder): Request.Builder {
        client.authHeaders().forEach { (k, v) -> b.header(k, v) }
        return b
    }

    private suspend fun post(client: AiClient, feature: AiFeature, path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val req = authed(client, Request.Builder().url(client.baseUrl + path).post(body.toString().toRequestBody("application/json".toMediaType()))).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw mapError(feature, r.code, text)
            val obj = runCatching { AiClient.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw AiException("bad response")
            obj["error"]?.let { e -> val m = e.toString(); if (m.contains("not configured", true)) throw AiNotConfiguredException(feature); throw AiException(m.take(300)) }
            obj
        }
    }

    private fun isOpenAi(client: AiClient) = client.baseUrl.contains("openai.com")
    private fun isGroq(client: AiClient) = client.baseUrl.contains("groq.com")

    /** Capabilities: asked from the proxy (cached for the process); guessed from the endpoint for an own key. */
    @Volatile private var proxyCaps: Set<AiFeature>? = null

    suspend fun capabilities(client: AiClient): Set<AiFeature> {
        if (!client.viaProxy) return buildSet {
            add(AiFeature.WEB_SEARCH)                     // keyless DuckDuckGo fallback
            if (isOpenAi(client)) addAll(listOf(AiFeature.VISION, AiFeature.IMAGES, AiFeature.TRANSCRIPTION, AiFeature.EMBEDDINGS))
            else if (isGroq(client)) addAll(listOf(AiFeature.VISION, AiFeature.TRANSCRIPTION))
            else addAll(listOf(AiFeature.VISION, AiFeature.IMAGES, AiFeature.TRANSCRIPTION, AiFeature.EMBEDDINGS))   // unknown server: try, map 404
        }
        proxyCaps?.let { return it }
        val caps = runCatching { parseCapabilities(client.getJson("/capabilities")) }.getOrElse { emptySet() }
        proxyCaps = caps
        return caps
    }

    suspend fun supports(client: AiClient, f: AiFeature): Boolean = f in capabilities(client)

    /** Chat with images (vision). Non-streaming; returns the answer text. */
    suspend fun visionChat(client: AiClient, messages: List<MediaMessage>, maxTokens: Int = 1500): String {
        if (client.viaProxy && !supports(client, AiFeature.VISION)) throw AiNotConfiguredException(AiFeature.VISION)
        val model = if (client.viaProxy) "" else if (isGroq(client)) "meta-llama/llama-4-scout-17b-16e-instruct" else if (isOpenAi(client)) VISION_MODEL_OPENAI else client.modelName
        val resp = post(client, AiFeature.VISION, "/chat/completions", visionBody(model, messages, maxTokens))
        return resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    /** Generate one image; returns PNG/JPEG bytes. [size] e.g. "1024x1024". */
    suspend fun generateImage(client: AiClient, prompt: String, size: String = "1024x1024"): ByteArray {
        if (client.viaProxy && !supports(client, AiFeature.IMAGES)) throw AiNotConfiguredException(AiFeature.IMAGES)
        val model = if (client.viaProxy) "" else IMAGE_MODEL_OPENAI
        val (bytes, url) = parseImage(post(client, AiFeature.IMAGES, "/images/generations", imageBody(model, prompt, size)))
        if (bytes != null) return bytes
        return withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(url!!).build()).execute().use { r -> r.body?.bytes() ?: throw AiException("empty image") } }
    }

    /**
     * aor (AI-8): prompt-based edit of an existing image (OpenAI-compatible `/images/edits`, multipart). [png] is the
     * source image as PNG; returns the edited image bytes. Through the proxy this needs its `/images/edits` route
     * (an older proxy answers 404, shown as "not configured").
     */
    suspend fun editImage(client: AiClient, png: ByteArray, prompt: String, size: String = "1024x1024"): ByteArray {
        if (client.viaProxy && !supports(client, AiFeature.IMAGES)) throw AiNotConfiguredException(AiFeature.IMAGES)
        val model = if (client.viaProxy) "" else IMAGE_MODEL_OPENAI
        val resp = withContext(Dispatchers.IO) {
            val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
            editFields(model, prompt, size).forEach { (k, v) -> mb.addFormDataPart(k, v) }
            mb.addFormDataPart("image", "image.png", png.toRequestBody("image/png".toMediaType()))
            val req = authed(client, Request.Builder().url(client.baseUrl + "/images/edits").post(mb.build())).build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw mapError(AiFeature.IMAGES, r.code, text)
                val obj = runCatching { AiClient.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw AiException("bad response")
                obj["error"]?.let { e -> val m = e.toString(); if (m.contains("not configured", true)) throw AiNotConfiguredException(AiFeature.IMAGES); throw AiException(m.take(300)) }
                obj
            }
        }
        val (bytes, url) = parseImage(resp)
        if (bytes != null) return bytes
        return withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(url!!).build()).execute().use { r -> r.body?.bytes() ?: throw AiException("empty image") } }
    }

    /** Transcribe an audio file (mp3, m4a, wav, ogg, webm…). [language] ISO-639-1 or blank for auto. */
    suspend fun transcribe(client: AiClient, audio: File, language: String = ""): String = withContext(Dispatchers.IO) {
        if (client.viaProxy && !supports(client, AiFeature.TRANSCRIPTION)) throw AiNotConfiguredException(AiFeature.TRANSCRIPTION)
        val model = if (client.viaProxy) "proxy" else if (isGroq(client)) TRANSCRIBE_MODEL_GROQ else TRANSCRIBE_MODEL_OPENAI
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", audio.name, audio.readBytes().toRequestBody(audioMime(audio.name).toMediaType()))
        if (language.isNotBlank()) mb.addFormDataPart("language", language)
        val req = authed(client, Request.Builder().url(client.baseUrl + "/audio/transcriptions").post(mb.build())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw mapError(AiFeature.TRANSCRIPTION, r.code, text)
            runCatching { (AiClient.json.parseToJsonElement(text).jsonObject["text"] as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: text
        }
    }

    fun audioMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3", "mpga", "mpeg" -> "audio/mpeg"; "m4a", "mp4", "aac" -> "audio/mp4"; "wav" -> "audio/wav"
        "ogg", "oga", "opus" -> "audio/ogg"; "webm" -> "audio/webm"; "flac" -> "audio/flac"; "3gp" -> "audio/3gpp"
        else -> "application/octet-stream"
    }

    /** Embeddings for [texts] (batched by 64). */
    suspend fun embed(client: AiClient, texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        if (client.viaProxy && !supports(client, AiFeature.EMBEDDINGS)) throw AiNotConfiguredException(AiFeature.EMBEDDINGS)
        val model = if (client.viaProxy) "" else EMBED_MODEL_OPENAI
        return texts.chunked(64).flatMap { batch -> parseEmbeddings(post(client, AiFeature.EMBEDDINGS, "/embeddings", embeddingsBody(model, batch.map { it.take(6000) }))) }
    }

    /** Live web search: the proxy's /web/search, else the keyless DuckDuckGo instant-answer API. */
    suspend fun webSearch(client: AiClient, query: String, count: Int = 5): List<WebResult> {
        if (client.viaProxy && supports(client, AiFeature.WEB_SEARCH))
            return parseWebResults(post(client, AiFeature.WEB_SEARCH, "/web/search", buildJsonObject { put("query", query); put("count", count) })).take(count)
        return withContext(Dispatchers.IO) {
            val url = "https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q=" + java.net.URLEncoder.encode(query, "UTF-8")
            http.newCall(Request.Builder().url(url).header("User-Agent", "SmartDocs").build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw AiException("HTTP ${r.code}")
                parseDuckDuckGo(AiClient.json.parseToJsonElement(text).jsonObject).take(count)
            }
        }
    }

    /** Server-side usage (proxy /usage); null when there is no proxy or it does not count. */
    suspend fun serverUsage(client: AiClient): AiUsage? {
        if (!client.viaProxy) return null
        return runCatching { parseUsage(client.getJson("/usage")) }.getOrNull()
    }

    /** Scale a bitmap-encoded image down to [MAX_IMAGE_SIDE] and re-encode as JPEG (Android only). */
    fun shrink(bytes: ByteArray): AiImage {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > MAX_IMAGE_SIDE * 2) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return AiImage(bytes)
        return fromBitmap(bmp)
    }

    fun fromBitmap(src: android.graphics.Bitmap): AiImage {
        val scale = minOf(1f, MAX_IMAGE_SIDE.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return AiImage(out.toByteArray(), "image/jpeg")
    }

    /** Message for the user: not configured / quota / generic. Needs a Context for strings. */
    fun userMessage(ctx: android.content.Context, e: Throwable): String = when {
        e is AiNotConfiguredException -> ctx.getString(com.smartdocs.editor.R.string.ai6_not_configured)
        com.smartdocs.editor.core.ai5.AiCalls.isQuota(e) -> ctx.getString(com.smartdocs.editor.R.string.a5_err_quota)
        else -> ctx.getString(com.smartdocs.editor.R.string.a5_err_failed, e.message ?: e.javaClass.simpleName)
    }

}
