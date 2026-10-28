package com.smartdocs.editor.ui.nve

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.TextSelection
import com.smartdocs.editor.ui.view5.V5State
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Geometry of a visible page: layout px inside [VisiblePage.rect] <-> PDF points (same maths as PageCanvas). */
internal fun geometryOf(s: ReaderState, vp: VisiblePage): PageGeometry {
    val (pw, ph) = s.pageSizes[vp.page] ?: (595f to 842f)
    val scale = vp.rect.width / (if (s.rotation % 180 == 0) pw else ph).coerceAtLeast(1f)
    val f = s.pageFrames[vp.page]
    return PageGeometry(pw, ph, scale, s.rotation, f?.rotate ?: 0, f?.cropX ?: 0f, f?.cropY ?: 0f)
}

/** Page under a viewport point, with the point in PDF space. */
internal fun pdfAt(s: ReaderState, p: Offset): Triple<Int, Float, Float>? {
    val vp = NveState.of(s).visiblePages().firstOrNull { it.rect.contains(p) } ?: return null
    val g = geometryOf(s, vp)
    val (x, y) = g.layoutToPdf(Offset(p.x - vp.rect.left, p.y - vp.rect.top))
    return Triple(vp.page, x, y)
}

/**
 * Overlays over the page area (hook next to View5PageOverlays in ReaderScreen): the rectangle-drag modes (marquee
 * zoom in/out, column selection, area snapshot), placing pasted text, page-anchored guides, and a mode pill.
 */
@Composable
fun NvePageOverlays(s: ReaderState) {
    val n = NveState.of(s)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    StatusPill(s, n)
    val mode = n.dragMode ?: return
    // tabs audit CM-10: an active annotation tool gets the touches, not a left-over View drag mode
    if (s.tool != null) return
    var start by remember(mode) { mutableStateOf<Offset?>(null) }
    var end by remember(mode) { mutableStateOf<Offset?>(null) }
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    Box(Modifier.fillMaxSize()
        .pointerInput(mode) {
            detectTapGestures { p ->
                when (mode) {
                    NveDrag.ZOOM_IN, NveDrag.ZOOM_OUT -> NveZoom.zoomToRect(s, Rect(p, Size(1f, 1f)), out = mode == NveDrag.ZOOM_OUT)
                    NveDrag.PLACE -> placePaste(s, p)
                    else -> {}
                }
            }
        }
        .pointerInput(mode) {
            detectDragGestures(
                onDragStart = { p -> start = p; end = p },
                onDragEnd = {
                    val a = start; val b = end
                    if (a != null && b != null) {
                        val r = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
                        when (mode) {
                            NveDrag.ZOOM_IN, NveDrag.ZOOM_OUT -> NveZoom.zoomToRect(s, r, out = mode == NveDrag.ZOOM_OUT)
                            NveDrag.COLUMN_SELECT -> scope.launch { columnSelect(s, r); n.dragMode = null }
                            NveDrag.SNAPSHOT -> scope.launch { snapshot = areaSnapshot(s, r) }
                            NveDrag.PLACE -> placePaste(s, r.center)
                        }
                    }
                    start = null; end = null
                },
                onDragCancel = { start = null; end = null },
            ) { ch, d -> ch.consume(); end = (end ?: ch.position) + d }
        }) {
        val a = start; val b = end
        if (a != null && b != null) Canvas(Modifier.fillMaxSize()) {
            val tl = Offset(min(a.x, b.x), min(a.y, b.y)); val sz = Size(abs(a.x - b.x), abs(a.y - b.y))
            drawRect(Color(0x332196F3), tl, sz)
            drawRect(Color(0xFF1976D2), tl, sz, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        }
        Surface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f)) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(when (mode) {
                    NveDrag.ZOOM_IN -> R.string.nv_marquee_hint; NveDrag.ZOOM_OUT -> R.string.nv_marquee_out_hint
                    NveDrag.COLUMN_SELECT -> R.string.nv_column_hint; NveDrag.SNAPSHOT -> R.string.nv_snapshot_hint
                    NveDrag.PLACE -> R.string.nv_place_hint
                }), style = MaterialTheme.typography.labelMedium)
                if (mode == NveDrag.ZOOM_IN || mode == NveDrag.ZOOM_OUT) IconButton(onClick = { n.dragMode = if (mode == NveDrag.ZOOM_IN) NveDrag.ZOOM_OUT else NveDrag.ZOOM_IN }) {
                    Icon(if (mode == NveDrag.ZOOM_IN) Icons.Default.ZoomOut else Icons.Default.ZoomIn, stringResource(R.string.nv_marquee_toggle))
                }
                IconButton(onClick = { n.dragMode = null; n.pasteText = null }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
        }
    }
    snapshot?.let { bmp -> SnapshotResultDialog(s, bmp) { snapshot = null; n.dragMode = null } }
    @Suppress("UNUSED_VARIABLE") val unusedCtx = ctx
}

