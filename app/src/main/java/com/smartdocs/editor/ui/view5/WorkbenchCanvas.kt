package com.smartdocs.editor.ui.view5

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.smartdocs.editor.core.view5.DashStyle
import com.smartdocs.editor.core.view5.Geom
import com.smartdocs.editor.core.view5.MarkKind
import com.smartdocs.editor.core.view5.P
import kotlin.math.roundToInt

/** Screen mapping of the workbench page: page points ↔ canvas pixels (fit width × zoom, panned). */
class WbView(val pageW: Float, val pageH: Float) {
    var vw by mutableFloatStateOf(1f)
    var vh by mutableFloatStateOf(1f)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    val scale: Float get() = vw / pageW * zoom              // px per point
    fun toScreen(p: P) = Offset((p.x * scale).toFloat() + panX, ((pageH - p.y) * scale).toFloat() + panY)
    fun toPdf(o: Offset) = P(((o.x - panX) / scale).toDouble(), (pageH - (o.y - panY) / scale).toDouble())
    fun clamp() {
        val w = pageW * scale; val h = pageH * scale
        panX = if (w <= vw) (vw - w) / 2 else panX.coerceIn(vw - w, 0f)
        panY = if (h <= vh) (vh - h) / 2 else panY.coerceIn(vh - h, 0f)
    }
}

private fun col(rgb: Int, a: Float = 1f) = Color(0xFF000000.toInt() or rgb).copy(alpha = a)

private fun dashEffect(d: DashStyle, w: Float): PathEffect? = d.scaled(w)?.let { PathEffect.dashPathEffect(it, 0f) }

/**
 * The page with pending workbench items, the shape in progress, snapping marker and a loupe while dragging.
 * One finger: tap = add point; drag = precise placement with the loupe, the point is added on release.
 * Two fingers: zoom and pan.
 */
