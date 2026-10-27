package com.smartdocs.editor.ui.reader.annot

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.smartdocs.editor.core.annot.Annot2Writer
import com.smartdocs.editor.core.annot.CloudPath
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.reader.annotBounds
import com.smartdocs.editor.ui.reader.annotColor
import com.smartdocs.editor.ui.reader.drawAnnot
import com.smartdocs.editor.ui.reader.mapAnnot
import com.smartdocs.editor.ui.reader.drawAnnotFrame
import com.smartdocs.editor.ui.reader.frameRect

/** Path of a polygon / polyline (cloudy when [cloud] > 0) in unrotated screen space. */
fun polyPath(pts: List<Pair<Float, Float>>, g: PageGeometry, closed: Boolean, cloud: Int, lineWidth: Float): Path {
    val p = Path()
    if (cloud > 0 && pts.size >= 3) {
        CloudPath.build(pts, cloud, lineWidth).forEachIndexed { i, c ->
            val s0 = g.pdfToScreen(c.x0, c.y0); val s1 = g.pdfToScreen(c.x1, c.y1); val s2 = g.pdfToScreen(c.x2, c.y2); val s3 = g.pdfToScreen(c.x3, c.y3)
            if (i == 0) p.moveTo(s0.x, s0.y)
            p.cubicTo(s1.x, s1.y, s2.x, s2.y, s3.x, s3.y)
        }
        p.close(); return p
    }
    pts.forEachIndexed { i, (x, y) -> val o = g.pdfToScreen(x, y); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
    if (closed) p.close()
    return p
}

/** Live preview of the annotation kinds of MODULE_annot2 (called from drawAnnot for kinds it does not know). */
fun DrawScope.drawAnnot2(a: Annot, g: PageGeometry) {
    val c = annotColor(a.color, a.opacity)
    val sw = (a.strokeWidth * g.scale).coerceAtLeast(1f)
    when (a.kind) {
        AnnotKind.POLYGON, AnnotKind.POLYLINE, AnnotKind.CLOUD -> {
            val closed = a.kind != AnnotKind.POLYLINE
            val path = polyPath(a.points, g, closed, if (a.kind == AnnotKind.CLOUD) a.cloud.coerceIn(1, 2) else 0, a.strokeWidth)
            if (closed) a.fillColor?.let { drawPath(path, annotColor(it, a.opacity), style = Fill) }
            drawPath(path, c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        AnnotKind.CARET -> a.rects.firstOrNull()?.let { drawCaret(g.pdfRectToScreen(it), c) }
        AnnotKind.REPLACE -> {
            a.rects.forEach { r ->
                val s = g.pdfRectToScreen(r)
                drawLine(c, Offset(s.left, s.center.y), Offset(s.right, s.center.y), strokeWidth = (1.2f * g.scale).coerceAtLeast(1.5f))
            }
            a.rects.lastOrNull()?.let { r ->
                val n = PageGeometry.normPdf(r)
                drawCaret(g.pdfRectToScreen(Annot2Writer.caretBox(n.right, n.bottom, n.top - n.bottom)), c)
            }
        }
        AnnotKind.FILE_ATTACH, AnnotKind.SOUND -> a.rects.firstOrNull()?.let { drawMediaIcon(a.kind, g.pdfRectToScreen(it), c) }
        // the in-page video box (the poster is drawn on top by InPageVideoLayer)
        AnnotKind.VIDEO -> a.rects.firstOrNull()?.let { drawVideoBox(g.pdfRectToScreen(it)) }
        else -> {}
    }
}

private fun DrawScope.drawCaret(s: androidx.compose.ui.geometry.Rect, c: Color) {
    val w = s.width; val h = s.height; val x = s.left; val yb = s.bottom
    val p = Path().apply {
        moveTo(x, yb)
        cubicTo(x + w * .25f, yb - h * .15f, x + w * .42f, yb - h * .55f, x + w * .5f, yb - h)
        cubicTo(x + w * .58f, yb - h * .55f, x + w * .75f, yb - h * .15f, x + w, yb)
        cubicTo(x + w * .7f, yb - h * .12f, x + w * .55f, yb - h * .35f, x + w * .5f, yb - h * .55f)
        cubicTo(x + w * .45f, yb - h * .35f, x + w * .3f, yb - h * .12f, x, yb)
        close()
    }
    drawPath(p, c)
}

/** Black video box with a round play button (same look as the PDF appearance written by VideoPdf). */
fun DrawScope.drawVideoBox(s: androidx.compose.ui.geometry.Rect) {
    drawRect(Color.Black, s.topLeft, s.size)
    drawPlayButton(s)
}

fun DrawScope.drawPlayButton(s: androidx.compose.ui.geometry.Rect) {
    val r = (minOf(s.width, s.height) * 0.17f).coerceIn(8f, 90f)
    val c = s.center
    drawCircle(Color.Black.copy(alpha = 0.55f), r, c)
    val t = r * 0.55f
    drawPath(Path().apply { moveTo(c.x - t * 0.6f, c.y - t); lineTo(c.x + t, c.y); lineTo(c.x - t * 0.6f, c.y + t); close() }, Color.White)
}

/** Paperclip / speaker / film icon inside screen rect [s]. */
fun DrawScope.drawMediaIcon(kind: AnnotKind, s: androidx.compose.ui.geometry.Rect, c: Color) {
    val w = s.width; val h = s.height; val l = s.left; val t = s.top
    val lw = (w / 14f).coerceAtLeast(1.2f)
    drawRoundRect(Color.White, s.topLeft, s.size, CornerRadius(w * .12f))
    drawRoundRect(c, s.topLeft, s.size, CornerRadius(w * .12f), style = Stroke(lw))
    when (kind) {
        AnnotKind.SOUND -> {
            val p = Path().apply {
                moveTo(l + w * .18f, t + h * .38f); lineTo(l + w * .32f, t + h * .38f); lineTo(l + w * .52f, t + h * .2f)
                lineTo(l + w * .52f, t + h * .8f); lineTo(l + w * .32f, t + h * .62f); lineTo(l + w * .18f, t + h * .62f); close()
            }
            drawPath(p, c)
            drawArc(c, -50f, 100f, false, Offset(l + w * .45f, t + h * .32f), Size(w * .26f, h * .36f), style = Stroke(lw, cap = StrokeCap.Round))
            drawArc(c, -55f, 110f, false, Offset(l + w * .42f, t + h * .2f), Size(w * .44f, h * .6f), style = Stroke(lw, cap = StrokeCap.Round))
        }
        AnnotKind.VIDEO -> {
            drawRect(c, Offset(l + w * .16f, t + h * .24f), Size(w * .68f, h * .52f), style = Stroke(lw))
            val p = Path().apply { moveTo(l + w * .42f, t + h * .36f); lineTo(l + w * .64f, t + h * .5f); lineTo(l + w * .42f, t + h * .64f); close() }
            drawPath(p, c)
        }
        else -> {
            val x1 = l + w * .36f; val x2 = l + w * .64f; val k = (x2 - x1) / 2
            val p = Path().apply {
                moveTo(x2, t + h * .7f); lineTo(x2, t + h * .28f)
                cubicTo(x2, t + h * .28f - k * 1.3f, x1, t + h * .28f - k * 1.3f, x1, t + h * .28f)
                lineTo(x1, t + h * .78f)
                cubicTo(x1, t + h * .78f + k * 1.6f, x2 + k * .4f, t + h * .78f + k * 1.6f, x2 + k * .4f, t + h * .78f)
                lineTo(x2 + k * .4f, t + h * .38f)
            }
            drawPath(p, c, style = Stroke(lw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * Overlays of MODULE_annot2 for one page (unrotated screen space, inside the rotation transform):
 * polygon being drawn, lasso / snapshot / replace rectangle, and the multi-selection frames.
 */
fun DrawScope.drawAnnot2Overlay(state: ReaderState, page: Int, g: PageGeometry, accent: Color) {
    val h = state.annot2
    // polygon / polyline / cloud in progress
    if (h.polyPage == page && h.vertices.isNotEmpty() && state.tool in h.polyTools) {
        val c = annotColor(state.color, state.opacity)
        val sw = (state.thickness * g.scale).coerceAtLeast(1.5f)
        val pts = h.vertices.toList()
        val cloud = if (state.tool == ReaderTool.CLOUD) h.cloudIntensity else 0
        if (pts.size >= 2) {
            val path = polyPath(pts, g, false, 0, state.thickness)
            drawPath(path, c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (state.tool != ReaderTool.POLYLINE && pts.size >= 3) {
                // closing edge (dashed) and, for clouds, the scalloped outline it will become
                val a = g.pdfToScreen(pts.last().first, pts.last().second); val b = g.pdfToScreen(pts.first().first, pts.first().second)
                drawLine(c.copy(alpha = 0.6f), a, b, sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                if (cloud > 0) drawPath(polyPath(pts, g, true, cloud, state.thickness), c.copy(alpha = 0.45f), style = Stroke(sw * 0.8f))
                h.fillColor?.let { drawPath(polyPath(pts, g, true, cloud, state.thickness), annotColor(it, 0.25f), blendMode = BlendMode.Multiply) }
            }
        }
        pts.forEachIndexed { i, (x, y) ->
            val o = g.pdfToScreen(x, y)
            drawCircle(Color.White, 9f, o); drawCircle(if (i == 0) accent else c, 9f, o, style = Stroke(3f))
        }
    }
    // drag in progress
    if (h.dragPage == page && h.dragPts.isNotEmpty()) {
        when (state.tool) {
            ReaderTool.SELECT -> if (h.moveOffset == Offset.Zero && h.dragPts.size >= 2) {
                val p = Path(); h.dragPts.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }; p.close()
                drawPath(p, accent.copy(alpha = 0.12f))
                drawPath(p, accent, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
            }
            ReaderTool.SNAPSHOT, ReaderTool.REPLACE, ReaderTool.VIDEO -> h.dragRect?.let { r ->
                drawRect(accent.copy(alpha = 0.12f), r.topLeft, r.size)
                drawRect(accent, r.topLeft, r.size, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
            }
            else -> {}
        }
    }
    // multi-selection
    val m = h.multi
    if (m.active && m.page == page) {
        val off = h.moveOffset
        val thin = accent.copy(alpha = 0.7f)
        m.pendingAnnots().forEach { a -> val r = frameRect(annotBounds(a), g).translate(off); drawRect(thin, r.topLeft, r.size, style = Stroke(1.5f)) }
        m.stored.forEach { e -> val r = frameRect(PageGeometry.normPdf(e.rect), g).translate(off); drawRect(thin, r.topLeft, r.size, style = Stroke(1.5f)) }
        m.bounds()?.let { b -> drawAnnotFrame(frameRect(b, g).inflate(6f).translate(off), accent, false) }
        // pending ones follow the finger while moving
        if (off != Offset.Zero) m.pendingAnnots().forEach { a ->
            val b = annotBounds(a)
            val dx = off.x / g.scale; val dy = -off.y / g.scale
            drawAnnot(mapAnnot(a, b, android.graphics.RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)), g)
        }
    }
}