private fun placePaste(s: ReaderState, p: Offset) {
    val n = NveState.of(s)
    val text = n.pasteText ?: return
    val (page, x, y) = pdfAt(s, p) ?: return
    val pageW = s.pageSizes[page]?.first ?: 595f
    val rtl = text.any { com.smartdocs.editor.core.nve.TextStats.isArabic(it) }
    s.addAnnot(s.newAnnot(AnnotKind.FREETEXT, page, rects = listOf(com.smartdocs.editor.ui.reader.textRectAt(x, y, text, s.fontSize, pageW, rtl)), text = text, color = 0x212121))
    n.pasteText = null; n.dragMode = null
}

/** Column / rectangle selection: only the characters inside [r], line by line. */
private suspend fun columnSelect(s: ReaderState, r: Rect) {
    val n = NveState.of(s)
    val vp = n.visiblePages().firstOrNull { it.rect.overlaps(r) } ?: return
    val g = geometryOf(s, vp)
    val local = Rect(r.left - vp.rect.left, r.top - vp.rect.top, r.right - vp.rect.left, r.bottom - vp.rect.top)
    val pr = PageGeometry.normPdf(g.layoutRectToPdf(local))
    val boxes = s.charBoxes(vp.page); val text = s.pageText(vp.page)
    val idx = boxes.indices.filter { i ->
        val b = PageGeometry.normPdf(boxes[i]); val cx = (b.left + b.right) / 2f; val cy = (b.top + b.bottom) / 2f
        i < text.length && b.width() > 0.01f && cx in pr.left..pr.right && cy in pr.bottom..pr.top
    }
    if (idx.isEmpty()) return
    val lines = ArrayList<MutableList<Int>>()
    var lastY = Float.NaN
    for (i in idx) {
        val b = PageGeometry.normPdf(boxes[i]); val y = (b.top + b.bottom) / 2f; val h = (b.top - b.bottom).coerceAtLeast(1f)
        if (lastY.isNaN() || abs(y - lastY) > h * 0.6f) { lines.add(mutableListOf(i)); lastY = y } else lines.last().add(i)
    }
    val sb = StringBuilder(); val rects = ArrayList<RectF>()
    lines.forEachIndexed { k, l ->
        if (k > 0) sb.append('\n')
        var prev = -2
        l.forEach { i -> if (prev >= 0 && i != prev + 1 && text.getOrNull(i - 1) == ' ') sb.append(' '); sb.append(text[i]); prev = i }
        val bs = l.map { PageGeometry.normPdf(boxes[it]) }
        rects.add(RectF(bs.minOf { it.left }, bs.maxOf { it.top }, bs.maxOf { it.right }, bs.minOf { it.bottom }))
    }
    s.selection = TextSelection(vp.page, idx.first(), idx.last(), rects, sb.toString())
}

/** Render the area [r] (viewport px) of the page under it at 2x screen resolution. */
private suspend fun areaSnapshot(s: ReaderState, r: Rect): Bitmap? {
    if (!s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) return null     // tabs audit CM-32: same rule as the Comment snapshot tool
    val n = NveState.of(s)
    val vp = n.visiblePages().firstOrNull { it.rect.overlaps(r) } ?: return null
    val sess = s.session ?: return null
    val g = geometryOf(s, vp)
    val clipLayout = Rect(max(r.left, vp.rect.left) - vp.rect.left, max(r.top, vp.rect.top) - vp.rect.top, min(r.right, vp.rect.right) - vp.rect.left, min(r.bottom, vp.rect.bottom) - vp.rect.top)
    // layout -> screen (unrotated page bitmap) fractions
    val a = g.layoutToScreen(Offset(clipLayout.left, clipLayout.top)); val b = g.layoutToScreen(Offset(clipLayout.right, clipLayout.bottom))
    val fx0 = min(a.x, b.x) / g.bitmapW; val fx1 = max(a.x, b.x) / g.bitmapW
    val fy0 = min(a.y, b.y) / g.bitmapH; val fy1 = max(a.y, b.y) / g.bitmapH
    val k = 2f
    return runCatching { sess.renderRegion(vp.page, (g.bitmapW * k).toInt(), (g.bitmapH * k).toInt(), RectF(fx0, fy0, fx1, fy1), false) }.getOrNull()
}

