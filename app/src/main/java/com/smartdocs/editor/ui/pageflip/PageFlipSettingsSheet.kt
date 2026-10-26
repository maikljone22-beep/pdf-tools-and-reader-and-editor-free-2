package com.smartdocs.editor.ui.pageflip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pageflip.PageFlipPrefs
import com.smartdocs.editor.core.pageflip.PfAxis
import com.smartdocs.editor.core.pageflip.PfDirection
import com.smartdocs.editor.core.pageflip.PfEffect
import com.smartdocs.editor.core.pageflip.PfLayout
import com.smartdocs.editor.core.pageflip.PfSpread
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Hook for ReaderScreen (dialogs area): restores the remembered reading mode once the document is open and shows the
 * settings sheet when [PageFlipController.sheetOpen].
 */
@Composable
fun PageFlipSheetHost(state: ReaderState) {
    val pf = remember(state) { PageFlipController.of(state) }
    LaunchedEffect(state.session != null) { if (state.session != null) pf.onOpened() }
    if (pf.sheetOpen) PageFlipSettingsSheet(state) { pf.sheetOpen = false }
}

/** Settings of the page navigation library with a live preview of every effect. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PageFlipSettingsSheet(state: ReaderState, onDismiss: () -> Unit) {
    val pf = remember(state) { PageFlipController.of(state) }
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = pf.prefs
    val uiRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val rtl = pf.rtl(uiRtl)
    val systemOff = remember { PageFlipController.animatorScale(ctx) == 0f }
    val samples = rememberSamplePages(state)
    val animate = !systemOff && !p.reducedMotion
    LaunchedEffect(state.session) { pf.detectDirection() }
    fun set(n: PageFlipPrefs) {
        if (n.layout != PfLayout.CLASSIC && p.layout == PfLayout.CLASSIC) scope.launch { app.settings.update { it.copy(continuousScroll = false) } }
        pf.update(n)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.pf_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            // ---- large interactive preview of the selected effect ----
            val shown = p.effectiveEffect(systemOff)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    key(shown, p.speed, animate) {
                        EffectPreview(shown, p, rtl, samples, Modifier.fillMaxWidth().height(190.dp), animate = animate, interactive = true)
                    }
                    Text(stringResource(shown.labelRes()) + " - " + stringResource(shown.descRes()), Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.pf_preview_hint), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (systemOff) Text(stringResource(R.string.pf_system_off), Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            // ---- layout ----
            Section(R.string.pf_layout)
            Chips(listOf(PfLayout.CLASSIC to R.string.pf_layout_classic, PfLayout.PAGED to R.string.pf_layout_paged, PfLayout.SCROLL_H to R.string.pf_layout_scroll_h), p.layout) { set(p.copy(layout = it)) }
            Text(stringResource(R.string.pf_layout_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val toPaged = if (p.layout == PfLayout.CLASSIC) PfLayout.PAGED else p.layout
            Section(R.string.pf_spread)
            Chips(listOf(PfSpread.SINGLE to R.string.pf_spread_single, PfSpread.TWO to R.string.pf_spread_two, PfSpread.TWO_COVER to R.string.pf_spread_cover), p.spread) { set(p.copy(spread = it, layout = PfLayout.PAGED)) }
            Section(R.string.pf_axis)
            Chips(listOf(PfAxis.HORIZONTAL to R.string.pf_axis_h, PfAxis.VERTICAL to R.string.pf_axis_v), p.axis) { set(p.copy(axis = it, layout = PfLayout.PAGED)) }
            Section(R.string.pf_direction)
            Chips(listOf(PfDirection.AUTO to R.string.pf_dir_auto, PfDirection.RTL to R.string.pf_dir_rtl, PfDirection.LTR to R.string.pf_dir_ltr), p.direction) { set(p.copy(direction = it)) }
            pf.docRtl?.let { Text(stringResource(if (it) R.string.pf_dir_detected_rtl else R.string.pf_dir_detected_ltr), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            // ---- effects with live previews ----
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = when { maxWidth > 900.dp -> 6; maxWidth > 600.dp -> 4; else -> 3 }
                val cardW = (maxWidth - 8.dp * (cols - 1)) / cols
                Column {
                    Section(R.string.pf_effects_book)
                    EffectRow(PfEffect.entries.filter { it.book }, cols, cardW, p, rtl, samples, animate, enabled = true) { set(p.copy(effect = it, layout = toPaged)) }
                    Section(R.string.pf_effects_modern)
                    EffectRow(PfEffect.entries.filter { !it.book }, cols, cardW, p, rtl, samples, animate, enabled = true) { set(p.copy(effect = it, layout = toPaged)) }
                }
            }

            // ---- appearance ----
            Section(R.string.pf_appearance)
            Slide(R.string.pf_speed, p.speed, PageFlipPrefs.SPEED_MIN..PageFlipPrefs.SPEED_MAX, stringResource(R.string.pf_speed_value, "%.1f".format(p.speed))) { set(p.copy(speed = it)) }
            Slide(R.string.pf_intensity, p.intensity, 0f..1f, stringResource(R.string.pf_percent, (p.intensity * 100).roundToInt())) { set(p.copy(intensity = it)) }
            Slide(R.string.pf_curvature, p.curvature, 0f..1f, stringResource(R.string.pf_percent, (p.curvature * 100).roundToInt())) { set(p.copy(curvature = it)) }
            Slide(R.string.pf_margins, p.marginDp, 0f..48f, stringResource(R.string.pf_dp_value, p.marginDp.roundToInt())) { set(p.copy(marginDp = it)) }
            Toggle(R.string.pf_shadows, p.shadows) { set(p.copy(shadows = it)) }
            Toggle(R.string.pf_thickness, p.thickness) { set(p.copy(thickness = it)) }
            // ---- motion ----
            Section(R.string.pf_motion)
            Toggle(R.string.pf_spring, p.spring) { set(p.copy(spring = it)) }
            Toggle(R.string.pf_bouncy, p.bouncy, enabled = p.spring) { set(p.copy(bouncy = it)) }
            Toggle(R.string.pf_reduced, p.reducedMotion, supporting = R.string.pf_reduced_d) { set(p.copy(reducedMotion = it)) }
            Toggle(R.string.pf_haptics, p.haptics) { set(p.copy(haptics = it)) }
            // ---- gestures ----
            Section(R.string.pf_gestures)
            Toggle(R.string.pf_zones, p.edgeZones, supporting = R.string.pf_zones_d) { set(p.copy(edgeZones = it)) }
            Slide(R.string.pf_zone_width, p.edgeZone, 0.08f..0.3f, stringResource(R.string.pf_percent, (p.edgeZone * 100).roundToInt()), enabled = p.edgeZones) { set(p.copy(edgeZone = it)) }
            Toggle(R.string.pf_peek, p.peek, enabled = p.edgeZones) { set(p.copy(peek = it)) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Toggle(R.string.pf_per_doc, pf.perDocument) { pf.updatePerDocument(it) }
            Text(stringResource(R.string.pf_limit_note), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(res: Int) {
    Text(stringResource(res), Modifier.padding(top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(items: List<Pair<T, Int>>, selected: T, enabled: Boolean = true, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (v, label) -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(stringResource(label)) }, enabled = enabled) }
    }
}

@Composable
private fun EffectRow(
    effects: List<PfEffect>, cols: Int, cardW: androidx.compose.ui.unit.Dp, p: PageFlipPrefs, rtl: Boolean,
    samples: List<android.graphics.Bitmap?>, animate: Boolean, enabled: Boolean, onPick: (PfEffect) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        effects.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { e ->
                    val sel = e == p.effect
                    OutlinedCard(
                        modifier = Modifier.width(cardW).clickable(enabled = enabled) { onPick(e) },
                        border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        EffectPreview(e, p, rtl, samples, Modifier.fillMaxWidth().height(92.dp), animate = animate)
                        Text(stringResource(e.labelRes()), Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun Slide(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, enabled: Boolean = true, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(v, { v = it }, valueRange = range, enabled = enabled, onValueChangeFinished = { onChange(v) })
}

@Composable
private fun Toggle(label: Int, checked: Boolean, enabled: Boolean = true, supporting: Int? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = supporting?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked, onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}
