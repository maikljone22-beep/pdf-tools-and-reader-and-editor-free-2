package com.smartdocs.editor.ui.office

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.smartdocs.editor.R
import com.smartdocs.editor.core.office5.*

private val SHEET_TABS = listOf(
    RibbonTab("home", R.string.o5_tab_home), RibbonTab("insert", R.string.o5_tab_insert), RibbonTab("formulas", R.string.o5_tab_formulas),
    RibbonTab("data", R.string.o5_tab_data), RibbonTab("view", R.string.o5_tab_view),
)

/** Number formats offered in the Home tab (label, format code). */
private val NUMBER_FORMATS = listOf(
    R.string.o5_nf_general to "General", R.string.o5_nf_number to "#,##0.00", R.string.o5_nf_integer to "#,##0",
    R.string.o5_nf_currency to "#,##0.00 \"\u0631.\u0633\"", R.string.o5_nf_currency_yer to "#,##0.00 \"\u0631.\u064A\"", R.string.o5_nf_dollar to "\"$\"#,##0.00",
    R.string.o5_nf_percent to "0%", R.string.o5_nf_percent2 to "0.00%", R.string.o5_nf_date to "yyyy-mm-dd", R.string.o5_nf_long_date to "d mmmm yyyy",
    R.string.o5_nf_time to "h:mm", R.string.o5_nf_scientific to "0.00E+00", R.string.o5_nf_text to "@",
)

@Composable
fun SheetRibbon(s: SheetState) {
    var tab by remember { mutableStateOf("home") }
    Column {
        RibbonTabs(SHEET_TABS, tab, { tab = it })
        when (tab) {
            "home" -> SheetHome(s)
            "insert" -> SheetInsert(s)
            "formulas" -> SheetFormulas(s)
            "data" -> SheetData(s)
            "view" -> SheetViewTab(s)
        }
        HorizontalDivider()
    }
}

/** Format code with one more / one less decimal place. */
fun changeDecimals(code: String, more: Boolean): String {
    if (code == "General" || code.isBlank()) return if (more) "0.0" else "0"
    val m = Regex("""0(\.0*)?""").findAll(code).lastOrNull() ?: return code
    val decs = m.groups[1]?.value?.length?.minus(1) ?: 0
    val nd = (decs + if (more) 1 else -1).coerceIn(0, 10)
    val rep = if (nd == 0) "0" else "0." + "0".repeat(nd)
    return code.replace(Regex("""0(\.0*)?""")) { if (it.range == m.range) rep else it.value }
}

