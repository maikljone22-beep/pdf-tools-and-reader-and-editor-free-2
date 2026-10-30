package com.smartdocs.editor.ui.tools5

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.PageQuad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The SmartDocs camera for the scanner: the phone camera at its full resolution (no re-encoding by another app),
 * live page-edge detection ([PageQuad]) drawn over the preview, auto-capture when the page is steady and sharp,
 * flash / torch, tap to focus, pinch to zoom, exposure, a grid, then a corner editor for each photo (the detected
 * corners can be dragged) and a perspective-corrected JPEG at the photo's own resolution.
 *
 * [pageLimit] 0 = unlimited. [onDone] receives the JPEG files in order; [onClose] cancels (pages taken so far are
 * still returned when there are any).
 */
@Composable
fun ScanCameraScreen(pageLimit: Int = 0, onDone: (List<File>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (!it) onClose() }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false)) {
        if (granted) Box(Modifier.fillMaxSize()) { CameraBody(pageLimit, onDone, onClose) }
        else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

/** A photo waiting in the corner editor: the file at full resolution, a preview bitmap and the detected corners. */
private class Shot(val file: File, val preview: Bitmap, val fullW: Int, val fullH: Int, var quad: PageQuad.Quad)

@Composable
private fun CameraBody(pageLimit: Int, onDone: (List<File>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("scan_camera", Context.MODE_PRIVATE) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    var flash by remember { mutableIntStateOf(prefs.getInt("flash", ImageCapture.FLASH_MODE_AUTO)) }
    var autoCapture by remember { mutableStateOf(prefs.getBoolean("auto", true)) }
    var grid by remember { mutableStateOf(prefs.getBoolean("grid", false)) }
    var exposureOpen by remember { mutableStateOf(false) }
    // FINAL_CHECK_2 Low (K11): QR / barcode mode — the analysis frames are decoded instead of edge-detected
    var qrMode by remember { mutableStateOf(false) }
    var qrFound by remember { mutableStateOf<com.smartdocs.editor.core.tools5.BarcodeScan.Code?>(null) }
    var qrTick by remember { mutableIntStateOf(0) }
    var exposure by remember { mutableIntStateOf(0) }
    var quad by remember { mutableStateOf<PageQuad.Quad?>(null) }
    var frameSize by remember { mutableStateOf(IntSize(4, 3)) }
    var steady by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Shot?>(null) }
    val pages = remember { mutableStateOf(listOf<File>()) }
    var lastAuto by remember { mutableStateOf(0L) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    val controller = remember {
        LifecycleCameraController(ctx).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE).build()
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
            isTapToFocusEnabled = true; isPinchToZoomEnabled = true
        }
    }
    LaunchedEffect(flash) { controller.imageCaptureFlashMode = flash; prefs.edit().putInt("flash", flash).apply() }
    DisposableEffect(Unit) {
        controller.bindToLifecycle(lifecycle)
        onDispose { controller.unbind(); executor.shutdown() }
    }

    fun finish() { onDone(pages.value) }

    /** Full-resolution capture; the photo then opens in the corner editor. */
    fun capture() {
        if (capturing || shot != null) return
        capturing = true
        val f = app.documents.newTemp("cam_raw", "jpg")
        controller.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                scope.launch {
                    val s = withContext(Dispatchers.IO) { runCatching { openShot(f) }.getOrNull() }
                    capturing = false
                    if (s != null) shot = s else f.delete()
                }
            }
            override fun onError(e: ImageCaptureException) { scope.launch { capturing = false; f.delete() } }
        })
    }

    // live page edges + auto-capture
    LaunchedEffect(Unit) {
        var last: PageQuad.Quad? = null
        controller.setImageAnalysisAnalyzer(executor) { img ->
            try {
                if (shot != null || capturing) { quad = null; steady = 0; return@setImageAnalysisAnalyzer }
                val (px, w, h) = rgba(img)
                if (qrMode) {
                    quad = null; steady = 0
                    if (qrFound == null && qrTick++ % 2 == 0) com.smartdocs.editor.core.tools5.BarcodeScan.decode(px, w, h, multiple = false).firstOrNull()?.let { c -> scope.launch { qrFound = c } }
                    return@setImageAnalysisAnalyzer
                }
                val q = PageQuad.detect(px, w, h)?.takeIf { it.area() < 0.97f }
                val moved = if (q == null || last == null) 1f else q.distance(last!!)
                last = q
                val st = if (q != null && moved < 0.012f) steady + 1 else 0
                scope.launch {
                    frameSize = IntSize(w, h); quad = q; steady = st
                    val now = System.currentTimeMillis()
                    if (autoCapture && q != null && st >= 10 && now - lastAuto > 2500L && !capturing && shot == null) {
                        // steady for ~1 s: check the frame is sharp enough, then take the photo
                        if (PageQuad.sharpness(px, w, h) > 6.0) { lastAuto = now; capture() }
                    }
                }
            } finally { img.close() }
        }
    }

    BackHandler { if (shot != null) { shot?.file?.delete(); shot = null } else if (pages.value.isNotEmpty()) finish() else onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c -> PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = controller } },
            Modifier.fillMaxSize().onSizeChanged { viewSize = it })
        // the page outline (green when steady) and the grid
        Canvas(Modifier.fillMaxSize()) {
            if (grid) {
                val c = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) { drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f); drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f) }
            }
            quad?.let { q ->
                val r = fillRect(frameSize, size.width, size.height)
                val p = Path()
                for (i in 0 until 4) { val x = r.left + q.x[i] * r.width; val y = r.top + q.y[i] * r.height; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                p.close()
                val col = if (steady >= 10) Color(0xFF4CD964) else Color(0xFF2F8CFF)
                drawPath(p, col.copy(alpha = 0.18f))
                drawPath(p, col, style = Stroke(5f))
                for (i in 0 until 4) drawCircle(Color.White, 9f, Offset(r.left + q.x[i] * r.width, r.top + q.y[i] * r.height))
            }
        }
        // top bar
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (pages.value.isNotEmpty()) finish() else onClose() }) { Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White) }
            Row {
                IconButton(onClick = { flash = when (flash) { ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF; else -> ImageCapture.FLASH_MODE_AUTO } }) {
                    Icon(when (flash) { ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn; ImageCapture.FLASH_MODE_OFF -> Icons.Default.FlashOff; else -> Icons.Default.FlashAuto }, stringResource(R.string.sc_cam_flash), tint = Color.White)
                }
                IconButton(onClick = { autoCapture = !autoCapture; prefs.edit().putBoolean("auto", autoCapture).apply() }) {
                    Icon(Icons.Default.MotionPhotosAuto, stringResource(R.string.sc_cam_auto), tint = if (autoCapture) Color(0xFF4CD964) else Color.White)
                }
                IconButton(onClick = { grid = !grid; prefs.edit().putBoolean("grid", grid).apply() }) { Icon(Icons.Default.GridOn, stringResource(R.string.sc_cam_grid), tint = if (grid) Color(0xFF4CD964) else Color.White) }
                IconButton(onClick = { exposureOpen = !exposureOpen }) { Icon(Icons.Default.Exposure, stringResource(R.string.sc_cam_exposure), tint = if (exposureOpen) Color(0xFF4CD964) else Color.White) }
                IconButton(onClick = { qrMode = !qrMode; qrFound = null }) { Icon(Icons.Default.QrCodeScanner, stringResource(R.string.sc_cam_qr), tint = if (qrMode) Color(0xFF4CD964) else Color.White) }
            }
        }
        // hint
        Text(
            stringResource(when { capturing -> R.string.sc_cam_taking; qrMode -> R.string.sc_cam_hint_qr; quad == null -> R.string.sc_cam_hint_find; autoCapture && steady < 10 -> R.string.sc_cam_hint_hold; else -> R.string.sc_cam_hint_ready }),
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
            color = Color.White, style = MaterialTheme.typography.bodyMedium,
        )
        // bottom: exposure, shutter, pages
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            if (exposureOpen) {
                val st = controller.cameraInfo?.exposureState
                val lo = st?.exposureCompensationRange?.lower ?: 0; val hi = st?.exposureCompensationRange?.upper ?: 0
                if (hi > lo) Slider(exposure.toFloat(), { v -> exposure = v.roundToInt(); controller.cameraControl?.setExposureCompensationIndex(exposure) },
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp), valueRange = lo.toFloat()..hi.toFloat(), steps = (hi - lo - 1).coerceAtLeast(0))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp))
                FilledIconButton(onClick = { capture() }, Modifier.size(78.dp), enabled = !capturing, shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White)) {
                    if (capturing) CircularProgressIndicator(Modifier.size(32.dp)) else Box(Modifier.size(62.dp).background(Color.White, CircleShape).padding(3.dp).background(Color.Black, CircleShape).padding(2.dp).background(Color.White, CircleShape))
                }
                if (pages.value.isNotEmpty()) BadgedBox(badge = { Badge { Text(pages.value.size.toString()) } }) {
                    FilledIconButton(onClick = { finish() }, Modifier.size(56.dp)) { Icon(Icons.Default.Check, stringResource(R.string.sc_cam_done)) }
                } else Box(Modifier.size(56.dp))
            }
        }
        if (capturing) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.25f)))
        qrFound?.let { c ->
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            androidx.compose.material3.Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(c.format.replace('_', ' '), style = MaterialTheme.typography.labelMedium)
                    Text(c.text, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(c.text)) }) { Text(stringResource(R.string.copy_text)) }
                        if (com.smartdocs.editor.core.tools5.BarcodeScan.isLink(c.text)) TextButton(onClick = {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(c.text.trim())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text(stringResource(R.string.sc_code_open)) }
                        TextButton(onClick = { qrFound = null }) { Text(stringResource(R.string.sc_cam_qr_again)) }
                    }
                }
            }
        }
    }

    shot?.let { s ->
        CornerEditor(s, onRetake = { s.file.delete(); shot = null }, onUse = { q ->
            shot = null
            scope.launch {
                val out = withContext(Dispatchers.IO) { runCatching { straighten(ctx, s, q, app.documents.newTemp("cam", "jpg")) }.getOrNull() }
                s.file.delete()
                if (out != null) {
                    pages.value = pages.value + out
                    if (pageLimit > 0 && pages.value.size >= pageLimit) finish()
                }
            }
        })
    }
}

