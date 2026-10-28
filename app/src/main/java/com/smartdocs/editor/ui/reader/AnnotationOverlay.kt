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
import com.smartdocs.editor.core.pdf.endStyle
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
            else drawPath(p, c, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dashEffectOf(a, g)))
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
            if (a.kind == AnnotKind.FREETEXT && a.comb > 0) drawCombText(a.text, a.comb, s, a.fontSize * g.scale, c)    // nitro-fillsign2
            else drawTextBlock(if (a.bullets) a.text.split('\n').joinToString("\n") { if (it.isBlank()) it else "\u2022 $it" } else a.text, s, a.fontSize * g.scale, c,
                bold = a.bold, italic = a.italic, underline = a.underline, strike = a.strike, family = a.fontFamily, align = a.align,
                lineSpacing = a.lineSpacing, letterSpacingPx = a.charSpacing * g.scale, scaleX = a.hScale)
        }
        AnnotKind.LINE, AnnotKind.ARROW -> if (a.points.size >= 2) {
            val p1 = g.pdfToScreen(a.points[0].first, a.points[0].second)
            val p2 = g.pdfToScreen(a.points[1].first, a.points[1].second)
            drawLine(c, p1, p2, sw, cap = StrokeCap.Round, pathEffect = dashEffectOf(a, g))
            drawLineEnd(a.endStyle(), p1, p2, c, sw)
            drawLineEnd(a.lineStart, p2, p1, c, sw)
        }
        AnnotKind.RECT, AnnotKind.OVAL -> a.rects.firstOrNull()?.let { r ->
            val s = g.pdfRectToScreen(r)
            if (a.kind == AnnotKind.OVAL && a.subject == com.smartdocs.editor.core.annot.AnnotStyle.TICKMARK) { drawTickmark(a, s); return@let }
            val fill = a.fillColor?.let { annotColor(it, a.opacity) }
            val stroke = Stroke(sw, pathEffect = dashEffectOf(a, g))
            if (a.cloud > 0 && a.corner <= 0f) {
                // cloudy rectangle / oval (Nitro 7.27), scallops in or out (Foxit 24.33)
                val n = PageGeometry.normPdf(r); val lv = a.cloud.coerceIn(1, 3)
                val pts = if (a.kind == AnnotKind.OVAL) com.smartdocs.editor.core.annot.CloudPath.ellipse(n.left, n.bottom, n.right, n.top, lv, a.strokeWidth)
                    else listOf(n.left to n.bottom, n.right to n.bottom, n.right to n.top, n.left to n.top)
                val path = com.smartdocs.editor.ui.reader.annot.polyPath(pts, g, true, lv, a.strokeWidth, a.cloudInward)
                fill?.let { drawPath(path, it) }
                if (!a.noBorder) drawPath(path, c, style = Stroke(sw))
            } else if (a.kind == AnnotKind.RECT && a.corner > 0f) {
                // nitro-fillsign2: rounded rectangle (radius clamped like the saved appearance)
                val rad = androidx.compose.ui.geometry.CornerRadius(com.smartdocs.editor.core.fillsign2.RoundRect.radius(s.width, s.height, a.corner * g.scale))
                fill?.let { drawRoundRect(it, s.topLeft, s.size, rad) }
                if (!a.noBorder) drawRoundRect(c, s.topLeft, s.size, rad, style = stroke)
            } else if (a.kind == AnnotKind.RECT) {
                fill?.let { drawRect(it, s.topLeft, s.size) }
                if (!a.noBorder) drawRect(c, s.topLeft, s.size, style = stroke)
            } else {
                fill?.let { drawOval(it, s.topLeft, s.size) }
                if (!a.noBorder) drawOval(c, s.topLeft, s.size, style = stroke)
            }
        }
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

/** Dash pattern of [a] scaled to the screen (null when solid). */
fun dashEffectOf(a: Annot, g: PageGeometry): androidx.compose.ui.graphics.PathEffect? =
    com.smartdocs.editor.core.view5.DashStyle.entries.getOrElse(a.dash) { com.smartdocs.editor.core.view5.DashStyle.SOLID }
        .scaled(a.strokeWidth)?.map { it * g.scale }?.toFloatArray()?.let { androidx.compose.ui.graphics.PathEffect.dashPathEffect(it, 0f) }