@Composable
private fun SheetHome(s: SheetState) {
    val f = s.fmt
    val cb = LocalClipboardManager.current
    var colorDlg by remember { mutableIntStateOf(0) }
    var sizeDlg by remember { mutableStateOf(false) }
    var borders by remember { mutableStateOf(false) }
    var numbers by remember { mutableStateOf(false) }
    RibbonRow {
        RibbonButton(Icons.Default.Search, R.string.o5_find, s.findOpen) { s.findOpen = !s.findOpen; s.bump() }   // office6
        RibbonButton(Icons.Default.ContentCut, R.string.o5_cut) { cb.setText(AnnotatedString(s.copy(true))) }
        RibbonButton(Icons.Default.ContentCopy, R.string.o5_copy) { cb.setText(AnnotatedString(s.copy(false))) }
        RibbonButton(Icons.Default.ContentPaste, R.string.o5_paste) { s.paste(false, cb.getText()?.text) }
        RibbonDivider()
        RibbonButton(Icons.Default.FormatBold, R.string.o5_bold, f.bold) { s.format { it.copy(bold = !f.bold) } }
        RibbonButton(Icons.Default.FormatItalic, R.string.o5_italic, f.italic) { s.format { it.copy(italic = !f.italic) } }
        RibbonButton(Icons.Default.FormatUnderlined, R.string.o5_underline, f.underline) { s.format { it.copy(underline = !f.underline) } }
        RibbonButton(Icons.Default.FormatStrikethrough, R.string.o5_strike, f.strike) { s.format { it.copy(strike = !f.strike) } }
        RibbonChip(FxCoerce.general(f.fontSize.toDouble())) { sizeDlg = true }
        RibbonColor(Icons.Default.FormatColorText, R.string.o5_font_color, Color.hex(f.fontColor)) { colorDlg = 1 }
        RibbonColor(Icons.Default.FormatColorFill, R.string.o5_fill, Color.hex(f.fill)) { colorDlg = 2 }
        Box {
            RibbonButton(Icons.Default.BorderAll, R.string.o5_borders) { borders = true }
            DropdownMenu(borders, { borders = false }) {
                listOf(R.string.o5_border_all to "all", R.string.o5_border_outside to "outside", R.string.o5_border_thick to "thick",
                    R.string.o5_border_bottom to "bottom", R.string.o5_border_none to "none").forEach { (l, k) ->
                    DropdownMenuItem({ Text(stringResource(l)) }, { borders = false; applyBorders(s, k) })
                }
            }
        }
        RibbonDivider()
        val h = f.hAlign
        RibbonButton(Icons.Default.FormatAlignRight, R.string.o5_align_start, h == "right") { s.format { it.copy(hAlign = if (h == "right") null else "right") } }
        RibbonButton(Icons.Default.FormatAlignCenter, R.string.o5_align_center, h == "center") { s.format { it.copy(hAlign = if (h == "center") null else "center") } }
        RibbonButton(Icons.Default.FormatAlignLeft, R.string.o5_align_end, h == "left") { s.format { it.copy(hAlign = if (h == "left") null else "left") } }
        RibbonButton(Icons.Default.VerticalAlignTop, R.string.o5_valign_top, f.vAlign == "top") { s.format { it.copy(vAlign = "top") } }
        RibbonButton(Icons.Default.VerticalAlignCenter, R.string.o5_valign_middle, f.vAlign == "center") { s.format { it.copy(vAlign = "center") } }
        RibbonButton(Icons.Default.VerticalAlignBottom, R.string.o5_valign_bottom, f.vAlign == null || f.vAlign == "bottom") { s.format { it.copy(vAlign = null) } }
        RibbonButton(Icons.AutoMirrored.Filled.WrapText, R.string.o5_wrap, f.wrap) { s.format { it.copy(wrap = !f.wrap) } }
        RibbonButton(Icons.Default.CallMerge, R.string.o5_merge) { s.change { s.wb.merge(s.sheet, s.sel) } }
        RibbonButton(Icons.Default.CallSplit, R.string.o5_unmerge) { s.change { s.wb.unmerge(s.sheet, s.row, s.col) } }
        RibbonDivider()
        Box {
            RibbonChip(stringResource(NUMBER_FORMATS.firstOrNull { it.second == f.numFmt }?.first ?: R.string.o5_nf_custom)) { numbers = true }
            DropdownMenu(numbers, { numbers = false }) {
                NUMBER_FORMATS.forEach { (l, code) -> DropdownMenuItem({ Text(stringResource(l)) }, { numbers = false; s.format { it.copy(numFmt = code) } }) }
            }
        }
        RibbonButton(Icons.Default.Percent, R.string.o5_nf_percent) { s.format { it.copy(numFmt = "0%") } }
        RibbonButton(Icons.Default.Add, R.string.o5_more_decimals) { s.format { it.copy(numFmt = changeDecimals(it.numFmt, true)) } }
        RibbonButton(Icons.Default.Remove, R.string.o5_less_decimals) { s.format { it.copy(numFmt = changeDecimals(it.numFmt, false)) } }
        RibbonButton(Icons.Default.FormatClear, R.string.o5_clear) { s.clear(formats = false) }
    }
    if (colorDlg != 0) ColorPickerDialog(if (colorDlg == 1) R.string.o5_font_color else R.string.o5_fill, true, onDismiss = { colorDlg = 0 }) { c ->
        val which = colorDlg; colorDlg = 0
        s.format { if (which == 1) it.copy(fontColor = c) else it.copy(fill = c) }
    }
    if (sizeDlg) FontSizeDialog(f.fontSize, { sizeDlg = false }) { v -> sizeDlg = false; s.format { it.copy(fontSize = v) } }
}

