package com.smartdocs.editor.ui.view5

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.annot.CommentStore
import com.smartdocs.editor.core.view5.CFilter
import com.smartdocs.editor.core.view5.CRow
import com.smartdocs.editor.core.view5.CSort
import com.smartdocs.editor.core.view5.CommentFilter
import com.smartdocs.editor.core.view5.CommentOps5
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.annot.kindSubtype
import com.smartdocs.editor.ui.reader.annot.subtypeLabelRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Loads stored + pending comments as [CRow]s with the text under every text markup. */
object CommentRows {
    private val markup = setOf("Highlight", "Underline", "StrikeOut", "Squiggly")

    private fun textUnder(text: String, boxes: List<android.graphics.RectF>, quads: List<FloatArray>): String {
        if (quads.isEmpty()) return ""
        val sb = StringBuilder()
        val n = minOf(text.length, boxes.size)
        for (i in 0 until n) {
            val b = boxes[i]
            val cx = (b.left + b.right) / 2f; val cy = (b.top + b.bottom) / 2f
            val inside = quads.any { q -> cx >= q[0] && cx <= q[2] && cy >= q[1] && cy <= q[3] }
            if (inside) sb.append(text[i]) else if (sb.isNotEmpty() && sb.last() != ' ' && (text[i] == '\n' || text[i] == '\r')) sb.append(' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    suspend fun load(s: ReaderState): List<CRow> {
        val f = s.file ?: return emptyList()
        val stored = runCatching { CommentStore.load(f, s.password) }.getOrDefault(emptyList())
        val extras = runCatching { CommentOps5.extras(f, s.password) }.getOrDefault(emptyMap())
        val rows = ArrayList<CRow>()
        stored.forEach { c ->
            val ex = extras[c.page to c.index]
            val marked = if (c.subtype in markup && ex != null) textUnder(s.pageText(c.page), s.charBoxes(c.page), ex.quads) else ""
            rows += CRow(c.page, c.index, c.subtype, c.author, c.contents, c.date, c.status.state, c.replies.map { (if (it.author.isNotBlank()) it.author + ": " else "") + it.text },
                c.color, ex?.checked ?: false, marked, created = c.created)
        }
        s.annots.forEach { a ->
            val sub = kindSubtype(a.kind)
            val marked = if (sub in markup) a.text else ""
            rows += CRow(a.page, -1, sub, a.author, if (sub in markup) "" else a.text, a.created, "None", emptyList(), a.color, false, marked, pending = true)
        }
        return rows
    }

    fun typeLabel(ctx: Context, sub: String): String = subtypeLabelRes(sub)?.let { ctx.getString(it) } ?: sub
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentManagerSheet(v: V5State, onMessage: (String) -> Unit) {
    val s = v.s
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var styling by remember { mutableStateOf<CRow?>(null) }
    val rows by produceState<List<CRow>?>(null, s.renderVersion, s.annots.size, reload) { value = CommentRows.load(s) }
    var filter by remember { mutableStateOf(CFilter()) }
    var sort by remember { mutableStateOf(CSort.PAGE) }
    val all = rows ?: emptyList()
    val shown = remember(all, filter, sort) { CommentFilter.apply(all, filter, sort) }
    val app = ctx.applicationContext as SmartDocsApp
    val base = s.file?.nameWithoutExtension ?: "document"

    fun share(file: File, mime: String) { runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(file), mime), null)) } }
    fun pageLabel(p: Int) = ctx.getString(R.string.v5_page_n, p + 1)
    fun header() = listOf(R.string.v5_col_page, R.string.v5_col_type, R.string.v5_col_author, R.string.v5_col_date, R.string.v5_col_status, R.string.v5_col_checked,
        R.string.v5_col_contents, R.string.v5_col_marked, R.string.v5_col_replies).map { ctx.getString(it) }

    ModalBottomSheet(onDismissRequest = { v.close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.v5_comment_manager), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("${shown.size}/${all.size}", style = MaterialTheme.typography.labelLarge)
                var exportOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { exportOpen = true }) { Icon(Icons.Default.IosShare, stringResource(R.string.v5_export)) }
                    DropdownMenu(exportOpen, { exportOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.v5_export_csv)) }, leadingIcon = { Icon(Icons.Default.TableChart, null) }, onClick = {
                            exportOpen = false
                            scope.launch { runCatching { withContext(Dispatchers.IO) { app.documents.newTemp(base + "_comments", "csv").also { it.writeText(CommentFilter.csv(shown, header())) } } }.onSuccess { share(it, "text/csv") }.onFailure { onMessage(it.message ?: ctx.getString(R.string.error_generic)) } }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.v5_export_word)) }, leadingIcon = { Icon(Icons.Default.Description, null) }, onClick = {
                            exportOpen = false
                            scope.launch {
                                val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                val table = shown.map { r -> listOf((r.page + 1).toString(), CommentRows.typeLabel(ctx, r.type), r.author, if (r.date > 0) df.format(Date(r.date)) else "", r.status,
                                    if (r.checked) "✓" else "", r.contents, r.markedText, r.replies.joinToString("\n")) }
                                runCatching { withContext(Dispatchers.IO) { app.documents.newTemp(base + "_comments", "docx").also { out -> CommentOps5.writeDocx(table, header(), ctx.getString(R.string.v5_comments_of, s.file?.name ?: ""), out) } } }
                                    .onSuccess { out -> share(out, "application/vnd.openxmlformats-officedocument.wordprocessingml.document") }.onFailure { onMessage(it.message ?: "") }
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.v5_export_summary)) }, leadingIcon = { Icon(Icons.Default.Summarize, null) }, onClick = {
                            exportOpen = false
                            scope.launch {
                                val txt = CommentFilter.summary(shown, ctx.getString(R.string.v5_comments_of, s.file?.name ?: ""), ::pageLabel) { CommentRows.typeLabel(ctx, it) }
                                runCatching { withContext(Dispatchers.IO) { app.documents.newTemp(base + "_summary", "txt").also { it.writeText(txt) } } }
                                    .onSuccess { share(it, "text/plain") }.onFailure { onMessage(it.message ?: ctx.getString(R.string.error_generic)) }
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.v5_export_summary_pdf)) }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) }, onClick = {
                            exportOpen = false; v.close(); s.annot2.launchSummary()
                        })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.v5_export_highlights)) }, leadingIcon = { Icon(Icons.Default.Highlight, null) }, onClick = {
                            exportOpen = false
                            scope.launch {
                                val txt = CommentFilter.highlightedText(shown, ::pageLabel)
                                if (txt.isBlank()) { onMessage(ctx.getString(R.string.v5_no_highlights)); return@launch }
                                runCatching { withContext(Dispatchers.IO) { app.documents.newTemp(base + "_highlights", "txt").also { it.writeText(txt) } } }
                                    .onSuccess { share(it, "text/plain") }.onFailure { onMessage(it.message ?: ctx.getString(R.string.error_generic)) }
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.v5_copy_highlights)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                            exportOpen = false
                            val txt = CommentFilter.highlightedText(shown, ::pageLabel)
                            if (txt.isBlank()) onMessage(ctx.getString(R.string.v5_no_highlights))
                            else { com.smartdocs.editor.ui.reader.ReaderIntents.copy(ctx, txt); onMessage(ctx.getString(R.string.copy_text)) }
                        })
                    }
                }
            }
            OutlinedTextField(filter.query, { filter = filter.copy(query = it) }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(stringResource(R.string.v5_search_comments)) },
                trailingIcon = { if (filter.query.isNotEmpty()) IconButton(onClick = { filter = filter.copy(query = "") }) { Icon(Icons.Default.Clear, null) } })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MultiFilter(stringResource(R.string.v5_col_author), CommentFilter.authors(all), filter.authors, { it }) { filter = filter.copy(authors = it) }
                MultiFilter(stringResource(R.string.v5_col_type), CommentFilter.types(all), filter.types, { CommentRows.typeLabel(ctx, it) }) { filter = filter.copy(types = it) }
                MultiFilter(stringResource(R.string.v5_col_status), CommentFilter.statuses(all), filter.statuses, { statusLabel(ctx, it) }) { filter = filter.copy(statuses = it) }
                MultiFilter(stringResource(R.string.v5_col_page), CommentFilter.pages(all), filter.pages, { (it + 1).toString() }) { filter = filter.copy(pages = it) }
                // filter by colour (Foxit 24.76)
                val allColors = remember(all) { all.mapNotNull { it.color }.distinct() }
                if (allColors.size > 1) allColors.forEach { c ->
                    com.smartdocs.editor.ui.reader.ColorDot(c, c in filter.colors, 28.dp) { filter = filter.copy(colors = if (c in filter.colors) filter.colors - c else filter.colors + c) }
                }
                FilterChip(filter.checked == true, { filter = filter.copy(checked = if (filter.checked == true) null else true) }, label = { Text(stringResource(R.string.v5_checked)) })
                FilterChip(filter.checked == false, { filter = filter.copy(checked = if (filter.checked == false) null else false) }, label = { Text(stringResource(R.string.v5_unchecked)) })
                SortMenu(sort) { sort = it }
                if (filter.active) AssistChip(onClick = { filter = CFilter() }, label = { Text(stringResource(R.string.v5_clear_filters)) }, leadingIcon = { Icon(Icons.Default.FilterAltOff, null, Modifier.size(16.dp)) })
            }
            if (rows == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (shown.isEmpty()) Text(stringResource(R.string.v5_no_comments), Modifier.padding(24.dp))
            else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                // list position in the key: pending annotations all have index -1 and may share page/date/type (duplicate keys crash)
                itemsIndexed(shown, key = { i, it -> "$i:${it.page}:${it.index}:${it.date}:${it.pending}:${it.type}" }) { _, r ->
                    val check: (() -> Unit)? = if (r.pending || r.index < 0) null else ({
                        scope.launch {
                            runCatching { s.applyInPlace(label = { s.context.getString(R.string.hi_comment_check) }) { src, out, pw -> CommentOps5.setChecked(src, out, pw, r.page, r.index, !r.checked, s.authorName) } }
                                .onFailure { onMessage(it.message ?: "") }
                            reload++
                        }
                        Unit
                    })
                    val style: (() -> Unit)? = if (!r.pending && r.index >= 0 && r.type in styleable) ({ styling = r }) else null
                    CommentRowCard(r, onJump = { s.jumpTo(r.page); v.close() }, onCheck = check, onStyle = style)
                }
            }
        }
    }
    styling?.let { r ->
        RestyleDialog(r, onApply = { w, d, st, en, c ->
            styling = null
            scope.launch {
                runCatching {
                    s.applyInPlace(label = { s.context.getString(R.string.hi_comment_style) }) { src, out, pw ->
                        withContext(Dispatchers.IO) {
                            com.smartdocs.editor.core.pdf.PdfOps.load(src, pw).use { doc ->
                                if (!com.smartdocs.editor.core.view5.CommentWriter5.restyle(doc, r.page, r.index, w, d, st, en, c)) error(ctx.getString(R.string.error_generic))
                                doc.save(out)
                            }
                        }
                    }
                }.onFailure { onMessage(it.message ?: "") }
                reload++
            }
        }, onDismiss = { styling = null })
    }
}

