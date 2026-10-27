package com.smartdocs.editor.ui.edit

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.core.edit.FontFamily
import com.smartdocs.editor.core.edit.TextAlign
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/**
 * The page being edited: its own render of [EditController.page] (fit to width × zoom, scrollable), outlines of
 * the editable blocks / images, the inline text editor and the image move/resize handles.
 * Display space (points, y up) ↔ pixels: x·scale, (H − y)·scale.
 */
@Composable
internal fun EditPageView(c: EditController, modifier: Modifier = Modifier) {
    val state = c.state
    val density = LocalDensity.current
    val rtlUi = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val pad = with(density) { 12.dp.toPx() }
        val m = c.model
        val size = m?.let { it.width to it.height } ?: state.pageSizes[c.page] ?: (595f to 842f)
        val pageW = size.first; val pageH = size.second
        val viewportW = constraints.maxWidth.toFloat().coerceAtLeast(100f)
        val scale = ((viewportW - 2 * pad) * c.zoom / pageW).coerceAtLeast(0.05f)
        val wPx = pageW * scale; val hPx = pageH * scale
        val wDp = with(density) { wPx.toDp() }; val hDp = with(density) { hPx.toDp() }

        var bitmap by remember(c.page) { mutableStateOf<Bitmap?>(null) }
        val session = state.session
        LaunchedEffect(c.page, wPx.roundToInt(), session, state.renderVersion) {
            val s = session ?: return@LaunchedEffect
            if (s.isClosed) return@LaunchedEffect
            val k = (2600f / wPx).coerceAtMost(1f)
            try { bitmap = s.render(c.page, (wPx * k).roundToInt().coerceAtLeast(1), (hPx * k).roundToInt().coerceAtLeast(1), state.nightMode, true) }
            catch (e: CancellationException) { throw e } catch (ignored: Throwable) { }
        }

        fun px(x: Float) = x * scale
        fun py(y: Float) = (pageH - y) * scale

        val vScroll = rememberScrollState(); val hScroll = rememberScrollState()
        // page space is absolute (left-to-right) whatever the UI direction
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
            Box(Modifier.padding(with(density) { pad.toDp() }).size(wDp, hDp).shadow(2.dp).background(Color.White)
                .pointerInput(c.mode, scale, pageH, c.model, c.busy) {
                    detectTapGestures { o -> c.onTap(o.x / scale, pageH - o.y / scale) }
                }
                .pointerInput(c.tools5.multi, scale, pageH) {
                    // NVE-img: marquee selection in multi-select
                    if (!c.tools5.multi) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val x0 = down.position.x / scale; val y0 = pageH - down.position.y / scale
                        var moved = false
                        drag(down.id) { ch ->
                            val p = ch.position
                            ch.consume()      // the page does not scroll while a marquee can start
                            if (!moved && (p - down.position).getDistance() < 6.dp.toPx()) return@drag
                            moved = true
                            c.tools5.marquee = RectF(x0, y0, p.x / scale, pageH - p.y / scale)
                        }
                        if (moved) c.tools5.marqueeEnd()
                    }
                }
                .pointerInput(c.image, scale, pageH) {
                    if (c.image == null) return@pointerInput
                    val handle = 28.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val r = c.previewRect() ?: return@awaitEachGesture
                        val left = px(r.left); val right = px(r.right); val top = py(r.top); val bottom = py(r.bottom)
                        val p = down.position
                        val corner = Offset(right, bottom)
                        val resize = (p - corner).getDistance() < handle
                        val inside = p.x in (left - handle / 2)..(right + handle / 2) && p.y in (top - handle / 2)..(bottom + handle / 2)
                        if (!resize && !inside) return@awaitEachGesture
                        down.consume()
                        c.tools5.dragStart(8.dp.toPx() / scale)      // NVE-img: smart alignment
                        drag(down.id) { ch ->
                            val d = ch.positionChange()
                            ch.consume()
                            c.tools5.drag(resize, d.x / scale, -d.y / scale)
                        }
                        c.tools5.dragEnd()
                    }
                },
            ) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                val primary = MaterialTheme.colorScheme.primary
                val tertiary = MaterialTheme.colorScheme.tertiary
                Canvas(Modifier.fillMaxSize()) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    fun box(r: RectF, color: Color, width: Float, dashed: Boolean, fill: Color? = null) {
                        val tl = Offset(px(r.left), py(r.top))
                        val sz = Size(px(r.right) - px(r.left), py(r.bottom) - py(r.top))
                        fill?.let { drawRect(it, tl, sz) }
                        drawRect(color, tl, sz, style = Stroke(width, pathEffect = if (dashed) dash else null))
                    }
                    val model = c.model ?: return@Canvas
                    when (c.mode) {
                        EditMode.TEXT -> if (!c.editingText) model.blocks.forEach { b -> box(b.rect, primary.copy(alpha = 0.55f), 1.5f, true, primary.copy(alpha = 0.06f)) }
                        EditMode.IMAGE -> {
                            model.images.forEach { im -> if (im != c.image) box(im.rect, tertiary.copy(alpha = 0.7f), 2f, true, tertiary.copy(alpha = 0.08f)) }
                            c.image?.let { im ->
                                box(im.rect, tertiary.copy(alpha = 0.35f), 1.5f, true)
                                c.previewRect()?.let { r ->
                                    box(r, primary, 3f, false, primary.copy(alpha = 0.10f))
                                    drawCircle(primary, 11.dp.toPx(), Offset(px(r.right), py(r.bottom)))
                                    drawCircle(Color.White, 5.dp.toPx(), Offset(px(r.right), py(r.bottom)))
                                }
                            }
                            c.insertAt?.let { (x, y) -> drawCircle(primary, 8.dp.toPx(), Offset(px(x), py(y))) }
                            // NVE-img: shapes / shadings, multi-selection, marquee and smart-alignment guides
                            val t5 = c.tools5
                            t5.visibleObjects().forEach { o -> if (o.kind != com.smartdocs.editor.core.edit.ImageEdit5.Kind.IMAGE && o.op != c.image?.op)
                                box(RectF(o.box[0], o.box[3], o.box[2], o.box[1]), Color(0xFF00897B).copy(alpha = 0.6f), 1.5f, true) }
                            if (c.image != null && t5.isVector(c.image)) c.previewRect()?.let { r ->
                                box(r, primary, 3f, false, primary.copy(alpha = 0.10f))
                                drawCircle(primary, 11.dp.toPx(), Offset(px(r.right), py(r.bottom)))
                                drawCircle(Color.White, 5.dp.toPx(), Offset(px(r.right), py(r.bottom)))
                            }
                            t5.marquee?.let { mq -> box(RectF(minOf(mq.left, mq.right), maxOf(mq.top, mq.bottom), maxOf(mq.left, mq.right), minOf(mq.top, mq.bottom)), primary, 1.5f, true, primary.copy(alpha = 0.08f)) }
                            c.tools5.selected.forEach { im -> box(im.rect, primary, 3f, false, primary.copy(alpha = 0.16f)) }
                            c.tools5.guides.forEach { g ->
                                if (g.vertical) drawLine(Color(0xFFE91E63), Offset(px(g.pos), 0f), Offset(px(g.pos), this.size.height), 1.5.dp.toPx(), pathEffect = dash)
                                else drawLine(Color(0xFFE91E63), Offset(0f, py(g.pos)), Offset(this.size.width, py(g.pos)), 1.5.dp.toPx(), pathEffect = dash)
                            }
                        }
                    }
                }

                if (c.mode == EditMode.TEXT && c.editingText) InlineEditor(c, scale, pageH, rtlUi)
            }
        }
        }
    }
}

