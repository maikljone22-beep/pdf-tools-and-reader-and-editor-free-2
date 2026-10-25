package com.smartdocs.editor.ui.reader

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.ui.reader.annot.drawAnnot2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

fun annotColor(rgb: Int, alpha: Float = 1f) = Color(((rgb shr 16) and 0xFF), ((rgb shr 8) and 0xFF), rgb and 0xFF, (alpha * 255).roundToInt().coerceIn(0, 255))

/**
 * Draws one pending annotation in unrotated screen space. Must be called inside the rotation transform
 * of [PageCanvas] so that it lines up with the rendered bitmap.
 */
fun DrawScope.drawAnnot(a: Annot, g: PageGeometry) {
    val c = annotColor(a.color, a.opacity)
    val sw = (a.strokeWidth * g.scale).coerceAtLeast(1f)
    when (a.kind) {
        AnnotKind.HIGHLIGHT -> a.rects.forEach { r ->
            val s = g.pdfRectToScreen(r)
            drawRect(c, s.topLeft, s.size, blendMode = BlendMode.Multiply)
        }
        AnnotKind.UNDERLINE -> a.rects.forEach { r ->
            val s = g.pdfRectToScreen(r)
            drawLine(c, Offset(s.left, s.bottom - 1f), Offset(s.right, s.bottom - 1f), strokeWidth = (1.2f * g.scale).coerceAtLeast(1.5f))
        }
        AnnotKind.STRIKEOUT -> a.rects.forEach { r ->
            val s = g.pdfRectToScreen(r)
            drawLine(c, Offset(s.left, s.center.y), Offset(s.right, s.center.y), strokeWidth = (1.2f * g.scale).coerceAtLeast(1.5f))
        }
        AnnotKind.SQUIGGLY -> a.rects.forEach { r ->
            val s = g.pdfRectToScreen(r)
            val p = Path(); val step = 2.5f * g.scale; var x = s.left; var up = true
            p.moveTo(x, s.bottom)
            while (x < s.right) { x += step; p.lineTo(x, if (up) s.bottom - step else s.bottom); up = !up }
            drawPath(p, c, style = Stroke(width = (1f * g.scale).coerceAtLeast(1.2f)))
        }
        AnnotKind.INK -> a.paths.forEach { stroke ->
            if (stroke.isEmpty()) return@forEach
            val p = Path()
            stroke.forEachIndexed { i, (x, y) -> val o = g.pdfToScreen(x, y); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
            if (stroke.size == 1) { val o = g.pdfToScreen(stroke[0].first, stroke[0].second); drawCircle(c, sw / 2, o) }
            else drawPath(p, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        AnnotKind.NOTE -> a.rects.firstOrNull()?.let { r ->
            val o = g.pdfToScreen(r.left, r.top)
            val sz = 20f * g.scale
            drawRoundRect(annotColor(a.color), Offset(o.x, o.y), Size(sz, sz), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * g.scale))
            drawRoundRect(Color(0xFF424242), Offset(o.x, o.y), Size(sz, sz), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * g.scale), style = Stroke(1f * g.scale))
            val lw = 1.2f * g.scale
            drawLine(Color(0xFF424242), Offset(o.x + sz * .25f, o.y + sz * .35f), Offset(o.x + sz * .75f, o.y + sz * .35f), lw)
            drawLine(Color(0xFF424242), Offset(o.x + sz * .25f, o.y + sz * .55f), Offset(o.x + sz * .75f, o.y + sz * .55f), lw)
            drawLine(Color(0xFF424242), Offset(o.x + sz * .25f, o.y + sz * .75f), Offset(o.x + sz * .55f, o.y + sz * .75f), lw)
        }
        AnnotKind.FREETEXT, AnnotKind.STAMP_TEXT -> a.rects.firstOrNull()?.let { r ->
            val s = g.pdfRectToScreen(r)
            if (a.kind == AnnotKind.STAMP_TEXT) drawRect(c, s.topLeft, s.size, style = Stroke(2f * g.scale))
            drawTextBlock(a.text, s, a.fontSize * g.scale, c)
        }
        AnnotKind.LINE, AnnotKind.ARROW -> if (a.points.size >= 2) {
            val p1 = g.pdfToScreen(a.points[0].first, a.points[0].second)
            val p2 = g.pdfToScreen(a.points[1].first, a.points[1].second)
            drawLine(c, p1, p2, sw, cap = StrokeCap.Round)
            if (a.kind == AnnotKind.ARROW) drawArrowHead(p1, p2, c, sw)
        }
        AnnotKind.RECT -> a.rects.firstOrNull()?.let { r -> val s = g.pdfRectToScreen(r); drawRect(c, s.topLeft, s.size, style = Stroke(sw)) }
        AnnotKind.OVAL -> a.rects.firstOrNull()?.let { r -> val s = g.pdfRectToScreen(r); drawOval(c, s.topLeft, s.size, style = Stroke(sw)) }
        AnnotKind.STAMP_IMAGE -> a.rects.firstOrNull()?.let { r ->
            val s = g.pdfRectToScreen(r); val img = a.image ?: return@let
            drawImage(img.asImageBitmap(), dstOffset = IntOffset(s.left.roundToInt(), s.top.roundToInt()),
                dstSize = IntSize(s.width.roundToInt().coerceAtLeast(1), s.height.roundToInt().coerceAtLeast(1)), alpha = a.opacity)
        }
        AnnotKind.REDACT -> a.rects.forEach { r -> val s = g.pdfRectToScreen(r); drawRect(Color.Black, s.topLeft, s.size) }
        AnnotKind.POLYGON, AnnotKind.POLYLINE, AnnotKind.CLOUD, AnnotKind.CARET, AnnotKind.REPLACE,
        AnnotKind.FILE_ATTACH, AnnotKind.SOUND, AnnotKind.VIDEO -> drawAnnot2(a, g)       // MODULE_annot2
    }
}

fun DrawScope.drawArrowHead(from: Offset, to: Offset, color: Color, sw: Float) {
    val ang = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val len = 10f + sw * 2
    for (s in listOf(-1, 1)) {
        val t = ang + s * Math.PI / 7
        drawLine(color, to, Offset(to.x - (len * cos(t)).toFloat(), to.y - (len * sin(t)).toFloat()), sw, cap = StrokeCap.Round)
    }
}

/** Multi-line text inside a rect using the native canvas (handles Arabic shaping and RTL alignment). */
fun DrawScope.drawTextBlock(text: String, box: Rect, textSizePx: Float, color: Color, rtl: Boolean = text.any { it in '؀'..'ۿ' }) {
    if (text.isBlank()) return
    drawIntoCanvas { cv ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb(); textSize = textSizePx
            textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        }
        val pad = 4f * (textSizePx / 14f).coerceAtLeast(0.5f)
        var y = box.top + textSizePx + pad * 0.5f
        val x = if (rtl) box.right - pad else box.left + pad
        text.split('\n').forEach { line ->
            if (y > box.bottom + textSizePx) return@forEach
            cv.nativeCanvas.drawText(line, x, y, paint)
            y += textSizePx * 1.3f
        }
    }
}

