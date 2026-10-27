package com.smartdocs.editor.ui.office

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.office5.PShape
import kotlin.math.roundToInt

/** Presentation editor: ribbon, slide thumbnails (bottom on phones, side column on tablets), slide canvas. */
@Composable
fun SlideEditor(s: SlideState) {
    val tick = s.tick
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 720.dp
        Column(Modifier.fillMaxSize()) {
            SlideRibbon(s)
            if (s.findOpen) SlideFindBar(s)   // office6
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide && s.thumbs) Thumbnails(s, vertical = true, Modifier.width(170.dp).fillMaxHeight())
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFDCDFE3)).padding(12.dp), contentAlignment = Alignment.Center) {
                    val (sw, sh) = s.size
                    val ratio = if (sh > 0) sw.toFloat() / sh else 16f / 9f
                    // the whole slide stays visible: limited by the width or by the height
                    val w = if (maxHeight * ratio < maxWidth) maxHeight * ratio else maxWidth
                    Box(Modifier.width(w)) { EditCanvas(s) }
                }
            }
            if (!wide && s.thumbs) Thumbnails(s, vertical = false, Modifier.fillMaxWidth().height(92.dp))
        }
    }
    s.showFrom?.let { from -> SlideShow(s, from) { s.showFrom = null; s.bump() } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumbnails(s: SlideState, vertical: Boolean, modifier: Modifier) {
    val tick = s.tick
    var menu by remember { mutableStateOf(-1) }
    val items = (0 until s.count).toList()
    @Composable
    fun thumb(i: Int, m: Modifier) {
        Box(m.padding(4.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SlideView(s.slide(i), Modifier.clip(RoundedCornerShape(4.dp)).border(if (i == s.current) 2.dp else 1.dp, if (i == s.current) MaterialTheme.colorScheme.primary else Color(0xFFB0B4BA), RoundedCornerShape(4.dp))
                    .combinedClickable(onClick = { s.go(i) }, onLongClick = { s.go(i); menu = i }))
                Text("${i + 1}", fontSize = 10.sp)
            }
            DropdownMenu(menu == i, { menu = -1 }) {
                DropdownMenuItem({ Text(stringResource(R.string.o5_duplicate_slide)) }, { menu = -1; s.duplicate() })
                DropdownMenuItem({ Text(stringResource(R.string.o5_move_up)) }, { menu = -1; s.moveSlide(-1) })
                DropdownMenuItem({ Text(stringResource(R.string.o5_move_down)) }, { menu = -1; s.moveSlide(1) })
                DropdownMenuItem({ Text(stringResource(R.string.o5_delete_slide)) }, { menu = -1; s.deleteSlide() }, enabled = s.count > 1)
            }
        }
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        if (vertical) LazyColumn(Modifier.fillMaxSize()) {
            items(items) { i -> thumb(i, Modifier.fillMaxWidth()) }
            item { IconButton(onClick = { s.newSlide() }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, stringResource(R.string.o5_new_slide)) } }
        } else LazyRow(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            items(items) { i -> thumb(i, Modifier.width(120.dp)) }
            item { IconButton(onClick = { s.newSlide() }) { Icon(Icons.Default.Add, stringResource(R.string.o5_new_slide)) } }
        }
    }
}

