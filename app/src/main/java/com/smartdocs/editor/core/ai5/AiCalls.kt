package com.smartdocs.editor.core.ai5

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.AiException
import com.smartdocs.editor.core.ai.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thrown when the proxy's daily request limit is reached (HTTP 429) or our local estimate says it will be. */
class AiQuotaException(message: String) : Exception(message)

/**
 * Every ai5 request goes through here so the proxy's daily limit (60 chat requests / device) is respected:
 * a local per-day counter (SharedPreferences "ai5_usage") lets screens show the remaining budget and refuse a
 * multi-request job (map-reduce summary, whole-book podcast, translation) that would not fit.
 */
object AiCalls {
    const val PROXY_DAILY_LIMIT = 60
    private const val PREFS = "ai5_usage"

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun usedToday(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.getString("day", "") == today()) p.getInt("n", 0) else 0
    }

    private fun count(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = usedToday(ctx) + 1
        p.edit().putString("day", today()).putInt("n", n).apply()
    }

    /** Remaining requests today through the proxy; Int.MAX_VALUE when the user has an own key / endpoint. */
    fun remaining(ctx: Context, client: AiClient): Int = if (client.viaProxy) (PROXY_DAILY_LIMIT - usedToday(ctx)).coerceAtLeast(0) else Int.MAX_VALUE

    /** True when [needed] requests fit in today's budget. */
    fun fits(ctx: Context, client: AiClient, needed: Int): Boolean = remaining(ctx, client) >= needed

    private fun map(e: Throwable): Throwable {
        val m = e.message.orEmpty()
        return if (m.contains("429") || m.contains("daily limit", true)) AiQuotaException(m) else e
    }

    suspend fun complete(ctx: Context, client: AiClient, msgs: List<ChatMessage>): String {
        count(ctx)
        return try { client.complete(msgs) } catch (e: AiException) { throw map(e) }
    }

    fun stream(ctx: Context, client: AiClient, msgs: List<ChatMessage>): Flow<String> =
        client.stream(msgs).onStart { count(ctx) }

    /** Convenience: system + user prompt, non-streaming. */
    suspend fun ask(ctx: Context, client: AiClient, system: String, user: String): String =
        complete(ctx, client, listOf(ChatMessage("system", system), ChatMessage("user", user)))

    fun isQuota(e: Throwable): Boolean = e is AiQuotaException || e.message.orEmpty().let { it.contains("429") || it.contains("daily limit", true) }

    /** Base system prompt for every ai5 feature. */
    const val SYSTEM =
        "أنت مساعد دقيق داخل تطبيق لقراءة ملفات PDF. " + // أنت مساعد دقيق داخل تطبيق لقراءة ملفات PDF.
            "أجب بلغة سؤال المستخدم إلا إذا طُلبت لغة أخرى، " + // أجب بلغة سؤال المستخدم إلا إذا طُلبت لغة أخرى،
            "ولا تخترع معلومات غير موجودة في المستند." // ولا تخترع معلومات غير موجودة في المستند.
}