private val styleable = setOf("Line", "PolyLine", "Polygon", "Square", "Circle", "Ink")

/** Dash, line endings, width and colour of a stored line / shape, rebuilt by PdfBox (CommentWriter5.restyle). */
@Composable
fun RestyleDialog(r: CRow, onApply: (Float, com.smartdocs.editor.core.view5.DashStyle, com.smartdocs.editor.core.view5.LineEnd?, com.smartdocs.editor.core.view5.LineEnd?, Int?) -> Unit, onDismiss: () -> Unit) {
    var dash by remember { mutableStateOf(com.smartdocs.editor.core.view5.DashStyle.SOLID) }
    var start by remember { mutableStateOf(com.smartdocs.editor.core.view5.LineEnd.NONE) }
    var end by remember { mutableStateOf(com.smartdocs.editor.core.view5.LineEnd.NONE) }
    var width by remember { mutableFloatStateOf(2f) }
    var color by remember { mutableStateOf(r.color) }
    val lines = r.type == "Line" || r.type == "PolyLine"
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.v5_line_style)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DashPicker(dash) { dash = it }
                if (lines) {
                    Text(stringResource(R.string.v5_line_start), style = MaterialTheme.typography.labelMedium); LineEndPicker(start) { start = it }
                    Text(stringResource(R.string.v5_line_end), style = MaterialTheme.typography.labelMedium); LineEndPicker(end) { end = it }
                }
                Text(stringResource(R.string.v5_width, "%.1f".format(Locale.US, width)), style = MaterialTheme.typography.labelMedium)
                Slider(width, { width = it }, valueRange = 0.5f..12f)
                Palette(color, allowNone = false) { color = it }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(width, dash, if (lines) start else null, if (lines) end else null, color) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

