package com.smartdocs.editor.ui.office

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionLToR
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R
import com.smartdocs.editor.core.office5.*
import com.smartdocs.editor.ui.common.rememberImagePicker

private val WORD_TABS = listOf(
    RibbonTab("home", R.string.o5_tab_home), RibbonTab("insert", R.string.o5_tab_insert), RibbonTab("layout", R.string.o5_tab_layout),
    RibbonTab("review", R.string.o5_tab_review), RibbonTab("view", R.string.o5_tab_view),
)

/** Word ribbon (Home, Insert, Layout, Review, View) plus contextual Table / Picture tabs. */
@Composable
fun WordRibbon(s: WordState, wide: Boolean) {
    var tab by remember { mutableStateOf("home") }
    val tick = s.tick
    val tabs = WORD_TABS + listOfNotNull(
        RibbonTab("table", R.string.o5_tab_table).takeIf { s.cell != null },
        RibbonTab("picture", R.string.o5_tab_picture).takeIf { s.image != null },
    )
    LaunchedEffect(s.cell != null, s.image != null) {
        if (s.cell != null) tab = "table" else if (s.image != null) tab = "picture" else if (tab == "table" || tab == "picture") tab = "home"
    }
    Column {
        RibbonTabs(tabs, tab, { tab = it })
        when (tab) {
            "home" -> WordHome(s)
            "insert" -> WordInsert(s)
            "layout" -> WordLayout(s)
            "review" -> WordReview(s)
            "view" -> WordViewTab(s, wide)
            "table" -> WordTable(s)
            "picture" -> WordPicture(s)
        }
        HorizontalDivider()
    }
}

@Composable
private fun needTarget(s: WordState): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    return { f -> if (s.hasTarget) f() else Toast.makeText(ctx, ctx.getString(R.string.o5_pick_paragraph), Toast.LENGTH_SHORT).show() }
}

@Composable
private fun WordHome(s: WordState) {
    val p = s.props()
    val run = needTarget(s)
    var colorDlg by remember { mutableStateOf(0) }
    var sizeDlg by remember { mutableStateOf(false) }
    RibbonRow {
        RibbonButton(Icons.Default.FormatBold, R.string.o5_bold, p.bold == true) { run { s.toggle({ it.bold }) { r, on -> WordFormat.setBold(r, on) } } }
        RibbonButton(Icons.Default.FormatItalic, R.string.o5_italic, p.italic == true) { run { s.toggle({ it.italic }) { r, on -> WordFormat.setItalic(r, on) } } }
        RibbonButton(Icons.Default.FormatUnderlined, R.string.o5_underline, p.underline == true) { run { s.toggle({ it.underline }) { r, on -> WordFormat.setUnderline(r, on) } } }
        RibbonButton(Icons.Default.FormatStrikethrough, R.string.o5_strike, p.strike == true) { run { s.toggle({ it.strike }) { r, on -> WordFormat.setStrike(r, on) } } }
        RibbonDivider()
        val size = p.sizePt ?: WordView.DEFAULT_SIZE
        RibbonButton(Icons.Default.TextDecrease, R.string.o5_smaller) { run { s.format { WordFormat.setSize(it, nextSize(size, false)) } } }
        RibbonChip(FxCoerce.general(size.toDouble())) { run { sizeDlg = true } }
        RibbonButton(Icons.Default.TextIncrease, R.string.o5_bigger) { run { s.format { WordFormat.setSize(it, nextSize(size, true)) } } }
        RibbonColor(Icons.Default.FormatColorText, R.string.o5_font_color, androidx.compose.ui.graphics.Color.hex(p.color)) { run { colorDlg = 1 } }
        RibbonColor(Icons.Default.BorderColor, R.string.o5_highlight, androidx.compose.ui.graphics.Color.hex(p.highlight)) { run { colorDlg = 2 } }
        RibbonButton(Icons.Default.FormatClear, R.string.o5_clear_format) { run { s.clearFormatting() } }
        RibbonDivider()
        val al = s.align()
        RibbonButton(Icons.Default.FormatAlignRight, R.string.o5_align_start, al == PAlign.START || al == null) { run { s.paragraphs { WordFormat.setAlign(it, PAlign.START) } } }
        RibbonButton(Icons.Default.FormatAlignCenter, R.string.o5_align_center, al == PAlign.CENTER) { run { s.paragraphs { WordFormat.setAlign(it, PAlign.CENTER) } } }
        RibbonButton(Icons.Default.FormatAlignLeft, R.string.o5_align_end, al == PAlign.END) { run { s.paragraphs { WordFormat.setAlign(it, PAlign.END) } } }
        RibbonButton(Icons.Default.FormatAlignJustify, R.string.o5_align_justify, al == PAlign.JUSTIFY) { run { s.paragraphs { WordFormat.setAlign(it, PAlign.JUSTIFY) } } }
        val rtl = s.rtl()
        RibbonButton(Icons.AutoMirrored.Filled.FormatTextdirectionRToL, R.string.o5_rtl, rtl) { run { s.paragraphs { WordFormat.setBidi(it, true) } } }
        RibbonButton(Icons.AutoMirrored.Filled.FormatTextdirectionLToR, R.string.o5_ltr, !rtl && s.hasTarget) { run { s.paragraphs { WordFormat.setBidi(it, false) } } }
        RibbonDivider()
        RibbonButton(Icons.AutoMirrored.Filled.FormatListBulleted, R.string.o5_bullets) { run { s.toggleList(true) } }
        RibbonButton(Icons.Default.FormatListNumbered, R.string.o5_numbering) { run { s.toggleList(false) } }
        RibbonButton(Icons.AutoMirrored.Filled.FormatIndentDecrease, R.string.o5_indent_less) { run { s.paragraphs { s.doc.changeListLevel(it, -1) } } }
        RibbonButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, R.string.o5_indent_more) { run { s.paragraphs { s.doc.changeListLevel(it, 1) } } }
        RibbonDivider()
        val k = s.styleKind()
        STYLE_LABELS.forEach { (kind, label) -> RibbonChip(stringResource(label), k == kind) { run { s.applyStyle(kind) } } }
    }
    if (colorDlg != 0) ColorPickerDialog(if (colorDlg == 1) R.string.o5_font_color else R.string.o5_highlight, allowNone = true,
        palette = if (colorDlg == 2) WordFormat.highlightPalette else OFFICE_PALETTE, onDismiss = { colorDlg = 0 }) { c ->
        val which = colorDlg; colorDlg = 0
        s.format { if (which == 1) WordFormat.setColor(it, c) else WordFormat.setHighlight(it, c) }
    }
    if (sizeDlg) FontSizeDialog(p.sizePt ?: WordView.DEFAULT_SIZE, { sizeDlg = false }) { v -> sizeDlg = false; s.format { WordFormat.setSize(it, v) } }
}