/** The preview rectangle (FILL_CENTER) of a [frame]-sized image inside a view of [vw]×[vh]. */
private fun fillRect(frame: IntSize, vw: Float, vh: Float): Rect {
    val k = max(vw / frame.width, vh / frame.height)
    val w = frame.width * k; val h = frame.height * k
    return Rect((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2)
}

/** The RGBA analysis frame as upright ARGB pixels (rotated by the frame's rotation). */
private fun rgba(img: androidx.camera.core.ImageProxy): Triple<IntArray, Int, Int> {
    val plane = img.planes[0]; val buf: ByteBuffer = plane.buffer; val stride = plane.rowStride; val ps = plane.pixelStride
    val w = img.width; val h = img.height
    val px = IntArray(w * h)
    val row = ByteArray(stride)
    for (y in 0 until h) {
        buf.position(y * stride); buf.get(row, 0, min(stride, buf.remaining()))
        var i = 0
        for (x in 0 until w) {
            val r = row[i].toInt() and 0xFF; val g = row[i + 1].toInt() and 0xFF; val b = row[i + 2].toInt() and 0xFF
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            i += ps
        }
    }
    return when (img.imageInfo.rotationDegrees) {
        90 -> Triple(IntArray(w * h) { k -> val x = k % h; val y = k / h; px[(h - 1 - x) * w + y] }, h, w)
        180 -> Triple(IntArray(w * h) { k -> px[w * h - 1 - k] }, w, h)
        270 -> Triple(IntArray(w * h) { k -> val x = k % h; val y = k / h; px[x * w + (w - 1 - y)] }, h, w)
        else -> Triple(px, w, h)
    }
}

/** Decode a photo (upright, ≤ 16 MP) into a preview bitmap and detect its page corners. */
private fun openShot(f: File): Shot? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, o)
    if (o.outWidth <= 0) return null
    val rot = exifRotation(f)
    val fullW = if (rot % 180 == 0) o.outWidth else o.outHeight; val fullH = if (rot % 180 == 0) o.outHeight else o.outWidth
    val preview = decode(f, 1400, rot) ?: return null
    val px = IntArray(preview.width * preview.height); preview.getPixels(px, 0, preview.width, 0, 0, preview.width, preview.height)
    // detection on a small copy
    val k = max(1, max(preview.width, preview.height) / 560)
    val sw = preview.width / k; val sh = preview.height / k
    val small = IntArray(sw * sh) { i -> px[(i / sw) * k * preview.width + (i % sw) * k] }
    val q = PageQuad.detect(small, sw, sh)?.takeIf { it.area() < 0.985f } ?: PageQuad.Quad.FULL
    return Shot(f, preview, fullW, fullH, q)
}

