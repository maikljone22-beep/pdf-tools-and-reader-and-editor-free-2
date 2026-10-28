package com.smartdocs.editor.ui.nve.shapes

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit.EditIo
import com.smartdocs.editor.core.edit.StalePageException
import com.smartdocs.editor.core.nve.shapes.Gradient
import com.smartdocs.editor.core.nve.shapes.GradientStop
import com.smartdocs.editor.core.nve.shapes.NveShape
import com.smartdocs.editor.core.nve.shapes.NveShapes
import com.smartdocs.editor.core.nve.shapes.ShapeKind
import com.smartdocs.editor.core.nve.shapes.ShapeStyle
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.edit.EditController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Tools of the shape editor: [kind] null = select / gradient box. */
private enum class STool(val kind: ShapeKind?, val label: Int, val icon: ImageVector, val byPoints: Boolean = false) {
    SELECT(null, R.string.nh_tool_select, Icons.Default.TouchApp),
    LINE(ShapeKind.LINE, R.string.nh_line, Icons.Default.HorizontalRule),
    RECT(ShapeKind.RECT, R.string.nh_rect, Icons.Default.CropSquare),
    ROUND_RECT(ShapeKind.ROUND_RECT, R.string.nh_round_rect, Icons.Default.RoundedCorner),
    ELLIPSE(ShapeKind.ELLIPSE, R.string.nh_ellipse, Icons.Default.Circle),
    POLYLINE(ShapeKind.POLYLINE, R.string.nh_polyline, Icons.Default.Timeline, true),
    POLYGON(ShapeKind.POLYGON, R.string.nh_polygon, Icons.Default.ChangeHistory, true),
    BEZIER(ShapeKind.BEZIER, R.string.nh_bezier, Icons.Default.Gesture, true),
    CLOSED_BEZIER(ShapeKind.CLOSED_BEZIER, R.string.nh_closed_bezier, Icons.Default.Loop, true),
    FREE(ShapeKind.FREE, R.string.nh_free, Icons.Default.Draw),
    GRADIENT(ShapeKind.RECT, R.string.nh_gradient_box, Icons.Default.Gradient),
}

private val SHAPE_COLORS = listOf(0x000000, 0x424242, 0x9E9E9E, 0xFFFFFF, 0xD32F2F, 0xF57C00, 0xFBC02D, 0x388E3C, 0x00897B, 0x1976D2, 0x303F9F, 0x7B1FA2, 0xC2185B)
private val DASHES = listOf(emptyList(), listOf(6f, 4f), listOf(1f, 3f), listOf(8f, 3f, 1f, 3f))

/** Small button over the edit page (hook in EditModeHost) opening the shape editor for the current page. */
@Composable
fun ShapesFab(c: EditController, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    if (c.model != null && !c.editingText && c.image == null && c.insertAt == null && c.message == null && !c.tools5.multi) {
        ExtendedFloatingActionButton(onClick = { open = true }, modifier = modifier.padding(12.dp),
            icon = { Icon(Icons.Default.Category, null) }, text = { Text(stringResource(R.string.nh_shapes)) })
    }
    if (open) ShapeEditorDialog(c) { open = false }
}