private val STYLE_LABELS = listOf(
    WordParts.StyleKind.NORMAL to R.string.o5_style_normal, WordParts.StyleKind.TITLE to R.string.o5_style_title,
    WordParts.StyleKind.HEADING1 to R.string.o5_style_h1, WordParts.StyleKind.HEADING2 to R.string.o5_style_h2,
    WordParts.StyleKind.HEADING3 to R.string.o5_style_h3, WordParts.StyleKind.QUOTE to R.string.o5_style_quote,
)

@Composable
private fun WordInsert(s: WordState) {
    var tableDlg by remember { mutableStateOf(false) }
    val pickImage = rememberImagePicker(multiple = false) { files -> files.firstOrNull()?.let { f -> runCatching { f.readBytes() }.getOrNull()?.let { s.insertImage(it) } } }
    val run = needTarget(s)
    RibbonRow {
        RibbonButton(Icons.Default.PostAdd, R.string.o5_new_paragraph) { s.insertParagraph() }
        RibbonButton(Icons.Default.TableChart, R.string.o5_table) { tableDlg = true }
        RibbonButton(Icons.Default.Image, R.string.o5_picture) { pickImage() }
        RibbonButton(Icons.Default.InsertPageBreak, R.string.o5_page_break) { s.insertPageBreak() }
        RibbonButton(Icons.Default.Today, R.string.o5_date) { run { s.insertAtCursor(java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date())) } }
        RibbonButton(Icons.Default.Delete, R.string.o5_delete_block, enabled = s.anchor != null) { s.deleteBlock() }
    }
    if (tableDlg) SizeDialog(R.string.o5_insert_table, 3, 3, R.string.o5_rows, R.string.o5_cols, { tableDlg = false }) { r, c -> tableDlg = false; s.insertTable(r, c) }
}

@Composable
private fun WordLayout(s: WordState) {
    val landscape = remember(s.tick) { s.doc.isLandscape() }
    RibbonRow {
        RibbonButton(Icons.Default.StayCurrentPortrait, R.string.o5_portrait, !landscape) { s.doc.change { s.doc.setLandscape(false) }; s.bump() }
        RibbonButton(Icons.Default.StayCurrentLandscape, R.string.o5_landscape, landscape) { s.doc.change { s.doc.setLandscape(true) }; s.bump() }
        RibbonDivider()
        RibbonChip(stringResource(R.string.o5_margin_normal)) { s.doc.change { s.doc.setMargins(1440) }; s.bump() }
        RibbonChip(stringResource(R.string.o5_margin_narrow)) { s.doc.change { s.doc.setMargins(720) }; s.bump() }
        RibbonChip(stringResource(R.string.o5_margin_wide)) { s.doc.change { s.doc.setMargins(2160) }; s.bump() }
        RibbonDivider()
        RibbonButton(Icons.AutoMirrored.Filled.FormatTextdirectionRToL, R.string.o5_doc_rtl) { s.doc.change { s.doc.blocks().filter { it.local == "p" }.forEach { WordFormat.setBidi(it, true) } }; s.bump() }
        RibbonButton(Icons.AutoMirrored.Filled.FormatTextdirectionLToR, R.string.o5_doc_ltr) { s.doc.change { s.doc.blocks().filter { it.local == "p" }.forEach { WordFormat.setBidi(it, false) } }; s.bump() }
    }
}

