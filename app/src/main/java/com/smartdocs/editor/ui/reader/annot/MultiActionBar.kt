package com.smartdocs.editor.ui.reader.annot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.ui.reader.PALETTE
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.reader.frameRect
import kotlin.math.roundToInt

private enum class MSub { NONE, COLOR, OPACITY, THICKNESS }

/**
 * Floating bar of a multi-selection on [page] (must be called inside the page's Box): colour, opacity,
 * thickness, group / ungroup, recognise handwriting (ink only), delete.
 */
@Composable
fun MultiActionBar(state: ReaderState, page: Int, g: PageGeometry) {
    val h = state.annot2
    val m = h.multi
    if (!m.active || m.page != page || h.isDragging(page)) return
    if (state.tool != null && state.tool != ReaderTool.SELECT) return
    val b = m.bounds() ?: return
    val frame = g.pdfRectToLayout(PageGeometry.normPdf(g.screenRectToPdf(frameRect(b, g))))
    var sub by remember(m.count) { mutableStateOf(MSub.NONE) }
    val enabled = !m.busy
    val allInk = m.pendingAnnots().all { it.kind == AnnotKind.INK } && m.stored.all { it.subtype == "Ink" }
    Surface(Modifier.floatAbove(frame, 12f).widthIn(max = 380.dp), shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(4.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.an_selected_n, m.count), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                MBtn(Icons.Default.Palette, R.string.ae_color, enabled, sub == MSub.COLOR) { sub = if (sub == MSub.COLOR) MSub.NONE else MSub.COLOR }
                MBtn(Icons.Default.Opacity, R.string.ae_opacity, enabled, sub == MSub.OPACITY) { sub = if (sub == MSub.OPACITY) MSub.NONE else MSub.OPACITY }
                MBtn(Icons.Default.LineWeight, R.string.ae_thickness, enabled, sub == MSub.THICKNESS) { sub = if (sub == MSub.THICKNESS) MSub.NONE else MSub.THICKNESS }
                MBtn(Icons.Default.Workspaces, R.string.an_group, enabled && m.count >= 2, false) { m.group() }
                MBtn(Icons.Default.GroupWork, R.string.an_ungroup, enabled, false) { m.ungroup() }
                if (allInk) MBtn(Icons.Default.Gesture, R.string.an_recognize, enabled, false) { h.startHandwriting() }
                MBtn(Icons.Default.Delete, R.string.ae_delete, enabled, false, MaterialTheme.colorScheme.error) { m.delete() }
                SelectionMoreButton(state, multi = true)      // section 7: align / distribute, lock, clipboard, flatten...
                if (m.busy) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(18.dp), strokeWidth = 2.dp)
            }
            when (sub) {
                MSub.COLOR -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PALETTE.forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                            .border(1.dp, Color(0x33000000), CircleShape).clickable(enabled = enabled) { m.setColor(c) })
                    }
                }
                MSub.OPACITY -> MSlider(0.1f..1f, 1f, enabled, { "${(it * 100).roundToInt()}%" }) { m.setOpacity(it) }
                MSub.THICKNESS -> MSlider(0.5f..20f, 2f, enabled, { String.format(java.util.Locale.US, "%.1f", it) }) { m.setThickness(it) }
                MSub.NONE -> {}
            }
        }
    }
}

@Composable
private fun MBtn(icon: ImageVector, label: Int, enabled: Boolean, active: Boolean, tint: Color = LocalContentColor.current, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        colors = if (active) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else IconButtonDefaults.iconButtonColors()) {
        Icon(icon, stringResource(label), tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

@Composable
private fun MSlider(range: ClosedFloatingPointRange<Float>, initial: Float, enabled: Boolean, fmt: (Float) -> String, onCommit: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(initial) }
    Row(Modifier.width(300.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Slider(v, { v = it }, Modifier.weight(1f), enabled = enabled, valueRange = range, onValueChangeFinished = { onCommit(v) })
        Text(fmt(v), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Fills the page box and places the content above [frame] (layout px), or below when there is no room. */
internal fun Modifier.floatAbove(frame: Rect, gapPx: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    val w = if (c.hasBoundedWidth) c.maxWidth else p.width
    val hh = if (c.hasBoundedHeight) c.maxHeight else p.height
    layout(w, hh) {
        val x = (frame.center.x - p.width / 2f).roundToInt().coerceIn(0, (w - p.width).coerceAtLeast(0))
        val above = frame.top - gapPx - p.height
        val y = (if (above >= 0f) above else frame.bottom + gapPx).roundToInt().coerceIn(0, (hh - p.height).coerceAtLeast(0))
        p.place(x, y)
    }
}