/**
 * Full-screen editor of vector shapes and gradient objects written as page content ([NveShapes]). Every commit is
 * one undoable content edit of the reader's history (EditController.runNve -> applyEdit -> state.applyInPlace).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShapeEditorDialog(c: EditController, onClose: () -> Unit) {
    val state = c.state
    val page = c.page
    var tool by remember { mutableStateOf(STool.RECT) }
    var style by remember { mutableStateOf(ShapeStyle(stroke = 0x1976D2, fill = null, width = 2f)) }
    var radius by remember { mutableFloatStateOf(10f) }
    var shapes by remember { mutableStateOf<List<NveShape>>(emptyList()) }
    var selected by remember { mutableStateOf<NveShape?>(null) }
    var preview by remember { mutableStateOf<NveShape?>(null) }        // selected shape moved / resized, not saved yet
    val draft = remember { mutableStateListOf<Float>() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var showStyle by remember { mutableStateOf(false) }

    LaunchedEffect(page, state.renderVersion, state.file) {
        val f = state.file ?: return@LaunchedEffect
        shapes = runCatching { withContext(Dispatchers.IO) { PdfOps.load(f, state.password).use { NveShapes.list(it.getPage(page)) } } }.getOrDefault(emptyList())
        selected = selected?.let { s -> shapes.firstOrNull { it.id == s.id } }
        preview = null
    }

    fun commitAdd(s: NveShape) {
        c.runNve(R.string.nh_added) { src, out ->
            withContext(Dispatchers.IO) { EditIo.load(src, state.password).use { d -> NveShapes.add(d, d.getPage(page), s); EditIo.save(d, out) } }
        }
    }
    fun commitReplace(id: Int, s: NveShape?) {
        c.runNve(if (s == null) R.string.nh_deleted else R.string.nh_saved) { src, out ->
            withContext(Dispatchers.IO) {
                EditIo.load(src, state.password).use { d -> if (!NveShapes.replace(d, d.getPage(page), id, s)) throw StalePageException(); EditIo.save(d, out) }
            }
        }
        if (s == null) selected = null
    }
    fun styleTarget(): ShapeStyle = (preview ?: selected)?.style ?: style
    fun setStyle(st: ShapeStyle) {
        val sel = preview ?: selected
        if (sel != null) preview = sel.copy(style = st) else style = st
    }
    fun finishDraft() {
        val k = tool.kind ?: return
        if (draft.size >= 4) commitAdd(NveShape(0, k, draft.toList(), style, radius))
        draft.clear()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                // ---- top bar ----
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Text(stringResource(R.string.nh_shapes_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { c.undo() }, enabled = c.canUndo && !c.busy) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    IconButton(onClick = { c.redo() }, enabled = c.canRedo && !c.busy) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                    IconButton(onClick = { zoom = when (zoom) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 3f; else -> 1f } }) { Icon(Icons.Default.ZoomIn, stringResource(R.string.ed_zoom)) }
                    IconButton(onClick = { showStyle = !showStyle }) { Icon(Icons.Default.Palette, stringResource(R.string.nh_style)) }
                }
                // ---- tools ----
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    STool.entries.forEach { t ->
                        FilterChip(selected = tool == t, onClick = { draft.clear(); tool = t; if (t != STool.SELECT) { selected = null; preview = null } },
                            label = { Text(stringResource(t.label)) }, leadingIcon = { Icon(t.icon, null, Modifier.size(18.dp)) }, modifier = Modifier.padding(horizontal = 3.dp))
                    }
                }
                Text(stringResource(when { tool == STool.SELECT -> R.string.nh_hint_select; tool.byPoints -> R.string.nh_hint_points; else -> R.string.nh_hint_drag }),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                if (c.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                // ---- page ----
                Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    PageSurface(c, zoom, shapes, selected, preview, draft, tool, style, radius,
                        onSelect = { s -> selected = s; preview = null },
                        onPreview = { preview = it },
                        onDraftDone = { pts -> tool.kind?.let { k ->
                            val st = if (tool == STool.GRADIENT) style.copy(stroke = null, fill = null, gradient = style.gradient ?: Gradient()) else style
                            commitAdd(NveShape(0, k, pts, st, radius))
                        } })
                    c.message?.let { m -> Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(m) } }
                }
                // ---- bottom bars ----
                if (tool.byPoints && draft.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${draft.size / 2}", Modifier.weight(1f))
                    TextButton(onClick = { draft.clear() }) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { finishDraft() }, enabled = draft.size >= 4 && !c.busy) { Text(stringResource(R.string.nh_finish)) }
                }
                val sel = preview ?: selected
                if (sel != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { commitReplace(sel.id, null) }, enabled = !c.busy) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nh_delete)) }
                    TextButton(onClick = { commitAdd(sel.copy(id = 0).moved(12f, -12f)) }, enabled = !c.busy) { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nh_duplicate)) }
                    TextButton(onClick = { showStyle = true }) { Text(stringResource(R.string.nh_style)) }
                    Spacer(Modifier.width(8.dp))
                    if (preview != null) TextButton(onClick = { preview = null }) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { preview?.let { commitReplace(it.id, it) }; preview = null }, enabled = preview != null && !c.busy) { Text(stringResource(R.string.apply)) }
                }
                if (showStyle) StylePanel(styleTarget(), radius = (preview ?: selected)?.radius ?: radius,
                    showRadius = ((preview ?: selected)?.kind ?: tool.kind) == ShapeKind.ROUND_RECT,
                    onStyle = { setStyle(it) },
                    onRadius = { r -> val s2 = preview ?: selected; if (s2 != null) preview = s2.copy(radius = r) else radius = r })
            }
        }
    }
}

/** The page render + shapes overlay + gestures. Display space (points, y up) <-> px: x·scale, (H − y)·scale. */
@Composable
private fun PageSurface(
    c: EditController, zoom: Float, shapes: List<NveShape>, selected: NveShape?, preview: NveShape?, draft: MutableList<Float>,
    tool: STool, style: ShapeStyle, radius: Float,
    onSelect: (NveShape?) -> Unit, onPreview: (NveShape?) -> Unit, onDraftDone: (List<Float>) -> Unit,
) {
    val state = c.state
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pad = with(density) { 12.dp.toPx() }
        val m = c.model
        val size = m?.let { it.width to it.height } ?: state.pageSizes[c.page] ?: (595f to 842f)
        val pageW = size.first; val pageH = size.second
        val viewportW = constraints.maxWidth.toFloat().coerceAtLeast(100f)
        val scale = ((viewportW - 2 * pad) * zoom / pageW).coerceAtLeast(0.05f)
        val wPx = pageW * scale; val hPx = pageH * scale
        var bitmap by remember(c.page) { mutableStateOf<Bitmap?>(null) }
        val session = state.session
        LaunchedEffect(c.page, wPx.roundToInt(), session, state.renderVersion) {
            val s = session ?: return@LaunchedEffect
            if (s.isClosed) return@LaunchedEffect
            val k = (2600f / wPx).coerceAtMost(1f)
            try { bitmap = s.render(c.page, (wPx * k).roundToInt().coerceAtLeast(1), (hPx * k).roundToInt().coerceAtLeast(1), state.nightMode, true) }
            catch (e: CancellationException) { throw e } catch (ignored: Throwable) { }
        }
        val curShapes by rememberUpdatedState(shapes)
        val curSel by rememberUpdatedState(preview ?: selected)
        val curTool by rememberUpdatedState(tool)
        val curDraftDone by rememberUpdatedState(onDraftDone)
        val handle = with(density) { 26.dp.toPx() }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Box(Modifier.padding(with(density) { pad.toDp() }).size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() }).shadow(2.dp).background(Color.White)
                    .pointerInput(scale, pageH, c.busy) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (c.busy) return@awaitEachGesture
                            fun pt(o: Offset) = o.x / scale to pageH - o.y / scale
                            val (x0, y0) = pt(down.position)
                            val t = curTool
                            when {
                                t == STool.SELECT -> {
                                    val sel = curSel
                                    val b = sel?.bounds()
                                    val corner = b?.let { Offset(it[2] * scale, (pageH - it[1]) * scale) }
                                    val resizing = corner != null && (down.position - corner).getDistance() < handle
                                    val hit = if (resizing) sel else NveShapes.hit(curShapes.map { s -> if (s.id == sel?.id) sel else s }, x0, y0, 6f / scale.coerceAtLeast(0.1f) + 4f)
                                    if (hit == null) { onSelect(null); return@awaitEachGesture }
                                    down.consume()
                                    if (hit.id != sel?.id) onSelect(hit)
                                    val start = hit
                                    var moved = false
                                    var total = Offset.Zero
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        val d = ch.positionChange(); ch.consume()
                                        total += d
                                        if (total.getDistance() > 3f) moved = true
                                        if (!moved) continue
                                        val dx = total.x / scale; val dy = -total.y / scale
                                        onPreview(if (resizing) {
                                            val pts = start.xs to start.ys
                                            val l = pts.first.min(); val bt = pts.second.min(); val r = pts.first.max(); val tp = pts.second.max()
                                            start.fitted(l, min(bt + dy, tp - 2f), max(r + dx, l + 2f), tp)
                                        } else start.moved(dx, dy))
                                    }
                                }
                                t.byPoints -> {
                                    down.consume()
                                    var up: Offset = down.position
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        up = ch.position
                                        if (!ch.pressed) break
                                    }
                                    if ((up - down.position).getDistance() < 24f) { draft.add(x0); draft.add(y0) }
                                }
                                else -> {
                                    down.consume()
                                    draft.clear(); draft.add(x0); draft.add(y0)
                                    if (t != STool.FREE) { draft.add(x0); draft.add(y0) }
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        ch.consume()
                                        val (x, y) = pt(ch.position)
                                        if (t == STool.FREE) {
                                            val lx = draft[draft.size - 2]; val ly = draft[draft.size - 1]
                                            if (hypot(x - lx, y - ly) > 1.5f) { draft.add(x); draft.add(y) }
                                        } else { draft[2] = x; draft[3] = y }
                                    }
                                    val pts = draft.toList(); draft.clear()
                                    val big = if (t == STool.FREE) pts.size >= 6 else abs(pts[2] - pts[0]) > 2f || abs(pts[3] - pts[1]) > 2f
                                    if (big) curDraftDone(pts)
                                }
                            }
                        }
                    },
                ) {
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    val primary = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxSize()) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                        fun px(x: Float) = x * scale
                        fun py(y: Float) = (pageH - y) * scale
                        fun boxOf(s: NveShape, col: Color, w: Float, dashed: Boolean) {
                            val b = s.bounds()
                            drawRect(col, Offset(px(b[0]), py(b[3])), Size(px(b[2]) - px(b[0]), py(b[1]) - py(b[3])), style = Stroke(w, pathEffect = if (dashed) dash else null))
                        }
                        val sel = preview ?: selected
                        shapes.forEach { s -> if (s.id != sel?.id) boxOf(s, primary.copy(alpha = 0.45f), 1.5f, true) }
                        sel?.let { s ->
                            drawShape(s, scale, pageH)
                            boxOf(s, primary, 2.5f, false)
                            val b = s.bounds()
                            drawCircle(primary, 10.dp.toPx(), Offset(px(b[2]), py(b[1])))
                            drawCircle(Color.White, 4.dp.toPx(), Offset(px(b[2]), py(b[1])))
                        }
                        val k = tool.kind
                        if (k != null && draft.size >= 2) {
                            val st = if (tool == STool.GRADIENT) style.copy(stroke = 0x757575, gradient = style.gradient ?: Gradient()) else style
                            if (draft.size >= 4) drawShape(NveShape(0, k, draft.toList(), st, radius), scale, pageH)
                            for (i in 0 until draft.size / 2) drawCircle(primary, 4.dp.toPx(), Offset(px(draft[2 * i]), py(draft[2 * i + 1])))
                        }
                    }
                }
            }
        }
    }
}

