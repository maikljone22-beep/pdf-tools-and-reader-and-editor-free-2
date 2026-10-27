package com.smartdocs.editor.ui.tools5

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.search.PasswordDialog
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.ColorChips
import com.smartdocs.editor.ui.tools.FieldLabel
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val MARK_COLORS = listOf(0x1E88E5, 0xE53935, 0xFB8C00, 0x43A047, 0x8E24AA, 0xFDD835, 0x212121)

private fun color(c: Int, alpha: Float) = Color(((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or c)

/** Page picture + page width in points (to place the word boxes). */
private suspend fun renderPage(ctx: Context, f: File, pw: String?, page: Int, widthPx: Int): Pair<Bitmap, Float>? = withContext(Dispatchers.IO) {
    val s = PdfSession.open(ctx, f, pw)
    try {
        if (page !in 0 until s.pageCount) return@withContext null
        val (w, h) = s.pageSize(page)
        val b = s.render(page, widthPx, (widthPx * h / w).toInt().coerceAtLeast(1), annotations = true).copy(Bitmap.Config.ARGB_8888, false)
        s.invalidateCache()
        b to w
    } finally { s.closeAsync() }
}

/** True when [f] needs a password ([pw] missing or wrong). */
private suspend fun locked(f: File, pw: String?): Boolean = withContext(Dispatchers.IO) {
    try { (if (pw.isNullOrEmpty()) com.tom_roush.pdfbox.pdmodel.PDDocument.load(f) else com.tom_roush.pdfbox.pdmodel.PDDocument.load(f, pw)).close(); false }
    catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { true }
    catch (e: Exception) { false }
}

@Composable
private fun MarkedPage(file: File, pw: String?, page: Int?, marks: SideMarks, kinds: Set<ChangeKind>, style: CompareStyle, label: String, modifier: Modifier) {
    val ctx = LocalContext.current
    Column(modifier) {
        Text(label + (page?.let { "  " + stringResource(R.string.t5_page_n, it + 1) } ?: ""), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (page == null) { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.nt_cmp_no_page)) }; return@Column }
        val data by produceState<Pair<Bitmap, Float>?>(null, file, page, pw) { value = runCatching { renderPage(ctx, file, pw, page, 1000) }.getOrNull() }
        val d = data
        if (d == null) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else Box(Modifier.fillMaxWidth().aspectRatio(d.first.width.toFloat() / d.first.height)) {
            Image(d.first.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.matchParentSize()) {
                val k = size.width / d.second
                marks.boxes[page].orEmpty().filter { it.kind in kinds }.forEach { m ->
                    val b = m.box
                    val c = style.colorOf(m.type)
                    val tl = Offset(b.left * k, b.top * k); val sz = Size(maxOf(2f, b.width() * k), maxOf(2f, b.height() * k))
                    if (style.line > 0f) drawRect(color(c, maxOf(style.opacity, 0.6f)), tl, sz, style = Stroke(style.line * k))
                    else drawRect(color(c, style.opacity), tl, sz)
                }
            }
        }
    }
}

