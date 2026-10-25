package com.smartdocs.editor.ui.reader.view

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ViewMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The reader's "View settings" sheet in the reference order: page mode (+ continuous, + facing separator),
 * colours (day / page colour presets / night), fit page / width, reflow, crop, auto flip, rotate, pan & zoom,
 * plus orientation lock and keep-screen-on timeout (both missing / not wired in the reference).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewSettingsSheet(state: ReaderState, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val settings = LocalSettings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vx = state.vx
    var cropMenu by remember { mutableStateOf(false) }
    var manualCrop by remember { mutableStateOf(false) }
    var timeoutDialog by remember { mutableStateOf(false) }
    fun setMode(m: ViewMode) { state.viewMode = m; state.zoom = 1f; state.reflow = false }
    fun persistColor(c: Long) { scope.launch { app.settings.update { it.copy(pageColor = c, nightMode = state.nightMode) } } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.vw_view_settings), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            // ---- page mode ----
            Label(R.string.vw_page_mode)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeCard(Icons.Default.ViewDay, stringResource(R.string.view_continuous), state.viewMode == ViewMode.CONTINUOUS, Modifier.weight(1f)) { setMode(ViewMode.CONTINUOUS) }
                ModeCard(Icons.Default.CropPortrait, stringResource(R.string.view_single), state.viewMode == ViewMode.SINGLE, Modifier.weight(1f)) { setMode(ViewMode.SINGLE) }
                ModeCard(Icons.Default.MenuBook, stringResource(R.string.view_facing), state.viewMode == ViewMode.FACING, Modifier.weight(1f)) { setMode(ViewMode.FACING) }
                ModeCard(Icons.Default.AutoStories, stringResource(R.string.vw_cover), state.viewMode == ViewMode.COVER, Modifier.weight(1f)) { setMode(ViewMode.COVER) }
            }
            val paired = state.viewMode == ViewMode.FACING || state.viewMode == ViewMode.COVER
            ListItem(
                headlineContent = { Text(stringResource(R.string.vw_separator)) },
                leadingContent = { Icon(Icons.Default.VerticalSplit, null) },
                trailingContent = { Switch(vx.facingSeparator, { vx.updateFacingSeparator(it) }, enabled = paired) },
                modifier = Modifier.clickable(enabled = paired) { vx.updateFacingSeparator(!vx.facingSeparator) },
            )
            // ---- colours ----
            Label(R.string.vw_colors)
            val plain = isPlainPage(state.pageColor)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !state.nightMode && plain, onClick = { state.nightMode = false; state.pageColor = 0xFFFFFFFFL; persistColor(state.pageColor) },
                    label = { Text(stringResource(R.string.vw_day)) }, leadingIcon = { Icon(Icons.Default.LightMode, null, Modifier.size(18.dp)) })
                FilterChip(selected = !state.nightMode && !plain, onClick = { state.nightMode = false; if (plain) { state.pageColor = PAGE_COLOR_PRESETS[2].background; persistColor(state.pageColor) } },
                    label = { Text(stringResource(R.string.page_color)) }, leadingIcon = { Icon(Icons.Default.Palette, null, Modifier.size(18.dp)) })
                FilterChip(selected = state.nightMode, onClick = { state.nightMode = true; persistColor(state.pageColor) },
                    label = { Text(stringResource(R.string.vw_night)) }, leadingIcon = { Icon(Icons.Default.DarkMode, null, Modifier.size(18.dp)) })
            }
            Spacer(Modifier.height(8.dp))
            PresetRow(state) { c -> state.nightMode = false; state.pageColor = c; persistColor(c) }
            Spacer(Modifier.height(8.dp))
            // ---- zoom ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.fitPage, onClick = { state.fitPage = true; state.zoom = 1f }, label = { Text(stringResource(R.string.fit_page)) },
                    leadingIcon = { if (state.fitPage) Icon(Icons.Default.Check, null, Modifier.size(18.dp)) })
                FilterChip(selected = !state.fitPage, onClick = { state.fitPage = false; state.zoom = 1f }, label = { Text(stringResource(R.string.fit_width)) },
                    leadingIcon = { if (!state.fitPage) Icon(Icons.Default.Check, null, Modifier.size(18.dp)) })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- modes ----
            Item(Icons.Default.ViewHeadline, R.string.reflow, checked = state.reflow) {
                state.reflow = !state.reflow
                if (state.reflow) { vx.crop.disable(); vx.magnifier = false }
                onDismiss()
            }
            Item(Icons.Default.Crop, R.string.crop_mode, checked = vx.crop.enabled) { cropMenu = true }
            Item(Icons.Default.Timer, R.string.auto_flip, checked = state.autoFlipSeconds > 0,
                supporting = stringResource(R.string.vw_flip_every, vx.autoFlipInterval)) {
                state.autoFlipSeconds = if (state.autoFlipSeconds > 0) 0 else vx.autoFlipInterval
                onDismiss()
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.rotate_view)) },
                supportingContent = { Text("${state.rotation}°") },
                leadingContent = { Icon(Icons.Default.ScreenRotation, null) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { state.rotation = (state.rotation + 270) % 360 }) { Icon(Icons.Default.RotateLeft, stringResource(R.string.vw_rotate_ccw)) }
                        IconButton(onClick = { state.rotation = (state.rotation + 90) % 360 }) { Icon(Icons.Default.RotateRight, stringResource(R.string.rotate_view)) }
                    }
                },
            )
            Item(Icons.Default.ZoomIn, R.string.vw_magnifier, checked = vx.magnifier, enabled = !vx.crop.enabled && !state.reflow) {
                vx.magnifier = !vx.magnifier; onDismiss()
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- screen ----
            ListItem(
                headlineContent = { Text(stringResource(R.string.vw_orientation_lock)) },
                supportingContent = { Text(stringResource(R.string.vw_orientation_lock_desc)) },
                leadingContent = { Icon(Icons.Default.ScreenLockRotation, null) },
                trailingContent = { Switch(vx.orientationLock, { vx.updateOrientationLock(it, ctx.isLandscape()) }) },
                modifier = Modifier.clickable { vx.updateOrientationLock(!vx.orientationLock, ctx.isLandscape()) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.keep_screen_on)) },
                leadingContent = { Icon(Icons.Default.LightMode, null) },
                trailingContent = { Switch(settings.keepScreenOn, { v -> scope.launch { app.settings.update { it.copy(keepScreenOn = v) } } }) },
            )
            if (settings.keepScreenOn) ListItem(
                headlineContent = { Text(stringResource(R.string.vw_screen_timeout)) },
                supportingContent = { Text(timeoutLabel(vx.screenTimeoutSec)) },
                leadingContent = { Icon(Icons.Default.Timer, null) },
                modifier = Modifier.clickable { timeoutDialog = true },
            )
        }
    }

    if (cropMenu) CropMenuDialog(state, onManual = { cropMenu = false; manualCrop = true }, onDismiss = { cropMenu = false })
    if (manualCrop) ManualCropDialog(state) { manualCrop = false }
    if (timeoutDialog) TimeoutDialog(vx.screenTimeoutSec, onPick = { vx.updateScreenTimeout(it); timeoutDialog = false }) { timeoutDialog = false }
}

