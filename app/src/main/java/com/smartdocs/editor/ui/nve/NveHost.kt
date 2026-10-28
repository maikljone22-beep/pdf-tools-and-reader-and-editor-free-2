package com.smartdocs.editor.ui.nve

import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.edit5.EDIT5_TOOLS
import com.smartdocs.editor.ui.reader.ReaderActions
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ToolChip
import com.smartdocs.editor.ui.view5.PagePane
import com.smartdocs.editor.ui.view5.SyncScroll
import com.smartdocs.editor.ui.view5.V5Command
import com.smartdocs.editor.ui.view5.V5Env

/**
 * Single host of the nitro-view-edit work in the reader (hook after View5ReaderHost in ReaderScreen): its sheets,
 * dialogs and panes (phone: bottom sheet; tablet: [NveSidePane] beside the pages).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NveReaderHost(s: ReaderState, nav: NavHostController, snack: SnackbarHostState, actions: ReaderActions) {
    val n = NveState.of(s)
    val tablet = LocalConfiguration.current.screenWidthDp > 700
    @Suppress("UNUSED_VARIABLE") val unusedSnack = snack
    when (n.surface) {
        NveSurface.NONE -> {}
        NveSurface.ZOOM -> NveZoomSheet(s)
        NveSurface.WORD_COUNT -> NveWordCountDialog(s)
        NveSurface.TOOLBAR_CUSTOMIZE -> NveExternal.toolbarCustomize(s) { n.close() }
        NveSurface.TEXT_VIEWER -> NveTextViewer(s)
        NveSurface.TRANSITIONS -> NveTransitionsSheet(s)
        NveSurface.GRID -> NveGridSheet(s)
        NveSurface.SPELL_DOC -> NveDocSpell(s)
        NveSurface.SNAPSHOT -> NvePageSnapshot(s)
        NveSurface.PASTE -> { val ctx = LocalContext.current; LaunchedEffect(Unit) { n.close(); NveClipboard.paste(ctx, s) } }
        NveSurface.SAVE_AS -> NveExternal.saveAs(s, nav) { n.close() }
        NveSurface.DOC_INFO -> NveExternal.docInfo(s, nav) { n.close() }
        NveSurface.QUAD_SPLIT -> NveQuadSplit(s)
        NveSurface.EDIT5 -> NveEdit5Sheet(s, actions)
        NveSurface.KEYS -> NveKeysSheet { n.close() }
    }
    // the attachments pane is main's reader panel (pdf-3c); requests for ours (banner "portfolio") go there
    if (n.pane == NvePane.ATTACHMENTS) LaunchedEffect(Unit) { n.pane = null; actions.panels(com.smartdocs.editor.ui.reader.ReaderPanel.ATTACHMENTS) }
    val pane = n.pane?.takeIf { it != NvePane.ATTACHMENTS }
    if (pane != null && !tablet) ModalBottomSheet(onDismissRequest = { n.pane = null }) {
        NveExternal.pane(s, pane, nav, onJump = { s.jumpTo(it); n.pane = null }, onClose = { n.pane = null })
    }
    NveContextMenu(s, actions)
    NveLinkPreview(s)
    NveLinkPrefetch(s)
    // "Edit text" from the selection bar: the reader opens Edit mode (hook reads requestEdit)
}

/** Tablet: the nitro-view-edit pane beside the pages (hook in ReaderScreen's Row after the panel Surface). */
@Composable
fun NveSidePane(s: ReaderState, nav: NavHostController) {
    val n = NveState.of(s)
    val pane = n.pane?.takeIf { it != NvePane.ATTACHMENTS } ?: return
    if (LocalConfiguration.current.screenWidthDp <= 700) return
    VerticalDivider()
    Surface(Modifier.width(340.dp).fillMaxHeight(), tonalElevation = 1.dp) {
        NveExternal.pane(s, pane, nav, onJump = { s.jumpTo(it) }, onClose = { n.pane = null })
    }
}

