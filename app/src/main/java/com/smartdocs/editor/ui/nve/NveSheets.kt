package com.smartdocs.editor.ui.nve

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.PageTrans
import com.smartdocs.editor.core.nve.PageTransitions
import com.smartdocs.editor.core.nve.TextStats
import com.smartdocs.editor.core.nve.TransStyle
import com.smartdocs.editor.core.nve.WordStats
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ViewMode
import com.smartdocs.editor.ui.view5.V5State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SwitchLine(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(on, onChange)
    }
}

/** Zoom: presets, numeric percentage, marquee zoom, page layout (continuous facing, cover, reverse), scroll lock. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NveZoomSheet(s: ReaderState) {
    val n = NveState.of(s)
    val pct = NveZoom.percent(s)
    var field by remember { mutableStateOf(pct.toInt().toString()) }
    ModalBottomSheet(onDismissRequest = { n.close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nv_zoom), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.nv_zoom_now, pct.toInt()), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ZoomPreset.FIT_WIDTH to R.string.fit_width, ZoomPreset.FIT_PAGE to R.string.fit_page, ZoomPreset.FIT_HEIGHT to R.string.nv_fit_height,
                    ZoomPreset.ACTUAL to R.string.nv_actual_size, ZoomPreset.AUTO to R.string.nv_zoom_auto).forEach { (p, l) ->
                    AssistChip(onClick = { NveZoom.preset(s, p); n.close() }, label = { Text(stringResource(l)) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val range = NveZoom.percentRange(s)
                NveZoom.PERCENTS.filter { it.toFloat() in range }.forEach { p -> FilterChip(kotlin.math.abs(pct - p) < 1f, { NveZoom.setPercent(s, p.toFloat()); field = p.toString() }, label = { Text("$p%") }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(field, { field = it.filter { c -> c.isDigit() }.take(5) }, Modifier.width(140.dp), singleLine = true,
                    label = { Text(stringResource(R.string.nv_zoom_percent)) }, suffix = { Text("%") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button(onClick = { field.toFloatOrNull()?.let { NveZoom.setPercent(s, it) } }) { Text(stringResource(R.string.nv_apply)) }
            }
            Text(stringResource(R.string.nv_zoom_range, NveZoom.percentRange(s).start.toInt(), NveZoom.percentRange(s).endInclusive.toInt()), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { n.dragMode = NveDrag.ZOOM_IN; n.close() }) { Icon(Icons.Default.ZoomIn, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nv_marquee)) }
                OutlinedButton(onClick = { n.dragMode = NveDrag.ZOOM_OUT; n.close() }) { Icon(Icons.Default.ZoomOut, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nv_marquee_out)) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.nv_page_layout), style = MaterialTheme.typography.labelLarge)
            SwitchLine(stringResource(R.string.nv_continuous_facing), s.viewMode == ViewMode.CONTINUOUS && n.continuousFacing) { on ->
                n.continuousFacing = on; if (on) s.viewMode = ViewMode.CONTINUOUS; n.saveView()
            }
            if (n.continuousFacing) SwitchLine(stringResource(R.string.nv_cover_page), n.continuousCover) { n.continuousCover = it; n.saveView() }
            SwitchLine(stringResource(R.string.nv_reverse_view), n.reverseView) { n.reverseView = it; n.saveView() }
            Text(stringResource(R.string.nv_scroll_lock), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to R.string.nv_lock_none, 1 to R.string.nv_lock_horizontal, 2 to R.string.nv_lock_vertical).forEach { (k, l) ->
                    FilterChip(n.scrollLock == k, { n.scrollLock = k }, label = { Text(stringResource(l)) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Word count of the selection ([NveState.wordCountText]) or of the whole document / page range. */
@Composable
fun NveWordCountDialog(s: ReaderState) {
    val n = NveState.of(s)
    val sel = n.wordCountText
    var stats by remember { mutableStateOf<WordStats?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf(false) }
    LaunchedEffect(sel, current) {
        stats = null
        stats = if (sel != null) TextStats.of(sel, 0) else withContext(Dispatchers.Default) {
            var acc = WordStats()
            val pages = if (current) listOf(s.currentPage) else (0 until s.pageCount).toList()
            pages.forEachIndexed { i, p -> acc += TextStats.of(s.pageText(p), 1); progress = i + 1 }
            acc
        }
    }
    AlertDialog(onDismissRequest = { n.wordCountText = null; n.close() },
        title = { Text(stringResource(R.string.nv_word_count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (sel != null) R.string.nv_wc_selection else if (current) R.string.nv_wc_page else R.string.nv_wc_document), style = MaterialTheme.typography.labelLarge)
                if (sel == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!current, { current = false }, label = { Text(stringResource(R.string.nv_wc_document)) })
                    FilterChip(current, { current = true }, label = { Text(stringResource(R.string.nv_wc_page)) })
                }
                val st = stats
                if (st == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); if (sel == null) Text("$progress / ${s.pageCount}") }
                else {
                    if (sel == null) StatRow(R.string.nv_wc_pages, st.pages)
                    StatRow(R.string.nv_wc_words, st.words)
                    StatRow(R.string.nv_wc_chars, st.chars)
                    StatRow(R.string.nv_wc_chars_ns, st.charsNoSpaces)
                    StatRow(R.string.nv_wc_lines, st.lines)
                    StatRow(R.string.nv_wc_paragraphs, st.paragraphs)
                    StatRow(R.string.nv_wc_arabic, st.arabic)
                    StatRow(R.string.nv_wc_asian, st.asian)
                }
            }
        },
        confirmButton = { TextButton(onClick = { n.wordCountText = null; n.close() }) { Text(stringResource(R.string.ok)) } })
}