/** Preview of a PDF line ending ([name] = /LE value) at [to] for a line coming from [from]. */
fun DrawScope.drawLineEnd(name: String, from: Offset, to: Offset, color: Color, sw: Float) {
    val ang = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val len = (sw * 4f + 5f * (sw / 2f).coerceAtLeast(1f))
    fun pt(d: Float, a: Double) = Offset(to.x + (d * cos(a)).toFloat(), to.y + (d * sin(a)).toFloat())
    when (name) {
        "OpenArrow", "ClosedArrow" -> {
            val p = Path().apply { val a1 = pt(len, ang + Math.PI - 0.45); val b1 = pt(len, ang + Math.PI + 0.45); moveTo(a1.x, a1.y); lineTo(to.x, to.y); lineTo(b1.x, b1.y); if (name == "ClosedArrow") close() }
            if (name == "ClosedArrow") drawPath(p, color) else drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round))
        }
        "ROpenArrow", "RClosedArrow" -> {
            val p = Path().apply { val a1 = pt(len, ang - 0.45); val b1 = pt(len, ang + 0.45); moveTo(a1.x, a1.y); lineTo(to.x, to.y); lineTo(b1.x, b1.y); if (name == "RClosedArrow") close() }
            if (name == "RClosedArrow") drawPath(p, color) else drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round))
        }
        "Butt" -> drawLine(color, pt(len * 0.6f, ang + Math.PI / 2), pt(len * 0.6f, ang - Math.PI / 2), sw)
        "Slash" -> drawLine(color, pt(len * 0.6f, ang + Math.PI / 3), pt(len * 0.6f, ang + Math.PI / 3 + Math.PI), sw)
        "Square" -> { val h = len * 0.4f; drawRect(color, Offset(to.x - h, to.y - h), Size(h * 2, h * 2)) }
        "Circle" -> drawCircle(color, len * 0.4f, to)
        "Diamond" -> {
            val h = len * 0.5f
            val p = Path().apply { val q = listOf(pt(h, ang), pt(h, ang + Math.PI / 2), pt(h, ang + Math.PI), pt(h, ang - Math.PI / 2)); moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(p, color)
        }
        else -> {}
    }
}

val NOTE_INDICATOR_KINDS = setOf(AnnotKind.HIGHLIGHT, AnnotKind.UNDERLINE, AnnotKind.STRIKEOUT, AnnotKind.SQUIGGLY, AnnotKind.INK,
    AnnotKind.LINE, AnnotKind.ARROW, AnnotKind.RECT, AnnotKind.OVAL, AnnotKind.POLYGON, AnnotKind.POLYLINE, AnnotKind.CLOUD)
val NOTE_INDICATOR_SUBTYPES = setOf("Highlight", "Underline", "StrikeOut", "Squiggly", "Ink", "Line", "Square", "Circle", "Polygon", "PolyLine")

/** Small sticky-note glyph at the top-right corner of [bounds] (PDF, top = larger y): this markup has a note. */
fun DrawScope.drawNoteIndicator(bounds: RectF, g: PageGeometry, color: Color) {
    val o = g.pdfToScreen(bounds.right, bounds.top)
    val sz = (9f * g.scale).coerceIn(10f, 28f)
    val tl = Offset(o.x - sz * 0.2f, o.y - sz * 0.8f)
    drawRect(color, tl, Size(sz, sz * 0.8f))
    drawRect(Color(0xFF424242), tl, Size(sz, sz * 0.8f), style = Stroke(1.2f))
    drawLine(Color(0xFF424242), Offset(tl.x + sz * 0.2f, tl.y + sz * 0.3f), Offset(tl.x + sz * 0.8f, tl.y + sz * 0.3f), 1.2f)
    drawLine(Color(0xFF424242), Offset(tl.x + sz * 0.2f, tl.y + sz * 0.55f), Offset(tl.x + sz * 0.6f, tl.y + sz * 0.55f), 1.2f)
}

