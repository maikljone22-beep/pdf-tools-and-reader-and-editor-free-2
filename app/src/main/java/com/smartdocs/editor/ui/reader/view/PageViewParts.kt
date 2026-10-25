package com.smartdocs.editor.ui.reader.view

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// Gesture detector and fast scroller of the page view (moved out of PageView.kt to keep it small).

/**
 * Pinch-zoom + single-finger pan handled in the Initial pass, so that the LazyColumn / pager below never sees a
 * pinch as a scroll. A one-finger drag is taken only when [canTake] (evaluated once, at touch slop, with the drag
 * direction) says so; otherwise it is left untouched for the list / pager / page tools.
 */
fun Modifier.readerGestures(
    canTake: (Offset) -> Boolean,
    onDown: () -> Unit,
    onPinch: (zoom: Float, centroid: Offset, pan: Offset) -> Unit,
    onPan: (Offset) -> Unit,
    onFling: (androidx.compose.ui.unit.Velocity) -> Unit,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onDown()
        var pinching = false
        var decided = false; var taking = false
        var total = Offset.Zero
        val tracker = VelocityTracker()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                pinching = true; taking = false
                val z = event.calculateZoom(); val c = event.calculateCentroid(); val p = event.calculatePan()
                if (z != 1f || p != Offset.Zero) onPinch(z, c, p)
                event.changes.forEach { it.consume() }
            } else if (pinching) {
                event.changes.forEach { it.consume() }      // ignore the finger left over after a pinch
            } else if (pressed.size == 1) {
                val ch = pressed[0]
                val d = ch.positionChange()
                if (!decided) {
                    total += d
                    if (total.getDistance() > slop) { decided = true; taking = canTake(total) }
                }
                if (taking) { onPan(d); tracker.addPosition(ch.uptimeMillis, ch.position); ch.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (taking) onFling(tracker.calculateVelocity())
    }
}

/** Draggable page scrubber on the right edge with a page-number bubble; fades out when idle. */
@Composable
fun FastScroller(
    listState: androidx.compose.foundation.lazy.LazyListState,
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var trackH by remember { mutableFloatStateOf(1f) }
    val visible = dragging || listState.isScrollInProgress
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) shown = true else { delay(1500); shown = false } }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, label = "fs")
    if (alpha <= 0.01f) return
    val density = LocalDensity.current
    val thumbH = with(density) { 44.dp.toPx() }
    val frac = if (dragging) dragFrac else if (pageCount <= 1) 0f else currentPage / (pageCount - 1f)
    val page = if (dragging) (dragFrac * (pageCount - 1)).roundToInt() else currentPage
    Box(
        modifier.fillMaxHeight().width(56.dp).padding(vertical = 12.dp)
            .androidx_alpha(alpha)
            .onSizeChanged { trackH = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(pageCount) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val y0 = frac * (trackH - thumbH)
                    if (abs(down.position.y - (y0 + thumbH / 2)) > thumbH * 1.2f) return@awaitEachGesture
                    down.consume(); dragging = true
                    do {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        dragFrac = ((c.position.y - thumbH / 2) / (trackH - thumbH)).coerceIn(0f, 1f)
                        val target = (dragFrac * (pageCount - 1)).roundToInt()
                        scope.launch { listState.scrollToItem(target) }
                        c.consume()
                    } while (ev.changes.any { it.pressed })
                    dragging = false
                }
            },
    ) {
        val y = with(density) { (frac * (trackH - thumbH)).toDp() }
        androidx.compose.material3.Surface(
            modifier = Modifier.align(Alignment.TopEnd).offset(y = y).padding(end = 4.dp).size(width = 8.dp, height = 44.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        ) {}
        if (dragging) androidx.compose.material3.Surface(
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-20).dp, y = y),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp,
        ) {
            androidx.compose.material3.Text("${page + 1} / $pageCount", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun Modifier.androidx_alpha(a: Float): Modifier = this.then(Modifier.graphicsLayer { alpha = a })