/** Chips at the end of the reader's View toolbar (hook after View5ViewChips). */
@Composable
fun NveViewChips(s: ReaderState) {
    val n = NveState.of(s)
    VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
    ToolChip(Icons.Default.Search, stringResource(R.string.nv_zoom_short, NveZoom.percent(s).toInt())) { n.open(NveSurface.ZOOM) }
    ToolChip(Icons.Default.HighlightAlt, stringResource(R.string.nv_marquee), selected = n.dragMode == NveDrag.ZOOM_IN || n.dragMode == NveDrag.ZOOM_OUT) {
        n.dragMode = if (n.dragMode == NveDrag.ZOOM_IN) null else NveDrag.ZOOM_IN
    }
    ToolChip(Icons.Default.Height, stringResource(R.string.nv_fit_height)) { NveZoom.preset(s, ZoomPreset.FIT_HEIGHT) }
    ToolChip(Icons.Default.Fullscreen, stringResource(R.string.nv_actual_size)) { NveZoom.preset(s, ZoomPreset.ACTUAL) }
    ToolChip(Icons.Default.ChromeReaderMode, stringResource(R.string.nv_continuous_facing), selected = n.continuousFacing && s.viewMode == com.smartdocs.editor.ui.reader.ViewMode.CONTINUOUS) {
        val on = !(n.continuousFacing && s.viewMode == com.smartdocs.editor.ui.reader.ViewMode.CONTINUOUS)
        n.continuousFacing = on; if (on) s.viewMode = com.smartdocs.editor.ui.reader.ViewMode.CONTINUOUS; n.saveView()
    }
    ToolChip(Icons.Default.SwapVert, stringResource(R.string.nv_reverse_view), selected = n.reverseView) { n.reverseView = !n.reverseView; n.saveView() }
    ToolChip(Icons.Default.Numbers, stringResource(R.string.nv_word_count)) { n.wordCountText = null; n.open(NveSurface.WORD_COUNT) }
    ToolChip(Icons.Default.Subject, stringResource(R.string.nv_text_viewer)) { n.open(NveSurface.TEXT_VIEWER) }
    ToolChip(Icons.Default.GridOn, stringResource(R.string.nv_grid_title)) { n.open(NveSurface.GRID) }
    ToolChip(Icons.Default.Window, stringResource(R.string.nv_quad_split)) { n.open(NveSurface.QUAD_SPLIT) }
    ToolChip(Icons.Default.Animation, stringResource(R.string.nv_transitions)) { n.open(NveSurface.TRANSITIONS) }
    ToolChip(Icons.Default.PhotoCamera, stringResource(R.string.nv_page_snapshot)) { n.open(NveSurface.SNAPSHOT) }
    ToolChip(Icons.Default.Crop, stringResource(R.string.nv_area_snapshot), selected = n.dragMode == NveDrag.SNAPSHOT) { n.dragMode = if (n.dragMode == NveDrag.SNAPSHOT) null else NveDrag.SNAPSHOT }
    ToolChip(Icons.Default.ContentPaste, stringResource(R.string.nv_paste)) { n.open(NveSurface.PASTE) }
    ToolChip(Icons.Default.SelectAll, stringResource(R.string.nv_select_all)) { s.scope.launchSelectAll(s) }
    ToolChip(Icons.Default.VerifiedUser, stringResource(R.string.nv_pane_signatures)) { n.pane = NvePane.SIGNATURES }
    ToolChip(Icons.Default.Spellcheck, stringResource(R.string.nv_spell_doc)) { n.open(NveSurface.SPELL_DOC) }
    ToolChip(Icons.Default.DashboardCustomize, stringResource(R.string.nv_customize_toolbar)) { n.open(NveSurface.TOOLBAR_CUSTOMIZE) }
}

private fun kotlinx.coroutines.CoroutineScope.launchSelectAll(s: ReaderState) { launch { NveSelectionOps.selectAll(s, s.currentPage) } }

/** Edit5 tools for the reader's Tools tab (hook inside its grid). */
@Composable
fun NveEdit5Chip(id: String, actions: ReaderActions) {
    val t = EDIT5_TOOLS.firstOrNull { it.id == id } ?: return
    ToolChip(t.icon, stringResource(t.label)) { actions.navigateTool(t.id) }
}

