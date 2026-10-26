package com.smartdocs.editor.ui.view5

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.view5.Box as PBox
import com.smartdocs.editor.core.view5.DashStyle
import com.smartdocs.editor.core.view5.Geom
import com.smartdocs.editor.core.view5.LineEnd
import com.smartdocs.editor.core.view5.MUnit
import com.smartdocs.editor.core.view5.MeasureScale
import com.smartdocs.editor.core.view5.P
import com.smartdocs.editor.core.view5.TextBoxSpec
import com.smartdocs.editor.ui.reader.PALETTE

/** Scale: type "1 cm = 2 m" / "1:100", choose units, or calibrate from a segment measured on the page. */
@Composable
fun ScaleDialog(v: V5State, calibrated: List<P>?, onCalibrate: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(v.scale.label()) }
    var known by remember { mutableStateOf("") }
    var knownUnit by remember { mutableStateOf(MUnit.M) }
    var precision by remember { mutableIntStateOf(v.precision) }
    val parsed = MeasureScale.parse(text)
    val measuredPt = calibrated?.let { Geom.pathLength(it) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.v5_scale_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.v5_scale_ratio)) }, singleLine = true,
                    isError = parsed == null, supportingText = { Text(stringResource(R.string.v5_scale_hint)) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1 cm = 1 cm", "1:50", "1:100", "1:200", "1:500", "1 in = 1 ft", "1 in = 10 ft").forEach { p ->
                        AssistChip(onClick = { text = p }, label = { Text(p) })
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.v5_calibrate), style = MaterialTheme.typography.titleSmall)
                if (measuredPt == null) {
                    Text(stringResource(R.string.v5_calibrate_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCalibrate) { Text(stringResource(R.string.v5_calibrate_draw)) }
                } else {
                    Text(stringResource(R.string.v5_calibrate_measured, "%.1f".format(java.util.Locale.US, measuredPt)), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(known, { known = it }, label = { Text(stringResource(R.string.v5_known_length)) }, singleLine = true, modifier = Modifier.weight(1f))
                        UnitMenu(knownUnit) { knownUnit = it }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.v5_precision, precision), style = MaterialTheme.typography.labelLarge)
                Slider(precision.toFloat(), { precision = it.toInt() }, valueRange = 0f..4f, steps = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val k = com.smartdocs.editor.core.view5.Digits.toLatin(known).replace(',', '.').replace('٫', '.').toDoubleOrNull()
                val cal = if (measuredPt != null && k != null) MeasureScale.calibrate(measuredPt, k, knownUnit) else null
                (cal ?: parsed)?.let { v.scale = it }
                v.precision = precision; v.saveMeasure(); onDismiss()
            }, enabled = parsed != null || (measuredPt != null && known.isNotBlank())) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun UnitMenu(unit: MUnit, onPick: (MUnit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(unit.code) }
        DropdownMenu(open, { open = false }) {
            MUnit.entries.forEach { u -> DropdownMenuItem(text = { Text(u.code) }, onClick = { onPick(u); open = false }) }
        }
    }
}

