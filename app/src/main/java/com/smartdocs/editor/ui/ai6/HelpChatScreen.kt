package com.smartdocs.editor.ui.ai6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai6.AiMedia
import com.smartdocs.editor.ui.common.AppTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Help-chat knowledge: the offline help articles and the FAQ, as plain text for the system prompt. */
object HelpKnowledge {
    val FAQ = listOf(
        R.string.ac_faq1_q to R.string.ac_faq1_a, R.string.ac_faq2_q to R.string.ac_faq2_a,
        R.string.ac_faq3_q to R.string.ac_faq3_a, R.string.ac_faq4_q to R.string.ac_faq4_a,
        R.string.ac_faq5_q to R.string.ac_faq5_a, R.string.ac_faq6_q to R.string.ac_faq6_a,
        R.string.ac_faq7_q to R.string.ac_faq7_a, R.string.ac_faq8_q to R.string.ac_faq8_a,
    )

    fun text(ctx: android.content.Context): String = buildString {
        (com.smartdocs.editor.ui.view5.helpArticles + FAQ).forEach { (t, b) ->
            append("## ").append(ctx.getString(t)).append('\n').append(ctx.getString(b)).append("\n\n")
        }
    }.take(14_000)

    fun system(knowledge: String): String =
        "أنت مساعد دعم لتطبيق SmartDocs (قارئ ومحرر PDF ومستندات أوفيس على أندرويد). أجب عن أسئلة استخدام التطبيق فقط، " +
            "بخطوات قصيرة مرقمة، بلغة سؤال المستخدم. اعتمد على مقالات المساعدة التالية؛ إن لم تجد الجواب فيها فقل ذلك واقترح " +
            "«المساعدة ← تواصل معنا». لا تخترع أزراراً أو قوائم غير مذكورة.\n\n" + knowledge
}

/** Route `tool/ai_help` (§41 13.1 support chatbot): questions about the app, answered from the help centre and FAQ. */
@Composable
fun HelpChatScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val knowledge = remember { HelpKnowledge.text(ctx) }
    val msgs = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.content?.length) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    val hasAi = settings.aiEnabled && AiClient.available(settings)

    fun ask(q: String) {
        if (q.isBlank() || busy) return
        val history = msgs.toList()
        msgs += ChatMessage("user", q.trim()); msgs += ChatMessage("assistant", "")
        busy = true
        job = scope.launch {
            val sb = StringBuilder()
            try {
                AiCalls.stream(ctx, client, listOf(ChatMessage("system", HelpKnowledge.system(knowledge))) + history.takeLast(8) + ChatMessage("user", q.trim())).collect { t ->
                    sb.append(t); msgs[msgs.lastIndex] = ChatMessage("assistant", sb.toString())
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { msgs[msgs.lastIndex] = ChatMessage("assistant", sb.toString().ifBlank { AiMedia.userMessage(ctx, e) })
            } finally { busy = false }
        }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.ai6h_help_chat), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!hasAi) Text(stringResource(if (!settings.aiEnabled) R.string.ai6_off else R.string.a5_no_ai), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (msgs.isEmpty()) item {
                    Text(stringResource(R.string.ai6h_help_hello), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    listOf(R.string.ai6h_help_q1, R.string.ai6h_help_q2, R.string.ai6h_help_q3).forEach { r ->
                        val q = stringResource(r)
                        SuggestionChip(onClick = { ask(q) }, enabled = hasAi, label = { Text(q) })
                    }
                }
                items(msgs.size) { i ->
                    val m = msgs[i]
                    val user = m.role == "user"
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                        Surface(shape = RoundedCornerShape(16.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 620.dp)) {
                            SelectionContainer {
                                Text(m.content.ifBlank { stringResource(R.string.a5_thinking) }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), enabled = hasAi, maxLines = 4, shape = RoundedCornerShape(24.dp),
                    placeholder = { Text(stringResource(R.string.ai6h_help_ask)) })
                Spacer(Modifier.width(6.dp))
                if (busy) FilledIconButton(onClick = { job?.cancel(); busy = false }) { Icon(Icons.Default.Stop, stringResource(R.string.a5_stop)) }
                else FilledIconButton(onClick = { val q = input; input = ""; ask(q) }, enabled = hasAi && input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.a5_send)) }
            }
        }
    }
}
