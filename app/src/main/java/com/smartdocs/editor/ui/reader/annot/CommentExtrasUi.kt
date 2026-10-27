package com.smartdocs.editor.ui.reader.annot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.AnnotStyle
import com.smartdocs.editor.core.annot.CalcTape
import com.smartdocs.editor.core.annot.SummaryOptions.Sort as SumSort
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.view5.DashStyle
import com.smartdocs.editor.core.view5.LineEnd
import com.smartdocs.editor.ui.reader.ColorDot
import com.smartdocs.editor.ui.reader.PALETTE
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.view5.DashPicker
import com.smartdocs.editor.ui.view5.LineEndPicker
import kotlinx.coroutines.launch
import java.io.File

/** Localised label of a note / attachment icon name. */
@Composable
fun iconLabel(name: String): String = stringResource(when (name) {
    "Key" -> R.string.cx_ic_key; "Note" -> R.string.cx_ic_note; "Help" -> R.string.cx_ic_help; "NewParagraph" -> R.string.cx_ic_newpar
    "Paragraph" -> R.string.cx_ic_par; "Insert" -> R.string.cx_ic_insert; "Check" -> R.string.cx_ic_check; "Circle" -> R.string.cx_ic_circle
    "Cross" -> R.string.cx_ic_cross; "CrossHairs" -> R.string.cx_ic_crosshairs; "RightArrow" -> R.string.cx_ic_rightarrow
    "RightPointer" -> R.string.cx_ic_rightpointer; "Star" -> R.string.cx_ic_star; "UpArrow" -> R.string.cx_ic_uparrow
    "UpLeftArrow" -> R.string.cx_ic_upleftarrow; "PushPin" -> R.string.cx_ic_pushpin; "Paperclip" -> R.string.cx_ic_paperclip
    "Graph" -> R.string.cx_ic_graph; "Tag" -> R.string.cx_ic_tag; else -> R.string.cx_ic_comment
})

/**
 * Section-7 creation options under the property bar: fill / dash / no border / cloud / square constraint for
 * shapes, dash + both line endings for lines, ink-to-shapes, bold / italic / underline for text boxes, note and
 * attachment icons, and the tickmark palette.
 */
