package com.smartdocs.editor.ui.nve.imgedit

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class ETool(val label: Int, val icon: ImageVector) {
    PAN(R.string.nh_ie_pan, Icons.Default.PanTool),
    BRUSH(R.string.nh_ie_brush, Icons.Default.Brush),
    ERASER(R.string.nh_ie_eraser, Icons.Default.AutoFixOff),
    BUCKET(R.string.nh_ie_bucket, Icons.Default.FormatColorFill),
    PICKER(R.string.nh_ie_picker, Icons.Default.Colorize),
    SEL_RECT(R.string.nh_ie_sel_rect, Icons.Default.HighlightAlt),
    LASSO(R.string.nh_ie_lasso, Icons.Default.Gesture),
    POLY_LASSO(R.string.nh_ie_poly_lasso, Icons.Default.Timeline),
    WAND(R.string.nh_ie_wand, Icons.Default.AutoFixHigh),
    BURN(R.string.nh_ie_burn, Icons.Default.Brightness4),
    DODGE(R.string.nh_ie_dodge, Icons.Default.Brightness7),
    CLONE(R.string.nh_ie_clone, Icons.Default.ControlPointDuplicate),
    HEAL(R.string.nh_ie_heal, Icons.Default.Healing),
}

private enum class EPanel { NONE, FILTERS, LAYERS }

private val IE_COLORS = listOf(0x000000, 0xFFFFFF, 0x9E9E9E, 0xD32F2F, 0xF57C00, 0xFBC02D, 0x388E3C, 0x00897B, 0x1976D2, 0x303F9F, 0x7B1FA2, 0xC2185B, 0x795548)

/** Editable document of the image editor: layers, active layer, selection mask, floating paste. */
private class EDoc(base: Bitmap) {
    val w = base.width; val h = base.height
    val layers = mutableStateListOf(Layer(base))
    var active by mutableIntStateOf(0)
    var mask by mutableStateOf<ByteArray?>(null)
    var overlay by mutableStateOf<Bitmap?>(null)
    var floating by mutableStateOf<Bitmap?>(null)
    var floatX by mutableFloatStateOf(0f)
    var floatY by mutableFloatStateOf(0f)
    var clip: Bitmap? = null
    var clipX = 0; var clipY = 0
    /** Bumped after every pixel change so the canvas redraws. */
    var version by mutableIntStateOf(0)
    val undo = ArrayDeque<Snap>(); val redo = ArrayDeque<Snap>()
    val maxSteps = (220_000_000L / (w.toLong() * h * 4 * 2)).toInt().coerceIn(2, 15)

    class Snap(val layers: List<Layer>, val active: Int, val mask: ByteArray?)

    private fun snap() = Snap(layers.map { it.copy() }, active, mask?.copyOf())
    fun push() { undo.addLast(snap()); while (undo.size > maxSteps) undo.removeFirst(); redo.clear() }
    private fun restore(s: Snap) { layers.clear(); layers.addAll(s.layers); active = s.active.coerceIn(0, layers.size - 1); setMask(s.mask); version++ }
    fun undo() { val s = undo.removeLastOrNull() ?: return; redo.addLast(snap()); restore(s) }
    fun redo() { val s = redo.removeLastOrNull() ?: return; undo.addLast(snap()); restore(s) }
    fun setMask(m: ByteArray?) { mask = m; overlay = m?.let { ImageOps.maskOverlay(it, w, h) } }
    val cur: Layer get() = layers[active.coerceIn(0, layers.size - 1)]
    fun composite(): Bitmap = ImageOps.flatten(layers)
}

/**
 * In-app raster image editor (full-screen dialog). [source] null = "Draw new image" (blank canvas of a chosen size
 * and background). Selections (rectangle, lasso, polygonal lasso, magic wand), cut / copy / paste, brush (size,
 * opacity, flow, blend), eraser, paint bucket, eyedropper, burn / dodge, clone stamp, simple spot healing, layers
 * (opacity, blend, visibility, merge), stackable filters with live preview, flips, undo / redo, pinch zoom.
 * [onDone] receives the flattened result.
 */
