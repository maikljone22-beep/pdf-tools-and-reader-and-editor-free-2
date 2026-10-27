package com.smartdocs.editor.ui.security

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A mark as drawn on the page view: display space (origin bottom-left, top > bottom) with its fill colour. */
data class ManualBox(val id: String, val rect: RectF, val fill: Int, val outline: Int?, val wholePage: Boolean = false, val locked: Boolean = false, val single: Boolean = true)

/**
 * Single-page viewer where the user drags rectangles. Existing regions of the page are drawn as black
 * boxes (preview of the final result); the rectangle being drawn is shown in translucent red.
 */
@Composable
fun RedactManualView(
    session: PdfSession,
    page: Int,
    onPage: (Int) -> Unit,
    boxes: List<ManualBox>,
    onAdd: (RectF) -> Unit,
    modifier: Modifier = Modifier,
    onMove: (String, Float, Float) -> Unit = { _, _, _ -> },
    onResize: (String, RectF) -> Unit = { _, _ -> },
    onDoubleTap: (Float, Float) -> Unit = { _, _ -> },
) {
    var drawMode by remember { mutableStateOf(true) }
    var size by remember(page) { mutableStateOf(595f to 842f) }
    // a bad page index / closed session must not crash; zero sizes would make aspectRatio() throw
    LaunchedEffect(page) { runCatching { session.pageSize(page) }.getOrNull()?.takeIf { it.first > 0f && it.second > 0f }?.let { size = it } }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.previous)) }
            Text(stringResource(R.string.page_of, page + 1, session.pageCount), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { onPage(page + 1) }, enabled = page < session.pageCount - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.next)) }
            FilterChip(drawMode, { drawMode = !drawMode }, label = { Text(stringResource(if (drawMode) R.string.pg_redact_draw else R.string.pg_redact_pan)) },
                leadingIcon = { Icon(if (drawMode) Icons.Default.Draw else Icons.Default.PanTool, null, Modifier.size(16.dp)) })
        }
        Text(stringResource(R.string.pg_redact_draw_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(8.dp)) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
            val (pw, ph) = size
            val heightPx = (widthPx * ph / pw).toInt().coerceAtLeast(1)
            val scale = widthPx / pw   // px per point
            val bmp by produceState<Bitmap?>(null, page, widthPx, size) {
                value = withContext(Dispatchers.IO) { runCatching { session.render(page, widthPx, heightPx) }.getOrNull() }
            }
            var dragStart by remember { mutableStateOf<Offset?>(null) }
            var dragNow by remember { mutableStateOf<Offset?>(null) }
            // Foxit: an existing mark is moved by dragging inside it and resized by dragging a corner
            var editing by remember { mutableStateOf<Pair<ManualBox, Int>?>(null) }   // box, -1 = move, 0..3 = corner
            val current by rememberUpdatedState(boxes)
            fun toPt(o: Offset) = (o.x / scale) to (ph - o.y / scale)
            fun hit(o: Offset): Pair<ManualBox, Int>? {
                val (x, y) = toPt(o)
                val slop = 18f / scale
                for (b in current.reversed()) {
                    if (b.wholePage || b.locked) continue
                    val r = b.rect
                    if (b.single) {
                        val corners = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)
                        corners.forEachIndexed { k, (cx, cy) -> if (kotlin.math.abs(cx - x) <= slop && kotlin.math.abs(cy - y) <= slop) return b to k }
                    }
                    if (x >= r.left && x <= r.right && y <= r.top && y >= r.bottom) return b to -1
                }
                return null
            }

            Box(
                Modifier.fillMaxWidth().aspectRatio(pw / ph).background(Color.White)
                    .pointerInput(page, scale) { detectTapGestures(onDoubleTap = { o -> val (x, y) = toPt(o); onDoubleTap(x, y) }) }
                    .then(if (drawMode) Modifier.pointerInput(page, scale) {
                        detectDragGestures(
                            onDragStart = { dragStart = it; dragNow = it; editing = hit(it) },
                            onDrag = { change, _ -> change.consume(); dragNow = change.position },
                            onDragEnd = {
                                val a = dragStart; val b = dragNow; val e = editing
                                if (a != null && b != null && e != null) {
                                    val (box, corner) = e
                                    val dx = (b.x - a.x) / scale; val dy = -(b.y - a.y) / scale
                                    if (corner < 0) onMove(box.id, dx, dy)
                                    else {
                                        val r = box.rect
                                        var l = r.left; var t = r.top; var rr = r.right; var bt = r.bottom
                                        when (corner) { 0 -> { l += dx; t += dy }; 1 -> { rr += dx; t += dy }; 2 -> { l += dx; bt += dy }; else -> { rr += dx; bt += dy } }
                                        val n = RectF(minOf(l, rr), maxOf(t, bt), maxOf(l, rr), minOf(t, bt))
                                        if (n.right - n.left > 2f && n.top - n.bottom > 2f) onResize(box.id, n)
                                    }
                                } else if (a != null && b != null) {
                                    val l = minOf(a.x, b.x) / scale; val r = maxOf(a.x, b.x) / scale
                                    val top = ph - minOf(a.y, b.y) / scale; val bottom = ph - maxOf(a.y, b.y) / scale
                                    if (r - l > 2f && top - bottom > 2f) onAdd(RectF(l.coerceIn(0f, pw), top.coerceIn(0f, ph), r.coerceIn(0f, pw), bottom.coerceIn(0f, ph)))
                                }
                                dragStart = null; dragNow = null; editing = null
                            },
                            onDragCancel = { dragStart = null; dragNow = null; editing = null },
                        )
                    } else Modifier)
            ) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
                Canvas(Modifier.fillMaxSize()) {
                    val s = this.size.width / pw
                    boxes.forEach { reg ->
                        val r = if (reg.wholePage) RectF(0f, ph, pw, 0f) else reg.rect
                        val tl = Offset(r.left * s, (ph - r.top) * s); val sz = Size((r.right - r.left) * s, (r.top - r.bottom) * s)
                        // preview: the fill at 55% so the marked text stays readable, plus the outline
                        drawRect(Color(0xFF000000 or reg.fill.toLong()).copy(alpha = 0.55f), tl, sz)
                        drawRect(Color(0xFF000000 or (reg.outline ?: 0xE53935).toLong()), tl, sz, style = Stroke(width = 2f))
                    }
                    val a = dragStart; val b = dragNow
                    val e = editing
                    if (a != null && b != null && e != null) {
                        // the moved / resized mark as it will land
                        val r = e.first.rect; val dx = b.x - a.x; val dy = b.y - a.y
                        var l = r.left * s; var t = (ph - r.top) * s; var rr = r.right * s; var bt = (ph - r.bottom) * s
                        when (e.second) { -1 -> { l += dx; rr += dx; t += dy; bt += dy }; 0 -> { l += dx; t += dy }; 1 -> { rr += dx; t += dy }; 2 -> { l += dx; bt += dy }; else -> { rr += dx; bt += dy } }
                        val tl = Offset(minOf(l, rr), minOf(t, bt)); val sz = Size(kotlin.math.abs(rr - l), kotlin.math.abs(bt - t))
                        drawRect(Color(0xFF1E88E5).copy(alpha = 0.25f), tl, sz)
                        drawRect(Color(0xFF1E88E5), tl, sz, style = Stroke(width = 3f))
                    } else if (a != null && b != null) {
                        val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y)); val sz = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                        drawRect(Color.Red.copy(alpha = 0.3f), tl, sz)
                        drawRect(Color.Red, tl, sz, style = Stroke(width = 3f))
                    }
                    // corner handles of the marks that can be resized
                    boxes.filter { !it.wholePage && !it.locked && it.single }.forEach { reg ->
                        val r = reg.rect
                        listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).forEach { (cx, cy) ->
                            drawCircle(Color.White, 5f, Offset(cx * s, (ph - cy) * s)); drawCircle(Color(0xFF1E88E5), 5f, Offset(cx * s, (ph - cy) * s), style = Stroke(2f))
                        }
                    }
                }
            }
        }
    }
}
