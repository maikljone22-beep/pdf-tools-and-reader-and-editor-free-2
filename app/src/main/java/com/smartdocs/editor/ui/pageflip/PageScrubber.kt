package com.smartdocs.editor.ui.pageflip

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pageflip.FlipMath
import com.smartdocs.editor.core.pageflip.SpreadMap
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

private const val THUMB_W = 132

/**
 * Page slider of the paged hosts: a track in reading direction (right to left in RTL) with a thumb; while dragging,
 * a bubble shows the target page's thumbnail on a small page stack and the page number. Release = animated jump.
 */
@Composable
fun PageScrubber(
    state: ReaderState,
    cache: PageSnapshotCache,
    scope: CoroutineScope,
    rtl: Boolean,
    view: View,
    pf: PageFlipController,
    modifier: Modifier = Modifier,
    onJump: (Int) -> Unit = { state.jumpTo(it) },
) {
    val count = state.pageCount
    var trackW by remember { mutableFloatStateOf(1f) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val frac = drag ?: FlipMath.fractionOf(state.currentPage, count, rtl)
    val page = FlipMath.pageAt(frac, count, rtl)
    var lastTick by remember { mutableIntStateOf(-1) }
    Box(modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        drag?.let {
            Box(Modifier.align(Alignment.BottomStart).absoluteOffset { IntOffset((frac * trackW - with(density) { (THUMB_W / 2 + 12).dp.toPx() }).roundToInt().coerceIn(0, (trackW - with(density) { (THUMB_W + 24).dp.toPx() }).roundToInt().coerceAtLeast(0)), -with(density) { 40.dp.toPx() }.roundToInt()) }) {
                PageBubble(cache, scope, state, page)
            }
        }
        Surface(Modifier.fillMaxWidth().height(36.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 3.dp) {
            Canvas(
                Modifier.fillMaxSize().padding(horizontal = 18.dp)
                    .onSizeChanged { trackW = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(count, rtl) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            drag = (down.position.x / trackW).coerceIn(0f, 1f)
                            do {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull() ?: break
                                drag = (c.position.x / trackW).coerceIn(0f, 1f)
                                val p = FlipMath.pageAt(drag ?: 0f, count, rtl)
                                if (p != lastTick) { lastTick = p; pf.haptic(view) }
                                c.consume()
                            } while (ev.changes.any { it.pressed })
                            val target = FlipMath.pageAt(drag ?: 0f, count, rtl)
                            drag = null
                            if (target != state.currentPage) onJump(target)
                        }
                    },
            ) {
                val cy = size.height / 2
                drawRoundRect(track, Offset(0f, cy - 2.dp.toPx()), Size(size.width, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                val x = frac * size.width
                val readFrom = if (rtl) size.width else 0f
                drawRoundRect(accent.copy(alpha = 0.55f), Offset(minOf(readFrom, x), cy - 2.dp.toPx()), Size(kotlin.math.abs(x - readFrom), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                drawCircle(accent, 8.dp.toPx(), Offset(x, cy))
            }
        }
    }
}

/** Thumbnail of [page] on a small stack of pages with the page number. */
@Composable
private fun PageBubble(cache: PageSnapshotCache, scope: CoroutineScope, state: ReaderState, page: Int) {
    val (pw, ph) = state.pageSizes[page] ?: (595f to 842f)
    val aspect = (pw / ph.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
    val density = LocalDensity.current
    val wPx = with(density) { THUMB_W.dp.toPx() }.roundToInt()
    val hPx = (wPx / aspect).roundToInt()
    LaunchedEffect(page) { cache.request(scope, page, wPx, hPx) }
    val v = cache.version.intValue
    val bmp = remember(page, v) { cache.peek(page, wPx, hPx) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(THUMB_W.dp, (THUMB_W / aspect).dp.coerceAtMost(200.dp))) {
                // page stack behind the thumbnail
                for (k in 2 downTo 1) Box(Modifier.matchParentSize().absoluteOffset(x = (k * 3).dp, y = (k * 3).dp).rotate(k * 1.5f)) {
                    Surface(Modifier.fillMaxSize(), color = Color(0xFFEDEAE3), shape = RoundedCornerShape(2.dp)) {}
                }
                Surface(Modifier.fillMaxSize(), color = Color.White, shape = RoundedCornerShape(2.dp)) {
                    bmp?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.page_of, page + 1, state.pageCount), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Riffle / fast multi-page flip preview: the target page on a fanned stack plus the number of pages moved. */
@Composable
fun StackPreview(state: ReaderState, cache: PageSnapshotCache, scope: CoroutineScope, map: SpreadMap, targetItem: Int, fromItem: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val page = map.firstPage(targetItem).coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
        val moved = kotlin.math.abs(map.firstPage(targetItem) - map.firstPage(fromItem))
        PageBubble(cache, scope, state, page)
        Spacer(Modifier.height(6.dp))
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(stringResource(R.string.pf_pages_flip, moved), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