@Composable
private fun StatRow(label: Int, v: Int) {
    Row(Modifier.fillMaxWidth()) { Text(stringResource(label), Modifier.weight(1f)); Text(java.text.NumberFormat.getIntegerInstance().format(v)) }
}

/**
 * Grid / rulers / snap / lens window settings. Grid colour, offsets, subdivisions, units and guides are main's
 * nav-prefs values (NpfPrefs, drawn by view5's GridAndRulers + NpfGuidesOverlay): this sheet edits the same values.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NveGridSheet(s: ReaderState) {
    val n = NveState.of(s)
    val v = V5State.of(s)
    val P = com.smartdocs.editor.core.data.NpfPrefs
    @Suppress("UNUSED_VARIABLE") val ver = P.version.intValue
    ModalBottomSheet(onDismissRequest = { n.saveView(); v.saveOverlays(); v.saveMeasure(); n.close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.nv_grid_title), style = MaterialTheme.typography.titleLarge)
            SwitchLine(stringResource(R.string.v5_grid), v.grid) { v.grid = it }
            SwitchLine(stringResource(R.string.v5_rulers), v.rulers) { v.rulers = it }
            Text(stringResource(R.string.nv_units), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("cm", "mm", "in", "pt").forEach { u -> FilterChip(P.units == u, { P.setString(P.K.UNITS, u) }, label = { Text(u) }) }
            }
            Text(stringResource(R.string.v5_grid_spacing, v.gridMm.toInt()), style = MaterialTheme.typography.labelMedium)
            Slider(v.gridMm, { v.gridMm = it }, valueRange = 2f..50f)
            Text(stringResource(R.string.nv_grid_subdiv, P.gridSubdivisions), style = MaterialTheme.typography.labelMedium)
            Slider(P.gridSubdivisions.toFloat(), { P.setInt(P.K.GRID_SUB, it.toInt()) }, valueRange = 1f..10f, steps = 8)
            Text(stringResource(R.string.nv_grid_offset, P.gridOffsetXmm.toInt(), P.gridOffsetYmm.toInt()), style = MaterialTheme.typography.labelMedium)
            Slider(P.gridOffsetXmm, { P.setFloat(P.K.GRID_OFF_X, it) }, valueRange = 0f..50f)
            Slider(P.gridOffsetYmm, { P.setFloat(P.K.GRID_OFF_Y, it) }, valueRange = 0f..50f)
            Text(stringResource(R.string.nv_grid_color), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0x2196F3, 0x9E9E9E, 0xF44336, 0x4CAF50, 0xFF9800, 0x9C27B0, 0x000000).forEach { c ->
                    com.smartdocs.editor.ui.reader.ColorDot(c, P.gridColor == c) { P.setInt(P.K.GRID_COLOR, c) }
                }
            }
            SwitchLine(stringResource(R.string.nv_snap_grid), v.snapGrid) { v.snapGrid = it }
            SwitchLine(stringResource(R.string.nv_guides_show), P.guides) { P.setBool(P.K.GUIDES, it) }
            if (P.guidesH.isNotEmpty() || P.guidesV.isNotEmpty()) TextButton(onClick = { P.setGuides(emptyList(), emptyList()) }) { Text(stringResource(R.string.nv_guides_clear)) }
            HorizontalDivider()
            Text(stringResource(R.string.nv_lens_size, n.lensW, n.lensH), style = MaterialTheme.typography.labelLarge)
            Slider(n.lensW.toFloat(), { n.lensW = it.toInt() }, valueRange = 200f..900f)
            Slider(n.lensH.toFloat(), { n.lensH = it.toInt() }, valueRange = 120f..600f)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Page transitions written into the PDF (/Trans) for a page range: style, direction, speed, auto advance. One undo step. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NveTransitionsSheet(s: ReaderState) {
    val n = NveState.of(s)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(TransStyle.FADE) }
    var duration by remember { mutableFloatStateOf(1f) }
    var horizontal by remember { mutableStateOf(true) }
    var inward by remember { mutableStateOf(true) }
    var direction by remember { mutableIntStateOf(0) }
    var auto by remember { mutableStateOf("") }
    var range by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val f = s.file ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { com.smartdocs.editor.core.pdf.PdfOps.load(f, s.password).use { PageTransitions.read(it, s.currentPage) } }.getOrNull() }?.let { t ->
            style = t.style; duration = t.duration; horizontal = t.horizontal; inward = t.inward; direction = t.direction; auto = t.autoAdvance?.toString() ?: ""
        }
    }
    fun apply(t: PageTrans?) {
        val pages = PageTransitions.parseRange(range, s.pageCount) ?: run { NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_bad_range)); return }
        busy = true
        scope.launch {
            runCatching {
                s.applyInPlace(label = { ctx.getString(R.string.nv_transitions) }) { src, out, pw ->
                    withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.PdfOps.load(src, pw).use { d -> PageTransitions.write(d, pages, t); d.save(out) } }
                }
            }.onFailure { NveSelectionOps.toast(ctx, it.message ?: ctx.getString(R.string.nv_failed)) }
            busy = false; n.close()
        }
    }
    ModalBottomSheet(onDismissRequest = { n.close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.nv_transitions), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.nv_transitions_sub), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TransStyle.entries.forEach { t -> FilterChip(style == t, { style = t }, label = { Text(transLabel(t)) }) } }
            Text(stringResource(R.string.nv_tr_speed, String.format(java.util.Locale.US, "%.1f", duration)), style = MaterialTheme.typography.labelMedium)
            Slider(duration, { duration = it }, valueRange = 0.2f..5f)
            if (style == TransStyle.SPLIT || style == TransStyle.BLINDS) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(horizontal, { horizontal = true }, label = { Text(stringResource(R.string.nv_tr_horizontal)) })
                FilterChip(!horizontal, { horizontal = false }, label = { Text(stringResource(R.string.nv_tr_vertical)) })
            }
            if (style == TransStyle.SPLIT || style == TransStyle.BOX || style == TransStyle.FLY) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(inward, { inward = true }, label = { Text(stringResource(R.string.nv_tr_inward)) })
                FilterChip(!inward, { inward = false }, label = { Text(stringResource(R.string.nv_tr_outward)) })
            }
            if (style in setOf(TransStyle.WIPE, TransStyle.GLITTER, TransStyle.FLY, TransStyle.COVER, TransStyle.UNCOVER, TransStyle.PUSH)) {
                Text(stringResource(R.string.nv_tr_direction), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "→", 180 to "←", 90 to "↑", 270 to "↓").forEach { (d, l) -> FilterChip(direction == d, { direction = d }, label = { Text(l) }) }
                }
            }
            OutlinedTextField(auto, { auto = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.nv_tr_auto)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nv_page_range)) }, placeholder = { Text("1-3, 5") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { apply(PageTrans(style, duration, horizontal, inward, direction, auto.toFloatOrNull())) }) { Text(stringResource(R.string.nv_apply)) }
                OutlinedButton(enabled = !busy, onClick = { apply(null) }) { Text(stringResource(R.string.nv_tr_remove)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun transLabel(t: TransStyle): String = stringResource(when (t) {
    TransStyle.REPLACE -> R.string.nv_tr_replace; TransStyle.SPLIT -> R.string.nv_tr_split; TransStyle.BLINDS -> R.string.nv_tr_blinds
    TransStyle.BOX -> R.string.nv_tr_box; TransStyle.WIPE -> R.string.nv_tr_wipe; TransStyle.DISSOLVE -> R.string.nv_tr_dissolve
    TransStyle.GLITTER -> R.string.nv_tr_glitter; TransStyle.FLY -> R.string.nv_tr_fly; TransStyle.PUSH -> R.string.nv_tr_push
    TransStyle.COVER -> R.string.nv_tr_cover; TransStyle.UNCOVER -> R.string.nv_tr_uncover; TransStyle.FADE -> R.string.nv_tr_fade
})

/** Whole-page snapshot of the current page (copy / paste back / save). */
@Composable
fun NvePageSnapshot(s: ReaderState) {
    val n = NveState.of(s)
    val bmp by produceState<android.graphics.Bitmap?>(null, s.currentPage) { value = pageSnapshot(s, s.currentPage) }
    val b = bmp
    if (b == null) AlertDialog(onDismissRequest = { n.close() }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    else SnapshotResultDialog(s, b) { n.close() }
}