/** Numbered / lettered / symbol tickmark preview (filled circle + white label). */
fun DrawScope.drawTickmark(a: Annot, s: Rect) {
    drawOval(annotColor(a.color, a.opacity), s.topLeft, s.size)
    val symbol = a.text.startsWith("zd:")
    val label = if (symbol) tickSymbolPreview(a.text.removePrefix("zd:")) else a.text
    drawIntoCanvas { cv ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = s.height * 0.62f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        cv.nativeCanvas.drawText(label, s.center.x, s.center.y + paint.textSize * 0.35f, paint)
    }
}

/** Screen glyph of a ZapfDingbats tickmark code (the palette of [com.smartdocs.editor.ui.reader.annot.TickSymbols]). */
fun tickSymbolPreview(code: String): String = com.smartdocs.editor.ui.reader.annot.TickSymbols.all.firstOrNull { it.first == code }?.second ?: "?"

fun DrawScope.drawArrowHead(from: Offset, to: Offset, color: Color, sw: Float) {
    val ang = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val len = 10f + sw * 2
    for (s in listOf(-1, 1)) {
        val t = ang + s * Math.PI / 7
        drawLine(color, to, Offset(to.x - (len * cos(t)).toFloat(), to.y - (len * sin(t)).toFloat()), sw, cap = StrokeCap.Round)
    }
}

/** Multi-line text inside a rect using the native canvas (handles Arabic shaping and RTL alignment). */
fun DrawScope.drawTextBlock(text: String, box: Rect, textSizePx: Float, color: Color, rtl: Boolean = text.any { it in '\u0600'..'\u06FF' },
                            bold: Boolean = false, italic: Boolean = false, underline: Boolean = false, strike: Boolean = false, family: Int = 0,
                            align: Int = -1, lineSpacing: Float = 1.3f, letterSpacingPx: Float = 0f, scaleX: Float = 1f) {
    if (text.isBlank()) return
    drawIntoCanvas { cv ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb(); textSize = textSizePx
            textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
            isFakeBoldText = bold; textSkewX = if (italic) -0.22f else 0f; isUnderlineText = underline; isStrikeThruText = strike
            typeface = when (family) { 1 -> android.graphics.Typeface.SERIF; 2 -> android.graphics.Typeface.MONOSPACE; else -> android.graphics.Typeface.DEFAULT }
            textScaleX = scaleX
            if (textSizePx > 0f) letterSpacing = letterSpacingPx / textSizePx
            textAlign = when (align) { 1 -> Paint.Align.CENTER; 2 -> if (rtl) Paint.Align.LEFT else Paint.Align.RIGHT; 0 -> if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT; else -> textAlign }
        }
        val pad = 4f * (textSizePx / 14f).coerceAtLeast(0.5f)
        var y = box.top + textSizePx + pad * 0.5f
        val x = when (paint.textAlign) { Paint.Align.CENTER -> box.center.x; Paint.Align.RIGHT -> box.right - pad; else -> box.left + pad }
        text.split('\n').forEach { line ->
            if (y > box.bottom + textSizePx) return@forEach
            cv.nativeCanvas.drawText(line, x, y, paint)
            y += textSizePx * lineSpacing
        }
    }
}

/** Comb text (nitro-fillsign2): one glyph centred in each of [cells] equal cells of [box], like the saved appearance. */
fun DrawScope.drawCombText(text: String, cells: Int, box: Rect, textSizePx: Float, color: Color) {
    if (text.isBlank()) return
    drawIntoCanvas { cv ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb(); textSize = textSizePx }
        val y = box.center.y + textSizePx * 0.35f
        com.smartdocs.editor.core.fillsign2.CombLayout.place(text, cells, text.any { it in '\u0600'..'\u06FF' }).forEach { (cell, ch) ->
            cv.nativeCanvas.drawText(ch, com.smartdocs.editor.core.fillsign2.CombLayout.glyphX(box.left, box.width, cells, cell, paint.measureText(ch)), y, paint)
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