@Composable
fun NveImageEditor(source: Bitmap?, onDone: (Bitmap) -> Unit, onCancel: () -> Unit) {
    var doc by remember { mutableStateOf<EDoc?>(null) }
    LaunchedEffect(source) {
        if (source != null) doc = EDoc(withContext(Dispatchers.Default) { fitWorking(source) })
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val d = doc
            when {
                d != null -> EditorBody(d, onDone, onCancel)
                source == null -> NewCanvasForm(onCreate = { doc = EDoc(it) }, onCancel = onCancel)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

/** Working copy: mutable ARGB, at most 4096 px on the longest side (memory). */
private fun fitWorking(b: Bitmap): Bitmap {
    val m = max(b.width, b.height)
    val src = if (m > 4096) Bitmap.createScaledBitmap(b, (b.width * 4096f / m).roundToInt().coerceAtLeast(1), (b.height * 4096f / m).roundToInt().coerceAtLeast(1), true) else b
    return src.copy(Bitmap.Config.ARGB_8888, true)
}

@Composable
private fun NewCanvasForm(onCreate: (Bitmap) -> Unit, onCancel: () -> Unit) {
    var w by remember { mutableStateOf("1200") }
    var h by remember { mutableStateOf("800") }
    var bg by remember { mutableStateOf<Int?>(0xFFFFFF) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.nh_ie_new), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.nh_ie_new_size), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(w, { w = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.nh_ie_width)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(h, { h = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.nh_ie_height)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(800 to 600, 1200 to 800, 1080 to 1080, 1600 to 900, 2480 to 3508).forEach { (a, b) -> AssistChip({ w = "$a"; h = "$b" }, label = { Text("$a×$b") }) }
        }
        Text(stringResource(R.string.nh_ie_bg), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(bg == null, { bg = null }, label = { Text(stringResource(R.string.nh_ie_transparent)) })
            IE_COLORS.forEach { c -> ColorDot(c, bg == c) { bg = c } }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(onClick = {
                val ww = (w.toIntOrNull() ?: 0).coerceIn(16, 4096); val hh = (h.toIntOrNull() ?: 0).coerceIn(16, 4096)
                val b = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
                bg?.let { b.eraseColor(0xFF000000.toInt() or it) }
                onCreate(b)
            }) { Text(stringResource(R.string.nh_ie_create)) }
        }
    }
}

@Composable
private fun EditorBody(d: EDoc, onDone: (Bitmap) -> Unit, onCancel: () -> Unit) {
    var tool by remember { mutableStateOf(ETool.BRUSH) }
    var color by remember { mutableIntStateOf(0xD32F2F) }
    var size by remember { mutableFloatStateOf(24f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var flow by remember { mutableFloatStateOf(0.6f) }
    var blend by remember { mutableStateOf(Blend.NORMAL) }
    var tolerance by remember { mutableFloatStateOf(32f) }
    var contiguous by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(EPanel.NONE) }
    var message by remember { mutableStateOf<String?>(null) }
    // view transform: screen = offset + image * scale
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun fit() {
        if (viewSize.width == 0) return
        scale = min(viewSize.width / d.w.toFloat(), viewSize.height / d.h.toFloat()) * 0.95f
        offset = Offset((viewSize.width - d.w * scale) / 2f, (viewSize.height - d.h * scale) / 2f)
    }
    LaunchedEffect(viewSize) { fit() }
    // stroke state
    var stroke by remember { mutableStateOf<Bitmap?>(null) }        // brush / eraser dabs of the current stroke
    var cloneSrc by remember { mutableStateOf<Offset?>(null) }
    val polyPts = remember { mutableStateListOf<Offset>() }
    var lassoPath by remember { mutableStateOf<Path?>(null) }
    var rectSel by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    // filters
    val filters = remember { mutableStateListOf<FilterStep>() }
    var filterPreview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(filters.toList(), d.version, d.active) {
        if (filters.isEmpty()) { filterPreview = null; return@LaunchedEffect }
        delay(150)
        val base = d.cur.bmp; val m = d.mask
        filterPreview = withContext(Dispatchers.Default) { ImageOps.maskMerge(base, ImageOps.applyFilters(base, filters.toList()), m) }
    }
    val ctxMsgNeedSource = stringResource(R.string.nh_ie_clone_need_source)

    fun img(p: Offset) = Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        // ---- top bar ----
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
            Text(stringResource(R.string.nh_ie_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { d.undo() }, enabled = d.undo.isNotEmpty() || d.version < 0) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
            IconButton(onClick = { d.redo() }, enabled = d.redo.isNotEmpty() || d.version < 0) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
            IconButton(onClick = { fit() }) { Icon(Icons.Default.FitScreen, stringResource(R.string.nh_ie_zoom_fit)) }
            Button(onClick = {
                val f = d.floating
                if (f != null) { d.push(); ACanvas(d.cur.bmp).drawBitmap(f, d.floatX, d.floatY, null); d.floating = null }
                onDone(d.composite())
            }) { Text(stringResource(R.string.done)) }
        }
        // ---- canvas ----
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFF303030)).onSizeChanged { viewSize = it }
            .pointerInput(tool, size, opacity, flow, blend, color, tolerance, contiguous) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val start = img(down.position)
                    var last = start
                    var multi = false
                    var began = false
                    var longPressed = false
                    // long press (clone source) without movement
                    if (tool == ETool.CLONE) {
                        try {
                            withTimeout(450) { while (true) { val e = awaitPointerEvent(); val c = e.changes.first(); if (!c.pressed || (c.position - down.position).getDistance() > 12f) break } }
                        } catch (e: PointerEventTimeoutCancellationException) { longPressed = true }
                        if (longPressed) { cloneSrc = start; return@awaitEachGesture }
                    }
                    fun begin() {
                        if (began) return
                        began = true
                        when (tool) {
                            ETool.BRUSH, ETool.ERASER -> { d.push(); stroke = Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888) }
                            ETool.BURN, ETool.DODGE, ETool.HEAL -> d.push()
                            ETool.CLONE -> d.push()
                            else -> {}
                        }
                    }
                    val cloneSnapshot = if (tool == ETool.CLONE) d.cur.bmp.copy(Bitmap.Config.ARGB_8888, false) else null
                    val cloneOff = cloneSrc?.let { it - start }
                    if (tool == ETool.CLONE && cloneSrc == null) { message = ctxMsgNeedSource; return@awaitEachGesture }
                    fun dab(p: Offset) {
                        when (tool) {
                            ETool.BRUSH, ETool.ERASER -> stroke?.let { s ->
                                val c = ACanvas(s)
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (0xFF000000.toInt() or color); alpha = (flow * 255).roundToInt().coerceIn(1, 255); strokeWidth = size; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
                                c.drawLine(last.x, last.y, p.x, p.y, paint)
                            }
                            ETool.BURN, ETool.DODGE -> ImageOps.burnDodge(d.cur.bmp, p.x, p.y, size / 2f, flow, tool == ETool.BURN, d.mask)
                            ETool.HEAL -> ImageOps.healDab(d.cur.bmp, p.x, p.y, size / 2f, d.mask)
                            ETool.CLONE -> cloneOff?.let { o -> cloneSnapshot?.let { s -> ImageOps.cloneDab(d.cur.bmp, s, p.x, p.y, o.x, o.y, size / 2f, d.mask) } }
                            else -> {}
                        }
                        d.version++
                    }
                    when (tool) {
                        ETool.BRUSH, ETool.ERASER, ETool.BURN, ETool.DODGE, ETool.HEAL, ETool.CLONE -> { begin(); dab(start) }
                        ETool.LASSO -> lassoPath = Path().apply { moveTo(start.x, start.y) }
                        ETool.SEL_RECT -> rectSel = start to start
                        else -> {}
                    }
                    val floatStart = Offset(d.floatX, d.floatY)
                    val fl = d.floating
                    val dragFloat = fl != null && start.x in d.floatX..(d.floatX + fl.width) && start.y in d.floatY..(d.floatY + fl.height)
                    var prevCentroid: Offset? = null; var prevSpan = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        ev.changes.forEach { it.consume() }
                        if (pressed.size >= 2 || (tool == ETool.PAN && !dragFloat)) {
                            // pinch zoom / pan of the view
                            multi = multi || pressed.size >= 2
                            val c = pressed.fold(Offset.Zero) { a, ch -> a + ch.position } / pressed.size.toFloat()
                            val span = if (pressed.size >= 2) (pressed[0].position - pressed[1].position).getDistance() else 0f
                            val pc = prevCentroid
                            if (pc != null) {
                                if (pressed.size >= 2 && prevSpan > 0f && span > 0f) {
                                    val z = (span / prevSpan)
                                    val ns = (scale * z).coerceIn(0.05f, 40f); val k = ns / scale
                                    offset = c - (c - offset) * k; scale = ns
                                }
                                offset += c - pc
                            }
                            prevCentroid = c; prevSpan = span
                            continue
                        }
                        prevCentroid = null
                        val p = img(pressed.first().position)
                        when (tool) {
                            ETool.BRUSH, ETool.ERASER, ETool.BURN, ETool.DODGE, ETool.HEAL, ETool.CLONE -> {
                                if (hypot(p.x - last.x, p.y - last.y) >= max(1f, size * 0.15f)) { dab(p); last = p }
                            }
                            ETool.LASSO -> { lassoPath?.lineTo(p.x, p.y); last = p; d.version++ }
                            ETool.SEL_RECT -> rectSel = start to p
                            else -> if (dragFloat) { d.floatX = floatStart.x + (p.x - start.x); d.floatY = floatStart.y + (p.y - start.y) }
                        }
                    }
                    if (multi) {
                        // a pinch during a stroke: keep what was painted (it is one undo step)
                    }
                    // ---- gesture end ----
                    when (tool) {
                        ETool.BRUSH, ETool.ERASER -> stroke?.let { s ->
                            if (d.mask != null) { val px = ImageOps.pixels(s); d.mask?.let { m -> for (i in px.indices) if (m[i].toInt() == 0) px[i] = 0 }; ImageOps.setPixels(s, px) }
                            val paint = if (tool == ETool.ERASER) Paint().apply { alpha = (opacity * 255).roundToInt(); xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
                                else ImageOps.paintFor(blend, opacity)
                            ACanvas(d.cur.bmp).drawBitmap(s, 0f, 0f, paint)
                            stroke = null; d.version++
                        }
                        ETool.BUCKET -> if (!multi) {
                            val x = start.x.toInt(); val y = start.y.toInt()
                            if (x in 0 until d.w && y in 0 until d.h) {
                                d.push()
                                val m = ImageOps.and(ImageOps.floodMask(ImageOps.pixels(d.cur.bmp), d.w, d.h, x, y, tolerance.roundToInt(), contiguous), d.mask)
                                val c = (((opacity * 255).roundToInt().coerceIn(0, 255)) shl 24) or color
                                ImageOps.fill(d.cur.bmp, m, c); d.version++
                            }
                        }
                        ETool.PICKER -> if (!multi) {
                            val x = start.x.toInt(); val y = start.y.toInt()
                            if (x in 0 until d.w && y in 0 until d.h) { val comp = d.composite(); color = comp.getPixel(x, y) and 0xFFFFFF; comp.recycle(); tool = ETool.BRUSH }
                        }
                        ETool.WAND -> if (!multi) {
                            val x = start.x.toInt(); val y = start.y.toInt()
                            if (x in 0 until d.w && y in 0 until d.h) {
                                d.push(); val comp = d.composite()
                                d.setMask(ImageOps.floodMask(ImageOps.pixels(comp), d.w, d.h, x, y, tolerance.roundToInt(), contiguous)); comp.recycle()
                            }
                        }
                        ETool.LASSO -> lassoPath?.let { p -> p.close(); d.push(); d.setMask(ImageOps.pathMask(p, d.w, d.h)); lassoPath = null }
                        ETool.SEL_RECT -> rectSel?.let { (a, b) ->
                            rectSel = null
                            if (hypot(a.x - b.x, a.y - b.y) > 3f) {
                                d.push(); d.setMask(ImageOps.pathMask(Path().apply { addRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), Path.Direction.CW) }, d.w, d.h))
                            }
                        }
                        ETool.POLY_LASSO -> if (!multi) { polyPts.add(start); d.version++ }
                        else -> {}
                    }
                }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val v = d.version
                drawIntoCanvas { cc ->
                    val nc = cc.nativeCanvas
                    nc.save()
                    nc.translate(offset.x, offset.y); nc.scale(scale, scale)
                    // checkerboard under transparent pixels
                    val chk = Paint().apply { this.color = 0xFFE0E0E0.toInt() }
                    nc.drawRect(0f, 0f, d.w.toFloat(), d.h.toFloat(), Paint().apply { this.color = android.graphics.Color.WHITE })
                    val cell = max(max(8f, 16f / scale), max(d.w, d.h) / 80f)
                    var yy = 0f; var row = 0
                    while (yy < d.h) { var xx = if (row % 2 == 0) 0f else cell; while (xx < d.w) { nc.drawRect(xx, yy, min(xx + cell, d.w.toFloat()), min(yy + cell, d.h.toFloat()), chk); xx += cell * 2 }; yy += cell; row++ }
                    val fp = filterPreview
                    ImageOps.drawLayers(nc, d.layers, if (fp != null) d.active to fp else null)
                    stroke?.let { s ->
                        val paint = if (tool == ETool.ERASER) Paint().apply { alpha = 140; colorFilter = android.graphics.PorterDuffColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN) }
                            else ImageOps.paintFor(blend, opacity)
                        nc.drawBitmap(s, 0f, 0f, paint)
                    }
                    d.floating?.let { f -> nc.drawBitmap(f, d.floatX, d.floatY, null) }
                    d.overlay?.let { o -> nc.drawBitmap(o, 0f, 0f, null) }
                    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f / scale; this.color = 0xFF2196F3.toInt() }
                    lassoPath?.let { nc.drawPath(it, outline) }
                    rectSel?.let { (a, b) -> nc.drawRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), outline) }
                    if (polyPts.isNotEmpty()) {
                        val pp = Path().apply { polyPts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                        nc.drawPath(pp, outline)
                        polyPts.forEach { nc.drawCircle(it.x, it.y, 4f / scale, outline) }
                    }
                    d.floating?.let { f -> nc.drawRect(d.floatX, d.floatY, d.floatX + f.width, d.floatY + f.height, outline) }
                    cloneSrc?.takeIf { tool == ETool.CLONE }?.let { s -> nc.drawCircle(s.x, s.y, size / 2f, outline); nc.drawLine(s.x - size / 2f, s.y, s.x + size / 2f, s.y, outline) }
                    nc.restore()
                }
            }
            message?.let { m ->
                LaunchedEffect(m) { delay(2500); message = null }
                Surface(Modifier.align(Alignment.TopCenter).padding(8.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(m, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            when {
                tool == ETool.CLONE && message == null -> Hint(stringResource(R.string.nh_ie_clone_hint), Modifier.align(Alignment.TopCenter))
                d.floating != null -> Hint(stringResource(R.string.nh_ie_paste_hint), Modifier.align(Alignment.TopCenter))
            }
        }
        // ---- panels ----
        when (panel) {
            EPanel.FILTERS -> FiltersPanel(filters, onApply = {
                filterPreview?.let { fp -> d.push(); d.cur.bmp = fp.copy(Bitmap.Config.ARGB_8888, true); d.version++ }
                filters.clear(); panel = EPanel.NONE
            })
            EPanel.LAYERS -> LayersPanel(d)
            EPanel.NONE -> {}
        }
        // ---- tool options ----
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                when (tool) {
                    ETool.BRUSH, ETool.ERASER, ETool.BURN, ETool.DODGE, ETool.CLONE, ETool.HEAL -> {
                        LabeledSlider(stringResource(R.string.nh_ie_size, size.roundToInt()), size, 1f..300f) { size = it }
                        if (tool == ETool.BRUSH || tool == ETool.ERASER) LabeledSlider(stringResource(R.string.nh_opacity, (opacity * 100).roundToInt()), opacity, 0.05f..1f) { opacity = it }
                        if (tool != ETool.HEAL && tool != ETool.CLONE) LabeledSlider(stringResource(R.string.nh_ie_flow, (flow * 100).roundToInt()), flow, 0.05f..1f) { flow = it }
                        if (tool == ETool.BRUSH) BlendRow(blend) { blend = it }
                    }
                    ETool.BUCKET, ETool.WAND -> {
                        LabeledSlider(stringResource(R.string.nh_ie_tolerance, tolerance.roundToInt()), tolerance, 0f..255f) { tolerance = it }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(contiguous, { contiguous = it }); Text(stringResource(R.string.nh_ie_contiguous)) }
                    }
                    ETool.POLY_LASSO -> Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { polyPts.clear(); d.version++ }) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = {
                            if (polyPts.size >= 3) { val p = Path().apply { polyPts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }; d.push(); d.setMask(ImageOps.pathMask(p, d.w, d.h)) }
                            polyPts.clear(); d.version++
                        }, enabled = polyPts.size >= 3) { Text(stringResource(R.string.nh_ie_finish_poly)) }
                    }
                    else -> {}
                }
                if (tool == ETool.BRUSH || tool == ETool.BUCKET || tool == ETool.PICKER) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    IE_COLORS.forEach { c -> ColorDot(c, color == c) { color = c } }
                    var hex by remember(color) { mutableStateOf("#%06X".format(color)) }
                    OutlinedTextField(hex, { v -> hex = v.take(7); v.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let { color = it } },
                        singleLine = true, modifier = Modifier.width(130.dp).padding(start = 6.dp))
                }
                // selection / edit actions
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Act(Icons.Default.SelectAll, R.string.nh_ie_select_all) { d.push(); d.setMask(ByteArray(d.w * d.h) { 1 }) }
                    Act(Icons.Default.Deselect, R.string.nh_ie_deselect, d.mask != null) { d.push(); d.setMask(null) }
                    Act(Icons.Default.FlipToBack, R.string.nh_ie_invert_sel, d.mask != null) { d.push(); d.setMask(d.mask?.let { ImageOps.invert(it) }) }
                    Act(Icons.Default.ContentCut, R.string.nh_ie_cut) {
                        ImageOps.extract(d.cur.bmp, d.mask)?.let { (b, x, y) -> d.clip = b; d.clipX = x; d.clipY = y }
                        d.push(); ImageOps.clear(d.cur.bmp, d.mask); d.version++
                    }
                    Act(Icons.Default.ContentCopy, R.string.nh_ie_copy) { ImageOps.extract(d.cur.bmp, d.mask)?.let { (b, x, y) -> d.clip = b; d.clipX = x; d.clipY = y } }
                    Act(Icons.Default.ContentPaste, R.string.nh_ie_paste, d.clip != null || d.version < 0) {
                        d.clip?.let { c -> d.floating = c.copy(Bitmap.Config.ARGB_8888, true); d.floatX = d.clipX + 12f; d.floatY = d.clipY + 12f; tool = ETool.PAN }
                    }
                    if (d.floating != null) Act(Icons.Default.Check, R.string.nh_ie_apply_paste) {
                        d.floating?.let { f -> d.push(); ACanvas(d.cur.bmp).drawBitmap(f, d.floatX, d.floatY, null) }; d.floating = null; d.version++
                    }
                    Act(Icons.Default.Flip, R.string.nh_ie_flip_h) {
                        d.push(); val m = d.mask
                        if (m == null) { d.layers.forEach { it.bmp = ImageOps.flipped(it.bmp, true) } } else ImageOps.flipSelection(d.cur.bmp, m, true)
                        d.version++
                    }
                    Act(Icons.Default.SwapVert, R.string.nh_ie_flip_v) {
                        d.push(); val m = d.mask
                        if (m == null) { d.layers.forEach { it.bmp = ImageOps.flipped(it.bmp, false) } } else ImageOps.flipSelection(d.cur.bmp, m, false)
                        d.version++
                    }
                    Act(Icons.Default.Tune, R.string.nh_ie_filters) { panel = if (panel == EPanel.FILTERS) EPanel.NONE else EPanel.FILTERS }
                    Act(Icons.Default.Layers, R.string.nh_ie_layers) { panel = if (panel == EPanel.LAYERS) EPanel.NONE else EPanel.LAYERS }
                }
                // tools
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    ETool.entries.forEach { t ->
                        FilterChip(tool == t, { tool = t; if (t != ETool.POLY_LASSO) polyPts.clear() }, label = { Text(stringResource(t.label)) },
                            leadingIcon = { Icon(t.icon, null, Modifier.size(18.dp)) }, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier) {
    Surface(modifier.padding(8.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Act(icon: ImageVector, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) }, modifier = Modifier.padding(horizontal = 2.dp))
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(120.dp))
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f).height(30.dp))
    }
}

