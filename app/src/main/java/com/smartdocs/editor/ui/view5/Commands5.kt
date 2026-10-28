package com.smartdocs.editor.ui.view5

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.view5.CommandDef
import com.smartdocs.editor.core.view5.CommandRank
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.reader.ReaderActions
import com.smartdocs.editor.ui.reader.ReaderPanel
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ViewMode
import com.smartdocs.editor.ui.quick.QuickAction

/** One runnable command of "tell me" search. */
class V5Command(val id: String, val label: Int, val icon: ImageVector, val keywords: String, val run: () -> Unit)

/** Links to what the commands act on; set by [View5ReaderHost]. */
class V5Env(val s: ReaderState, val v: V5State, val nav: NavHostController, val actions: ReaderActions)

object V5Commands {
    fun all(e: V5Env): List<V5Command> {
        val s = e.s; val v = e.v; val a = e.actions
        fun open(x: V5Surface, measure: Boolean? = null) { if (measure != null) v.workbenchTool = if (measure) WTool.DISTANCE else WTool.CALLOUT; v.open(x) }
        return listOf(
            V5Command("measure", R.string.v5_measure, Icons.Default.Straighten, "measure distance area perimeter angle قياس مسافة مساحة محيط زاوية") { open(V5Surface.WORKBENCH, true) },
            V5Command("measurements", R.string.v5_measurements, Icons.Default.TableChart, "measurements list csv قياسات جدول") { open(V5Surface.MEASURE_LIST) },
            V5Command("comment_tools", R.string.v5_comment_tools, Icons.Default.Comment, "callout typewriter tick cross area highlight وسيلة شرح آلة كاتبة صح خطأ تمييز منطقة") { open(V5Surface.WORKBENCH, false) },
            V5Command("comments", R.string.v5_comment_manager, Icons.Default.Forum, "comments manager filter export تعليقات مدير تصفية تصدير") { open(V5Surface.COMMENTS) },
            V5Command("stamps", R.string.v5_stamps, Icons.Default.Approval, "stamp sign here dynamic ختم وقع هنا") { a.pickStamp() },
            V5Command("split", R.string.v5_split_view, Icons.Default.VerticalSplit, "split view تقسيم العرض") { open(V5Surface.SPLIT) },
            V5Command("compare", R.string.v5_compare, Icons.Default.Compare, "compare side by side مقارنة جنبا") { open(V5Surface.COMPARE) },
            V5Command("present", R.string.v5_presentation, Icons.Default.Slideshow, "presentation slideshow full screen عرض تقديمي شرائح") { open(V5Surface.PRESENT) },
            V5Command("reflow", R.string.v5_reflow, Icons.Default.ViewHeadline, "reflow liquid reading mode قراءة إعادة تدفق") { s.reflow = !s.reflow },
            V5Command("rulers", R.string.v5_rulers, Icons.Default.Straighten, "rulers مساطر") { v.rulers = !v.rulers; v.saveOverlays() },
            V5Command("grid", R.string.v5_grid, Icons.Default.Grid4x4, "grid شبكة") { v.grid = !v.grid; v.saveOverlays() },
            V5Command("reading_ruler", R.string.v5_reading_ruler, Icons.Default.HorizontalRule, "reading ruler line focus مسطرة القراءة") { v.readingRuler = !v.readingRuler },
            V5Command("lens", R.string.v5_lens, Icons.Default.ZoomIn, "lens magnifier loupe عدسة مكبر") { v.lens = !v.lens; if (v.lens) s.vx.magnifier = false },   // tab-view-core V-10: one magnifier at a time
            V5Command("auto_scroll", R.string.v5_auto_scroll, Icons.Default.SwipeVertical, "auto scroll تمرير تلقائي") {
                if (v.autoScroll) v.autoScroll = false else v.startAutoScroll(s)      // tab-view-core V-34
            },
            V5Command("back", R.string.v5_back_position, Icons.AutoMirrored.Filled.ArrowBack, "back previous position رجوع الموضع السابق") { v.back() },
            V5Command("forward", R.string.v5_forward_position, Icons.AutoMirrored.Filled.ArrowForward, "forward next position تقدم الموضع التالي") { v.forward() },
            V5Command("goto", R.string.go_to_page, Icons.Default.Numbers, "go to page انتقال صفحة") { a.goToPage() },
            V5Command("search", R.string.search, Icons.Default.Search, "find search بحث") { a.search() },
            V5Command("print", R.string.print, Icons.Default.Print, "print booklet طباعة كتيب") { open(V5Surface.PRINT) },
            V5Command("share", R.string.share, Icons.Default.Share, "share مشاركة") { a.share() },
            V5Command("save", R.string.save, Icons.Default.Save, "save حفظ") { a.save() },
            V5Command("save_as", R.string.save_as, Icons.Default.SaveAlt, "save as حفظ باسم") { a.saveAs() },
            V5Command("read_aloud", R.string.tool_read_aloud, Icons.Default.RecordVoiceOver, "read aloud speak tts قراءة بصوت") { a.readAloud() },
            V5Command("read_aloud_options", R.string.v5_read_aloud_options, Icons.Default.Repeat, "repeat read aloud تكرار القراءة") { open(V5Surface.READ_ALOUD) },
            V5Command("night", R.string.night_mode, Icons.Default.DarkMode, "night dark ليلي داكن") { s.nightMode = !s.nightMode },
            V5Command("page_color", R.string.page_color, Icons.Default.Palette, "page color لون الصفحة") { a.pageColor() },
            V5Command("view_mode", R.string.rd_view_mode, Icons.Default.ViewDay, "view mode single continuous facing وضع العرض") { a.viewModeSheet() },
            V5Command("rotate", R.string.rotate_view, Icons.Default.RotateRight, "rotate view تدوير") { s.rotation = (s.rotation + 90) % 360 },
            V5Command("outline", R.string.rd_panels, Icons.Default.FormatListBulleted, "outline bookmarks toc فهرس") { a.panels(ReaderPanel.OUTLINE) },
            V5Command("bookmarks", R.string.panel_bookmarks, Icons.Default.Bookmark, "bookmarks علامات") { a.panels(ReaderPanel.BOOKMARKS) },
            V5Command("thumbnails", R.string.panel_thumbnails, Icons.Default.GridView, "thumbnails pages مصغرات") { a.panels(ReaderPanel.THUMBNAILS) },
            V5Command("attachments", R.string.panel_attachments, Icons.Default.AttachFile, "attachments embedded files portfolio مرفقات") { a.panels(ReaderPanel.ATTACHMENTS) },   // nav-attach
            V5Command("doc_info", R.string.tool_doc_info, Icons.Default.Info, "document info properties خصائص معلومات") { a.docInfo() },
            V5Command("ai", R.string.tool_ai, Icons.Default.AutoAwesome, "ai assistant ask مساعد ذكاء") { a.ai() },
            V5Command("ocr", R.string.tool_ocr, Icons.Default.DocumentScanner, "ocr recognize text تعرف ضوئي") { a.quick(QuickAction.OCR) },
            V5Command("fix_text", R.string.tool_fix_text, Icons.Default.Spellcheck, "repair fix text copy arabic إصلاح نص نسخ") { a.quick(QuickAction.FIX_TEXT) },
            V5Command("compress", R.string.tool_compress, Icons.Default.Compress, "compress reduce size ضغط") { a.quick(QuickAction.COMPRESS) },
            V5Command("protect", R.string.tool_protect, Icons.Default.Lock, "password protect حماية كلمة مرور") { a.quick(QuickAction.PROTECT) },
            V5Command("flatten", R.string.tool_flatten, Icons.Default.Layers, "flatten تسطيح") { a.quick(QuickAction.FLATTEN) },
            V5Command("organize", R.string.tool_organize, Icons.Default.AutoAwesomeMotion, "organize pages تنظيم الصفحات") { a.navigateTool("organize") },
            V5Command("shortcuts", R.string.v5_keyboard_shortcuts, Icons.Default.Keyboard, "keyboard shortcuts اختصارات لوحة المفاتيح") { open(V5Surface.SHORTCUTS) },
            V5Command("overlays", R.string.v5_overlay_settings, Icons.Default.Tune, "rulers grid lens settings إعدادات المساطر والشبكة") { open(V5Surface.OVERLAY_SETTINGS) },
            V5Command("trash", R.string.v5_trash, Icons.Default.DeleteSweep, "trash recycle bin deleted المحذوفات سلة") { e.nav.navigate(V5Routes.TRASH) },
            V5Command("help", R.string.v5_help_center, Icons.AutoMirrored.Filled.HelpOutline, "help faq مساعدة") { e.nav.navigate(V5Routes.HELP) },
            V5Command("whats_new", R.string.v5_whats_new, Icons.Default.NewReleases, "what's new changes الجديد") { e.nav.navigate(V5Routes.WHATS_NEW) },
            V5Command("settings", R.string.settings, Icons.Default.Settings, "settings preferences إعدادات") { e.nav.navigate(Routes.SETTINGS) },
        ).filter { it.id != "ai" || com.smartdocs.editor.core.gaps6.AiGate.on() } + com.smartdocs.editor.ui.nve.NveCommands.all(e)   // nav-prefs (PREF-8); NVE commands: no AI command when AI is off
    }

