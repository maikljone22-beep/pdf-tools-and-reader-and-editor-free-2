package com.smartdocs.editor.ui.ai5

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    // ai6 (§35 adjustable panel width): on wide screens the button cycles narrow / medium / wide and the side
    // handle drags the width freely (aor); the width is remembered in dp
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val wideScreen = conf.screenWidthDp > 700
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val density = androidx.compose.ui.platform.LocalDensity.current
    val prefs = remember { ctx.getSharedPreferences("ai6_ui", android.content.Context.MODE_PRIVATE) }
    var widthDp by remember {
        mutableStateOf(prefs.getFloat("assistant_width_dp", PanelWidth.STEPS[prefs.getInt("assistant_width", 1).coerceIn(0, 2)]))
    }
    val maxDp = conf.screenWidthDp.toFloat()
    val sheetWidth = if (wideScreen) PanelWidth.clamp(widthDp, maxDp).dp else BottomSheetDefaults.SheetMaxWidth
    fun saveWidth() { prefs.edit().putFloat("assistant_width_dp", PanelWidth.clamp(widthDp, maxDp)).apply() }
    ModalBottomSheet(onDismissRequest = { chat.stop(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = sheetWidth) {
        Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.a5_hub_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (wideScreen) IconButton(onClick = { widthDp = PanelWidth.nextStep(PanelWidth.clamp(widthDp, maxDp)); saveWidth() }) {
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
        // aor: drag handle on the end edge; the sheet is centred, so the width grows by twice the drag
        if (wideScreen) {
            val dragDesc = stringResource(R.string.aor_panel_drag)
            Box(
                Modifier.align(Alignment.CenterEnd).width(20.dp).height(72.dp)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { px ->
                            widthDp = PanelWidth.drag(PanelWidth.clamp(widthDp, maxDp), with(density) { px.toDp().value }, rtl, maxDp)
                        },
                        onDragStopped = { saveWidth() },
                    )
                    .semantics { contentDescription = dragDesc },
                contentAlignment = Alignment.Center,
            ) {
                Surface(Modifier.width(4.dp).height(48.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.outline) {}
            }
        }
        }
    }
    if (history) ChatHistorySheet(chat, onExportChat = { t -> scope.launch { exportDocxAndShare(ctx, app.documents, file.name, t) } }) { history = false }
    textCitation?.let { c -> com.smartdocs.editor.ui.ai6.TextSourceDialog(c) { textCitation = null } }
    if (voice) VoiceChatDialog(busy = chat.busy, onAsk = { chat.send(it) }, latestAnswer = { chat.lastAnswer()?.answer?.text ?: chat.lastAnswer()?.text }, onDismiss = { voice = false })
}

/** aor: width rules of the reader AI panel on wide screens (dp). */
object PanelWidth {
    val STEPS = floatArrayOf(480f, 640f, 960f)
    const val MIN = 360f

    fun clamp(w: Float, maxDp: Float): Float = w.coerceIn(MIN, maxOf(MIN, maxDp))

    /** The next preset after [w] (cycling), for the width button. */
    fun nextStep(w: Float): Float = STEPS.firstOrNull { it > w + 1f } ?: STEPS[0]

    /**
     * New width after dragging the end-edge handle by [deltaDp] (screen x, + = right). The end edge is on the right
     * in LTR and on the left in RTL; the sheet is centred, so both sides move.
     */
    fun drag(w: Float, deltaDp: Float, rtl: Boolean, maxDp: Float): Float = clamp(w + 2f * (if (rtl) -deltaDp else deltaDp), maxDp)
}