@Composable
private fun OverlayPage(old: File, oldPw: String?, new: File, newPw: String?, pair: PagePair, style: CompareStyle) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, old, new, pair, style) {
        value = runCatching {
            val a = pair.old?.let { renderPage(ctx, old, oldPw, it, 1000)?.first }; val b = pair.new?.let { renderPage(ctx, new, newPw, it, 1000)?.first }
            if (a != null && b != null) withContext(Dispatchers.Default) { CompareOutput.overlay(a, b, style) } else a ?: b
        }.getOrNull()
    }
    val d = bmp
    if (d == null) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else Image(d.asImageBitmap(), stringResource(R.string.t5_cmp_overlay), Modifier.fillMaxWidth().aspectRatio(d.width.toFloat() / d.height), contentScale = ContentScale.FillBounds)
    Text(stringResource(R.string.t5_cmp_overlay_legend), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun kindLabel(k: ChangeKind) = stringResource(when (k) {
    ChangeKind.TEXT -> R.string.nt_cmp_k_text; ChangeKind.IMAGE -> R.string.nt_cmp_k_images; ChangeKind.ANNOTATION -> R.string.nt_cmp_k_annots
    ChangeKind.FORMAT -> R.string.nt_cmp_k_format; ChangeKind.PAGE -> R.string.nt_cmp_k_pages; ChangeKind.TABLE -> R.string.nt_cmp_k_tables
})

/**
 * Compare: word-level diff (Arabic-aware) plus Nitro / Foxit options — page ranges, matched (reordered / inserted /
 * deleted) pages, image, annotation, formatting, table and page (rotation / size) changes, a filter by change kind,
 * marker colours / opacity / thickness with "set as default", side-by-side / overlay / list / table views, PDF
 * report, visual-compare document, annotated copy (changes as comments), AI summary, word count, protected files.
 */
@Composable
fun Compare2Screen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val snackbar = remember { SnackbarHostState() }
    var old by remember { mutableStateOf(fileOf(uri)) }
    var new by remember { mutableStateOf<File?>(null) }
    var oldPw by remember { mutableStateOf<String?>(null) }
    var newPw by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf(0) }   // 1 = old, 2 = new
    var cmp by remember { mutableStateOf<CompareResult?>(null) }
    var opts by remember { mutableStateOf(CompareOptions()) }
    var style by remember { mutableStateOf(CompareStyle.saved(ctx)) }
    var kinds by remember { mutableStateOf(ChangeKind.values().toSet()) }
    var mode by remember { mutableIntStateOf(0) }
    var at by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf<String?>(null) }
    var showStyle by remember { mutableStateOf(false) }
    var showCount by remember { mutableStateOf(false) }
    val job = rememberT5Job()
    val pickOld = rememberPdfPicker { old = it.firstOrNull(); oldPw = null; cmp = null }
    val pickNew = rememberPdfPicker { new = it.firstOrNull(); newPw = null; cmp = null }
    LaunchedEffect(old, oldPw) { old?.let { if (locked(it, oldPw)) ask = 1 } }
    LaunchedEffect(new, newPw) { new?.let { if (locked(it, newPw)) ask = 2 } }
    if (ask != 0) PasswordDialog(onDismiss = { if (ask == 1) old = null else new = null; ask = 0 }) { pw -> if (ask == 1) oldPw = pw else newPw = pw; ask = 0 }
    val comparing = stringResource(R.string.t5_cmp_working)
    val labels = CompareLabels(
        stringResource(R.string.t5_cmp_report_title), stringResource(R.string.t5_cmp_old), stringResource(R.string.t5_cmp_new),
        stringResource(R.string.t5_cmp_inserted), stringResource(R.string.t5_cmp_deleted), stringResource(R.string.t5_cmp_replaced),
        stringResource(R.string.t5_page_n), stringResource(R.string.t5_cmp_changes), stringResource(R.string.t5_cmp_summary),
        kindLabel(ChangeKind.TEXT), kindLabel(ChangeKind.IMAGE), kindLabel(ChangeKind.ANNOTATION), kindLabel(ChangeKind.FORMAT),
        kindLabel(ChangeKind.PAGE), kindLabel(ChangeKind.TABLE),
        stringResource(R.string.nt_cmp_page_inserted), stringResource(R.string.nt_cmp_page_deleted), stringResource(R.string.nt_cmp_page_moved),
        stringResource(R.string.nt_cmp_page_rotated), stringResource(R.string.nt_cmp_page_cropped),
        stringResource(R.string.nt_cmp_old_only), stringResource(R.string.nt_cmp_new_only),
    )
    val arabicUi = settings.language == "ar"

    ToolScaffold(stringResource(R.string.t5_cmp_title), nav, snackbar = snackbar, actions = {
        IconButton(onClick = { showCount = true }, enabled = old != null || new != null) { Icon(Icons.Default.Numbers, stringResource(R.string.nt_wc_title)) }
        IconButton(onClick = { showStyle = true }) { Icon(Icons.Default.Palette, stringResource(R.string.nt_cmp_style)) }
    }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 700.dp
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { FilePickRow(old, stringResource(R.string.t5_cmp_old), pickOld) }
                Box(Modifier.weight(1f)) { FilePickRow(new, stringResource(R.string.t5_cmp_new), pickNew) }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilePickRow(old, stringResource(R.string.t5_cmp_old), pickOld)
                FilePickRow(new, stringResource(R.string.t5_cmp_new), pickNew)
            }
        }
        T5Card(stringResource(R.string.t5_options), Icons.Default.Tune) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(opts.oldRange, { opts = opts.copy(oldRange = it) }, Modifier.weight(1f), singleLine = true,
                    label = { Text(stringResource(R.string.nt_cmp_old_pages)) }, placeholder = { Text(stringResource(R.string.e5_all_pages)) })
                OutlinedTextField(opts.newRange, { opts = opts.copy(newRange = it) }, Modifier.weight(1f), singleLine = true,
                    label = { Text(stringResource(R.string.nt_cmp_new_pages)) }, placeholder = { Text(stringResource(R.string.e5_all_pages)) })
            }
            SwitchRow(stringResource(R.string.nt_cmp_auto_match), opts.autoMatch) { opts = opts.copy(autoMatch = it) }
            SwitchRow(stringResource(R.string.nt_cmp_o_text), opts.text) { opts = opts.copy(text = it) }
            SwitchRow(stringResource(R.string.nt_cmp_o_images), opts.images) { opts = opts.copy(images = it) }
            SwitchRow(stringResource(R.string.nt_cmp_o_annots), opts.annotations) { opts = opts.copy(annotations = it) }
            SwitchRow(stringResource(R.string.nt_cmp_o_format), opts.formatting) { opts = opts.copy(formatting = it) }
            SwitchRow(stringResource(R.string.nt_cmp_o_pages), opts.pages) { opts = opts.copy(pages = it) }
            SwitchRow(stringResource(R.string.nt_cmp_o_tables), opts.tables) { opts = opts.copy(tables = it) }
        }
        PrimaryButton(stringResource(R.string.t5_cmp_run), enabled = old != null && new != null && !job.running && ask == 0, icon = Icons.Default.Compare) {
            val a = old ?: return@PrimaryButton; val b = new ?: return@PrimaryButton
            val o = opts; val pa = oldPw; val pb = newPw
            job.launch(comparing) { val c = CompareEngine.compare(a, pa, b, pb, o); cmp = c; summary = null; at = 0 }
        }
        val r = cmp
        if (r != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(stringResource(R.string.t5_cmp_inserted) + ": " + r.stats.inserted, color(style.insert, 1f))
                StatusPill(stringResource(R.string.t5_cmp_deleted) + ": " + r.stats.deleted, color(style.delete, 1f))
                StatusPill(stringResource(R.string.t5_cmp_replaced) + ": " + r.stats.replaced, color(style.replace, 1f))
                StatusPill(stringResource(R.string.t5_cmp_similarity, (r.stats.similarity() * 100).toInt()), T5Green)
                if (r.pageChanges.isNotEmpty()) StatusPill(kindLabel(ChangeKind.PAGE) + ": " + r.pageChanges.size, T5Orange)
                r.objectChanges.groupBy { it.kind }.forEach { (k, l) -> StatusPill(kindLabel(k) + ": " + l.size, T5Orange) }
                if (r.formatChanges.isNotEmpty()) StatusPill(kindLabel(ChangeKind.FORMAT) + ": " + r.formatChanges.size, T5Orange)
                if (r.tableChanges.isNotEmpty()) StatusPill(kindLabel(ChangeKind.TABLE) + ": " + r.tableChanges.size, T5Orange)
            }
            val nothing = r.stats.changes == 0 && r.pageChanges.isEmpty() && r.objectChanges.isEmpty() && r.formatChanges.isEmpty() && r.tableChanges.isEmpty()
            if (nothing) Text(stringResource(R.string.t5_cmp_same), style = MaterialTheme.typography.titleSmall, color = T5Green)
            FieldLabel(stringResource(R.string.nt_cmp_filter))
            val all = ChangeKind.values().toSet()
            ChoiceChips(listOf<ChangeKind?>(null) + ChangeKind.values().toList(), if (kinds == all) null else kinds.singleOrNull(),
                { k -> if (k == null) stringResource(R.string.nt_cmp_k_all) else kindLabel(k) }) { k -> kinds = if (k == null) all else setOf(k); at = 0 }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val a = old ?: return@OutlinedButton; val b = new ?: return@OutlinedButton
                    val s = summary; val st = style; val ks = kinds; val pa = oldPw; val pb = newPw
                    job.launch(comparing) {
                        val out = app.documents.uniqueFile(app.documents.docsDir, a.nameWithoutExtension + "_compare")
                        result = CompareReport.write(ctx, a, pa, b, pb, r, out, labels, s, style = st, kinds = ks)
                    }
                }, enabled = !job.running) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_cmp_report)) }
                OutlinedButton(onClick = {
                    val a = old ?: return@OutlinedButton; val b = new ?: return@OutlinedButton
                    val st = style; val pa = oldPw; val pb = newPw
                    job.launch(comparing) {
                        val out = app.documents.uniqueFile(app.documents.docsDir, a.nameWithoutExtension + "_visual_compare")
                        result = CompareOutput.visualDocument(ctx, a, pa, b, pb, r.pairs.ifEmpty { PageMatch.inOrder((0 until r.oldPages).toList(), (0 until r.newPages).toList()) }, st, labels, out)
                    }
                }, enabled = !job.running) { Icon(Icons.Default.Layers, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nt_cmp_visual_doc)) }
                OutlinedButton(onClick = {
                    val b = new ?: return@OutlinedButton
                    val st = style; val ks = kinds; val pb = newPw
                    job.launch(comparing) {
                        val out = app.documents.uniqueFile(app.documents.docsDir, b.nameWithoutExtension + "_changes")
                        result = CompareOutput.annotatedCopy(b, pb, r, st, ks, labels, out, settings.authorName.ifBlank { "Compare" })
                    }
                }, enabled = !job.running) { Icon(Icons.Default.Comment, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nt_cmp_annotated)) }
                if (AltTextAi.available(settings)) OutlinedButton(onClick = {
                    job.launch(comparing) { summary = CompareEngine.aiSummary(ctx, settings, r, arabicUi) }
                }, enabled = !job.running && r.stats.changes > 0) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_cmp_ai)) }
            }
            summary?.let { T5Card(stringResource(R.string.t5_cmp_summary), Icons.Default.AutoAwesome) { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            TabRow(selectedTabIndex = mode) {
                Tab(mode == 0, { mode = 0 }, text = { Text(stringResource(R.string.t5_cmp_side)) })
                Tab(mode == 1, { mode = 1 }, text = { Text(stringResource(R.string.t5_cmp_overlay)) })
                Tab(mode == 2, { mode = 2 }, text = { Text(stringResource(R.string.t5_cmp_list)) })
                if (r.tableChanges.isNotEmpty()) Tab(mode == 3, { mode = 3 }, text = { Text(kindLabel(ChangeKind.TABLE)) })
            }
            val pairs = r.changedPairs(kinds).ifEmpty { r.pairs.take(1) }.ifEmpty { listOf(PagePair(0, 0)) }
            val pair = pairs[at.coerceIn(0, pairs.size - 1)]
            if (mode < 2) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { at = (at - 1).coerceAtLeast(0) }, enabled = at > 0) { Icon(Icons.Default.ChevronRight, stringResource(R.string.t5_prev_change)) }
                Text(stringResource(R.string.nt_cmp_pair, at + 1, pairs.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { at = (at + 1).coerceAtMost(pairs.size - 1) }, enabled = at < pairs.size - 1) { Icon(Icons.Default.ChevronLeft, stringResource(R.string.t5_next_change)) }
            }
            when (mode) {
                0 -> { val a = old; val b = new; if (a != null && b != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth > 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarkedPage(a, oldPw, pair.old, r.oldMarks, kinds, style, stringResource(R.string.t5_cmp_old), Modifier.weight(1f))
                        MarkedPage(b, newPw, pair.new, r.newMarks, kinds, style, stringResource(R.string.t5_cmp_new), Modifier.weight(1f))
                    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarkedPage(a, oldPw, pair.old, r.oldMarks, kinds, style, stringResource(R.string.t5_cmp_old), Modifier.fillMaxWidth())
                        MarkedPage(b, newPw, pair.new, r.newMarks, kinds, style, stringResource(R.string.t5_cmp_new), Modifier.fillMaxWidth())
                    }
                } }
                1 -> { val a = old; val b = new; if (a != null && b != null) OverlayPage(a, oldPw, b, newPw, pair, style) }
                2 -> ChangeList(r, kinds, style, labels) { p -> pairs.indexOfFirst { it.new == p || it.old == p }.takeIf { it >= 0 }?.let { at = it }; mode = 0 }
                else -> TableChanges(r, style)
            }
        }
    }
    if (showStyle) StyleDialog(style, onDismiss = { showStyle = false }, onDefault = { CompareStyle.saveDefault(ctx, it) }) { style = it; showStyle = false }
    if (showCount) WordCountDialog(listOfNotNull(old?.let { Triple(it, oldPw, stringResource(R.string.t5_cmp_old)) }, new?.let { Triple(it, newPw, stringResource(R.string.t5_cmp_new)) })) { showCount = false }
    T5JobUi(job, nav, snackbar)
}