/** A whole page rendered at about [maxPx] on its long side (page snapshot). */
internal suspend fun pageSnapshot(s: ReaderState, page: Int, maxPx: Int = 2400): Bitmap? {
    if (!s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) return null     // tabs audit CM-32
    val sess = s.session ?: return null
    val (w, h) = s.pageSize(page)
    val k = maxPx / max(w, h).coerceAtLeast(1f)
    return runCatching { sess.render(page, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), false, true) }.getOrNull()
}

/** Put [bmp] on the clipboard (PNG through the app FileProvider) and return the file. */
internal fun copyImageToClipboard(ctx: Context, s: ReaderState, bmp: Bitmap): java.io.File? = runCatching {
    val f = s.app.documents.newTemp("snapshot", "png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = s.app.documents.shareUri(f)
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "snapshot", uri))
    f
}.getOrNull()

/** After an area / page snapshot: copy, paste back onto a page (image annotation), save to Pictures, share. */
@Composable
internal fun SnapshotResultDialog(s: ReaderState, bmp: Bitmap, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nv_snapshot_title)) },
        text = {
            Column {
                androidx.compose.foundation.Image(bmp.asImageBitmapSafe(), null, Modifier.fillMaxWidth().heightIn(max = 260.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.nv_snapshot_size, bmp.width, bmp.height), style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { if (!s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) return@TextButton; copyImageToClipboard(ctx, s, bmp); android.widget.Toast.makeText(ctx, R.string.nv_copied, android.widget.Toast.LENGTH_SHORT).show(); onDismiss() }) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nv_copy_image))
                }
                TextButton(onClick = {
                    // paste back: the reader's image tool places it (drag / resize, then done) as a stamp image annotation
                    s.pendingImage = bmp; s.tool = com.smartdocs.editor.ui.reader.ReaderTool.IMAGE; s.selection = null; onDismiss()
                }) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nv_paste_back)) }
                TextButton(onClick = {
                    if (!s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) return@TextButton
                    scope.launch {
                        val ok = saveBitmapToPictures(ctx, bmp, "SmartDocs_snapshot_" + System.currentTimeMillis())
                        android.widget.Toast.makeText(ctx, if (ok) R.string.nv_saved_pictures else R.string.nv_failed, android.widget.Toast.LENGTH_SHORT).show(); onDismiss()
                    }
                }) { Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nv_save_png)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

internal fun Bitmap.asImageBitmapSafe(): androidx.compose.ui.graphics.ImageBitmap = asImageBitmap()

internal suspend fun saveBitmapToPictures(ctx: Context, bmp: Bitmap, name: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartDocs")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
    }.getOrDefault(false)
}

// ---------------------------------------------------------------- guides, grid, rulers (page anchored)

@Composable
private fun GuideLines(s: ReaderState, n: NveState) {
    Canvas(Modifier.fillMaxSize()) {
        n.visiblePages().forEach { vp ->
            val g = geometryOf(s, vp)
            val (pw, ph) = s.pageSizes[vp.page] ?: (595f to 842f)
            n.guidesX.forEach { x ->
                val a = g.pdfToLayout(x, 0f); val b = g.pdfToLayout(x, ph)
                drawLine(Color(0xFF00BCD4), Offset(vp.rect.left + a.x, vp.rect.top + a.y), Offset(vp.rect.left + b.x, vp.rect.top + b.y), 1.5f)
            }
            n.guidesY.forEach { yTop ->
                val y = ph - yTop
                val a = g.pdfToLayout(0f, y); val b = g.pdfToLayout(pw, y)
                drawLine(Color(0xFF00BCD4), Offset(vp.rect.left + a.x, vp.rect.top + a.y), Offset(vp.rect.left + b.x, vp.rect.top + b.y), 1.5f)
            }
        }
    }
}