@Composable
fun CreationExtrasRow(state: ReaderState) {
    val h = state.annot2
    val t = state.tool ?: return
    val shapes = t == ReaderTool.RECT || t == ReaderTool.OVAL
    val lines = t == ReaderTool.LINE || t == ReaderTool.ARROW
    val poly = t == ReaderTool.POLYGON || t == ReaderTool.CLOUD || t == ReaderTool.POLYLINE
    if (!(shapes || lines || poly || t == ReaderTool.INK || t == ReaderTool.FREETEXT || t == ReaderTool.NOTE || t == ReaderTool.ATTACH || t == ReaderTool.TICKMARK)) return
    var dashOpen by remember { mutableStateOf(false) }
    var fmtOpen by remember { mutableStateOf(false) }
    if (fmtOpen) TextFormatDialog(h.textFmt, onDismiss = { fmtOpen = false }) { h.textFmt = it; fmtOpen = false }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (shapes) {
                Text(stringResource(R.string.an_fill), style = MaterialTheme.typography.labelSmall)
                Box(Modifier.size(26.dp).clip(CircleShape).border(if (h.fillColor == null) 3.dp else 1.dp,
                    if (h.fillColor == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { h.fillColor = null }, contentAlignment = Alignment.Center) { Icon(Icons.Default.Block, stringResource(R.string.an_no_fill), Modifier.size(16.dp)) }
                PALETTE.forEach { c -> ColorDot(c, h.fillColor == c, 26.dp) { h.fillColor = c } }
                VerticalDivider(Modifier.height(24.dp))
            }
            if (shapes || lines || poly || t == ReaderTool.INK)
                FilterChip(dashOpen || h.dash > 0, { dashOpen = !dashOpen }, label = { Text(stringResource(R.string.cx_dash)) },
                    leadingIcon = { Icon(Icons.Default.LineStyle, null, Modifier.size(16.dp)) }, modifier = Modifier.height(30.dp))
            if (shapes || (poly && t != ReaderTool.POLYLINE))
                FilterChip(h.noBorder, { h.noBorder = !h.noBorder }, label = { Text(stringResource(R.string.cx_no_border)) }, enabled = h.fillColor != null, modifier = Modifier.height(30.dp))
            if (t == ReaderTool.RECT) {
                Text(stringResource(R.string.cx_cloud), style = MaterialTheme.typography.labelSmall)
                (0..3).forEach { lv -> FilterChip(h.rectCloud == lv, { h.rectCloud = lv }, label = { Text(if (lv == 0) "—" else lv.toString()) }, modifier = Modifier.height(30.dp)) }
            }
            if (t == ReaderTool.CLOUD) FilterChip(h.cloudIntensity == 3, { h.cloudIntensity = 3 }, label = { Text("3") }, modifier = Modifier.height(30.dp))
            if (shapes) FilterChip(h.constrain, { h.constrain = !h.constrain }, label = { Text(stringResource(R.string.cx_square_constraint)) }, modifier = Modifier.height(30.dp))
            if (t == ReaderTool.INK) FilterChip(h.inkToShapes, { h.inkToShapes = !h.inkToShapes }, label = { Text(stringResource(R.string.cx_ink_shapes)) },
                leadingIcon = { Icon(Icons.Default.AutoFixHigh, null, Modifier.size(16.dp)) }, modifier = Modifier.height(30.dp))
            if (t == ReaderTool.FREETEXT) {
                FilterChip(h.bold, { h.bold = !h.bold }, label = { Text("B", fontWeight = FontWeight.Bold) }, modifier = Modifier.height(30.dp))
                FilterChip(h.italic, { h.italic = !h.italic }, label = { Text("I", fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif) }, modifier = Modifier.height(30.dp))
                FilterChip(h.underline, { h.underline = !h.underline }, label = { Text("U", textDecoration = TextDecoration.Underline) }, modifier = Modifier.height(30.dp))
                FilterChip(h.textFmt.strike, { h.textFmt = h.textFmt.copy(strike = !h.textFmt.strike) }, label = { Text("S", textDecoration = TextDecoration.LineThrough) }, modifier = Modifier.height(30.dp))
                AssistChip(onClick = { fmtOpen = true }, label = { Text(stringResource(R.string.cx_text_format)) }, leadingIcon = { Icon(Icons.Default.FormatSize, null, Modifier.size(16.dp)) }, modifier = Modifier.height(30.dp))
            }
            if (t == ReaderTool.NOTE || t == ReaderTool.ATTACH) {
                val icons = if (t == ReaderTool.NOTE) AnnotStyle.NOTE_ICONS else AnnotStyle.ATTACH_ICONS
                Text(stringResource(R.string.cx_icon), style = MaterialTheme.typography.labelSmall)
                icons.forEach { n -> FilterChip(h.noteIcon == n || (h.noteIcon.isBlank() && n == icons.first { it == "Comment" || it == "PushPin" }), { h.noteIcon = n }, label = { Text(iconLabel(n)) }, modifier = Modifier.height(30.dp)) }
            }
            if (t == ReaderTool.TICKMARK) TickRow(state)
        }
        if (dashOpen && (shapes || lines || poly || t == ReaderTool.INK)) DashPicker(DashStyle.entries.getOrElse(h.dash) { DashStyle.SOLID }) { h.dash = it.ordinal }
        if (lines) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(h.caption, { h.caption = it }); Text(stringResource(R.string.cx_line_caption), style = MaterialTheme.typography.labelSmall) }
            Text(stringResource(R.string.v5_line_start), style = MaterialTheme.typography.labelSmall)
            LineEndPicker(LineEnd.of(h.lineStart)) { h.lineStart = it.pdf }
            Text(stringResource(R.string.v5_line_end), style = MaterialTheme.typography.labelSmall)
            LineEndPicker(LineEnd.of(h.lineEnd.ifBlank { if (t == ReaderTool.ARROW) "OpenArrow" else "None" })) { h.lineEnd = it.pdf }
        }
    }
}

@Composable
private fun TickRow(state: ReaderState) {
    val cx = state.annot2.cx
    FilterChip(cx.tickMode == TickMode.NUMBER, { cx.tickMode = TickMode.NUMBER }, label = { Text(stringResource(R.string.cx_tick_numbers)) }, modifier = Modifier.height(30.dp))
    FilterChip(cx.tickMode == TickMode.LETTER, { cx.tickMode = TickMode.LETTER }, label = { Text(stringResource(R.string.cx_tick_letters)) }, modifier = Modifier.height(30.dp))
    FilterChip(cx.tickMode == TickMode.SYMBOL, { cx.tickMode = TickMode.SYMBOL }, label = { Text(stringResource(R.string.cx_tick_symbols)) }, modifier = Modifier.height(30.dp))
    if (cx.tickMode == TickMode.SYMBOL) TickSymbols.all.forEach { (code, glyph) ->
        FilterChip(cx.tickSymbol == code, { cx.tickSymbol = code }, label = { Text(glyph) }, modifier = Modifier.height(30.dp))
    }
    VerticalDivider(Modifier.height(24.dp))
    Text("${stringResource(R.string.cx_tick_size)} ${cx.tickSize.toInt()}", style = MaterialTheme.typography.labelSmall)
    Slider(cx.tickSize, { cx.tickSize = it }, valueRange = 10f..40f, modifier = Modifier.width(110.dp).height(28.dp))
    TextButton(onClick = { cx.selectTickmarks() }) { Text(stringResource(R.string.cx_tick_select)) }
    IconButton(onClick = { cx.stepTickmark(false) }, Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.cx_tick_prev)) }
    IconButton(onClick = { cx.stepTickmark(true) }, Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.cx_tick_next)) }
}

