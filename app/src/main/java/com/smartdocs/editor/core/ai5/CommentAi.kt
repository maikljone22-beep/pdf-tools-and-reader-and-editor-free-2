package com.smartdocs.editor.core.ai5

import android.content.Context
import android.graphics.RectF
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.core.pdf.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AI summary of the document's comments (notes, highlights with text, replies), added back to the document as a
 * NOTE annotation on the first page. One request; comments are truncated to ~12k characters in page order.
 */
object CommentAi {

    suspend fun commentsText(src: File, password: String?): String {
        val list = AnnotationWriter.list(src, password).filter { it.contents.isNotBlank() }
        return list.sortedWith(compareBy({ it.page }, { -it.rect.top })).joinToString("\n") { e ->
            "[صفحة ${e.page + 1}] (${e.subtype}${if (e.author.isNotBlank()) " — " + e.author else ""}) ${e.contents.replace('\n', ' ').trim()}"
        }.let { if (it.length > 12_000) it.take(12_000) + "…" else it }
    }

    suspend fun summarize(ctx: Context, client: AiClient, src: File, password: String?, language: String = ""): String {
        val text = commentsText(src, password)
        if (text.isBlank()) throw IllegalStateException("no comments")
        val lang = AiLang.name(language.ifBlank { AiLang.detect(text).ifBlank { "ar" } })
        return AiCalls.complete(ctx, client, listOf(
            ChatMessage("system", AiCalls.SYSTEM),
            ChatMessage("user", "لخّص تعليقات المراجعين التالية بلغة $lang: أهم الملاحظات مجمعة حسب الموضوع مع أرقام الصفحات، ثم المطلوب تنفيذه في نقاط، " +
                "ثم الملاحظات المتعارضة إن وجدت. لا تستخدم Markdown.\n\n$text"),
        )).replace("**", "").trim()
    }

    /** Writes [summary] as a NOTE on page 1 (top-left corner) into [out]; returns [out]. */
    suspend fun addAsNote(ctx: Context, src: File, password: String?, summary: String, author: String, out: File): File {
        val h = withContext(Dispatchers.IO) { PdfOps.load(src, password).use { d -> d.getPage(0).mediaBox.let { it.upperRightY } } }
        val r = RectF(18f, h - 18f, 38f, h - 38f)
        AnnotationWriter.commit(ctx, src, listOf(Annot(kind = AnnotKind.NOTE, page = 0, rects = listOf(r), text = summary, author = author, color = 0xFFC107)), out, password)
        return out
    }
}