    fun defs(ctx: Context, list: List<V5Command>) = list.map { CommandDef(it.id, ctx.getString(it.label), it.keywords.split(' ')) }

    private const val K_PINS = "pins"
    private const val K_RECENT = "cmd_recent"
    fun pins(v: V5State): List<String> = (v.prefs.getString(K_PINS, "measure,comments") ?: "").split(',').filter { it.isNotBlank() }
    fun togglePin(v: V5State, id: String) {
        val cur = pins(v)
        v.prefs.edit().putString(K_PINS, (if (id in cur) cur - id else cur + id).joinToString(",")).apply()
        v.pinsVersion++
    }
    // fc-settings: where the pinned commands (quick access) are shown: "top" (reader top bar) or "bottom" (a row above the bottom toolbar)
    const val QAT_TOP = "top"; const val QAT_BOTTOM = "bottom"
    private const val K_QAT_POS = "qat_pos"
    fun qatPosition(ctx: Context): String = ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE).getString(K_QAT_POS, QAT_TOP) ?: QAT_TOP
    fun setQatPosition(ctx: Context, pos: String) { ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE).edit().putString(K_QAT_POS, if (pos == QAT_BOTTOM) QAT_BOTTOM else QAT_TOP).apply() }
    fun recent(v: V5State): List<String> = (v.prefs.getString(K_RECENT, "") ?: "").split(',').filter { it.isNotBlank() }
    fun pushRecent(v: V5State, id: String) { v.prefs.edit().putString(K_RECENT, CommandRank.pushRecent(recent(v), id).joinToString(",")).apply() }
}