/** Overflow menu of the selection bars (single or multi): properties, lock, clipboard, align, flatten, rotate, pages, export. */
@Composable
fun SelectionMoreButton(state: ReaderState, multi: Boolean) {
    val cx = state.annot2.cx
    var open by remember { mutableStateOf(false) }
    var align by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.cx_more)) }
        DropdownMenu(open, { open = false }) {
            CxItem(R.string.cx_props, Icons.Default.Info) { open = false; cx.openProps() }
            CxItem(R.string.cx_lock, Icons.Default.Lock) { open = false; cx.setLocked(true) }
            CxItem(R.string.cx_unlock, Icons.Default.LockOpen) { open = false; cx.setLocked(false) }
            CxItem(R.string.cx_cut, Icons.Default.ContentCut) { open = false; cx.copy(cut = true) }
            CxItem(R.string.cx_copy, Icons.Default.ContentCopy) { open = false; cx.copy() }
            if (cx.canPaste) CxItem(R.string.cx_paste_in_place, Icons.Default.ContentPaste) { open = false; cx.paste(true) }
            if (multi) CxItem(R.string.cx_align, Icons.Default.AlignHorizontalLeft) { open = false; align = true }
            CxItem(R.string.cx_rotate_left, Icons.Default.RotateLeft) { open = false; cx.rotate(90f) }
            CxItem(R.string.cx_rotate_right, Icons.Default.RotateRight) { open = false; cx.rotate(-90f) }
            CxItem(R.string.cx_rotate_custom, Icons.Default.Rotate90DegreesCcw) { open = false; cx.rotateReq = cx.target() }
            CxItem(R.string.cx_place_pages, Icons.Default.FileCopy) { open = false; cx.target()?.let { cx.pagesReq = PagesReq(it) } }
            CxItem(R.string.cx_flatten, Icons.Default.Layers) { open = false; cx.flatten() }
            CxItem(R.string.cx_export_sel_xfdf, Icons.Default.Upload) { open = false; state.annot2.launchExportSelected(true, false, false) }
            CxItem(R.string.cx_export_sel_fdf, Icons.Default.Upload) { open = false; state.annot2.launchExportSelected(false, false, false) }
            CxItem(R.string.cx_email_fdf, Icons.Default.Email) { open = false; state.annot2.launchExportSelected(false, true, false) }
        }
        DropdownMenu(align, { align = false }) {
            listOf(AlignMode.LEFT to R.string.cx_align_left, AlignMode.CENTER_H to R.string.cx_align_center_h, AlignMode.RIGHT to R.string.cx_align_right,
                AlignMode.TOP to R.string.cx_align_top, AlignMode.CENTER_V to R.string.cx_align_center_v, AlignMode.BOTTOM to R.string.cx_align_bottom,
                AlignMode.DIST_H to R.string.cx_dist_h, AlignMode.DIST_V to R.string.cx_dist_v, AlignMode.SAME_W to R.string.cx_same_w,
                AlignMode.SAME_H to R.string.cx_same_h, AlignMode.SAME_BOTH to R.string.cx_same_both).forEach { (m, l) ->
                DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { align = false; cx.align(m) })
            }
        }
    }
}

@Composable
private fun CxItem(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)

