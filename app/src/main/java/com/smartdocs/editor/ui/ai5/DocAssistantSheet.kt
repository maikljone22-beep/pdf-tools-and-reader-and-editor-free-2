package com.smartdocs.editor.ui.ai5

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.DocHost
import com.smartdocs.editor.core.ai5.Citation
import kotlinx.coroutines.launch
import java.io.File

/**
 * Reader AI sheet: "اسأل المستند" (cited answers over the whole document; a citation calls [onCitation] with a
 * 0-based page) and the existing agent tab (edits with confirmation). Reuses the open session's page text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocAssistantSheet(
    host: DocHost, file: File, password: String?, pageCount: Int, pageText: suspend (Int) -> String,
    onCitation: (Citation) -> Unit, onDismiss: () -> Unit, initialQuestion: String? = null, onPodcast: (() -> Unit)? = null,
    currentPage: Int = 0,   // ai6: default page of the "page snapshot" attachment
) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val docs = rememberDocSet(ctx, listOf(file), null, "", pageText, pageCount, password)
    val chat = remember(docs) { CitedChatState(scope, ctx.applicationContext, client, docs) }
    var tab by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf(false) }
    val hasAi = AiClient.available(settings)
    var asked by remember { mutableStateOf(false) }
    var textCitation by remember { mutableStateOf<Citation?>(null) }
    LaunchedEffect(docs.ready) {
        if (!docs.ready || !hasAi) return@LaunchedEffect
        if (!initialQuestion.isNullOrBlank() && !asked) { asked = true; chat.send(initialQuestion) } else chat.loadStarters()
    }
    // ai6 (§35 adjustable panel width): on wide screens the sheet width cycles narrow / medium / wide (remembered)
    val wideScreen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp > 700
    val prefs = remember { ctx.getSharedPreferences("ai6_ui", android.content.Context.MODE_PRIVATE) }
    var widthStep by remember { mutableStateOf(prefs.getInt("assistant_width", 1).coerceIn(0, 2)) }
    val sheetWidth = if (wideScreen) listOf(480.dp, 640.dp, 960.dp)[widthStep] else BottomSheetDefaults.SheetMaxWidth
    ModalBottomSheet(onDismissRequest = { chat.stop(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = sheetWidth) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.a5_hub_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (wideScreen) IconButton(onClick = { widthStep = (widthStep + 1) % 3; prefs.edit().putInt("assistant_width", widthStep).apply() }) {
                    Icon(Icons.Default.SwapHoriz, stringResource(R.string.ai6h_panel_width))
                }
                if (tab == 0) IconButton(onClick = { history = true }) { Icon(Icons.Default.History, stringResource(R.string.a5_history)) }
                if (onPodcast != null) IconButton(onClick = onPodcast) { Icon(Icons.Default.Headphones, stringResource(R.string.a5_podcast)) }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.a5_tab_ask)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.a5_tab_agent)) })
            }
            if (!hasAi) Text(stringResource(R.string.a5_no_ai), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            if (tab == 0) {
                if (docs.loading) LinearProgressIndicator(progress = { docs.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                CitedMessageList(chat, { c -> if (isTextSource(c.filePath)) textCitation = c else onCitation(c) }, onShare = { shareText(ctx, it) },
                    onExportPdf = { t -> scope.launch { exportPdfAndShare(ctx, app.documents, file.name, t) } }, modifier = Modifier.weight(1f).fillMaxWidth(),
                    onExportDocx = { t -> scope.launch { exportDocxAndShare(ctx, app.documents, file.name, t) } })   // ai6 (AI-15)
                // ai6: images / page snapshot (AI-10), skills (AI-6 / AI-11), web search (AI-14)
                com.smartdocs.editor.ui.ai6.HubChatInput(chat, enabled = hasAi && docs.ready, onVoiceChat = { voice = true },
                    snapshotFile = file, snapshotPassword = password, snapshotPages = pageCount, currentPage = currentPage)
            } else {
                com.smartdocs.editor.ui.ai.DocAgentPanel(host, Modifier.weight(1f).fillMaxWidth(), null, null)
            }
        }
    }
    if (history) ChatHistorySheet(chat, onExportChat = { t -> scope.launch { exportDocxAndShare(ctx, app.documents, file.name, t) } }) { history = false }
    textCitation?.let { c -> com.smartdocs.editor.ui.ai6.TextSourceDialog(c) { textCitation = null } }
    if (voice) VoiceChatDialog(busy = chat.busy, onAsk = { chat.send(it) }, latestAnswer = { chat.lastAnswer()?.answer?.text ?: chat.lastAnswer()?.text }, onDismiss = { voice = false })
}