/** "Tell me what you want to do": ranked commands, long-press to pin to the top bar. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CommandSearchSheet(e: V5Env) {
    val ctx = LocalContext.current
    val v = e.v
    val cmds = remember(e) { V5Commands.all(e) }
    val defs = remember(cmds) { V5Commands.defs(ctx, cmds) }
    var q by remember { mutableStateOf("") }
    val pv = v.pinsVersion
    val pins = remember(pv) { V5Commands.pins(v).toSet() }
    val ranked = remember(q, pv) { CommandRank.rank(defs, q, pins, V5Commands.recent(v)).mapNotNull { d -> cmds.firstOrNull { it.id == d.id } } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(onDismissRequest = { v.close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 12.dp)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Bolt, null) }, placeholder = { Text(stringResource(R.string.v5_tell_me)) })
            Text(stringResource(R.string.v5_pin_hint), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
            // fc-settings: position of the pinned commands
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.fcs_qat_pos), style = MaterialTheme.typography.labelMedium)
                val pos = remember(pv) { V5Commands.qatPosition(ctx) }
                listOf(V5Commands.QAT_TOP to R.string.fcs_qat_top, V5Commands.QAT_BOTTOM to R.string.fcs_qat_bottom).forEach { (k, l) ->
                    FilterChip(pos == k, { V5Commands.setQatPosition(ctx, k); v.pinsVersion++ }, label = { Text(stringResource(l)) })
                }
            }
            val nveAi = com.smartdocs.editor.LocalSettings.current.aiEnabled
            LazyColumn(Modifier.weight(1f)) {
                items(ranked, key = { it.id }) { c ->
                    ListItem(headlineContent = { Text(stringResource(c.label)) }, leadingContent = { Icon(c.icon, null) },
                        trailingContent = { IconButton(onClick = { V5Commands.togglePin(v, c.id) }) { Icon(if (c.id in pins) Icons.Default.PushPin else Icons.Outlined.PushPin, stringResource(R.string.v5_pin)) } },
                        modifier = Modifier.combinedClickable(onClick = { V5Commands.pushRecent(v, c.id); v.close(); c.run() }, onLongClick = { V5Commands.togglePin(v, c.id) }))
                }
                if (q.isNotBlank()) item {
                    ListItem(headlineContent = { Text(stringResource(R.string.v5_search_in_doc, q)) }, leadingContent = { Icon(Icons.Default.FindInPage, null) },
                        modifier = Modifier.combinedClickable(onClick = { v.close(); e.s.searchOpen = true; e.s.runSearch(q) }))
                }
                // NVE-shell: "Get help on <keywords>" and the help assistant (AI) for the same query
                if (q.isNotBlank()) item {
                    ListItem(headlineContent = { Text(stringResource(R.string.nv_help_on, q)) }, leadingContent = { Icon(Icons.AutoMirrored.Filled.HelpOutline, null) },
                        modifier = Modifier.combinedClickable(onClick = { v.close(); com.smartdocs.editor.ui.nve.shell.NveHelp.openWithQuery(e.nav, q) }))
                }
                if (q.isNotBlank() && nveAi) item {
                    ListItem(headlineContent = { Text(stringResource(R.string.nv_ask_assistant, q)) }, leadingContent = { Icon(Icons.Default.AutoAwesome, null) },
                        modifier = Modifier.combinedClickable(onClick = { v.close(); com.smartdocs.editor.ui.nve.shell.NveHelp.askAi(e.nav, q) }))
                }
            }
        }
    }
}

/** Pinned commands + back / forward + command search, for the reader top bar (hook). */
@Composable
fun V5TopActions(s: ReaderState, show: (String) -> Boolean = { true }) {
    val v = V5State.of(s)
    val e = v.env ?: return
    val pinned = v5Pinned(s, v, e)
    @Suppress("UNUSED_VARIABLE") val hv = v.historyVersion
    // tab-view-core V-03: [show] = the entries that fit in the bar; the others are in the ⋮ menu (V5TopOverflowItems)
    if (v.history.canBack && show(TOP_BACK)) IconButton(onClick = { v.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.v5_back_position)) }
    if (v.history.canForward && show(TOP_FORWARD)) IconButton(onClick = { v.forward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.v5_forward_position)) }
    pinned.forEach { c -> if (show(TOP_PIN + c.id)) IconButton(onClick = c.run) { Icon(c.icon, stringResource(c.label)) } }
    if (show(TOP_TELL_ME)) IconButton(onClick = { v.open(V5Surface.COMMANDS) }) { Icon(Icons.Default.Bolt, stringResource(R.string.v5_tell_me)) }
}