/** Items of the comments "more" menu added by section 7. */
@Composable
fun CommentExtrasMenuItems(h: Annot2Host, close: () -> Unit) {
    val cx = h.cx
    HorizontalDivider()
    DropdownMenuItem(text = { Text(stringResource(if (cx.hidden) R.string.cx_show_comments else R.string.cx_hide_comments)) },
        leadingIcon = { Icon(if (cx.hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) }, onClick = { close(); cx.toggleHidden() })
    if (cx.canPaste) {
        DropdownMenuItem(text = { Text(stringResource(R.string.cx_paste)) }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) }, onClick = { close(); cx.paste(false) })
        DropdownMenuItem(text = { Text(stringResource(R.string.cx_paste_in_place)) }, leadingIcon = { Icon(Icons.Default.ContentPasteGo, null) }, onClick = { close(); cx.paste(true) })
    }
    DropdownMenuItem(text = { Text(stringResource(R.string.cx_note_indicators)) }, leadingIcon = { Icon(if (cx.noteIndicators) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null) },
        onClick = { close(); cx.toggleNoteIndicators() })
    DropdownMenuItem(text = { Text(stringResource(R.string.cx_copy_encircled)) }, leadingIcon = { Icon(if (cx.copyEncircled) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null) },
        onClick = { close(); cx.toggleCopyEncircled() })
    DropdownMenuItem(text = { Text(stringResource(R.string.cx_note_prefs)) }, leadingIcon = { Icon(Icons.Default.TextFormat, null) }, onClick = { close(); cx.notePrefsOpen = true })
    DropdownMenuItem(text = { Text(stringResource(R.string.cx_prev_comment)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.NavigateBefore, null) }, onClick = { close(); cx.stepComment(false) })
    DropdownMenuItem(text = { Text(stringResource(R.string.cx_next_comment)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.NavigateNext, null) }, onClick = { close(); cx.stepComment(true) })
    DropdownMenuItem(text = { Text(stringResource(R.string.cx_popups_open)) }, leadingIcon = { Icon(Icons.Default.OpenInFull, null) }, onClick = { close(); cx.popupsOpen(true) })
    DropdownMenuItem(text = { Text(stringResource(R.string.cx_popups_close)) }, leadingIcon = { Icon(Icons.Default.CloseFullscreen, null) }, onClick = { close(); cx.popupsOpen(false) })
    DropdownMenuItem(text = { Text(stringResource(R.string.cx_popups_reset_action)) }, leadingIcon = { Icon(Icons.Default.RestartAlt, null) }, onClick = { close(); cx.resetPopups() })
    DropdownMenuItem(text = { Text(stringResource(R.string.cx_email_fdf)) }, leadingIcon = { Icon(Icons.Default.Email, null) }, onClick = { close(); h.launchExportSelected(false, true, true) })
    DropdownMenuItem(text = { Text(stringResource(R.string.cx_calculator)) }, leadingIcon = { Icon(Icons.Default.Calculate, null) }, onClick = { close(); cx.calculator = true })
}

