package com.smartdocs.editor.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.onboarding.CoachIds
import com.smartdocs.editor.ui.onboarding.coachTarget
import com.smartdocs.editor.ui.quick.QuickAction

/** Everything the toolbars can trigger that lives outside [ReaderState] (sheets, navigation, pickers). */
class ReaderActions(
    val viewModeSheet: () -> Unit,
    val pageColor: () -> Unit,
    val autoFlip: () -> Unit,
    val goToPage: () -> Unit,
    val panels: (ReaderPanel) -> Unit,
    val docInfo: () -> Unit,
    val search: () -> Unit,
    val readAloud: () -> Unit,
    val share: () -> Unit,
    val print: () -> Unit,
    val save: () -> Unit,
    val saveAs: () -> Unit,
    val pickImage: () -> Unit,
    val pickStamp: () -> Unit,
    val pickSignature: (String) -> Unit,
    val navigateTool: (String) -> Unit,
    val ai: () -> Unit,
    /** One-step document tools (compress, OCR, watermark…): run on the open document in place. */
    val quick: (com.smartdocs.editor.ui.quick.QuickAction) -> Unit = {},
)

enum class ReaderPanel { OUTLINE, BOOKMARKS, ANNOTATIONS, THUMBNAILS, INFO }

@Composable
fun ReaderTabRow(state: ReaderState, onTab: (ReaderTab) -> Unit) {
    val tabs = listOf(
        ReaderTab.VIEW to R.string.tab_view, ReaderTab.COMMENT to R.string.tab_comment, ReaderTab.CUSTOM to R.string.an_custom_tab, ReaderTab.EDIT to R.string.tab_edit,
        ReaderTab.FILL_SIGN to R.string.tab_fill_sign, ReaderTab.SIGN to R.string.tab_sign, ReaderTab.TOOLS to R.string.tab_tools, ReaderTab.PAGES to R.string.tab_pages,
    )
    val idx = tabs.indexOfFirst { it.first == state.tab }.coerceAtLeast(0)
    ScrollableTabRow(selectedTabIndex = idx, modifier = Modifier.coachTarget(CoachIds.READER_TABS), edgePadding = 8.dp, divider = {}, containerColor = MaterialTheme.colorScheme.surface) {
        tabs.forEach { (t, label) ->
            Tab(selected = state.tab == t, onClick = { onTab(t) }, text = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
}

/** Rounded tool chip: icon over a tiny label, highlighted when selected. */
@Composable
fun ToolChip(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f) else if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(24.dp), tint = tint?.takeIf { enabled } ?: fg)
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ToolRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun BottomToolbar(state: ReaderState, actions: ReaderActions) {
    Surface(Modifier.coachTarget(CoachIds.READER_TOOLBAR), tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            when (state.tab) {
                ReaderTab.VIEW -> ViewToolbar(state, actions)
                ReaderTab.COMMENT -> { PropertyBar(state); CommentToolbar(state, actions) }
                ReaderTab.EDIT -> { if (state.tool != null) PropertyBar(state); EditToolbar(state, actions) }
                ReaderTab.FILL_SIGN -> { if (state.tool != null) PropertyBar(state); FillSignToolbar(state) }
                ReaderTab.SIGN -> SignToolbar(state, actions)
                ReaderTab.TOOLS -> ToolsToolbar(actions)
                ReaderTab.PAGES -> {}
                ReaderTab.CUSTOM -> { if (state.tool != null) PropertyBar(state); com.smartdocs.editor.ui.reader.annot.CustomToolbar(state, actions) }
            }
        }
    }
}

@Composable
private fun ViewToolbar(state: ReaderState, a: ReaderActions) {
    ToolRow {
        ToolChip(Icons.Default.ViewDay, stringResource(R.string.rd_view_mode), onClick = a.viewModeSheet)
        ToolChip(if (state.fitPage) Icons.Default.FitScreen else Icons.Default.WidthFull, stringResource(if (state.fitPage) R.string.fit_page else R.string.fit_width), selected = state.fitPage) { state.fitPage = !state.fitPage; state.zoom = 1f }
        ToolChip(Icons.Default.DarkMode, stringResource(R.string.night_mode), selected = state.nightMode) { state.nightMode = !state.nightMode }
        ToolChip(Icons.Default.Palette, stringResource(R.string.page_color), onClick = a.pageColor)
        ToolChip(Icons.Default.ViewHeadline, stringResource(R.string.reflow), selected = state.reflow) { state.reflow = !state.reflow }
        ToolChip(Icons.Default.Timer, stringResource(R.string.auto_flip), selected = state.autoFlipSeconds > 0, onClick = a.autoFlip)
        ToolChip(Icons.Default.RotateRight, stringResource(R.string.rotate_view)) { state.rotation = (state.rotation + 90) % 360 }
        // view module: crop mode (auto toggle; the View sheet has the full menu) and the pan & zoom window
        ToolChip(Icons.Default.Crop, stringResource(R.string.crop_mode), selected = state.vx.crop.enabled) {
            if (state.vx.crop.enabled) state.vx.crop.disable() else { state.vx.crop.enableAuto(); state.reflow = false; state.vx.magnifier = false }
        }
        ToolChip(Icons.Default.ZoomIn, stringResource(R.string.vw_magnifier), selected = state.vx.magnifier, enabled = !state.vx.crop.enabled && !state.reflow) { state.vx.magnifier = !state.vx.magnifier }
        ToolChip(Icons.Default.Search, stringResource(R.string.search), selected = state.searchOpen, onClick = a.search)
        ToolChip(Icons.Default.FormatListBulleted, stringResource(R.string.rd_panels)) { a.panels(ReaderPanel.OUTLINE) }
        ToolChip(Icons.Default.Bookmark, stringResource(R.string.panel_bookmarks)) { a.panels(ReaderPanel.BOOKMARKS) }
        ToolChip(Icons.Default.GridView, stringResource(R.string.panel_thumbnails)) { a.panels(ReaderPanel.THUMBNAILS) }
        ToolChip(Icons.Default.RecordVoiceOver, stringResource(R.string.tool_read_aloud), onClick = a.readAloud)
        if (com.smartdocs.editor.LocalSettings.current.aiEnabled) ToolChip(Icons.Default.AutoAwesome, stringResource(R.string.tool_ai), onClick = a.ai)
        ToolChip(Icons.Default.Share, stringResource(R.string.share), onClick = a.share)
        ToolChip(Icons.Default.Print, stringResource(R.string.print), onClick = a.print)
        ToolChip(Icons.Default.Info, stringResource(R.string.tool_doc_info), onClick = a.docInfo)
        com.smartdocs.editor.ui.view5.View5ViewChips(state)
    }
}

private data class ToolDef(val tool: ReaderTool, val icon: ImageVector, val label: Int)

private val commentTools = listOf(
    ToolDef(ReaderTool.HIGHLIGHT, Icons.Default.Highlight, R.string.annot_highlight),
    ToolDef(ReaderTool.UNDERLINE, Icons.Default.FormatUnderlined, R.string.annot_underline),
    ToolDef(ReaderTool.STRIKEOUT, Icons.Default.FormatStrikethrough, R.string.annot_strikeout),
    ToolDef(ReaderTool.SQUIGGLY, Icons.Default.Waves, R.string.annot_squiggly),
    ToolDef(ReaderTool.NOTE, Icons.Default.StickyNote2, R.string.annot_note),
    ToolDef(ReaderTool.FREETEXT, Icons.Default.TextFields, R.string.annot_text),
    ToolDef(ReaderTool.INK, Icons.Default.Draw, R.string.annot_ink),
    ToolDef(ReaderTool.ERASER, Icons.Default.CleaningServices, R.string.annot_eraser),
    ToolDef(ReaderTool.LINE, Icons.Default.HorizontalRule, R.string.annot_line),
    ToolDef(ReaderTool.ARROW, Icons.Default.ArrowOutward, R.string.annot_arrow),
    ToolDef(ReaderTool.RECT, Icons.Default.CropSquare, R.string.annot_rect),
    ToolDef(ReaderTool.OVAL, Icons.Default.Circle, R.string.annot_oval),
    ToolDef(ReaderTool.STAMP, Icons.Default.Approval, R.string.annot_stamp),
    ToolDef(ReaderTool.MEASURE, Icons.Default.Straighten, R.string.annot_measure),
)

@Composable
private fun CommentToolbar(state: ReaderState, a: ReaderActions) {
    ToolRow {
        commentTools.forEach { d ->
            ToolChip(d.icon, stringResource(d.label), selected = state.tool == d.tool) {
                if (d.tool == ReaderTool.STAMP) { state.selectTool(ReaderTool.STAMP); if (state.tool == ReaderTool.STAMP) a.pickStamp() }
                else state.selectTool(d.tool)
            }
        }
        com.smartdocs.editor.ui.reader.annot.Annot2CommentChips(state, a)      // MODULE_annot2 tools + comments menu
        com.smartdocs.editor.ui.view5.View5CommentChips(state)
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        ToolChip(Icons.Default.List, stringResource(R.string.panel_annotations)) { a.panels(ReaderPanel.ANNOTATIONS) }
    }
}

@Composable
private fun EditToolbar(state: ReaderState, a: ReaderActions) {
    ToolRow {
        ToolChip(Icons.Default.EditNote, stringResource(R.string.ed_content_edit)) { a.navigateTool("content_edit") }
        ToolChip(Icons.Default.TextFields, stringResource(R.string.rd_add_text), selected = state.tool == ReaderTool.TEXTBOX) { state.selectTool(ReaderTool.TEXTBOX) }
        ToolChip(Icons.Default.Image, stringResource(R.string.rd_add_image), selected = state.tool == ReaderTool.IMAGE) { state.selectTool(ReaderTool.IMAGE); if (state.tool == ReaderTool.IMAGE) a.pickImage() }
        ToolChip(Icons.Default.Link, stringResource(R.string.annot_link), selected = state.tool == ReaderTool.LINK) { state.selectTool(ReaderTool.LINK) }
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        ToolChip(Icons.Default.DocumentScanner, stringResource(R.string.tool_ocr)) { a.quick(QuickAction.OCR) }
        ToolChip(Icons.Default.BrandingWatermark, stringResource(R.string.tool_watermark)) { a.quick(QuickAction.WATERMARK) }
        ToolChip(Icons.Default.FormatListNumbered, stringResource(R.string.tool_page_numbers)) { a.quick(QuickAction.PAGE_NUMBERS) }
        ToolChip(Icons.Default.Info, stringResource(R.string.tool_doc_info)) { a.quick(QuickAction.PROPERTIES) }
        ToolChip(Icons.Default.AutoAwesomeMotion, stringResource(R.string.tool_organize)) { a.navigateTool("organize") }
        ToolChip(Icons.Default.AddBox, stringResource(R.string.fx_edit_fields)) { a.navigateTool("form_builder") }   // formsx: form designer
    }
}

@Composable
private fun FillSignToolbar(state: ReaderState) {
    val app = com.smartdocs.editor.LocalApp.current
    val fields by app.db.fillProfile().all().collectAsState(initial = emptyList())
    ToolRow {
        ToolChip(Icons.Default.TextFields, stringResource(R.string.fill_sign_text), selected = state.tool == ReaderTool.FS_TEXT) { state.selectTool(ReaderTool.FS_TEXT) }
        ToolChip(Icons.Default.Check, stringResource(R.string.fill_sign_check), selected = state.tool == ReaderTool.FS_CHECK) { state.selectTool(ReaderTool.FS_CHECK) }
        ToolChip(Icons.Default.Close, stringResource(R.string.fill_sign_cross), selected = state.tool == ReaderTool.FS_CROSS) { state.selectTool(ReaderTool.FS_CROSS) }
        ToolChip(Icons.Default.FiberManualRecord, stringResource(R.string.fill_sign_dot), selected = state.tool == ReaderTool.FS_DOT) { state.selectTool(ReaderTool.FS_DOT) }
        ToolChip(Icons.Default.HorizontalRule, stringResource(R.string.fill_sign_line), selected = state.tool == ReaderTool.FS_LINE) { state.selectTool(ReaderTool.FS_LINE) }
        ToolChip(Icons.Default.Today, stringResource(R.string.fill_sign_date), selected = state.tool == ReaderTool.FS_DATE) {
            state.selectTool(ReaderTool.FS_DATE)
            if (state.tool == ReaderTool.FS_DATE) state.pendingText = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(java.util.Date())
        }
        com.smartdocs.editor.ui.forms5.FillSignFormChips(state)     // rectangle, comb text, form menu (data, JavaScript)
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        if (fields.isEmpty()) {
            Text(stringResource(R.string.rd_profile_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
        fields.forEach { f ->
            val sel = state.tool == ReaderTool.FS_PROFILE && state.pendingText == f.value
            FilterChip(selected = sel, onClick = {
                if (sel) state.selectTool(null) else { state.tool = ReaderTool.FS_PROFILE; state.pendingText = f.value; state.selection = null }
            }, label = { Text(f.label.ifBlank { f.value }, maxLines = 1) }, leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(16.dp)) }, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

@Composable
private fun SignToolbar(state: ReaderState, a: ReaderActions) {
    val app = com.smartdocs.editor.LocalApp.current
    val sigs by app.db.signatures().all().collectAsState(initial = emptyList())
    val placing = state.placement != null
    ToolRow {
        if (placing) {
            FilledTonalButton(onClick = { state.confirmPlacement() }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.done)) }
            TextButton(onClick = { state.cancelPlacement() }) { Text(stringResource(R.string.cancel)) }
            VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        }
        ToolChip(Icons.Default.Add, stringResource(R.string.sig_create)) { a.navigateTool("signatures") }
        ToolChip(Icons.Default.VerifiedUser, stringResource(R.string.sig_digital)) { a.navigateTool("digital_sign") }
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        if (sigs.isEmpty()) Text(stringResource(R.string.rd_no_signatures), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        sigs.forEach { s ->
            val selected = state.tool == ReaderTool.SIGNATURE && state.pendingText == s.path
            Box(
                Modifier.padding(2.dp).size(width = 84.dp, height = 48.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable { a.pickSignature(s.path) },
                contentAlignment = Alignment.Center,
            ) {
                coil.compose.AsyncImage(model = java.io.File(s.path), contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp))
                if (s.isInitials) Text(stringResource(R.string.sig_initials), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp), color = Color.Gray)
            }
        }
    }
}

/** Tools tab of the reader: one-step actions on the open document first, then the full tool screens (opened on this file). */
private sealed interface ReaderToolItem {
    val label: Int; val icon: ImageVector
    data class Quick(val action: QuickAction, override val label: Int, override val icon: ImageVector) : ReaderToolItem
    data class Screen(val id: String, override val label: Int, override val icon: ImageVector) : ReaderToolItem
}

private val readerTools: List<ReaderToolItem> = listOf(
    ReaderToolItem.Quick(QuickAction.COMPRESS, R.string.tool_compress, Icons.Default.Compress),
    ReaderToolItem.Quick(QuickAction.OCR, R.string.tool_ocr, Icons.Default.DocumentScanner),
    ReaderToolItem.Quick(QuickAction.FIX_TEXT, R.string.tool_fix_text, Icons.Default.Spellcheck),
    ReaderToolItem.Quick(QuickAction.WATERMARK, R.string.tool_watermark, Icons.Default.BrandingWatermark),
    ReaderToolItem.Quick(QuickAction.PAGE_NUMBERS, R.string.tool_page_numbers, Icons.Default.FormatListNumbered),
    ReaderToolItem.Quick(QuickAction.PROTECT, R.string.tool_protect, Icons.Default.Lock),
    ReaderToolItem.Quick(QuickAction.UNLOCK, R.string.tool_unlock, Icons.Default.LockOpen),
    ReaderToolItem.Quick(QuickAction.FLATTEN, R.string.tool_flatten, Icons.Default.Layers),
    ReaderToolItem.Quick(QuickAction.ROTATE_ALL, R.string.qk_rot_label, Icons.Default.RotateRight),
    ReaderToolItem.Quick(QuickAction.ADD_BLANK, R.string.qk_blank_label, Icons.Default.NoteAdd),
    ReaderToolItem.Quick(QuickAction.DELETE_PAGE, R.string.qk_del_label, Icons.Default.DeleteOutline),
    ReaderToolItem.Quick(QuickAction.EXTRACT, R.string.tool_extract_pages, Icons.Default.ContentCut),
    ReaderToolItem.Quick(QuickAction.PROPERTIES, R.string.tool_doc_info, Icons.Default.Info),
    ReaderToolItem.Quick(QuickAction.PRINT, R.string.print, Icons.Default.Print),
    ReaderToolItem.Quick(QuickAction.SHARE, R.string.share, Icons.Default.Share),
    ReaderToolItem.Screen("organize", R.string.tool_organize, Icons.Default.AutoAwesomeMotion),
    ReaderToolItem.Screen("redact", R.string.tool_redact, Icons.Default.Block),
    ReaderToolItem.Screen("find_replace", R.string.s5_replace_title, Icons.Default.FindReplace),
    ReaderToolItem.Screen("search_highlight", R.string.s5_mark_title, Icons.Default.BorderColor),
    ReaderToolItem.Screen("sanitize", R.string.s5_sanitize_title, Icons.Default.CleaningServices),
    ReaderToolItem.Screen("protect_advanced", R.string.s5_protect_title, Icons.Default.EnhancedEncryption),
    ReaderToolItem.Screen("forms", R.string.tool_forms, Icons.Default.Checklist),
    ReaderToolItem.Screen("ai", R.string.tool_ai, Icons.Default.AutoAwesome),
    ReaderToolItem.Screen("merge", R.string.tool_merge, Icons.Default.CallMerge),
    ReaderToolItem.Screen("split", R.string.tool_split, Icons.Default.CallSplit),
    ReaderToolItem.Screen("compare", R.string.tool_compare, Icons.Default.Compare),
    ReaderToolItem.Screen("pdf_to_images", R.string.tool_pdf_to_images, Icons.Default.Collections),
    ReaderToolItem.Screen("pdf_to_text", R.string.tool_pdf_to_text, Icons.Default.Subject),
    ReaderToolItem.Screen("convert_hub", R.string.cv6_title, Icons.Default.SwapHoriz),
    ReaderToolItem.Screen("extract_images", R.string.cv6_t_extract_images, Icons.Default.Collections),
)

@Composable
private fun ToolsToolbar(a: ReaderActions) {
    val g6Settings = com.smartdocs.editor.LocalSettings.current
    val g6ReaderTools = remember(g6Settings.aiEnabled) { readerTools.filter { it !is ReaderToolItem.Screen || com.smartdocs.editor.core.gaps6.AiGate.allowsTool(g6Settings, it.id) } }
    LazyHorizontalGrid(rows = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().height(132.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        items(g6ReaderTools) { t ->
            ToolChip(t.icon, stringResource(t.label)) {
                when (t) {
                    is ReaderToolItem.Quick -> a.quick(t.action)
                    is ReaderToolItem.Screen -> a.navigateTool(t.id)
                }
            }
        }
    }
}

/** Colour palette, custom hex, opacity, thickness, font size, undo/redo and "keep creating". */
@Composable
fun PropertyBar(state: ReaderState) {
    var showMore by remember { mutableStateOf(false) }
    var hex by remember { mutableStateOf("") }
    val t = state.tool
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = { state.undo() }, enabled = state.canUndo, modifier = Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
            IconButton(onClick = { state.redo() }, enabled = state.canRedo, modifier = Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
            VerticalDivider(Modifier.height(24.dp))
            PALETTE.forEach { c -> ColorDot(c, state.color == c) { state.color = c } }
            Box(Modifier.size(28.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { showMore = !showMore }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Tune, stringResource(R.string.rd_custom_color), Modifier.size(16.dp))
            }
            VerticalDivider(Modifier.height(24.dp))
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { o ->
                FilterChip(selected = state.opacity == o, onClick = { state.opacity = o }, label = { Text("${(o * 100).toInt()}%") }, modifier = Modifier.height(30.dp))
            }
            VerticalDivider(Modifier.height(24.dp))
            FilterChip(selected = state.continueCreating, onClick = { state.continueCreating = !state.continueCreating },
                label = { Text(stringResource(R.string.rd_continue_creating)) }, leadingIcon = { Icon(Icons.Default.Repeat, null, Modifier.size(16.dp)) }, modifier = Modifier.height(30.dp))
        }
        if (showMore) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hex, { v -> hex = v.take(7); v.removePrefix("#").toIntOrNull(16)?.let { if (v.removePrefix("#").length == 6) state.color = it } },
                    label = { Text(stringResource(R.string.rd_custom_color)) }, placeholder = { Text("#FF5722") }, singleLine = true, modifier = Modifier.width(140.dp))
                val thick = t == ReaderTool.INK || t == ReaderTool.LINE || t == ReaderTool.ARROW || t == ReaderTool.RECT || t == ReaderTool.OVAL || t == ReaderTool.ERASER || t == null
                val fontT = t == ReaderTool.FREETEXT || t == ReaderTool.TEXTBOX || t == ReaderTool.FS_TEXT || t == ReaderTool.FS_PROFILE || t == ReaderTool.FS_DATE || t == ReaderTool.NOTE
                        || t == ReaderTool.FS_CHECK || t == ReaderTool.FS_CROSS || t == ReaderTool.FS_DOT || t == ReaderTool.FS_LINE
                Column(Modifier.weight(1f)) {
                    if (thick) {
                        Text("${stringResource(R.string.prop_thickness)}: ${"%.1f".format(state.thickness)}", style = MaterialTheme.typography.labelSmall)
                        Slider(state.thickness, { state.thickness = it }, valueRange = 0.5f..24f, modifier = Modifier.height(28.dp))
                    }
                    if (fontT || !thick) {
                        Text("${stringResource(R.string.prop_font_size)}: ${state.fontSize.toInt()}", style = MaterialTheme.typography.labelSmall)
                        Slider(state.fontSize, { state.fontSize = it }, valueRange = 6f..72f, modifier = Modifier.height(28.dp))
                    }
                }
            }
        }
        com.smartdocs.editor.ui.reader.annot.Annot2PropertyRow(state)      // MODULE_annot2: polygon progress, fill, cloud
        com.smartdocs.editor.ui.reader.annot.CreationExtrasRow(state)      // section 7: fill, dash, no border, line ends, rich text, icons, tickmarks
    }
}

@Composable
fun ColorDot(rgb: Int, selected: Boolean, size: androidx.compose.ui.unit.Dp = 28.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(annotColor(rgb))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = if (rgb == 0xFFEB3B || rgb == 0x8BC34A) Color.Black else Color.White) }
}
