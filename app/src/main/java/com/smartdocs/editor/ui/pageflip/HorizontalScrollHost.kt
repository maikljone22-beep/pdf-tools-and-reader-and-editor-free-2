package com.smartdocs.editor.ui.pageflip

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.core.pdf.PageLink
import com.smartdocs.editor.ui.reader.MAX_ZOOM
import com.smartdocs.editor.ui.reader.MIN_ZOOM
import com.smartdocs.editor.ui.reader.PageCanvas
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.TextPrompt
import com.smartdocs.editor.ui.reader.view.readerGestures
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Horizontal continuous layout: pages side by side, each fitted to the height, scrolled sideways (right to left for
 * RTL documents). Pinch zoom; zoomed in, vertical drags pan. Jumps scroll with animation; mouse wheel scrolls a page.
 */
@Composable
fun HorizontalScrollHost(
    state: ReaderState,
    pf: PageFlipController,
    chrome: Boolean,
    onTapCenter: () -> Unit,
    onOpenLink: (PageLink) -> Unit,
    onPrompt: (TextPrompt) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.session ?: return
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val uiRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(session) { pf.detectDirection() }
    val rtl = pf.rtl(uiRtl)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var panY by remember { mutableFloatStateOf(0f) }
    val gap = with(density) { 8.dp.toPx() }
    val baseH = (viewport.height - gap * 2).coerceAtLeast(1f)
    val lowRam = remember { PageFlipController.lowRam(ctx) }
    val cache = remember(session, state.renderVersion, state.nightMode) { PageSnapshotCache(session, state.nightMode, lowRam) }
    fun aspect(p: Int) = (state.pageSizes[p] ?: state.pageSizes[0] ?: (595f to 842f)).let { (w, h) -> (w / h.coerceAtLeast(1f)).coerceIn(0.1f, 10f) }
    fun clampPan() { val my = max(0f, (baseH * state.zoom - viewport.height) / 2f + gap); panY = panY.coerceIn(-my, my) }
    LaunchedEffect(state.zoom) { if (state.zoom <= 1.01f) panY = 0f else clampPan() }

    LaunchedEffect(state.pendingJump) {
        val p = state.pendingJump ?: return@LaunchedEffect
        state.pendingJump = null
        val far = abs(p - state.currentPage) > 8
        if (far) listState.scrollToItem((p - if (p > state.currentPage) 2 else -2).coerceIn(0, state.pageCount - 1))
        listState.animateScrollToItem(p.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val c = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - c) }?.index ?: -1
        }.collect { if (it >= 0 && it != state.currentPage) { state.rememberPage(it); pf.haptic(view) } }
    }
    var wheelAt by remember { mutableLongStateOf(0L) }
    com.smartdocs.editor.ui.reader.view.AutoFlipEffect(state)

    Box(
        modifier.fillMaxSize().background(if (state.nightMode) Color(0xFF121212) else MaterialTheme.colorScheme.surfaceContainerLowest)
            .onSizeChanged { viewport = it }.clipToBounds()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Scroll) continue
                        val c = e.changes.firstOrNull() ?: continue
                        val now = SystemClock.uptimeMillis()
                        val d = c.scrollDelta.y + c.scrollDelta.x
                        if (abs(d) >= 0.5f && now - wheelAt > 140L) {
                            wheelAt = now
                            state.vx.flip(if (d > 0) 1 else -1)
                        }
                        c.consume()
                    }
                }
            }
            .readerGestures(
                canTake = { d -> state.tool == null && state.selection == null && state.zoom > 1.01f && abs(d.y) > abs(d.x) },
                onDown = {},
                onPinch = { z, _, _ -> state.zoom = (state.zoom * z).coerceIn(MIN_ZOOM, MAX_ZOOM); clampPan() },
                onPan = { d -> panY += d.y; clampPan() },
                onFling = {},
            ),
    ) {
        if (viewport == IntSize.Zero || state.pageCount == 0) return@Box
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize().absoluteOffset { IntOffset(0, panY.roundToInt()) },
            reverseLayout = rtl != uiRtl,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            items(state.pageCount, key = { it }) { page ->
                val h = baseH * state.zoom
                val w = h * aspect(page)
                PageCanvas(
                    state, page, w,
                    visibleClip = { visibleInRow(listState, page, w, h, viewport, panY) },
                    onTap = { _, _ -> onTapCenter() },
                    onDoubleTap = { state.zoom = if (state.zoom > 1.05f) 1f else 2f },
                    onOpenLink = onOpenLink, onPrompt = onPrompt,
                    modifier = Modifier.wrapContentSize(unbounded = true).shadow(2.dp, RoundedCornerShape(2.dp)),
                )
            }
        }
        com.smartdocs.editor.ui.reader.view.AutoFlipBar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        if (chrome && state.pageCount > 1) PageScrubber(
            state, cache, scope, rtl, view, pf,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 44.dp),
            onJump = { p -> scope.launch { listState.animateScrollToItem(p) } },
        )
    }
}

/** Visible part of a page box of the row, in fractions of the box (for the zoom tile of PageCanvas). */
private fun visibleInRow(list: androidx.compose.foundation.lazy.LazyListState, page: Int, w: Float, h: Float, vp: IntSize, panY: Float): Rect? {
    val info = list.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == page } ?: return null
    val left = item.offset.toFloat().let { if (info.reverseLayout) info.viewportEndOffset - it - item.size else it }
    val l = ((info.viewportStartOffset - left) / w).coerceIn(0f, 1f)
    val r = ((info.viewportEndOffset - left) / w).coerceIn(0f, 1f)
    val top = (vp.height - h) / 2f + panY
    val t = ((0f - top) / h).coerceIn(0f, 1f); val b = ((vp.height - top) / h).coerceIn(0f, 1f)
    return if (r <= l || b <= t) null else Rect(l, t, r, b)
}
