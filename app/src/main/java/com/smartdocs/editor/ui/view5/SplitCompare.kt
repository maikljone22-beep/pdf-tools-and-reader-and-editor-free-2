package com.smartdocs.editor.ui.view5

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.flow.collectLatest
import java.io.File

/** A scrollable column of rendered pages of [session] (its own scroll state and zoom). */
@Composable
fun PagePane(session: PdfSession?, pageCount: Int, sizes: Map<Int, Pair<Float, Float>>, list: LazyListState, night: Boolean, zoom: Float, modifier: Modifier = Modifier, label: String = "") {
    BoxWithConstraints(modifier.background(Color(0xFF808080))) {
        val density = LocalDensity.current
        // never negative / zero: requiredWidth / height with a negative or infinite size throws
        val wPx = (with(density) { maxWidth.toPx() } * zoom - with(density) { 8.dp.toPx() }).coerceAtLeast(1f)
        val contentW = maxWidth * zoom
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.width(contentW).fillMaxHeight(), state = list, contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pageCount) { p ->
                val (pw0, ph0) = sizes[p] ?: sizes[0] ?: (595f to 842f)
                val pw = if (pw0 > 0f) pw0 else 595f; val ph = if (ph0 > 0f) ph0 else 842f
                val hPx = (wPx * ph / pw).coerceIn(1f, 100_000f)
                val bmp by produceState<Bitmap?>(null, session, p, wPx.toInt(), night) {
                    val s = session ?: return@produceState
                    val w = wPx.toInt().coerceIn(200, 2400); val h = (w * ph / pw).toInt().coerceIn(1, 8000)
                    value = runCatching { s.render(p, w, h, night, true) }.getOrNull()
                }
                Box(Modifier.requiredWidth(with(density) { wPx.toDp() }).height(with(density) { hPx.toDp() }).background(Color.White)) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    Text("${p + 1}", Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 6.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        }
        if (label.isNotEmpty()) Surface(Modifier.align(Alignment.TopStart).padding(6.dp), color = Color(0xCC000000), shape = MaterialTheme.shapes.small) {
            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Keeps [b] at the same page / relative offset as [a] while [a] is being scrolled, and the other way round. */
@Composable
fun SyncScroll(a: LazyListState, b: LazyListState, enabled: Boolean) {
    LaunchedEffect(a, b, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { Triple(a.firstVisibleItemIndex, a.firstVisibleItemScrollOffset, a.isScrollInProgress) }.collectLatest { (i, off, moving) ->
            if (!moving || b.isScrollInProgress) return@collectLatest
            val ha = a.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
            val hb = b.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }?.size ?: b.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: ha
            b.scrollToItem(i, (off.toFloat() / ha * hb).toInt())
        }
    }
    LaunchedEffect(a, b, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { Triple(b.firstVisibleItemIndex, b.firstVisibleItemScrollOffset, b.isScrollInProgress) }.collectLatest { (i, off, moving) ->
            if (!moving || a.isScrollInProgress) return@collectLatest
            val hb = b.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
            val ha = a.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }?.size ?: a.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: hb
            a.scrollToItem(i, (off.toFloat() / hb * ha).toInt())
        }
    }
}

/**
 * Split view (two panes of the open document) or side-by-side compare with another PDF. Side by side or
 * stacked, a draggable divider, per-pane zoom and synchronised scrolling.
 */
@Composable
fun SplitCompareDialog(v: V5State, compare: Boolean) {
    val s = v.s
    val ctx = LocalContext.current
    var other by remember { mutableStateOf<File?>(null) }
    var otherSession by remember { mutableStateOf<PdfSession?>(null) }
    var otherSizes by remember { mutableStateOf<Map<Int, Pair<Float, Float>>>(emptyMap()) }
    var otherError by remember { mutableStateOf<String?>(null) }
    val pick = rememberPdfPicker(multiple = false) { files -> files.firstOrNull()?.let { other = it } }
    LaunchedEffect(other) {
        val f = other ?: return@LaunchedEffect
        runCatching { otherSession?.closeAsync() }
        otherSession = null
        runCatching { PdfSession.open(ctx, f, null) }.onSuccess { ses -> otherSizes = runCatching { ses.pageSizes() }.getOrDefault(emptyMap()); otherSession = ses; otherError = null }
            .onFailure { otherError = it.message ?: ctx.getString(R.string.error_generic) }
    }
    DisposableEffect(Unit) { onDispose { otherSession?.let { ses -> runCatching { ses.close() } } } }
    LaunchedEffect(compare) { if (compare && other == null) pick() }

    var stacked by remember { mutableStateOf(false) }
    var sync by remember { mutableStateOf(compare) }
    var frac by remember { mutableFloatStateOf(0.5f) }
    var zoomA by remember { mutableFloatStateOf(1f) }
    var zoomB by remember { mutableFloatStateOf(1f) }
    val listA = rememberLazyListState(s.currentPage)
    val listB = rememberLazyListState(if (compare) 0 else s.currentPage)
    SyncScroll(listA, listB, sync)

    Dialog(onDismissRequest = { v.close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { v.close() }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Text(stringResource(if (compare) R.string.v5_compare else R.string.v5_split_view), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    FilterChip(sync, { sync = !sync }, label = { Text(stringResource(R.string.v5_sync_scroll)) }, leadingIcon = { Icon(Icons.Default.Sync, null, Modifier.size(16.dp)) })
                    IconButton(onClick = { stacked = !stacked }) { Icon(if (stacked) Icons.Default.VerticalSplit else Icons.Default.HorizontalSplit, stringResource(R.string.v5_layout)) }
                    if (compare) IconButton(onClick = { pick() }) { Icon(Icons.Default.FileOpen, stringResource(R.string.v5_pick_other)) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    ZoomButtons(zoomA) { zoomA = it }
                    ZoomButtons(zoomB) { zoomB = it }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val density = LocalDensity.current
                    val total = with(density) { if (stacked) maxHeight.toPx() else maxWidth.toPx() }
                    val paneA: @Composable (Modifier) -> Unit = { m -> PagePane(s.session, s.pageCount, s.pageSizes, listA, s.nightMode, zoomA, m, s.file?.name ?: "") }
                    val paneB: @Composable (Modifier) -> Unit = { m ->
                        if (!compare) PagePane(s.session, s.pageCount, s.pageSizes, listB, s.nightMode, zoomB, m, s.file?.name ?: "")
                        else if (otherSession != null) PagePane(otherSession, otherSession!!.pageCount, otherSizes, listB, s.nightMode, zoomB, m, other?.name ?: "")
                        else Box(m.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                otherError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Button(onClick = { pick() }) { Text(stringResource(R.string.v5_pick_other)) }
                            }
                        }
                    }
                    val divider = Modifier.background(MaterialTheme.colorScheme.outlineVariant).pointerInput(stacked, total) {
                        detectDragGestures { ch, d -> ch.consume(); frac = (frac + (if (stacked) d.y else d.x) / total).coerceIn(0.15f, 0.85f) }
                    }
                    if (stacked) Column(Modifier.fillMaxSize()) {
                        paneA(Modifier.fillMaxWidth().weight(frac))
                        Box(divider.fillMaxWidth().height(12.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.DragHandle, null, Modifier.size(12.dp)) }
                        paneB(Modifier.fillMaxWidth().weight(1f - frac))
                    } else Row(Modifier.fillMaxSize()) {
                        paneA(Modifier.fillMaxHeight().weight(frac))
                        Box(divider.fillMaxHeight().width(12.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.DragIndicator, null, Modifier.size(12.dp)) }
                        paneB(Modifier.fillMaxHeight().weight(1f - frac))
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomButtons(z: Float, onZoom: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onZoom((z / 1.25f).coerceAtLeast(0.5f)) }, Modifier.size(32.dp)) { Icon(Icons.Default.ZoomOut, stringResource(R.string.pc_cd_zoom_out)) }
        Text("${(z * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = { onZoom((z * 1.25f).coerceAtMost(4f)) }, Modifier.size(32.dp)) { Icon(Icons.Default.ZoomIn, stringResource(R.string.pc_cd_zoom_in)) }
    }
}
