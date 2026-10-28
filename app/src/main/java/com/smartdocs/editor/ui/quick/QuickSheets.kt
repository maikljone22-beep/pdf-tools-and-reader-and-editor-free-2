package com.smartdocs.editor.ui.quick

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.io.File

// ======================= shared sheet scaffolding =======================

/** Compact bottom sheet: title row, content, one primary button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSheet(title: String, primary: String, primaryEnabled: Boolean = true, onPrimary: () -> Unit, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
            }
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(primary, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun QuickLabel(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
fun QuickSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(84.dp))
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(display, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
    }
}

/** Small render of the current page, for live previews. */
@Composable
fun rememberPageThumb(state: ReaderState, widthPx: Int = 360): Pair<ImageBitmap?, Pair<Float, Float>> {
    val page = state.currentPage
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var size by remember { mutableStateOf(state.pageSizes[page] ?: (595f to 842f)) }
    LaunchedEffect(page) {
        val s = state.session ?: return@LaunchedEffect
        val sz = state.pageSize(page); size = sz
        val h = (widthPx * sz.second / sz.first).toInt().coerceAtLeast(1)
        thumb = runCatching { s.render(page, widthPx, h, annotations = true).asImageBitmap() }.getOrNull()
    }
    return thumb to size
}

// ======================= compress =======================

enum class CompressQuality(val jpeg: Int, val maxDim: Int, val label: Int) {
    LOW(40, 1000, R.string.qk_q_low), MEDIUM(60, 1400, R.string.qk_q_medium), HIGH(80, 2000, R.string.qk_q_high),
}

/** A precomputed compression result: reused by "apply" when the document did not change meanwhile. */
class CompressEstimate(val file: File, private val srcLength: Long, private val srcModified: Long) {
    fun matches(src: File) = src.length() == srcLength && src.lastModified() == srcModified
}