@Composable
private fun ColorDot(c: Int, sel: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(3.dp).size(if (sel) 30.dp else 26.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
        .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape).clickable(onClick = onClick))
}

private val BLEND_LABELS = listOf(R.string.nh_bm_normal, R.string.nh_bm_multiply, R.string.nh_bm_screen, R.string.nh_bm_overlay, R.string.nh_bm_darken, R.string.nh_bm_lighten)

@Composable
private fun BlendRow(value: Blend, onChange: (Blend) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.nh_ie_blend), style = MaterialTheme.typography.labelSmall)
        Blend.entries.forEachIndexed { i, b -> FilterChip(value == b, { onChange(b) }, label = { Text(stringResource(BLEND_LABELS[i])) }) }
    }
}

private val FILTER_LABELS = mapOf(
    FilterType.BRIGHTNESS to R.string.nh_f_brightness, FilterType.CONTRAST to R.string.nh_f_contrast, FilterType.SATURATION to R.string.nh_f_saturation,
    FilterType.GRAYSCALE to R.string.nh_f_grayscale, FilterType.SEPIA to R.string.nh_f_sepia, FilterType.INVERT to R.string.nh_f_invert,
    FilterType.BLUR to R.string.nh_f_blur, FilterType.SHARPEN to R.string.nh_f_sharpen, FilterType.THRESHOLD to R.string.nh_f_threshold,
)

