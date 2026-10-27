package com.smartdocs.editor.ui.signature

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Signature box as fractions (0..1) of the page as displayed: left, top, width, height. */
data class PlaceBox(val l: Float, val t: Float, val w: Float, val h: Float) {
    fun clamped(): PlaceBox {
        val cw = w.coerceIn(0.03f, 1f); val ch = h.coerceIn(0.02f, 1f)
        return PlaceBox(l.coerceIn(0f, 1f - cw), t.coerceIn(0f, 1f - ch), cw, ch)
    }

    companion object {
        /** The four quick positions of the old corner chips (190×60 pt box on an A4-ish page, 36/48 pt margins). */
        fun corner(i: Int): PlaceBox = when (i) {
            0 -> PlaceBox(0.62f, 0.86f, 0.32f, 0.08f)   // bottom right
            1 -> PlaceBox(0.06f, 0.86f, 0.32f, 0.08f)   // bottom left
            2 -> PlaceBox(0.62f, 0.06f, 0.32f, 0.08f)   // top right
            else -> PlaceBox(0.06f, 0.06f, 0.32f, 0.08f)
        }
    }
}

private enum class DragMode { MOVE, RESIZE, DRAW }

/**
 * Nitro "place / draw the signature anywhere": a page preview where the signature box can be dragged, resized
 * with its corner handle, or drawn anew by dragging on an empty area. [taken] are boxes of existing signature
 * fields on that page (shown outlined). Returns the 0-based page and the box.
 */
@Composable
fun SigPlacementDialog(
    file: File, startPage: Int, initial: PlaceBox?, password: String? = null,
    taken: (page: Int) -> List<PlaceBox> = { emptyList() },
    onDone: (Int, PlaceBox) -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(startPage) }
    var count by remember { mutableIntStateOf(1) }
    var box by remember { mutableStateOf(initial ?: PlaceBox.corner(0)) }
    val bmp by produceState<Bitmap?>(null, file, page) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val s = PdfSession.open(ctx, file, password)
                try {
                    count = s.pageCount
                    val p = page.coerceIn(0, s.pageCount - 1)
                    val (w, h) = s.pageSize(p)
                    s.render(p, 900, (900 * h / w).toInt().coerceAtLeast(1)).copy(Bitmap.Config.ARGB_8888, false).also { s.invalidateCache() }
                } finally { s.closeAsync() }
            }.getOrNull()
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val handle = with(LocalDensity.current) { 28.dp.toPx() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ns_place_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ns_place_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) }
                    Text(stringResource(R.string.ns_page_of, page + 1, count))
                    IconButton(onClick = { if (page < count - 1) page++ }, enabled = page < count - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
                    Spacer(Modifier.weight(1f))
                    listOf(R.string.ns_corner_br, R.string.ns_corner_bl, R.string.ns_corner_tr, R.string.ns_corner_tl).forEachIndexed { i, s ->
                        TextButton(onClick = { box = PlaceBox.corner(i) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(stringResource(s), style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b == null) CircularProgressIndicator()
                    else Box(Modifier.aspectRatio(b.width.toFloat() / b.height)) {
                        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        val others = remember(page) { taken(page) }
                        Canvas(Modifier.matchParentSize().pointerInput(page) {
                            var mode = DragMode.DRAW
                            var anchor = Offset.Zero
                            detectDragGestures(
                                onDragStart = { o ->
                                    val W = size.width.toFloat(); val H = size.height.toFloat()
                                    val r = box
                                    val right = (r.l + r.w) * W; val bottom = (r.t + r.h) * H
                                    mode = when {
                                        kotlin.math.abs(o.x - right) < handle && kotlin.math.abs(o.y - bottom) < handle -> DragMode.RESIZE
                                        o.x in r.l * W..right && o.y in r.t * H..bottom -> DragMode.MOVE
                                        else -> DragMode.DRAW
                                    }
                                    anchor = o
                                    if (mode == DragMode.DRAW) box = PlaceBox(o.x / W, o.y / H, 0.03f, 0.02f).clamped()
                                },
                                onDrag = { change, d ->
                                    change.consume()
                                    val W = size.width.toFloat(); val H = size.height.toFloat()
                                    box = when (mode) {
                                        DragMode.MOVE -> box.copy(l = box.l + d.x / W, t = box.t + d.y / H)
                                        DragMode.RESIZE -> box.copy(w = box.w + d.x / W, h = box.h + d.y / H)
                                        DragMode.DRAW -> {
                                            val p = change.position
                                            PlaceBox(minOf(anchor.x, p.x) / W, minOf(anchor.y, p.y) / H, kotlin.math.abs(p.x - anchor.x) / W, kotlin.math.abs(p.y - anchor.y) / H)
                                        }
                                    }.clamped()
                                },
                            )
                        }) {
                            others.forEach { o -> drawRect(Color.Gray, Offset(o.l * size.width, o.t * size.height), Size(o.w * size.width, o.h * size.height), style = Stroke(2f)) }
                            val tl = Offset(box.l * size.width, box.t * size.height); val sz = Size(box.w * size.width, box.h * size.height)
                            drawRect(accent.copy(alpha = 0.18f), tl, sz)
                            drawRect(accent, tl, sz, style = Stroke(3f))
                            drawCircle(accent, radius = 10f, center = Offset(tl.x + sz.width, tl.y + sz.height))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { onDone(page, box.clamped()) }, enabled = bmp != null) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}