/** Preview of [s] drawn from the same path operators the PDF gets ([NveShapes.pathOps]). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShape(s: NveShape, scale: Float, pageH: Float) {
    val (ops, closed) = NveShapes.pathOps(s)
    val path = Path()
    for (line in ops.lines()) {
        val t = line.trim().split(' ').filter { it.isNotEmpty() }
        if (t.isEmpty()) continue
        val v = t.dropLast(1).mapNotNull { it.toFloatOrNull() }
        fun x(i: Int) = v[i] * scale
        fun y(i: Int) = (pageH - v[i]) * scale
        when (t.last()) {
            "m" -> path.moveTo(x(0), y(1))
            "l" -> path.lineTo(x(0), y(1))
            "c" -> path.cubicTo(x(0), y(1), x(2), y(3), x(4), y(5))
            "re" -> { path.moveTo(x(0), y(1)); path.lineTo(x(0) + v[2] * scale, y(1)); path.lineTo(x(0) + v[2] * scale, y(1) - v[3] * scale); path.lineTo(x(0), y(1) - v[3] * scale); path.close() }
            "h" -> path.close()
        }
    }
    val st = s.style
    val alpha = st.opacity.coerceIn(0f, 1f)
    if (closed) {
        st.gradient?.let { g ->
            val xs = s.xs; val ys = s.ys
            val l = xs.min() * scale; val r = xs.max() * scale; val top = (pageH - ys.max()) * scale; val bottom = (pageH - ys.min()) * scale
            val stops = g.stops.sortedBy { it.offset }.map { it.offset to Color(0xFF000000 or it.color.toLong()) }.toTypedArray()
            val brush = if (g.radial) androidx.compose.ui.graphics.Brush.radialGradient(*stops, center = Offset(l + g.x0 * (r - l), bottom - g.y0 * (bottom - top)), radius = (g.r * max(r - l, bottom - top)).coerceAtLeast(1f))
                else androidx.compose.ui.graphics.Brush.linearGradient(*stops, start = Offset(l + g.x0 * (r - l), bottom - g.y0 * (bottom - top)), end = Offset(l + g.x1 * (r - l), bottom - g.y1 * (bottom - top)))
            drawPath(path, brush, alpha = alpha)
        } ?: st.fill?.let { drawPath(path, Color(0xFF000000 or it.toLong()), alpha = alpha) }
    }
    st.stroke?.let { col ->
        val dash = st.dash.takeIf { it.isNotEmpty() }?.let { d -> PathEffect.dashPathEffect((if (d.size % 2 == 1) d + d else d).map { (it * scale).coerceAtLeast(1f) }.toFloatArray()) }
        drawPath(path, Color(0xFF000000 or col.toLong()), alpha = alpha, style = Stroke((st.width * scale).coerceAtLeast(1f),
            miter = st.miter, cap = when (st.cap) { 1 -> StrokeCap.Round; 2 -> StrokeCap.Square; else -> StrokeCap.Butt },
            join = when (st.join) { 1 -> StrokeJoin.Round; 2 -> StrokeJoin.Bevel; else -> StrokeJoin.Miter }, pathEffect = dash))
    }
}

/** Stroke / fill / dash / cap / join / miter / opacity / radius / gradient controls. */
@Composable
private fun StylePanel(st: ShapeStyle, radius: Float, showRadius: Boolean, onStyle: (ShapeStyle) -> Unit, onRadius: (Float) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ColorRow(stringResource(R.string.nh_stroke), st.stroke) { onStyle(st.copy(stroke = it)) }
            ColorRow(stringResource(R.string.nh_fill), st.fill) { onStyle(st.copy(fill = it, gradient = if (it != null) null else st.gradient)) }
            Text(stringResource(R.string.nh_width, "%.1f".format(java.util.Locale.US, st.width)), style = MaterialTheme.typography.labelMedium)
            Slider(st.width, { onStyle(st.copy(width = (it * 2).roundToInt() / 2f)) }, valueRange = 0.5f..24f)
            Text(stringResource(R.string.nh_dash), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.nh_dash_solid, R.string.nh_dash_dashed, R.string.nh_dash_dotted, R.string.nh_dash_dashdot).forEachIndexed { i, l ->
                    FilterChip(st.dash == DASHES[i], { onStyle(st.copy(dash = DASHES[i])) }, label = { Text(stringResource(l)) })
                }
            }
            Text(stringResource(R.string.nh_cap), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.nh_cap_butt, R.string.nh_cap_round, R.string.nh_cap_square).forEachIndexed { i, l -> FilterChip(st.cap == i, { onStyle(st.copy(cap = i)) }, label = { Text(stringResource(l)) }) }
            }
            Text(stringResource(R.string.nh_join), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.nh_join_miter, R.string.nh_join_round, R.string.nh_join_bevel).forEachIndexed { i, l -> FilterChip(st.join == i, { onStyle(st.copy(join = i)) }, label = { Text(stringResource(l)) }) }
            }
            if (st.join == 0) {
                Text(stringResource(R.string.nh_miter, "%.1f".format(java.util.Locale.US, st.miter)), style = MaterialTheme.typography.labelMedium)
                Slider(st.miter, { onStyle(st.copy(miter = (it * 2).roundToInt() / 2f)) }, valueRange = 1f..20f)
            }
            Text(stringResource(R.string.nh_opacity, (st.opacity * 100).roundToInt()), style = MaterialTheme.typography.labelMedium)
            Slider(st.opacity, { onStyle(st.copy(opacity = it)) }, valueRange = 0.05f..1f)
            if (showRadius) {
                Text(stringResource(R.string.nh_radius, radius.roundToInt().toString()), style = MaterialTheme.typography.labelMedium)
                Slider(radius, { onRadius(it.roundToInt().toFloat()) }, valueRange = 0f..60f)
            }
            HorizontalDivider()
            GradientEditor(st.gradient) { g -> onStyle(st.copy(gradient = g, fill = if (g != null) null else st.fill)) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ColorRow(label: String, value: Int?, onChange: (Int?) -> Unit) {
    var hex by remember(value) { mutableStateOf(value?.let { "#%06X".format(it) } ?: "") }
    Text(label, style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(value == null, { onChange(null) }, label = { Text(stringResource(R.string.nh_none)) })
        Spacer(Modifier.width(4.dp))
        SHAPE_COLORS.forEach { col -> Swatch(col, value == col) { onChange(col) } }
        OutlinedTextField(hex, { v -> hex = v.take(7); v.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let(onChange) },
            singleLine = true, label = { Text(stringResource(R.string.nh_hex)) }, modifier = Modifier.width(150.dp).padding(start = 6.dp))
    }
}

@Composable
private fun Swatch(col: Int, sel: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(3.dp).size(if (sel) 30.dp else 26.dp).clip(CircleShape).background(Color(0xFF000000 or col.toLong()))
        .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape).clickable(onClick = onClick))
}

