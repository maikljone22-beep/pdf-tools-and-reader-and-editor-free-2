package com.smartdocs.editor.ui.view5

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tts.ReadAloudFocus
import com.smartdocs.editor.ui.reader.ReaderActions
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.reader.ToolChip
import com.smartdocs.editor.ui.reader.ViewMode
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single host of MODULE view5 in the reader (hook after Annot2Dialogs in ReaderScreen): renders the open
 * surface, records the position history, remembers per-tool settings and applies the read-aloud options.
 */
@Composable
fun View5ReaderHost(s: ReaderState, nav: NavHostController, snack: SnackbarHostState, actions: ReaderActions, outputFile: suspend () -> File?) {
    val v = V5State.of(s)
    val scope = rememberCoroutineScope()
    val env = remember(s, nav, actions) { V5Env(s, v, nav, actions) }
    SideEffect { v.env = env }
    val msg: (String) -> Unit = { m -> scope.launch { snack.showSnackbar(m) } }

    // every jump (links, outline, bookmarks, search, go-to) goes through pendingJump: record where we were
    LaunchedEffect(s) { snapshotFlow { s.pendingJump }.collect { p -> if (p != null) v.recordJump(s.currentPage, p) } }
    ToolMemoryEffect(s)
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ReadAloudOptions.apply(ctx) }

    when (v.surface) {
        V5Surface.NONE -> {}
        V5Surface.WORKBENCH -> WorkbenchSheet(v, measureFirst = v.workbenchTool.ordinal <= WTool.COUNT.ordinal, onMessage = msg)
        V5Surface.MEASURE_LIST -> MeasureListSheet(v)
        V5Surface.COMMENTS -> CommentManagerSheet(v, msg)
        V5Surface.STAMPS -> LaunchedEffect(Unit) { v.close(); actions.pickStamp() }
        V5Surface.SPLIT -> SplitCompareDialog(v, compare = false)
        V5Surface.COMPARE -> SplitCompareDialog(v, compare = true)
        V5Surface.PRESENT -> PresentationDialog(v)
        V5Surface.COMMANDS -> CommandSearchSheet(env)
        V5Surface.PRINT -> PrintSheet5(v, outputFile, msg)
        V5Surface.SHORTCUTS -> ShortcutsSheet(v)
        V5Surface.OVERLAY_SETTINGS -> OverlaySettingsSheet(v)
        V5Surface.AUTO_SCROLL -> LaunchedEffect(Unit) { v.close(); if (s.viewMode != ViewMode.CONTINUOUS) s.viewMode = ViewMode.CONTINUOUS; v.autoScroll = true; v.autoPaused = false }
        V5Surface.READ_ALOUD -> ReadAloudOptionsSheet(v)
    }
}

/** Chips added at the end of the View toolbar (hook). */
@Composable
fun View5ViewChips(s: ReaderState) {
    val v = V5State.of(s)
    VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
    ToolChip(Icons.Default.Bolt, stringResource(R.string.v5_tell_me)) { v.open(V5Surface.COMMANDS) }
    ToolChip(Icons.Default.ViewHeadline, stringResource(R.string.v5_reflow), selected = s.reflow) { s.reflow = !s.reflow }
    ToolChip(Icons.Default.VerticalSplit, stringResource(R.string.v5_split_view)) { v.open(V5Surface.SPLIT) }
    ToolChip(Icons.Default.Compare, stringResource(R.string.v5_compare)) { v.open(V5Surface.COMPARE) }
    ToolChip(Icons.Default.Slideshow, stringResource(R.string.v5_presentation)) { v.open(V5Surface.PRESENT) }
    ToolChip(Icons.Default.SwipeVertical, stringResource(R.string.v5_auto_scroll), selected = v.autoScroll) {
        if (v.autoScroll) v.autoScroll = false else v.open(V5Surface.AUTO_SCROLL)
    }
    ToolChip(Icons.Default.Straighten, stringResource(R.string.v5_rulers), selected = v.rulers) { v.rulers = !v.rulers; v.saveOverlays() }
    ToolChip(Icons.Default.Grid4x4, stringResource(R.string.v5_grid), selected = v.grid) { v.grid = !v.grid; v.saveOverlays() }
    ToolChip(Icons.Default.HorizontalRule, stringResource(R.string.v5_reading_ruler), selected = v.readingRuler) { v.readingRuler = !v.readingRuler }
    ToolChip(Icons.Default.ZoomIn, stringResource(R.string.v5_lens), selected = v.lens) { v.lens = !v.lens }
    ToolChip(Icons.Default.Tune, stringResource(R.string.v5_overlay_settings)) { v.open(V5Surface.OVERLAY_SETTINGS) }
    ToolChip(Icons.Default.Repeat, stringResource(R.string.v5_read_aloud_options)) { v.open(V5Surface.READ_ALOUD) }
}

