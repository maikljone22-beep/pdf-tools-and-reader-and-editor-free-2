package com.smartdocs.editor.ui.tools

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.common.PageRangeField
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner

private val wmColors = listOf(0x9E9E9E, 0xE53935, 0x1E88E5, 0x43A047, 0xFB8C00, 0x212121)

/** Tool "watermark": text watermark with a live preview on the first page. */
@Composable
fun WatermarkScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    var text by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(48f) }
    var opacity by remember { mutableFloatStateOf(0.3f) }
    var rotation by remember { mutableFloatStateOf(45f) }
    var color by remember { mutableIntStateOf(wmColors[0]) }
    var range by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var pageSize by remember { mutableStateOf(595f to 842f) }
    var pages by remember { mutableIntStateOf(0) }
    var previewError by remember { mutableStateOf<String?>(null) }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val processing = stringResource(R.string.processing)
    val needPassword = stringResource(R.string.password_enter)

    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        preview = null; previewError = null
        runCatching {
            val s = PdfSession.open(ctx, f)
            try {
                pages = s.pageCount
                val (w, h) = s.pageSize(0); pageSize = w to h
                val pw = 700; val ph = (700 * h / w).toInt().coerceAtLeast(1)
                preview = s.render(0, pw, ph)
            } finally { s.closeAsync() }
        }.onFailure { previewError = if (it is PdfSession.Companion.PasswordRequiredException) needPassword else (it.message ?: it.toString()) }
    }

    ToolScaffold(stringResource(R.string.tool_watermark), nav, runner) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 700.dp
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { WatermarkOptions(file, pick, text, { text = it }, fontSize, { fontSize = it }, opacity, { opacity = it }, rotation, { rotation = it }, color, { color = it }, range, { range = it }) }
                Column(Modifier.weight(1f)) { WatermarkPreview(preview, previewError, pageSize, text, fontSize, opacity, rotation, color) }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WatermarkOptions(file, pick, text, { text = it }, fontSize, { fontSize = it }, opacity, { opacity = it }, rotation, { rotation = it }, color, { color = it }, range, { range = it })
                WatermarkPreview(preview, previewError, pageSize, text, fontSize, opacity, rotation, color)
            }
        }
        PrimaryButton(stringResource(R.string.tl_add_watermark), enabled = file != null && text.isNotBlank(), icon = Icons.Default.BrandingWatermark) {
            val src = file ?: return@PrimaryButton
            val t = text; val fs = fontSize; val op = opacity; val rot = rotation; val col = color; val spec = range; val total = pages
            runner.run(processing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, "watermark"))
                val count = if (total > 0) total else PdfOps.pageCount(src)
                val target = if (spec.isBlank()) null else PdfOps.parseRange(spec, count)
                PdfOps.watermark(ctx, src, t, out, fs, col, op, rot, target)
                out
            }
        }
    }
}

@Composable
private fun WatermarkOptions(
    file: java.io.File?, pick: () -> Unit,
    text: String, onText: (String) -> Unit,
    fontSize: Float, onFontSize: (Float) -> Unit,
    opacity: Float, onOpacity: (Float) -> Unit,
    rotation: Float, onRotation: (Float) -> Unit,
    color: Int, onColor: (Int) -> Unit,
    range: String, onRange: (String) -> Unit,
) {
    FilePickRow(file, stringResource(R.string.tl_input_file), pick)
    OutlinedTextField(text, onText, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.watermark_text)) }, singleLine = true)
    LabeledSlider(stringResource(R.string.watermark_size), fontSize, 12f..120f, onFontSize)
    LabeledSlider(stringResource(R.string.prop_opacity), opacity, 0.05f..1f, onOpacity) { "${(it * 100).toInt()}%" }
    LabeledSlider(stringResource(R.string.watermark_rotation), rotation, -90f..90f, onRotation) { "${it.toInt()}°" }
    FieldLabel(stringResource(R.string.prop_color))
    ColorChips(wmColors, color, onColor)
    PageRangeField(range, onRange)
}

@Composable
private fun WatermarkPreview(bitmap: Bitmap?, error: String?, pageSize: Pair<Float, Float>, text: String, fontSize: Float, opacity: Float, rotation: Float, color: Int) {
    FieldLabel(stringResource(R.string.tl_preview))
    Spacer(Modifier.height(4.dp))
    if (error != null) { Text(error, color = MaterialTheme.colorScheme.error); return }
    if (bitmap == null) {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.tl_preview_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    // aspectRatio() throws for 0 / NaN (broken MediaBox): fall back to A4 portrait
    val ratio = (pageSize.first / pageSize.second).takeIf { it.isFinite() && it > 0f } ?: 0.707f
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp))) {
            val scale = size.width / pageSize.first
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.drawBitmap(bitmap, null, android.graphics.RectF(0f, 0f, size.width, size.height), null)
                if (text.isNotBlank()) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = (0xFF000000.toInt() or color); alpha = (opacity * 255).toInt().coerceIn(0, 255)
                        textSize = fontSize * scale; textAlign = Paint.Align.CENTER
                    }
                    val cx = size.width / 2; val cy = size.height / 2
                    nc.save()
                    nc.rotate(-rotation, cx, cy)
                    nc.drawText(text, cx, cy + paint.textSize / 3, paint)
                    nc.restore()
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
    Text(stringResource(R.string.tl_preview_first_page), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
