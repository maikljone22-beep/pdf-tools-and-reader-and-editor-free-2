package com.smartdocs.editor.ui.ai5

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.AiStore
import com.smartdocs.editor.core.ai5.Citation
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class HubTab { CHAT, SUMMARY, STUDY, TOOLS, AGENT }

/** Open the reader at a citation's page (the navigation every citation click maps to). */
fun citationRoute(c: Citation): String = Routes.reader(Uri.fromFile(File(c.filePath)).toString(), c.page)
fun pageRoute(filePath: String, page: Int): String = Routes.reader(Uri.fromFile(File(filePath)).toString(), page)

fun shareText(ctx: Context, text: String) {
    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
}

/** Write [text] to a PDF in the documents folder and open the share sheet. */
suspend fun exportPdfAndShare(ctx: Context, docs: DocumentRepository, title: String, text: String) {
    // called from scope.launch in the UI: a failing write (font, storage) must not crash the app
    val out = runCatching {
        withContext(Dispatchers.IO) {
            val f = docs.uniqueFile(docs.docsDir, title.substringBeforeLast('.').ifBlank { "ai" }.take(40) + "_ai")
            AnswerPdf.write(ctx, title, text, f); f
        }
    }.getOrElse { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.widget.Toast.makeText(ctx, ctx.getString(com.smartdocs.editor.R.string.error_generic), android.widget.Toast.LENGTH_LONG).show()
        return
    }
    runCatching { ctx.startActivity(Intent.createChooser(docs.shareIntent(listOf(out), "application/pdf"), null)) }
}

/**
 * AI hub (route `tool/ai`). [uri] is a `file://` URI, `space:<id>` for a Space, optionally followed by
 * `#tab=chat|summary|study|tools|agent`. Tabs: Chat (cited, whole document) · Summary · Study · Tools · Agent.
 */
@Composable
fun AiHubScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val store = remember { AiStore(ctx) }
    val raw = uri.orEmpty()
    val tabArg = raw.substringAfter("#tab=", "").lowercase()
    val base = raw.substringBefore('#')
    val space = remember(base) { if (base.startsWith("space:")) store.space(base.removePrefix("space:")) else null }
    var files by remember(base) {
        mutableStateOf(space?.files?.map(::File)?.filter { it.exists() }
            ?: base.takeIf { it.isNotBlank() }?.let { runCatching { listOf(File(Uri.parse(it).path!!)) }.getOrNull() }.orEmpty())
    }
    var tab by remember { mutableStateOf(HubTab.entries.firstOrNull { it.name.lowercase() == tabArg } ?: HubTab.CHAT) }
    val pick = rememberPdfPicker { picked -> picked.firstOrNull()?.let { files = listOf(it) } }
    val docs = rememberDocSet(ctx, files, space?.id, space?.persona.orEmpty())
    val chat = remember(docs) { CitedChatState(scope, ctx.applicationContext, client, docs) }
    var history by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf(false) }
    val hasAi = AiClient.available(settings)
    LaunchedEffect(docs.ready) { if (docs.ready && hasAi) chat.loadStarters() }
    val onCitation: (Citation) -> Unit = { c -> nav.navigate(citationRoute(c)) }
    val onPage: (String, Int) -> Unit = { p, pg -> if (p.isNotBlank()) nav.navigate(pageRoute(p, pg)) }
    val onShare: (String) -> Unit = { shareText(ctx, it) }
    val onPdf: (String) -> Unit = { t -> scope.launch { exportPdfAndShare(ctx, app.documents, docs.title, t) } }

    Scaffold(topBar = {
        AppTopBar(space?.name ?: stringResource(R.string.a5_hub_title), onBack = { nav.popBackStack() }) {
            if (tab == HubTab.CHAT) IconButton(onClick = { history = true }) { Icon(Icons.Default.History, stringResource(R.string.a5_history)) }
            IconButton(onClick = { nav.navigate(Routes.tool("ai_spaces")) }) { Icon(Icons.Default.Workspaces, stringResource(R.string.a5_spaces)) }
            IconButton(onClick = { nav.navigate(Routes.tool("ai_settings")) }) { Icon(Icons.Default.Settings, stringResource(R.string.a5_settings)) }
        }
    }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val wide = maxWidth > 700.dp
            val inner = if (wide) Modifier.padding(horizontal = 48.dp) else Modifier
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp, 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (space == null) FilePickRow(files.firstOrNull(), stringResource(R.string.a5_pick_pdf), pick)
                    else Text(stringResource(R.string.a5_space_files, files.size), style = MaterialTheme.typography.bodySmall)
                    if (!hasAi) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.a5_no_ai), Modifier.weight(1f))
                            TextButton(onClick = { nav.navigate(Routes.tool("ai_settings")) }) { Text(stringResource(R.string.a5_settings)) }
                        }
                    }
                    if (docs.loading) Column {
                        Text(stringResource(R.string.a5_indexing), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { docs.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    docs.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (client.viaProxy) Text(stringResource(R.string.a5_quota_left, AiCalls.remaining(ctx, client)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 12.dp) {
                    HubTab.entries.forEach { t ->
                        Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(when (t) {
                            HubTab.CHAT -> R.string.a5_tab_chat; HubTab.SUMMARY -> R.string.a5_tab_summary; HubTab.STUDY -> R.string.a5_tab_study
                            HubTab.TOOLS -> R.string.a5_tab_tools; HubTab.AGENT -> R.string.a5_tab_agent })) })
                    }
                }
                val body = Modifier.weight(1f).fillMaxWidth().then(inner)
                when (tab) {
                    HubTab.CHAT -> Column(body) {
                        CitedMessageList(chat, onCitation, onShare, onPdf, Modifier.weight(1f).fillMaxWidth())
                        CitedChatInput(enabled = hasAi && docs.ready, busy = chat.busy, onSend = { chat.send(it) }, onStop = { chat.stop() }, onVoiceChat = { voice = true })
                    }
                    HubTab.SUMMARY -> SummaryPanel(docs, client, onPage, onAsk = { q -> tab = HubTab.CHAT; chat.send(q) }, onShare, onPdf, body)
                    HubTab.STUDY -> StudyPanel(docs, client, onPage, onShare, onPdf, body)
                    HubTab.TOOLS -> AiToolsPanel(nav, files.singleOrNull(), body)
                    HubTab.AGENT -> {
                        val f = files.singleOrNull()
                        if (f == null) Text(stringResource(R.string.a5_agent_one_file), Modifier.padding(16.dp))
                        else {
                            val host = remember(f) { com.smartdocs.editor.core.ai.FileDocHostState(ctx.applicationContext, app.documents, f) }
                            LaunchedEffect(host) { host.refresh() }
                            val docHost = remember(host) { host.host(openFile = { o -> nav.navigate(Routes.reader(Uri.fromFile(o).toString())) },
                                share = { l, mime -> runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(l, mime), null)) } }) }
                            com.smartdocs.editor.ui.ai.DocAgentPanel(docHost, body)
                        }
                    }
                }
            }
        }
    }
    if (history) ChatHistorySheet(chat) { history = false }
    if (voice) VoiceChatDialog(busy = chat.busy, onAsk = { chat.send(it) }, latestAnswer = { chat.lastAnswer()?.answer?.text ?: chat.lastAnswer()?.text }, onDismiss = { voice = false })
}