/** Text field laid over the block being edited (or the new text box), drawn with the block's size / colour. */
@Composable
private fun InlineEditor(c: EditController, scale: Float, pageH: Float, rtlUi: Boolean) {
    val density = LocalDensity.current
    if (c.unreadable) return              // nothing readable to edit: the format bar shows the notice instead
    val rect: RectF = c.block?.rect ?: c.newBox(rtlUi) ?: return
    val st = c.style
    val rtl = c.block?.takeIf { !c.replaceWhole }?.rtl ?: EditController.isRtl(c.text, c.block?.rtl ?: rtlUi)
    val x = rect.left * scale; val y = (pageH - rect.top) * scale
    val w = ((rect.right - rect.left) * scale).coerceAtLeast(with(density) { 120.dp.toPx() })
    val fontPx = st.size * scale
    val focus = remember { FocusRequester() }
    LaunchedEffect(c.block, c.newAt) { runCatching { focus.requestFocus() } }
    val color = Color(0xFF000000 or st.color.toLong())
    val textStyle = TextStyle(
        color = color,
        fontSize = with(density) { fontPx.toSp() },
        lineHeight = with(density) { ((st.lineHeight ?: c.block?.lineHeight?.takeIf { (c.block?.lineCount ?: 1) > 1 } ?: (st.size * 1.25f)) * scale).toSp() },
        fontWeight = if (st.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (st.italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = when (st.family) {
            FontFamily.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
            FontFamily.MONO -> androidx.compose.ui.text.font.FontFamily.Monospace
            FontFamily.SANS -> androidx.compose.ui.text.font.FontFamily.SansSerif
        },
        textAlign = when (st.align) {
            TextAlign.LEFT -> androidx.compose.ui.text.style.TextAlign.Left
            TextAlign.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
            TextAlign.RIGHT -> androidx.compose.ui.text.style.TextAlign.Right
        },
        textDirection = if (rtl) TextDirection.Rtl else TextDirection.ContentOrLtr,
    )
    // the field is positioned in absolute (left-to-right) page pixels whatever the UI direction
    val lum = 0.299f * ((st.color shr 16) and 0xFF) + 0.587f * ((st.color shr 8) and 0xFF) + 0.114f * (st.color and 0xFF)
    val bg = if (lum > 200f) Color(0xFF424242) else Color.White
    BasicTextField(
        value = c.text, onValueChange = { c.text = it }, textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .absoluteOffset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(with(density) { w.toDp() })
            .background(bg.copy(alpha = 0.96f))
            .border(2.dp, MaterialTheme.colorScheme.primary)
            .padding(horizontal = 2.dp)
            .focusRequester(focus),
    )
}