/** Stackable filters: add a filter, tune its amount (live preview), remove, apply to the active layer / selection. */
@Composable
private fun FiltersPanel(filters: MutableList<FilterStep>, onApply: () -> Unit) {
    Surface(tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterType.entries.forEach { t -> AssistChip({ filters.add(FilterStep(t, ImageOps.range(t).first)) }, label = { Text(stringResource(FILTER_LABELS.getValue(t))) }, modifier = Modifier.padding(horizontal = 2.dp)) }
            }
            filters.forEachIndexed { i, f ->
                val (_, r) = ImageOps.range(f.type)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(FILTER_LABELS.getValue(f.type)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp))
                    if (r.start < r.endInclusive) Slider(f.amount, { v -> filters[i] = f.copy(amount = v) }, valueRange = r, modifier = Modifier.weight(1f).height(30.dp)) else Spacer(Modifier.weight(1f))
                    IconButton(onClick = { filters.removeAt(i) }) { Icon(Icons.Default.Close, null) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { filters.clear() }, enabled = filters.isNotEmpty()) { Text(stringResource(R.string.nh_ie_clear_filters)) }
                Button(onClick = onApply, enabled = filters.isNotEmpty()) { Text(stringResource(R.string.nh_ie_apply_filters)) }
            }
        }
    }
}

