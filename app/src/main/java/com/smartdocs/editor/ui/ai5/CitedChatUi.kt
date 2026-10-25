package com.smartdocs.editor.ui.ai5

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.AnswerPart
import com.smartdocs.editor.core.ai5.CitedAnswer
import com.smartdocs.editor.core.ai5.Citation

/** Answer text with superscript, clickable citation numbers. */
@Composable
fun AnswerText(answer: CitedAnswer, onCitation: (Citation) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val text = remember(answer) {
        buildAnnotatedString {
            answer.parts.forEach { p ->
                when (p) {
                    is AnswerPart.Text -> append(p.text)
                    is AnswerPart.Cite -> {
                        val c = answer.citation(p.n)
                        if (c == null) return@forEach
                        withLink(LinkAnnotation.Clickable("cite-${p.n}", TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.Bold)),
                            LinkInteractionListener { onCitation(c) })) {
                            withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 11.sp)) { append("[${p.n}]") }
                        }
                    }
                }
            }
        }
    }
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)) }
}

/** Source chips under an answer: "[2] ص 14" (with the file name when several documents are involved). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceChips(answer: CitedAnswer, onCitation: (Citation) -> Unit) {
    if (answer.citations.isEmpty()) return
    val multi = answer.citations.map { it.docKey }.distinct().size > 1
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        answer.citations.forEach { c ->
            val label = "[${c.n}] " + (if (multi) c.fileName.substringBeforeLast('.').take(18) + " · " else "") + stringResource(R.string.a5_page_n, c.page + 1)
            AssistChip(onClick = { onCitation(c) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(14.dp)) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionRow(items: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    if (items.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { s -> SuggestionChip(onClick = { onPick(s) }, enabled = enabled, label = { Text(s, style = MaterialTheme.typography.labelMedium, maxLines = 2) }) }
    }
}

@Composable
fun CitedMessageList(chat: CitedChatState, onCitation: (Citation) -> Unit, onShare: (String) -> Unit, onExportPdf: (String) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.text?.length) { if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.size - 1) }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (chat.messages.isEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.a5_chat_hello), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (chat.startersLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (chat.starters.isNotEmpty()) {
                    Text(stringResource(R.string.a5_suggested), style = MaterialTheme.typography.labelLarge)
                    SuggestionRow(chat.starters, !chat.busy) { chat.send(it) }
                }
            }
        }
        items(chat.messages, key = { it.id }) { m ->
            val user = m.role == "user"
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                Surface(shape = RoundedCornerShape(16.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 620.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when {
                            m.error -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(m.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            m.streaming && m.text.isBlank() -> Text(stringResource(R.string.a5_thinking), style = MaterialTheme.typography.bodyMedium)
                            m.answer != null -> AnswerText(m.answer, onCitation)
                            else -> SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)) }
                        }
                        val a = m.answer
                        if (!user && a != null && !m.streaming) {
                            SourceChips(a, onCitation)
                            Row(Modifier.align(Alignment.End)) {
                                val plain = a.plainWithSources()
                                IconButton(onClick = { clipboard.setText(AnnotatedString(plain)) }, Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, stringResource(R.string.a5_copy), Modifier.size(16.dp)) }
                                IconButton(onClick = { onShare(plain) }, Modifier.size(32.dp)) { Icon(Icons.Default.Share, stringResource(R.string.a5_share), Modifier.size(16.dp)) }
                                IconButton(onClick = { onExportPdf(plain) }, Modifier.size(32.dp)) { Icon(Icons.Default.Description, stringResource(R.string.a5_export_pdf), Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
                val a = m.answer
                if (!user && a != null && a.followUps.isNotEmpty() && m.id == chat.messages.lastOrNull()?.id) {
                    Spacer(Modifier.height(6.dp))
                    SuggestionRow(a.followUps, !chat.busy) { chat.send(it) }
                }
            }
        }
    }
}

/** Input row: text field, dictation mic, voice-chat button, send/stop. */
@Composable
fun CitedChatInput(enabled: Boolean, busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, onVoiceChat: (() -> Unit)?) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MicButton(enabled = enabled && !busy, onText = { t -> text = if (text.isBlank()) t else "$text $t" })
        if (onVoiceChat != null) IconButton(onClick = onVoiceChat, enabled = enabled && !busy) { Icon(Icons.Default.RecordVoiceOver, stringResource(R.string.a5_voice_chat)) }
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.a5_ask_doc)) }, enabled = enabled, maxLines = 4, shape = RoundedCornerShape(24.dp))
        Spacer(Modifier.width(6.dp))
        if (busy) FilledIconButton(onClick = onStop) { Icon(Icons.Default.Stop, stringResource(R.string.a5_stop)) }
        else FilledIconButton(onClick = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }, enabled = enabled && text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.a5_send))
        }
    }
}

/** Saved chats of the current scope (open / delete) + "new chat" + temporary toggle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistorySheet(chat: CitedChatState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(chat.savedChats()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.a5_history), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(chat.temporary, { chat.temporary = it }); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.a5_temporary_chat), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { chat.newChat(); onDismiss() }) { Text(stringResource(R.string.a5_new_chat)) }
            if (list.isEmpty()) Text(stringResource(R.string.a5_no_history), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.title.ifBlank { "…" }, maxLines = 1) },
                        supportingContent = { Text(android.text.format.DateUtils.getRelativeTimeSpanString(c.updated).toString() + " · " + ctx.getString(R.string.a5_n_messages, c.messages.size)) },
                        trailingContent = { TextButton(onClick = { chat.delete(c.id); list = chat.savedChats() }) { Text(stringResource(R.string.a5_delete)) } },
                        modifier = Modifier.fillMaxWidth().then(Modifier),
                    )
                    TextButton(onClick = { chat.open(c); onDismiss() }) { Text(stringResource(R.string.a5_open_chat)) }
                }
            }
        }
    }
}