private fun applyBorders(s: SheetState, kind: String) {
    val r = s.sel
    s.formatAt { row, col, f ->
        when (kind) {
            "all" -> f.copy(left = "thin", right = "thin", top = "thin", bottom = "thin")
            "none" -> f.copy(left = null, right = null, top = null, bottom = null)
            "bottom" -> if (row == r.r2) f.copy(bottom = "thin") else f
            else -> {
                val st = if (kind == "thick") "medium" else "thin"
                f.copy(left = if (col == r.c1) st else f.left, right = if (col == r.c2) st else f.right, top = if (row == r.r1) st else f.top, bottom = if (row == r.r2) st else f.bottom)
            }
        }
    }
}

@Composable
private fun SheetInsert(s: SheetState) {
    val base = stringResource(R.string.o5_sheet_base)
    var fx by remember { mutableStateOf(false) }
    RibbonRow {
        RibbonButton(Icons.Default.ArrowUpward, R.string.o5_insert_row) { s.change { s.wb.shiftRows(s.sheet, s.sel.r1, s.sel.rows.coerceAtMost(1000)) } }
        RibbonButton(Icons.Default.ArrowDownward, R.string.o5_insert_row_below) { s.change { s.wb.shiftRows(s.sheet, minOf(s.sel.r2, MAX_ROWS - 2) + 1, s.sel.rows.coerceAtMost(1000)) } }
        RibbonButton(Icons.Default.ViewColumn, R.string.o5_insert_col) { s.change { s.wb.shiftCols(s.sheet, s.sel.c1, s.sel.cols.coerceAtMost(100)) } }
        RibbonButton(Icons.Default.ViewWeek, R.string.o5_insert_col_after) { s.change { s.wb.shiftCols(s.sheet, minOf(s.sel.c2, MAX_COLS - 2) + 1, s.sel.cols.coerceAtMost(100)) } }
        RibbonButton(Icons.Default.TableRows, R.string.o5_delete_row) { s.change { s.wb.shiftRows(s.sheet, s.sel.r1, -s.sel.rows) } }
        RibbonButton(Icons.Default.GridOff, R.string.o5_delete_col) { s.change { s.wb.shiftCols(s.sheet, s.sel.c1, -s.sel.cols) } }
        RibbonDivider()
        if (s.wb.kind != Workbook5.Kind.CSV) RibbonButton(Icons.Default.PostAdd, R.string.o5_add_sheet) { s.change { s.wb.addSheet(s.wb.uniqueName(base), s.active + 1) }; s.switchSheet(s.active + 1) }
        RibbonButton(Icons.Default.Functions, R.string.o5_functions) { fx = true }
    }
    if (fx) FunctionsDialog({ fx = false }) { n -> fx = false; s.startEdit("=$n(") }
}

@Composable
private fun SheetFormulas(s: SheetState) {
    var fx by remember { mutableStateOf(false) }
    RibbonRow {
        RibbonButton(Icons.Default.Functions, R.string.o5_autosum) { s.autoFunction("SUM") }
        RibbonChip(stringResource(R.string.o5_average)) { s.autoFunction("AVERAGE") }
        RibbonChip(stringResource(R.string.o5_count)) { s.autoFunction("COUNT") }
        RibbonChip(stringResource(R.string.o5_min)) { s.autoFunction("MIN") }
        RibbonChip(stringResource(R.string.o5_max)) { s.autoFunction("MAX") }
        RibbonDivider()
        RibbonButton(Icons.Default.Calculate, R.string.o5_functions) { fx = true }
        RibbonButton(Icons.Default.Refresh, R.string.o5_recalc) { s.wb.book.recalcAll(); s.bump() }
        RibbonButton(Icons.Default.DataArray, R.string.o5_show_formulas, s.showFormulas) { s.showFormulas = !s.showFormulas; s.bump() }
    }
    if (fx) FunctionsDialog({ fx = false }) { n -> fx = false; s.startEdit("=$n(") }
}

