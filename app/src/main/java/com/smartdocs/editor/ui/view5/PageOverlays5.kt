package com.smartdocs.editor.ui.view5

import android.os.Build
import android.widget.Magnifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Overlays drawn over the page area (hook inside the page Box of ReaderScreen): rulers, grid, reading ruler,
 * lens, auto-scroll controls and the "back to previous position" pill. Rulers and grid use the fit-width
 * page scale at the current zoom (PageView's scroll geometry is private), so they measure real page units.
 */
@Composable
fun View5PageOverlays(s: ReaderState) {
    val v = V5State.of(s)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val gap = with(density) { 8.dp.toPx() }
        val pageW = s.pageSizes[s.currentPage]?.first ?: 595f
        val pxPerPt = ((wPx - gap * 2) / pageW * s.zoom).coerceAtLeast(0.01f)

        if (v.grid || v.rulers) GridAndRulers(v, pxPerPt, wPx, hPx)
        if (v.readingRuler) ReadingRuler(v, hPx)
        if (v.lens) LensOverlay(v)
        if (v.autoScroll) AutoScrollBar(v, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
        HistoryPill(v, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 56.dp))
    }
}

@Composable
private fun GridAndRulers(v: V5State, pxPerPt: Float, w: Float, h: Float) {
    val mmPx = pxPerPt * 72f / 25.4f
    val label = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = 0xFF37474F.toInt() } }
    Canvas(Modifier.fillMaxSize()) {
        if (v.grid) {
            val step = (v.gridMm * mmPx).coerceAtLeast(6f)
            var x = 0f; var i = 0
            while (x < w) { drawLine(Color(if (i % 5 == 0) 0x552196F3 else 0x302196F3), Offset(x, 0f), Offset(x, h), if (i % 5 == 0) 1.5f else 1f); x += step; i++ }
            var y = 0f; i = 0
            while (y < h) { drawLine(Color(if (i % 5 == 0) 0x552196F3 else 0x302196F3), Offset(0f, y), Offset(w, y), if (i % 5 == 0) 1.5f else 1f); y += step; i++ }
        }
        if (v.rulers) {
            val band = 28f
            drawRect(Color(0xE6FFFDE7), Offset.Zero, Size(w, band))
            drawRect(Color(0xE6FFFDE7), Offset.Zero, Size(band, h))
            // mm ticks, cm labels
            var k = 0
            while (k * mmPx < w) {
                val x = k * mmPx; val len = if (k % 10 == 0) band else if (k % 5 == 0) band * 0.6f else band * 0.35f
                if (mmPx >= 2.5f || k % 5 == 0) drawLine(Color(0xFF455A64), Offset(x, 0f), Offset(x, len), 1f)
                if (k % 10 == 0 && k > 0) drawContext.canvas.nativeCanvas.drawText((k / 10).toString(), x + 3f, band - 4f, label)
                k++
            }
            k = 0
            while (k * mmPx < h) {
                val y = k * mmPx; val len = if (k % 10 == 0) band else if (k % 5 == 0) band * 0.6f else band * 0.35f
                if (mmPx >= 2.5f || k % 5 == 0) drawLine(Color(0xFF455A64), Offset(0f, y), Offset(len, y), 1f)
                if (k % 10 == 0 && k > 0) drawContext.canvas.nativeCanvas.drawText((k / 10).toString(), 3f, y - 3f, label)
                k++
            }
            drawContext.canvas.nativeCanvas.drawText("cm", 2f, band - 4f, label)
        }
    }
}