fun statusLabel(ctx: Context, st: String): String = when (st) {
    "Accepted" -> ctx.getString(R.string.an_status_accepted); "Rejected" -> ctx.getString(R.string.an_status_rejected)
    "Cancelled" -> ctx.getString(R.string.an_status_cancelled); "Completed" -> ctx.getString(R.string.an_status_completed)
    else -> ctx.getString(R.string.an_status_none)
}

@Composable
private fun <T> MultiFilter(label: String, values: List<T>, selected: Set<T>, name: (T) -> String, onChange: (Set<T>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected.isNotEmpty(), { open = true }, label = { Text(if (selected.isEmpty()) label else "$label (${selected.size})") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) })
        DropdownMenu(open, { open = false }) {
            if (values.isEmpty()) DropdownMenuItem(text = { Text("—") }, onClick = { open = false })
            values.forEach { x ->
                DropdownMenuItem(text = { Text(name(x), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Checkbox(x in selected, null) },
                    onClick = { onChange(if (x in selected) selected - x else selected + x) })
            }
        }
    }
}

@Composable
private fun SortMenu(sort: CSort, onSort: (CSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(CSort.PAGE to R.string.v5_sort_page, CSort.DATE_NEW to R.string.v5_sort_new, CSort.DATE_OLD to R.string.v5_sort_old,
        CSort.AUTHOR to R.string.v5_col_author, CSort.TYPE to R.string.v5_col_type, CSort.STATUS to R.string.v5_col_status,
        CSort.MODIFIED to R.string.cx_sum_sort_mod, CSort.COLOR to R.string.cx_sort_color)
    Box {
        AssistChip(onClick = { open = true }, label = { Text(stringResource(labels.getValue(sort))) }, leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(16.dp)) })
        DropdownMenu(open, { open = false }) {
            CSort.entries.forEach { c -> DropdownMenuItem(text = { Text(stringResource(labels.getValue(c))) }, onClick = { onSort(c); open = false }) }
        }
    }
}