const val TOP_BACK = "v5:back"
const val TOP_FORWARD = "v5:forward"
const val TOP_TELL_ME = "v5:tellme"
const val TOP_PIN = "v5:pin:"

@Composable
private fun v5Pinned(s: ReaderState, v: V5State, e: V5Env): List<V5Command> {
    val cmds = remember(e) { V5Commands.all(e) }
    val pv = v.pinsVersion
    val max = if (LocalConfiguration.current.screenWidthDp > 700) 6 else 2
    // fc-settings: pinned commands sit above the bottom toolbar (V5PinnedBottomRow) when their position is "bottom":
    // then none are in the top bar or its ⋮ overflow (tab-view-core V-03)
    val ctx = LocalContext.current
    val bottom = remember(pv) { V5Commands.qatPosition(ctx) } == V5Commands.QAT_BOTTOM
    return remember(pv, cmds, bottom) { if (bottom) emptyList() else V5Commands.pins(v).mapNotNull { id -> cmds.firstOrNull { it.id == id } }.take(max) }
}

/** tab-view-core V-03: the entries of [V5TopActions] with their width (dp), in keep-priority order (tell me first). */
@Composable
fun v5TopEntries(s: ReaderState): List<Pair<String, Int>> {
    val v = V5State.of(s)
    val e = v.env ?: return emptyList()
    val pinned = v5Pinned(s, v, e)
    @Suppress("UNUSED_VARIABLE") val hv = v.historyVersion
    return buildList {
        add(TOP_TELL_ME to 48)
        pinned.forEach { add(TOP_PIN + it.id to 48) }
        if (v.history.canBack) add(TOP_BACK to 48)
        if (v.history.canForward) add(TOP_FORWARD to 48)
    }
}

