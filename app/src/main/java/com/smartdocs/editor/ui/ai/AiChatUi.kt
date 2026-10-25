package com.smartdocs.editor.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** A message in the chat UI. */
data class UiMessage(val role: String, val content: String, val streaming: Boolean = false)

enum class QuickAction(val label: Int) {
    SUMMARIZE(R.string.ai_summarize), EXPLAIN(R.string.ai_explain), TRANSLATE_AR(R.string.sa_ai_translate_ar),
    TRANSLATE_EN(R.string.sa_ai_translate_en), QUESTIONS(R.string.ai_questions), DEFINE(R.string.sa_ai_define),
}

/** Chat state + streaming driver shared by the sheet and the full screen. */
class AiChatState(private val scope: CoroutineScope) {
    val messages = mutableStateListOf<UiMessage>()
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null

    fun clear() { stop(); messages.clear(); error = null }

    fun stop() {
        job?.cancel(); job = null
        val last = messages.lastOrNull()
        if (last != null && last.streaming) messages[messages.size - 1] = last.copy(streaming = false)
        busy = false
    }

    /** Append a user bubble (optional) and stream the assistant answer from [flow]. */
    fun run(userText: String?, flow: Flow<String>) {
        if (busy) return
        error = null
        if (userText != null) messages += UiMessage("user", userText)
        messages += UiMessage("assistant", "", streaming = true)
        val idx = messages.size - 1
        busy = true
        job = scope.launch {
            try {
                val sb = StringBuilder()
                flow.collect { token -> sb.append(token); messages[idx] = UiMessage("assistant", sb.toString(), streaming = true) }
                messages[idx] = UiMessage("assistant", sb.toString().ifBlank { "…" })
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
                messages[idx] = UiMessage("assistant", messages[idx].content.ifBlank { "⚠ ${error}" })
            } finally { busy = false }
        }
    }

    /** Send a chat turn with the whole history as context. */
    fun send(client: AiClient, text: String, docContext: String?) {
        val history = messages.filter { !it.streaming }.map { ChatMessage(it.role, it.content) } + ChatMessage("user", text)
        run(text, client.chat(history, docContext))
    }

    /** Run a quick action on [target] text. */
    fun action(client: AiClient, a: QuickAction, target: String, label: String) {
        val flow = when (a) {
            QuickAction.SUMMARIZE -> client.summarize(target)
            QuickAction.EXPLAIN -> client.explain(target)
            QuickAction.TRANSLATE_AR -> client.translate(target, "العربية")
            QuickAction.TRANSLATE_EN -> client.translate(target, "الإنجليزية")
            QuickAction.QUESTIONS -> client.quizQuestions(target)
            QuickAction.DEFINE -> client.define(target)
        }
        run(label, flow)
    }
}

@Composable
fun rememberAiChatState(): AiChatState { val scope = rememberCoroutineScope(); return remember { AiChatState(scope) } }

@Composable
fun QuickActionChips(enabled: Boolean, onAction: (QuickAction) -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction.entries.forEach { a -> AssistChip(onClick = { onAction(a) }, label = { Text(stringResource(a.label)) }, enabled = enabled) }
        extra()
    }
}

@Composable
fun MessageList(messages: List<UiMessage>, listState: LazyListState, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (messages.isEmpty()) item { Text(stringResource(R.string.ai_hello), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
        itemsIndexed(messages) { _, m ->
            val isUser = m.role == "user"
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 560.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(if (isUser) R.string.sa_ai_you else R.string.sa_ai_assistant), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (m.streaming && m.content.isBlank()) Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodyMedium)
                        else SelectionContainerText(m.content)
                        if (!isUser && !m.streaming && m.content.isNotBlank()) {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(m.content)) }, Modifier.size(32.dp).align(Alignment.End)) {
                                Icon(Icons.Default.ContentCopy, stringResource(R.string.copy_text), Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionContainerText(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
fun ChatInput(enabled: Boolean, busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.ai_ask)) }, enabled = enabled, maxLines = 4, shape = RoundedCornerShape(24.dp))
        Spacer(Modifier.width(8.dp))
        if (busy) FilledIconButton(onClick = onStop) { Icon(Icons.Default.Stop, stringResource(R.string.sa_ai_stop)) }
        else FilledIconButton(onClick = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }, enabled = enabled && text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.sa_ai_send)) }
    }
}