private fun exifRotation(f: File): Int = runCatching {
    when (ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
    }
}.getOrDefault(0)

/** Decode [f] with the long side ≤ [maxDim] (power-of-two subsampling, so a full-resolution request keeps every pixel) and rotate upright. */
private fun decode(f: File, maxDim: Int, rot: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, o)
    var sample = 1
    while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
    val b = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
    if (rot == 0) return b
    val m = Matrix().apply { postRotate(rot.toFloat()) }
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it !== b) b.recycle() }
}

/** Perspective-correct the full-resolution photo with [q] and write a JPEG (quality 96) to [out]. */
private fun straighten(ctx: Context, s: Shot, q: PageQuad.Quad, out: File): File {
    // the full photo, capped at 16 MP so two copies fit in memory
    val b = decode(s.file, 4096, exifRotation(s.file)) ?: throw IllegalStateException("decode")
    val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); b.recycle()
    val (wp, ow, oh) = if (q === PageQuad.Quad.FULL || q.distance(PageQuad.Quad.FULL) < 0.002f) Triple(px, b.width, b.height) else PageQuad.warp(px, b.width, b.height, q, maxDim = 6000)
    val r = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888); r.setPixels(wp, 0, ow, 0, 0, ow, oh)
    out.outputStream().use { r.compress(Bitmap.CompressFormat.JPEG, 96, it) }
    r.recycle()
    return out
}

