package com.smartdocs.editor.ui.ai

import android.content.Context
import androidx.compose.runtime.*
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.ActionError
import com.smartdocs.editor.core.ai.ActionResult
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import com.smartdocs.editor.core.ai.DocAgent
import com.smartdocs.editor.core.ai.DocCommand
import com.smartdocs.editor.core.ai.DocCommandRunner
import com.smartdocs.editor.core.ai.DocHost
import com.smartdocs.editor.core.ai.PdfDocActions
import com.smartdocs.editor.core.files.DocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AgentKind { USER, ASSISTANT, CONFIRM, RESULT, ERROR }
enum class ConfirmStatus { WAITING, RUNNING, DONE, CANCELLED, FAILED }

/** One chat row: text bubble, confirmation card ("هل تريد …؟" تنفيذ / إلغاء) or command result (with undo / open). */
data class AgentMsg(
    val id: Long,
    val kind: AgentKind,
    val text: String,
    val command: DocCommand? = null,
    val status: ConfirmStatus = ConfirmStatus.WAITING,
    val result: ActionResult? = null,
    val undone: Boolean = false,
)

/**
 * State of the document agent chat. Talks to [DocAgent], shows confirmation cards for mutating commands and
 * executes them in place through [PdfDocActions] → [DocHost.apply] (the reader's `applyInPlace`).
 */
class AgentChatState(
    private val scope: CoroutineScope,
    private val ctx: Context,
    private val host: DocHost,
    docs: DocumentRepository,
    private val client: AiClient,
) {
    val items = mutableStateListOf<AgentMsg>()
    var busy by mutableStateOf(false); private set
    var running by mutableStateOf(false); private set
    var loadingDoc by mutableStateOf(false); private set

    private val agent = DocAgent(client)
    private val actions = PdfDocActions(ctx, host, docs)
    private val runner = DocCommandRunner(ctx, actions, client)
    private var docText: String? = null
    private var job: Job? = null
    private var seq = 0L
    private val backups = ArrayList<File>()

    private fun nextId() = ++seq

    fun clear() { stop(); items.clear() }

    fun stop() { job?.cancel(); job = null; busy = false }

    /** Forget the cached document text (after an edit). */
    fun invalidateDoc() { docText = null }

    private suspend fun document(): String {
        docText?.let { return it }
        loadingDoc = true
        try {
            val n = host.pageCount().coerceAtMost(MAX_PAGES)
            val pages = (0 until n).map { runCatching { host.pageText(it) }.getOrDefault("") }
            return AiClient.trimDocument(pages).also { docText = it }
        } finally { loadingDoc = false }
    }

    /** History for the model: executed / cancelled commands become short assistant notes. */
    private fun history(): List<ChatMessage> = items.mapNotNull { m ->
        when (m.kind) {
            AgentKind.USER -> ChatMessage("user", m.text)
            AgentKind.ASSISTANT -> ChatMessage("assistant", m.text)
            AgentKind.RESULT -> ChatMessage("assistant", "[" + ctx.getString(R.string.ai2_hist_done) + "] " + m.text)
            AgentKind.CONFIRM -> when (m.status) {
                ConfirmStatus.CANCELLED -> ChatMessage("assistant", "[" + ctx.getString(R.string.ai2_hist_cancelled) + "] " + m.text)
                ConfirmStatus.FAILED -> ChatMessage("assistant", "[" + ctx.getString(R.string.ai2_hist_failed) + "] " + m.text)
                else -> null
            }
            AgentKind.ERROR -> null
        }
    }.takeLast(MAX_HISTORY)

    fun send(text: String) {
        if (busy || text.isBlank()) return
        items += AgentMsg(nextId(), AgentKind.USER, text.trim())
        busy = true
        job = scope.launch {
            try {
                val doc = document()
                val reply = agent.turn(history(), doc, host.pageCount(), host.currentPage(), !host.selectedText().isNullOrBlank(), host.file()?.name ?: "")
                if (reply.text.isNotBlank()) items += AgentMsg(nextId(), AgentKind.ASSISTANT, reply.text)
                reply.commands.forEach { c ->
                    val confirmText = runCatching { runner.confirmText(c) }.getOrDefault(c.cmd.id)
                    if (c.cmd.confirm) items += AgentMsg(nextId(), AgentKind.CONFIRM, confirmText, c)
                    else execute(c, null)
                }
                if (reply.text.isBlank() && reply.commands.isEmpty()) items += AgentMsg(nextId(), AgentKind.ASSISTANT, "…")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                items += AgentMsg(nextId(), AgentKind.ERROR, e.message ?: e.toString())
            } finally { busy = false }
        }
    }

    /** "تنفيذ" on a confirmation card. */
    fun confirm(id: Long) {
        val i = items.indexOfFirst { it.id == id }; if (i < 0 || running) return
        val m = items[i]; val c = m.command ?: return
        if (m.status != ConfirmStatus.WAITING) return
        items[i] = m.copy(status = ConfirmStatus.RUNNING)
        scope.launch { execute(c, id) }
    }

    /** "إلغاء" on a confirmation card. */
    fun cancel(id: Long) {
        val i = items.indexOfFirst { it.id == id }; if (i < 0) return
        if (items[i].status == ConfirmStatus.WAITING) items[i] = items[i].copy(status = ConfirmStatus.CANCELLED)
    }

    private suspend fun execute(c: DocCommand, cardId: Long?) {
        running = true
        fun mark(st: ConfirmStatus) { cardId?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { items[it] = items[it].copy(status = st) } } }
        try {
            val r = runner.run(c)
            r.backup?.let { synchronized(backups) { backups += it } }
            if (r.backup != null) invalidateDoc()
            mark(ConfirmStatus.DONE)
            items += AgentMsg(nextId(), AgentKind.RESULT, r.message, c, ConfirmStatus.DONE, r)
        } catch (e: CancellationException) {
            mark(ConfirmStatus.CANCELLED); throw e
        } catch (e: Throwable) {
            mark(ConfirmStatus.FAILED)
            val msg = if (e is ActionError) e.message.orEmpty() else ctx.getString(R.string.ai2_err_failed, e.message ?: e.javaClass.simpleName)
            items += AgentMsg(nextId(), AgentKind.ERROR, msg)
        } finally { running = false }
    }

    val canUndo: Boolean get() = host.restore != null

    /** Put the version before a result back. */
    fun undo(id: Long) {
        val i = items.indexOfFirst { it.id == id }; if (i < 0 || running) return
        val m = items[i]; val r = m.result ?: return; val b = r.backup ?: return
        val restore = host.restore ?: return
        if (m.undone) return
        running = true
        scope.launch {
            try {
                withContext(NonCancellable) { restore(b, r.prevPassword) }
                items[i] = m.copy(undone = true)
                invalidateDoc()
                items += AgentMsg(nextId(), AgentKind.RESULT, ctx.getString(R.string.ai2_undone))
            } catch (e: Throwable) {
                items += AgentMsg(nextId(), AgentKind.ERROR, ctx.getString(R.string.ai2_err_failed, e.message ?: ""))
            } finally { running = false }
        }
    }

    fun openFile(f: File) = host.openFile(f)

    fun shareFile(f: File) = host.share(listOf(f), com.smartdocs.editor.ui.common.mimeFor(f.extension))

    fun dispose() {
        stop()
        val list = synchronized(backups) { backups.toList().also { backups.clear() } }
        CoroutineScope(Dispatchers.IO).launch { list.forEach { runCatching { it.delete() } } }
    }

    companion object {
        const val MAX_PAGES = 400
        const val MAX_HISTORY = 16
    }
}
