package com.smartdocs.editor.ui.nve

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.quick.QuickAction
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTab
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.reader.view.DocTab
import com.smartdocs.editor.ui.reader.view.ReaderTabs
import com.smartdocs.editor.ui.view5.V5Routes
import com.smartdocs.editor.ui.view5.V5State
import com.smartdocs.editor.ui.view5.V5Surface
import com.smartdocs.editor.ui.view5.WTool
import kotlinx.coroutines.launch

/** A key chord. */
data class Chord(val key: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {
    fun encode() = "$key:${if (ctrl) 1 else 0}:${if (shift) 1 else 0}:${if (alt) 1 else 0}"
    fun label(): String = buildString {
        if (ctrl) append("Ctrl+"); if (alt) append("Alt+"); if (shift) append("Shift+")
        append(KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_").replace("MOVE_", "").replace("DPAD_", ""))
    }
    companion object {
        fun decode(s: String): Chord? = s.split(':').takeIf { it.size == 4 }?.let { p -> p[0].toIntOrNull()?.let { Chord(it, p[1] == "1", p[2] == "1", p[3] == "1") } }
    }
}

/**
 * Keyboard shortcuts added by the nitro-view-edit work (Foxit / Acrobat set): tabs, navigation, zoom presets, page
 * tools, comment tools, view toggles, read aloud. Every binding can be changed or removed ([NveKeysSheet], prefs
 * "nve_keys"); "Foxit layout" also takes Ctrl+K/L/M/N and Ctrl+Shift+C/R from the older view5 bindings.
 */
enum class NveKey(val label: Int, val def: Chord?, val foxitOnly: Boolean = false) {
    OPEN(R.string.nv_k_open, Chord(KeyEvent.KEYCODE_O, ctrl = true)),
    CLOSE_TAB(R.string.nv_k_close_tab, Chord(KeyEvent.KEYCODE_W, ctrl = true)),
    CLOSE_ALL(R.string.nv_k_close_all, Chord(KeyEvent.KEYCODE_W, ctrl = true, shift = true)),
    NEXT_TAB(R.string.nv_k_next_tab, Chord(KeyEvent.KEYCODE_TAB, ctrl = true)),
    PREV_TAB(R.string.nv_k_prev_tab, Chord(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true)),
    NEXT_TAB2(R.string.nv_k_next_tab, Chord(KeyEvent.KEYCODE_PAGE_DOWN, ctrl = true, shift = true)),
    PREV_TAB2(R.string.nv_k_prev_tab, Chord(KeyEvent.KEYCODE_PAGE_UP, ctrl = true, shift = true)),
    FIRST_PAGE(R.string.v5_first_page, Chord(KeyEvent.KEYCODE_MOVE_HOME, ctrl = true)),
    LAST_PAGE(R.string.v5_last_page, Chord(KeyEvent.KEYCODE_MOVE_END, ctrl = true)),
    PREV_PAGE(R.string.nv_k_prev_page, Chord(KeyEvent.KEYCODE_PAGE_UP, ctrl = true)),
    NEXT_PAGE(R.string.nv_k_next_page, Chord(KeyEvent.KEYCODE_PAGE_DOWN, ctrl = true)),
    SPACE_DOWN(R.string.nv_k_next_page, Chord(KeyEvent.KEYCODE_SPACE)),
    SPACE_UP(R.string.nv_k_prev_page, Chord(KeyEvent.KEYCODE_SPACE, shift = true)),
    PROPERTIES(R.string.nv_properties, Chord(KeyEvent.KEYCODE_D, ctrl = true)),
    FIND_NEXT(R.string.next, Chord(KeyEvent.KEYCODE_F3)),
    FIND_PREV(R.string.previous, Chord(KeyEvent.KEYCODE_F3, shift = true)),
    ADV_SEARCH(R.string.nv_k_adv_search, Chord(KeyEvent.KEYCODE_F, ctrl = true, shift = true)),
    ACTUAL(R.string.nv_actual_size, Chord(KeyEvent.KEYCODE_1, ctrl = true)),
    FIT_WIDTH(R.string.fit_width, Chord(KeyEvent.KEYCODE_2, ctrl = true)),
    FIT_VISIBLE(R.string.nv_fit_height, Chord(KeyEvent.KEYCODE_3, ctrl = true)),
    FIT_PAGE(R.string.fit_page, Chord(KeyEvent.KEYCODE_0, ctrl = true), foxitOnly = true),
    ZOOM_TO(R.string.nv_zoom, Chord(KeyEvent.KEYCODE_M, ctrl = true), foxitOnly = true),
    ROTATE_CW(R.string.rotate_view, Chord(KeyEvent.KEYCODE_EQUALS, ctrl = true, shift = true)),
    ROTATE_CCW(R.string.vw_rotate_ccw, Chord(KeyEvent.KEYCODE_MINUS, ctrl = true, shift = true)),
    SELECT_ALL(R.string.nv_select_all, Chord(KeyEvent.KEYCODE_A, ctrl = true)),
    COPY(R.string.copy_text, Chord(KeyEvent.KEYCODE_C, ctrl = true)),
    PASTE(R.string.nv_paste, Chord(KeyEvent.KEYCODE_V, ctrl = true)),
    ADD_BOOKMARK(R.string.add_bookmark, Chord(KeyEvent.KEYCODE_B, ctrl = true)),
    FIND_REPLACE(R.string.s5_replace_title, Chord(KeyEvent.KEYCODE_T, ctrl = true)),
    DELETE_PAGE(R.string.qk_del_label, Chord(KeyEvent.KEYCODE_D, ctrl = true, shift = true)),
    INSERT_BLANK(R.string.qk_blank_label, Chord(KeyEvent.KEYCODE_T, ctrl = true, shift = true)),
    INSERT_FILE(R.string.tool_organize, Chord(KeyEvent.KEYCODE_I, ctrl = true, shift = true)),
    HIGHLIGHT(R.string.annot_highlight, Chord(KeyEvent.KEYCODE_H, shift = true)),
    SQUIGGLY(R.string.annot_squiggly, Chord(KeyEvent.KEYCODE_U, shift = true)),
    STRIKE(R.string.annot_strikeout, Chord(KeyEvent.KEYCODE_S, shift = true)),
    UNDERLINE(R.string.annot_underline, Chord(KeyEvent.KEYCODE_U, ctrl = true)),
    PENCIL(R.string.annot_ink, Chord(KeyEvent.KEYCODE_P, shift = true)),
    CALLOUT(R.string.v5_tool_callout, Chord(KeyEvent.KEYCODE_C, shift = true)),
    TEXTBOX(R.string.annot_text, Chord(KeyEvent.KEYCODE_E, shift = true)),
    TYPEWRITER(R.string.v5_tool_typewriter, Chord(KeyEvent.KEYCODE_T, shift = true)),
    NOTE(R.string.annot_note, Chord(KeyEvent.KEYCODE_N, ctrl = true, shift = true)),
    INSERT_TEXT(R.string.nv_k_insert_text, Chord(KeyEvent.KEYCODE_I, shift = true)),
    REPLACE_TEXT(R.string.nv_k_replace_text, Chord(KeyEvent.KEYCODE_R, ctrl = true, shift = true), foxitOnly = true),
    COMMENTS(R.string.v5_comment_manager, Chord(KeyEvent.KEYCODE_L, ctrl = true), foxitOnly = true),
    TEXT_VIEWER(R.string.nv_text_viewer, Chord(KeyEvent.KEYCODE_6, ctrl = true)),
    RULERS(R.string.v5_rulers, Chord(KeyEvent.KEYCODE_R, ctrl = true)),
    MARQUEE(R.string.nv_marquee, Chord(KeyEvent.KEYCODE_5, alt = true)),
    NAV_PANE(R.string.rd_panels, Chord(KeyEvent.KEYCODE_F4)),
    FULL_SCREEN(R.string.v5_presentation, Chord(KeyEvent.KEYCODE_F11)),
    READ_MODE(R.string.v5_reflow, Chord(KeyEvent.KEYCODE_H, ctrl = true)),
    AUTO_SCROLL(R.string.v5_auto_scroll, Chord(KeyEvent.KEYCODE_H, ctrl = true, shift = true)),
    READ_ALOUD(R.string.tool_read_aloud, Chord(KeyEvent.KEYCODE_V, ctrl = true, shift = true)),
    READ_STOP(R.string.tts_stop, Chord(KeyEvent.KEYCODE_E, ctrl = true, shift = true)),
    HELP(R.string.v5_help_center, Chord(KeyEvent.KEYCODE_F1)),
    PREFERENCES(R.string.settings, Chord(KeyEvent.KEYCODE_K, ctrl = true), foxitOnly = true),
    NIGHT_FOXIT(R.string.nv_k_new_doc, Chord(KeyEvent.KEYCODE_N, ctrl = true), foxitOnly = true),
    EMAIL(R.string.share, Chord(KeyEvent.KEYCODE_L, ctrl = true, shift = true)),
    WORD_COUNT(R.string.nv_word_count, null),
    SNAPSHOT(R.string.nv_page_snapshot, Chord(KeyEvent.KEYCODE_7, alt = true)),
    ;
}

object NveKeys {
    private const val PREFS = "nve_keys"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun foxitLayout(ctx: Context): Boolean = prefs(ctx).getBoolean("foxit", false)
    fun setFoxitLayout(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("foxit", on).apply()
    fun singleKey(ctx: Context): Boolean = prefs(ctx).getBoolean("single", false)
    fun setSingleKey(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("single", on).apply()

    /** Current chord of [k] (null = none); custom bindings override the defaults ("-" = removed). */
    fun chord(ctx: Context, k: NveKey): Chord? {
        val v = prefs(ctx).getString("k." + k.name, null)
        return when {
            v == "-" -> null
            v != null -> Chord.decode(v)
            k.foxitOnly && !foxitLayout(ctx) -> null
            else -> k.def
        }
    }
    fun bind(ctx: Context, k: NveKey, c: Chord?) = prefs(ctx).edit().putString("k." + k.name, c?.encode() ?: "-").apply()
    fun reset(ctx: Context, k: NveKey) = prefs(ctx).edit().remove("k." + k.name).apply()
    fun resetAll(ctx: Context) { val e = prefs(ctx).edit(); NveKey.entries.forEach { e.remove("k." + it.name) }; e.apply() }

    /** Single-key accelerators (preference): letters without modifiers pick comment tools, as in Foxit. */
    private val single = mapOf(
        KeyEvent.KEYCODE_H to NveKey.HIGHLIGHT, KeyEvent.KEYCODE_U to NveKey.UNDERLINE, KeyEvent.KEYCODE_K to NveKey.STRIKE,
        KeyEvent.KEYCODE_D to NveKey.PENCIL, KeyEvent.KEYCODE_X to NveKey.TEXTBOX, KeyEvent.KEYCODE_S to NveKey.NOTE,
        KeyEvent.KEYCODE_Z to NveKey.MARQUEE, KeyEvent.KEYCODE_F to NveKey.ADV_SEARCH, KeyEvent.KEYCODE_E to NveKey.CALLOUT,
    )

    /** Hook in ReaderScreen's onPreviewKeyEvent (before view5): true when consumed. */
    fun onKey(s: ReaderState, e: KeyEvent, switchTab: (DocTab?) -> Unit): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val ctx = s.context
        // auto-scroll running: 0-9 set the speed, minus reverses, arrows / page keys jump pages (Foxit)
        val v5 = V5State.of(s)
        if (v5.autoScroll && !e.isCtrlPressed && !e.isAltPressed) {
            when (e.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { v5.autoSpeed = (e.keyCode - KeyEvent.KEYCODE_0).coerceAtLeast(1); return true }
                KeyEvent.KEYCODE_MINUS -> { NveState.of(s).autoReverse = !NveState.of(s).autoReverse; return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> { s.jumpTo(s.currentPage - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { s.jumpTo(s.currentPage + 1); return true }
                KeyEvent.KEYCODE_ESCAPE -> { v5.autoScroll = false; return true }
            }
        }
        val c = Chord(e.keyCode, e.isCtrlPressed, e.isShiftPressed, e.isAltPressed)
        val k = NveKey.entries.firstOrNull { chord(ctx, it) == c }
            ?: if (singleKey(ctx) && !c.ctrl && !c.alt && !c.shift) single[e.keyCode] else null
        k ?: return false
        return run(s, k, switchTab)
    }

    fun run(s: ReaderState, k: NveKey, switchTab: (DocTab?) -> Unit): Boolean {
        val v = V5State.of(s); val n = NveState.of(s)
        val a = v.env?.actions
        val nav = v.env?.nav
        val ctx = s.context
        // tabs audit CM-24: the same path as the chips (caret / replace act on the selection), and the Comment toolbar shows
        fun tool(t: ReaderTool) { s.tab = ReaderTab.COMMENT; n.userTab = null; if (a != null) com.smartdocs.editor.ui.reader.annot.clickTool(s, a, t) else s.selectTool(t) }
        fun tabStep(d: Int) {
            val tabs = ReaderTabs.tabs.toList(); if (tabs.size < 2) return
            val i = tabs.indexOfFirst { it.uri == s.fileUri }
            switchTab(tabs[((i + d) % tabs.size + tabs.size) % tabs.size])
        }
        when (k) {
            NveKey.OPEN -> nav?.navigate(Routes.FILES) ?: return false
            NveKey.CLOSE_TAB -> { val next = ReaderTabs.close(ctx, s.fileUri); switchTab(next) }
            NveKey.CLOSE_ALL -> { ReaderTabs.clear(ctx); switchTab(null) }
            NveKey.NEXT_TAB, NveKey.NEXT_TAB2 -> tabStep(1)
            NveKey.PREV_TAB, NveKey.PREV_TAB2 -> tabStep(-1)
            NveKey.FIRST_PAGE -> s.jumpTo(0)
            NveKey.LAST_PAGE -> s.jumpTo(s.pageCount - 1)
            NveKey.PREV_PAGE, NveKey.SPACE_UP -> s.jumpTo(s.currentPage - 1)
            NveKey.NEXT_PAGE, NveKey.SPACE_DOWN -> s.jumpTo(s.currentPage + 1)
            NveKey.PROPERTIES -> n.open(NveSurface.DOC_INFO)
            NveKey.FIND_NEXT -> if (s.searchHits.isNotEmpty()) s.searchStep(1) else a?.search?.invoke()
            NveKey.FIND_PREV -> if (s.searchHits.isNotEmpty()) s.searchStep(-1) else a?.search?.invoke()
            NveKey.ADV_SEARCH -> nav?.navigate(Routes.tool("search_all")) ?: return false
            NveKey.ACTUAL -> NveZoom.preset(s, ZoomPreset.ACTUAL)
            NveKey.FIT_WIDTH -> NveZoom.preset(s, ZoomPreset.FIT_WIDTH)
            NveKey.FIT_VISIBLE -> NveZoom.preset(s, ZoomPreset.FIT_HEIGHT)
            NveKey.FIT_PAGE -> NveZoom.preset(s, ZoomPreset.FIT_PAGE)
            NveKey.ZOOM_TO -> n.open(NveSurface.ZOOM)
            NveKey.ROTATE_CW -> s.rotation = (s.rotation + 90) % 360
            NveKey.ROTATE_CCW -> s.rotation = (s.rotation + 270) % 360
            NveKey.SELECT_ALL -> s.scope.launch { NveSelectionOps.selectAll(s, s.currentPage) }
            NveKey.COPY -> { val sel = s.selection ?: return false; if (s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) com.smartdocs.editor.ui.reader.ReaderIntents.copy(ctx, sel.text) }
            NveKey.PASTE -> NveClipboard.paste(ctx, s)
            NveKey.ADD_BOOKMARK -> a?.panels?.invoke(com.smartdocs.editor.ui.reader.ReaderPanel.BOOKMARKS)
            NveKey.FIND_REPLACE -> a?.navigateTool?.invoke("find_replace")
            NveKey.DELETE_PAGE -> a?.quick?.invoke(QuickAction.DELETE_PAGE)
            NveKey.INSERT_BLANK -> a?.quick?.invoke(QuickAction.ADD_BLANK)
            NveKey.INSERT_FILE -> a?.navigateTool?.invoke("organize")
            NveKey.HIGHLIGHT -> if (s.selection != null) s.markupFromSelection(com.smartdocs.editor.core.pdf.AnnotKind.HIGHLIGHT) else tool(ReaderTool.HIGHLIGHT)
            NveKey.SQUIGGLY -> if (s.selection != null) s.markupFromSelection(com.smartdocs.editor.core.pdf.AnnotKind.SQUIGGLY) else tool(ReaderTool.SQUIGGLY)
            NveKey.STRIKE -> if (s.selection != null) s.markupFromSelection(com.smartdocs.editor.core.pdf.AnnotKind.STRIKEOUT) else tool(ReaderTool.STRIKEOUT)
            NveKey.UNDERLINE -> if (s.selection != null) s.markupFromSelection(com.smartdocs.editor.core.pdf.AnnotKind.UNDERLINE) else tool(ReaderTool.UNDERLINE)
            NveKey.PENCIL -> tool(ReaderTool.INK)
            NveKey.CALLOUT -> { v.workbenchTool = WTool.CALLOUT; v.open(V5Surface.WORKBENCH) }
            NveKey.TEXTBOX -> tool(ReaderTool.FREETEXT)
            NveKey.TYPEWRITER -> { v.workbenchTool = WTool.TYPEWRITER; v.open(V5Surface.WORKBENCH) }
            NveKey.NOTE -> tool(ReaderTool.NOTE)
            NveKey.INSERT_TEXT -> tool(ReaderTool.CARET)
            NveKey.REPLACE_TEXT -> tool(ReaderTool.REPLACE)
            NveKey.COMMENTS -> v.open(V5Surface.COMMENTS)
            NveKey.TEXT_VIEWER -> n.open(NveSurface.TEXT_VIEWER)
            NveKey.RULERS -> { v.rulers = !v.rulers; v.saveOverlays() }
            NveKey.MARQUEE -> n.dragMode = if (n.dragMode == NveDrag.ZOOM_IN) null else NveDrag.ZOOM_IN
            NveKey.NAV_PANE -> a?.panels?.invoke(com.smartdocs.editor.ui.reader.ReaderPanel.OUTLINE)
            NveKey.FULL_SCREEN -> v.open(V5Surface.PRESENT)
            NveKey.READ_MODE -> s.reflow = !s.reflow
            NveKey.AUTO_SCROLL -> if (v.autoScroll) v.autoScroll = false else v.open(V5Surface.AUTO_SCROLL)
            NveKey.READ_ALOUD -> a?.readAloud?.invoke()
            NveKey.READ_STOP -> com.smartdocs.editor.core.tts.ReadAloudController.stop()
            NveKey.HELP -> nav?.navigate(V5Routes.HELP) ?: return false
            NveKey.PREFERENCES -> nav?.navigate(Routes.SETTINGS) ?: return false
            NveKey.NIGHT_FOXIT -> nav?.navigate(Routes.tool("blank")) ?: return false
            NveKey.EMAIL -> a?.share?.invoke()
            NveKey.WORD_COUNT -> { n.wordCountText = null; n.open(NveSurface.WORD_COUNT) }
            NveKey.SNAPSHOT -> n.dragMode = NveDrag.SNAPSHOT
        }
        return true
    }
}

/** Customise keyboard shortcuts: press a new chord for an action, remove it, reset one / all; Foxit layout; single keys. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NveKeysSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    var capture by remember { mutableStateOf<NveKey?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.nv_keys_title), style = MaterialTheme.typography.titleLarge)
            @Suppress("UNUSED_EXPRESSION") ver
            SwitchLine(stringResource(R.string.nv_keys_foxit), NveKeys.foxitLayout(ctx)) { NveKeys.setFoxitLayout(ctx, it); ver++ }
            SwitchLine(stringResource(R.string.nv_keys_single), NveKeys.singleKey(ctx)) { NveKeys.setSingleKey(ctx, it); ver++ }
            TextButton(onClick = { NveKeys.resetAll(ctx); ver++ }) { Text(stringResource(R.string.nv_keys_reset_all)) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(NveKey.entries.toList(), key = { it.name }) { k ->
                    val ch = remember(ver) { NveKeys.chord(ctx, k) }
                    ListItem(headlineContent = { Text(stringResource(k.label)) },
                        supportingContent = { Text(ch?.label() ?: stringResource(R.string.nv_keys_none)) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { NveKeys.bind(ctx, k, null); ver++ }) { Text(stringResource(R.string.delete)) }
                                TextButton(onClick = { NveKeys.reset(ctx, k); ver++ }) { Text(stringResource(R.string.nv_keys_reset)) }
                            }
                        },
                        modifier = Modifier.clickable { capture = k })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    capture?.let { k ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(k) { runCatching { focus.requestFocus() } }
        AlertDialog(onDismissRequest = { capture = null },
            title = { Text(stringResource(k.label)) },
            text = {
                Box(Modifier.fillMaxWidth().height(80.dp).focusRequester(focus).focusable().onPreviewKeyEvent { ev ->
                    val ne = ev.nativeKeyEvent
                    if (ne.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
                    if (ne.keyCode in setOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)) return@onPreviewKeyEvent true
                    NveKeys.bind(ctx, k, Chord(ne.keyCode, ne.isCtrlPressed, ne.isShiftPressed, ne.isAltPressed)); ver++; capture = null; true
                }, contentAlignment = Alignment.Center) { Text(stringResource(R.string.nv_keys_press)) }
            },
            confirmButton = { TextButton(onClick = { capture = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