/** tab-view-core V-03: menu items for the [V5TopActions] entries that did not fit ([hidden]). */
@Composable
fun V5TopOverflowItems(s: ReaderState, hidden: Set<String>, close: () -> Unit) {
    val v = V5State.of(s)
    val e = v.env ?: return
    val pinned = v5Pinned(s, v, e)
    if (TOP_TELL_ME in hidden) DropdownMenuItem(text = { Text(stringResource(R.string.v5_tell_me)) }, leadingIcon = { Icon(Icons.Default.Bolt, null) }, onClick = { close(); v.open(V5Surface.COMMANDS) })
    pinned.forEach { c -> if (TOP_PIN + c.id in hidden) DropdownMenuItem(text = { Text(stringResource(c.label)) }, leadingIcon = { Icon(c.icon, null) }, onClick = { close(); c.run() }) }
    if (TOP_BACK in hidden && v.history.canBack) DropdownMenuItem(text = { Text(stringResource(R.string.v5_back_position)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }, onClick = { close(); v.back() })
    if (TOP_FORWARD in hidden && v.history.canForward) DropdownMenuItem(text = { Text(stringResource(R.string.v5_forward_position)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }, onClick = { close(); v.forward() })
}

/** fc-settings: the pinned commands as a scrollable row above the bottom toolbar, when their position is "bottom" (hook in the reader's bottom bar). */
@Composable
fun V5PinnedBottomRow(s: ReaderState) {
    val v = V5State.of(s)
    val e = v.env ?: return
    val ctx = LocalContext.current
    val pv = v.pinsVersion
    if (remember(pv) { V5Commands.qatPosition(ctx) } != V5Commands.QAT_BOTTOM) return
    val cmds = remember(e) { V5Commands.all(e) }
    val pinned = remember(pv, cmds) { V5Commands.pins(v).mapNotNull { id -> cmds.firstOrNull { it.id == id } } }
    if (pinned.isEmpty()) return
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
            pinned.forEach { c -> IconButton(onClick = c.run) { Icon(c.icon, stringResource(c.label)) } }
        }
    }
}