@Composable
fun WorkbenchCanvas(m: WorkbenchModel, bitmap: Bitmap?, view: WbView, snapTo: (P, Float) -> P?, modifier: Modifier = Modifier) {
    val img: ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var snapped by remember { mutableStateOf<P?>(null) }
    val slop = LocalViewConfiguration.current.touchSlop
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; typeface = Typeface.DEFAULT_BOLD } }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    fun resolve(o: Offset): P {
        val raw = view.toPdf(o)
        var p = if (m.v.snap) snapTo(raw, 14f / view.scale) ?: raw else raw
        p = com.smartdocs.editor.ui.nve.NveGridSnap.snapP(m.v.s, p, m.page)   // NVE: snap to grid / guides
        if (m.v.ortho && m.pts.isNotEmpty() && (m.tool == WTool.DISTANCE || m.tool == WTool.PERIMETER || m.tool == WTool.AREA || m.tool == WTool.LINE || m.calibrating)) p = Geom.constrain45(m.pts.last(), p)
        return p
    }

    Canvas(modifier.fillMaxSize().clipToBounds()
        .onSizeChanged { sz -> view.vw = sz.width.toFloat().coerceAtLeast(1f); view.vh = sz.height.toFloat().coerceAtLeast(1f); view.clamp() }
        .pointerInput(m, view) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var multi = false; var dragged = false
                do {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.count { it.pressed }
                    if (pressed >= 2) {
                        multi = true; cursor = null; snapped = null
                        val z = ev.calculateZoom(); val pan = ev.calculatePan(); val c = ev.calculateCentroid()
                        val z0 = view.zoom; val z1 = (z0 * z).coerceIn(1f, 10f); val r = z1 / z0
                        view.panX = c.x - (c.x - view.panX) * r + pan.x
                        view.panY = c.y - (c.y - view.panY) * r + pan.y
                        view.zoom = z1; view.clamp()
                        ev.changes.forEach { it.consume() }
                    } else if (!multi && pressed == 1) {
                        val ch = ev.changes.first { it.pressed }
                        if (!dragged && (ch.position - down.position).getDistance() > slop) dragged = true
                        if (dragged) { cursor = ch.position; snapped = resolve(ch.position); ch.consume() }
                    }
                } while (ev.changes.any { it.pressed })
                if (!multi) m.addPoint(if (dragged) (snapped ?: resolve(cursor ?: down.position)) else resolve(down.position))
                cursor = null; snapped = null
            }
        }) {
        val s = view.scale
        if (img != null) drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(view.panX.roundToInt(), view.panY.roundToInt()), dstSize = IntSize((view.pageW * s).roundToInt(), (view.pageH * s).roundToInt()))
        // pending items
        m.items.filter { it.page == m.page }.forEach { drawItem(it, view, labelPaint, textPaint) }
        // in progress
        val pts = m.pts.toList() + listOfNotNull(snapped)
        if (pts.isNotEmpty()) {
            val c = col(if (m.calibrating) 0x1E88E5 else m.color)
            val sp = pts.map { view.toScreen(it) }
            when {
                m.tool == WTool.CIRCLE && sp.size >= 2 && !m.calibrating -> drawCircle(c, (sp[1] - sp[0]).getDistance(), sp[0], style = Stroke(3f))
                (m.tool == WTool.TEXTBOX || m.tool == WTool.AREA_HIGHLIGHT) && sp.size >= 2 && !m.calibrating ->
                    drawRect(c.copy(alpha = 0.25f), Offset(minOf(sp[0].x, sp[1].x), minOf(sp[0].y, sp[1].y)), Size(kotlin.math.abs(sp[1].x - sp[0].x), kotlin.math.abs(sp[1].y - sp[0].y)))
                m.tool == WTool.CALLOUT && sp.size >= 3 -> {
                    drawLine(c, sp[0], Offset((sp[1].x + sp[2].x) / 2, (sp[1].y + sp[2].y) / 2), 3f)
                    drawRect(c, Offset(minOf(sp[1].x, sp[2].x), minOf(sp[1].y, sp[2].y)), Size(kotlin.math.abs(sp[2].x - sp[1].x), kotlin.math.abs(sp[2].y - sp[1].y)), style = Stroke(3f))
                }
                else -> {
                    val path = Path().apply { sp.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; if (m.tool == WTool.AREA && sp.size > 2) close() }
                    if (m.tool != WTool.COUNT) drawPath(path, c, style = Stroke(3f, cap = StrokeCap.Round, pathEffect = dashEffect(m.dash, 2f)))
                }
            }
            sp.forEach { o -> drawCircle(Color.White, 9f, o); drawCircle(c, 6f, o) }
            // live readout next to the last point
            if (m.isMeasure || m.calibrating) {
                val txt = if (m.calibrating) "%.1f pt".format(java.util.Locale.US, Geom.pathLength(pts)) else m.label(m.tool, m.valueOf(m.tool, pts))
                val o = sp.last()
                labelPaint.color = android.graphics.Color.WHITE
                val w = labelPaint.measureText(txt)
                drawRect(Color(0xCC000000), Offset(o.x + 18f, o.y - 64f), Size(w + 20f, 46f))
                drawContext.canvas.nativeCanvas.drawText(txt, o.x + 28f, o.y - 30f, labelPaint)
            }
        }
        // loupe
        val cur = cursor
        if (cur != null && img != null) {
            val r = 130f; val k = 2.5f
            val center = Offset(cur.x, (cur.y - r - 60f).let { if (it < r) cur.y + r + 60f else it })
            val circle = Path().apply { addOval(Rect(center, r)) }
            clipPath(circle) {
                drawRect(Color.White, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r))
                val target = snapped?.let { view.toScreen(it) } ?: cur
                val dstW = view.pageW * s * k; val dstH = view.pageH * s * k
                val ox = center.x - (target.x - view.panX) * k; val oy = center.y - (target.y - view.panY) * k
                drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()))
                drawLine(Color.Red, Offset(center.x - 20f, center.y), Offset(center.x + 20f, center.y), 2f)
                drawLine(Color.Red, Offset(center.x, center.y - 20f), Offset(center.x, center.y + 20f), 2f)
            }
            drawCircle(Color.DarkGray, r, center, style = Stroke(4f))
            if (snapped != null) drawCircle(Color(0xFF00C853), 12f, view.toScreen(snapped!!), style = Stroke(3f))
        }
    }
}

