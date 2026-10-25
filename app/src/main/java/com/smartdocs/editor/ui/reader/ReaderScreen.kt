package com.smartdocs.editor.ui.reader

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tts.ReadAloudController
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.ai.AiSheet
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.quick.OcrSuggestionBanner
import com.smartdocs.editor.ui.quick.QuickAction
import com.smartdocs.editor.ui.quick.QuickActionHost
import com.smartdocs.editor.ui.quick.QuickDoneBar
import com.smartdocs.editor.ui.quick.rememberQuickController
import com.smartdocs.editor.ui.stamps.StampPickerSheet
import com.smartdocs.editor.ui.reader.view.readerTouchWatcher
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(nav: NavHostController, uri: String, startPage: Int) {
    val app = LocalApp.current
    val settings = LocalSettings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberReaderState(app, uri, startPage, settings)
    val snack = remember { SnackbarHostState() }
    val quick = rememberQuickController(state)

    // ---- ui state ----
    var chrome by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<ReaderPanel?>(null) }
    var showViewMode by remember { mutableStateOf(false) }
    var showPageColor by remember { mutableStateOf(false) }
    var showAutoFlip by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var showStamps by remember { mutableStateOf(false) }
    var aiText by remember { mutableStateOf<String?>(null) }
    var showAi by remember { mutableStateOf(false) }
    var textTools by remember { mutableStateOf<Pair<TextSelection, com.smartdocs.editor.core.ai5.TextAction?>?>(null) }
    var contentEdit by remember { mutableStateOf(false) }          // MODULE_edit: real text/image editing
    var prompt by remember { mutableStateOf<TextPrompt?>(null) }
    var menu by remember { mutableStateOf(false) }
    val tts by ReadAloudController.status.collectAsState()
    val tablet = LocalConfiguration.current.screenWidthDp > 700

    // ---- platform glue ----
    val view = LocalView.current
    // view module: keep-screen-on timeout (restarts on touch), orientation lock, hardware keys, tabs bookkeeping
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    com.smartdocs.editor.ui.reader.view.ReaderViewEffects(state, settings, rtl)
    @Suppress("DEPRECATION") val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settings.autoSave) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE && settings.autoSave && state.dirty) state.saveInBackground()
            if (e == Lifecycle.Event.ON_RESUME) state.reloadIfChanged()
        }
        lifecycleOwner.lifecycle.addObserver(obs); onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val writeBack = com.smartdocs.editor.ui.common.rememberWriteBack()
    LaunchedEffect(state.writeBackResult) {
        val r = state.writeBackResult ?: return@LaunchedEffect
        state.writeBackResult = null
        // needs a permission prompt or a re-pick: the shared helper shows the right UI and retries
        if (r !is com.smartdocs.editor.core.files.DocumentRepository.WriteBack.Done && r !is com.smartdocs.editor.core.files.DocumentRepository.WriteBack.NotNeeded) state.file?.let { writeBack(it) }
    }
    LaunchedEffect(state.error) { state.error?.let { snack.showSnackbar(it); state.error = null } }
    LaunchedEffect(tts.page, tts.state) { if (tts.state != ReadAloudController.State.IDLE && tts.page != state.currentPage && state.pageCount > 0) state.jumpTo(tts.page) }

    fun leave() { nav.popBackStack() }
    val switchTab = com.smartdocs.editor.ui.reader.view.rememberTabSwitcher(nav, state)
    fun saveThen(block: () -> Unit) { scope.launch { if (state.save()) block() } }
    BackHandler {
        when {
            state.searchOpen -> state.closeSearch()
            state.selection != null -> state.selection = null
            state.edit.target != null -> state.edit.clear()
            state.annot2.vertices.isNotEmpty() -> state.annot2.cancelPoly()
            state.annot2.multi.active -> state.annot2.multi.clear()
            state.placement != null -> state.cancelPlacement()
            state.tool != null -> state.selectTool(null)
            panel != null -> panel = null
            state.dirty -> if (settings.autoSave) saveThen { leave() } else showDiscard = true
            else -> leave()
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        if (target != null) scope.launch {
            val tmp = app.documents.newTemp("saveas")
            runCatching { state.exportWithAnnots(tmp); app.documents.exportTo(tmp, target); tmp.delete() }
                .onSuccess { snack.showSnackbar(ctx.getString(R.string.success)) }.onFailure { state.error = it.message }
        }
    }
    val pickImage = rememberImagePicker(multiple = false) { files ->
        scope.launch { files.firstOrNull()?.let { ReaderIntents.decode(it) }?.let { state.pendingImage = it; state.tool = ReaderTool.IMAGE } }
    }
    fun doStartReadAloud(fromChar: Int, page: Int) {
        if (state.pageCount == 0 || state.session == null) return
        ReadAloudController.start(ctx, state.file?.name ?: "", page.coerceIn(0, state.pageCount - 1), state.pageCount, settings.ttsRate, settings.ttsPitch, { state.pageText(it) }, fromChar)
    }
    // API 33+: ask for POST_NOTIFICATIONS first, start reading once the user answered (granted or not —
    // the foreground service is allowed either way, only the notification would be hidden).
    var pendingRead by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pendingRead?.let { (c, p) -> doStartReadAloud(c, p) }
        pendingRead = null
    }
    fun startReadAloud(fromChar: Int = 0, page: Int = state.currentPage) {
        if (state.pageCount == 0) return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingRead = fromChar to page
            runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure { pendingRead = null; doStartReadAloud(fromChar, page) }
            return
        }
        doStartReadAloud(fromChar, page)
    }
    /** File to share / print: the saved file, or a temp copy with pending annotations. */
    suspend fun outputFile(): File? {
        val f = state.file ?: return null
        if (!state.dirty) return f
        val tmp = app.documents.newTemp("share"); state.exportWithAnnots(tmp); return tmp
    }

    val actions = ReaderActions(
            viewModeSheet = { showViewMode = true }, pageColor = { showPageColor = true }, autoFlip = { showAutoFlip = true }, goToPage = { showGoTo = true },
            panels = { panel = it }, docInfo = { panel = ReaderPanel.INFO }, search = { state.searchOpen = !state.searchOpen; if (!state.searchOpen) state.closeSearch() },
            readAloud = { if (ReadAloudController.isActive) ReadAloudController.stop() else startReadAloud() },
            share = { scope.launch { outputFile()?.let { ReaderIntents.shareFile(ctx, app, it) } } },
            print = { scope.launch { outputFile()?.let { ReaderIntents.print(ctx, it, state.pageCount) } } },
            save = { scope.launch { if (state.save()) snack.showSnackbar(ctx.getString(R.string.saved_to, state.file?.name ?: "")) } },
            saveAs = { saveAs.launch(state.file?.name ?: "document.pdf") },
            pickImage = { pickImage() }, pickStamp = { showStamps = true },
            pickSignature = { path -> scope.launch { ReaderIntents.decode(File(path))?.let { state.pendingImage = it; state.tool = ReaderTool.SIGNATURE; state.pendingText = path; state.selection = null } } },
            navigateTool = { id ->
                if (id == "content_edit") { state.selectTool(null); contentEdit = true } else {
                val go = {
                    when (id) {
                        "signatures" -> nav.navigate(Routes.tool("signatures"))
                        else -> nav.navigate(Routes.tool(id, state.fileUri))
                    }
                }
                if (state.dirty) saveThen(go) else go()      // tools read the file: pending edits go in first
                }
            },
            ai = { aiText = null; showAi = true },
            quick = { qa ->
                quick.open(qa,
                    onShare = { scope.launch { outputFile()?.let { ReaderIntents.shareFile(ctx, app, it) } } },
                    onPrint = { scope.launch { outputFile()?.let { ReaderIntents.print(ctx, it, state.pageCount) } } })
            },
        )

    val focus = remember { FocusRequester() }
    LaunchedEffect(state.session) { if (state.session != null) runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize().readerTouchWatcher(state)) {
        Scaffold(
            snackbarHost = { Column { QuickDoneBar(quick, nav); SnackbarHost(snack) } },
            topBar = {
                if (chrome) Column {
                    TopAppBar(
                        title = { Text(state.file?.name ?: stringResource(R.string.loading), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = { IconButton(onClick = { if (state.dirty) { if (settings.autoSave) saveThen { leave() } else showDiscard = true } else leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                        actions = {
                            if (state.dirty) IconButton(onClick = actions.save) { Icon(Icons.Default.Save, stringResource(R.string.save), tint = MaterialTheme.colorScheme.primary) }
                            if (!tablet) com.smartdocs.editor.ui.reader.view.ReaderTabsAction(state, switchTab)
                            IconButton(onClick = actions.search) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, leadingIcon = { Icon(Icons.Default.Save, null) }, enabled = state.dirty, onClick = { menu = false; actions.save() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.save_as)) }, leadingIcon = { Icon(Icons.Default.SaveAlt, null) }, onClick = { menu = false; actions.saveAs() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; actions.share() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.print)) }, leadingIcon = { Icon(Icons.Default.Print, null) }, onClick = { menu = false; actions.print() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_compress)) }, leadingIcon = { Icon(Icons.Default.Compress, null) }, onClick = { menu = false; actions.quick(QuickAction.COMPRESS) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_ocr)) }, leadingIcon = { Icon(Icons.Default.DocumentScanner, null) }, onClick = { menu = false; actions.quick(QuickAction.OCR) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_protect)) }, leadingIcon = { Icon(Icons.Default.Lock, null) }, onClick = { menu = false; actions.quick(QuickAction.PROTECT) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_flatten)) }, leadingIcon = { Icon(Icons.Default.Layers, null) }, onClick = { menu = false; actions.quick(QuickAction.FLATTEN) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.go_to_page)) }, leadingIcon = { Icon(Icons.Default.Numbers, null) }, onClick = { menu = false; showGoTo = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_doc_info)) }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { menu = false; actions.quick(QuickAction.PROPERTIES) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.keep_screen_on)) }, leadingIcon = { Icon(if (settings.keepScreenOn) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null) },
                                        onClick = { menu = false; scope.launch { app.settings.update { it.copy(keepScreenOn = !it.keepScreenOn) } } })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { menu = false; nav.navigate(Routes.SETTINGS) })
                                }
                            }
                        },
                    )
                    if (tablet) com.smartdocs.editor.ui.reader.view.ReaderTabStrip(state, switchTab, alwaysShow = true)
                    if (state.searchOpen) SearchBar(state) { state.closeSearch() }
                    else ReaderTabRow(state) { t ->
                        if (t == ReaderTab.PAGES) actions.navigateTool("organize")
                        else { state.tab = t; state.selectTool(null); state.selection = null }
                    }
                }
            },
            bottomBar = {
                if (chrome) Column {
                    if (tts.state != ReadAloudController.State.IDLE) TtsBar(tts) { r -> ReadAloudController.setRate(r); scope.launch { app.settings.update { it.copy(ttsRate = r) } } }
                    state.selection?.let { sel ->
                        SelectionMenu(sel, state,
                            onCopy = { ReaderIntents.copy(ctx, it); state.selection = null; scope.launch { snack.showSnackbar(ctx.getString(R.string.copy_text)) } },
                            onTranslate = { textTools = sel to com.smartdocs.editor.core.ai5.TextAction.TRANSLATE }, onAskAi = { textTools = sel to com.smartdocs.editor.core.ai5.TextAction.EXPLAIN },
                            onReadFrom = { s -> startReadAloud(s.start, s.page); state.selection = null },
                            onWebSearch = { ReaderIntents.webSearch(ctx, it) }, onShare = { ReaderIntents.shareText(ctx, it) })
                    }
                    if (state.session != null) BottomToolbar(state, actions)
                }
            },
        ) { pad ->
            Row(Modifier.fillMaxSize().padding(pad)) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // volume (if enabled) / arrows / page keys: spread-aware screen flips (view module)
                        state.vx.handleKeyCode(e.nativeKeyEvent.keyCode, settings.volumeKeysFlip, rtl)
                    },
                ) {
                    when {
                        state.session == null && state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.error_generic)) }
                        state.session == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (!state.needPassword) CircularProgressIndicator() }
                        else -> {
                            PageView(state, onTapCenter = { if (state.tool == null) chrome = !chrome },
                                onOpenLink = { l -> ReaderIntents.openLink(ctx, l) { p -> state.jumpTo(p) } }, onPrompt = { prompt = it })
                            if (state.reflow) ReflowView(state)
                            PageIndicator(state.currentPage, state.pageCount, { showGoTo = true }, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
                            if (state.tool != null && state.placement == null) ToolHint(state.tool!!, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                            else OcrSuggestionBanner(state, onOcr = { actions.quick(QuickAction.OCR) }, modifier = Modifier.align(Alignment.TopCenter))
                            com.smartdocs.editor.ui.forms5.InPageXfaBanner(state, onOpen = { f -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString(), 0)) },
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp))   // MODULE_forms5
                        }
                    }
                }
                if (tablet && panel != null) {
                    VerticalDivider()
                    Surface(Modifier.width(340.dp).fillMaxHeight(), tonalElevation = 1.dp) {
                        PanelsContent(state, panel!!, onPanel = { panel = it }, onJump = { state.jumpTo(it) }, onAiOutline = { nav.navigate(Routes.tool("ai", state.fileUri)) }, onClose = { panel = null })
                    }
                }
            }
        }
        if (!tablet && panel != null) ModalBottomSheet(onDismissRequest = { panel = null }) {
            PanelsContent(state, panel!!, onPanel = { panel = it }, onJump = { state.jumpTo(it); panel = null }, onAiOutline = { panel = null; nav.navigate(Routes.tool("ai", state.fileUri)) }, onClose = { panel = null })
        }
        com.smartdocs.editor.ui.forms5.InPageFormBar(state, Modifier.align(Alignment.BottomCenter))   // MODULE_forms5
    }

    // ---- dialogs & sheets ----
    if (state.needPassword) PasswordDialog(state.wrongPassword, onOk = { state.open(it) }, onCancel = { leave() })
    if (showDiscard) DiscardDialog(onSave = { showDiscard = false; saveThen { leave() } }, onDiscard = { showDiscard = false; state.edit.discardOnClose(); state.forms5.discard(); leave() }, onCancel = { showDiscard = false })
    if (showGoTo) GoToPageDialog(state.currentPage, state.pageCount, onGo = { state.jumpTo(it); showGoTo = false }, onDismiss = { showGoTo = false })
    if (showViewMode) ViewModeSheet(state, tablet = tablet) { showViewMode = false; scope.launch { app.settings.update { it.copy(continuousScroll = state.viewMode == ViewMode.CONTINUOUS, nightMode = state.nightMode) } } }
    if (showPageColor) PageColorSheet(state, onPersist = { c -> scope.launch { app.settings.update { it.copy(pageColor = c) } } }) { showPageColor = false }
    if (showAutoFlip) AutoFlipDialog(state) { showAutoFlip = false }
    prompt?.let { p -> TextPromptDialog(state, p) { prompt = null } }
    if (showStamps) StampPickerSheet(onPicked = { bmp -> state.pendingImage = bmp; state.tool = ReaderTool.STAMP; state.selection = null; showStamps = false }, onDismiss = { showStamps = false; if (state.pendingImage == null) state.selectTool(null) })
    // MODULE_ai2: the assistant can act on the open document (confirm-then-apply, with undo)
    val agentHost = remember(state) {
        com.smartdocs.editor.core.ai.DocHost(
            file = { state.file }, password = { state.password }, pageCount = { state.pageCount }, currentPage = { state.currentPage },
            apply = { e -> state.applyInPlace(if (e.changesPassword) e.newPassword else state.password, e.op) },
            restore = { backup, pw -> state.restoreFrom(backup, pw) },
            goToPage = { state.jumpTo(it) },
            pageText = { state.pageText(it) },
            search = { q -> state.searchOpen = true; state.runSearch(q) },
            setPageColor = { state.pageColor = it },
            selectedText = { state.selection?.text },
            openFile = { f -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString())) },
            share = { files, mime -> ctx.startActivity(android.content.Intent.createChooser(app.documents.shareIntent(files, mime), null)) },
        )
    }
    if (showAi) state.file?.let { f -> com.smartdocs.editor.ui.ai5.DocAssistantSheet(agentHost, f, state.password, state.pageCount, { state.pageText(it) },
        onCitation = { c -> showAi = false; if (c.filePath == f.absolutePath) state.jumpTo(c.page) else nav.navigate(com.smartdocs.editor.ui.ai5.citationRoute(c)) },
        onDismiss = { showAi = false },
        initialQuestion = aiText?.let { "اشرح هذا النص:\n$it" },
        onPodcast = { showAi = false; nav.navigate(Routes.tool("podcast", state.fileUri)) }) }
    textTools?.let { (sel, act) -> com.smartdocs.editor.ui.ai5.TextToolsSheet(sel.text, act, docContext = null, onReplace = null,
        onAddNote = { t -> sel.rects.firstOrNull()?.let { r -> val top = maxOf(r.top, r.bottom)
            state.addAnnot(com.smartdocs.editor.core.pdf.Annot(kind = com.smartdocs.editor.core.pdf.AnnotKind.NOTE, page = sel.page,
                rects = listOf(android.graphics.RectF(r.left, top, r.left + 20f, top - 20f)), text = t, author = state.authorName)) } },
        onShare = { ReaderIntents.shareText(ctx, it) }, onDismiss = { textTools = null; state.selection = null }) }
    if (contentEdit && state.session != null) com.smartdocs.editor.ui.edit.EditModeHost(state) { contentEdit = false }
    if (state.saving) ProgressDialog(stringResource(R.string.processing))
    QuickActionHost(quick, snack)
    com.smartdocs.editor.ui.reader.annot.Annot2Dialogs(state, nav, snack)      // MODULE_annot2 pickers & dialogs
}

/** One-line hint under the top bar telling the user what the active tool expects. */
@Composable
private fun ToolHint(tool: ReaderTool, modifier: Modifier = Modifier) {
    val text = stringResource(
        when (tool) {
            ReaderTool.HIGHLIGHT, ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY -> R.string.rd_hint_markup
            ReaderTool.INK, ReaderTool.LINE, ReaderTool.ARROW, ReaderTool.RECT, ReaderTool.OVAL -> R.string.rd_hint_draw
            ReaderTool.ERASER -> R.string.rd_hint_eraser
            ReaderTool.MEASURE -> R.string.rd_hint_measure
            ReaderTool.LINK -> R.string.rd_hint_link
            ReaderTool.SIGNATURE, ReaderTool.STAMP, ReaderTool.IMAGE -> R.string.sig_tap_to_place
            else -> R.string.rd_hint_tap
        }
    )
    Surface(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}
