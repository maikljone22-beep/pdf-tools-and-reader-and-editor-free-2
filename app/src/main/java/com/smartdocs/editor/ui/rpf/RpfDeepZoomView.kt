package com.smartdocs.editor.ui.rpf

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.rpf.Frac
import com.smartdocs.editor.core.rpf.ViewMath
import com.smartdocs.editor.ui.nve.NveZoom
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private class DeepShot(val bmp: Bitmap, val clip: Frac)

/**
 * reader-prefs: the deep-zoom view (hook over the page area of ReaderScreen). Only the part of the page on screen is
 * rendered (PDFium renders that region of the page at full scale), so text stays sharp up to 6400 %. Pinch and drag
 * move around; zooming out below the page view's limit, double tap, × or Back return to the normal view.
 */
@Composable
fun RpfDeepZoomView(s: ReaderState) {
    val r = RpfState.of(s)
    val d = r.deep ?: return
    val session = s.session ?: return
    BackHandler { RpfDeepZoom.exit(s) }
    var vp by remember { mutableStateOf(IntSize.Zero) }
    var shot by remember { mutableStateOf<DeepShot?>(null) }
    val (pw, ph) = s.pageSizes[d.page] ?: (595f to 842f)
    val pxPerPt = NveZoom.pxPerPt(s)
    val night = s.nightMode
    LaunchedEffect(d, vp, night, s.renderVersion) {
        if (vp == IntSize.Zero) return@LaunchedEffect
        if (shot != null) delay(120)       // gestures move the last shot; render when they pause
        val scale = d.pct / 100f * pxPerPt
        val clip = ViewMath.deepWindow(pw, ph, scale, d.cx, d.cy, vp.width.toFloat(), vp.height.toFloat()).second
        val bmp = runCatching {
            session.renderRegion(d.page, (pw * scale).roundToInt(), (ph * scale).roundToInt(), RectF(clip.left, clip.top, clip.right, clip.bottom), night)
        }.getOrNull()
        if (bmp != null) shot = DeepShot(bmp, clip)
    }
    fun move(centroid: Offset, pan: Offset, zoom: Float) {
        val cur = r.deep ?: return
        val scale = cur.pct / 100f * pxPerPt
        val pct2 = (cur.pct * zoom).coerceAtMost(ViewMath.DEEP_MAX_PCT)
        if (pct2 < cur.minPct) { RpfDeepZoom.exit(s); return }
        val s2 = pct2 / 100f * pxPerPt
        val dx = centroid.x - vp.width / 2f; val dy = centroid.y - vp.height / 2f
        // the page point under the centroid stays there; then the drag moves the window
        val px = cur.cx + dx / (pw * scale); val py = cur.cy + dy / (ph * scale)
        val cx = px - dx / (pw * s2) - pan.x / (pw * s2); val cy = py - dy / (ph * s2) - pan.y / (ph * s2)
        val c = ViewMath.deepWindow(pw, ph, s2, cx, cy, vp.width.toFloat(), vp.height.toFloat()).first
        r.deep = cur.copy(pct = pct2, cx = c.first, cy = c.second)
    }
    Box(
        Modifier.fillMaxSize().background(if (night) Color(0xFF121212) else Color(0xFF808080)).onSizeChanged { vp = it }
            .pointerInput(Unit) { detectTransformGestures { c, pan, zoom, _ -> move(c, pan, zoom) } }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { RpfDeepZoom.exit(s) }) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sh = shot ?: return@Canvas
            val scale = d.pct / 100f * pxPerPt
            val win = ViewMath.deepWindow(pw, ph, scale, d.cx, d.cy, size.width, size.height).second
            val fw = pw * scale; val fh = ph * scale
            val left = (sh.clip.left - win.left) * fw; val top = (sh.clip.top - win.top) * fh
            drawImage(sh.bmp.asImageBitmap(), dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize((sh.clip.width * fw).roundToInt().coerceAtLeast(1), (sh.clip.height * fh).roundToInt().coerceAtLeast(1)))
        }
        Surface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = { move(Offset(vp.width / 2f, vp.height / 2f), Offset.Zero, 0.5f) }) { Icon(Icons.Default.ZoomOut, stringResource(R.string.rpf_zoom_out)) }
                Text(stringResource(R.string.rpf_deep_zoom, d.pct.roundToInt(), d.page + 1), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { move(Offset(vp.width / 2f, vp.height / 2f), Offset.Zero, 2f) }, enabled = d.pct < ViewMath.DEEP_MAX_PCT) { Icon(Icons.Default.ZoomIn, stringResource(R.string.rpf_zoom_in)) }
                IconButton(onClick = { RpfDeepZoom.exit(s) }) { Icon(Icons.Default.Close, stringResource(R.string.rpf_deep_exit)) }
            }
        }
    }
}
