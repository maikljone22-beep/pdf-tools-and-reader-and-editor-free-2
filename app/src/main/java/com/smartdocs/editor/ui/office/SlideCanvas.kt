package com.smartdocs.editor.ui.office

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.core.office5.PPara
import com.smartdocs.editor.core.office5.PShape
import com.smartdocs.editor.core.office5.PSlide

/** Draws a slide (background, shapes, pictures, text, tables) scaled to the composable's width. */
@Composable
fun SlideView(slide: PSlide, modifier: Modifier = Modifier, hide: Any? = null) {
    val tm = rememberTextMeasurer(cacheSize = 64)
    val d = LocalDensity.current
    val ratio = if (slide.height > 0) slide.width.toFloat() / slide.height else 16f / 9f
    val bg = remember(slide) { decodeImage(slide.backgroundImage, 1600) }
    Canvas(modifier.aspectRatio(ratio)) {
        val k = size.width / slide.width
        drawRect(Color.hex(slide.background) ?: Color.White)
        bg?.let { drawImage(it, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
        clipRect {
            for (sh in slide.shapes) if (hide == null || sh.elem !== hide) drawShape(sh, k, tm, d.density * d.fontScale)
        }
    }
}

fun DrawScope.drawShape(sh: PShape, k: Float, tm: TextMeasurer, spPx: Float) {
    val x = sh.x * k; val y = sh.y * k; val w = sh.w * k; val h = sh.h * k
    rotate(sh.rot, Offset(x + w / 2, y + h / 2)) {
        scale(if (sh.flipH) -1f else 1f, if (sh.flipV) -1f else 1f, Offset(x + w / 2, y + h / 2)) {
            when (sh.kind) {
                PShape.Kind.LINE -> {
                    val c = Color.hex(sh.line) ?: Color.Black
                    drawLine(c, Offset(x, y), Offset(x + w, y + h), (sh.lineW * k).coerceAtLeast(1f))
                }
                PShape.Kind.PICTURE -> {
                    val bmp = decodeImage(sh.image, 1600)
                    if (bmp != null) drawImage(bmp, dstOffset = IntOffset(x.toInt(), y.toInt()), dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)))
                    else drawRect(Color(0xFFE0E0E0), Offset(x, y), Size(w, h))
                    Color.hex(sh.line)?.let { drawRect(it, Offset(x, y), Size(w, h), style = Stroke((sh.lineW * k).coerceAtLeast(1f))) }
                }
                PShape.Kind.TABLE -> drawTable(sh, k, tm, spPx)
                PShape.Kind.SHAPE -> {
                    val path = geometry(sh.geom, x, y, w, h)
                    Color.hex(sh.fill)?.let { c -> if (path != null) drawPath(path, c) else drawRect(c, Offset(x, y), Size(w, h)) }
                    Color.hex(sh.line)?.let { c -> val st = Stroke((sh.lineW * k).coerceAtLeast(1f)); if (path != null) drawPath(path, c, style = st) else drawRect(c, Offset(x, y), Size(w, h), style = st) }
                }
            }
        }
        if (sh.kind == PShape.Kind.SHAPE && sh.paragraphs.any { p -> p.runs.any { it.text.isNotBlank() } }) drawTextBody(sh, x, y, w, h, k, tm, spPx)
    }
}

/** Preset geometry outline; null means a plain rectangle. */
private fun geometry(prst: String, x: Float, y: Float, w: Float, h: Float): Path? {
    val p = Path()
    when (prst) {
        "ellipse", "circle", "flowChartConnector" -> p.addOval(androidx.compose.ui.geometry.Rect(x, y, x + w, y + h))
        "roundRect", "flowChartAlternateProcess" -> { val r = minOf(w, h) * 0.16f; p.addRoundRect(androidx.compose.ui.geometry.RoundRect(x, y, x + w, y + h, CornerRadius(r, r))) }
        "triangle", "flowChartExtract" -> { p.moveTo(x + w / 2, y); p.lineTo(x + w, y + h); p.lineTo(x, y + h); p.close() }
        "rtTriangle" -> { p.moveTo(x, y); p.lineTo(x + w, y + h); p.lineTo(x, y + h); p.close() }
        "diamond", "flowChartDecision" -> { p.moveTo(x + w / 2, y); p.lineTo(x + w, y + h / 2); p.lineTo(x + w / 2, y + h); p.lineTo(x, y + h / 2); p.close() }
        "rightArrow" -> { val s = h * 0.25f; val hx = x + w - minOf(w * 0.4f, h); p.moveTo(x, y + s); p.lineTo(hx, y + s); p.lineTo(hx, y); p.lineTo(x + w, y + h / 2); p.lineTo(hx, y + h); p.lineTo(hx, y + h - s); p.lineTo(x, y + h - s); p.close() }
        "leftArrow" -> { val s = h * 0.25f; val hx = x + minOf(w * 0.4f, h); p.moveTo(x + w, y + s); p.lineTo(hx, y + s); p.lineTo(hx, y); p.lineTo(x, y + h / 2); p.lineTo(hx, y + h); p.lineTo(hx, y + h - s); p.lineTo(x + w, y + h - s); p.close() }
        "hexagon" -> { val i = w * 0.25f; p.moveTo(x + i, y); p.lineTo(x + w - i, y); p.lineTo(x + w, y + h / 2); p.lineTo(x + w - i, y + h); p.lineTo(x + i, y + h); p.lineTo(x, y + h / 2); p.close() }
        "parallelogram" -> { val i = w * 0.25f; p.moveTo(x + i, y); p.lineTo(x + w, y); p.lineTo(x + w - i, y + h); p.lineTo(x, y + h); p.close() }
        "pentagon", "homePlate" -> { val i = w * 0.2f; p.moveTo(x, y); p.lineTo(x + w - i, y); p.lineTo(x + w, y + h / 2); p.lineTo(x + w - i, y + h); p.lineTo(x, y + h); p.close() }
        else -> return null
    }
    return p
}