@Composable
private fun WordReview(s: WordState) {
    var count by remember { mutableStateOf(false) }
    RibbonRow {
        RibbonButton(Icons.Default.Search, R.string.o5_find, s.findOpen) { s.findOpen = !s.findOpen; s.bump() }
        RibbonButton(Icons.Default.FindReplace, R.string.o5_replace) { s.findOpen = true; s.bump() }
        RibbonButton(Icons.Default.Numbers, R.string.o5_word_count) { count = true }
        RibbonButton(Icons.Default.RecordVoiceOver, R.string.of6_read_aloud) { OfficeCommandRegistry.run("read_aloud") }   // office6 (registry)
        RibbonButton(Icons.Default.Print, R.string.of6_print) { OfficeCommandRegistry.run("print") }
    }
    if (count) {
        val st = remember { s.doc.stats() }
        AlertDialog(onDismissRequest = { count = false }, title = { Text(stringResource(R.string.o5_word_count)) },
            text = { Text(stringResource(R.string.o5_word_count_text, st.words, st.chars, st.charsNoSpaces, st.paragraphs, st.pagesEstimate)) },
            confirmButton = { TextButton(onClick = { count = false }) { Text(stringResource(R.string.o5_ok)) } })
    }
}

@Composable
private fun WordViewTab(s: WordState, wide: Boolean) {
    RibbonRow {
        RibbonButton(Icons.Default.ChromeReaderMode, R.string.o5_reading_mode, s.reading) { s.commitPending(); s.clearFocus(); s.reading = true; s.bump() }
        RibbonButton(Icons.Default.ZoomOut, R.string.o5_zoom_out) { s.zoom = (s.zoom - 0.1f).coerceAtLeast(0.6f); s.bump() }
        RibbonButton(Icons.Default.ZoomIn, R.string.o5_zoom_in) { s.zoom = (s.zoom + 0.1f).coerceAtMost(2.5f); s.bump() }
        if (wide) RibbonButton(Icons.Default.ViewSidebar, R.string.o5_outline, s.outline) { s.outline = !s.outline; s.bump() }
    }
}

@Composable
private fun WordTable(s: WordState) {
    var shade by remember { mutableStateOf(false) }
    RibbonRow {
        RibbonButton(Icons.Default.ArrowUpward, R.string.o5_row_above) { s.table { t, c -> WordTables.insertRow(t, c.row, false) } }
        RibbonButton(Icons.Default.ArrowDownward, R.string.o5_row_below) { s.table { t, c -> WordTables.insertRow(t, c.row, true) } }
        RibbonButton(Icons.Default.ViewColumn, R.string.o5_col_before) { s.table { t, c -> WordTables.insertColumn(t, WordTables.gridColOf(WordTables.rows(t)[c.row], c.cell), false) } }
        RibbonButton(Icons.Default.ViewWeek, R.string.o5_col_after) { s.table { t, c -> WordTables.insertColumn(t, WordTables.gridColOf(WordTables.rows(t)[c.row], c.cell), true) } }
        RibbonDivider()
        RibbonButton(Icons.Default.TableRows, R.string.o5_delete_row) { s.table { t, c -> WordTables.deleteRow(t, c.row) } }
        RibbonButton(Icons.Default.GridOff, R.string.o5_delete_col) { s.table { t, c -> WordTables.deleteColumn(t, WordTables.gridColOf(WordTables.rows(t)[c.row], c.cell)) } }
        RibbonButton(Icons.Default.FormatColorFill, R.string.o5_cell_shading) { shade = true }
        RibbonButton(Icons.Default.Delete, R.string.o5_delete_table) { s.deleteBlock() }
    }
    if (shade) ColorPickerDialog(R.string.o5_cell_shading, true, onDismiss = { shade = false }) { c ->
        shade = false; s.table { t, pos -> WordTables.rows(t).getOrNull(pos.row)?.let { WordTables.cells(it).getOrNull(pos.cell) }?.let { WordTables.setShading(it, c) } }
    }
}

@Composable
private fun WordPicture(s: WordState) {
    RibbonRow {
        RibbonButton(Icons.Default.ZoomOut, R.string.o5_smaller) { s.resizeImage(0.85f) }
        RibbonButton(Icons.Default.ZoomIn, R.string.o5_bigger) { s.resizeImage(1.15f) }
        RibbonButton(Icons.Default.FormatAlignRight, R.string.o5_align_start) { s.paragraphs { WordFormat.setAlign(it, PAlign.START) } }
        RibbonButton(Icons.Default.FormatAlignCenter, R.string.o5_align_center) { s.paragraphs { WordFormat.setAlign(it, PAlign.CENTER) } }
        RibbonButton(Icons.Default.FormatAlignLeft, R.string.o5_align_end) { s.paragraphs { WordFormat.setAlign(it, PAlign.END) } }
        RibbonButton(Icons.Default.Delete, R.string.o5_delete) { s.deleteBlock() }
    }
}