/** Dialogs of section 7 (properties, rotate, place on pages, calculator) and the export-selected launchers. */
@Composable
fun CommentExtrasDialogs(state: ReaderState) {
    val h = state.annot2
    val cx = h.cx
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingOut by remember { mutableStateOf<File?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pendingOut; pendingOut = null
        if (uri != null && f != null) scope.launch { runCatching { MediaIntents.writeTo(ctx, f, uri) }.onFailure { state.error = it.message } }
    }
    SideEffect {
        h.launchExportSelected = { xfdf, share, all ->
            if (all) { h.multi.clear(); state.edit.clear() }
            cx.exportSelected(xfdf, share) { out -> pendingOut = out; saver.launch(out.name) }
        }
    }
    cx.props?.let { p -> PropsDialog(p, onDismiss = { cx.props = null }) { a, s, n, c, l, i -> cx.applyProps(p, a, s, n, c, l, i) } }
    cx.rotateReq?.let { RotateDialog(onDismiss = { cx.rotateReq = null }) { deg -> cx.rotate(deg) } }
    cx.pagesReq?.let { PagesDialog(state.pageCount, onDismiss = { cx.pagesReq = null }) { spec -> cx.placeOnPages(spec) } }
    if (cx.calculator) CalculatorDialog(state) { cx.calculator = false }
    if (cx.notePrefsOpen) AlertDialog(onDismissRequest = { cx.saveNotePrefs(); cx.notePrefsOpen = false }, title = { Text(stringResource(R.string.cx_note_prefs)) },
        text = { Column {
            Text(stringResource(R.string.cx_note_font, cx.noteFontSize.toInt()), style = MaterialTheme.typography.labelMedium)
            Slider(cx.noteFontSize, { cx.noteFontSize = it }, valueRange = 10f..28f)
            Text(stringResource(R.string.cx_note_opacity, (cx.noteOpacity * 100).toInt()), style = MaterialTheme.typography.labelMedium)
            Slider(cx.noteOpacity, { cx.noteOpacity = it }, valueRange = 0.4f..1f)
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().alpha(cx.noteOpacity)) {
                Text(stringResource(R.string.cx_note_sample), Modifier.padding(8.dp), fontSize = cx.noteFontSize.sp)
            }
        } },
        confirmButton = { TextButton(onClick = { cx.saveNotePrefs(); cx.notePrefsOpen = false }) { Text(stringResource(R.string.ok)) } })
    if (cx.summaryAsk) SummaryOptionsDialog(state.pageCount, onDismiss = { cx.summaryAsk = false }) { o -> cx.summaryAsk = false; cx.runSummary(o) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropsDialog(p: PropsReq, onDismiss: () -> Unit, onOk: (String, String, String, String, Boolean, String) -> Unit) {
    var author by remember { mutableStateOf(p.author) }
    var subject by remember { mutableStateOf(p.subject) }
    var name by remember { mutableStateOf(p.name) }
    var contents by remember { mutableStateOf(p.contents) }
    var locked by remember { mutableStateOf(p.locked) }
    var icon by remember { mutableStateOf(p.icon) }
    val icons = when (p.kind) { "Text" -> AnnotStyle.NOTE_ICONS; "FileAttachment" -> AnnotStyle.ATTACH_ICONS; else -> emptyList() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.cx_props)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.cx_author)) }, singleLine = true)
                OutlinedTextField(subject, { subject = it }, label = { Text(stringResource(R.string.cx_subject)) }, singleLine = true)
                if (p.single) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.cx_name)) }, singleLine = true)
                    OutlinedTextField(contents, { contents = it }, label = { Text(stringResource(R.string.cx_contents)) }, minLines = 2)
                } else Text(stringResource(R.string.cx_multi_hint), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(locked, { locked = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.cx_locked)) }
                if (icons.isNotEmpty()) {
                    Text(stringResource(R.string.cx_icon), style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        icons.forEach { n -> FilterChip(icon == n, { icon = n }, label = { Text(iconLabel(n)) }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(author.trim(), subject.trim(), name.trim(), contents, locked, icon) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun RotateDialog(onDismiss: () -> Unit, onOk: (Float) -> Unit) {
    var deg by remember { mutableFloatStateOf(15f) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.cx_rotate_custom)) },
        text = {
            Column {
                Text(stringResource(R.string.cx_degrees, deg.toInt()))
                // 15 degree steps (Foxit / Acrobat Shift-rotate)
                Slider(deg, { deg = (it / 15f).let { k -> Math.round(k) * 15f } }, valueRange = -180f..180f, steps = 23)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onOk(deg) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PagesDialog(count: Int, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var spec by remember { mutableStateOf("1-$count") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.cx_place_pages)) },
        text = {
            Column {
                OutlinedTextField(spec, { spec = it }, label = { Text(stringResource(R.string.cx_pages_hint)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onOk(spec) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Accounting calculator with tape (25.24-25.26): keypad, editable tape, place the tape on the page as a text box. */
@Composable
private fun CalculatorDialog(state: ReaderState, onDismiss: () -> Unit) {
    val tape = remember { CalcTape() }
    var entry by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var decimals by remember { mutableIntStateOf(2) }
    var withDate by remember { mutableStateOf(true) }
    var editing by remember { mutableIntStateOf(-1) }
    fun value() = entry.toDoubleOrNull() ?: 0.0
    fun commit(op: CalcTape.Op, percent: Boolean = false) {
        if (entry.isBlank() && op != CalcTape.Op.SUBTOTAL && op != CalcTape.Op.TOTAL) return
        when (op) { CalcTape.Op.SUBTOTAL -> tape.subtotal(); CalcTape.Op.TOTAL -> tape.total(); else -> tape.add(op, value(), percent) }
        entry = ""; version++
    }
    val rows = remember(version) { tape.rows() }
    val dateHeader = remember(withDate, version) { if (withDate) java.text.DateFormat.getDateTimeInstance().format(java.util.Date()) else null }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.cx_calculator)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 180.dp)) {
                    LazyColumn(Modifier.padding(6.dp)) {
                        itemsIndexed(rows) { i, r ->
                            val text = when (r.line.op) {
                                CalcTape.Op.SUBTOTAL, CalcTape.Op.TOTAL -> CalcTape.fmt(r.result, decimals) + "  " + r.line.op.sign
                                else -> CalcTape.fmt(r.line.value, decimals) + (if (r.line.percent) "%" else "") + "  " + r.line.op.sign
                            }
                            Row(Modifier.fillMaxWidth().clickable { editing = i; entry = if (r.line.op in setOf(CalcTape.Op.SUBTOTAL, CalcTape.Op.TOTAL)) "" else r.line.value.toString() },
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(text, Modifier.weight(1f), fontFamily = FontFamily.Monospace,
                                    fontWeight = if (r.line.op == CalcTape.Op.TOTAL) FontWeight.Bold else FontWeight.Normal,
                                    color = if (i == editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                if (i == editing) IconButton(onClick = { tape.delete(i); editing = -1; version++ }, Modifier.size(28.dp)) { Icon(Icons.Default.Delete, stringResource(R.string.cx_calc_delete_line), Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
                Text((if (editing >= 0) "✎ " else "") + entry.ifBlank { CalcTape.fmt(tape.current(), decimals) }, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                Text("M ${CalcTape.fmt(tape.memory, decimals)}   GT ${CalcTape.fmt(tape.grandTotal(), decimals)}", style = MaterialTheme.typography.labelSmall)
                val keys = listOf(
                    listOf("7", "8", "9", "÷", "√"), listOf("4", "5", "6", "×", "1/x"),
                    listOf("1", "2", "3", "−", "%"), listOf("0", ".", "±", "+", "CE"),
                    listOf("M+", "M−", "MR", "MC", "C"), listOf("◇", "*", "↶", "↷", "✎"))
                keys.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { k ->
                            FilledTonalButton(onClick = {
                                when (k) {
                                    in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9") -> entry += k
                                    "." -> if ('.' !in entry) entry += if (entry.isEmpty()) "0." else "."
                                    "±" -> entry = if (entry.startsWith("-")) entry.drop(1) else "-$entry"
                                    "√" -> entry = CalcTape.sqrt(value()).toString()
                                    "1/x" -> entry = CalcTape.inverse(value()).toString()
                                    "+" -> commit(CalcTape.Op.ADD); "−" -> commit(CalcTape.Op.SUB)
                                    "×" -> commit(CalcTape.Op.MUL); "÷" -> commit(CalcTape.Op.DIV)
                                    "%" -> commit(CalcTape.Op.ADD, percent = true)
                                    "CE" -> entry = ""
                                    "C" -> { tape.clear(); entry = ""; editing = -1; version++ }
                                    "M+" -> { tape.memoryAdd(entry.toDoubleOrNull() ?: tape.current()); version++ }
                                    "M−" -> { tape.memorySub(entry.toDoubleOrNull() ?: tape.current()); version++ }
                                    "MR" -> entry = tape.memory.toString()
                                    "MC" -> { tape.memoryClear(); version++ }
                                    "◇" -> commit(CalcTape.Op.SUBTOTAL)
                                    "*" -> commit(CalcTape.Op.TOTAL)
                                    "↶" -> { tape.undo(); version++ }
                                    "↷" -> { tape.redo(); version++ }
                                    "✎" -> if (editing >= 0) { entry.toDoubleOrNull()?.let { tape.edit(editing, it) }; editing = -1; entry = ""; version++ }
                                }
                            }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(48.dp).height(38.dp)) { Text(k, maxLines = 1) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cx_calc_decimals), style = MaterialTheme.typography.labelSmall)
                    listOf(0, 2, 4).forEach { d -> FilterChip(decimals == d, { decimals = d }, label = { Text(d.toString()) }, modifier = Modifier.padding(start = 4.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(withDate, { withDate = it }); Text(stringResource(R.string.cx_calc_date)) }
            }
        },
        confirmButton = {
            TextButton(enabled = tape.size > 0, onClick = {
                val text = tape.text(decimals, dateHeader)
                val pg = state.currentPage
                state.scope.launch {
                    val (pw, ph) = state.pageSize(pg)
                    val lines = text.split('\n')
                    val fs = 10f
                    val w = (lines.maxOf { it.length } * fs * 0.62f + 16f).coerceAtMost(pw - 20f)
                    val hh = lines.size * fs * 1.3f + 10f
                    val r = android.graphics.RectF(pw / 2 - w / 2, ph / 2 + hh / 2, pw / 2 + w / 2, ph / 2 - hh / 2)
                    state.addAnnot(state.newAnnot(AnnotKind.FREETEXT, pg, rects = listOf(r), text = text, fontSize = fs, color = 0x212121).copy(subject = "Calculator Tape"))
                }
                onDismiss()
            }) { Text(stringResource(R.string.cx_calc_place)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}

/**
 * Bar shown while a stamp / image is being placed (7.40, 24.54): exact width and height in points with a ratio
 * lock, opacity, "place on several pages" and done.
 */
@Composable
fun PlacementBar(state: ReaderState, modifier: Modifier = Modifier) {
    val p = state.placement ?: return
    var sizeOpen by remember { mutableStateOf(false) }
    var opacity by remember(p.image) { mutableFloatStateOf(1f) }
    var opOpen by remember { mutableStateOf(false) }
    Surface(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Column(Modifier.padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { sizeOpen = true }) { Icon(Icons.Default.AspectRatio, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.cx_size)) }
                TextButton(onClick = { opOpen = !opOpen }) { Icon(Icons.Default.Opacity, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ae_opacity)) }
                TextButton(onClick = {
                    val before = state.annots.map { it.id }.toSet()
                    state.confirmPlacement()
                    state.annots.firstOrNull { it.id !in before }?.let { a -> state.annot2.cx.pagesReq = PagesReq(CTarget(a.page, listOf(a.id), emptyList())) }
                }) { Icon(Icons.Default.FileCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.cx_place_pages)) }
                IconButton(onClick = { state.confirmPlacement() }) { Icon(Icons.Default.Check, stringResource(R.string.done)) }
            }
            if (opOpen) Row(Modifier.width(280.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Slider(opacity, { opacity = it }, Modifier.weight(1f), valueRange = 0.1f..1f, onValueChangeFinished = {
                    val cur = state.placement ?: return@Slider
                    state.placement = cur.copy(image = com.smartdocs.editor.ui.view5.StampProcess.apply(cur.image.copy(android.graphics.Bitmap.Config.ARGB_8888, true), opacity, false))
                    opacity = 1f
                })
                Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (sizeOpen) {
        val r = com.smartdocs.editor.ui.reader.PageGeometry.normPdf(p.rect)
        val w0 = r.right - r.left; val h0 = r.top - r.bottom
        var w by remember { mutableStateOf(String.format(java.util.Locale.US, "%.1f", w0)) }
        var hh by remember { mutableStateOf(String.format(java.util.Locale.US, "%.1f", h0)) }
        var lock by remember { mutableStateOf(true) }
        AlertDialog(onDismissRequest = { sizeOpen = false }, title = { Text(stringResource(R.string.cx_size)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(w, { v -> w = v; if (lock) v.toFloatOrNull()?.let { hh = String.format(java.util.Locale.US, "%.1f", it * h0 / w0.coerceAtLeast(0.1f)) } },
                    label = { Text(stringResource(R.string.cx_width_pt)) }, singleLine = true)
                OutlinedTextField(hh, { v -> hh = v; if (lock) v.toFloatOrNull()?.let { w = String.format(java.util.Locale.US, "%.1f", it * w0 / h0.coerceAtLeast(0.1f)) } },
                    label = { Text(stringResource(R.string.cx_height_pt)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(lock, { lock = it }); Text(stringResource(R.string.cx_keep_ratio)) }
            } },
            confirmButton = { TextButton(onClick = {
                val nw = w.toFloatOrNull()?.coerceIn(4f, 5000f) ?: w0; val nh = hh.toFloatOrNull()?.coerceIn(4f, 5000f) ?: h0
                val cx = (r.left + r.right) / 2; val cy = (r.top + r.bottom) / 2
                state.placement = state.placement?.copy(rect = android.graphics.RectF(cx - nw / 2, cy + nh / 2, cx + nw / 2, cy - nh / 2))
                sizeOpen = false
            }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { sizeOpen = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Options of the comments summary (7.70 / 24.87); also used by print with summary (7.71 / 7.72). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryOptionsFields(o: com.smartdocs.editor.core.annot.SummaryOptions, pageCount: Int, onChange: (com.smartdocs.editor.core.annot.SummaryOptions) -> Unit) {
    Text(stringResource(R.string.cx_sum_sort), style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(SumSort.PAGE to R.string.cx_sum_sort_page, SumSort.AUTHOR to R.string.an_sort_author, SumSort.TYPE to R.string.cx_sort_type,
            SumSort.CREATED_NEW to R.string.cx_sum_sort_new, SumSort.CREATED_OLD to R.string.cx_sum_sort_old, SumSort.MODIFIED to R.string.cx_sum_sort_mod).forEach { (s, l) ->
            FilterChip(o.sort == s, { onChange(o.copy(sort = s)) }, label = { Text(stringResource(l)) })
        }
    }
    Text(stringResource(R.string.cx_sum_text_size), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(0.85f to "S", 1f to "M", 1.25f to "L").forEach { (v, l) -> FilterChip(o.textScale == v, { onChange(o.copy(textScale = v)) }, label = { Text(l) }) }
        Spacer(Modifier.width(8.dp))
        FilterChip(!o.letter, { onChange(o.copy(letter = false)) }, label = { Text("A4") })
        FilterChip(o.letter, { onChange(o.copy(letter = true)) }, label = { Text("Letter") })
    }
    SwRow(o.thumbnails, R.string.cx_sum_thumbs) { onChange(o.copy(thumbnails = it)) }
    SwRow(o.includeEmptyPages, R.string.cx_sum_empty_pages) { onChange(o.copy(includeEmptyPages = it)) }
    SwRow(o.replies, R.string.cx_sum_replies) { onChange(o.copy(replies = it)) }
    SwRow(o.status, R.string.cx_sum_status) { onChange(o.copy(status = it)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(o.fromPage.toString(), { v -> v.toIntOrNull()?.let { onChange(o.copy(fromPage = it.coerceIn(1, pageCount.coerceAtLeast(1)))) } },
            Modifier.width(96.dp), label = { Text(stringResource(R.string.cx_sum_from)) }, singleLine = true)
        OutlinedTextField(if (o.toPage == Int.MAX_VALUE) pageCount.toString() else o.toPage.toString(), { v -> v.toIntOrNull()?.let { onChange(o.copy(toPage = it.coerceAtLeast(1))) } },
            Modifier.width(96.dp), label = { Text(stringResource(R.string.cx_sum_to)) }, singleLine = true)
    }
}

@Composable
private fun SwRow(v: Boolean, l: Int, f: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) { Switch(v, f); Spacer(Modifier.width(8.dp)); Text(stringResource(l)) }

@Composable
private fun SummaryOptionsDialog(pageCount: Int, onDismiss: () -> Unit, onOk: (com.smartdocs.editor.core.annot.SummaryOptions) -> Unit) {
    var o by remember { mutableStateOf(com.smartdocs.editor.core.annot.SummaryOptions()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.an_summary)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) { SummaryOptionsFields(o, pageCount) { o = it } } },
        confirmButton = { TextButton(onClick = { onOk(o) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Text box format (Foxit 24.99 font family, 24.101 strike, 24.102 spacing / scale, 24.105 bullets, 24.106 justify, 24.107 line spacing). */
@Composable
fun TextFormatDialog(initial: TextFmt, onDismiss: () -> Unit, showBiu: Boolean = false, onOk: (TextFmt) -> Unit) {
    var f by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.cx_text_format)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(R.string.cx_font_sans, R.string.cx_font_serif, R.string.cx_font_mono).forEachIndexed { i, r ->
                        FilterChip(f.family == i, { f = f.copy(family = i) }, label = { Text(stringResource(r)) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(-1 to Icons.Default.AutoAwesome, 0 to Icons.Default.FormatAlignLeft, 1 to Icons.Default.FormatAlignCenter,
                        2 to Icons.Default.FormatAlignRight, 3 to Icons.Default.FormatAlignJustify).forEach { (v, ic) ->
                        FilterChip(f.align == v, { f = f.copy(align = v) }, label = { Icon(ic, null, Modifier.size(18.dp)) })
                    }
                }
                if (showBiu) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(f.bold, { f = f.copy(bold = !f.bold) }, label = { Text("B", fontWeight = FontWeight.Bold) })
                    FilterChip(f.italic, { f = f.copy(italic = !f.italic) }, label = { Text("I", fontStyle = FontStyle.Italic) })
                    FilterChip(f.underline, { f = f.copy(underline = !f.underline) }, label = { Text("U", textDecoration = TextDecoration.Underline) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(f.strike, { f = f.copy(strike = it) }); Text(stringResource(R.string.cx_strike)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(f.bullets, { f = f.copy(bullets = it) }); Text(stringResource(R.string.cx_bullets)) }
                Text(stringResource(R.string.cx_line_spacing, "%.2f".format(java.util.Locale.US, f.lineSpacing)), style = MaterialTheme.typography.labelMedium)
                Slider(f.lineSpacing, { f = f.copy(lineSpacing = it) }, valueRange = 0.9f..3f)
                Text(stringResource(R.string.cx_char_spacing, "%.1f".format(java.util.Locale.US, f.charSpacing)), style = MaterialTheme.typography.labelMedium)
                Slider(f.charSpacing, { f = f.copy(charSpacing = it) }, valueRange = -1f..8f)
                Text(stringResource(R.string.cx_word_spacing, "%.1f".format(java.util.Locale.US, f.wordSpacing)), style = MaterialTheme.typography.labelMedium)
                Slider(f.wordSpacing, { f = f.copy(wordSpacing = it) }, valueRange = 0f..20f)
                Text(stringResource(R.string.cx_char_scale, (f.hScale * 100).toInt()), style = MaterialTheme.typography.labelMedium)
                Slider(f.hScale, { f = f.copy(hScale = it) }, valueRange = 0.5f..2f)
            }
        },
        confirmButton = { TextButton(onClick = { onOk(f) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