private fun DrawScope.drawItem(it: WItem, v: WbView, label: Paint, text: Paint) {
    when (it) {
        is WItem.Meas -> {
            val c = col(it.m.color)
            val sp = it.m.pts.map { p -> v.toScreen(p) }
            val w = (it.m.width * v.scale).coerceAtLeast(2f)
            val fx = dashEffect(it.m.dash, w)
            when (it.m.type) {
                com.smartdocs.editor.core.view5.MeasureType.CIRCLE -> if (sp.size >= 2) {
                    val r = (sp[1] - sp[0]).getDistance()
                    it.m.fill?.let { f -> drawCircle(col(f, 0.25f), r, sp[0]) }
                    drawCircle(c, r, sp[0], style = Stroke(w, pathEffect = fx))
                }
                com.smartdocs.editor.core.view5.MeasureType.COUNT -> sp.forEachIndexed { i, o ->
                    drawCircle(c, 18f, o)
                    label.color = android.graphics.Color.WHITE
                    val t = (i + 1).toString(); drawContext.canvas.nativeCanvas.drawText(t, o.x - label.measureText(t) / 2, o.y + 12f, label)
                }
                else -> {
                    val path = Path().apply { sp.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; if (it.m.type == com.smartdocs.editor.core.view5.MeasureType.AREA) close() }
                    if (it.m.type == com.smartdocs.editor.core.view5.MeasureType.AREA) it.m.fill?.let { f -> drawPath(path, col(f, 0.25f)) }
                    drawPath(path, c, style = Stroke(w, pathEffect = fx))
                }
            }
            if (it.m.type != com.smartdocs.editor.core.view5.MeasureType.COUNT && sp.isNotEmpty()) {
                val at = if (it.m.type == com.smartdocs.editor.core.view5.MeasureType.AREA) Offset(sp.map { o -> o.x }.average().toFloat(), sp.map { o -> o.y }.average().toFloat())
                    else if (sp.size >= 2) Offset((sp[sp.size - 2].x + sp.last().x) / 2, (sp[sp.size - 2].y + sp.last().y) / 2) else sp[0]
                label.color = 0xFF000000.toInt() or it.m.color
                val tw = label.measureText(it.m.label)
                drawRect(Color(0xEEFFFFFF), Offset(at.x - tw / 2 - 6, at.y - 30f), Size(tw + 12, 40f))
                drawContext.canvas.nativeCanvas.drawText(it.m.label, at.x - tw / 2, at.y, label)
            }
        }
        is WItem.Text -> {
            val b = it.spec.box
            val tl = v.toScreen(P(b.x0.toDouble(), b.y1.toDouble())); val br = v.toScreen(P(b.x1.toDouble(), b.y0.toDouble()))
            it.spec.fill?.takeIf { _ -> !it.spec.typewriter }?.let { f -> drawRect(col(f), tl, Size(br.x - tl.x, br.y - tl.y)) }
            if (it.spec.borderColor != null && !it.spec.typewriter) drawRect(col(it.spec.borderColor), tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke((it.spec.borderWidth * v.scale).coerceAtLeast(2f), pathEffect = dashEffect(it.spec.dash, 3f)))
            it.spec.tip?.let { t ->
                val ts = v.toScreen(t)
                drawLine(col(it.spec.borderColor ?: it.spec.textColor), ts, Offset((tl.x + br.x) / 2, (tl.y + br.y) / 2).let { c -> Offset(c.x.coerceIn(tl.x, br.x), if (ts.y < tl.y) tl.y else if (ts.y > br.y) br.y else c.y) }, 3f)
                drawCircle(col(it.spec.borderColor ?: it.spec.textColor), 6f, ts)
            }
            text.color = 0xFF000000.toInt() or it.spec.textColor; text.textSize = it.spec.fontSize * v.scale
            var y = tl.y + text.textSize
            it.spec.text.split('\n').forEach { line ->
                val lw = text.measureText(line)
                val x = if (it.spec.rtl || com.smartdocs.editor.core.view5.ReflowBlocks.isRtl(line)) br.x - lw - 4f else tl.x + 4f
                drawContext.canvas.nativeCanvas.drawText(line, x, y, text); y += text.textSize * 1.2f
            }
        }
        is WItem.Mark -> {
            val o = v.toScreen(it.at); val h = it.size * v.scale / 2; val c = col(it.color); val w = (it.size * v.scale / 8).coerceAtLeast(2f)
            if (it.kind == MarkKind.TICK) {
                val p = Path().apply { moveTo(o.x - h * 0.7f, o.y); lineTo(o.x - h * 0.2f, o.y + h * 0.7f); lineTo(o.x + h * 0.7f, o.y - h * 0.7f) }
                drawPath(p, c, style = Stroke(w, cap = StrokeCap.Round))
            } else {
                drawLine(c, Offset(o.x - h * 0.7f, o.y - h * 0.7f), Offset(o.x + h * 0.7f, o.y + h * 0.7f), w, cap = StrokeCap.Round)
                drawLine(c, Offset(o.x - h * 0.7f, o.y + h * 0.7f), Offset(o.x + h * 0.7f, o.y - h * 0.7f), w, cap = StrokeCap.Round)
            }
        }
        is WItem.Area -> {
            val tl = v.toScreen(P(it.box.x0.toDouble(), it.box.y1.toDouble())); val br = v.toScreen(P(it.box.x1.toDouble(), it.box.y0.toDouble()))
            drawRect(col(it.color, it.opacity), tl, Size(br.x - tl.x, br.y - tl.y))
        }
        is WItem.Line -> {
            val a = v.toScreen(it.a); val b = v.toScreen(it.b); val w = (it.width * v.scale).coerceAtLeast(2f)
            drawLine(col(it.color), a, b, w, cap = StrokeCap.Round, pathEffect = dashEffect(it.dash, w))
            if (it.end != com.smartdocs.editor.core.view5.LineEnd.NONE) drawCircle(col(it.color), w * 1.6f, b)
            if (it.start != com.smartdocs.editor.core.view5.LineEnd.NONE) drawCircle(col(it.color), w * 1.6f, a)
        }
    }
}