/** Chips added to the Comment toolbar after Annot2CommentChips (hook). */
@Composable
fun View5CommentChips(s: ReaderState) {
    val v = V5State.of(s)
    VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
    ToolChip(Icons.Default.Straighten, stringResource(R.string.v5_measure)) { v.workbenchTool = WTool.DISTANCE; v.open(V5Surface.WORKBENCH) }
    ToolChip(Icons.Default.Comment, stringResource(R.string.v5_tool_callout)) { v.workbenchTool = WTool.CALLOUT; v.open(V5Surface.WORKBENCH) }
    ToolChip(Icons.Default.Keyboard, stringResource(R.string.v5_tool_typewriter)) { v.workbenchTool = WTool.TYPEWRITER; v.open(V5Surface.WORKBENCH) }
    ToolChip(Icons.Default.Check, stringResource(R.string.v5_tool_tick)) { v.workbenchTool = WTool.TICK; v.open(V5Surface.WORKBENCH) }
    ToolChip(Icons.Default.HighlightAlt, stringResource(R.string.v5_tool_area_highlight)) { v.workbenchTool = WTool.AREA_HIGHLIGHT; v.open(V5Surface.WORKBENCH) }
    ToolChip(Icons.Default.LineStyle, stringResource(R.string.v5_tool_line)) { v.workbenchTool = WTool.LINE; v.open(V5Surface.WORKBENCH) }
    ToolChip(Icons.Default.Forum, stringResource(R.string.v5_comment_manager)) { v.open(V5Surface.COMMENTS) }
    ToolChip(Icons.Default.TableChart, stringResource(R.string.v5_measurements)) { v.open(V5Surface.MEASURE_LIST) }
}

/** Items for the reader's ⋮ menu (hook, after Settings). */
@Composable
fun V5MoreMenuItems(s: ReaderState, nav: NavHostController, close: () -> Unit) {
    val v = V5State.of(s)
    HorizontalDivider()
    DropdownMenuItem(text = { Text(stringResource(R.string.v5_tell_me)) }, leadingIcon = { Icon(Icons.Default.Bolt, null) }, onClick = { close(); v.open(V5Surface.COMMANDS) })
    DropdownMenuItem(text = { Text(stringResource(R.string.v5_comment_manager)) }, leadingIcon = { Icon(Icons.Default.Forum, null) }, onClick = { close(); v.open(V5Surface.COMMENTS) })
    DropdownMenuItem(text = { Text(stringResource(R.string.v5_presentation)) }, leadingIcon = { Icon(Icons.Default.Slideshow, null) }, onClick = { close(); v.open(V5Surface.PRESENT) })
    DropdownMenuItem(text = { Text(stringResource(R.string.v5_keyboard_shortcuts)) }, leadingIcon = { Icon(Icons.Default.Keyboard, null) }, onClick = { close(); v.open(V5Surface.SHORTCUTS) })
    DropdownMenuItem(text = { Text(stringResource(R.string.v5_help_center)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, null) }, onClick = { close(); nav.navigate(V5Routes.HELP) })
}

// ---------------------------------------------------------------- remember last settings per tool

private val remembered = setOf(
    ReaderTool.HIGHLIGHT, ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY, ReaderTool.NOTE, ReaderTool.FREETEXT, ReaderTool.INK,
    ReaderTool.LINE, ReaderTool.ARROW, ReaderTool.RECT, ReaderTool.OVAL, ReaderTool.TEXTBOX, ReaderTool.POLYGON, ReaderTool.POLYLINE, ReaderTool.CLOUD,
    ReaderTool.CARET, ReaderTool.REPLACE, ReaderTool.FS_TEXT, ReaderTool.FS_CHECK, ReaderTool.FS_CROSS, ReaderTool.FS_DOT, ReaderTool.FS_LINE, ReaderTool.FS_DATE,
    ReaderTool.FS_RRECT, ReaderTool.FS_COMB,
)

/**
 * Remembers colour, opacity, thickness and font size per annotation tool (prefs "view5_tools") and puts
 * them back when the tool is picked again. Values change → saved for the active tool; tool changes → restored.
 */
