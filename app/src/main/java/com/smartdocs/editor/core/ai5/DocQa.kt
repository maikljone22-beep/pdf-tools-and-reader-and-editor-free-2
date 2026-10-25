package com.smartdocs.editor.core.ai5

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Grounded question answering over one or several documents, with numbered citations. */
object DocQa {

    /** Prompt for one turn. [history] = previous turns as plain text (markers stripped), newest last. */
    fun messages(question: String, sources: List<Source>, history: List<ChatMessage>, docNames: List<String>, persona: String? = null): List<ChatMessage> = buildList {
        add(ChatMessage("system", AiCalls.SYSTEM + " " + CitationFormat.INSTRUCTIONS +
            (if (docNames.size > 1) " المستندات: " + docNames.joinToString("، ") + ". اذكر اسم الملف عند المقارنة بين المستندات." else "") +
            (persona?.takeIf { it.isNotBlank() }?.let { " تعليمات المستخدم لهذه المساحة: $it" } ?: "")))
        history.takeLast(8).forEach { add(it.copy(content = DocIndex.clip(CitationFormat.plain(it.content), 1500))) }
        add(ChatMessage("user", "المقاطع من المستند:\n\n" + Retriever.sourcesBlock(sources) + "\n\nالسؤال: " + question))
    }

    /** Retrieval query: the question plus the last user turn when the question is a short follow-up ("وماذا عن الثاني؟"). */
    fun retrievalQuery(question: String, history: List<ChatMessage>): String {
        val prev = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        return if (AiTokens.tokens(question).size <= 3 && prev.isNotBlank()) "$prev $question" else question
    }

    /** Three to five starter questions for a document, from a spread of its passages (one request, cached by the caller). */
    suspend fun starters(ctx: Context, client: AiClient, indexes: List<DocIndex>): List<String> {
        val material = indexes.joinToString("\n\n") { d -> "## ${d.name}\n" + DocIndex.material(d.spread(6000 / indexes.size.coerceAtLeast(1))) }
        val answer = AiCalls.ask(ctx, client, AiCalls.SYSTEM,
            "اقترح 5 أسئلة قصيرة ومفيدة يمكن أن يطرحها القارئ عن هذا المستند، بلغة المستند. أعد JSON فقط: {\"questions\":[\"...\"]}\n\n$material")
        return parseQuestions(answer)
    }

    fun parseQuestions(answer: String): List<String> {
        val el = AiJson.extract(answer)
        val list = when (el) {
            is JsonObject -> AiJson.strings(AiJson.arr(el, "questions", "items", "followups"))
            is JsonArray -> AiJson.strings(el)
            else -> answer.lines().map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }.filter { it.endsWith("?") || it.endsWith("؟") }
        }
        return list.map { it.trim() }.filter { it.length in 4..200 }.distinct().take(5)
    }
}
