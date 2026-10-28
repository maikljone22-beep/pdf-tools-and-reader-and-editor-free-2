package com.smartdocs.editor.ui.reader.annot

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.reader.AnnotTarget
import com.smartdocs.editor.ui.reader.ReaderState
import kotlin.math.roundToInt

/** 7.10: a comment's pop-up note opened as a floating window (stays open while the page scrolls or the selection changes). */
data class FloatingNote(val target: AnnotTarget, val author: String, val text: String)

object FloatingNoteGeom {
    /** Window position ([x], [y], top-left) kept inside the viewport [bw] x [bh] for a window of [w] x [h]. */
    fun clamp(x: Float, y: Float, w: Float, h: Float, bw: Float, bh: Float): Pair<Float, Float> =
        x.coerceIn(0f, (bw - w).coerceAtLeast(0f)) to y.coerceIn(0f, (bh - h).coerceAtLeast(0f))

    /** 24.18: the size after a resize-grip drag of ([dx], [dy]), kept between the minimum and the viewport [bw] x [bh]. */
    fun resize(w: Float, h: Float, dx: Float, dy: Float, minW: Float, minH: Float, bw: Float, bh: Float): Pair<Float, Float> =
        (w + dx).coerceIn(minW, bw.coerceAtLeast(minW)) to (h + dy).coerceIn(minH, bh.coerceAtLeast(minH))
}

/**
 * The floating pop-up of [CommentExtras.floating]: drag it by its title bar, edit the note and save it into the
 * comment (the comment is selected again for that, as the action bar's "Edit text" does).
 */
@Composable
fun FloatingNoteWindow(state: ReaderState, bounds: IntSize, modifier: Modifier = Modifier) {
    val cx = state.annot2.cx
    val note = cx.floating ?: return
    val density = LocalDensity.current
    var pos by remember { mutableStateOf<Offset?>(null) }
    var win by remember { mutableStateOf(IntSize.Zero) }
    var text by remember(note.target) { mutableStateOf(note.text) }
    // 24.18: window width and note-area height in px, changed by the bottom-end resize grip (null = default size)
    var boxW by remember { mutableStateOf<Float?>(null) }
    var boxH by remember { mutableStateOf<Float?>(null) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val edTarget = state.edit.target
    LaunchedEffect(edTarget) { cx.rebindFloating(edTarget) }
    val start = pos ?: Offset(bounds.width - with(density) { 276.dp.toPx() }, with(density) { 72.dp.toPx() })
    fun clamp(o: Offset) = FloatingNoteGeom.clamp(o.x, o.y, win.width.toFloat(), win.height.toFloat(), bounds.width.toFloat(), bounds.height.toFloat()).let { Offset(it.first, it.second) }
    Surface(
        modifier.absoluteOffset { val c = clamp(start); IntOffset(c.x.roundToInt(), c.y.roundToInt()) }
            .width(boxW?.let { with(density) { it.toDp() } } ?: 260.dp).onSizeChanged { win = it }.alpha(cx.noteOpacity.coerceIn(0.3f, 1f)),
        shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp, shadowElevation = 10.dp, color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(32.dp).pointerInput(bounds) { detectDragGestures { ch, d -> ch.consume(); pos = clamp((pos ?: start) + d) } }
                .padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DragIndicator, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(note.author.ifBlank { stringResource(R.string.cf_float_note) }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                IconButton(onClick = { cx.floating = null }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, stringResource(R.string.close), Modifier.size(18.dp)) }
            }
            BasicTextField(text, { text = it },
                Modifier.fillMaxWidth().then(boxH?.let { Modifier.height(with(density) { it.toDp() }) } ?: Modifier.heightIn(min = 60.dp, max = 200.dp)).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
                textStyle = TextStyle(fontSize = cx.noteFontSize.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, textDirection = TextDirection.Content),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = text != note.text && !state.edit.busy, onClick = {
                    if (cx.saveFloating(text)) cx.floating = note.copy(text = text)
                }) { Text(stringResource(R.string.save)) }
                // 24.18: resize grip; drag changes the width and the note-area height
                Icon(Icons.Default.OpenInFull, stringResource(R.string.cmr_note_resize), Modifier.size(28.dp).padding(4.dp)
                    .pointerInput(bounds, rtl) {
                        val minW = 180.dp.toPx(); val minH = 48.dp.toPx(); val chrome = 80.dp.toPx()
                        detectDragGestures { ch, d ->
                            ch.consume()
                            // in RTL the grip sits bottom-left: dragging left widens the window and moves its left edge
                            val dx = if (rtl) -d.x else d.x
                            val w0 = boxW ?: win.width.toFloat()
                            val (w, h) = FloatingNoteGeom.resize(w0, boxH ?: (win.height - chrome).coerceAtLeast(minH),
                                dx, d.y, minW, minH, bounds.width.toFloat(), (bounds.height - chrome).coerceAtLeast(minH))
                            if (rtl) pos = (pos ?: start).let { Offset(it.x - (w - w0), it.y) }
                            boxW = w; boxH = h
                        }
                    })
            }
        }
    }
}
