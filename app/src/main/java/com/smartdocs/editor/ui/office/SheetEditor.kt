package com.smartdocs.editor.ui.office

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.office5.*

/** Spreadsheet editor: ribbon, formula bar, grid, sheet tabs and the selection status bar. */
@Composable
fun SheetEditor(s: SheetState) {
    val tick = s.tick
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 840.dp
        Column(Modifier.fillMaxSize()) {
            SheetRibbon(s)
            if (s.findOpen) SheetFindBar(s)   // office6
            FormulaBar(s)
            s.suggestions().takeIf { it.isNotEmpty() }?.let { names ->
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) { names.forEach { n -> SuggestionChip(onClick = { s.applySuggestion(n) }, label = { Text(n) }, modifier = Modifier.padding(end = 4.dp)) } }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    SheetGrid(s)
                    CellMenu(s)
                }
                if (wide) CellPanel(s, Modifier.width(260.dp).fillMaxHeight())
            }
            StatusRow(s)
            SheetTabs(s)
        }
    }
}

@Composable
private fun FormulaBar(s: SheetState) {
    val fr = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    var goto by remember { mutableStateOf(false) }
    var fx by remember { mutableStateOf(false) }
    LaunchedEffect(s.editing) { if (s.editing) runCatching { fr.requestFocus() } }
    Surface(tonalElevation = 1.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.sel.size > 1) s.sel.a1().let { if (s.wholeColumns || s.wholeRows) A1.name(s.row, s.col) else it } else A1.name(s.row, s.col),
                    Modifier.width(76.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { goto = true }.padding(horizontal = 6.dp, vertical = 8.dp),
                    fontSize = 13.sp, maxLines = 1)
                IconButton(onClick = { fx = true }) { Icon(Icons.Default.Functions, stringResource(R.string.o5_functions)) }
                BasicTextField(
                    s.input, { s.onInput(it) },
                    Modifier.weight(1f).focusRequester(fr).onFocusChanged { if (it.isFocused && !s.editing) s.startEdit() }
                        .border(1.dp, if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)).padding(8.dp),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (s.commit(true)) focus.clearFocus() }),
                )
                if (s.editing) {
                    IconButton(onClick = { if (s.commit(false)) focus.clearFocus() }) { Icon(Icons.Default.Check, stringResource(R.string.o5_ok), tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { s.cancelEdit(); focus.clearFocus() }) { Icon(Icons.Default.Close, stringResource(R.string.o5_cancel)) }
                }
            }
            s.error?.let { Text(stringResource(R.string.o5_formula_error, it), Modifier.padding(horizontal = 10.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }
    if (goto) TextInputDialog(R.string.o5_goto, A1.name(s.row, s.col), onDismiss = { goto = false }) { t ->
        goto = false
        val ref = t.trim().uppercase()
        A1.parseRange(ref)?.let { r -> s.select(r.r1, r.c1); if (r.size > 1) { s.sel = r; s.bump() } }
    }
    if (fx) FunctionsDialog({ fx = false }) { name ->
        fx = false
        if (s.editing && s.input.text.startsWith("=")) { val t = s.input.text; val at = s.input.selection.start; s.onInput(androidx.compose.ui.text.input.TextFieldValue(t.substring(0, at) + name + "(" + t.substring(at), androidx.compose.ui.text.TextRange(at + name.length + 1))) }
        else s.startEdit("=$name(")
    }
}

private val CATEGORIES = listOf("math" to R.string.o5_cat_math, "stat" to R.string.o5_cat_stat, "logical" to R.string.o5_cat_logical, "text" to R.string.o5_cat_text,
    "date" to R.string.o5_cat_date, "lookup" to R.string.o5_cat_lookup, "info" to R.string.o5_cat_info)

@Composable
fun FunctionsDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var cat by remember { mutableStateOf("math") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.o5_functions)) },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState())) { CATEGORIES.forEach { (k, l) -> FilterChip(cat == k, { cat = k }, { Text(stringResource(l)) }, Modifier.padding(end = 4.dp)) } }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(FxFunctions.all.filter { it.category == cat }.sortedBy { it.name }) { f ->
                        Text(f.name, Modifier.fillMaxWidth().clickable { onPick(f.name) }.padding(vertical = 10.dp, horizontal = 4.dp), fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.o5_cancel)) } },
    )
}

/** Context menu on a re-tapped cell. */
@Composable
private fun BoxScope.CellMenu(s: SheetState) {
    val cb = LocalClipboardManager.current
    val items = listOf<Pair<Int, () -> Unit>>(
        R.string.o5_cut to { cb.setText(AnnotatedString(s.copy(cut = true))) },
        R.string.o5_copy to { cb.setText(AnnotatedString(s.copy(cut = false))) },
        R.string.o5_paste to { s.paste(false, cb.getText()?.text) },
        R.string.o5_paste_values to { s.paste(true, cb.getText()?.text) },
        R.string.o5_clear to { s.clear() },
        R.string.o5_insert_row to { s.change { s.wb.shiftRows(s.sheet, s.sel.r1, s.sel.rows.coerceAtMost(1000)) } },
        R.string.o5_insert_col to { s.change { s.wb.shiftCols(s.sheet, s.sel.c1, s.sel.cols.coerceAtMost(100)) } },
        R.string.o5_delete_row to { s.change { s.wb.shiftRows(s.sheet, s.sel.r1, -s.sel.rows) } },
        R.string.o5_delete_col to { s.change { s.wb.shiftCols(s.sheet, s.sel.c1, -s.sel.cols) } },
    )
    Box(Modifier.align(Alignment.TopCenter)) {
        DropdownMenu(s.menuOpen, { s.menuOpen = false }) {
            items.forEachIndexed { i, (label, action) ->
                if (i == 5) HorizontalDivider()
                DropdownMenuItem({ Text(stringResource(label)) }, { s.menuOpen = false; action() })
            }
        }
    }
}

