package com.smartdocs.editor.ui.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.DocHost

/** Remember an [AgentChatState] bound to [host]; backups are deleted when it leaves the composition. */
@Composable
fun rememberAgentChatState(host: DocHost): AgentChatState {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val state = remember(host, client) { AgentChatState(scope, ctx.applicationContext, host, app.documents, client) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

/**
 * Reader bottom sheet with the document agent ("المساعد الذكي" that can also edit the document).
 * [onPodcast] (optional) opens the audio summary / podcast screen for the open file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocAgentSheet(host: DocHost, onDismiss: () -> Unit, initialPrompt: String? = null, onPodcast: (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DocAgentPanel(host, Modifier.fillMaxWidth().fillMaxHeight(0.92f), initialPrompt, onPodcast)
    }
}

/** The agent chat as a plain panel (tablet side panel or inside [DocAgentSheet]). */
@Composable
fun DocAgentPanel(host: DocHost, modifier: Modifier = Modifier, initialPrompt: String? = null, onPodcast: (() -> Unit)? = null) {
    val settings = LocalSettings.current
    val chat = rememberAgentChatState(host)
    val hasKey = AiClient.available(settings)
    LaunchedEffect(initialPrompt) { if (!initialPrompt.isNullOrBlank() && hasKey && chat.items.isEmpty()) chat.send(initialPrompt) }

    Column(modifier) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.ai2_agent_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onPodcast != null) IconButton(onClick = onPodcast) { Icon(Icons.Default.Headphones, stringResource(R.string.ai2_podcast_title)) }
            IconButton(onClick = { chat.clear() }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.sa_ai_clear_chat)) }
        }
        if (!hasKey) Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(stringResource(R.string.ai_no_key), Modifier.padding(16.dp))
        }
        val suggestions = listOf(
            R.string.ai2_sg_summary, R.string.ai2_sg_note, R.string.ai2_sg_delete, R.string.ai2_sg_rotate, R.string.ai2_sg_word, R.string.ai2_sg_numbers, R.string.ai2_sg_compress,
        ).map { stringResource(it) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { sg -> SuggestionChip(onClick = { chat.send(sg) }, label = { Text(sg) }, enabled = hasKey && !chat.busy && !chat.running) }
        }
        if (chat.loadingDoc || chat.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        AgentMessageList(chat, Modifier.weight(1f).fillMaxWidth())
        ChatInput(enabled = hasKey && !chat.running, busy = chat.busy, onSend = { chat.send(it) }, onStop = { chat.stop() })
    }
}

@Composable
fun AgentMessageList(chat: AgentChatState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(chat.items.size, chat.busy) { val n = chat.items.size + (if (chat.busy) 1 else 0); if (n > 0) listState.animateScrollToItem(n - 1) }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chat.items.isEmpty()) item {
            Text(stringResource(R.string.ai2_agent_hello), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        items(chat.items, key = { it.id }) { m ->
            when (m.kind) {
                AgentKind.USER, AgentKind.ASSISTANT -> Bubble(m.text, m.kind == AgentKind.USER)
                AgentKind.CONFIRM -> ConfirmCard(m, onRun = { chat.confirm(m.id) }, onCancel = { chat.cancel(m.id) }, enabled = !chat.running)
                AgentKind.RESULT -> ResultCard(m, chat)
                AgentKind.ERROR -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(m.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (chat.busy) item { Text(stringResource(R.string.ai_thinking), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Bubble(text: String, isUser: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 560.dp)) {
            SelectionContainer {
                Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
            }
        }
    }
}

/** "Would you like me to …?" card: تنفيذ / إلغاء, then the status. */
@Composable
private fun ConfirmCard(m: AgentMsg, onRun: () -> Unit, onCancel: () -> Unit, enabled: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.HelpOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(m.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            when (m.status) {
                ConfirmStatus.WAITING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.ai2_cancel)) }
                    Button(onClick = onRun, enabled = enabled) { Text(stringResource(R.string.ai2_run)) }
                }
                ConfirmStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai2_running), style = MaterialTheme.typography.bodySmall)
                }
                ConfirmStatus.DONE -> Text(stringResource(R.string.ai2_status_done), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                ConfirmStatus.CANCELLED -> Text(stringResource(R.string.ai2_status_cancelled), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ConfirmStatus.FAILED -> Text(stringResource(R.string.ai2_status_failed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ResultCard(m: AgentMsg, chat: AgentChatState) {
    val r = m.result
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                SelectionContainer(Modifier.weight(1f)) { Text(m.text, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)) }
            }
            if (r != null && (r.files.isNotEmpty() || (r.backup != null && chat.canUndo))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    r.files.firstOrNull()?.let { f ->
                        if (f.extension.equals("pdf", true)) TextButton(onClick = { chat.openFile(f) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ai2_open))
                        }
                        TextButton(onClick = { chat.shareFile(f) }) { Icon(Icons.Default.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ai2_share)) }
                    }
                    if (r.backup != null && chat.canUndo) TextButton(onClick = { chat.undo(m.id) }, enabled = !m.undone && !chat.running) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(if (m.undone) R.string.ai2_undone_short else R.string.ai2_undo))
                    }
                }
            }
        }
    }
}
