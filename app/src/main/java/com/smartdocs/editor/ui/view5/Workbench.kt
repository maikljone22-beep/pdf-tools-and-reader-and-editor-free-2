package com.smartdocs.editor.ui.view5

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.view5.Geom
import com.smartdocs.editor.core.view5.P
import com.smartdocs.editor.core.view5.PageGraphics
import com.smartdocs.editor.ui.reader.ToolChip
import kotlinx.coroutines.launch

private data class WToolDef(val tool: WTool, val icon: ImageVector, val label: Int)

private val measureTools = listOf(
    WToolDef(WTool.DISTANCE, Icons.Default.Straighten, R.string.v5_tool_distance),
    WToolDef(WTool.PERIMETER, Icons.Default.Timeline, R.string.v5_tool_perimeter),
    WToolDef(WTool.AREA, Icons.Default.Pentagon, R.string.v5_tool_area),
    WToolDef(WTool.CIRCLE, Icons.Default.RadioButtonUnchecked, R.string.v5_tool_circle),
    WToolDef(WTool.ANGLE, Icons.Default.Architecture, R.string.v5_tool_angle),
    WToolDef(WTool.COUNT, Icons.Default.Pin, R.string.v5_tool_count),
)
private val commentTools = listOf(
    WToolDef(WTool.CALLOUT, Icons.Default.Comment, R.string.v5_tool_callout),
    WToolDef(WTool.TYPEWRITER, Icons.Default.Keyboard, R.string.v5_tool_typewriter),
    WToolDef(WTool.TEXTBOX, Icons.Default.TextFields, R.string.v5_tool_textbox),
    WToolDef(WTool.TICK, Icons.Default.Check, R.string.v5_tool_tick),
    WToolDef(WTool.CROSS, Icons.Default.Close, R.string.v5_tool_cross),
    WToolDef(WTool.AREA_HIGHLIGHT, Icons.Default.HighlightAlt, R.string.v5_tool_area_highlight),
    WToolDef(WTool.LINE, Icons.Default.ArrowOutward, R.string.v5_tool_line),
)

/**
 * Page workbench: measure tools (distance, perimeter, area, circle, angle, count with scale, calibration,
 * snapping, 45° constraint and loupe) and the multi-tap comment tools (callout, typewriter, text box, tick,
 * cross, area highlight, styled line / arrow). Items are written with [ReaderState.applyInPlace] (undo).
 */