@Composable
fun ToolMemoryEffect(s: ReaderState) {
    val ctx = LocalContext.current
    LaunchedEffect(s) {
        val p = ctx.getSharedPreferences("view5_tools", Context.MODE_PRIVATE)
        if (!ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE).getBoolean("tool_memory", true)) return@LaunchedEffect
        var lastTool: ReaderTool? = s.tool
        snapshotFlow { listOf(s.tool, s.color, s.opacity, s.thickness, s.fontSize) }.collect { l ->
            val t = l[0] as ReaderTool?
            if (t != lastTool) {
                lastTool = t
                if (t != null && t in remembered && p.contains("${t.name}.c")) {
                    s.color = p.getInt("${t.name}.c", s.color); s.opacity = p.getFloat("${t.name}.o", s.opacity)
                    s.thickness = p.getFloat("${t.name}.w", s.thickness); s.fontSize = p.getFloat("${t.name}.f", s.fontSize)
                }
            } else if (t != null && t in remembered) {
                p.edit().putInt("${t.name}.c", s.color).putFloat("${t.name}.o", s.opacity).putFloat("${t.name}.w", s.thickness).putFloat("${t.name}.f", s.fontSize).apply()
            }
        }
    }
}

// ---------------------------------------------------------------- read aloud options

object ReadAloudOptions {
    fun apply(ctx: Context) {
        val p = ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE)
        ReadAloudFocus.repeat = ReadAloudFocus.Repeat.entries.getOrElse(p.getInt("tts_repeat", 0)) { ReadAloudFocus.Repeat.OFF }
        ReadAloudFocus.pauseOnFocusLoss = p.getBoolean("tts_focus", true)
    }
    fun save(ctx: Context) {
        ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE).edit()
            .putInt("tts_repeat", ReadAloudFocus.repeat.ordinal).putBoolean("tts_focus", ReadAloudFocus.pauseOnFocusLoss).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudOptionsSheet(v: V5State) {
    val ctx = LocalContext.current
    var repeat by remember { mutableStateOf(ReadAloudFocus.repeat) }
    var focus by remember { mutableStateOf(ReadAloudFocus.pauseOnFocusLoss) }
    var memory by remember { mutableStateOf(v.prefs.getBoolean("tool_memory", true)) }
    ModalBottomSheet(onDismissRequest = { v.close() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.v5_read_aloud_options), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.v5_repeat), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ReadAloudFocus.Repeat.OFF to R.string.v5_repeat_off, ReadAloudFocus.Repeat.PAGE to R.string.v5_repeat_page, ReadAloudFocus.Repeat.DOCUMENT to R.string.v5_repeat_doc).forEach { (r, l) ->
                    FilterChip(repeat == r, { repeat = r; ReadAloudFocus.repeat = r; ReadAloudOptions.save(ctx) }, label = { Text(stringResource(l)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(focus, { focus = it; ReadAloudFocus.pauseOnFocusLoss = it; ReadAloudOptions.save(ctx) })
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.v5_pause_on_call))
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(memory, { memory = it; v.prefs.edit().putBoolean("tool_memory", it).apply() })
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.v5_tool_memory))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- overlay settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlaySettingsSheet(v: V5State) {
    ModalBottomSheet(onDismissRequest = { v.saveOverlays(); v.close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.v5_overlay_settings), style = MaterialTheme.typography.titleLarge)
            SwitchRow(stringResource(R.string.v5_rulers), v.rulers) { v.rulers = it }
            SwitchRow(stringResource(R.string.v5_grid), v.grid) { v.grid = it }
            Text(stringResource(R.string.v5_grid_spacing, v.gridMm.toInt()), style = MaterialTheme.typography.labelMedium)
            Slider(v.gridMm, { v.gridMm = it }, valueRange = 2f..50f)
            NpfGridSettings()   // nav-prefs: snap, subdivisions, offsets, colour, guides
            HorizontalDivider()
            SwitchRow(stringResource(R.string.v5_reading_ruler), v.readingRuler) { v.readingRuler = it }
            SwitchRow(stringResource(R.string.v5_dim_outside), v.rulerDim) { v.rulerDim = it }
            Text(stringResource(R.string.v5_band_height, v.rulerHeightDp.toInt()), style = MaterialTheme.typography.labelMedium)
            Slider(v.rulerHeightDp, { v.rulerHeightDp = it }, valueRange = 24f..160f)
            Palette(v.rulerColor, allowNone = false) { v.rulerColor = it ?: 0xFFEB3B }
            HorizontalDivider()
            SwitchRow(stringResource(R.string.v5_lens), v.lens) { v.lens = it }
            Text(stringResource(R.string.v5_lens_zoom, "%.1f".format(java.util.Locale.US, v.lensZoom)), style = MaterialTheme.typography.labelMedium)
            Slider(v.lensZoom, { v.lensZoom = it }, valueRange = 1.5f..5f)
            HorizontalDivider()
            Text(stringResource(R.string.v5_speed_n, v.autoSpeed), style = MaterialTheme.typography.labelMedium)
            Slider(v.autoSpeed.toFloat(), { v.autoSpeed = it.toInt() }, valueRange = 1f..10f, steps = 8)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(on, onChange)
    }
}
