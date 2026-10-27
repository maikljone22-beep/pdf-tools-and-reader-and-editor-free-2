package com.smartdocs.editor.ui.ai5

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.AiStore
import com.smartdocs.editor.core.ai5.CitationFormat
import com.smartdocs.editor.core.ai5.CitedAnswer
import com.smartdocs.editor.core.ai5.DocQa
import com.smartdocs.editor.core.ai5.Retriever
import com.smartdocs.editor.core.ai5.SavedChat
import com.smartdocs.editor.core.ai5.StoredMsg
import com.smartdocs.editor.core.ai5.SummaryGen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A chat row. Assistant rows carry the parsed [answer] once streaming ends. */
data class CitedMsg(val id: Long, val role: String, val text: String, val answer: CitedAnswer? = null, val streaming: Boolean = false, val error: Boolean = false,
                    // ai6: images sent with a question (AI-4 / AI-10), web results used (AI-14), thumbs feedback
                    val images: Int = 0, val web: List<com.smartdocs.editor.core.ai6.WebResult> = emptyList(), val feedback: Int = 0)

/**
 * Grounded chat over a [DocSet]: each question retrieves passages from the whole document(s), streams the answer
 * (hiding the citation tail) and parses citations + follow-ups. Chats are saved per scope unless [temporary].
 */
class CitedChatState(private val scope: CoroutineScope, private val ctx: Context, private val client: AiClient, docs: DocSet) {
    /** ai6 (AI-4): the hub can add files; the chat keeps its messages and uses the new set. */
    var docs: DocSet by mutableStateOf(docs)
    /** ai6 (AI-14): add live web results to each question. */
    var webSearch by mutableStateOf(false)
    val messages = mutableStateListOf<CitedMsg>()
    var busy by mutableStateOf(false); private set
    var starters by mutableStateOf<List<String>>(emptyList()); private set
    var startersLoading by mutableStateOf(false); private set
    var temporary by mutableStateOf(false)
    var chatId by mutableStateOf(AiStore.newId()); private set
    private val store = AiStore(ctx)
    private var job: Job? = null
    private var seq = 0L

    fun stop() {
        job?.cancel(); job = null; busy = false
        val i = messages.indexOfLast { it.streaming }
        if (i >= 0) messages[i] = messages[i].copy(streaming = false)
    }

    fun newChat() { stop(); messages.clear(); chatId = AiStore.newId() }

    fun open(c: SavedChat) {
        stop(); messages.clear(); chatId = c.id
        c.messages.forEach { m ->
            messages += CitedMsg(++seq, m.role, m.text, if (m.role == "assistant") CitedAnswer(m.text, m.citations, m.followUps) else null,
                images = m.images, web = m.web, feedback = m.feedback)
        }
    }

    fun savedChats(): List<SavedChat> = store.chats(docs.scope)
    fun delete(id: String) { store.deleteChat(id); if (id == chatId) newChat() }

    private fun persist() {
        if (temporary || messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == "user" }?.text?.take(60).orEmpty()
        val list = messages.filter { !it.streaming && !it.error }.map { m -> StoredMsg(m.role, m.answer?.text ?: m.text, m.answer?.citations.orEmpty(), m.answer?.followUps.orEmpty(), m.feedback, m.web, m.images) }
        runCatching { store.saveChat(SavedChat(chatId, docs.scope, title, System.currentTimeMillis(), list, docs.files.map { it.absolutePath })) }
    }