/** The editable slide: tap selects, double tap edits text in place, drag moves, corner handles resize. */
@Composable
private fun EditCanvas(s: SlideState) {
    val tick = s.tick
    val slide = s.slide(s.current)
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(1) }
    val k = widthPx.toFloat() / slide.width
    val sel = s.selectedShape()
    val handle = with(density) { 14.dp.toPx() }
    Box(Modifier.fillMaxWidth().wrapContentHeight().onSizeChanged { widthPx = it.width.coerceAtLeast(1) }) {
        SlideView(slide, Modifier.fillMaxWidth().background(Color.White)
            .pointerInput(s.current, k) {
                detectTapGestures(
                    onTap = { p -> s.select(hitShape(s, p, k)) },
                    onDoubleTap = { p -> val e = hitShape(s, p, k); s.select(e); if (e != null) s.beginText() },
                )
            }
            .pointerInput(s.current, k, sel?.elem) {
                var mode = 0 // 1 move, 2 resize br, 3 resize tl, 4 resize tr, 5 resize bl
                var bx = 0f; var by = 0f; var bw = 0f; var bh = 0f; var first = true
                detectDragGestures(
                    onDragStart = { p ->
                        val sh = s.selectedShape()
                        mode = 0; first = true
                        if (sh != null && sh.elem != null && !s.editingText) {
                            bx = sh.x * k; by = sh.y * k; bw = sh.w * k; bh = sh.h * k
                            mode = when {
                                (p - Offset(bx + bw, by + bh)).getDistance() < handle * 1.6f -> 2
                                (p - Offset(bx, by)).getDistance() < handle * 1.6f -> 3
                                (p - Offset(bx + bw, by)).getDistance() < handle * 1.6f -> 4
                                (p - Offset(bx, by + bh)).getDistance() < handle * 1.6f -> 5
                                p.x in bx..(bx + bw) && p.y in (by - handle)..(by + bh + handle) -> 1
                                else -> 0
                            }
                        }
                    },
                    onDrag = { ch, d ->
                        val e = s.selected
                        if (mode != 0 && e != null) {
                            ch.consume()
                            when (mode) {
                                1 -> { bx += d.x; by += d.y }
                                2 -> { bw += d.x; bh += d.y }
                                3 -> { bx += d.x; by += d.y; bw -= d.x; bh -= d.y }
                                4 -> { by += d.y; bw += d.x; bh -= d.y }
                                5 -> { bx += d.x; bw -= d.x; bh += d.y }
                            }
                            val min = handle
                            s.setBounds(e, (bx / k).toLong(), (by / k).toLong(), (maxOf(bw, min) / k).toLong(), (maxOf(bh, if (s.selectedShape()?.kind == PShape.Kind.LINE) 0f else min) / k).toLong(), first)
                            first = false
                        }
                    },
                )
            }, hide = if (s.editingText) s.selected else null)
        // selection frame and handles
        if (sel != null && !s.editingText) {
            val x = sel.x * k; val y = sel.y * k; val w = sel.w * k; val h = sel.h * k
            with(density) {
                Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(w.toDp(), maxOf(h, 2f).toDp()).border(1.5.dp, MaterialTheme.colorScheme.primary))
                listOf(Offset(x, y), Offset(x + w, y), Offset(x, y + h), Offset(x + w, y + h)).forEach { c ->
                    Box(Modifier.offset { IntOffset((c.x - handle / 2).roundToInt(), (c.y - handle / 2).roundToInt()) }.size(handle.toDp()).clip(RoundedCornerShape(50)).background(Color.White).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                }
            }
        }
        // in-place text editing
        if (sel != null && s.editingText) InPlaceText(s, sel, k)
    }
}

private fun hitShape(s: SlideState, p: Offset, k: Float) =
    s.slide(s.current).shapes.lastOrNull { sh -> sh.elem != null && p.x in (sh.x * k)..((sh.x + sh.w) * k).coerceAtLeast(sh.x * k + 24f) && p.y in (sh.y * k - 12f)..((sh.y + sh.h) * k + 12f) }?.elem

@Composable
private fun InPlaceText(s: SlideState, sh: PShape, k: Float) {
    val density = LocalDensity.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(sh.elem) { runCatching { fr.requestFocus() } }
    val first = sh.paragraphs.firstOrNull()
    val run = first?.runs?.firstOrNull()
    val pt = (run?.sizePt ?: sh.defaultSize) * sh.fontScale
    val spPx = density.density * density.fontScale
    val x = sh.x * k; val y = sh.y * k; val w = sh.w * k; val h = sh.h * k
    with(density) {
        Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(w.toDp(), maxOf(h, 40f).toDp()).border(2.dp, MaterialTheme.colorScheme.primary).background(Color.White.copy(alpha = 0.85f))) {
            BasicTextField(
                s.text, { s.text = it; s.bump() },
                Modifier.fillMaxSize().focusRequester(fr).padding((sh.insets[0] * k).toDp(), (sh.insets[1] * k).toDp()),
                textStyle = TextStyle(
                    fontSize = (pt * 12700f * k / spPx).sp, color = Color.hex(run?.color) ?: Color.hex(sh.defaultColor) ?: Color.Black,
                    textAlign = when (first?.align) { "ctr" -> TextAlign.Center; "r" -> TextAlign.Right; "l" -> TextAlign.Left; else -> TextAlign.Start },
                    textDirection = if (first?.rtl == true) TextDirection.Rtl else TextDirection.Content,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
            SmallFloatingActionButton(onClick = { s.commitText() }, Modifier.align(Alignment.TopEnd).offset(12.dp, (-16).dp)) { Icon(Icons.Default.Check, stringResource(R.string.o5_ok)) }
        }
    }
}
