package com.smartdocs.editor.ui.tools5

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val InsColor = Color(0x661E88E5)
private val DelColor = Color(0x66E53935)
private val RepColor = Color(0x66FB8C00)

/** Page picture + page width in points (to place the word boxes). */
private suspend fun renderPage(ctx: Context, f: File, page: Int, widthPx: Int): Pair<Bitmap, Float>? = withContext(Dispatchers.IO) {
    val s = PdfSession.open(ctx, f, null)
    try {
        if (page !in 0 until s.pageCount) return@withContext null
        val (w, h) = s.pageSize(page)
        val b = s.render(page, widthPx, (widthPx * h / w).toInt().coerceAtLeast(1), annotations = true).copy(Bitmap.Config.ARGB_8888, false)
        s.invalidateCache()
        b to w
    } finally { s.closeAsync() }
}

@Composable
private fun MarkedPage(file: File, page: Int, marks: SideMarks, label: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val data by produceState<Pair<Bitmap, Float>?>(null, file, page) { value = runCatching { renderPage(ctx, file, page, 1000) }.getOrNull() }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val d = data
        if (d == null) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else Box(Modifier.fillMaxWidth().aspectRatio(d.first.width.toFloat() / d.first.height)) {
            Image(d.first.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.matchParentSize()) {
                val k = size.width / d.second
                marks.boxes[page].orEmpty().forEach { (b, t) ->
                    drawRect(when (t) { ChangeType.INSERT -> InsColor; ChangeType.DELETE -> DelColor; else -> RepColor }, Offset(b.left * k, b.top * k), Size(maxOf(2f, b.width() * k), maxOf(2f, b.height() * k)))
                }
            }
        }
    }
}

@Composable
private fun OverlayPage(old: File, new: File, page: Int) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, old, new, page) {
        value = runCatching {
            val a = renderPage(ctx, old, page, 1000)?.first; val b = renderPage(ctx, new, page, 1000)?.first
            if (a != null && b != null) withContext(Dispatchers.Default) { CompareEngine.overlay(a, b) } else a ?: b
        }.getOrNull()
    }
    val d = bmp
    if (d == null) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else Image(d.asImageBitmap(), stringResource(R.string.t5_cmp_overlay), Modifier.fillMaxWidth().aspectRatio(d.width.toFloat() / d.height), contentScale = ContentScale.FillBounds)
    Text(stringResource(R.string.t5_cmp_overlay_legend), style = MaterialTheme.typography.bodySmall)
}