@Composable
private fun SheetData(s: SheetState) {
    RibbonRow {
        RibbonButton(Icons.Default.FindReplace, R.string.o5_find, s.findOpen) { s.findOpen = !s.findOpen; s.bump() }   // office6
        RibbonButton(Icons.Default.RecordVoiceOver, R.string.of6_read_aloud) { OfficeCommandRegistry.run("read_aloud") }   // office6 (registry)
        RibbonButton(Icons.Default.Print, R.string.of6_print) { OfficeCommandRegistry.run("print") }
        RibbonButton(Icons.Default.SortByAlpha, R.string.o5_sort_asc) { s.sort(true) }
        RibbonButton(Icons.AutoMirrored.Filled.Sort, R.string.o5_sort_desc) { s.sort(false) }
        RibbonDivider()
        RibbonButton(Icons.Default.ArrowDownward, R.string.o5_fill_down, enabled = s.sel.rows > 1) { s.fillSelection(true) }
        RibbonButton(Icons.Default.ArrowBack, R.string.o5_fill_right, enabled = s.sel.cols > 1) { s.fillSelection(false) }
        RibbonButton(Icons.Default.ClearAll, R.string.o5_clear_all) { s.clear(formats = true) }
    }
}

@Composable
private fun SheetViewTab(s: SheetState) {
    var width by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(false) }
    val sh = s.sheet
    RibbonRow {
        RibbonButton(Icons.Default.Window, R.string.o5_freeze_top, sh.freezeRows == 1 && sh.freezeCols == 0) { s.change { s.wb.freeze(sh, if (sh.freezeRows == 1 && sh.freezeCols == 0) 0 else 1, 0) } }
        RibbonButton(Icons.Default.ViewColumn, R.string.o5_freeze_first_col, sh.freezeCols == 1 && sh.freezeRows == 0) { s.change { s.wb.freeze(sh, 0, if (sh.freezeCols == 1 && sh.freezeRows == 0) 0 else 1) } }
        RibbonButton(Icons.Default.GridOn, R.string.o5_freeze_here) { s.change { s.wb.freeze(sh, s.row, s.col) } }
        RibbonButton(Icons.Default.GridOff, R.string.o5_unfreeze) { s.change { s.wb.freeze(sh, 0, 0) } }
        RibbonDivider()
        RibbonButton(Icons.Default.Grid4x4, R.string.o5_gridlines, s.gridlines) { s.gridlines = !s.gridlines; s.bump() }
        RibbonButton(Icons.Default.ZoomOut, R.string.o5_zoom_out) { s.zoom = (s.zoom - 0.1f).coerceAtLeast(0.4f); s.bump() }
        RibbonButton(Icons.Default.ZoomIn, R.string.o5_zoom_in) { s.zoom = (s.zoom + 0.1f).coerceAtMost(3f); s.bump() }
        RibbonButton(Icons.Default.SwapHoriz, R.string.o5_sheet_rtl, sh.rtl) { s.change { sh.rtl = !sh.rtl; sh.dirty = true } }
        RibbonDivider()
        RibbonButton(Icons.Default.Height, R.string.o5_row_height) { height = true }
        RibbonButton(Icons.Default.WidthNormal, R.string.o5_col_width) { width = true }
        RibbonButton(Icons.Default.FitScreen, R.string.o5_autofit) { s.change { (s.sel.c1..minOf(s.sel.c2, s.sel.c1 + 200)).forEach { c -> s.wb.setColWidth(sh, c..c, s.wb.fitWidth(sh, c)) } } }
    }
    if (width) TextInputDialog(R.string.o5_col_width, FxCoerce.general(sh.colWidth(s.col).toDouble()), number = true, onDismiss = { width = false }) { v ->
        width = false; v.replace(',', '.').toFloatOrNull()?.let { w -> s.change { s.wb.setColWidth(sh, s.sel.c1..minOf(s.sel.c2, s.sel.c1 + 500), w) } }
    }
    if (height) TextInputDialog(R.string.o5_row_height, FxCoerce.general(sh.rowHeight(s.row).toDouble()), number = true, onDismiss = { height = false }) { v ->
        height = false; v.replace(',', '.').toFloatOrNull()?.let { h -> s.change { s.wb.setRowHeight(sh, s.sel.r1..minOf(s.sel.r2, s.sel.r1 + 5000), h) } }
    }
}