/** Tablet side panel: details of the active cell. */
@Composable
private fun CellPanel(s: SheetState, modifier: Modifier) {
    val tick = s.tick
    val cell = s.sheet.cell(s.row, s.col)
    val v = s.wb.book.value(s.sheet, s.row, s.col)
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(A1.name(s.row, s.col), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.o5_cell_value) + ": " + s.wb.book.display(s.sheet, s.row, s.col).text, style = TextStyle(textDirection = TextDirection.Content))
            cell?.formula?.let { Text(stringResource(R.string.o5_cell_formula) + ": =" + it) }
            Text(stringResource(R.string.o5_cell_format) + ": " + s.fmt.numFmt)
            if (cell?.formula != null && s.wb.book.usesUnsupported(s.sheet, s.row, s.col)) Text(stringResource(R.string.o5_unsupported_fn), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (v == FV.CIRC) Text(stringResource(R.string.o5_circular), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            s.wb.aggregates(s.sheet, s.sel)?.takeIf { s.sel.size > 1 }?.let { a ->
                HorizontalDivider()
                AGG.forEachIndexed { i, l -> Text(stringResource(l) + ": " + FxCoerce.general(FxCoerce.round(a[i], 6))) }
            }
        }
    }
}

private val AGG = listOf(R.string.o5_sum, R.string.o5_average, R.string.o5_count, R.string.o5_min, R.string.o5_max)

@Composable
private fun StatusRow(s: SheetState) {
    val tick = s.tick
    val agg = if (s.sel.size > 1) s.wb.aggregates(s.sheet, s.sel) else null
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (agg != null) stringResource(R.string.o5_sheet_status, FxCoerce.general(FxCoerce.round(agg[0], 6)), FxCoerce.general(FxCoerce.round(agg[1], 6)), agg[5].toInt())
                else if (s.wb.kind == Workbook5.Kind.CSV) stringResource(R.string.o5_csv_hint) else "",
                fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1,
            )
            Text("${(s.zoom * 100).toInt()}%", fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetTabs(s: SheetState) {
    val tick = s.tick
    var menuFor by remember { mutableStateOf(-1) }
    var rename by remember { mutableStateOf(-1) }
    var err by remember { mutableStateOf<String?>(null) }
    val base = stringResource(R.string.o5_sheet_base)
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (s.wb.kind != Workbook5.Kind.CSV) IconButton(onClick = {
                s.change { s.wb.addSheet(s.wb.uniqueName(base), s.active + 1) }; s.switchSheet(s.active + 1)
            }) { Icon(Icons.Default.Add, stringResource(R.string.o5_add_sheet)) }
            LazyRow(Modifier.weight(1f)) {
                itemsIndexed(s.wb.book.sheets) { i, sh ->
                    Box {
                        Text(sh.name, Modifier.combinedClickable(onClick = { s.switchSheet(i) }, onLongClick = { menuFor = i })
                            .background(if (i == s.active) MaterialTheme.colorScheme.surface else Color.Transparent).padding(horizontal = 14.dp, vertical = 10.dp),
                            fontWeight = if (i == s.active) FontWeight.Bold else FontWeight.Normal, color = if (i == s.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        DropdownMenu(menuFor == i, { menuFor = -1 }) {
                            DropdownMenuItem({ Text(stringResource(R.string.o5_rename)) }, { menuFor = -1; rename = i })
                            DropdownMenuItem({ Text(stringResource(R.string.o5_move_left)) }, { menuFor = -1; if (i > 0) { s.change { s.wb.moveSheet(i, i - 1) }; s.switchSheet(i - 1) } })
                            DropdownMenuItem({ Text(stringResource(R.string.o5_move_right)) }, { menuFor = -1; if (i < s.wb.book.sheets.size - 1) { s.change { s.wb.moveSheet(i, i + 1) }; s.switchSheet(i + 1) } })
                            DropdownMenuItem({ Text(stringResource(R.string.o5_delete)) }, { menuFor = -1; if (s.wb.book.sheets.size > 1) { s.change { s.wb.deleteSheet(sh) }; s.switchSheet((i - 1).coerceAtLeast(0)) } }, enabled = s.wb.book.sheets.size > 1)
                        }
                    }
                }
            }
        }
    }
    if (rename >= 0) {
        val sh = s.wb.book.sheets.getOrNull(rename)
        if (sh == null) rename = -1 else TextInputDialog(R.string.o5_rename, sh.name, onDismiss = { rename = -1 }) { name ->
            val why = s.wb.checkName(name.trim(), sh)
            if (why != null) err = why else { s.change { s.wb.renameSheet(sh, name.trim()) }; rename = -1 }
        }
    }
    err?.let { AlertDialog(onDismissRequest = { err = null }, text = { Text(stringResource(R.string.o5_bad_sheet_name)) }, confirmButton = { TextButton(onClick = { err = null }) { Text(stringResource(R.string.o5_ok)) } }) }
}
