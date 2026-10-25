package com.smartdocs.editor.ui.ai

import androidx.compose.material.icons.filled.Headphones
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.AnswerPdf
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen AI assistant: pick a PDF, chat / quick actions, generate outline, export answer as PDF. */
@Composable
fun AiScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val chat = rememberAiChatState()
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()
    val listState = rememberLazyListState()
    var file by remember { mutableStateOf(uri?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull() }) }
    var docText by remember { mutableStateOf<String?>(null) }
    var loadingDoc by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showResult by remember { mutableStateOf(false) }
    var resultInfo by remember { mutableStateOf<String?>(null) }
    val hasKey = com.smartdocs.editor.core.ai.AiClient.available(settings)
    val pick = rememberPdfPicker { files -> files.firstOrNull()?.let { file = it; docText = null } }
    val processingMsg = stringResource(R.string.processing)
    val outlineFailed = stringResource(R.string.sa_ai_outline_failed)
    val outlineApplied = stringResource(R.string.sa_ai_outline_applied)
    val outlineItemsFmt = stringResource(R.string.sa_ai_outline_items)
    val labels = QuickAction.entries.associateWith { stringResource(it.label) }
    var agentMode by remember { mutableStateOf(false) }
    val fileHost = remember(file) { file?.let { com.smartdocs.editor.core.ai.FileDocHostState(ctx.applicationContext, app.documents, it) } }
    LaunchedEffect(fileHost) { fileHost?.refresh() }
    val docHost = remember(fileHost) {
        fileHost?.host(
            openFile = { f -> nav.navigate(Routes.reader(Uri.fromFile(f).toString())) },
            share = { files, mime -> runCatching { ctx.startActivity(android.content.Intent.createChooser(app.documents.shareIntent(files, mime), null)) } },
        )
    }

    // Load the document text (with page markers) whenever the file changes.
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        loadingDoc = true; loadError = null
        runCatching {
            withContext(Dispatchers.IO) {
                val s = PdfSession.open(ctx, f)
                try { AiClient.trimDocument(s.allText()) } finally { s.closeAsync() }
            }
        }.onSuccess { docText = it }.onFailure { loadError = it.message ?: it.toString() }
        loadingDoc = false
    }
    LaunchedEffect(runner.result) { if (runner.result != null) showResult = true }

    fun generateOutline() {
        val f = file ?: return; val text = docText ?: return
        runner.run(processingMsg) {
            val items = client.outline(text)
            if (items.isEmpty()) throw IllegalStateException(outlineFailed)
            val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_outline")
            PdfOps.setOutline(f, items.map { Triple(it.title, (it.page - 1).coerceAtLeast(0), it.level) }, out)
            resultInfo = outlineApplied + " — " + outlineItemsFmt.format(items.size)
            out
        }
    }

    fun exportAnswer() {
        val last = chat.messages.lastOrNull { it.role == "assistant" && !it.streaming && it.content.isNotBlank() } ?: return
        runner.run(processingMsg) {
            val base = (file?.nameWithoutExtension ?: "ai") + "_answer"
            val out = app.documents.uniqueFile(app.documents.docsDir, base)
            AnswerPdf.write(ctx, file?.name ?: "", last.content, out)
            resultInfo = null
            out
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.ai_title), onBack = { nav.popBackStack() }) {
                IconButton(onClick = { chat.clear() }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.sa_ai_clear_chat)) }
                IconButton(onClick = { nav.navigate(Routes.tool("ai_settings")) }) { Icon(Icons.Default.Settings, stringResource(R.string.ai_setup)) }
            }
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val wide = maxWidth > 700.dp
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilePickRow(file, stringResource(R.string.open_file), pick)
                    if (!hasKey) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.ai_no_key))
                            TextButton(onClick = { nav.navigate(Routes.tool("ai_settings")) }) { Text(stringResource(R.string.ai_setup)) }
                        }
                    }
                    if (loadingDoc) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sa_ai_loading_doc), style = MaterialTheme.typography.bodySmall) }
                    loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (file == null) Text(stringResource(R.string.sa_ai_no_doc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !agentMode, onClick = { agentMode = false }, label = { Text(stringResource(R.string.ai2_tab_chat)) })
                    FilterChip(selected = agentMode, onClick = { agentMode = true }, label = { Text(stringResource(R.string.ai2_tab_agent)) }, enabled = file != null)
                    AssistChip(onClick = { nav.navigate(Routes.tool("podcast", file?.let { Uri.fromFile(it).toString() })) }, label = { Text(stringResource(R.string.ai2_podcast_title)) },
                        leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Headphones, null, Modifier.size(18.dp)) })
                }
                if (agentMode && docHost != null) {
                    DocAgentPanel(docHost, Modifier.weight(1f).fillMaxWidth().then(if (wide) Modifier.padding(horizontal = 48.dp) else Modifier))
                } else {
                val ready = hasKey && docText != null && !chat.busy && !runner.running
                QuickActionChips(enabled = ready, onAction = { a -> docText?.let { chat.action(client, a, it, labels[a] ?: a.name) } }) {
                    AssistChip(onClick = { generateOutline() }, label = { Text(stringResource(R.string.sa_ai_outline)) }, leadingIcon = { Icon(Icons.Default.Toc, null, Modifier.size(18.dp)) }, enabled = ready)
                    AssistChip(onClick = { exportAnswer() }, label = { Text(stringResource(R.string.sa_ai_export_pdf)) }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp)) }, enabled = !runner.running && chat.messages.any { it.role == "assistant" && !it.streaming })
                }
                chat.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                MessageList(chat.messages, listState, Modifier.weight(1f).fillMaxWidth().then(if (wide) Modifier.padding(horizontal = 48.dp) else Modifier))
                ChatInput(enabled = hasKey, busy = chat.busy, onSend = { q -> scope.launch { chat.send(client, q, docText) } }, onStop = { chat.stop() })
                }
            }
        }
    }
    if (runner.running) ProgressDialog(runner.message)
    runner.error?.let { err ->
        AlertDialog(onDismissRequest = { runner.error = null }, title = { Text(stringResource(R.string.error_generic)) }, text = { Text(err) }, confirmButton = { TextButton(onClick = { runner.error = null }) { Text(stringResource(R.string.ok)) } })
    }
    if (showResult) runner.result?.let { ResultSheet(it, nav, onDismiss = { showResult = false; runner.result = null }, extraInfo = resultInfo) }
}