@Composable
private fun Label(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Item(icon: ImageVector, label: Int, checked: Boolean, enabled: Boolean = true, supporting: String? = null, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    ListItem(
        headlineContent = { Text(stringResource(label), color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)) },
        supportingContent = if (supporting != null) ({ Text(supporting) }) else null,
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) },
        trailingContent = { if (checked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
fun ModeCard(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(modifier.clickable(onClick = onClick), colors = colors) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(26.dp)); Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** The 8 page-colour presets as swatches showing the matching text colour ("Aa"). */
@Composable
fun PresetRow(state: ReaderState, onPick: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PAGE_COLOR_PRESETS.forEach { p ->
            val sel = !state.nightMode && (state.pageColor and 0xFFFFFFL) == (p.background and 0xFFFFFFL)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(p.background))
                    .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
                    .clickable { onPick(p.background) },
                contentAlignment = Alignment.Center,
            ) { Text("Aa", color = Color(p.text), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun timeoutLabel(sec: Int): String = when {
    sec < 0 -> stringResource(R.string.vw_never)
    sec < 60 -> stringResource(R.string.vw_seconds, sec)
    sec < 3600 -> stringResource(R.string.vw_minutes, sec / 60)
    else -> stringResource(R.string.vw_hour)
}

@Composable
private fun TimeoutDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vw_screen_timeout)) },
        text = {
            Column {
                ViewExtras.TIMEOUTS.forEach { s ->
                    Row(Modifier.fillMaxWidth().selectable(selected = s == current, onClick = { onPick(s) }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s == current, onClick = null)
                        Spacer(Modifier.width(12.dp)); Text(timeoutLabel(s))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Crop mode menu (reference): automatic, manual, and "remove all page cropping" when a crop is active. */
@Composable
private fun CropMenuDialog(state: ReaderState, onManual: () -> Unit, onDismiss: () -> Unit) {
    val crop = state.vx.crop
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Crop, null) },
        title = { Text(stringResource(R.string.crop_mode)) },
        text = {
            Column {
                ListItem(headlineContent = { Text(stringResource(R.string.vw_crop_auto)) }, leadingContent = { Icon(Icons.Default.CropFree, null) },
                    trailingContent = { if (crop.enabled && crop.manual == null) Icon(Icons.Default.Check, null) },
                    modifier = Modifier.clickable { crop.enableAuto(); state.reflow = false; state.vx.magnifier = false; onDismiss() })
                ListItem(headlineContent = { Text(stringResource(R.string.vw_crop_manual)) }, leadingContent = { Icon(Icons.Default.Crop, null) },
                    trailingContent = { if (crop.enabled && crop.manual != null) Icon(Icons.Default.Check, null) },
                    modifier = Modifier.clickable { onManual() })
                if (crop.enabled) ListItem(headlineContent = { Text(stringResource(R.string.vw_crop_remove)) }, leadingContent = { Icon(Icons.Default.Close, null) },
                    modifier = Modifier.clickable { crop.disable(); onDismiss() })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Manual crop: the current page with the crop frame and four margin sliders; "reset" = detected content box;
 * "symmetric" mirrors left/right margins on even pages (odd/even crop of the reference).
 */
@Composable
private fun ManualCropDialog(state: ReaderState, onDismiss: () -> Unit) {
    val crop = state.vx.crop
    val page = state.currentPage
    val start = crop.manual ?: crop.rectFor(page) ?: CropController.FULL
    var l by remember { mutableFloatStateOf(start.left) }
    var t by remember { mutableFloatStateOf(start.top) }
    var r by remember { mutableFloatStateOf(1f - start.right) }
    var b by remember { mutableFloatStateOf(1f - start.bottom) }
    var symmetric by remember { mutableStateOf(crop.symmetric) }
    val scope = rememberCoroutineScope()
    val thumb by produceState<Bitmap?>(null, page, state.session) {
        val s = state.session ?: return@produceState
        val (w, h) = state.pageSize(page)
        value = runCatching { s.render(page, 300, (300 * h / w).roundToInt().coerceIn(20, 900), false, true) }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vw_crop_manual)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                thumb?.let { bmp ->
                    val aspect = bmp.width / bmp.height.toFloat()
                    Canvas(Modifier.fillMaxWidth(0.6f).aspectRatio(aspect).align(Alignment.CenterHorizontally).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        drawImage(bmp.asImageBitmap(), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                        val rect = Rect(l * size.width, t * size.height, (1f - r) * size.width, (1f - b) * size.height)
                        val shade = Color(0x88000000)
                        drawRect(shade, Offset.Zero, Size(size.width, rect.top))
                        drawRect(shade, Offset(0f, rect.bottom), Size(size.width, size.height - rect.bottom))
                        drawRect(shade, Offset(0f, rect.top), Size(rect.left, rect.height))
                        drawRect(shade, Offset(rect.right, rect.top), Size(size.width - rect.right, rect.height))
                        drawRect(Color(0xFF2196F3), rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
                    }
                }
                MarginSlider(R.string.vw_crop_left, l) { l = it }
                MarginSlider(R.string.vw_crop_right, r) { r = it }
                MarginSlider(R.string.vw_crop_top, t) { t = it }
                MarginSlider(R.string.vw_crop_bottom, b) { b = it }
                Row(Modifier.fillMaxWidth().clickable { symmetric = !symmetric }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(symmetric, { symmetric = it }); Text(stringResource(R.string.vw_crop_symmetric), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = {
                    scope.launch {
                        val d = crop.detected(page) ?: runCatching { crop.detect(page) }.getOrNull() ?: CropController.FULL
                        l = d.left; t = d.top; r = 1f - d.right; b = 1f - d.bottom
                    }
                }) { Icon(Icons.Default.CenterFocusStrong, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.vw_crop_reset)) }
            }
        },
        confirmButton = {
            Button(onClick = { crop.enableManual(Rect(l, t, 1f - r, 1f - b), symmetric); state.reflow = false; state.vx.magnifier = false; onDismiss() }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MarginSlider(label: Int, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text("${stringResource(label)}: ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(value, { onChange(it.coerceIn(0f, 0.45f)) }, valueRange = 0f..0.45f, modifier = Modifier.height(32.dp))
    }
}
