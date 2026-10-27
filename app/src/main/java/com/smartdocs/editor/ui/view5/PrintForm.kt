package com.smartdocs.editor.ui.view5

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.SummaryLabels
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.view5.PrintContent
import com.smartdocs.editor.core.view5.PrintLayout
import com.smartdocs.editor.core.view5.PrintPresets
import com.smartdocs.editor.core.view5.PrintSettings
import com.smartdocs.editor.ui.reader.annot.statusLabelRes
import com.smartdocs.editor.ui.reader.annot.subtypeLabelRes
import java.io.File

/** Labels of the comment summary appended when printing (same texts as the reader's "Summarize comments"). */
fun printSummaryLabels(ctx: Context): SummaryLabels = SummaryLabels(
    title = ctx.getString(R.string.an_summary_title), pageFmt = ctx.getString(R.string.an_summary_page),
    continued = ctx.getString(R.string.an_summary_cont), repliesLabel = ctx.getString(R.string.an_reply_word),
    statusLabel = { ctx.getString(statusLabelRes(it)) },
    typeLabel = { sub -> subtypeLabelRes(sub)?.let { ctx.getString(it) } ?: sub },
    empty = ctx.getString(R.string.an_no_comments),
)

private enum class PrintMode { SINGLE, NUP, BOOKLET, POSTER }

private fun PrintSettings.mode(): PrintMode = when {
    booklet -> PrintMode.BOOKLET
    poster -> PrintMode.POSTER
    perSheet > 1 || cols > 0 && rows > 0 -> PrintMode.NUP
    else -> PrintMode.SINGLE
}