/** Better compare: word-level diff (Arabic-aware), side-by-side / overlay / list, PDF report, AI summary. */
@Composable
fun Compare2Screen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val snackbar = remember { SnackbarHostState() }
    var old by remember { mutableStateOf(fileOf(uri)) }
    var new by remember { mutableStateOf<File?>(null) }
    var cmp by remember { mutableStateOf<CompareResult?>(null) }
    var mode by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf<String?>(null) }
    val job = rememberT5Job()
    val pickOld = rememberPdfPicker { old = it.firstOrNull(); cmp = null }
    val pickNew = rememberPdfPicker { new = it.firstOrNull(); cmp = null }
    val comparing = stringResource(R.string.t5_cmp_working)
    val labels = CompareLabels(
        stringResource(R.string.t5_cmp_report_title), stringResource(R.string.t5_cmp_old), stringResource(R.string.t5_cmp_new),
        stringResource(R.string.t5_cmp_inserted), stringResource(R.string.t5_cmp_deleted), stringResource(R.string.t5_cmp_replaced),
        stringResource(R.string.t5_page_n), stringResource(R.string.t5_cmp_changes), stringResource(R.string.t5_cmp_summary),
    )
    val arabicUi = settings.language == "ar"

    ToolScaffold(stringResource(R.string.t5_cmp_title), nav, snackbar = snackbar) {
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
        PrimaryButton(stringResource(R.string.t5_cmp_run), enabled = old != null && new != null && !job.running, icon = Icons.Default.Compare) {
            val a = old ?: return@PrimaryButton; val b = new ?: return@PrimaryButton
            job.launch(comparing) { val c = CompareEngine.compare(a, null, b, null); cmp = c; summary = null; page = c.changedPages.firstOrNull() ?: 0 }
        }
        val r = cmp
        if (r != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(stringResource(R.string.t5_cmp_inserted) + ": " + r.stats.inserted, Color(0xFF1E88E5))
                StatusPill(stringResource(R.string.t5_cmp_deleted) + ": " + r.stats.deleted, T5Red)
                StatusPill(stringResource(R.string.t5_cmp_replaced) + ": " + r.stats.replaced, T5Orange)
                StatusPill(stringResource(R.string.t5_cmp_similarity, (r.stats.similarity() * 100).toInt()), T5Green)
            }
            if (r.stats.changes == 0) Text(stringResource(R.string.t5_cmp_same), style = MaterialTheme.typography.titleSmall, color = T5Green)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val a = old ?: return@OutlinedButton; val b = new ?: return@OutlinedButton
                    val s = summary
                    job.launch(comparing) {
                        val out = app.documents.uniqueFile(app.documents.docsDir, a.nameWithoutExtension + "_compare")
                        result = CompareReport.write(ctx, a, null, b, null, r, out, labels, s)
                    }
                }, enabled = !job.running) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_cmp_report)) }
                if (AltTextAi.available(settings)) OutlinedButton(onClick = {
                    job.launch(comparing) { summary = CompareEngine.aiSummary(ctx, settings, r, arabicUi) }
                }, enabled = !job.running && r.stats.changes > 0) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_cmp_ai)) }
            }
            summary?.let { T5Card(stringResource(R.string.t5_cmp_summary), Icons.Default.AutoAwesome) { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            TabRow(selectedTabIndex = mode) {
                Tab(mode == 0, { mode = 0 }, text = { Text(stringResource(R.string.t5_cmp_side)) })
                Tab(mode == 1, { mode = 1 }, text = { Text(stringResource(R.string.t5_cmp_overlay)) })
                Tab(mode == 2, { mode = 2 }, text = { Text(stringResource(R.string.t5_cmp_list)) })
            }
            if (mode != 2) {
                val pages = r.changedPages.ifEmpty { listOf(0) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { pages.lastOrNull { it < page }?.let { page = it } }, enabled = pages.any { it < page }) { Icon(Icons.Default.ChevronRight, stringResource(R.string.t5_prev_change)) }
                    Text(stringResource(R.string.t5_page_n, page + 1), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { pages.firstOrNull { it > page }?.let { page = it } }, enabled = pages.any { it > page }) { Icon(Icons.Default.ChevronLeft, stringResource(R.string.t5_next_change)) }
                }
            }
            when (mode) {
                0 -> if (old != null && new != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val a = old ?: return@BoxWithConstraints; val b = new ?: return@BoxWithConstraints
                    if (maxWidth > 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarkedPage(a, page, r.oldMarks, stringResource(R.string.t5_cmp_old), Modifier.weight(1f))
                        MarkedPage(b, page, r.newMarks, stringResource(R.string.t5_cmp_new), Modifier.weight(1f))
                    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarkedPage(a, page, r.oldMarks, stringResource(R.string.t5_cmp_old), Modifier.fillMaxWidth())
                        MarkedPage(b, page, r.newMarks, stringResource(R.string.t5_cmp_new), Modifier.fillMaxWidth())
                    }
                }
                1 -> { val a = old; val b = new; if (a != null && b != null) OverlayPage(a, b, page) }
                else -> ChangeList(r) { p -> page = p; mode = 0 }
            }
        }
    }
    T5JobUi(job, nav, snackbar)
}

@Composable
private fun ChangeList(r: CompareResult, onPage: (Int) -> Unit) {
    val items = remember(r) { r.changes.filter { it.type != ChangeType.EQUAL }.take(500) }
    Column {
        items.forEach { c ->
            val a = c.a.joinToString(" ") { r.oldWords[it].text }; val b = c.b.joinToString(" ") { r.newWords[it].text }
            val pg = (c.b.firstOrNull()?.let { r.newWords.getOrNull(it)?.page } ?: c.a.firstOrNull()?.let { r.oldWords.getOrNull(it)?.page }) ?: 0
            val (icon, color) = when (c.type) { ChangeType.INSERT -> Icons.Default.Add to Color(0xFF1E88E5); ChangeType.DELETE -> Icons.Default.Remove to T5Red; else -> Icons.Default.SwapHoriz to T5Orange }
            ListItem(
                modifier = Modifier.clickable { onPage(pg) },
                leadingContent = { Icon(icon, null, tint = color) },
                headlineContent = { Text(when (c.type) { ChangeType.INSERT -> b; ChangeType.DELETE -> a; else -> "$a → $b" }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(stringResource(R.string.t5_page_n, pg + 1)) },
            )
        }
    }
}