@Composable
private fun CommentRowCard(r: CRow, onJump: () -> Unit, onCheck: (() -> Unit)?, onStyle: (() -> Unit)?) {
    val ctx = LocalContext.current
    val df = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onJump)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.color?.let { c -> Surface(Modifier.size(10.dp), shape = androidx.compose.foundation.shape.CircleShape, color = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or c)) {} }
                    Text(CommentRows.typeLabel(ctx, r.type), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.v5_page_n, r.page + 1), style = MaterialTheme.typography.labelSmall)
                    if (r.pending) Text(stringResource(R.string.v5_pending), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    if (r.status != "None") Text(statusLabel(ctx, r.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(listOf(r.author, if (r.date > 0) df.format(Date(r.date)) else "").filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.markedText.isNotBlank()) Text("«" + r.markedText + "»", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (r.contents.isNotBlank()) Text(r.contents, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (r.replies.isNotEmpty()) Text(stringResource(R.string.v5_replies_n, r.replies.size), style = MaterialTheme.typography.labelSmall)
            }
            if (onStyle != null) IconButton(onClick = onStyle) { Icon(Icons.Default.LineStyle, stringResource(R.string.v5_line_style)) }
            if (onCheck != null) IconButton(onClick = onCheck) {
                Icon(if (r.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, stringResource(R.string.v5_checked))
            }
        }
    }
}