@Composable
private fun Chips(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(on, onChange)
    }

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onValue: (String) -> Unit) =
    OutlinedTextField(value, onValue, modifier, singleLine = true, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

/**
 * The print options shared by the print sheet and batch print: which pages (subset / reverse), what to print, layout
 * (one page per sheet with scale, N-up or N×M, booklet with sides, poster tiles), paper, margins, greyscale / image,
 * comment summary; and the named presets.
 */
@Composable
fun PrintOptionsForm(s: PrintSettings, onChange: (PrintSettings) -> Unit) {
    PrintPresetsRow(s, onChange)
    Chips {
        FilterChip(s.subset == PrintLayout.Subset.ALL, { onChange(s.copy(subset = PrintLayout.Subset.ALL)) }, label = { Text(stringResource(R.string.v5_odd_even_all)) })
        FilterChip(s.subset == PrintLayout.Subset.ODD, { onChange(s.copy(subset = PrintLayout.Subset.ODD)) }, label = { Text(stringResource(R.string.v5_odd)) })
        FilterChip(s.subset == PrintLayout.Subset.EVEN, { onChange(s.copy(subset = PrintLayout.Subset.EVEN)) }, label = { Text(stringResource(R.string.v5_even)) })
        FilterChip(s.reverse, { onChange(s.copy(reverse = !s.reverse)) }, label = { Text(stringResource(R.string.v5_reverse)) })
    }
    Text(stringResource(R.string.nbt_print_what), style = MaterialTheme.typography.labelLarge)
    Chips {
        listOf(PrintContent.DOC_AND_MARKUPS to R.string.nbt_pc_doc_markups, PrintContent.DOCUMENT to R.string.nbt_pc_doc, PrintContent.DOC_AND_STAMPS to R.string.nbt_pc_stamps,
            PrintContent.FORM_FIELDS to R.string.nbt_pc_fields, PrintContent.MARKUPS to R.string.nbt_pc_markups).forEach { (c, r) ->
            FilterChip(s.content == c, { onChange(s.copy(content = c)) }, label = { Text(stringResource(r)) })
        }
    }
    Toggle(stringResource(R.string.nbt_print_notes), s.notes) { onChange(s.copy(notes = it)) }
    Toggle(stringResource(R.string.nbt_print_summary), s.summary) { onChange(s.copy(summary = it)) }
    HorizontalDivider()
    Text(stringResource(R.string.v5_layout), style = MaterialTheme.typography.labelLarge)
    val mode = s.mode()
    Chips {
        FilterChip(mode == PrintMode.SINGLE, { onChange(s.copy(booklet = false, poster = false, perSheet = 1, cols = 0, rows = 0)) }, label = { Text(stringResource(R.string.nbt_mode_single)) })
        FilterChip(mode == PrintMode.NUP, { onChange(s.copy(booklet = false, poster = false, perSheet = if (s.perSheet > 1) s.perSheet else 2)) }, label = { Text(stringResource(R.string.v5_per_sheet)) })
        FilterChip(mode == PrintMode.BOOKLET, { onChange(s.copy(booklet = true, poster = false)) }, label = { Text(stringResource(R.string.v5_booklet)) })
        FilterChip(mode == PrintMode.POSTER, { onChange(s.copy(booklet = false, poster = true, perSheet = 1, cols = 0, rows = 0)) }, label = { Text(stringResource(R.string.nbt_mode_poster)) })
    }
    when (mode) {
        PrintMode.SINGLE -> {
            Chips {
                listOf(PrintLayout.Scale.AUTO to R.string.nbt_scale_auto, PrintLayout.Scale.FIT to R.string.nbt_scale_fit, PrintLayout.Scale.SHRINK to R.string.nbt_scale_shrink,
                    PrintLayout.Scale.ACTUAL to R.string.nbt_scale_actual, PrintLayout.Scale.CUSTOM to R.string.nbt_scale_custom).forEach { (m, r) ->
                    FilterChip(s.scale == m, { onChange(s.copy(scale = m)) }, label = { Text(stringResource(r)) })
                }
            }
            if (s.scale == PrintLayout.Scale.CUSTOM) NumberField(stringResource(R.string.nbt_scale_pct), s.scalePct.toString(), Modifier.fillMaxWidth()) { v -> v.toIntOrNull()?.let { onChange(s.copy(scalePct = it.coerceIn(5, 800))) } }
            if (s.scale != PrintLayout.Scale.AUTO) {
                Toggle(stringResource(R.string.nbt_auto_rotate), s.autoRotate) { onChange(s.copy(autoRotate = it)) }
                Toggle(stringResource(R.string.v5_borders), s.borders) { onChange(s.copy(borders = it)) }
            }
            Toggle(stringResource(R.string.nbt_cut_marks), s.cutMarks) { onChange(s.copy(cutMarks = it)) }
        }
        PrintMode.NUP -> {
            Chips {
                listOf(2, 4, 6, 9, 16).forEach { n -> FilterChip(s.perSheet == n && s.cols == 0, { onChange(s.copy(perSheet = n, cols = 0, rows = 0)) }, label = { Text(n.toString()) }) }
                FilterChip(s.cols > 0, { onChange(s.copy(cols = if (s.cols > 0) 0 else 2, rows = if (s.cols > 0) 0 else 3, perSheet = if (s.cols > 0) 2 else s.perSheet.coerceAtLeast(2))) }, label = { Text(stringResource(R.string.nbt_grid_custom)) })
            }
            if (s.cols > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.nbt_grid_cols), s.cols.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { onChange(s.copy(cols = it.coerceIn(1, 8))) } }
                NumberField(stringResource(R.string.nbt_grid_rows), s.rows.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { onChange(s.copy(rows = it.coerceIn(1, 8))) } }
            }
            Chips {
                FilterChip(!s.vertical, { onChange(s.copy(vertical = false)) }, label = { Text(stringResource(R.string.nbt_order_rows)) })
                FilterChip(s.vertical, { onChange(s.copy(vertical = true)) }, label = { Text(stringResource(R.string.nbt_order_cols)) })
                FilterChip(s.rtlOrder, { onChange(s.copy(rtlOrder = !s.rtlOrder)) }, label = { Text(stringResource(R.string.nbt_order_rtl)) })
            }
            Toggle(stringResource(R.string.v5_borders), s.borders) { onChange(s.copy(borders = it)) }
        }
        PrintMode.BOOKLET -> {
            Chips {
                FilterChip(s.rightBinding, { onChange(s.copy(rightBinding = true)) }, label = { Text(stringResource(R.string.v5_bind_right)) })
                FilterChip(!s.rightBinding, { onChange(s.copy(rightBinding = false)) }, label = { Text(stringResource(R.string.v5_bind_left)) })
            }
            Chips {
                listOf(PrintLayout.Sides.BOTH to R.string.nbt_sides_both, PrintLayout.Sides.FRONT to R.string.nbt_sides_front, PrintLayout.Sides.BACK to R.string.nbt_sides_back).forEach { (v, r) ->
                    FilterChip(s.sides == v, { onChange(s.copy(sides = v)) }, label = { Text(stringResource(r)) })
                }
            }
            OutlinedTextField(s.sheets, { onChange(s.copy(sheets = it)) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbt_sheets)) })
        }
        PrintMode.POSTER -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.nbt_poster_zoom), s.posterZoom.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { onChange(s.copy(posterZoom = it.coerceIn(10, 2000))) } }
                NumberField(stringResource(R.string.nbt_poster_overlap), s.overlap.toInt().toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { onChange(s.copy(overlap = it.coerceIn(0, 144).toFloat())) } }
            }
            Toggle(stringResource(R.string.nbt_poster_labels), s.posterLabels) { onChange(s.copy(posterLabels = it)) }
            Toggle(stringResource(R.string.nbt_cut_marks), s.cutMarks) { onChange(s.copy(cutMarks = it)) }
        }
    }
    Chips {
        PrintSettings.papers.forEach { n -> FilterChip(s.paper == n, { onChange(s.copy(paper = n)) }, label = { Text(n) }) }
        if (!s.booklet) FilterChip(s.landscape, { onChange(s.copy(landscape = !s.landscape)) }, label = { Text(stringResource(R.string.v5_landscape)) })
    }
    if (mode != PrintMode.BOOKLET && !(mode == PrintMode.SINGLE && s.scale == PrintLayout.Scale.AUTO))
        NumberField(stringResource(R.string.nbt_margin), s.margin.toInt().toString(), Modifier.fillMaxWidth()) { v -> v.toIntOrNull()?.let { onChange(s.copy(margin = it.coerceIn(0, 144).toFloat())) } }
    HorizontalDivider()
    Toggle(stringResource(R.string.nbt_grayscale), s.grayscale) { onChange(s.copy(grayscale = it)) }
    Toggle(stringResource(R.string.nbt_as_image), s.asImage) { onChange(s.copy(asImage = it)) }
    if (s.asImage) Chips { listOf(100, 150, 200, 300).forEach { d -> FilterChip(s.dpi == d, { onChange(s.copy(dpi = d)) }, label = { Text("$d dpi") }) } }
}