/** Selection / search rect painters (unrotated screen space). */
fun DrawScope.drawPdfRects(rects: List<RectF>, g: PageGeometry, color: Color, multiply: Boolean = false) {
    rects.forEach { r ->
        val s = g.pdfRectToScreen(r)
        drawRect(color, s.topLeft, s.size, blendMode = if (multiply) BlendMode.Multiply else BlendMode.SrcOver)
    }
}

/** Placement frame: image, border, 4 corner resize handles and a rotate handle above the top edge. */
fun DrawScope.drawPlacement(p: Placement, g: PageGeometry, accent: Color) {
    val s = g.pdfRectToScreen(p.rect)
    val center = s.center
    rotate(p.rotation, pivot = center) {
        drawImage(p.image.asImageBitmap(), dstOffset = IntOffset(s.left.roundToInt(), s.top.roundToInt()),
            dstSize = IntSize(s.width.roundToInt().coerceAtLeast(1), s.height.roundToInt().coerceAtLeast(1)))
        drawRect(accent, s.topLeft, s.size, style = Stroke(2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
        placementHandles(s).forEach { h -> drawCircle(Color.White, HANDLE_R, h); drawCircle(accent, HANDLE_R, h, style = Stroke(2.5f)) }
        val rot = rotateHandle(s)
        drawLine(accent, Offset(center.x, s.top), rot, 2f)
        drawCircle(accent, HANDLE_R, rot); drawCircle(Color.White, HANDLE_R * 0.45f, rot)
    }
}

const val HANDLE_R = 14f
fun placementHandles(s: Rect) = listOf(s.topLeft, s.topRight, s.bottomLeft, s.bottomRight)
fun rotateHandle(s: Rect) = Offset(s.center.x, s.top - 48f)

/** Rotate a point around a pivot by -deg (used to hit-test handles of a rotated placement). */
fun unrotatePoint(p: Offset, pivot: Offset, deg: Float): Offset {
    val a = Math.toRadians(-deg.toDouble()); val dx = p.x - pivot.x; val dy = p.y - pivot.y
    return Offset(pivot.x + (dx * cos(a) - dy * sin(a)).toFloat(), pivot.y + (dx * sin(a) + dy * cos(a)).toFloat())
}

/** Build the icon-like annotations used by Fill & Sign quick items (check, cross, dot, line) as INK strokes in PDF space. */
fun quickMarkPaths(tool: ReaderTool, x: Float, y: Float, size: Float): List<List<Pair<Float, Float>>> = when (tool) {
    ReaderTool.FS_CHECK -> listOf(listOf(x - size * .45f to y, x - size * .1f to y - size * .4f, x + size * .5f to y + size * .45f))
    ReaderTool.FS_CROSS -> listOf(listOf(x - size * .4f to y + size * .4f, x + size * .4f to y - size * .4f), listOf(x - size * .4f to y - size * .4f, x + size * .4f to y + size * .4f))
    ReaderTool.FS_DOT -> {
        val pts = (0..24).map { i -> val t = i / 24.0 * Math.PI * 2; (x + size * .18f * cos(t)).toFloat() to (y + size * .18f * sin(t)).toFloat() }
        listOf(pts)
    }
    ReaderTool.FS_LINE -> listOf(listOf(x - size to y, x + size to y))
    else -> emptyList()
}
