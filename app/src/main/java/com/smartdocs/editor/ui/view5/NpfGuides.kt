package com.smartdocs.editor.ui.view5

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.NpfPrefs
import kotlin.math.roundToInt

/**
 * nav-prefs (Nitro "Guides", Foxit layout grid): guides over the page area. Each guide is a dashed line with a round
 * handle in the ruler band (left edge for horizontal guides, top edge for vertical ones): drag the handle to move the
 * guide, double-tap it to remove the guide. Positions are fractions of the page area, kept in [NpfPrefs].
 */
@Composable
fun NpfGuidesOverlay() {
    if (!NpfPrefs.guides) return
    var h by remember { mutableStateOf(NpfPrefs.guidesH) }
    var v by remember { mutableStateOf(NpfPrefs.guidesV) }
    // pick up guides added from the overlay settings sheet
    val ver = NpfPrefs.version.intValue
    androidx.compose.runtime.LaunchedEffect(ver) { h = NpfPrefs.guidesH; v = NpfPrefs.guidesV }
    val density = LocalDensity.current
    val handle = 22.dp
    val handlePx = with(density) { handle.toPx() }
    val color = Color(0xFFE91E63)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }; val ht = with(density) { maxHeight.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            h.forEach { f -> drawLine(color, Offset(0f, f * ht), Offset(w, f * ht), 2f, pathEffect = dash) }
            v.forEach { f -> drawLine(color, Offset(f * w, 0f), Offset(f * w, ht), 2f, pathEffect = dash) }
        }
        h.forEachIndexed { i, f ->
            Handle(Modifier.offset { IntOffset(0, (f * ht - handlePx / 2).roundToInt()) }, handle, color,
                onDrag = { d -> h = h.toMutableList().also { it[i] = (it[i] + d.y / ht).coerceIn(0f, 1f) } },
                onEnd = { NpfPrefs.setGuides(h, v) },
                onRemove = { h = h.filterIndexed { k, _ -> k != i }; NpfPrefs.setGuides(h, v) })
        }
        v.forEachIndexed { i, f ->
            Handle(Modifier.offset { IntOffset((f * w - handlePx / 2).roundToInt(), 0) }, handle, color,
                onDrag = { d -> v = v.toMutableList().also { it[i] = (it[i] + d.x / w).coerceIn(0f, 1f) } },
                onEnd = { NpfPrefs.setGuides(h, v) },
                onRemove = { v = v.filterIndexed { k, _ -> k != i }; NpfPrefs.setGuides(h, v) })
        }
    }
}

@Composable
private fun Handle(modifier: Modifier, size: androidx.compose.ui.unit.Dp, color: Color, onDrag: (Offset) -> Unit, onEnd: () -> Unit, onRemove: () -> Unit) {
    Box(modifier.size(size).background(color.copy(alpha = 0.85f), CircleShape)
        .pointerInput(Unit) { detectDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { ch, d -> ch.consume(); onDrag(d) } }
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onRemove() }) })
}

/** Grid and guide options inside the overlay settings sheet (hook in OverlaySettingsSheet). */
@Composable
fun NpfGridSettings() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.npf_grid_snap), Modifier.weight(1f))
            Switch(NpfPrefs.gridSnap, { NpfPrefs.setBool(NpfPrefs.K.GRID_SNAP, it) })
        }
        Text(stringResource(R.string.npf_grid_sub, NpfPrefs.gridSubdivisions), style = MaterialTheme.typography.labelMedium)
        Slider(NpfPrefs.gridSubdivisions.toFloat(), { NpfPrefs.setInt(NpfPrefs.K.GRID_SUB, it.roundToInt()) }, valueRange = 1f..10f, steps = 8)
        Text(stringResource(R.string.npf_grid_offset_x, NpfPrefs.gridOffsetXmm.roundToInt()), style = MaterialTheme.typography.labelMedium)
        Slider(NpfPrefs.gridOffsetXmm, { NpfPrefs.setFloat(NpfPrefs.K.GRID_OFF_X, it) }, valueRange = 0f..50f)
        Text(stringResource(R.string.npf_grid_offset_y, NpfPrefs.gridOffsetYmm.roundToInt()), style = MaterialTheme.typography.labelMedium)
        Slider(NpfPrefs.gridOffsetYmm, { NpfPrefs.setFloat(NpfPrefs.K.GRID_OFF_Y, it) }, valueRange = 0f..50f)
        Text(stringResource(R.string.npf_grid_color), style = MaterialTheme.typography.labelMedium)
        NpfColorRow(NpfPrefs.gridColor, GRID_COLORS) { NpfPrefs.setInt(NpfPrefs.K.GRID_COLOR, it) }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.npf_guides), Modifier.weight(1f))
            Switch(NpfPrefs.guides, { NpfPrefs.setBool(NpfPrefs.K.GUIDES, it) })
        }
        if (NpfPrefs.guides) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { NpfPrefs.setGuides(NpfPrefs.guidesH + 0.5f, NpfPrefs.guidesV) }, label = { Text(stringResource(R.string.npf_guide_add_h)) })
            AssistChip(onClick = { NpfPrefs.setGuides(NpfPrefs.guidesH, NpfPrefs.guidesV + 0.5f) }, label = { Text(stringResource(R.string.npf_guide_add_v)) })
            AssistChip(onClick = { NpfPrefs.setGuides(emptyList(), emptyList()) }, label = { Text(stringResource(R.string.npf_guide_clear)) })
        }
        if (NpfPrefs.guides) Text(stringResource(R.string.npf_guides_hint), style = MaterialTheme.typography.bodySmall)
    }
}

private val GRID_COLORS = listOf(0x2196F3, 0x4CAF50, 0xE91E63, 0x9E9E9E, 0x000000, 0xFF9800)

/** A row of colour swatches (RGB). */
@Composable
fun NpfColorRow(selected: Int, colors: List<Int>, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            val on = (selected and 0xFFFFFF) == (c and 0xFFFFFF)
            Box(Modifier.size(if (on) 34.dp else 28.dp).background(Color(0xFF000000.toInt() or c), CircleShape)
                .pointerInput(c) { detectTapGestures(onTap = { onPick(c) }) })
        }
    }
}
