package com.smartdocs.editor.ui.reader.view

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.reader.MAX_ZOOM
import com.smartdocs.editor.ui.reader.MIN_ZOOM
import com.smartdocs.editor.ui.reader.ReaderState
import kotlin.math.roundToInt

/**
 * Floating "pan & zoom" window inside the reader (the reference's PanZoomView, never a system overlay):
 * a mini-map of what the main view shows (the current page, or the current spread in paged modes) with a
 * rectangle for the visible part. Dragging / tapping the mini-map moves the main view; the window itself is
 * dragged by its handle; a slider sets the zoom; arrows turn screens.
 *
 * @param pages pages drawn side by side in the mini-map.
 * @param visible visible part of the mini-map content, in fractions (null when nothing is visible).
 * @param onMove new centre of the visible part, in content fractions.
 * @param onDragging true while the user drags the rectangle (the caller freezes [pages] meanwhile).
 */
@Composable
fun MagnifierWindow(
    state: ReaderState,
    pages: List<Int>,
    bounds: IntSize,
    visible: () -> Rect?,
    onMove: (Offset) -> Unit,
    onDragging: (Boolean) -> Unit,
    onZoom: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val winW = with(density) { 220.dp.toPx() }
    var pos by remember { mutableStateOf<Offset?>(null) }
    var winSize by remember { mutableStateOf(IntSize.Zero) }
    // reference placement: centred horizontally, a quarter down
    val p = pos ?: Offset((bounds.width - winW) / 2f, bounds.height / 4f)
    fun clampPos(o: Offset) = Offset(
        o.x.coerceIn(0f, (bounds.width - winSize.width).toFloat().coerceAtLeast(0f)),
        o.y.coerceIn(0f, (bounds.height - winSize.height).toFloat().coerceAtLeast(0f)),
    )

    Surface(
        modifier = modifier
            .absoluteOffset { val c = clampPos(p); IntOffset(c.x.roundToInt(), c.y.roundToInt()) }
            .width(220.dp)
            .onSizeChanged { winSize = it },
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 4.dp, shadowElevation = 10.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(32.dp)
                    .pointerInput(bounds) { detectDragGestures { ch, d -> ch.consume(); pos = clampPos((pos ?: p) + d) } }
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.DragIndicator, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.vw_magnifier), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, stringResource(R.string.close), Modifier.size(18.dp)) }
            }
            MiniMap(state, pages, visible, onMove, onDragging, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ZoomOut, null, Modifier.size(18.dp))
                Slider(state.zoom, { onZoom(it) }, valueRange = MIN_ZOOM..MAX_ZOOM, modifier = Modifier.weight(1f).height(32.dp))
                Icon(Icons.Default.ZoomIn, null, Modifier.size(18.dp))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.vx.flip(-1) }, enabled = state.vx.canFlip(-1)) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.previous)) }
                Text("%.1f×".format(state.zoom), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { state.vx.flip(1) }, enabled = state.vx.canFlip(1)) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.next)) }
            }
        }
    }
}

@Composable
private fun MiniMap(
    state: ReaderState,
    pages: List<Int>,
    visible: () -> Rect?,
    onMove: (Offset) -> Unit,
    onDragging: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val session = state.session
    val rotation = state.rotation
    val night = state.nightMode
    val accent = MaterialTheme.colorScheme.primary
    // layout aspect (w/h) of each page after rotation
    val aspects = pages.map { pg ->
        val (w, h) = state.pageSizes[pg] ?: (595f to 842f)
        if (rotation % 180 == 0) w / h else h / w
    }
    val totalAspect = aspects.sum().coerceAtLeast(0.1f)        // pages share the same height in the mini-map
    val thumbs = remember(pages, session, night, state.renderVersion) { mutableStateMapOf<Int, Bitmap>() }
    LaunchedEffect(pages, session, night, state.renderVersion) {
        val s = session ?: return@LaunchedEffect
        for (pg in pages) {
            if (s.isClosed) break
            val (w, h) = state.pageSizes[pg] ?: (595f to 842f)
            val tw = 200 / pages.size.coerceAtLeast(1)
            val th = (tw * h / w.coerceAtLeast(1f)).roundToInt().coerceIn(8, 600)
            runCatching { s.render(pg, tw, th, night, true) }.getOrNull()?.let { thumbs[pg] = it }
        }
    }
    var center by remember { mutableStateOf(Offset.Zero) }
    // tall pages would make a huge window: cap the mini-map height and centre the pages horizontally
    BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = with(LocalDensity.current) { 240.dp.toPx() }
        val h = (maxW / totalAspect).coerceAtMost(maxH)
        val w = h * totalAspect
        val den = LocalDensity.current
        Box(Modifier.fillMaxWidth().height(with(den) { h.toDp() }), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.size(with(den) { w.toDp() }, with(den) { h.toDp() })
                    .background(Color(0x22000000))
                    .pointerInput(pages) {
                        detectTapGestures { t -> onMove(Offset(t.x / this.size.width, t.y / this.size.height)) }
                    }
                    .pointerInput(pages) {
                        detectDragGestures(
                            onDragStart = { s ->
                                onDragging(true)
                                val v = visible()
                                center = v?.let { Offset((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) } ?: Offset(s.x / this.size.width, s.y / this.size.height)
                            },
                            onDragEnd = { onDragging(false) },
                            onDragCancel = { onDragging(false) },
                        ) { ch, d ->
                            ch.consume()
                            center = Offset((center.x + d.x / this.size.width).coerceIn(0f, 1f), (center.y + d.y / this.size.height).coerceIn(0f, 1f))
                            onMove(center)
                        }
                    },
            ) {
                var x = 0f
                val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                val order = if (rtl) pages.indices.reversed() else pages.indices
                for (i in order) {
                    val pw = this.size.height * aspects[i]
                    val bmp = thumbs[pages[i]]
                    val bw = if (rotation % 180 == 0) pw else this.size.height
                    val bh = if (rotation % 180 == 0) this.size.height else pw
                    withTransform({
                        translate(left = x)
                        when (rotation) {
                            90 -> { translate(left = bh); rotate(90f, Offset.Zero) }
                            180 -> { translate(left = bw, top = bh); rotate(180f, Offset.Zero) }
                            270 -> { translate(top = bw); rotate(270f, Offset.Zero) }
                        }
                    }) {
                        if (bmp != null) drawImage(bmp.asImageBitmap(), dstSize = IntSize(bw.roundToInt().coerceAtLeast(1), bh.roundToInt().coerceAtLeast(1)))
                        else drawRect(if (night) Color(0xFF303030) else Color.White, Offset.Zero, Size(bw, bh))
                    }
                    x += pw
                }
                visible()?.let { v ->
                    val r = Rect(v.left * this.size.width, v.top * this.size.height, v.right * this.size.width, v.bottom * this.size.height)
                    drawRect(accent.copy(alpha = 0.18f), r.topLeft, r.size)
                    drawRect(accent, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}