@Composable
fun CompressSheet(c: QuickController, onDismiss: () -> Unit) {
    val state = c.state
    var quality by remember { mutableStateOf(CompressQuality.MEDIUM) }
    val estimates = remember { mutableStateMapOf<CompressQuality, CompressEstimate>() }
    var failed by remember { mutableStateOf(false) }
    val original = remember { state.file?.length() ?: 0L }
    DisposableEffect(Unit) { onDispose { estimates.values.forEach { it.file.delete() } } }
    LaunchedEffect(quality) {
        if (estimates[quality] != null) return@LaunchedEffect
        val f = state.file ?: return@LaunchedEffect
        failed = false
        val out = state.app.documents.newTemp("cmp_${quality.name}")
        try {
            val len = f.length(); val mod = f.lastModified()
            // audit 5 T-04: switching chips cancels only this coroutine (PdfBox keeps working): one at a time
            c.compressLock.withLock { PdfOps.compress(f, out, quality.jpeg, quality.maxDim, state.password) }
            estimates[quality] = CompressEstimate(out, len, mod)
        } catch (e: CancellationException) { out.delete(); throw e } catch (e: Throwable) { out.delete(); failed = true }
    }
    val est = estimates[quality]
    val after = est?.file?.length()
    val gain = after != null && after < original
    // audit 5 T-04: "Compress" waits for the estimate (it reuses it), so a second compression never starts beside it
    QuickSheet(stringResource(R.string.qk_compress_title), stringResource(R.string.qk_compress_btn), primaryEnabled = gain || failed,
        onPrimary = { estimates.remove(quality); c.compress(est, quality) }, onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompressQuality.entries.forEach { q -> FilterChip(quality == q, { quality = q }, label = { Text(stringResource(q.label)) }) }
        }
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                when {
                    failed -> Text(stringResource(R.string.qk_estimate_failed), style = MaterialTheme.typography.bodyMedium)
                    after == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.qk_estimating, DocumentRepository.formatSize(original)))
                    }
                    gain -> {
                        val pct = ((original - after!!) * 100 / original.coerceAtLeast(1)).toInt()
                        Text(stringResource(R.string.qk_size_from_to, DocumentRepository.formatSize(original), DocumentRepository.formatSize(after)), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.qk_saving_pct, pct), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> Text(stringResource(R.string.qk_no_gain), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.qk_compress_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ======================= watermark =======================

private val WM_COLORS = listOf(0x9E9E9E, 0xE53935, 0x1E88E5, 0x43A047, 0x212121)

@Composable
fun WatermarkSheet(c: QuickController, onDismiss: () -> Unit) {
    val settings = LocalSettings.current
    val fallback = stringResource(R.string.qk_wm_default)
    var text by remember { mutableStateOf(settings.authorName.ifBlank { fallback }) }
    var opacity by remember { mutableFloatStateOf(0.3f) }
    var rotation by remember { mutableFloatStateOf(45f) }
    var wmSize by remember { mutableFloatStateOf(48f) }
    var color by remember { mutableIntStateOf(WM_COLORS[0]) }
    val (thumb, pageSize) = rememberPageThumb(c.state)

    QuickSheet(stringResource(R.string.qk_wm_title), stringResource(R.string.qk_wm_btn), primaryEnabled = text.isNotBlank(),
        onPrimary = { c.watermark(text.trim(), wmSize, color, opacity, rotation) }, onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.Top) {
            // live mini preview of the current page
            Box(
                Modifier.width(110.dp).aspectRatio(pageSize.first / pageSize.second).clip(RoundedCornerShape(6.dp))
                    .background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    thumb?.let { drawImage(it, dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt())) }
                    val k = this.size.width / pageSize.first
                    drawIntoCanvas { cv ->
                        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            textSize = wmSize * k; this.color = (0xFF shl 24) or color; alpha = (opacity * 255).toInt()
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        val cx = this.size.width / 2; val cy = this.size.height / 2
                        cv.nativeCanvas.save(); cv.nativeCanvas.rotate(-rotation, cx, cy)
                        cv.nativeCanvas.drawText(text, cx, cy + p.textSize / 3, p); cv.nativeCanvas.restore()
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.qk_wm_text)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WM_COLORS.forEach { col ->
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(Color((0xFF shl 24) or col))
                            .border(if (col == color) 3.dp else 1.dp, if (col == color) MaterialTheme.colorScheme.primary else Color(0x33000000), RoundedCornerShape(50))
                            .clickable { color = col })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        QuickSlider(stringResource(R.string.qk_wm_opacity), opacity, 0.05f..1f, "${(opacity * 100).toInt()}%") { opacity = it }
        QuickSlider(stringResource(R.string.qk_wm_rotation), rotation, -90f..90f, "${rotation.toInt()}°") { rotation = it }
        QuickSlider(stringResource(R.string.qk_wm_size), wmSize, 12f..140f, wmSize.toInt().toString()) { wmSize = it }
    }
}

// ======================= page numbers =======================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageNumbersSheet(c: QuickController, onDismiss: () -> Unit) {
    val arabicUi = LocalConfiguration.current.locales[0].language == "ar"
    var position by remember { mutableStateOf(PdfOps.NumberPosition.BOTTOM_CENTER) }
    var arabicDigits by remember { mutableStateOf(arabicUi) }
    var start by remember { mutableStateOf("1") }
    val positions = listOf(
        PdfOps.NumberPosition.BOTTOM_CENTER to R.string.qk_pn_bc, PdfOps.NumberPosition.BOTTOM_RIGHT to R.string.qk_pn_br,
        PdfOps.NumberPosition.BOTTOM_LEFT to R.string.qk_pn_bl, PdfOps.NumberPosition.TOP_CENTER to R.string.qk_pn_tc,
    )
    val startN = start.toIntOrNull()?.coerceAtLeast(0) ?: 1
    QuickSheet(stringResource(R.string.qk_pn_title), stringResource(R.string.qk_pn_btn),
        onPrimary = { c.pageNumbers(position, startN, arabicDigits) }, onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.Top) {
            // schematic preview: where the number lands (physical left/right, independent of RTL layout)
            Box(Modifier.width(72.dp).aspectRatio(0.707f).clip(RoundedCornerShape(6.dp)).background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)).padding(6.dp)) {
                val label = if (arabicDigits) startN.toString().map { '٠' + (it - '0') }.joinToString("") else startN.toString()
                val align = when (position) {
                    PdfOps.NumberPosition.BOTTOM_CENTER -> Alignment.BottomCenter
                    PdfOps.NumberPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
                    PdfOps.NumberPosition.BOTTOM_LEFT -> Alignment.BottomStart
                    PdfOps.NumberPosition.TOP_CENTER -> Alignment.TopCenter
                }
                CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    Text(label, Modifier.align(align), style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.qk_pn_position), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    positions.forEach { (p, l) -> FilterChip(position == p, { position = p }, label = { Text(stringResource(l)) }) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.qk_pn_arabic), Modifier.weight(1f))
            Switch(arabicDigits, { arabicDigits = it })
        }
        OutlinedTextField(start, { v -> start = v.filter { it.isDigit() }.take(5) }, label = { Text(stringResource(R.string.qk_pn_start)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(160.dp))
    }
}
