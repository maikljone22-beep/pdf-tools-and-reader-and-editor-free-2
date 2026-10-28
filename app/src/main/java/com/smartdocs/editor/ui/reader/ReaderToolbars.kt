package com.smartdocs.editor.ui.reader

import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.draw.rotate
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
    /** Tab audit D25: E-sign request from the Sign tab (the same route as the top-bar menu). */
    val esign: () -> Unit = {},
)

enum class ReaderPanel { OUTLINE, BOOKMARKS, ANNOTATIONS, THUMBNAILS, ATTACHMENTS, INFO }

@Composable
fun ReaderTabRow(state: ReaderState, onTab: (ReaderTab) -> Unit) {
    val tabs = listOf(
        ReaderTab.VIEW to R.string.tab_view, ReaderTab.COMMENT to R.string.tab_comment, ReaderTab.CUSTOM to R.string.an_custom_tab, ReaderTab.EDIT to R.string.tab_edit,
        ReaderTab.FILL_SIGN to R.string.tab_fill_sign, ReaderTab.SIGN to R.string.tab_sign, ReaderTab.TOOLS to R.string.tab_tools, ReaderTab.PAGES to R.string.tab_pages,
    )
    // NVE (SHL-3): tab order / hidden tabs / custom tab name from the toolbar customisation
    val nveCtx = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE") val nveVer = com.smartdocs.editor.ui.nve.shell.NveShellPrefs.version.intValue
    val nveVisible = com.smartdocs.editor.ui.nve.shell.NveToolbarPrefs.visibleTabs(nveCtx)
    val tabsShown = nveVisible.mapNotNull { id -> tabs.firstOrNull { it.first.name == id } }.ifEmpty { tabs }
    val nveCustomName = com.smartdocs.editor.ui.nve.shell.NveShellPrefs.customTabName(nveCtx)
    val nveUserTabs = com.smartdocs.editor.ui.nve.shell.NveUserTabs.tabs(nveCtx)
    val nveState = com.smartdocs.editor.ui.nve.NveState.of(state)
    val nveUserIdx = nveUserTabs.indexOfFirst { it.id == nveState.userTab }
    // tab-view-core V-36: the current tab was hidden in Customise toolbar: switch to the first visible one (its tools too)
    androidx.compose.runtime.LaunchedEffect(tabsShown, state.tab, nveUserIdx) {
        if (nveUserIdx < 0 && tabsShown.none { it.first == state.tab }) tabsShown.firstOrNull { it.first != ReaderTab.PAGES }?.let { state.tab = it.first; state.selectTool(null) }
    }
    val idx = if (nveUserIdx >= 0) tabsShown.size + nveUserIdx else tabsShown.indexOfFirst { it.first == state.tab }.coerceAtLeast(0)
    ScrollableTabRow(selectedTabIndex = idx, modifier = Modifier.coachTarget(CoachIds.READER_TABS), edgePadding = 8.dp, divider = {}, containerColor = MaterialTheme.colorScheme.surface) {
        tabsShown.forEach { (t, label) ->
            Tab(selected = nveUserIdx < 0 && state.tab == t, onClick = { nveState.userTab = null; onTab(t) }, text = { Text(if (t == ReaderTab.CUSTOM && nveCustomName != null) nveCustomName else stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
        // NVE: user-defined tabs (customise ribbon)
        nveUserTabs.forEach { ut ->
            Tab(selected = nveState.userTab == ut.id, onClick = { nveState.userTab = ut.id; state.selectTool(null) }, text = { Text(ut.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(com.smartdocs.editor.ui.nve.shell.NveUserTabs.icon(ut.icon), null, Modifier.size(16.dp)) })
        }
    }
}

/** Rounded tool chip: icon over a tiny label, highlighted when selected. */
@Composable
fun ToolChip(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, tint: Color? = null, maxLines: Int = 2, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f) else if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // tab-view-core E-15: the label is read once (icon without its own description) and may take two lines
        Icon(icon, null, Modifier.size(24.dp), tint = tint?.takeIf { enabled } ?: fg)
        Spacer(Modifier.height(3.dp))
        // tabs audit CM-19: two lines (Arabic labels such as "مدير التعليقات" were cut at one line)
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 12.sp)
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
            val nveUser = com.smartdocs.editor.ui.nve.NveState.of(state).userTab      // NVE: a user tab replaces the built-in toolbars
            if (nveUser != null) com.smartdocs.editor.ui.nve.shell.NveUserTabToolbar(nveUser, actions.navigateTool, onMissing = { com.smartdocs.editor.ui.nve.NveState.of(state).userTab = null }) else
            when (state.tab) {
                ReaderTab.VIEW -> ViewToolbar(state, actions)
                ReaderTab.COMMENT -> { if (state.tool != null) PropertyBar(state); CommentToolbar(state, actions) }
                ReaderTab.EDIT -> { if (state.tool != null) PropertyBar(state); EditToolbar(state, actions) }
                ReaderTab.FILL_SIGN -> { if (state.tool != null) PropertyBar(state); FillSignToolbar(state, actions) }
                ReaderTab.SIGN -> SignToolbar(state, actions)
                ReaderTab.TOOLS -> ToolsToolbar(actions)
                ReaderTab.PAGES -> Unit   // audit 5 G-05: never shown, the Pages tab opens the organizer (ReaderScreen)
                ReaderTab.CUSTOM -> { if (state.tool != null) PropertyBar(state); com.smartdocs.editor.ui.reader.annot.CustomToolbar(state, actions) }
            }
        }
    }
}

@Composable
private fun ViewToolbar(state: ReaderState, a: ReaderActions) {
    // NVE (SHL-3): View chips the user hid
    val nveCtx = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE") val nveVer = com.smartdocs.editor.ui.nve.shell.NveShellPrefs.version.intValue
    val nveHidden = com.smartdocs.editor.ui.nve.shell.NveToolbarPrefs.hiddenChips(nveCtx)
    ToolRow {
        if ("view_mode" !in nveHidden) ToolChip(Icons.Default.ViewDay, stringResource(R.string.rd_view_mode), onClick = a.viewModeSheet)
        if ("fit" !in nveHidden) ToolChip(if (state.fitPage) Icons.Default.FitScreen else Icons.Default.WidthFull, stringResource(if (state.fitPage) R.string.fit_page else R.string.fit_width), selected = state.fitPage) { state.fitPage = !state.fitPage; state.zoom = 1f }
        if ("night" !in nveHidden) ToolChip(Icons.Default.DarkMode, stringResource(R.string.night_mode), selected = state.nightMode) { state.nightMode = !state.nightMode }
        if ("page_color" !in nveHidden) ToolChip(Icons.Default.Palette, stringResource(R.string.page_color), onClick = a.pageColor)
        if ("reflow" !in nveHidden) ToolChip(Icons.Default.ViewHeadline, stringResource(R.string.reflow), selected = state.reflow) { state.reflow = !state.reflow }
        if ("auto_flip" !in nveHidden) ToolChip(Icons.Default.Timer, stringResource(R.string.auto_flip), selected = state.autoFlipSeconds > 0, onClick = a.autoFlip)
        if ("rotate" !in nveHidden) ToolChip(Icons.Default.RotateRight, stringResource(R.string.rotate_view)) { state.rotation = (state.rotation + 90) % 360 }
        // view module: crop mode (auto toggle; the View sheet has the full menu) and the pan & zoom window
        if ("crop" !in nveHidden) ToolChip(Icons.Default.Crop, stringResource(R.string.crop_mode), selected = state.vx.crop.enabled) {
            if (state.vx.crop.enabled) state.vx.crop.disable() else { state.vx.crop.enableAuto(); state.reflow = false; state.vx.magnifier = false; com.smartdocs.editor.ui.view5.V5State.of(state).lens = false }
        }
        // tab-view-core V-10: the one magnifier chip (the v5 lens stays in Tell me; only one of them is on at a time)
        if ("magnifier" !in nveHidden) ToolChip(Icons.Default.ZoomIn, stringResource(R.string.vw_magnifier), selected = state.vx.magnifier, enabled = !state.vx.crop.enabled && !state.reflow) { state.vx.magnifier = !state.vx.magnifier; if (state.vx.magnifier) com.smartdocs.editor.ui.view5.V5State.of(state).lens = false }
        if ("search" !in nveHidden) ToolChip(Icons.Default.Search, stringResource(R.string.search), selected = state.searchOpen, onClick = a.search)
        if ("panels" !in nveHidden) ToolChip(Icons.Default.FormatListBulleted, stringResource(R.string.rd_panels)) { a.panels(ReaderPanel.OUTLINE) }
        if ("bookmarks" !in nveHidden) ToolChip(Icons.Default.Bookmark, stringResource(R.string.panel_bookmarks)) { a.panels(ReaderPanel.BOOKMARKS) }
        if ("thumbnails" !in nveHidden) ToolChip(Icons.Default.GridView, stringResource(R.string.panel_thumbnails)) { a.panels(ReaderPanel.THUMBNAILS) }
        if ("attachments" !in nveHidden) ToolChip(Icons.Default.AttachFile, stringResource(R.string.panel_attachments)) { a.panels(ReaderPanel.ATTACHMENTS) }   // nav-attach
        // tab-view-core V-08: the chip shows when this document is being read aloud
        val ttsSt by com.smartdocs.editor.core.tts.ReadAloudController.status.collectAsState()
        if ("read_aloud" !in nveHidden) ToolChip(Icons.Default.RecordVoiceOver, stringResource(R.string.tool_read_aloud), selected = ttsSt.state != com.smartdocs.editor.core.tts.ReadAloudController.State.IDLE && ReaderAi6.ownsTts(state), onClick = a.readAloud)
        if ("ai" !in nveHidden && com.smartdocs.editor.LocalSettings.current.aiEnabled) ToolChip(Icons.Default.AutoAwesome, stringResource(R.string.tool_ai), onClick = a.ai)
        if ("share" !in nveHidden) ToolChip(Icons.Default.Share, stringResource(R.string.share), onClick = a.share)
        if ("print" !in nveHidden) ToolChip(Icons.Default.Print, stringResource(R.string.print), onClick = a.print)
        if ("doc_info" !in nveHidden) ToolChip(Icons.Default.Info, stringResource(R.string.tvc_info_panel), onClick = a.docInfo)   // tab-view-core V-21: the INFO side panel, named apart from "Document properties"
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
    // tabs audit CM-05: measuring is the workbench "Measure" chip (saved); the canvas measure is not offered twice
)

/** One entry of the grouped Comment row (tabs audit CM-19). */
private class CItem(val icon: ImageVector, val label: Int, val selected: Boolean, val onClick: () -> Unit)

/**
 * Tabs audit CM-19: on phones the ~30 Comment chips are grouped (Markup, Text, Draw, Shapes, Media) into dropdown
 * chips that show the active tool; tablets keep the flat row.
 */
@Composable
private fun CommentToolbar(state: ReaderState, a: ReaderActions) {
    if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600) { GroupedCommentToolbar(state, a); return }
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
        // tab-view-core E-15: "Edit tools" (own icon) and "Edit form fields" come early, so they are not hidden off-screen
        ToolChip(Icons.Default.Handyman, stringResource(R.string.nv_edit5_tools)) { com.smartdocs.editor.ui.nve.NveState.of(state).open(com.smartdocs.editor.ui.nve.NveSurface.EDIT5) }   // NVE
        ToolChip(Icons.Default.AddBox, stringResource(R.string.fx_edit_fields)) { a.navigateTool("form_builder") }   // formsx: form designer
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        // tab-view-core E-09: these two make comment annotations (text box / image stamp); page content is added in Edit content
        ToolChip(Icons.Default.TextFields, stringResource(R.string.tvc_add_text_comment), selected = state.tool == ReaderTool.TEXTBOX) { state.selectTool(ReaderTool.TEXTBOX) }
        ToolChip(Icons.Default.Image, stringResource(R.string.tvc_add_image_stamp), selected = state.tool == ReaderTool.IMAGE) { state.selectTool(ReaderTool.IMAGE); if (state.tool == ReaderTool.IMAGE) a.pickImage() }
        ToolChip(Icons.Default.Link, stringResource(R.string.annot_link), selected = state.tool == ReaderTool.LINK) { state.selectTool(ReaderTool.LINK) }
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        ToolChip(Icons.Default.DocumentScanner, stringResource(R.string.tool_ocr)) { a.quick(QuickAction.OCR) }
        // tab-view-core E-04 / E-10: the edit5 watermark and header & footer (page numbers preset): rotation / crop-box
        // aware, tagged, updatable and removable — the same screens as in Edit tools
        ToolChip(Icons.Default.BrandingWatermark, stringResource(R.string.tool_watermark)) { a.navigateTool("e5_watermark") }
        ToolChip(Icons.Default.FormatListNumbered, stringResource(R.string.tool_page_numbers)) { a.navigateTool("e5_header_footer") }
        // tab-view-core E-10 / E-17: one Document properties (full editor, XMP kept in step)
        ToolChip(Icons.Default.Info, stringResource(R.string.e5_props_title)) { a.navigateTool("e5_doc_props") }
        ToolChip(Icons.Default.AutoAwesomeMotion, stringResource(R.string.tool_organize)) { a.navigateTool("organize") }
    }
}