/** Load / save / delete named print presets. */
@Composable
private fun PrintPresetsRow(s: PrintSettings, onChange: (PrintSettings) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(PrintPresets.list(ctx)) }
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { menu = true }, enabled = list.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Tune, null); Spacer(Modifier.width(6.dp))
                Text(current ?: stringResource(if (list.isEmpty()) R.string.nbt_presets_none else R.string.nbt_presets_pick), maxLines = 1)
            }
            DropdownMenu(menu, { menu = false }) {
                list.forEach { (n, p) -> DropdownMenuItem(text = { Text(n) }, onClick = { menu = false; current = n; onChange(p) }) }
            }
        }
        IconButton(onClick = { naming = true }) { Icon(Icons.Default.Save, stringResource(R.string.nbt_presets_save)) }
        IconButton(onClick = { current?.let { PrintPresets.delete(ctx, it); list = PrintPresets.list(ctx); current = null } }, enabled = current != null) {
            Icon(Icons.Default.Delete, stringResource(R.string.nbt_presets_delete))
        }
    }
    if (naming) {
        var name by remember { mutableStateOf(current.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.nbt_presets_save)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.nbt_presets_name)) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { PrintPresets.save(ctx, name.trim(), s); list = PrintPresets.list(ctx); current = name.trim(); naming = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Print preview: the prepared file page by page with a slider. */
@Composable
fun PrintPreviewDialog(file: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    val session by produceState<PdfSession?>(null, file) { value = runCatching { PdfSession.open(ctx, file, null) }.getOrNull() }
    DisposableEffect(session) { val s = session; onDispose { if (s != null) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { s.closeAsync() } } } }
    val count = session?.pageCount ?: 0
    val bmp by produceState<android.graphics.Bitmap?>(null, session, page) {
        val s = session ?: return@produceState
        value = runCatching {
            val (w, h) = s.pageSize(page.coerceIn(0, (count - 1).coerceAtLeast(0)))
            val bw = 900; s.render(page, bw, (bw * h / w).toInt().coerceIn(1, 2400), false, true)
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nbt_preview_title, page + 1, count)) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 440.dp), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b == null) CircularProgressIndicator() else Image(b.asImageBitmap(), stringResource(R.string.nbt_preview_page, page + 1), Modifier.fillMaxWidth())
                }
                if (count > 1) Slider(page.toFloat(), { page = it.toInt() }, valueRange = 0f..(count - 1).toFloat(), steps = if (count in 3..60) count - 2 else 0)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