/**
 * Grid and rulers drawn by View5PageOverlays (hook): page-anchored (origin = page top-left) when the page geometry is
 * known, in the chosen unit, with colour, subdivisions, offsets, ruler orientation and opacity. Tapping a ruler
 * band adds a guide there; the grid sheet lists / clears guides.
 */
@Composable
fun NveGridRulers(s: ReaderState, grid: Boolean, rulers: Boolean) {
    val n = NveState.of(s)
    val v = V5State.of(s)
    val label = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = 0xFF37474F.toInt() } }
    val band = 28f
    val pages = n.visiblePages()
    val first = pages.firstOrNull()
    Canvas(Modifier.fillMaxSize()) {
        val stepPt = v.gridMm * 72f / 25.4f
        if (grid) {
            val col = Color(0xFF000000 or n.gridColor.toLong())
            val targets = if (n.gridPageAnchored && pages.isNotEmpty()) pages else listOf(null)
            targets.forEach { vp ->
                val origin = vp?.rect?.topLeft ?: Offset.Zero
                val pxPerPt = if (vp != null) vp.rect.width / ((s.pageSizes[vp.page]?.let { if (s.rotation % 180 == 0) it.first else it.second }) ?: 595f) else
                    ((size.width - 16f) / (s.pageSizes[s.currentPage]?.first ?: 595f) * s.zoom)
                val area = vp?.rect ?: Rect(0f, 0f, size.width, size.height)
                val sub = n.gridSubdiv.coerceIn(1, 10)
                val step = (stepPt / sub * pxPerPt).coerceAtLeast(4f)
                val ox = origin.x + n.gridOffX * 72f / 25.4f * pxPerPt; val oy = origin.y + n.gridOffY * 72f / 25.4f * pxPerPt
                var x = ox; var i = 0
                while (x <= area.right) { if (x >= area.left) drawLine(col.copy(alpha = if (i % sub == 0) 0.4f else 0.18f), Offset(x, max(area.top, 0f)), Offset(x, min(area.bottom, size.height)), if (i % sub == 0) 1.5f else 1f); x += step; i++ }
                var y = oy; i = 0
                while (y <= area.bottom) { if (y >= area.top) drawLine(col.copy(alpha = if (i % sub == 0) 0.4f else 0.18f), Offset(max(area.left, 0f), y), Offset(min(area.right, size.width), y), if (i % sub == 0) 1.5f else 1f); y += step; i++ }
            }
        }
        if (rulers) {
            val origin = first?.rect?.topLeft ?: Offset.Zero
            val pxPerPt = first?.let { it.rect.width / ((s.pageSizes[it.page]?.let { sz -> if (s.rotation % 180 == 0) sz.first else sz.second }) ?: 595f) } ?: 1f
            val unitPx = n.unit.ptPerUnit * pxPerPt
            val minor = when (n.unit) { NveUnit.MM -> 1f; NveUnit.CM -> 0.1f; NveUnit.IN -> 0.125f; NveUnit.PT -> 10f; NveUnit.PICA -> 1f }
            val major = when (n.unit) { NveUnit.MM -> 10f; NveUnit.CM -> 1f; NveUnit.IN -> 1f; NveUnit.PT -> 72f; NveUnit.PICA -> 6f }
            val bg = Color(0xFFFFFDE7).copy(alpha = n.rulerOpacity)
            if (n.rulerHorizontal) {
                drawRect(bg, Offset.Zero, Size(size.width, band))
                val stepPx = minor * unitPx
                if (stepPx > 0.5f) {
                    var k = kotlin.math.floor(-origin.x / stepPx).toInt()
                    while (origin.x + k * stepPx < size.width) {
                        val x = origin.x + k * stepPx; val value = k * minor
                        val isMajor = abs(value / major - Math.round(value / major)) < 1e-3
                        if (stepPx >= 2.5f || isMajor) drawLine(Color(0xFF455A64), Offset(x, 0f), Offset(x, if (isMajor) band else band * 0.4f), 1f)
                        if (isMajor && k != 0) drawContext.canvas.nativeCanvas.drawText(fmt(value), x + 3f, band - 4f, label)
                        k++
                    }
                }
                drawContext.canvas.nativeCanvas.drawText(n.unit.label, 2f, band - 4f, label)
            }
            if (n.rulerVertical) {
                drawRect(bg, Offset(0f, if (n.rulerHorizontal) band else 0f), Size(band, size.height))
                val stepPx = minor * unitPx
                if (stepPx > 0.5f) {
                    var k = kotlin.math.floor(-origin.y / stepPx).toInt()
                    while (origin.y + k * stepPx < size.height) {
                        val y = origin.y + k * stepPx; val value = k * minor
                        val isMajor = abs(value / major - Math.round(value / major)) < 1e-3
                        if (y > band && (stepPx >= 2.5f || isMajor)) drawLine(Color(0xFF455A64), Offset(0f, y), Offset(if (isMajor) band else band * 0.4f, y), 1f)
                        if (isMajor && k != 0 && y > band) drawContext.canvas.nativeCanvas.drawText(fmt(value), 3f, y - 3f, label)
                        k++
                    }
                }
            }
        }
    }
    if (rulers && first != null) {
        val density = LocalDensity.current
        val bandDp = with(density) { band.toDp() }
        val g = geometryOf(s, first)
        // tap on the top ruler: vertical guide at that x; on the left ruler: horizontal guide at that y
        if (n.rulerHorizontal) Box(Modifier.fillMaxWidth().height(bandDp).pointerInput(first) {
            detectTapGestures { p -> val (x, _) = g.layoutToPdf(Offset(p.x - first.rect.left, 0f)); n.guidesX.add(x); n.guidesOn = true; n.saveView() }
        })
        if (n.rulerVertical) Box(Modifier.fillMaxHeight().width(bandDp).padding(top = bandDp).pointerInput(first) {
            detectTapGestures { p ->
                val y0 = p.y + band - first.rect.top
                val (_, y) = g.layoutToPdf(Offset(0f, y0))
                val ph = s.pageSizes[first.page]?.second ?: 842f
                n.guidesY.add(ph - y); n.guidesOn = true; n.saveView()
            }
        })
    }
}

