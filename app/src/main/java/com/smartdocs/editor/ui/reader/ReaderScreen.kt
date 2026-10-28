package com.smartdocs.editor.ui.reader

import com.smartdocs.editor.ui.signature.SigFieldSheet
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
    val pageLabels = com.smartdocs.editor.ui.edit5.rememberPageLabels(state)
    // old plan A6: the panel / full screen the document asks for on its first open
    LaunchedEffect(state.initialPageMode) {
        when (val m = InitialViewMap.panelOf(state.initialPageMode)) {
            null -> {}
            "full" -> chrome = false
            else -> panel = runCatching { ReaderPanel.valueOf(m) }.getOrNull()
        }
        state.initialPageMode = null
    }
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
    var showHistory by remember { mutableStateOf(false) }           // MODULE_history: unified undo / redo sheet
    val tts by ReadAloudController.status.collectAsState()
    SideEffect { com.smartdocs.editor.core.voice.AiVoiceEngine.aiSwitchOn = settings.aiEnabled }   // ai6: AI switch off -> no AI voice in read aloud
    val tablet = LocalConfiguration.current.screenWidthDp > 700

    // ---- platform glue ----
    val view = LocalView.current
    // view module: keep-screen-on timeout (restarts on touch), orientation lock, hardware keys, tabs bookkeeping
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    com.smartdocs.editor.ui.reader.view.ReaderViewEffects(state, settings, rtl)
    val fold = com.smartdocs.editor.ui.gaps6.rememberFoldState()
    com.smartdocs.editor.ui.gaps6.G6ReaderEffects(state, settings, fold)   // MODULE_gaps6
    NpfReaderEffects(state, settings, nav) { panel = it }                  // nav-prefs: panel on open, autosave interval, recovery copies
    @Suppress("DEPRECATION") val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settings.autoSave) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE && settings.autoSave && state.dirty) state.saveInBackground()
            if (e == Lifecycle.Event.ON_RESUME) { state.reloadIfChanged(); state.guard.publish() }   // nitro-protect2: this document's link / launch decisions
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
    com.smartdocs.editor.ui.cloud.CollabLiveHost(state.file, snack) { op -> state.applyInPlace(state.password, label = { ctx.getString(R.string.hi_collab) }, action = com.smartdocs.editor.core.security.DocAction.ANNOTATE, op = op) }   // share6: live review comments
    HistorySnackbar(state, snack)                                                                                   // MODULE_history
    state.file?.let { com.smartdocs.editor.ui.share5.ReturnedCommentsHost(it, nav) }                               // share6: returned e-mail-review comments
    val connUri = state.file?.let { android.net.Uri.fromFile(it).toString() }
    LaunchedEffect(state.enterpriseInfo) { if (state.enterpriseInfo != null && connUri != null) { nav.popBackStack(); nav.navigate(com.smartdocs.editor.ui.connected.ConnRoutes.enterprise(connUri)) } }
    LaunchedEffect(state.connectedLocked) { if (state.connectedLocked) { val id = state.connected?.docId ?: state.file?.let { com.smartdocs.editor.core.connected.ConnectedDocs.markOf(it)?.docId }; if (id != null) { nav.popBackStack(); nav.navigate(com.smartdocs.editor.ui.connected.ConnRoutes.connectedOpen(id, connUri)) } } }
    var showShare5 by remember { mutableStateOf(false) }   // share6: share sheet
    LaunchedEffect(tts.page, tts.state) { if (tts.state != ReadAloudController.State.IDLE && tts.page != state.currentPage && state.pageCount > 0) state.jumpTo(tts.page) }

    fun leave() { nav.popBackStack() }
    val switchTab = com.smartdocs.editor.ui.reader.view.rememberTabSwitcher(nav, state)
    fun saveThen(block: () -> Unit) { scope.launch { if (state.save()) block() } }
    BackHandler {
        when {
            com.smartdocs.editor.ui.view5.V5State.of(state).onBack() -> {}
            com.smartdocs.editor.ui.nve.NveState.of(state).onBack() -> {}      // NVE
            com.smartdocs.editor.ui.selmenu.SelHooks.onBack(state) -> {}      // selmenu: close the object / right-click menu
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

    val saveAs = rememberLauncherForActivityResult(com.smartdocs.editor.ui.nve.shell.NveCreateDocument(ctx)) { target ->   // NVE-shell: starts in the default save folder
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
        // ai6 (C1): column-aware reading order + form fields; "read from here" offsets mapped into that order
        val p0 = page.coerceIn(0, state.pageCount - 1)
        scope.launch {
            val from = ReaderAi6.toReadingOffset(state, p0, fromChar)
            ReadAloudController.start(ctx, state.file?.name ?: "", p0, state.pageCount, settings.ttsRate, settings.ttsPitch, { pg -> state.file?.let { f -> com.smartdocs.editor.ui.nve.tts.NveReadText.pageText(ctx, f, state.password, pg, base = ReaderAi6.readingText(state, pg)) } ?: ReaderAi6.readingText(state, pg) }, from)   // NVE: + comments / alt text per reading prefs
        }
    }
    // API 33+: ask for POST_NOTIFICATIONS first, start reading once the user answered (granted or not \u2014
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
        // nav-attach (Foxit "automatically save document as e-mail attachment"): with auto-save on, save first
        if (settings.autoSave && runCatching { state.save() }.getOrDefault(false) && !state.dirty) return f
        val tmp = app.documents.newTemp("share"); state.exportWithAnnots(tmp); return tmp
    }

    val actions = ReaderActions(
            viewModeSheet = { showViewMode = true }, pageColor = { showPageColor = true }, autoFlip = { showAutoFlip = true }, goToPage = { showGoTo = true },
            panels = { panel = it }, docInfo = { panel = ReaderPanel.INFO }, search = { state.searchOpen = !state.searchOpen; if (!state.searchOpen) state.closeSearch() },
            readAloud = { if (ReadAloudController.isActive) ReadAloudController.stop() else startReadAloud() },
            // nitro-protect2: a restricted document is shared as it is (the share sheet's copies would drop its protection)
            share = { if (state.guard.allows(com.smartdocs.editor.core.security.DocAction.SAVE_UNPROTECTED)) showShare5 = true else state.file?.let { f -> runCatching { ctx.startActivity(android.content.Intent.createChooser(app.documents.shareIntent(listOf(f), "application/pdf"), null)) } } },
            print = { com.smartdocs.editor.ui.view5.V5State.of(state).open(com.smartdocs.editor.ui.view5.V5Surface.PRINT) },
            save = { scope.launch { if (state.save()) snack.showSnackbar(ctx.getString(R.string.saved_to, state.file?.name ?: "")) } },
            saveAs = { saveAs.launch(state.file?.name ?: "document.pdf") },
            pickImage = { pickImage() }, pickStamp = { showStamps = true },
            pickSignature = { path -> scope.launch { ReaderIntents.decode(File(path))?.let { state.pendingImage = it; state.tool = ReaderTool.SIGNATURE; state.pendingText = path; state.selection = null } } },
            navigateTool = navigate@{ id ->
                // tabs audit CM-21: a tool that does not use this document opens as is (no guard, no save, no undo step)
                if (id in com.smartdocs.editor.core.security.ReaderRestrictions.DOCUMENT_FREE) { state.selectTool(null); nav.navigate(Routes.tool(id)); return@navigate }
                // nitro-protect2: what the tool needs from the document (restrictions / Protected View)
                val need = com.smartdocs.editor.core.security.ReaderRestrictions.actionForTool(id)
                if (need != null && !state.guard.check(need)) { } else
                if (id == "content_edit") { state.selectTool(null); contentEdit = true } else {
                val go = {
                    when (id) {
                        "signatures" -> nav.navigate(Routes.tool("signatures"))
                        else -> {
                            // MODULE_history: a change the tool saves into this file becomes one undo step on return
                            val label = ctx.getString(if (id == "organize") R.string.hi_organize else R.string.hi_tool_change)
                            scope.launch { state.beforeExternalTool(label); nav.navigate(Routes.tool(id, state.fileUri)) }
                        }
                    }
                    Unit
                }
                if (state.dirty) saveThen(go) else go()      // tools read the file: pending edits go in first
                }
            },
            ai = { if (com.smartdocs.editor.core.gaps6.AiGate.enabled(settings) && state.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) { aiText = null; showAi = true } },   // nav-prefs (PREF-8)
            quick = { qa ->
                quick.open(qa,
                    onShare = { showShare5 = true },   // share6: Share5Sheet
                    onPrint = { com.smartdocs.editor.ui.view5.V5State.of(state).open(com.smartdocs.editor.ui.view5.V5Surface.PRINT) })
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
                        title = { Text(NpfReader.title(state) ?: state.file?.name ?: stringResource(R.string.loading), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = { IconButton(onClick = { if (state.dirty) { if (settings.autoSave) saveThen { leave() } else showDiscard = true } else leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                        actions = {
                            HistoryButtons(state) { showHistory = true }      // MODULE_history: always-visible undo / redo (long press: history)
                            if (state.dirty) IconButton(onClick = actions.save) { Icon(Icons.Default.Save, stringResource(R.string.save), tint = MaterialTheme.colorScheme.primary) }
                            if (!tablet) com.smartdocs.editor.ui.reader.view.ReaderTabsAction(state, switchTab)
                            com.smartdocs.editor.ui.view5.V5TopActions(state)
                            IconButton(onClick = actions.search) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, leadingIcon = { Icon(Icons.Default.Save, null) }, enabled = state.dirty, onClick = { menu = false; actions.save() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.hi_history)) }, leadingIcon = { Icon(Icons.Default.History, null) }, onClick = { menu = false; showHistory = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.save_as)) }, leadingIcon = { Icon(Icons.Default.SaveAlt, null) }, onClick = { menu = false; actions.saveAs() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; actions.share() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.print)) }, leadingIcon = { Icon(Icons.Default.Print, null) }, onClick = { menu = false; actions.print() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_compress)) }, leadingIcon = { Icon(Icons.Default.Compress, null) }, onClick = { menu = false; actions.quick(QuickAction.COMPRESS) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_ocr)) }, leadingIcon = { Icon(Icons.Default.DocumentScanner, null) }, onClick = { menu = false; actions.quick(QuickAction.OCR) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_protect)) }, leadingIcon = { Icon(Icons.Default.Lock, null) }, onClick = { menu = false; actions.quick(QuickAction.PROTECT) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_flatten)) }, leadingIcon = { Icon(Icons.Default.Layers, null) }, onClick = { menu = false; actions.quick(QuickAction.FLATTEN) })
                                    // MODULE_search5b: redaction marks from the reader
                                    DropdownMenuItem(text = { Text(stringResource(R.string.s5_rd_page)) }, leadingIcon = { Icon(Icons.Default.Block, null) }, onClick = { menu = false; com.smartdocs.editor.ui.search.ReaderRedact.markPage(ctx, state, quick) })
                                    // old plan K13: OCR of the page on screen (the sheet opens locked to it)
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pc_ocr_this_page)) }, leadingIcon = { Icon(Icons.Default.TextFields, null) }, onClick = { menu = false; quick.openOcrForPage(state.currentPage) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.s5_rd_apply)) }, leadingIcon = { Icon(Icons.Default.Gavel, null) }, onClick = { menu = false; actions.navigateTool("redact") })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.go_to_page)) }, leadingIcon = { Icon(Icons.Default.Numbers, null) }, onClick = { menu = false; showGoTo = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_doc_info)) }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { menu = false; actions.quick(QuickAction.PROPERTIES) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.keep_screen_on)) }, leadingIcon = { Icon(if (settings.keepScreenOn) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null) },
                                        onClick = { menu = false; scope.launch { app.settings.update { it.copy(keepScreenOn = !it.keepScreenOn) } } })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { menu = false; nav.navigate(Routes.SETTINGS) })
                                    // share6: signature requests / connected documents
                                    DropdownMenuItem(text = { Text(stringResource(R.string.cn_tool_esign)) }, leadingIcon = { Icon(Icons.Default.HistoryEdu, null) }, onClick = { menu = false; nav.navigate(com.smartdocs.editor.ui.connected.ConnRoutes.esignCreate(state.fileUri)) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.cn_register)) }, leadingIcon = { Icon(Icons.Default.Link, null) }, onClick = { menu = false; nav.navigate(com.smartdocs.editor.ui.connected.ConnRoutes.connected(state.fileUri)) })
                                    com.smartdocs.editor.ui.view5.V5MoreMenuItems(state, nav) { menu = false }
                                    com.smartdocs.editor.ui.nve.NveMoreMenuItems(state) { menu = false }     // NVE
                                    var nveRevert by remember { mutableStateOf(false) }      // NVE-shell: revert to the saved file
                                    DropdownMenuItem(text = { Text(stringResource(R.string.nl_revert)) }, leadingIcon = { Icon(Icons.Default.Restore, null) }, enabled = state.dirty, onClick = { nveRevert = true })
                                    if (nveRevert) AlertDialog(onDismissRequest = { nveRevert = false }, text = { Text(stringResource(R.string.nl_revert_confirm)) },
                                        confirmButton = { TextButton(onClick = { nveRevert = false; menu = false; com.smartdocs.editor.ui.nve.shell.NveRecovery.revert(state) { nav.popBackStack(); nav.navigate(Routes.reader(state.fileUri)) } }) { Text(stringResource(R.string.ok)) } },
                                        dismissButton = { TextButton(onClick = { nveRevert = false }) { Text(stringResource(R.string.cancel)) } })
                                }
                            }
                        },
                    )
                    if (tablet) com.smartdocs.editor.ui.reader.view.ReaderTabStrip(state, switchTab, alwaysShow = true)
                    if (state.searchOpen) SearchBar(state, quick) { state.closeSearch() }
                    else ReaderTabRow(state) { t ->
                        // tabs audit CM-10 / CM-25 / CM-38: a tab switch ends the View drag modes, the multi-selection
                        // and the active tool (also before Organize, so the eraser is not still on when coming back)
                        com.smartdocs.editor.ui.nve.NveState.of(state).let { n -> n.dragMode = null; n.pasteText = null }
                        state.annot2.onTabChanged()
                        if (state.tool != null) state.selectTool(null)
                        if (t == ReaderTab.PAGES) actions.navigateTool("organize")
                        else { state.tab = t; state.selection = null }
                    }
                    state.connected?.let { com.smartdocs.editor.ui.connected.ConnectedBanner(it, nav, connUri) }   // share6: above the pages
                    if (state.session != null) ReaderSecurityBanners(state)   // nitro-protect2: Protected View, action inspector, restrictions
                }
            },
            bottomBar = {
                if (chrome) Column {
                    if (tts.state != ReadAloudController.State.IDLE) TtsBar(tts) { r -> ReadAloudController.setRate(r); scope.launch { app.settings.update { it.copy(ttsRate = r) } } }
                    // selmenu: the floating selection menu (SelMenuLayer below) replaced this bottom bar
                    if (com.smartdocs.editor.ui.selmenu.SelHooks.LEGACY_BAR) state.selection?.let { sel ->
                        SelectionMenu(sel, state,
                            // nitro-protect2: copying / sending the text out follows the document's copy permission
                            onCopy = { if (state.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) { ReaderIntents.copy(ctx, it); state.selection = null; scope.launch { snack.showSnackbar(ctx.getString(R.string.copy_text)) } } },
                            onTranslate = { if (state.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) textTools = sel to com.smartdocs.editor.core.ai5.TextAction.TRANSLATE }, onAskAi = { if (state.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) textTools = sel to com.smartdocs.editor.core.ai5.TextAction.EXPLAIN },
                            onReadFrom = { s -> startReadAloud(s.start, s.page); state.selection = null },
                            onWebSearch = { if (state.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) ReaderIntents.webSearch(ctx, it) }, onShare = { if (state.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) ReaderIntents.shareText(ctx, it) },
                            onRedact = { s -> com.smartdocs.editor.ui.search.ReaderRedact.markSelection(ctx, state, quick, s) },   // MODULE_search5b
                            onRewrite = { s -> if (state.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) textTools = s to com.smartdocs.editor.core.ai5.TextAction.REWRITE })   // ai6 (B11)
                    }
                    if (state.session != null) BottomToolbar(state, actions)
                }
            },
        ) { pad ->
            Row(Modifier.fillMaxSize().padding(pad)) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().focusRequester(focus).focusable().then(com.smartdocs.editor.ui.nve.nveMouseModifier(state)).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // volume (if enabled) / arrows / page keys: spread-aware screen flips (view module)
                        // MODULE_pageflip6: Space / arrows in the document's direction while the page-turn view is shown
                        com.smartdocs.editor.ui.selmenu.SelHooks.onKey(state, e.nativeKeyEvent) ||     // selmenu: Shift+F10 / Menu key
                        com.smartdocs.editor.ui.nve.NveKeys.onKey(state, e.nativeKeyEvent, switchTab) ||     // NVE: tabs / Ctrl+Home… / customised keys
                        com.smartdocs.editor.ui.pageflip.PageFlipKeys.onKey(state, e.nativeKeyEvent, rtl) ||
                        com.smartdocs.editor.ui.view5.V5Keys.onKey(state, e.nativeKeyEvent) || com.smartdocs.editor.ui.gaps6.G6Keys.handle(state, e.nativeKeyEvent.keyCode, settings, rtl)
                    },
                ) {
                    when {
                        state.session == null && state.openError != null -> OpenErrorView(state.openError!!, onRetry = { state.retryOpen() }, onBack = { leave() },
                            onOpenWith = state.file?.takeIf { it.exists() }?.let { f -> { openWithOtherApp(ctx, app.documents.shareUri(f)) } },
                            // NVE-shell: try to repair a damaged file and open the repaired copy
                            repair = { com.smartdocs.editor.ui.nve.shell.NveRepairButton(state.file, state.password) { f -> nav.popBackStack(); nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString())) } })
                        state.session == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (!state.needPassword) CircularProgressIndicator() }
                        else -> {
                            val openLink: (com.smartdocs.editor.core.pdf.PageLink) -> Unit = { l -> if (l.destPage == null && l.uri.isNullOrBlank()) scope.launch { runCatching { com.smartdocs.editor.ui.edit5.followFileLinkAt(state, state.currentPage, l.bounds.centerX(), l.bounds.centerY()) { f, p -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString(), p)) } } } else if (l.destPage == null && com.smartdocs.editor.ui.nve.NveLinks.openWeb(state, l.uri)) Unit /* NVE: show the URL first */ else ReaderIntents.openLink(ctx, l) { p -> state.jumpTo(p) } }
                            // MODULE_pageflip6: page-turn / horizontal-scroll view modes replace the page list when chosen
                            if (com.smartdocs.editor.ui.pageflip.PageFlipController.of(state).hosts())
                                com.smartdocs.editor.ui.pageflip.PageFlipHost(state, chrome, onTapCenter = { if (state.tool == null) chrome = !chrome }, onOpenLink = openLink, onPrompt = { prompt = it })
                            else PageView(state, onTapCenter = { if (state.tool == null) chrome = !chrome }, onOpenLink = openLink, onPrompt = { prompt = it })
                            com.smartdocs.editor.ui.view5.View5PageOverlays(state)
                            com.smartdocs.editor.ui.nve.NvePageOverlays(state)      // NVE: marquee zoom, column select, snapshot, paste, guides
                            com.smartdocs.editor.ui.nve.articles.NveArticleDrawOverlay(state,      // NVE: article boxes (draw / read)
                                pageRectsPx = { com.smartdocs.editor.ui.nve.NveState.of(state).visiblePages().map { it.page to it.rect } },
                                onShowRect = { p, r -> com.smartdocs.editor.ui.nve.NveZoom.zoomToPageRect(state, p, r[0], r[1], r[2], r[3]) })
                            if (state.reflow) com.smartdocs.editor.ui.view5.ReflowReader(state)
                            PageIndicator(state.currentPage, state.pageCount, { showGoTo = true }, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), labels = pageLabels)
                            if (state.tool != null && state.placement == null) ToolHint(state.tool!!, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                            else if (state.placement != null) com.smartdocs.editor.ui.reader.annot.PlacementBar(state, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))   // section 7.40 / 24.54
                            else OcrSuggestionBanner(state, onOcr = { actions.quick(QuickAction.OCR) }, modifier = Modifier.align(Alignment.TopCenter))
                            com.smartdocs.editor.ui.forms5.InPageXfaBanner(state, onOpen = { f -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString(), 0)) },
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp))   // MODULE_forms5
                            com.smartdocs.editor.ui.search.RedactMarksBanner(state, onApply = { actions.navigateTool("redact") },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 44.dp))   // MODULE_search5b
                            // NVE: document notifications (forms, JavaScript, PDF/A, redactions, 3D, portfolio) + crash-recovery copy
                            com.smartdocs.editor.ui.nve.panes.NveDocBanners(state, onOpenTool = { actions.navigateTool(it) }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp))
                            com.smartdocs.editor.ui.nve.shell.NveRecoveryBanner(state, onOpen = { f -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString())) }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
                            // selmenu: floating selection / long-press / right-click menus (one registry, core/selmenu)
                            com.smartdocs.editor.ui.selmenu.SelMenuLayer(state, actions, quick, snack,
                                onTextTool = { s, a -> if (state.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) textTools = s to a },
                                onReadAloud = { p, c -> startReadAloud(c, p) }, onPrompt = { prompt = it }, onOpenLink = openLink)
                        }
                    }
                }
                if (tablet && panel != null) {
                    VerticalDivider()
                    Surface(Modifier.width(com.smartdocs.editor.core.data.NpfPrefs.panelWidthDp.dp).fillMaxHeight(), tonalElevation = 1.dp) {
                        PanelsContent(state, panel!!, onPanel = { panel = it }, onJump = { state.jumpTo(it) }, onAiOutline = { nav.navigate(Routes.tool("ai", state.fileUri)) }, onClose = { panel = null },
                            onOpenPdf = { f -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString())) })
                    }
                }
                com.smartdocs.editor.ui.nve.NveSidePane(state, nav)      // NVE: attachments / signatures / tags / fields / destinations / articles
            }
        }
        // captured value: the sheet's own composition can recompose after panel became null (NPE with panel!!)
        if (!tablet) panel?.let { shownPanel -> ModalBottomSheet(onDismissRequest = { panel = null }) {
            PanelsContent(state, shownPanel, onPanel = { panel = it }, onJump = { state.jumpTo(it); panel = null }, onAiOutline = { panel = null; nav.navigate(Routes.tool("ai", state.fileUri)) }, onClose = { panel = null },
                onOpenPdf = { f -> panel = null; nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString())) })
        } }
        com.smartdocs.editor.ui.forms5.InPageFormBar(state, Modifier.align(Alignment.BottomCenter),    // MODULE_forms5
            onOpen = { f, p -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString(), p)) },
            onSign = { f -> nav.navigate(Routes.tool("digital_sign", android.net.Uri.fromFile(f).toString())) })
        SigFieldSheet(state, nav)   // Nitro signature-field actions + signed/certified bar
        com.smartdocs.editor.ui.attachments.PortfolioPrompt(state) { nav.navigate(Routes.tool("portfolio", state.fileUri)) }   // nav-attach: portfolio detection
    }

    // ---- dialogs & sheets ----
    if (state.needPassword) PasswordDialog(state.wrongPassword, onOk = { state.open(it) }, onCancel = { leave() })
    if (showDiscard) DiscardDialog(onSave = { showDiscard = false; saveThen { leave() } }, onDiscard = { showDiscard = false; state.discardChanges(); leave() }, onCancel = { showDiscard = false })
    state.signedBlock?.let { reason ->
        AlertDialog(onDismissRequest = { state.signedBlock = null },
            icon = { Icon(Icons.Default.Verified, null) },
            text = { Text(stringResource(when (reason) {
                com.smartdocs.editor.core.pdf.SignedDocumentException.Reason.CERTIFIED_NO_CHANGES -> R.string.signed_doc_certified_no_changes
                com.smartdocs.editor.core.pdf.SignedDocumentException.Reason.CONTENT_CHANGE -> R.string.tc_signed_content_change
                else -> R.string.signed_doc_save_as_copy
            })) },
            confirmButton = { TextButton(onClick = { state.signedBlock = null; saveAs.launch((state.file?.nameWithoutExtension ?: "document") + "_copy.pdf") }) { Text(stringResource(R.string.signed_doc_save_copy_action)) } },
            dismissButton = { TextButton(onClick = { state.signedBlock = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (showGoTo) GoToPageDialog(state.currentPage, state.pageCount, onGo = { state.jumpTo(it); showGoTo = false }, onDismiss = { showGoTo = false }, labels = pageLabels)
    if (showViewMode) ViewModeSheet(state, tablet = tablet) { showViewMode = false; scope.launch { app.settings.update { it.copy(continuousScroll = state.viewMode == ViewMode.CONTINUOUS, nightMode = state.nightMode) } } }
    com.smartdocs.editor.ui.pageflip.PageFlipSheetHost(state)   // MODULE_pageflip6: page turning settings + remembered reading mode
    if (showPageColor) PageColorSheet(state, onPersist = { c -> scope.launch { app.settings.update { it.copy(pageColor = c) } } }) { showPageColor = false }
    if (showAutoFlip) AutoFlipDialog(state) { showAutoFlip = false }
    prompt?.let { p -> if (!com.smartdocs.editor.ui.edit5.ReaderLinkPrompt(state, p.tool == ReaderTool.LINK, p.page, p.x, p.y, p.rect) { prompt = null }) TextPromptDialog(state, p) { prompt = null } }
    if (showStamps) com.smartdocs.editor.ui.view5.V5StampPicker(onPicked = { bmp -> state.pendingImage = bmp; state.tool = ReaderTool.STAMP; state.selection = null; showStamps = false }, onDismiss = { showStamps = false; if (state.pendingImage == null) state.selectTool(null) })
    // MODULE_ai2: the assistant can act on the open document (confirm-then-apply, with undo)
    val agentHost = remember(state) {
        com.smartdocs.editor.core.ai.DocHost(
            file = { state.file }, password = { state.password }, pageCount = { state.pageCount }, currentPage = { state.currentPage },
            // MODULE_history: the change is a step of the unified history. The chat gets a token path next to the
            // snapshot (it deletes its "backups" when closed; the token never exists, the snapshot stays the history's)
            apply = { e -> state.applyInPlace(if (e.changesPassword) e.newPassword else state.password, label = { ctx.getString(R.string.hi_ai_edit) }, op = e.op)?.let { File(it.path + AI_TOKEN) } },
            restore = { token, _ ->
                val b = File(token.path.removeSuffix(AI_TOKEN))
                // audit AN-08: never undo the user's later changes along with the AI edit
                if (state.fileUndoBlocked) error(ctx.getString(R.string.fx_undo_save_first))
                state.history.undoThrough(b) ?: error(state.history.newerStepsThan(b).let { n -> if (n > 0) ctx.getString(R.string.fx_undo_newer, n) else ctx.getString(R.string.hi_gone) })
            },
            goToPage = { state.jumpTo(it) },
            pageText = { state.pageText(it) },
            search = { q -> state.searchOpen = true; state.runSearch(q) },
            setPageColor = { state.pageColor = it },
            selectedText = { state.selection?.text },
            openFile = { f -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString())) },
            share = { files, mime -> runCatching { ctx.startActivity(android.content.Intent.createChooser(app.documents.shareIntent(files, mime), null)) } },
        )
    }
    if (showAi) state.file?.let { f -> com.smartdocs.editor.ui.ai5.DocAssistantSheet(agentHost, f, state.password, state.pageCount, { state.pageText(it) },
        onCitation = { c -> showAi = false; if (c.filePath == f.absolutePath) ReaderAi6.highlightQuote(scope, state, c.page, c.quote) else nav.navigate(com.smartdocs.editor.ui.ai5.citationRoute(c)) },   // ai6 (B1): highlight the passage
        onDismiss = { showAi = false },
        initialQuestion = aiText?.let { "\u0627\u0634\u0631\u062D \u0647\u0630\u0627 \u0627\u0644\u0646\u0635:\n$it" },
        onPodcast = { showAi = false; nav.navigate(Routes.tool("podcast", state.fileUri)) }, currentPage = state.currentPage) }   // ai6: snapshot page
    textTools?.let { (sel, act) -> com.smartdocs.editor.ui.ai5.TextToolsSheet(sel.text, act, docContext = null,
        onReplace = if (sel.rects.isNotEmpty() && state.session != null) ({ t: String -> ReaderAi6.replaceSelection(ctx, state, quick, sel, t) }) else null,   // ai6 (B11): replace in the document, undoable
        onAddNote = { t -> sel.rects.firstOrNull()?.let { r -> val top = maxOf(r.top, r.bottom)
            state.addAnnot(com.smartdocs.editor.core.pdf.Annot(kind = com.smartdocs.editor.core.pdf.AnnotKind.NOTE, page = sel.page,
                rects = listOf(android.graphics.RectF(r.left, top, r.left + 20f, top - 20f)), text = t, author = state.authorName)) } },
        onShare = { ReaderIntents.shareText(ctx, it) }, onDismiss = { textTools = null; state.selection = null }) }
    if (contentEdit && state.session != null) com.smartdocs.editor.ui.edit.EditModeHost(state) { contentEdit = false }
    if (state.saving) ProgressDialog(stringResource(R.string.processing))
    if (showHistory) HistorySheet(state) { showHistory = false }
    QuickActionHost(quick, snack) { id -> actions.navigateTool(id) }
    com.smartdocs.editor.ui.reader.annot.Annot2Dialogs(state, nav, snack)      // MODULE_annot2 pickers & dialogs
    if (state.session != null && !state.needPassword) com.smartdocs.editor.ui.onboarding.ReaderCoachMarks()   // share6
    // share6: share sheet (whole/page/range, compressed, without comments, flattened, images, XFDF, e-mail)
    if (showShare5) { var f by remember { mutableStateOf<java.io.File?>(null) }; LaunchedEffect(Unit) { f = outputFile() }
        f?.let { com.smartdocs.editor.ui.share5.Share5Sheet(it, if (it == state.file) state.password else null, state.currentPage, nav) { showShare5 = false } } }
    com.smartdocs.editor.ui.view5.View5ReaderHost(state, nav, snack, actions) { outputFile() }
    com.smartdocs.editor.ui.nve.NveReaderHost(state, nav, snack, actions)     // NVE (feature/nitro-view-edit)
    com.smartdocs.editor.ui.nve.shell.NveRecoveryEffect(state)
    val nveEdit = com.smartdocs.editor.ui.nve.NveState.of(state).requestEdit
    LaunchedEffect(nveEdit) { if (nveEdit != null) { com.smartdocs.editor.ui.nve.NveEditRequest.forward(state, nveEdit); com.smartdocs.editor.ui.nve.NveState.of(state).requestEdit = null; state.selection = null; state.selectTool(null); contentEdit = true } }
    ReaderAi6.ReadAloudHighlight(state)   // ai6 (C1): sentence highlight while reading aloud
}

/** Suffix of the token path handed to the AI chat for its undo (see agentHost). */
private const val AI_TOKEN = ".ai-undo"

/** One-line hint under the top bar telling the user what the active tool expects. */
@Composable
private fun ToolHint(tool: ReaderTool, modifier: Modifier = Modifier) {
    val text = stringResource(
        when (tool) {
            ReaderTool.HIGHLIGHT, ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY -> R.string.rd_hint_markup
            ReaderTool.INK, ReaderTool.LINE, ReaderTool.ARROW, ReaderTool.RECT, ReaderTool.OVAL, ReaderTool.FS_RRECT -> R.string.rd_hint_draw
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
