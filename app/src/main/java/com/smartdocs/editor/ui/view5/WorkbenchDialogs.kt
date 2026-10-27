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
import kotlinx.coroutines.launch

/** Scale: type "1 cm = 2 m" / "1:100", choose units, or calibrate from a segment measured on the page. */
@Composable
fun ScaleDialog(v: V5State, calibrated: List<P>?, onCalibrate: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(v.scale.label()) }
    var desc by remember { mutableStateOf(v.scaleDescription) }
    var store by remember { mutableIntStateOf(0) }        // 0 app only, 1 this page, 2 all pages (7.47 / 25.12)
    val page = v.s.currentPage
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
                HorizontalDivider()
                OutlinedTextField(desc, { desc = it }, label = { Text(stringResource(R.string.cx_scale_desc)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.cx_scale_store), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(store == 0, { store = 0 }, label = { Text(stringResource(R.string.cx_scale_app)) })
                    FilterChip(store == 1, { store = 1 }, label = { Text(stringResource(R.string.v5_current_page, page + 1)) })
                    FilterChip(store == 2, { store = 2 }, label = { Text(stringResource(R.string.v5_all_pages)) })
                }
                if (v.docScales.isNotEmpty()) {
                    Text(stringResource(R.string.cx_doc_scales), style = MaterialTheme.typography.labelMedium)
                    v.docScales.entries.sortedBy { it.key }.take(12).forEach { (p, ps) ->
                        Text("${p + 1}: ${ps.label}" + if (ps.description.isNotBlank()) " \u00B7 ${ps.description}" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    if (com.smartdocs.editor.core.view5.ScaleStore.hasMultiple(v.docScales))
                        Text(stringResource(R.string.cx_multi_scale_warn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val k = com.smartdocs.editor.core.view5.Digits.toLatin(known).replace(',', '.').replace('٫', '.').toDoubleOrNull()
                val cal = if (measuredPt != null && k != null) MeasureScale.calibrate(measuredPt, k, knownUnit) else null
                (cal ?: parsed)?.let { v.scale = it }
                v.precision = precision; v.scaleDescription = desc.trim(); v.saveMeasure()
                if (store != 0) {
                    val sc = v.scale; val pr = precision; val d = desc.trim(); val pages = if (store == 1) listOf(page) else null
                    v.s.scope.launch {
                        runCatching { v.s.applyInPlace(label = { v.s.context.getString(R.string.cx_scale_saved) }) { src, out, pw -> com.smartdocs.editor.core.view5.ScaleStore.write(src, out, pw, pages, sc, pr, d) } }
                            .onFailure { v.s.error = it.message }
                    }
                }
                onDismiss()
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

/** Leader lines of the distance tool (7.30 / 25.4): length (negative = flipped side), extension, offset. */
@Composable
fun LeaderDialog(m: WorkbenchModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.cx_leader_lines)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.cx_leader_len, "%.0f".format(java.util.Locale.US, m.leader)), style = MaterialTheme.typography.labelMedium)
                Slider(m.leader, { m.leader = it }, valueRange = -60f..60f)
                Text(stringResource(R.string.cx_leader_ext, "%.0f".format(java.util.Locale.US, m.leaderExt)), style = MaterialTheme.typography.labelMedium)
                Slider(m.leaderExt, { m.leaderExt = it }, valueRange = 0f..30f)
                Text(stringResource(R.string.cx_leader_off, "%.0f".format(java.util.Locale.US, m.leaderOffset)), style = MaterialTheme.typography.labelMedium)
                Slider(m.leaderOffset, { m.leaderOffset = it }, valueRange = 0f..30f)
                OutlinedTextField(m.nextLabel, { m.nextLabel = it }, label = { Text(stringResource(R.string.cx_measure_label)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { m.persist(); onDismiss() }) { Text(stringResource(R.string.ok)) } })
}

/** Edit the last measurement: custom label, flip, leader lines and exact end points (25.14 / 25.19 / 25.20). */
@Composable
fun EditMeasureDialog(meas: com.smartdocs.editor.core.view5.Measurement, onDismiss: () -> Unit, onOk: (com.smartdocs.editor.core.view5.Measurement) -> Unit) {
    var label by remember { mutableStateOf(meas.customLabel ?: "") }
    var ll by remember { mutableFloatStateOf(meas.leader) }
    var lle by remember { mutableFloatStateOf(meas.leaderExt) }
    val coords = remember { mutableStateListOf<String>().apply { meas.pts.forEach { p -> add("%.1f".format(java.util.Locale.US, p.x)); add("%.1f".format(java.util.Locale.US, p.y)) } } }
    val dist = meas.type == com.smartdocs.editor.core.view5.MeasureType.DISTANCE
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.cx_edit_last_measure)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.cx_measure_label)) }, singleLine = true, supportingText = { Text(meas.label) })
                if (dist) {
                    Text(stringResource(R.string.cx_leader_len, "%.0f".format(java.util.Locale.US, ll)), style = MaterialTheme.typography.labelMedium)
                    Slider(ll, { ll = it }, valueRange = -60f..60f)
                    Text(stringResource(R.string.cx_leader_ext, "%.0f".format(java.util.Locale.US, lle)), style = MaterialTheme.typography.labelMedium)
                    Slider(lle, { lle = it }, valueRange = 0f..30f)
                    OutlinedButton(onClick = {
                        // flip: the dimension line goes to the other side and the end points swap
                        ll = -ll
                        if (coords.size >= 4) { val a = coords[0]; val b = coords[1]; coords[0] = coords[2]; coords[1] = coords[3]; coords[2] = a; coords[3] = b }
                    }) { Text(stringResource(R.string.cx_flip)) }
                }
                Text(stringResource(R.string.cx_end_points), style = MaterialTheme.typography.labelMedium)
                for (i in 0 until coords.size / 2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(coords[i * 2], { coords[i * 2] = it }, Modifier.weight(1f), label = { Text("X${i + 1}") }, singleLine = true)
                    OutlinedTextField(coords[i * 2 + 1], { coords[i * 2 + 1] = it }, Modifier.weight(1f), label = { Text("Y${i + 1}") }, singleLine = true)
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            val pts = (0 until coords.size / 2).map { i -> P(coords[i * 2].toDoubleOrNull() ?: meas.pts[i].x, coords[i * 2 + 1].toDoubleOrNull() ?: meas.pts[i].y) }
            onOk(meas.copy(pts = pts, customLabel = label.takeIf { it.isNotBlank() }, leader = ll, leaderExt = lle))
        }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