@Composable
private fun GradientEditor(g: Gradient?, onChange: (Gradient?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.nh_gradient), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Switch(g != null, { on -> onChange(if (on) Gradient() else null) })
    }
    if (g == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(!g.radial, { onChange(g.copy(radial = false)) }, label = { Text(stringResource(R.string.nh_linear)) })
        FilterChip(g.radial, { onChange(g.copy(radial = true, x0 = 0.5f, y0 = 0.5f)) }, label = { Text(stringResource(R.string.nh_radial)) })
    }
    if (!g.radial) {
        Text(stringResource(R.string.nh_angle), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(g.y0 == g.y1, { onChange(g.copy(x0 = 0f, y0 = 0.5f, x1 = 1f, y1 = 0.5f)) }, label = { Text(stringResource(R.string.nh_dir_lr)) })
            FilterChip(g.x0 == g.x1, { onChange(g.copy(x0 = 0.5f, y0 = 1f, x1 = 0.5f, y1 = 0f)) }, label = { Text(stringResource(R.string.nh_dir_tb)) })
            FilterChip(g.x0 != g.x1 && g.y0 != g.y1, { onChange(g.copy(x0 = 0f, y0 = 1f, x1 = 1f, y1 = 0f)) }, label = { Text(stringResource(R.string.nh_dir_diag)) })
        }
    } else {
        Slider(g.r, { onChange(g.copy(r = it)) }, valueRange = 0.1f..1f)
    }
    g.stops.forEachIndexed { i, s ->
        Text(stringResource(R.string.nh_stop, i + 1) + "  " + (s.offset * 100).roundToInt() + "%", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            SHAPE_COLORS.forEach { col -> Swatch(col, s.color == col) { onChange(g.copy(stops = g.stops.toMutableList().also { it[i] = s.copy(color = col) })) } }
            if (g.stops.size > 2) IconButton(onClick = { onChange(g.copy(stops = g.stops.filterIndexed { j, _ -> j != i })) }) { Icon(Icons.Default.Delete, stringResource(R.string.pc_cd_delete)) }
        }
        if (i != 0 && i != g.stops.size - 1) Slider(s.offset, { v -> onChange(g.copy(stops = g.stops.toMutableList().also { it[i] = s.copy(offset = v) })) }, valueRange = 0.01f..0.99f)
    }
    TextButton(onClick = {
        val sorted = g.stops.sortedBy { it.offset }
        val a = sorted[sorted.size - 2]; val b = sorted.last()
        onChange(g.copy(stops = (sorted + GradientStop((a.offset + b.offset) / 2f, 0xFBC02D)).sortedBy { it.offset }))
    }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nh_add_stop)) }
}