private fun fmt(v: Float): String = if (abs(v - Math.round(v)) < 1e-3) Math.round(v).toString() else String.format(java.util.Locale.US, "%.1f", v)

/** Snap a PDF point of [page] to the grid (and guides) when "snap to grid" is on. */
object NveGridSnap {
    fun snap(s: ReaderState, x: Float, y: Float, page: Int = s.currentPage): Pair<Float, Float> {
        val n = NveState.of(s)
        if (!n.snapToGrid) return x to y
        val v = V5State.of(s)
        val step = (v.gridMm * 72f / 25.4f / n.gridSubdiv.coerceIn(1, 10)).coerceAtLeast(1f)
        val ph = s.pageSizes[page]?.second ?: 842f
        val ox = n.gridOffX * 72f / 25.4f; val oy = n.gridOffY * 72f / 25.4f
        // grid lines run from the page top-left (as drawn): x from the left edge, y from the top edge
        var sx = ox + Math.round((x - ox) / step) * step
        val top = ph - y
        var sTop = oy + Math.round((top - oy) / step) * step
        // guides win when closer than half a grid step
        n.guidesX.minByOrNull { abs(it - x) }?.let { if (abs(it - x) < step / 2) sx = it }
        n.guidesY.minByOrNull { abs(it - top) }?.let { if (abs(it - top) < step / 2) sTop = it }
        return sx to (ph - sTop)
    }

    /** Workbench hook: snap its PDF point type. */
    fun snapP(s: ReaderState, p: com.smartdocs.editor.core.view5.P, page: Int): com.smartdocs.editor.core.view5.P {
        if (!NveState.of(s).snapToGrid) return p
        val (x, y) = snap(s, p.x.toFloat(), p.y.toFloat(), page)
        return com.smartdocs.editor.core.view5.P(x.toDouble(), y.toDouble())
    }
}

// ---------------------------------------------------------------- mouse: Ctrl+wheel zoom, right-click menu

/**
 * Mouse support for the page area (hook: modifier on the page Box of ReaderScreen): Ctrl + wheel zooms around the
 * pointer, a right click opens [NveContextMenu] at the pointer.
 */