/** Items of the reader's ⋮ menu (hook after V5MoreMenuItems). */
@Composable
fun NveMoreMenuItems(s: ReaderState, close: () -> Unit) {
    val n = NveState.of(s)
    HorizontalDivider()
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_edit5_tools)) }, leadingIcon = { Icon(Icons.Default.EditNote, null) }, onClick = { close(); n.open(NveSurface.EDIT5) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_save_as_more)) }, leadingIcon = { Icon(Icons.Default.SaveAs, null) }, onClick = { close(); n.open(NveSurface.SAVE_AS) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_properties)) }, leadingIcon = { Icon(Icons.Default.Description, null) }, onClick = { close(); n.open(NveSurface.DOC_INFO) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_zoom)) }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { close(); n.open(NveSurface.ZOOM) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_word_count)) }, leadingIcon = { Icon(Icons.Default.Numbers, null) }, onClick = { close(); n.wordCountText = null; n.open(NveSurface.WORD_COUNT) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_paste)) }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) }, onClick = { close(); n.open(NveSurface.PASTE) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_page_snapshot)) }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null) }, onClick = { close(); n.open(NveSurface.SNAPSHOT) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_spell_doc)) }, leadingIcon = { Icon(Icons.Default.Spellcheck, null) }, onClick = { close(); n.open(NveSurface.SPELL_DOC) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_pane_signatures)) }, leadingIcon = { Icon(Icons.Default.VerifiedUser, null) }, onClick = { close(); n.pane = NvePane.SIGNATURES })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_customize_toolbar)) }, leadingIcon = { Icon(Icons.Default.DashboardCustomize, null) }, onClick = { close(); n.open(NveSurface.TOOLBAR_CUSTOMIZE) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_keys_title)) }, leadingIcon = { Icon(Icons.Default.Keyboard, null) }, onClick = { close(); n.open(NveSurface.KEYS) })
}

/** Edit5 tools listed for the open document (⋮ → Edit tools). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NveEdit5Sheet(s: ReaderState, actions: ReaderActions) {
    val n = NveState.of(s)
    ModalBottomSheet(onDismissRequest = { n.close() }) {
        Text(stringResource(R.string.nv_edit5_tools), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            items(EDIT5_TOOLS.size) { i ->
                val t = EDIT5_TOOLS[i]
                ListItem(headlineContent = { Text(stringResource(t.label)) }, supportingContent = t.subtitle?.let { { Text(stringResource(it)) } },
                    leadingContent = { Icon(t.icon, null) }, modifier = Modifier.clickable { n.close(); actions.navigateTool(t.id) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Commands for "tell me" search (hook: appended to V5Commands.all). */