/** Layers: select, visibility, opacity, blend, add, delete, merge down. Index 0 is the image itself. */
@Composable
private fun LayersPanel(d: EDoc) {
    Surface(tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
            @Suppress("UNUSED_VARIABLE") val v = d.version
            for (i in d.layers.indices.reversed()) {
                val l = d.layers[i]
                Surface(color = if (i == d.active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().clickable { d.active = i }) {
                    Column(Modifier.padding(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(l.visible, { l.visible = it; d.version++ })
                            Text(if (i == 0) stringResource(R.string.nh_ie_layer_base) else stringResource(R.string.nh_ie_layer, i), Modifier.weight(1f))
                            if (i > 0) {
                                IconButton(onClick = {
                                    d.push(); val below = d.layers[i - 1]
                                    if (l.visible) ACanvas(below.bmp).drawBitmap(l.bmp, 0f, 0f, ImageOps.paintFor(l.blend, l.opacity))
                                    d.layers.removeAt(i); d.active = i - 1; d.version++
                                }) {
                                    Icon(Icons.Default.MergeType, stringResource(R.string.nh_ie_merge))
                                }
                                IconButton(onClick = { d.push(); d.layers.removeAt(i); d.active = d.active.coerceAtMost(d.layers.size - 1); d.version++ }) { Icon(Icons.Default.Delete, stringResource(R.string.nh_ie_delete_layer)) }
                            }
                        }
                        if (i > 0) {
                            LabeledSlider(stringResource(R.string.nh_ie_layer_opacity, (l.opacity * 100).roundToInt()), l.opacity, 0f..1f) { l.opacity = it; d.version++ }
                            BlendRow(l.blend) { l.blend = it; d.version++ }
                        }
                    }
                }
            }
            TextButton(onClick = { d.push(); d.layers.add(Layer(Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888))); d.active = d.layers.size - 1; d.version++ }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nh_ie_add_layer))
            }
        }
    }
}
