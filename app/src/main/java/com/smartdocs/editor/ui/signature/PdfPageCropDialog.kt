package com.smartdocs.editor.ui.signature

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.tools5.CompareEngine
import java.io.File

/**
 * QuickSign "From file: PDF page" (nitro-quicksign): choose a page of [file], render it (PDFium, ~1400 px wide), drag a
 * crop rectangle around the signature and hand the cropped bitmap to the normal image path (background removal, save).
 */
@Composable
internal fun PdfPageCropDialog(file: File, onDismiss: () -> Unit, onCropped: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    var pageCount by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var pageText by remember { mutableStateOf("1") }
    var error by remember { mutableStateOf<String?>(null) }
    // crop rectangle in fractions of the page (left, top, right, bottom)
    var crop by remember { mutableStateOf(RectF(0.1f, 0.4f, 0.9f, 0.6f)) }
    val pwMsg = stringResource(R.string.nq_pdf_password)
    val failMsg = stringResource(R.string.nq_pdf_failed)

    LaunchedEffect(file) {
        pageCount = try {
            val s = PdfSession.open(ctx, file, null)
            try { s.pageCount } finally { s.closeAsync() }
        } catch (e: PdfSession.Companion.PasswordRequiredException) { error = pwMsg; 0 } catch (e: Exception) { error = failMsg; 0 }
    }
    val bmp by produceState<Bitmap?>(null, file, page, pageCount) {
        value = null
        if (pageCount > 0) value = runCatching { CompareEngine.render(ctx, file, null, page, 1400) }.getOrNull().also { if (it == null) error = failMsg }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nq_pdf_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.nq_pdf_crop_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pageCount > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (page > 0) { page--; pageText = "${page + 1}" } }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.pc_cd_prev_page)) }
                    OutlinedTextField(pageText, { t ->
                        pageText = t.filter { it.isDigit() }.take(5)
                        pageText.toIntOrNull()?.takeIf { it in 1..pageCount }?.let { page = it - 1 }
                    }, Modifier.width(96.dp), singleLine = true, label = { Text(stringResource(R.string.nq_pdf_page)) })
                    Text(stringResource(R.string.nq_pdf_of, pageCount), Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = { if (page < pageCount - 1) { page++; pageText = "${page + 1}" } }, enabled = page < pageCount - 1) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.pc_cd_next_page)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b == null) { if (error == null) CircularProgressIndicator() }
                    else CropArea(b, crop) { crop = it }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = {
                        val b = bmp ?: return@Button
                        val l = (crop.left * b.width).toInt().coerceIn(0, b.width - 1); val t = (crop.top * b.height).toInt().coerceIn(0, b.height - 1)
                        val w = ((crop.right - crop.left) * b.width).toInt().coerceIn(1, b.width - l); val h = ((crop.bottom - crop.top) * b.height).toInt().coerceIn(1, b.height - t)
                        onCropped(Bitmap.createBitmap(b, l, t, w, h))
                    }, enabled = bmp != null) { Text(stringResource(R.string.nq_pdf_use)) }
                }
            }
        }
    }
}

/** The page with a draggable crop rectangle: drag a corner to resize, drag inside to move. */
@Composable
private fun CropArea(bmp: Bitmap, crop: RectF, onChange: (RectF) -> Unit) {
    val density = LocalDensity.current
    val handle = with(density) { 28.dp.toPx() }
    val current by rememberUpdatedState(crop)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ratio = bmp.width.toFloat() / bmp.height
        val boxW = with(density) { maxWidth.toPx() }; val boxH = with(density) { maxHeight.toPx() }
        val w = minOf(boxW, boxH * ratio); val h = w / ratio
        Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().pointerInput(w, h) {
                var mode = 0   // 0 none, 1..4 corners (tl, tr, bl, br), 5 move
                detectDragGestures(
                    onDragStart = { p ->
                        val r = current
                        val corners = listOf(Offset(r.left * w, r.top * h), Offset(r.right * w, r.top * h), Offset(r.left * w, r.bottom * h), Offset(r.right * w, r.bottom * h))
                        val near = corners.indexOfFirst { (it - p).getDistance() < handle }
                        mode = when {
                            near >= 0 -> near + 1
                            p.x in r.left * w..r.right * w && p.y in r.top * h..r.bottom * h -> 5
                            else -> 0
                        }
                    },
                    onDrag = { ch, d ->
                        ch.consume()
                        val dx = d.x / w; val dy = d.y / h
                        val r = RectF(current)
                        val min = 0.03f
                        when (mode) {
                            1 -> { r.left = (r.left + dx).coerceIn(0f, r.right - min); r.top = (r.top + dy).coerceIn(0f, r.bottom - min) }
                            2 -> { r.right = (r.right + dx).coerceIn(r.left + min, 1f); r.top = (r.top + dy).coerceIn(0f, r.bottom - min) }
                            3 -> { r.left = (r.left + dx).coerceIn(0f, r.right - min); r.bottom = (r.bottom + dy).coerceIn(r.top + min, 1f) }
                            4 -> { r.right = (r.right + dx).coerceIn(r.left + min, 1f); r.bottom = (r.bottom + dy).coerceIn(r.top + min, 1f) }
                            5 -> {
                                val mx = dx.coerceIn(-r.left, 1f - r.right); val my = dy.coerceIn(-r.top, 1f - r.bottom)
                                r.offset(mx, my)
                            }
                            else -> return@detectDragGestures
                        }
                        onChange(r)
                    },
                )
            }) {
                val l = crop.left * size.width; val t = crop.top * size.height; val r = crop.right * size.width; val b = crop.bottom * size.height
                val dim = Color.Black.copy(alpha = 0.45f)
                drawRect(dim, Offset.Zero, Size(size.width, t))
                drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
                drawRect(dim, Offset(0f, t), Size(l, b - t))
                drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
                drawRect(Color(0xFF2196F3), Offset(l, t), Size(r - l, b - t), style = Stroke(width = 2.dp.toPx()))
                listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { drawCircle(Color(0xFF2196F3), 8.dp.toPx(), it) }
            }
        }
    }
}
