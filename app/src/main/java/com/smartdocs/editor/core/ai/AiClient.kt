package com.smartdocs.editor.core.ai

import com.smartdocs.editor.core.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)

data class OutlineEntry(val title: String, val page: Int, val level: Int = 0)

/**
 * Minimal OpenAI-compatible chat-completions client (works with OpenAI, Groq, OpenRouter, Ollama, LM Studio…).
 * Streams tokens through SSE; falls back to a non-streaming request when the server does not stream.
 */
class AiClient(private val endpoint: String, private val model: String, private val apiKey: String, private val appToken: String = "") {

    /** The user's own key/endpoint when set; otherwise the app's proxy (no key needed on the phone). */
    constructor(settings: AppSettings) : this(
        if (settings.aiApiKey.isBlank() && usesProxy()) com.smartdocs.editor.BuildConfig.AI_PROXY_URL.trimEnd('/') else settings.aiEndpoint,
        settings.aiModel,
        settings.aiApiKey,
        if (settings.aiApiKey.isBlank() && usesProxy()) com.smartdocs.editor.BuildConfig.AI_APP_TOKEN else "",
    )

    companion object {
        fun usesProxy(): Boolean = com.smartdocs.editor.BuildConfig.AI_PROXY_URL.isNotBlank()
        /** True when the assistant can run: own key, a keyless endpoint (local server) or the app's proxy. */
        fun available(settings: AppSettings): Boolean = settings.aiApiKey.isNotBlank() || usesProxy() || !settings.aiEndpoint.contains("openai.com")
        const val MAX_DOC_CHARS = 12_000
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /** Join page texts with "[صفحة N]" markers and trim to [MAX_DOC_CHARS], keeping a fair share of every page. */
        fun trimDocument(pages: List<String>, max: Int = MAX_DOC_CHARS): String {
            if (pages.isEmpty()) return ""
            val total = pages.sumOf { it.length }
            val sb = StringBuilder()
            if (total <= max) {
                pages.forEachIndexed { i, p -> if (p.isNotBlank()) sb.append("[صفحة ${i + 1}]\n").append(p.trim()).append("\n\n") }
                return sb.toString()
            }
            val perPage = (max / pages.size).coerceAtLeast(200)
            pages.forEachIndexed { i, p ->
                if (sb.length >= max) return@forEachIndexed
                if (p.isBlank()) return@forEachIndexed
                val t = p.trim()
                sb.append("[صفحة ${i + 1}]\n").append(if (t.length > perPage) t.take(perPage) + "…" else t).append("\n\n")
            }
            return if (sb.length > max) sb.substring(0, max) else sb.toString()
        }

        /** Parse a JSON array of {title, page[, level]} possibly wrapped in prose / code fences. */
        fun parseOutline(answer: String): List<OutlineEntry> {
            val start = answer.indexOf('['); val end = answer.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val arr: JsonArray = runCatching { json.parseToJsonElement(answer.substring(start, end + 1)).jsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val title = o["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val page = o["page"]?.jsonPrimitive?.let { p -> runCatching { p.int }.getOrNull() ?: p.content.filter { it.isDigit() }.toIntOrNull() } ?: 1
                val level = o["level"]?.jsonPrimitive?.let { runCatching { it.int }.getOrNull() } ?: 0
                OutlineEntry(title, page, level)
            }
        }

        const val SYSTEM_PROMPT = "أنت مساعد ذكي داخل تطبيق لقراءة وتحرير ملفات PDF. أجب بالعربية الفصحى الواضحة إلا إذا طُلبت لغة أخرى. اعتمد على نص المستند المرفق عند توفره واذكر رقم الصفحة عند الاقتباس."

        // ---- Helper prompts (Arabic) ----
        fun summarizePrompt(text: String) = "لخّص النص التالي في نقاط واضحة ومرتبة، مع ذكر الأفكار الرئيسية والنتائج:\n\n$text"
        fun explainPrompt(text: String) = "اشرح النص التالي بأسلوب مبسّط يناسب طالباً، ووضّح المصطلحات الصعبة:\n\n$text"
        fun translatePrompt(text: String, targetLang: String) = "ترجم النص التالي إلى $targetLang ترجمة دقيقة مع الحفاظ على المعنى والتنسيق، وبدون أي تعليق إضافي:\n\n$text"
        fun quizPrompt(text: String) = "أنشئ 5 أسئلة مراجعة متنوعة (اختيار من متعدد وصح/خطأ وسؤال مفتوح) من النص التالي، مع الإجابة النموذجية لكل سؤال:\n\n$text"
        fun definePrompt(text: String) = "عرّف المصطلح أو العبارة التالية تعريفاً دقيقاً ومختصراً مع مثال إن أمكن:\n\n$text"
        fun outlinePrompt(text: String) = "استخرج فهرس محتويات لهذا المستند. أعد الإجابة بصيغة JSON فقط: مصفوفة من العناصر على شكل {\"title\": \"العنوان\", \"page\": رقم_الصفحة, \"level\": 0}. استخدم علامات [صفحة N] لتحديد رقم الصفحة. لا تكتب أي نص خارج الـ JSON.\n\n$text"
    }

    val isConfigured: Boolean get() = endpoint.isNotBlank() && model.isNotBlank()

    private fun url() = endpoint.trimEnd('/') + "/chat/completions"

    private fun body(messages: List<ChatMessage>, stream: Boolean): String = buildJsonObject {
        put("model", model)
        put("stream", stream)
        put("messages", buildJsonArray {
            messages.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
        })
    }.toString()

    private fun request(messages: List<ChatMessage>, stream: Boolean): Request {
        val b = Request.Builder().url(url())
            .post(body(messages, stream).toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
        if (apiKey.isNotBlank()) b.header("Authorization", "Bearer $apiKey")
        if (appToken.isNotBlank()) b.header("X-App-Token", appToken)
        if (stream) b.header("Accept", "text/event-stream")
        return b.build()
    }

    /** Stream assistant tokens. Emits deltas as they arrive. */
    fun stream(messages: List<ChatMessage>): Flow<String> = flow {
        val resp = http.newCall(request(messages, true)).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw AiException("HTTP ${r.code}: ${r.body?.string()?.take(300)}")
            val ct = r.header("Content-Type").orEmpty()
            val source = r.body?.source() ?: throw AiException("empty body")
            if (!ct.contains("text/event-stream")) {
                // Non-streaming server: parse the full JSON once.
                val text = source.readUtf8()
                emit(extractContent(text))
                return@use
            }
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = runCatching {
                    val obj = json.parseToJsonElement(data).jsonObject
                    val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    choice?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content
                        ?: choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                }.getOrNull()
                if (!delta.isNullOrEmpty()) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Single non-streaming completion. */
    suspend fun complete(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        http.newCall(request(messages, false)).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException("HTTP ${r.code}: ${text.take(300)}")
            extractContent(text)
        }
    }

    private fun extractContent(text: String): String {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw AiException("bad response")
        obj["error"]?.let { throw AiException(it.toString().take(300)) }
        return obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
    }

    // ---- Raw access (agent tool calling, speech) ----

    /** Base URL without trailing slash (the user's endpoint or the app proxy). */
    val baseUrl: String get() = endpoint.trimEnd('/')
    val modelName: String get() = model
    /** True when requests go through the app's proxy (the server forces model / voices). */
    val viaProxy: Boolean get() = appToken.isNotBlank()

    /** Auth headers for raw requests built elsewhere (ai6 media routes). */
    fun authHeaders(): Map<String, String> = buildMap {
        if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
        if (appToken.isNotBlank()) put("X-App-Token", appToken)
    }

    private fun authed(b: Request.Builder): Request.Builder {
        if (apiKey.isNotBlank()) b.header("Authorization", "Bearer $apiKey")
        if (appToken.isNotBlank()) b.header("X-App-Token", appToken)
        return b
    }

    /** POST a JSON body to [path] (e.g. "/chat/completions") and parse the JSON answer. Throws [AiException] on HTTP errors. */
    suspend fun postJson(path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val req = authed(Request.Builder().url(baseUrl + path).post(body.toString().toRequestBody("application/json".toMediaType())).header("Content-Type", "application/json")).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException("HTTP ${r.code}: ${text.take(300)}")
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw AiException("bad response")
            obj["error"]?.let { throw AiException(it.toString().take(300)) }
            obj
        }
    }

    /** POST a JSON body and return the raw bytes + Content-Type (used for /audio/speech). */
    suspend fun postForBytes(path: String, body: JsonObject): Pair<ByteArray, String?> = withContext(Dispatchers.IO) {
        val req = authed(Request.Builder().url(baseUrl + path).post(body.toString().toRequestBody("application/json".toMediaType())).header("Content-Type", "application/json")).build()
        http.newCall(req).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) throw AiException("HTTP ${r.code}: ${String(bytes.take(300).toByteArray())}")
            bytes to r.header("Content-Type")
        }
    }

    /** GET [path] and parse JSON (e.g. the proxy's "/audio/voices"). */
    suspend fun getJson(path: String): JsonObject = withContext(Dispatchers.IO) {
        val req = authed(Request.Builder().url(baseUrl + path).get()).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException("HTTP ${r.code}: ${text.take(300)}")
            runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw AiException("bad response")
        }
    }

    /** Quick connectivity test: a tiny completion. Throws on failure. */
    suspend fun testConnection(): String = complete(listOf(ChatMessage("user", "قل: مرحباً"))).take(100)

    // ---- Convenience helpers ----
    private fun withSystem(user: String, docContext: String? = null): List<ChatMessage> = buildList {
        add(ChatMessage("system", SYSTEM_PROMPT))
        if (!docContext.isNullOrBlank()) add(ChatMessage("system", "نص المستند:\n$docContext"))
        add(ChatMessage("user", user))
    }

    fun summarize(text: String): Flow<String> = stream(withSystem(summarizePrompt(text)))
    fun explain(text: String): Flow<String> = stream(withSystem(explainPrompt(text)))
    fun translate(text: String, targetLang: String): Flow<String> = stream(withSystem(translatePrompt(text, targetLang)))
    fun quizQuestions(text: String): Flow<String> = stream(withSystem(quizPrompt(text)))
    fun define(text: String): Flow<String> = stream(withSystem(definePrompt(text)))

    /** Outline generation (non-streaming): returns parsed entries, or empty when the model answered with no JSON. */
    suspend fun outline(text: String): List<OutlineEntry> = parseOutline(complete(withSystem(outlinePrompt(text))))

    /** Chat with full history; the document text is injected as a system message. */
    fun chat(history: List<ChatMessage>, docContext: String?): Flow<String> {
        val msgs = buildList {
            add(ChatMessage("system", SYSTEM_PROMPT))
            if (!docContext.isNullOrBlank()) add(ChatMessage("system", "نص المستند:\n$docContext"))
            addAll(history)
        }
        return stream(msgs)
    }
}

class AiException(message: String) : Exception(message)
