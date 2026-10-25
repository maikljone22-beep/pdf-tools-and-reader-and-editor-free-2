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
data class CitedMsg(val id: Long, val role: String, val text: String, val answer: CitedAnswer? = null, val streaming: Boolean = false, val error: Boolean = false)

/**
 * Grounded chat over a [DocSet]: each question retrieves passages from the whole document(s), streams the answer
 * (hiding the citation tail) and parses citations + follow-ups. Chats are saved per scope unless [temporary].
 */
class CitedChatState(private val scope: CoroutineScope, private val ctx: Context, private val client: AiClient, private val docs: DocSet) {
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
            messages += CitedMsg(++seq, m.role, m.text, if (m.role == "assistant") CitedAnswer(m.text, m.citations, m.followUps) else null)
        }
    }

    fun savedChats(): List<SavedChat> = store.chats(docs.scope)
    fun delete(id: String) { store.deleteChat(id); if (id == chatId) newChat() }

    private fun persist() {
        if (temporary || messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == "user" }?.text?.take(60).orEmpty()
        val list = messages.filter { !it.streaming && !it.error }.map { m -> StoredMsg(m.role, m.answer?.text ?: m.text, m.answer?.citations.orEmpty(), m.answer?.followUps.orEmpty()) }
        runCatching { store.saveChat(SavedChat(chatId, docs.scope, title, System.currentTimeMillis(), list)) }
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

    fun send(question: String) {
        val q = question.trim()
        if (q.isEmpty() || busy || !docs.ready) return
        val history = messages.filter { !it.streaming && !it.error }.map { ChatMessage(it.role, it.answer?.text ?: it.text) }
        messages += CitedMsg(++seq, "user", q)
        val id = ++seq
        messages += CitedMsg(id, "assistant", "", streaming = true)
        busy = true
        job = scope.launch {
            val sb = StringBuilder()
            fun idx() = messages.indexOfFirst { it.id == id }
            try {
                val sources = withContext(Dispatchers.Default) { Retriever.retrieve(docs.indexes, DocQa.retrievalQuery(q, history)) }
                val msgs = DocQa.messages(q, sources, history, docs.indexes.map { it.name }, docs.persona)
                AiCalls.stream(ctx, client, msgs).collect { t ->
                    sb.append(t)
                    val i = idx(); if (i >= 0) messages[i] = messages[i].copy(text = CitationFormat.visible(sb.toString()))
                }
                val parsed = CitationFormat.parse(sb.toString(), sources)
                val i = idx(); if (i >= 0) messages[i] = messages[i].copy(text = parsed.text, answer = parsed, streaming = false)
                persist()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val msg = if (AiCalls.isQuota(e)) ctx.getString(R.string.a5_err_quota) else ctx.getString(R.string.a5_err_failed, e.message ?: e.javaClass.simpleName)
                val i = idx(); if (i >= 0) messages[i] = messages[i].copy(text = sb.toString().ifBlank { msg }, streaming = false, error = sb.isEmpty())
            } finally { busy = false }
        }
    }

    /** Last finished answer (for voice chat / export). */
    fun lastAnswer(): CitedMsg? = messages.lastOrNull { it.role == "assistant" && !it.streaming && !it.error }
}