/** The photo with four draggable corner handles; "use" straightens it, "full" takes the whole photo. */
@Composable
private fun CornerEditor(s: Shot, onRetake: () -> Unit, onUse: (PageQuad.Quad) -> Unit) {
    var qx by remember { mutableStateOf(s.quad.x.copyOf()) }
    var qy by remember { mutableStateOf(s.quad.y.copyOf()) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var drag by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val grab = with(density) { 36.dp.toPx() }
    fun imageRect(): Rect {
        if (box.width == 0 || box.height == 0) return Rect.Zero
        val k = min(box.width.toFloat() / s.preview.width, box.height.toFloat() / s.preview.height)
        val w = s.preview.width * k; val h = s.preview.height * k
        return Rect((box.width - w) / 2, (box.height - h) / 2, (box.width + w) / 2, (box.height + h) / 2)
    }
    BackHandler { onRetake() }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding().onSizeChanged { box = it }
            .pointerInput(s) {
                detectDragGestures(
                    onDragStart = { p ->
                        val r = imageRect(); var best = -1; var bd = grab
                        for (i in 0 until 4) { val d = hypot(p.x - (r.left + qx[i] * r.width), p.y - (r.top + qy[i] * r.height)); if (d < bd) { bd = d; best = i } }
                        drag = best
                    },
                    onDragEnd = { drag = -1 }, onDragCancel = { drag = -1 },
                ) { change, d ->
                    val i = drag; if (i < 0) return@detectDragGestures
                    change.consume()
                    val r = imageRect(); if (r.width <= 0f) return@detectDragGestures
                    qx = qx.copyOf().also { it[i] = (it[i] + d.x / r.width).coerceIn(0f, 1f) }
                    qy = qy.copyOf().also { it[i] = (it[i] + d.y / r.height).coerceIn(0f, 1f) }
                }
            }) {
            Image(s.preview.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Canvas(Modifier.fillMaxSize()) {
                val r = imageRect(); if (r.width <= 0f) return@Canvas
                val p = Path()
                for (i in 0 until 4) { val x = r.left + qx[i] * r.width; val y = r.top + qy[i] * r.height; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                p.close()
                drawPath(p, Color(0xFF2F8CFF).copy(alpha = 0.15f))
                drawPath(p, Color(0xFF2F8CFF), style = Stroke(4f))
                for (i in 0 until 4) {
                    val c = Offset(r.left + qx[i] * r.width, r.top + qy[i] * r.height)
                    drawCircle(Color.White, if (drag == i) 22f else 16f, c); drawCircle(Color(0xFF2F8CFF), if (drag == i) 22f else 16f, c, style = Stroke(3f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRetake) { Icon(Icons.Default.Refresh, null, tint = Color.White); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.sc_cam_retake), color = Color.White) }
            OutlinedButton(onClick = { qx = PageQuad.Quad.FULL.x.copyOf(); qy = PageQuad.Quad.FULL.y.copyOf() }) { Icon(Icons.Default.CropFree, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.sc_cam_full)) }
            Button(onClick = { onUse(PageQuad.Quad(qx, qy)) }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.sc_cam_use)) }
        }
        Spacer(Modifier.height(4.dp))
    }
}