    /** ai6 (§35 feedback buttons): thumbs up (1) / down (-1) / clear (0) on an answer; saved with the chat. */
    fun setFeedback(id: Long, value: Int) {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0) return
        messages[i] = messages[i].copy(feedback = if (messages[i].feedback == value) 0 else value)
        persist()
    }

    /** Starter questions: from a cached summary when one exists (free), else one request. */
    fun loadStarters() {
        if (starters.isNotEmpty() || startersLoading || !docs.ready) return
        startersLoading = true
        scope.launch {
            try {
                val cached = docs.indexes.firstNotNullOfOrNull { SummaryGen.anyCached(ctx, it) }?.questions.orEmpty()
                starters = cached.ifEmpty { withContext(Dispatchers.IO) { DocQa.starters(ctx, client, docs.indexes) } }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { startersLoading = false }
        }
    }

    /**
     * Ask [question]. ai6: [images] go to a vision model (AI-4 / AI-10), [skill] puts its instruction first (AI-6 / AI-11),
     * [webSearch] adds live web results (AI-14), and embeddings re-rank the passages when available.
     */
    fun send(question: String, images: List<com.smartdocs.editor.core.ai6.AiImage> = emptyList(), skill: com.smartdocs.editor.core.ai6.Skill? = null) {
        val q = question.trim()
        if ((q.isEmpty() && images.isEmpty()) || busy || !docs.ready) return
        val history = messages.filter { !it.streaming && !it.error }.map { ChatMessage(it.role, it.answer?.text ?: it.text) }
        val shown = if (skill != null) "/${skill.id} $q".trim() else q
        messages += CitedMsg(++seq, "user", shown, images = images.size)
        val id = ++seq
        messages += CitedMsg(id, "assistant", "", streaming = true)
        busy = true
        job = scope.launch {
            val sb = StringBuilder()
            fun idx() = messages.indexOfFirst { it.id == id }
            try {
                val rq = DocQa.retrievalQuery(q.ifBlank { skill?.name.orEmpty() }, history)
                val wide = withContext(Dispatchers.Default) { Retriever.retrieve(docs.indexes, rq, Retriever.MAX_CHARS * 2, Retriever.K * 2) }
                // ai6: embeddings re-rank (silent fallback to the BM25 pick)
                val sources = com.smartdocs.editor.core.ai6.Reranker.rerank(ctx, client, rq, wide, Retriever.MAX_CHARS, Retriever.K)
                    ?: withContext(Dispatchers.Default) { Retriever.retrieve(docs.indexes, rq) }
                // ai6 (AI-14): live web results as [W1]… sources
                val web = if (webSearch && q.isNotBlank()) runCatching { com.smartdocs.editor.core.ai6.AiMedia.webSearch(client, q) }.getOrDefault(emptyList()) else emptyList()
                val asked = com.smartdocs.editor.core.ai6.Skills.apply(skill, q) +
                    (if (web.isNotEmpty()) "\n\nنتائج بحث الويب (استشهد بها هكذا [W1]):\n" + com.smartdocs.editor.core.ai6.AiMedia.webBlock(web) else "")
                val msgs = DocQa.messages(asked, sources, history, docs.indexes.map { it.name }, docs.persona)
                if (images.isNotEmpty()) {
                    // vision: one non-streaming request with the images attached to the last (user) turn
                    val media = msgs.mapIndexed { i, m -> com.smartdocs.editor.core.ai6.MediaMessage(m.role, m.content, if (i == msgs.lastIndex) images else emptyList()) }
                    sb.append(com.smartdocs.editor.core.ai6.AiMedia.visionChat(client, media))
                } else AiCalls.stream(ctx, client, msgs).collect { t ->
                    sb.append(t)
                    val i = idx(); if (i >= 0) messages[i] = messages[i].copy(text = CitationFormat.visible(sb.toString()))
                }
                val parsed = CitationFormat.parse(sb.toString(), sources)
                val i = idx(); if (i >= 0) messages[i] = messages[i].copy(text = parsed.text, answer = parsed, streaming = false, web = web)
                persist()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val msg = com.smartdocs.editor.core.ai6.AiMedia.userMessage(ctx, e)
                val i = idx(); if (i >= 0) messages[i] = messages[i].copy(text = sb.toString().ifBlank { msg }, streaming = false, error = sb.isEmpty())
            } finally { busy = false }
        }
    }

    /** ai6 (AI-15): the conversation as text (answers with their sources). */
    fun transcript(userLabel: String, aiLabel: String): String = com.smartdocs.editor.core.ai6.ChatExport.transcript(
        messages.filter { !it.streaming && !it.error }.map { m -> com.smartdocs.editor.core.ai6.ChatExport.Turn(m.role == "user", if (m.role == "user") m.text else m.answer?.plainWithSources() ?: m.text) },
        userLabel, aiLabel)

    /** Last finished answer (for voice chat / export). */
    fun lastAnswer(): CitedMsg? = messages.lastOrNull { it.role == "assistant" && !it.streaming && !it.error }
}