@Composable
private fun FillSignToolbar(state: ReaderState, a: ReaderActions) {
    val app = com.smartdocs.editor.LocalApp.current
    val fields by app.db.fillProfile().all().collectAsState(initial = emptyList())
    ToolRow {
        ToolChip(Icons.Default.TextFields, stringResource(R.string.fill_sign_text), selected = state.tool == ReaderTool.FS_TEXT) { state.selectTool(ReaderTool.FS_TEXT) }
        ToolChip(Icons.Default.Check, stringResource(R.string.fill_sign_check), selected = state.tool == ReaderTool.FS_CHECK) { state.selectTool(ReaderTool.FS_CHECK) }
        ToolChip(Icons.Default.Close, stringResource(R.string.fill_sign_cross), selected = state.tool == ReaderTool.FS_CROSS) { state.selectTool(ReaderTool.FS_CROSS) }
        ToolChip(Icons.Default.FiberManualRecord, stringResource(R.string.fill_sign_dot), selected = state.tool == ReaderTool.FS_DOT) { state.selectTool(ReaderTool.FS_DOT) }
        ToolChip(Icons.Default.HorizontalRule, stringResource(R.string.fill_sign_line), selected = state.tool == ReaderTool.FS_LINE) { state.selectTool(ReaderTool.FS_LINE) }
        // nitro-fillsign2: rounded rectangle (drag) and comb text for boxed forms (tap)
        ToolChip(Icons.Default.CropDin, stringResource(R.string.nf2_rounded_rect), selected = state.tool == ReaderTool.FS_RRECT) { state.selectTool(ReaderTool.FS_RRECT) }
        ToolChip(Icons.Default.Pin, stringResource(R.string.nf2_comb), selected = state.tool == ReaderTool.FS_COMB) { state.selectTool(ReaderTool.FS_COMB) }
        PasteStampChip(state)      // nitro-fillsign2: a copied signature / stamp
        // tab audit D26: the date in the chosen format, with the typing-digits option (Arabic-Indic digits)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var datePattern by remember { mutableStateOf(com.smartdocs.editor.core.fillsign2.FillSignPrefs.datePattern(ctx)) }
        fun today(p: String) = com.smartdocs.editor.ui.nve.NveDigits.apply(ctx, com.smartdocs.editor.core.fillsign2.FsDate.format(p, System.currentTimeMillis()))
        ToolChip(Icons.Default.Today, stringResource(R.string.fill_sign_date), selected = state.tool == ReaderTool.FS_DATE) {
            state.selectTool(ReaderTool.FS_DATE)
            if (state.tool == ReaderTool.FS_DATE) state.pendingText = today(datePattern)
        }
        if (state.tool == ReaderTool.FS_DATE) Box {
            var open by remember { mutableStateOf(false) }
            ToolChip(Icons.Default.EditCalendar, stringResource(R.string.tfr_date_format), selected = open) { open = true }
            DropdownMenu(open, { open = false }) {
                com.smartdocs.editor.core.fillsign2.FsDate.PATTERNS.forEach { p ->
                    DropdownMenuItem(text = { Text(today(p)) }, leadingIcon = { if (p == datePattern) Icon(Icons.Default.Check, null) },
                        onClick = { open = false; datePattern = p; com.smartdocs.editor.core.fillsign2.FillSignPrefs.setDatePattern(ctx, p); state.pendingText = today(p) })
                }
            }
        }
        com.smartdocs.editor.ui.forms5.FillSignFormChips(state, openDocJs = { a.navigateTool("e5_doc_js") })     // form menu (data, JavaScript)
        com.smartdocs.editor.ui.forms5.FillSignSmartFillChip(state)      // tab audit D18: AI smart fill from the toolbar
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        if (fields.isEmpty()) {
            Text(stringResource(R.string.rd_profile_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
        fields.forEach { f ->
            val sel = state.tool == ReaderTool.FS_PROFILE && state.pendingText == f.value
            FilterChip(selected = sel, onClick = {
                // tab audit D27: through selectTool (tool memory, edit / measure cleared); a second tap deselects
                if (sel) state.selectTool(null) else {
                    if (state.tool != ReaderTool.FS_PROFILE) state.selectTool(ReaderTool.FS_PROFILE)
                    if (state.tool == ReaderTool.FS_PROFILE) state.pendingText = f.value
                }
            }, label = { Text(f.label.ifBlank { f.value }, maxLines = 1) }, leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(16.dp)) }, modifier = Modifier.padding(horizontal = 2.dp))
        }
        // tab audit D25: the fill-profile editor from the Fill & Sign tab (it was only reachable from Account)
        ToolChip(Icons.Default.ManageAccounts, stringResource(R.string.tfr_edit_profile)) { a.navigateTool("fill_profile") }
    }
}

@Composable
private fun SignToolbar(state: ReaderState, a: ReaderActions) {
    val app = com.smartdocs.editor.LocalApp.current
    val sigs by app.db.signatures().all().collectAsState(initial = emptyList())
    val placing = state.placement != null
    // nitro-quicksign: locked signatures ask for their password; recently used ones come first
    val gate = com.smartdocs.editor.ui.signature.rememberSignatureGate()
    val meta by remember { com.smartdocs.editor.core.data.SignatureMetaStore.flow(app) }.collectAsState()
    val recent = com.smartdocs.editor.core.data.SignatureMetaStore.recent(meta, sigs.map { it.path }, max = 3)
    // nitro-fillsign2: the rest follows the palette order of Manage Signatures (move up / down)
    val palette = com.smartdocs.editor.core.data.SignatureMetaStore.ordered(meta, sigs.map { it.path }).mapNotNull { p -> sigs.firstOrNull { it.path == p } }
    val ordered = recent.mapNotNull { p -> sigs.firstOrNull { it.path == p } } + palette.filter { it.path !in recent }
    ToolRow {
        if (placing) {
            // tab audit D24: Done lives in the placement bar only (one Done); Cancel stays here
            TextButton(onClick = { state.cancelPlacement() }) { Text(stringResource(R.string.cancel)) }
            VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        }
        ToolChip(Icons.Default.Add, stringResource(R.string.sig_create)) { a.navigateTool("signatures") }
        ToolChip(Icons.Default.VerifiedUser, stringResource(R.string.sig_digital)) { a.navigateTool("digital_sign") }
        FillSign2SignChips(state, sigs)      // nitro-fillsign2: paste, Apply All Signatures, paging seal
        // tab audit D25: entry points that were only in the View tab / the top-bar menu / Account
        ToolChip(Icons.Default.Shield, stringResource(R.string.tfr_sig_panel)) { com.smartdocs.editor.ui.nve.NveState.of(state).pane = com.smartdocs.editor.ui.nve.NvePane.SIGNATURES }
        ToolChip(Icons.Default.Verified, stringResource(R.string.tfr_validate_all)) {
            state.file?.let { f -> com.smartdocs.editor.ui.signature.SigFieldRequest.pending = com.smartdocs.editor.ui.signature.SigFieldRequest.Request(f.path, null, verify = true) }
            a.navigateTool("digital_sign")
        }
        ToolChip(Icons.Default.Badge, stringResource(R.string.tfr_digital_ids)) { a.navigateTool("digital_ids") }
        // tab audit D25: add an empty signature field (placement opens on the digital-sign screen)
        ToolChip(Icons.Default.AddBox, stringResource(R.string.ns2_add_empty_field)) {
            state.file?.let { f -> com.smartdocs.editor.ui.signature.SigFieldRequest.pending = com.smartdocs.editor.ui.signature.SigFieldRequest.Request(f.path, null, verify = false, addField = true) }
            a.navigateTool("digital_sign")
        }
        ToolChip(Icons.Default.HistoryEdu, stringResource(R.string.cn_tool_esign), onClick = a.esign)
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        if (sigs.isEmpty()) Text(stringResource(R.string.rd_no_signatures), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        if (recent.isNotEmpty()) Text(stringResource(R.string.nq_recent), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
        ordered.forEachIndexed { i, s ->
            if (recent.isNotEmpty() && i == recent.size) VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
            val selected = state.tool == ReaderTool.SIGNATURE && state.pendingText == s.path
            // tab audit D15 / D28: locked thumbnails blurred, every thumbnail labelled; D27: a second tap deselects
            SignatureThumb(s, i, meta[s.path]?.locked == true,
                Modifier.padding(2.dp).size(width = 84.dp, height = 48.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable { if (selected) state.selectTool(null) else gate.request(s.path) { a.pickSignature(s.path) } })
        }
    }
}

/** Tools tab of the reader: one-step actions on the open document first, then the full tool screens (opened on this file). */
private sealed interface ReaderToolItem {
    val label: Int; val icon: ImageVector
    data class Quick(val action: QuickAction, override val label: Int, override val icon: ImageVector) : ReaderToolItem
    data class Screen(val id: String, override val label: Int, override val icon: ImageVector) : ReaderToolItem
}

/** audit 5 T-08: the Tools tab in groups with a header each (Pages / Protect / Text & OCR / Edit / Convert / More). */
private class ReaderToolGroup(val title: Int, val items: List<ReaderToolItem>)

/** An edit5 tool of [com.smartdocs.editor.ui.edit5.EDIT5_TOOLS] as a Tools-tab item (label and icon from there). */
private fun e5(id: String): ReaderToolItem.Screen? =
    com.smartdocs.editor.ui.edit5.EDIT5_TOOLS.firstOrNull { it.id == id }?.let { ReaderToolItem.Screen(it.id, it.label, it.icon) }

/**
 * audit 5 T-06 / T-07 / T-13: every tool once (no two chips with the same label: the quick watermark and quick
 * properties sheets give way to the fuller edit5 tools here, the edit5 organizer is called "advanced organize"),
 * each with its own icon, and the tools that were only on the home screen (accessibility, PDF/A, optimize, OCR
 * review, OCR batch, attachments, certificate protection) are here too, all opened through actions.navigateTool.
 */
private val readerToolGroups: List<ReaderToolGroup> = listOf(
    ReaderToolGroup(R.string.tttl_grp_pages, listOfNotNull(
        ReaderToolItem.Screen("organize", R.string.tool_organize, Icons.Default.AutoAwesomeMotion),
        ReaderToolItem.Quick(QuickAction.ROTATE_ALL, R.string.qk_rot_label, Icons.Default.RotateRight),
        ReaderToolItem.Quick(QuickAction.ADD_BLANK, R.string.tttl_blank_after, Icons.Default.NoteAdd),
        ReaderToolItem.Quick(QuickAction.DELETE_PAGE, R.string.qk_del_label, Icons.Default.DeleteOutline),
        ReaderToolItem.Quick(QuickAction.EXTRACT, R.string.tool_extract_pages, Icons.Default.ContentCut),
        ReaderToolItem.Screen("merge", R.string.tool_merge, Icons.Default.CallMerge),
        ReaderToolItem.Screen("split", R.string.tool_split, Icons.Default.CallSplit),
        e5("e5_organize"), e5("e5_page_ops"), e5("e5_split_bookmarks"), e5("e5_smart_split"), e5("e5_split_page"),
        e5("e5_mix"), e5("e5_interleave"), e5("e5_copy_pages"), e5("e5_replace_pages"), e5("e5_insert_blank"), e5("e5_templates"),
        e5("e5_page_labels"), e5("e5_crop"), e5("e5_resize"), e5("e5_impose"),
    )),
    ReaderToolGroup(R.string.tttl_grp_protect, listOfNotNull(
        ReaderToolItem.Quick(QuickAction.PROTECT, R.string.tool_protect, Icons.Default.Lock),
        ReaderToolItem.Quick(QuickAction.UNLOCK, R.string.tool_unlock, Icons.Default.LockOpen),
        ReaderToolItem.Screen("protect_advanced", R.string.s5_protect_title, Icons.Default.EnhancedEncryption),
        ReaderToolItem.Screen("cert_protect", R.string.ps_cert_protect_title, Icons.Default.Key),
        ReaderToolItem.Screen("redact", R.string.tool_redact, Icons.Default.Block),
        ReaderToolItem.Screen("sanitize", R.string.s5_sanitize_title, Icons.Default.CleaningServices),
        e5("e5_security_wm"),
    )),
    ReaderToolGroup(R.string.tttl_grp_text, listOf(
        ReaderToolItem.Quick(QuickAction.OCR, R.string.tool_ocr, Icons.Default.DocumentScanner),
        ReaderToolItem.Screen("ocr_review", R.string.nt_ocr_review, Icons.Default.RateReview),
        ReaderToolItem.Screen("ocr_plus", R.string.t5_ocr_title, Icons.Default.DynamicFeed),
        ReaderToolItem.Quick(QuickAction.FIX_TEXT, R.string.tool_fix_text, Icons.Default.Spellcheck),
        ReaderToolItem.Screen("find_replace", R.string.s5_replace_title, Icons.Default.FindReplace),
        ReaderToolItem.Screen("search_highlight", R.string.s5_mark_title, Icons.Default.BorderColor),
        ReaderToolItem.Screen("accessibility", R.string.t5_acc_title, Icons.Default.Accessibility),
    )),
    ReaderToolGroup(R.string.tttl_grp_edit, listOfNotNull(
        ReaderToolItem.Quick(QuickAction.PAGE_NUMBERS, R.string.tool_page_numbers, Icons.Default.FormatListNumbered),
        e5("e5_header_footer"), e5("e5_bates"), e5("e5_watermark"), e5("e5_background"),
        ReaderToolItem.Quick(QuickAction.FLATTEN, R.string.tool_flatten, Icons.Default.FilterNone),
        ReaderToolItem.Screen("forms", R.string.tool_forms, Icons.Default.Checklist),
        e5("e5_links"), e5("e5_bookmarks"), e5("e5_destinations"), e5("e5_xref"), e5("e5_doc_js"), e5("e5_layers"),
        ReaderToolItem.Screen("attachments", R.string.tool_attachments, Icons.Default.AttachFile),
        e5("e5_doc_props"),
    )),
    ReaderToolGroup(R.string.tttl_grp_convert, listOf(
        ReaderToolItem.Quick(QuickAction.COMPRESS, R.string.tool_compress, Icons.Default.Compress),
        ReaderToolItem.Screen("optimize", R.string.t5_opt_title, Icons.Default.Speed),
        ReaderToolItem.Screen("pdfa", R.string.t5_pdfa_title, Icons.Default.Inventory),
        ReaderToolItem.Screen("convert_hub", R.string.cv6_title, Icons.Default.SwapHoriz),
        ReaderToolItem.Screen("pdf_to_images", R.string.tool_pdf_to_images, Icons.Default.Collections),
        ReaderToolItem.Screen("pdf_to_text", R.string.tool_pdf_to_text, Icons.Default.Subject),
        ReaderToolItem.Screen("extract_images", R.string.cv6_t_extract_images, Icons.Default.PhotoLibrary),
    )),
    ReaderToolGroup(R.string.tttl_grp_more, listOf(
        ReaderToolItem.Screen("ai", R.string.tool_ai, Icons.Default.AutoAwesome),
        ReaderToolItem.Screen("compare", R.string.tool_compare, Icons.Default.Compare),
        ReaderToolItem.Quick(QuickAction.PRINT, R.string.print, Icons.Default.Print),
        ReaderToolItem.Quick(QuickAction.SHARE, R.string.share, Icons.Default.Share),
    )),
)

@Composable
private fun ToolsToolbar(a: ReaderActions) {
    val g6Settings = com.smartdocs.editor.LocalSettings.current
    // audit 5 T-15: not remembered on aiEnabled only: AiGate.allowsTool also depends on offline mode
    val groups = readerToolGroups.map { g -> g to g.items.filter { it !is ReaderToolItem.Screen || com.smartdocs.editor.core.gaps6.AiGate.allowsTool(g6Settings, it.id) } }
        .filter { it.second.isNotEmpty() }
    LazyHorizontalGrid(rows = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().height(156.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        groups.forEach { (g, list) ->
            // the group's title as a narrow header column spanning both rows
            item(key = "grp_" + g.title, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Box(Modifier.width(28.dp).fillMaxHeight().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(g.title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, softWrap = false, modifier = Modifier.wrapContentSize(unbounded = true).rotate(-90f))
                }
            }
            items(list) { t ->
                ToolChip(t.icon, stringResource(t.label), maxLines = 2) {
                    when (t) {
                        is ReaderToolItem.Quick -> a.quick(t.action)
                        is ReaderToolItem.Screen -> a.navigateTool(t.id)
                    }
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
            // tab-view-core E-14: only the controls the tool uses (IMAGE: opacity; LINK: none)
            val colorT = t != ReaderTool.IMAGE && t != ReaderTool.LINK
            val opacityT = t != ReaderTool.LINK
            if (colorT) {
                PALETTE.forEach { c -> ColorDot(c, state.color == c) { state.color = c } }
                Box(Modifier.size(28.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { showMore = !showMore }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Tune, stringResource(R.string.rd_custom_color), Modifier.size(16.dp))
                }
            }
            if (opacityT) {
                VerticalDivider(Modifier.height(24.dp))
                listOf(0.25f, 0.5f, 0.75f, 1f).forEach { o ->
                    FilterChip(selected = state.opacity == o, onClick = { state.opacity = o }, label = { Text("${(o * 100).toInt()}%") }, modifier = Modifier.height(30.dp))
                }
            }
            VerticalDivider(Modifier.height(24.dp))
            FilterChip(selected = state.continueCreating, onClick = { state.continueCreating = !state.continueCreating; com.smartdocs.editor.core.data.NpfPrefs.setBool(com.smartdocs.editor.core.data.NpfPrefs.K.RETAIN_TOOLS, state.continueCreating) },
                label = { Text(stringResource(R.string.rd_continue_creating)) }, leadingIcon = { Icon(Icons.Default.Repeat, null, Modifier.size(16.dp)) }, modifier = Modifier.height(30.dp))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var memOn by remember { mutableStateOf(com.smartdocs.editor.ui.reader.annot.ToolMemory.enabled(ctx)) }
            FilterChip(selected = memOn, onClick = { memOn = !memOn; com.smartdocs.editor.ui.reader.annot.ToolMemory.setEnabled(ctx, memOn) },
                label = { Text(stringResource(R.string.v5_tool_memory)) }, leadingIcon = { Icon(Icons.Default.Memory, null, Modifier.size(16.dp)) }, modifier = Modifier.height(30.dp))
        }
        if (showMore && t != ReaderTool.IMAGE && t != ReaderTool.LINK) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hex, { v -> hex = v.take(7); v.removePrefix("#").toIntOrNull(16)?.let { if (v.removePrefix("#").length == 6) state.color = it } },
                    label = { Text(stringResource(R.string.rd_custom_color)) }, placeholder = { Text("#FF5722") }, singleLine = true, modifier = Modifier.width(140.dp))
                val poly = t == ReaderTool.POLYGON || t == ReaderTool.POLYLINE || t == ReaderTool.CLOUD
                val thick = !poly && (t == ReaderTool.INK || t == ReaderTool.LINE || t == ReaderTool.ARROW || t == ReaderTool.RECT || t == ReaderTool.OVAL || t == ReaderTool.ERASER || t == null || t == ReaderTool.FS_RRECT)
                val fontT = t == ReaderTool.FREETEXT || t == ReaderTool.TEXTBOX || t == ReaderTool.FS_TEXT || t == ReaderTool.FS_PROFILE || t == ReaderTool.FS_DATE || t == ReaderTool.NOTE
                        || t == ReaderTool.FS_CHECK || t == ReaderTool.FS_CROSS || t == ReaderTool.FS_DOT || t == ReaderTool.FS_LINE || t == ReaderTool.FS_COMB
                Column(Modifier.weight(1f)) {
                    if (thick) {
                        Text("${stringResource(R.string.prop_thickness)}: ${"%.1f".format(state.thickness)}", style = MaterialTheme.typography.labelSmall)
                        Slider(state.thickness, { state.thickness = it }, valueRange = 0.5f..24f, modifier = Modifier.height(28.dp))
                    }
                    if (fontT || (!thick && !poly)) {
                        Text("${stringResource(R.string.prop_font_size)}: ${state.fontSize.toInt()}", style = MaterialTheme.typography.labelSmall)
                        Slider(state.fontSize, { state.fontSize = it }, valueRange = 6f..72f, modifier = Modifier.height(28.dp))
                    }
                }
            }
        }
        com.smartdocs.editor.ui.reader.annot.Annot2PropertyRow(state)      // MODULE_annot2: polygon progress, fill, cloud
        // tabs audit CM-19: on a short screen (phone in landscape) the creation extras fold behind a toggle
        val shortScreen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
        var extrasOpen by remember { mutableStateOf(false) }
        if (shortScreen && !extrasOpen) TextButton(onClick = { extrasOpen = true }, modifier = Modifier.height(32.dp)) { Text(stringResource(R.string.tc2_more_options)) }
        else com.smartdocs.editor.ui.reader.annot.CreationExtrasRow(state)      // section 7: fill, dash, no border, line ends, rich text, icons, tickmarks
    }
}

@Composable
fun ColorDot(rgb: Int, selected: Boolean, size: androidx.compose.ui.unit.Dp = 28.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(annotColor(rgb))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
            .clickable(onClickLabel = colorName(rgb), role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = colorName0(rgb); this.selected = selected },
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = if (rgb == 0xFFEB3B || rgb == 0x8BC34A) Color.Black else Color.White) }
}

/** Spoken name of an annotation colour (tabs audit CM-30): a palette name or the hex value. */
@Composable
private fun colorName(rgb: Int): String = colorName0(rgb)

private fun colorName0(rgb: Int): String = COLOR_NAMES[rgb] ?: "#%06X".format(rgb and 0xFFFFFF)

private val COLOR_NAMES = mapOf(
    0xFFEB3B to "Yellow · أصفر", 0xF44336 to "Red · أحمر", 0xE53935 to "Red · أحمر", 0x4CAF50 to "Green · أخضر", 0x8BC34A to "Light green · أخضر فاتح",
    0x2196F3 to "Blue · أزرق", 0x3F51B5 to "Indigo · نيلي", 0x9C27B0 to "Purple · بنفسجي", 0xE91E63 to "Pink · وردي", 0xFF9800 to "Orange · برتقالي",
    0x795548 to "Brown · بني", 0x212121 to "Black · أسود", 0x000000 to "Black · أسود", 0xFFFFFF to "White · أبيض", 0x9E9E9E to "Grey · رمادي", 0x00BCD4 to "Cyan · سماوي",
)

@Composable
private fun GroupedCommentToolbar(state: ReaderState, a: ReaderActions) {
    val v = com.smartdocs.editor.ui.view5.V5State.of(state)
    fun tool(t: ReaderTool): CItem? {
        val d = commentTools.firstOrNull { it.tool == t }
        val e = com.smartdocs.editor.ui.reader.annot.ToolCatalog.of(t)
        val icon = d?.icon ?: e?.icon ?: return null
        val label = d?.label ?: e?.label ?: return null
        return CItem(icon, label, state.tool == t) { com.smartdocs.editor.ui.reader.annot.clickTool(state, a, t) }
    }
    fun wb(t: com.smartdocs.editor.ui.view5.WTool, icon: ImageVector, label: Int) =
        CItem(icon, label, false) { v.workbenchTool = t; v.open(com.smartdocs.editor.ui.view5.V5Surface.WORKBENCH) }
    val groups = listOf(
        Triple(R.string.tc2_g_markup, Icons.Default.Highlight, CommentGroups.MARKUP.mapNotNull(::tool) + listOf(wb(com.smartdocs.editor.ui.view5.WTool.AREA_HIGHLIGHT, Icons.Default.HighlightAlt, R.string.v5_tool_area_highlight))),
        Triple(R.string.tc2_g_text, Icons.Default.TextFields, CommentGroups.TEXT.mapNotNull(::tool) + listOf(
            wb(com.smartdocs.editor.ui.view5.WTool.CALLOUT, Icons.Default.Comment, R.string.v5_tool_callout), wb(com.smartdocs.editor.ui.view5.WTool.TYPEWRITER, Icons.Default.Keyboard, R.string.v5_tool_typewriter))),
        Triple(R.string.tc2_g_draw, Icons.Default.Draw, CommentGroups.DRAW.mapNotNull(::tool)),
        Triple(R.string.tc2_g_shapes, Icons.Default.CropSquare, CommentGroups.SHAPES.mapNotNull(::tool)),
        Triple(R.string.tc2_g_media, Icons.Default.Attachment, CommentGroups.MEDIA.mapNotNull(::tool)),
    )
    ToolRow {
        groups.forEach { (label, icon, items) -> GroupChip(stringResource(label), icon, items) }
        (CommentGroups.SINGLE.mapNotNull(::tool) + wb(com.smartdocs.editor.ui.view5.WTool.DISTANCE, Icons.Default.Straighten, R.string.v5_measure)).forEach { c ->
            ToolChip(c.icon, stringResource(c.label), selected = c.selected, onClick = c.onClick)
        }
        ToolChip(Icons.Default.Forum, stringResource(R.string.v5_comment_manager)) { v.open(com.smartdocs.editor.ui.view5.V5Surface.COMMENTS) }
        ToolChip(Icons.Default.TableChart, stringResource(R.string.v5_measurements)) { v.open(com.smartdocs.editor.ui.view5.V5Surface.MEASURE_LIST) }
        com.smartdocs.editor.ui.reader.annot.CommentsMoreChip(state)
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        ToolChip(Icons.Default.List, stringResource(R.string.panel_annotations)) { a.panels(ReaderPanel.ANNOTATIONS) }
    }
}

@Composable
private fun GroupChip(label: String, icon: ImageVector, items: List<CItem>) {
    var open by remember { mutableStateOf(false) }
    val sel = items.firstOrNull { it.selected }
    Box {
        ToolChip(sel?.icon ?: icon, if (sel != null) stringResource(sel.label) else label, selected = sel != null) { open = true }
        DropdownMenu(open, { open = false }) {
            items.forEach { c ->
                DropdownMenuItem(text = { Text(stringResource(c.label)) }, leadingIcon = { Icon(c.icon, null) },
                    trailingIcon = { if (c.selected) Icon(Icons.Default.Check, stringResource(R.string.tc2_selected)) },
                    onClick = { open = false; c.onClick() })
            }
        }
    }
}