@Composable
private fun ReadingRuler(v: V5State, hPx: Float) {
    val density = LocalDensity.current
    val bandPx = with(density) { v.rulerHeightDp.dp.toPx() }
    val top = (v.rulerY * hPx - bandPx / 2).coerceIn(0f, (hPx - bandPx).coerceAtLeast(0f))
    val tint = Color(0xFF000000.toInt() or v.rulerColor)
    Canvas(Modifier.fillMaxSize()) {
        if (v.rulerDim) {
            drawRect(Color(0x88000000), Offset.Zero, Size(size.width, top))
            drawRect(Color(0x88000000), Offset(0f, top + bandPx), Size(size.width, size.height - top - bandPx))
        }
        drawRect(tint.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, bandPx))
        drawLine(tint, Offset(0f, top), Offset(size.width, top), 3f)
        drawLine(tint, Offset(0f, top + bandPx), Offset(size.width, top + bandPx), 3f)
    }
    // drag handle on the band's right edge only, so the page keeps its own gestures
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.align(Alignment.TopEnd).offset { androidx.compose.ui.unit.IntOffset(0, top.roundToInt()) }.size(36.dp, with(density) { bandPx.toDp() })
            .pointerInput(hPx) { detectVerticalDragGestures { ch, dy -> ch.consume(); if (hPx > 0f) v.rulerY = ((v.rulerY * hPx + dy) / hPx).coerceIn(0f, 1f) /* hPx 0 → NaN → roundToInt crash */ } },
            color = tint.copy(alpha = 0.85f), shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)) {
            Icon(Icons.Default.DragHandle, stringResource(R.string.v5_reading_ruler), Modifier.padding(6.dp))
        }
    }
}

@Composable
private fun LensOverlay(v: V5State) {
    val view = LocalView.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val magnifier = remember(view, v.lensZoom) {
        if (Build.VERSION.SDK_INT >= 29) Magnifier.Builder(view).setInitialZoom(v.lensZoom).setSize(420, 260).setCornerRadius(40f).build()
        else if (Build.VERSION.SDK_INT >= 28) @Suppress("DEPRECATION") Magnifier(view) else null
    }
    DisposableEffect(magnifier) { onDispose { if (Build.VERSION.SDK_INT >= 28) magnifier?.dismiss() } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(magnifier) {
            detectDragGestures(
                onDragStart = { p -> if (Build.VERSION.SDK_INT >= 28) magnifier?.show(origin.x + p.x, origin.y + p.y) },
                onDragEnd = { if (Build.VERSION.SDK_INT >= 28) magnifier?.dismiss() },
                onDragCancel = { if (Build.VERSION.SDK_INT >= 28) magnifier?.dismiss() },
            ) { ch, _ -> ch.consume(); if (Build.VERSION.SDK_INT >= 28) magnifier?.show(origin.x + ch.position.x, origin.y + ch.position.y) }
        }) {
        Surface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (Build.VERSION.SDK_INT >= 28) R.string.v5_lens_hint else R.string.v5_lens_unsupported), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { v.lens = false }, Modifier.size(28.dp)) { Icon(Icons.Default.Close, stringResource(R.string.close), Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun AutoScrollBar(v: V5State, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { v.autoSpeed = (v.autoSpeed - 1).coerceAtLeast(1); v.saveOverlays() }) { Icon(Icons.Default.Remove, stringResource(R.string.v5_slower)) }
            Text(stringResource(R.string.v5_speed_n, v.autoSpeed), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { v.autoSpeed = (v.autoSpeed + 1).coerceAtMost(10); v.saveOverlays() }) { Icon(Icons.Default.Add, stringResource(R.string.v5_faster)) }
            IconButton(onClick = { v.autoPaused = !v.autoPaused }) { Icon(if (v.autoPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null) }
            IconButton(onClick = { v.autoScroll = false; v.autoPaused = false }) { Icon(Icons.Default.Stop, stringResource(R.string.v5_stop)) }
        }
    }
}

@Composable
private fun HistoryPill(v: V5State, modifier: Modifier) {
    val ver = v.historyVersion
    LaunchedEffect(ver, v.showHistoryPill) { if (v.showHistoryPill) { delay(6000); v.showHistoryPill = false } }
    val target = v.history.peekBack()
    if (v.showHistoryPill && target != null) {
        Surface(onClick = { v.back(); v.showHistoryPill = false }, modifier = modifier, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.v5_back_to_page, target + 1), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Smooth auto-scroll of the continuous list (hook in PageView). Speed 1..10 ≈ 18..180 dp/s. */
@Composable
fun V5AutoScrollEffect(s: ReaderState, list: LazyListState) {
    val v = V5State.of(s)
    val density = LocalDensity.current
    LaunchedEffect(v.autoScroll, v.autoPaused, v.autoSpeed, list) {
        if (!v.autoScroll || v.autoPaused) return@LaunchedEffect
        val pxPerSec = with(density) { (v.autoSpeed * 18).dp.toPx() }
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f); last = now
            list.dispatchRawDelta(pxPerSec * dt)
            if (!list.canScrollForward) { v.autoScroll = false; break }
        }
    }
}
