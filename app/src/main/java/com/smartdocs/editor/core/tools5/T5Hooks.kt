package com.smartdocs.editor.core.tools5

import android.content.Context
import android.graphics.Bitmap
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.data.AppSettings

/**
 * Integration points other modules (or the lead) can plug in without editing tools5 files.
 * See MODULE_tools5_r5.md, hook H1.
 */
object T5Hooks {
    /**
     * Vision description of a figure (alt text). When null, [AltTextAi] falls back to a text-only prompt built
     * from the page text around the figure (works with every OpenAI-compatible endpoint).
     */
    @Volatile var describeImage: (suspend (ctx: Context, image: Bitmap, arabic: Boolean) -> String)? = null
}

object AltTextAi {

    /** Alt text for a figure: the vision hook when available, else a context-based suggestion. Short (≤ 150 chars). */
    suspend fun suggest(ctx: Context, settings: AppSettings, image: Bitmap?, pageText: String, arabic: Boolean): String {
        T5Hooks.describeImage?.let { hook ->
            // a vision route the endpoint / proxy does not offer falls back to the page-text prompt below
            if (image != null) try { hook(ctx, image, arabic).trim().takeIf { it.isNotEmpty() }?.let { return it.take(250) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
        val system = if (arabic) "اكتب نصا\u064B بديلا\u064B (Alt text) قصيرا\u064B ودقيقا\u064B لصورة في مستند، بجملة واحدة لا تتجاوز 120 حرفا\u064B، اعتمادا\u064B على نص الصفحة المحيط. لا تبدأ بعبارة «صورة لـ»."
        else "Write one short, precise alt text (max 120 characters) for a figure in a document, based on the surrounding page text. Do not start with \"Image of\"."
        val user = pageText.take(3000).ifBlank { "(no text on the page)" }
        return AiCalls.ask(ctx, AiClient(settings), system, user).trim().trim('"').take(250)
    }

    fun available(settings: AppSettings): Boolean = settings.aiEnabled && AiClient.available(settings)   // ai6: AI master switch
}