object NveCommands {
    fun all(e: V5Env): List<V5Command> {
        val s = e.s; val n = NveState.of(s); val a = e.actions
        val list = mutableListOf(
            V5Command("nv_zoom", R.string.nv_zoom, Icons.Default.Search, "zoom percent magnification تكبير نسبة") { n.open(NveSurface.ZOOM) },
            V5Command("nv_fit_height", R.string.nv_fit_height, Icons.Default.Height, "fit height ملاءمة الارتفاع") { NveZoom.preset(s, ZoomPreset.FIT_HEIGHT) },
            V5Command("nv_actual", R.string.nv_actual_size, Icons.Default.Fullscreen, "actual size 100% الحجم الفعلي") { NveZoom.preset(s, ZoomPreset.ACTUAL) },
            V5Command("nv_auto_zoom", R.string.nv_zoom_auto, Icons.Default.AutoFixNormal, "automatic zoom تلقائي") { NveZoom.preset(s, ZoomPreset.AUTO) },
            V5Command("nv_marquee", R.string.nv_marquee, Icons.Default.HighlightAlt, "marquee zoom area rectangle تكبير منطقة") { n.dragMode = NveDrag.ZOOM_IN },
            V5Command("nv_cont_facing", R.string.nv_continuous_facing, Icons.Default.ChromeReaderMode, "continuous facing two up صفحتان متتاليتان") {
                n.continuousFacing = !n.continuousFacing; if (n.continuousFacing) s.viewMode = com.smartdocs.editor.ui.reader.ViewMode.CONTINUOUS; n.saveView()
            },
            V5Command("nv_reverse", R.string.nv_reverse_view, Icons.Default.SwapVert, "reverse view backward order عرض معكوس") { n.reverseView = !n.reverseView; n.saveView() },
            V5Command("nv_word_count", R.string.nv_word_count, Icons.Default.Numbers, "word count عدد الكلمات") { n.wordCountText = null; n.open(NveSurface.WORD_COUNT) },
            V5Command("nv_text_viewer", R.string.nv_text_viewer, Icons.Default.Subject, "text viewer plain text عارض النص") { n.open(NveSurface.TEXT_VIEWER) },
            V5Command("nv_grid", R.string.nv_grid_title, Icons.Default.GridOn, "grid guides snap units rulers شبكة أدلة محاذاة") { n.open(NveSurface.GRID) },
            V5Command("nv_quad", R.string.nv_quad_split, Icons.Default.Window, "spreadsheet split four panes تقسيم أربعة") { n.open(NveSurface.QUAD_SPLIT) },
            V5Command("nv_transitions", R.string.nv_transitions, Icons.Default.Animation, "page transitions انتقالات الصفحات") { n.open(NveSurface.TRANSITIONS) },
            V5Command("nv_snapshot", R.string.nv_page_snapshot, Icons.Default.PhotoCamera, "snapshot page image لقطة صفحة") { n.open(NveSurface.SNAPSHOT) },
            V5Command("nv_area_snapshot", R.string.nv_area_snapshot, Icons.Default.Crop, "snapshot area لقطة منطقة") { n.dragMode = NveDrag.SNAPSHOT },
            V5Command("nv_paste", R.string.nv_paste, Icons.Default.ContentPaste, "paste clipboard لصق الحافظة") { n.open(NveSurface.PASTE) },
            V5Command("nv_select_all", R.string.nv_select_all, Icons.Default.SelectAll, "select all تحديد الكل") { s.scope.launchSelectAll(s) },
            V5Command("nv_column", R.string.nv_column_select, Icons.Default.ViewColumn, "column rectangle select تحديد عمودي") { n.dragMode = NveDrag.COLUMN_SELECT },
            V5Command("nv_scroll_lock", R.string.nv_scroll_lock, Icons.Default.Lock, "scroll lock قفل التمرير") { n.scrollLock = (n.scrollLock + 1) % 3 },
            V5Command("nv_spell", R.string.nv_spell_doc, Icons.Default.Spellcheck, "spell check spelling تدقيق إملائي") { n.open(NveSurface.SPELL_DOC) },
            V5Command("nv_save_as", R.string.nv_save_as_more, Icons.Default.SaveAs, "save as copy pdfa optimized حفظ باسم") { n.open(NveSurface.SAVE_AS) },
            V5Command("nv_props", R.string.nv_properties, Icons.Default.Description, "properties fonts security xmp خصائص خطوط أمان") { n.open(NveSurface.DOC_INFO) },
            V5Command("nv_customize", R.string.nv_customize_toolbar, Icons.Default.DashboardCustomize, "customize toolbar tabs تخصيص شريط الأدوات") { n.open(NveSurface.TOOLBAR_CUSTOMIZE) },
            V5Command("nv_attachments", R.string.nv_pane_attachments, Icons.Default.AttachFile, "attachments مرفقات") { a.panels(com.smartdocs.editor.ui.reader.ReaderPanel.ATTACHMENTS) },   // main's attachments panel (pdf-3c)
            V5Command("nv_signatures", R.string.nv_pane_signatures, Icons.Default.VerifiedUser, "signatures validate التواقيع تحقق") { n.pane = NvePane.SIGNATURES },
            V5Command("nv_tags", R.string.nv_pane_tags, Icons.Default.AccountTree, "tags structure accessibility وسوم بنية") { a.panels(com.smartdocs.editor.ui.reader.ReaderPanel.INFO) },
            V5Command("nv_fields", R.string.nv_pane_fields, Icons.Default.Checklist, "form fields حقول") { a.panels(com.smartdocs.editor.ui.reader.ReaderPanel.INFO) },
            V5Command("nv_dests", R.string.nv_pane_destinations, Icons.Default.Place, "named destinations وجهات") { a.panels(com.smartdocs.editor.ui.reader.ReaderPanel.INFO) },
            V5Command("nv_articles", R.string.nv_pane_articles, Icons.Default.Article, "articles threads مقالات") { n.pane = NvePane.ARTICLES },
            V5Command("nv_reset_pins", R.string.nv_reset_pins, Icons.Default.PushPin, "reset pinned commands quick access إعادة تعيين المثبتة") {
                e.v.prefs.edit().putString("pins", "").apply(); e.v.pinsVersion++
            },
            V5Command("nv_keys", R.string.nv_keys_title, Icons.Default.Keyboard, "customize keyboard shortcuts تخصيص الاختصارات") { n.open(NveSurface.KEYS) },
            V5Command("nv_edit5", R.string.nv_edit5_tools, Icons.Default.EditNote, "edit tools header footer bates links bookmarks أدوات التحرير") { n.open(NveSurface.EDIT5) },
        )
        // every edit5 tool by itself
        EDIT5_TOOLS.forEach { t -> list.add(V5Command("e5:" + t.id, t.label, t.icon, t.id.removePrefix("e5_").replace('_', ' ')) { a.navigateTool(t.id) }) }
        return list
    }
}