/** Text of a callout / text box / typewriter with its style. */
@Composable
fun TextBoxDialog(req: TextRequest, color: Int, dash: DashStyle, author: String, onDone: (TextBoxSpec) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableFloatStateOf(12f) }
    var textColor by remember { mutableIntStateOf(if (req.tool == WTool.TYPEWRITER) 0x212121 else 0x212121) }
    var border by remember { mutableStateOf<Int?>(if (req.tool == WTool.TYPEWRITER) null else color) }
    var fill by remember { mutableStateOf<Int?>(if (req.tool == WTool.TYPEWRITER) null else 0xFFFFFF) }
    var lineDash by remember { mutableStateOf(dash) }
    var align by remember { mutableIntStateOf(0) }
    var rtl by remember { mutableStateOf(true) }
    var end by remember { mutableStateOf(LineEnd.OPEN_ARROW) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(when (req.tool) { WTool.CALLOUT -> R.string.v5_tool_callout; WTool.TYPEWRITER -> R.string.v5_tool_typewriter; else -> R.string.v5_tool_textbox })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.v5_text)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.v5_font_size, size.toInt()), style = MaterialTheme.typography.labelMedium)
                Slider(size, { size = it }, valueRange = 6f..36f)
                Text(stringResource(R.string.v5_text_color), style = MaterialTheme.typography.labelMedium)
                Palette(textColor, allowNone = false) { textColor = it ?: 0x212121 }
                if (req.tool != WTool.TYPEWRITER) {
                    Text(stringResource(R.string.v5_border_color), style = MaterialTheme.typography.labelMedium)
                    Palette(border, allowNone = true) { border = it }
                    Text(stringResource(R.string.v5_fill_color), style = MaterialTheme.typography.labelMedium)
                    Palette(fill, allowNone = true, extra = listOf(0xFFFFFF, 0xFFF9C4)) { fill = it }
                    DashPicker(lineDash) { lineDash = it }
                }
                if (req.tool == WTool.CALLOUT) {
                    Text(stringResource(R.string.v5_line_end), style = MaterialTheme.typography.labelMedium)
                    LineEndPicker(end) { end = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(R.string.v5_align_start, R.string.v5_align_center, R.string.v5_align_end).forEachIndexed { i, r ->
                        FilterChip(align == i, { align = i }, label = { Text(stringResource(r)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(rtl, { rtl = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.v5_rtl))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                val b = if (req.tool == WTool.TYPEWRITER) {
                    // grow the box to the text: width of the longest line, one line height per line
                    val lines = text.split('\n')
                    val w = (lines.maxOf { it.length } * size * 0.55f + 8f).coerceIn(30f, 500f)
                    val h = lines.size * size * 1.2f + 6f
                    val top = req.box.y1
                    if (rtl) PBox(req.box.x0 - w, top - h, req.box.x0, top) else PBox(req.box.x0, top - h, req.box.x0 + w, top)
                } else req.box
                onDone(TextBoxSpec(req.page, b, text.trim(), size, textColor, border, 1f, fill, lineDash, rtl, align, req.tip, null, end,
                    typewriter = req.tool == WTool.TYPEWRITER, author = author))
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun Palette(selected: Int?, allowNone: Boolean, extra: List<Int> = emptyList(), scroll: Boolean = true, onPick: (Int?) -> Unit) {
    // scroll = false when the palette sits inside a row that already scrolls horizontally (nested scroll crashes)
    Row(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (allowNone) Box(Modifier.size(28.dp).clip(CircleShape).border(if (selected == null) 3.dp else 1.dp, if (selected == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable { onPick(null) }, contentAlignment = Alignment.Center) { Text("∅", style = MaterialTheme.typography.labelMedium) }
        (extra + PALETTE).distinct().forEach { c ->
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or c))
                .border(if (selected == c) 3.dp else 1.dp, if (selected == c) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape).clickable { onPick(c) })
        }
    }
}

/** Dash style swatches. */
@Composable
fun DashPicker(selected: DashStyle, onPick: (DashStyle) -> Unit) {
    Text(stringResource(R.string.v5_dash), style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DashStyle.entries.forEach { d ->
            val sel = d == selected
            Box(Modifier.width(56.dp).height(32.dp).clip(RoundedCornerShape(8.dp))
                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onPick(d) }, contentAlignment = Alignment.Center) {
                val c = MaterialTheme.colorScheme.onSurface
                androidx.compose.foundation.Canvas(Modifier.width(40.dp).height(4.dp)) {
                    drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), 4f,
                        pathEffect = d.scaled(4f)?.let { PathEffect.dashPathEffect(it, 0f) })
                }
            }
        }
    }
}

/** Line-ending shapes (drawn as glyph previews). */
@Composable
fun LineEndPicker(selected: LineEnd, onPick: (LineEnd) -> Unit) {
    val glyph = mapOf(LineEnd.NONE to "—", LineEnd.OPEN_ARROW to "→", LineEnd.CLOSED_ARROW to "➤", LineEnd.R_OPEN_ARROW to "↤",
        LineEnd.R_CLOSED_ARROW to "◂", LineEnd.BUTT to "⊣", LineEnd.SQUARE to "■", LineEnd.CIRCLE to "●", LineEnd.DIAMOND to "◆", LineEnd.SLASH to "∕")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LineEnd.entries.forEach { e -> FilterChip(e == selected, { onPick(e) }, label = { Text(glyph[e] ?: e.pdf) }) }
    }
}

/** Line style sheet for the Line tool: dash, both endings and width. */
@Composable
fun LineStyleDialog(m: WorkbenchModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.v5_line_style)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DashPicker(m.dash) { m.dash = it }
                Text(stringResource(R.string.v5_line_start), style = MaterialTheme.typography.labelMedium)
                LineEndPicker(m.startEnd) { m.startEnd = it }
                Text(stringResource(R.string.v5_line_end), style = MaterialTheme.typography.labelMedium)
                LineEndPicker(m.endEnd) { m.endEnd = it }
                Text(stringResource(R.string.v5_width, "%.1f".format(java.util.Locale.US, m.width)), style = MaterialTheme.typography.labelMedium)
                Slider(m.width, { m.width = it }, valueRange = 0.5f..12f)
            }
        },
        confirmButton = { TextButton(onClick = { m.persist(); onDismiss() }) { Text(stringResource(R.string.ok)) } })
}