@Composable
fun WorkbenchSheet(v: V5State, measureFirst: Boolean, onMessage: (String) -> Unit) {
    val s = v.s
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val m = remember { WorkbenchModel(v).also { if (measureFirst && !it.isMeasure) it.tool = WTool.DISTANCE; if (!measureFirst && it.isMeasure) it.tool = WTool.CALLOUT } }
    var showScale by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val size = s.pageSizes[m.page] ?: (595f to 842f)
    val view = remember(m.page, size) { WbView(size.first.coerceAtLeast(1f), size.second.coerceAtLeast(1f)) }   // 0 → NaN → roundToInt crash
    // page bitmap (about two screens wide for sharp zoom)
    val bitmap by produceState<Bitmap?>(null, m.page, s.session, s.renderVersion) {
        val sess = s.session ?: return@produceState
        val w = 2000; val h = if (size.first > 0f && size.second > 0f) (w * size.second / size.first).toInt().coerceIn(1, 6000) else 2828   // 0-size page boxes → no Infinity
        value = runCatching { sess.render(m.page, w, h, false, true) }.getOrNull()
    }
    // snapping targets: vector path segments + text glyph corners
    val segments by produceState<List<Pair<P, P>>>(emptyList(), m.page, s.renderVersion) {
        val f = s.file ?: return@produceState
        value = runCatching { PageGraphics.scan(f, s.password, m.page).segments }.getOrDefault(emptyList())
    }
    val glyphCorners by produceState<List<P>>(emptyList(), m.page, s.renderVersion) {
        value = s.charBoxes(m.page).filter { it.width() > 0.5f }.take(4000).flatMap { r ->
            val top = maxOf(r.top, r.bottom).toDouble(); val bot = minOf(r.top, r.bottom).toDouble()
            listOf(P(r.left.toDouble(), bot), P(r.right.toDouble(), bot), P(r.left.toDouble(), top), P(r.right.toDouble(), top))
        }
    }
    val snapTo: (P, Float) -> P? = { p, tol ->
        val own = m.pts.toList() + m.items.filter { it.page == m.page }.flatMap { (it as? WItem.Meas)?.m?.pts ?: emptyList() }
        Geom.snapToSegments(p, segments, tol.toDouble(), own + glyphCorners)
    }
    fun close() { m.persist(); v.close() }

    Dialog(onDismissRequest = { if (m.items.isEmpty()) close() else confirmClose = true },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // ---- header ----
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (m.items.isEmpty()) close() else confirmClose = true }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Text(stringResource(if (measureFirst) R.string.v5_measure else R.string.v5_comment_tools), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (m.page > 0) { m.page--; m.pts.clear() } }, enabled = m.page > 0) { Icon(Icons.Default.ChevronLeft, stringResource(R.string.previous)) }
                    Text("${m.page + 1}/${s.pageCount}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { if (m.page < s.pageCount - 1) { m.page++; m.pts.clear() } }, enabled = m.page < s.pageCount - 1) { Icon(Icons.Default.ChevronRight, stringResource(R.string.next)) }
                    IconButton(onClick = { m.undoPoint() }, enabled = m.pts.isNotEmpty() || m.items.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    Button(enabled = m.items.isNotEmpty() && !saving, onClick = {
                        saving = true
                        scope.launch {
                            val r = runCatching { s.applyInPlace { src, out, pw -> m.writeAll(ctx, src, out, pw) } }
                            saving = false
                            r.onSuccess { m.items.clear(); onMessage(ctx.getString(R.string.v5_saved_items)); close() }
                                .onFailure { onMessage(it.message ?: ctx.getString(R.string.error_generic)) }
                        }
                    }) { Text(stringResource(R.string.save) + if (m.items.isNotEmpty()) " (${m.items.size})" else "") }
                }
                // ---- tools ----
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    (if (measureFirst) measureTools + commentTools else commentTools + measureTools).forEachIndexed { i, d ->
                        if (i == (if (measureFirst) measureTools.size else commentTools.size)) VerticalDivider(Modifier.height(40.dp).padding(horizontal = 4.dp))
                        ToolChip(d.icon, stringResource(d.label), selected = m.tool == d.tool && !m.calibrating) { m.selectTool(d.tool) }
                    }
                }
                // ---- settings of the tool ----
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m.isMeasure || m.calibrating) {
                        AssistChip(onClick = { showScale = true }, label = { Text(v.scale.label()) }, leadingIcon = { Icon(Icons.Default.SquareFoot, null, Modifier.size(16.dp)) })
                        FilterChip(v.snap, { v.snap = !v.snap; v.saveMeasure() }, label = { Text(stringResource(R.string.v5_snap)) }, leadingIcon = { Icon(Icons.Default.GpsFixed, null, Modifier.size(16.dp)) })
                        FilterChip(v.ortho, { v.ortho = !v.ortho }, label = { Text("45°") })
                        FilterChip(m.arabicDigits, { m.arabicDigits = !m.arabicDigits }, label = { Text("١٢٣") })
                        if (m.tool == WTool.AREA || m.tool == WTool.CIRCLE) FilterChip(m.fillArea, { m.fillArea = !m.fillArea }, label = { Text(stringResource(R.string.v5_fill)) })
                    }
                    if (m.tool == WTool.LINE || m.isMeasure) AssistChip(onClick = { showStyle = true }, label = { Text(stringResource(R.string.v5_line_style)) }, leadingIcon = { Icon(Icons.Default.LineStyle, null, Modifier.size(16.dp)) })
                    if (m.tool == WTool.TICK || m.tool == WTool.CROSS) listOf(10f, 16f, 24f).forEach { sz ->
                        FilterChip(m.markSize == sz, { m.markSize = sz }, label = { Text(stringResource(when (sz) { 10f -> R.string.v5_small; 16f -> R.string.v5_medium; else -> R.string.v5_large })) })
                    }
                    Palette(m.color, allowNone = false, extra = listOf(0xE53935, 0x1E88E5, 0x43A047), scroll = false) { m.color = it ?: 0xE53935 }
                }
                // ---- hint / live status ----
                val hint = when {
                    m.calibrating -> stringResource(R.string.v5_hint_calibrate)
                    m.tool == WTool.PERIMETER || m.tool == WTool.AREA || m.tool == WTool.COUNT -> stringResource(R.string.v5_hint_multi)
                    m.tool == WTool.CALLOUT -> stringResource(R.string.v5_hint_callout)
                    m.tool == WTool.ANGLE -> stringResource(R.string.v5_hint_angle)
                    m.tool == WTool.CIRCLE -> stringResource(R.string.v5_hint_circle)
                    else -> stringResource(R.string.v5_hint_tap)
                }
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(hint, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (m.needed() == 0 && m.pts.isNotEmpty()) FilledTonalButton(onClick = { m.finish() }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(32.dp)) {
                        Text(stringResource(R.string.done))
                    }
                    if (m.pts.size >= 2 && m.isMeasure) {
                        val a = m.pts[m.pts.size - 2]; val b = m.pts.last()
                        val dx = v.scale.length(kotlin.math.abs(b.x - a.x)); val dy = v.scale.length(kotlin.math.abs(b.y - a.y))
                        Text("  ΔX ${"%.${v.precision}f".format(java.util.Locale.US, dx)}  ΔY ${"%.${v.precision}f".format(java.util.Locale.US, dy)}  ∠ ${"%.1f".format(java.util.Locale.US, Geom.bearing(a, b))}°",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF9E9E9E))) {
                    WorkbenchCanvas(m, bitmap, view, snapTo)
                    if (bitmap == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }

    if (showScale) ScaleDialog(v, m.calibrationPts, onCalibrate = { showScale = false; m.pts.clear(); m.calibrating = true }) { showScale = false; m.calibrationPts = null }
    LaunchedEffect(m.calibrationPts) { if (m.calibrationPts != null) showScale = true }
    if (showStyle) LineStyleDialog(m) { showStyle = false }
    m.textRequest?.let { req ->
        TextBoxDialog(req, m.color, m.dash, s.authorName, onDone = { spec -> m.items += WItem.Text(spec); m.textRequest = null }, onDismiss = { m.textRequest = null })
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false },
        title = { Text(stringResource(R.string.v5_discard_items_title)) }, text = { Text(stringResource(R.string.v5_discard_items, m.items.size)) },
        confirmButton = { TextButton(onClick = { confirmClose = false; m.items.clear(); close() }) { Text(stringResource(R.string.v5_discard)) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.cancel)) } })
}
