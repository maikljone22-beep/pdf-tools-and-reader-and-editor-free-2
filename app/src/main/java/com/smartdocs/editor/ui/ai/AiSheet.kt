package com.smartdocs.editor.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import kotlinx.coroutines.launch

/**
 * Chat bottom sheet used from the reader. [docText] is loaded lazily (already trimmed with page markers or raw);
 * [selectedText], when present, is the target of quick actions instead of the whole document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSheet(docText: suspend () -> String, selectedText: String?, onDismiss: () -> Unit) {
    val settings = LocalSettings.current
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val chat = rememberAiChatState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var doc by remember { mutableStateOf<String?>(null) }
    var loadingDoc by remember { mutableStateOf(false) }
    val hasKey = com.smartdocs.editor.core.ai.AiClient.available(settings)

    suspend fun document(): String {
        doc?.let { return it }
        loadingDoc = true
        val t = runCatching { docText() }.getOrDefault("")
        val trimmed = if (t.length > AiClient.MAX_DOC_CHARS) AiClient.trimDocument(t.split(Regex("(?=\\[صفحة \\d+\\])"))) else t
        doc = trimmed; loadingDoc = false
        return trimmed
    }

    fun act(a: QuickAction, label: String) {
        scope.launch {
            val target = selectedText?.takeIf { it.isNotBlank() } ?: document()
            if (target.isBlank()) return@launch
            chat.action(client, a, target, label)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { chat.clear() }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.sa_ai_clear_chat)) }
            }
            if (!hasKey) {
                Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(stringResource(R.string.ai_no_key), Modifier.padding(16.dp))
                }
            }
            Text(
                if (!selectedText.isNullOrBlank()) "${stringResource(R.string.sa_ai_selected_text)}: ${selectedText.take(60)}…" else stringResource(R.string.sa_ai_whole_doc),
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            val labels = QuickAction.entries.associateWith { stringResource(it.label) }
            QuickActionChips(enabled = hasKey && !chat.busy, onAction = { a -> act(a, labels[a] ?: a.name) })
            if (loadingDoc) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            chat.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
            MessageList(chat.messages, listState, Modifier.weight(1f).fillMaxWidth())
            ChatInput(enabled = hasKey, busy = chat.busy, onSend = { q -> scope.launch { chat.send(client, q, document()) } }, onStop = { chat.stop() })
        }
    }
}