private fun paragraphs(ps: List<PPara>, sh: PShape, k: Float, spPx: Float): AnnotatedString = buildAnnotatedString {
    ps.forEachIndexed { i, p ->
        val align = when (p.align) { "ctr" -> TextAlign.Center; "r" -> if (p.rtl) TextAlign.Start else TextAlign.Right; "just", "dist" -> TextAlign.Justify; "l" -> if (p.rtl) TextAlign.End else TextAlign.Left; else -> TextAlign.Start }
        val indent = (p.level * 0.5f * 72f * 12700f * k / spPx).sp
        withStyle(ParagraphStyle(textAlign = align, textDirection = if (p.rtl) TextDirection.Rtl else TextDirection.Content, textIndent = TextIndent(indent, indent))) {
            p.bullet?.let { b -> append(b); append(' ') }
            for (r in p.runs) {
                val pt = (r.sizePt ?: sh.defaultSize) * sh.fontScale
                val deco = listOfNotNull(TextDecoration.Underline.takeIf { r.underline }, TextDecoration.LineThrough.takeIf { r.strike })
                withStyle(SpanStyle(
                    color = Color.hex(r.color) ?: Color.hex(sh.defaultColor) ?: Color.Black,
                    fontSize = (pt * 12700f * k / spPx).sp,
                    fontWeight = if (r.bold) FontWeight.Bold else null, fontStyle = if (r.italic) FontStyle.Italic else null,
                    textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
                )) { append(r.text) }
            }
            if (p.runs.isEmpty()) withStyle(SpanStyle(fontSize = (sh.defaultSize * sh.fontScale * 12700f * k / spPx).sp)) { append(" ") }
        }
        if (i < ps.size - 1) append('\n')
    }
}

private fun DrawScope.drawTextBody(sh: PShape, x: Float, y: Float, w: Float, h: Float, k: Float, tm: TextMeasurer, spPx: Float) {
    val l = sh.insets[0] * k; val t = sh.insets[1] * k; val r = sh.insets[2] * k; val b = sh.insets[3] * k
    val tw = (w - l - r).coerceAtLeast(1f)
    val text = paragraphs(sh.paragraphs, sh, k, spPx)
    val layout = runCatching { tm.measure(text, constraints = Constraints(maxWidth = tw.toInt().coerceAtLeast(1))) }.getOrNull() ?: return
    val th = layout.size.height.toFloat()
    val ty = when (sh.anchor) { "ctr" -> y + (h - th) / 2; "b" -> y + h - b - th; else -> y + t }
    drawText(layout, topLeft = Offset(x + l, ty))
}

private fun DrawScope.drawTable(sh: PShape, k: Float, tm: TextMeasurer, spPx: Float) {
    val x = sh.x * k; val y = sh.y * k; val w = sh.w * k
    val total = sh.colWidths.sum().takeIf { it > 0 } ?: 1L
    val cols = sh.table.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return
    val widths = if (sh.colWidths.size >= cols) sh.colWidths.map { it * k } else List(cols) { w / cols }
    var cy = y
    val line = Color.hex(sh.line) ?: Color.Gray
    sh.table.forEachIndexed { ri, row ->
        var cx = x
        var rowH = (sh.h * k / sh.table.size.coerceAtLeast(1))
        val layouts = row.mapIndexed { ci, cell ->
            val cw = widths.getOrElse(ci) { w / cols }
            val txt = paragraphs(cell, sh.copyForText(), k, spPx)
            runCatching { tm.measure(txt, constraints = Constraints(maxWidth = (cw - 8f).toInt().coerceAtLeast(1))) }.getOrNull()
        }
        rowH = maxOf(rowH, (layouts.maxOfOrNull { it?.size?.height ?: 0 } ?: 0) + 8f)
        if (ri == 0) drawRect(Color(0xFFDDE4F0), Offset(x, cy), Size(widths.sum(), rowH))
        row.forEachIndexed { ci, _ ->
            val cw = widths.getOrElse(ci) { w / cols }
            drawRect(line, Offset(cx, cy), Size(cw, rowH), style = Stroke(1f))
            layouts[ci]?.let { drawText(it, topLeft = Offset(cx + 4f, cy + 4f)) }
            cx += cw
        }
        cy += rowH
    }
    if (total == 0L) return
}

private fun PShape.copyForText(): PShape = PShape(null, PShape.Kind.SHAPE, 0, 0, 0, 0, defaultSize = 14f, defaultColor = defaultColor)