/** Spreadsheet split: four panes of the open document, row panes and column panes scroll together; F6 cycles the active pane. */
@Composable
fun NveQuadSplit(s: ReaderState) {
    val n = NveState.of(s)
    val lists = List(4) { rememberLazyListState(s.currentPage) }
    var active by remember { mutableIntStateOf(0) }
    var syncRows by remember { mutableStateOf(true) }
    var syncCols by remember { mutableStateOf(false) }
    val zooms = remember { mutableStateListOf(1f, 1f, 1f, 1f) }
    SyncScroll(lists[0], lists[1], syncRows)
    SyncScroll(lists[2], lists[3], syncRows)
    SyncScroll(lists[0], lists[2], syncCols)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = { n.close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent { e ->
            if (e.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_F6) {
                active = if (e.nativeKeyEvent.isShiftPressed) (active + 3) % 4 else (active + 1) % 4; true
            } else false
        }) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { n.close() }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Text(stringResource(R.string.nv_quad_split), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { active = (active + 3) % 4 }) { Icon(Icons.Default.NavigateBefore, stringResource(R.string.nv_prev_pane)) }
                    IconButton(onClick = { active = (active + 1) % 4 }) { Icon(Icons.Default.NavigateNext, stringResource(R.string.nv_next_pane)) }
                    IconButton(onClick = { zooms[active] = (zooms[active] / 1.25f).coerceAtLeast(0.5f) }) { Icon(Icons.Default.ZoomOut, stringResource(R.string.pc_cd_zoom_out)) }
                    IconButton(onClick = { zooms[active] = (zooms[active] * 1.25f).coerceAtMost(4f) }) { Icon(Icons.Default.ZoomIn, stringResource(R.string.pc_cd_zoom_in)) }
                    FilterChip(syncRows, { syncRows = !syncRows }, label = { Text(stringResource(R.string.nv_sync_rows)) })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(syncCols, { syncCols = !syncCols }, label = { Text(stringResource(R.string.nv_sync_cols)) })
                }
                Column(Modifier.fillMaxSize()) {
                    for (r in 0..1) Row(Modifier.fillMaxWidth().weight(1f)) {
                        for (c in 0..1) {
                            val i = r * 2 + c
                            val border = if (active == i) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier
                            Box(Modifier.weight(1f).fillMaxHeight().then(border).clickable { active = i }) {
                                PagePane(s.session, s.pageCount, s.pageSizes, lists[i], s.nightMode, zooms[i], Modifier.fillMaxSize(), "${i + 1}")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hook at the end of main's INFO panels (ui/reader/NpfPanels.kt): the NVE editors that do more than main's list —
 * rename / sort named destinations, the expandable structure tree with alt / actual text, the article editor
 * (define boxes, properties, read, modify). One panel per feature: these open from main's panel only.
 */
@Composable
fun NveMoreFor(s: ReaderState, panel: String) {
    val pane = when (panel) { "DESTS" -> NvePane.DESTINATIONS; "TAGS" -> NvePane.TAGS; "ARTICLES" -> NvePane.ARTICLES; else -> return }
    TextButton(onClick = { NveState.of(s).pane = pane }) { Text(stringResource(R.string.nv_more_tools)) }
}