fun nveMouseModifier(s: ReaderState): Modifier = Modifier.pointerInput(s) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            when (ev.type) {
                PointerEventType.Scroll -> if (ev.keyboardModifiers.isCtrlPressed) {
                    val ch = ev.changes.firstOrNull() ?: continue
                    NveZoom.wheel(s, ch.scrollDelta.y, ch.position); ev.changes.forEach { it.consume() }
                }
                PointerEventType.Press -> if (ev.buttons.isSecondaryPressed) {
                    val ch = ev.changes.firstOrNull() ?: continue
                    NveState.of(s).contextMenuAt = ch.position; ev.changes.forEach { it.consume() }
                }
                PointerEventType.Move -> ev.changes.firstOrNull()?.let { if (it.type == androidx.compose.ui.input.pointer.PointerType.Mouse) NveState.of(s).cursor = it.position }
                PointerEventType.Exit -> NveState.of(s).cursor = null
                else -> {}
            }
        }
    }
}

/** Right-click (or long-press-free) context menu of the page area. */
@Composable
fun NveContextMenu(s: ReaderState, actions: com.smartdocs.editor.ui.reader.ReaderActions) {
    if (com.smartdocs.editor.ui.selmenu.SelHooks.ownsContextMenu()) return     // selmenu: drawn by SelMenuLayer from the shared registry
    val n = NveState.of(s)
    val at = n.contextMenuAt ?: return
    val density = LocalDensity.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(Modifier.offset(with(density) { at.x.toDp() }, with(density) { at.y.toDp() })) {
        DropdownMenu(true, { n.contextMenuAt = null }, offset = DpOffset.Zero) {
            val close = { n.contextMenuAt = null }
            s.selection?.let { sel ->
                CtxItem(R.string.copy_text, Icons.Default.ContentCopy, close) { if (s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) com.smartdocs.editor.ui.reader.ReaderIntents.copy(ctx, sel.text) }
                CtxItem(R.string.nv_copy_formatting, Icons.Default.FormatPaint, close) { scope.launch { NveSelectionOps.copyWithFormatting(ctx, s, sel) } }
                CtxItem(R.string.nv_copy_table, Icons.Default.TableChart, close) { scope.launch { NveSelectionOps.copyAsTable(ctx, s, sel) } }
                HorizontalDivider()
            }
            CtxItem(R.string.nv_select_all, Icons.Default.SelectAll, close) { scope.launch { NveSelectionOps.selectAll(s, s.currentPage) } }
            CtxItem(R.string.nv_paste, Icons.Default.ContentPaste, close) { NveClipboard.paste(ctx, s) }
            CtxItem(R.string.nv_page_snapshot, Icons.Default.PhotoCamera, close) { n.open(NveSurface.SNAPSHOT) }
            CtxItem(R.string.nv_marquee, Icons.Default.ZoomIn, close) { n.dragMode = NveDrag.ZOOM_IN }
            CtxItem(R.string.nv_zoom, Icons.Default.Search, close) { n.open(NveSurface.ZOOM) }
            CtxItem(R.string.go_to_page, Icons.Default.Numbers, close) { actions.goToPage() }
            CtxItem(R.string.rotate_view, Icons.Default.RotateRight, close) { s.rotation = (s.rotation + 90) % 360 }
            CtxItem(R.string.print, Icons.Default.Print, close) { actions.print() }
            CtxItem(R.string.tool_doc_info, Icons.Default.Info, close) { actions.docInfo() }
        }
    }
}

@Composable
private fun CtxItem(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, close: () -> Unit, run: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, onClick = { close(); run() })
}

/** Status read-out (Foxit status bar): zoom percentage while zooming and the mouse pointer's page coordinates. */
@Composable
private fun StatusPill(s: ReaderState, n: NveState) {
    val cur = n.cursor
    var showZoom by remember { mutableStateOf(false) }
    val z = s.zoom
    LaunchedEffect(z) { showZoom = true; kotlinx.coroutines.delay(1500); showZoom = false }
    val coords = cur?.let { pdfAt(s, it) }
    if (!showZoom && coords == null) return
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f), contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            val parts = ArrayList<String>()
            if (showZoom || coords != null) parts.add("${NveZoom.percent(s).toInt()}%")
            coords?.let { (p, x, y) ->
                val ph = s.pageSizes[p]?.second ?: 842f
                parts.add(String.format(java.util.Locale.US, "p%d  x %.1f  y %.1f %s", p + 1, x / n.unit.ptPerUnit, (ph - y) / n.unit.ptPerUnit, n.unit.label))
            }
            Text(parts.joinToString("  ·  "), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