@Composable
private fun ChangeList(r: CompareResult, kinds: Set<ChangeKind>, style: CompareStyle, labels: CompareLabels, onPage: (Int) -> Unit) {
    val text = remember(r) { r.changes.filter { it.type != ChangeType.EQUAL }.take(500) }
    Column {
        if (ChangeKind.PAGE in kinds) r.pageChanges.forEach { pc ->
            ListItem(modifier = Modifier.clickable { (pc.new ?: pc.old)?.let(onPage) },
                leadingContent = { Icon(Icons.Default.Description, null, tint = color(style.replace, 1f)) },
                headlineContent = { Text(labels.pageChange(pc)) }, supportingContent = { if (pc.detail.isNotBlank()) Text(pc.detail) })
        }
        r.objectChanges.filter { it.kind in kinds }.forEach { c ->
            ListItem(modifier = Modifier.clickable { (c.newPage ?: c.oldPage)?.let(onPage) },
                leadingContent = { Icon(if (c.kind == ChangeKind.IMAGE) Icons.Default.Image else Icons.Default.Comment, null, tint = color(style.colorOf(c.type), 1f)) },
                headlineContent = { Text(c.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(kindLabel(c.kind) + " · " + stringResource(R.string.t5_page_n, (c.newPage ?: c.oldPage ?: 0) + 1)) })
        }
        if (ChangeKind.FORMAT in kinds) r.formatChanges.take(200).forEach { f ->
            val pg = r.newWords.getOrNull(f.newWord)?.page ?: 0
            ListItem(modifier = Modifier.clickable { onPage(pg) },
                leadingContent = { Icon(Icons.Default.FormatSize, null, tint = color(style.replace, 1f)) },
                headlineContent = { Text(f.text, maxLines = 1) },
                supportingContent = { Text("${f.oldFont} ${f.oldSize} → ${f.newFont} ${f.newSize} · " + stringResource(R.string.t5_page_n, pg + 1)) })
        }
        if (ChangeKind.TEXT in kinds) text.forEach { c ->
            val a = c.a.joinToString(" ") { r.oldWords[it].text }; val b = c.b.joinToString(" ") { r.newWords[it].text }
            val pg = (c.b.firstOrNull()?.let { r.newWords.getOrNull(it)?.page } ?: c.a.firstOrNull()?.let { r.oldWords.getOrNull(it)?.page }) ?: 0
            val icon = when (c.type) { ChangeType.INSERT -> Icons.Default.Add; ChangeType.DELETE -> Icons.Default.Remove; else -> Icons.Default.SwapHoriz }
            ListItem(
                modifier = Modifier.clickable { onPage(pg) },
                leadingContent = { Icon(icon, null, tint = color(style.colorOf(c.type), 1f)) },
                headlineContent = { Text(when (c.type) { ChangeType.INSERT -> b; ChangeType.DELETE -> a; else -> "$a → $b" }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(stringResource(R.string.t5_page_n, pg + 1)) },
            )
        }
    }
}

/** Foxit "Compare Table": changed rows and cells. */
@Composable
private fun TableChanges(r: CompareResult, style: CompareStyle) {
    Column {
        r.tableChanges.take(500).forEach { t ->
            val where = stringResource(R.string.t5_page_n, (t.newPage ?: t.oldPage ?: 0) + 1) + " · " +
                if (t.col < 0) stringResource(R.string.nt_cmp_row, t.row + 1) else stringResource(R.string.nt_cmp_cell, t.row + 1, t.col + 1)
            ListItem(leadingContent = { Icon(Icons.Default.TableChart, null, tint = color(style.colorOf(t.type), 1f)) },
                headlineContent = { Text(when (t.type) { ChangeType.INSERT -> t.new; ChangeType.DELETE -> t.old; else -> "${t.old} → ${t.new}" }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(where) })
        }
    }
}

/** Marker colours, opacity and line thickness (0 = filled), with "set as default". */
@Composable
private fun StyleDialog(initial: CompareStyle, onDismiss: () -> Unit, onDefault: (CompareStyle) -> Unit, onApply: (CompareStyle) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.nt_cmp_style)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.t5_cmp_inserted)); ColorChips(MARK_COLORS, s.insert) { s = s.copy(insert = it) }
                Text(stringResource(R.string.t5_cmp_deleted)); ColorChips(MARK_COLORS, s.delete) { s = s.copy(delete = it) }
                Text(stringResource(R.string.t5_cmp_replaced)); ColorChips(MARK_COLORS, s.replace) { s = s.copy(replace = it) }
                Text(stringResource(R.string.e5_opacity, (s.opacity * 100).toInt()))
                Slider(s.opacity, { s = s.copy(opacity = it) }, valueRange = 0.1f..1f)
                Text(if (s.line <= 0f) stringResource(R.string.nt_cmp_filled) else stringResource(R.string.nt_cmp_line, s.line))
                Slider(s.line, { s = s.copy(line = if (it < 0.25f) 0f else (it * 2).toInt() / 2f) }, valueRange = 0f..4f)
                TextButton(onClick = { onDefault(s) }) { Text(stringResource(R.string.nt_cmp_set_default)) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(s) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Foxit "Word Count" for each picked document, over all pages or a page range. */
@Composable
fun WordCountDialog(files: List<Triple<File, String?, String>>, onDismiss: () -> Unit) {
    var range by remember { mutableStateOf("") }
    var counts by remember { mutableStateOf<List<Pair<String, WordCountResult?>>>(emptyList()) }
    LaunchedEffect(files, range) {
        counts = files.map { it.third to null }
        counts = files.map { (f, pw, label) -> label to runCatching { WordCount.ofPdf(f, pw, range) }.getOrNull() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.nt_wc_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) },
                    placeholder = { Text(stringResource(R.string.e5_all_pages)) })
                counts.forEach { (label, c) ->
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    if (c == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else WordCountTable(c)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
}

@Composable
fun WordCountTable(c: WordCountResult) {
    val rows = listOfNotNull(
        if (c.pages > 0) R.string.nt_wc_pages to c.pages else null,
        R.string.nt_wc_words to c.words, R.string.nt_wc_chars to c.chars, R.string.nt_wc_chars_nospace to c.charsNoSpaces,
        R.string.nt_wc_lines to c.lines, R.string.nt_wc_asian to c.asian,
    )
    Column {
        rows.forEach { (l, v) -> Row { Text(stringResource(l), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text("%,d".format(v), style = MaterialTheme.typography.bodyMedium) } }
    }
}
