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
import com.smartdocs.editor.core.tools5.ScanKind
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.produceState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.CameraAlt
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
fun ScanCameraScreen(pageLimit: Int = 0, onDone: (List<Pair<File, PageQuad.Quad?>>) -> Unit, onClose: () -> Unit, onUseGoogle: (() -> Unit)? = null,
                     kind: ScanKind = ScanKind.DOCUMENT, onKind: ((ScanKind) -> Unit)? = null, onGallery: ((List<Pair<File, PageQuad.Quad?>>) -> Unit)? = null) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    // coming back from the system settings with the permission given
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !granted && ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { granted = true; denied = false }
        }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false)) {
        if (granted) Box(Modifier.fillMaxSize()) { CameraBody(pageLimit, onDone, onClose, kind, onKind, onGallery) }
        else if (denied) {
            // scanner UX audit: a refused permission used to close the camera without a word
            BackHandler { onClose() }
            Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CameraAlt, null, tint = Color.White, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.sc_cam_denied), color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.sc_cam_allow)) }
                TextButton(onClick = {
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text(stringResource(R.string.sc_cam_settings), color = Color.White) }
                if (onUseGoogle != null) TextButton(onClick = onUseGoogle) { Text(stringResource(R.string.sc_cam_use_google), color = Color.White) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.close), color = Color.White) }
            }
        }
        else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

/** A photo waiting in the corner editor: the file at full resolution, a preview bitmap and the detected corners. */
private class Shot(val file: File, val preview: Bitmap, val fullW: Int, val fullH: Int, var quad: PageQuad.Quad)

@Composable
private fun CameraBody(pageLimit: Int, onDone: (List<Pair<File, PageQuad.Quad?>>) -> Unit, onClose: () -> Unit,
                       kind: ScanKind, onKind: ((ScanKind) -> Unit)?, onGallery: ((List<Pair<File, PageQuad.Quad?>>) -> Unit)?) {
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
    // S2: the corner editor after every photo is optional (off: continuous capture, edges adjusted in the review)
    var reviewEach by remember { mutableStateOf(prefs.getBoolean("review_each", false)) }
    var torch by remember { mutableStateOf(false) }
    // deep audit E3: after a photo, auto-capture waits until the page changes (turned / replaced) or leaves the frame
    var armed by remember { mutableStateOf(true) }
    var shotSig by remember { mutableStateOf<IntArray?>(null) }
    var lastSig by remember { mutableStateOf<IntArray?>(null) }
    var blurry by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val view = androidx.compose.ui.platform.LocalView.current
    val sound = remember { runCatching { android.media.MediaActionSound().apply { load(android.media.MediaActionSound.SHUTTER_CLICK) } }.getOrNull() }
    val capturedMsg = stringResource(R.string.sc_cam_captured)
    val badCorners = stringResource(R.string.sc_cam_bad_corners)
    val captureFailed = stringResource(R.string.sc_cam_failed)
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
    val pages = remember { mutableStateOf(listOf<Pair<File, PageQuad.Quad?>>()) }
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
        onDispose { controller.unbind(); executor.shutdown(); sound?.release() }
    }
    LaunchedEffect(torch) { runCatching { controller.enableTorch(torch) } }

    fun finish() { if (!capturing) onDone(pages.value) }

    /** A page kept with its corners (FULL = the whole photo). */
    fun addPage(f: File, q: PageQuad.Quad) {
        pages.value = pages.value + (f to q.takeIf { it.distance(PageQuad.Quad.FULL) >= 0.002f })
        lastAuto = System.currentTimeMillis()
        val n = pages.value.size
        status = capturedMsg.format(n)
        if (pageLimit > 0 && n >= pageLimit) finish()
    }

    /** Full-resolution capture; the page is kept with its detected corners (or opens in the corner editor). */
    fun capture() {
        if (capturing || shot != null) return
        capturing = true
        armed = false; shotSig = lastSig
        runCatching { sound?.play(android.media.MediaActionSound.SHUTTER_CLICK) }
        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        val f = app.documents.newTemp("cam_raw", "jpg")
        controller.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                scope.launch {
                    val s = withContext(Dispatchers.IO) { runCatching { openShot(f) }.getOrNull() }
                    capturing = false
                    when {
                        s == null -> { f.delete(); status = captureFailed }
                        reviewEach -> shot = s
                        else -> { s.preview.recycle(); addPage(f, s.quad) }
                    }
                }
            }
            // deep audit E25: a failed capture said nothing
            override fun onError(e: ImageCaptureException) { scope.launch { capturing = false; f.delete(); status = captureFailed } }
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
                val sig = signature(px, w, h)
                // sharpness on the analysis thread (it was on the main thread)
                val sharp = if (autoCapture && q != null && st >= 10) PageQuad.sharpness(px, w, h) > 6.0 else true
                scope.launch {
                    frameSize = IntSize(w, h); quad = q; steady = st; lastSig = sig
                    // re-arm once the page is gone or clearly different from the last photo
                    if (!armed && !capturing && shot == null && (q == null || shotSig == null || sigDiff(sig, shotSig!!) > 14)) armed = true
                    blurry = autoCapture && q != null && st >= 10 && !sharp
                    val now = System.currentTimeMillis()
                    if (autoCapture && armed && q != null && st >= 10 && sharp && now - lastAuto > 1500L && !capturing && shot == null) { lastAuto = now; capture() }
                }
            } finally { img.close() }
        }
    }

    BackHandler { if (qrFound != null) qrFound = null else if (shot != null) { shot?.file?.delete(); shot = null; armed = true } else if (capturing) Unit else if (pages.value.isNotEmpty()) finish() else onClose() }

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
            // X = done with the pages taken so far (they are kept), or close when there are none
            IconButton(onClick = { if (capturing) Unit else if (pages.value.isNotEmpty()) finish() else onClose() }) { Icon(Icons.Default.Close, stringResource(if (pages.value.isNotEmpty()) R.string.sc_cam_done else R.string.close), tint = Color.White) }
            Row {
                val on = stringResource(R.string.sc_state_on); val off = stringResource(R.string.sc_state_off)
                // scanner audit U8: every toggle says its state (not colour only); the flash names its mode
                val flashState = stringResource(when (flash) { ImageCapture.FLASH_MODE_ON -> R.string.sc_flash_on; ImageCapture.FLASH_MODE_OFF -> R.string.sc_flash_off; else -> R.string.sc_flash_auto })
                CamToggle(when (flash) { ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn; ImageCapture.FLASH_MODE_OFF -> Icons.Default.FlashOff; else -> Icons.Default.FlashAuto },
                    stringResource(R.string.sc_cam_flash) + ": " + flashState, flash != ImageCapture.FLASH_MODE_OFF, null) {
                    flash = when (flash) { ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF; else -> ImageCapture.FLASH_MODE_AUTO }
                }
                CamToggle(Icons.Default.FlashlightOn, stringResource(R.string.sc_cam_torch), torch, if (torch) on else off) { torch = !torch }
                CamToggle(Icons.Default.MotionPhotosAuto, stringResource(R.string.sc_cam_auto), autoCapture, if (autoCapture) on else off) { autoCapture = !autoCapture; prefs.edit().putBoolean("auto", autoCapture).apply() }
                CamToggle(Icons.Default.QrCodeScanner, stringResource(R.string.sc_cam_qr), qrMode, if (qrMode) on else off) { qrMode = !qrMode; qrFound = null }
                // less used settings in a menu, so the bar fits a 360 dp phone; each item names its state
                var more by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.sc_cam_more), tint = Color.White) }
                    DropdownMenu(more, { more = false }) {
                        @Composable fun item(label: Int, checked: Boolean, act: () -> Unit) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { act() },
                            leadingIcon = { Checkbox(checked, null) })
                        item(R.string.sc_cam_review_each, reviewEach) { reviewEach = !reviewEach; prefs.edit().putBoolean("review_each", reviewEach).apply() }
                        item(R.string.sc_cam_grid, grid) { grid = !grid; prefs.edit().putBoolean("grid", grid).apply() }
                        item(R.string.sc_cam_exposure, exposureOpen) { exposureOpen = !exposureOpen; more = false }
                    }
                }
            }
        }
        // hint
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(when {
                    capturing -> R.string.sc_cam_taking; qrMode -> R.string.sc_cam_hint_qr
                    autoCapture && !armed && pages.value.isNotEmpty() -> R.string.sc_cam_hint_next
                    quad == null -> R.string.sc_cam_hint_find; blurry -> R.string.sc_cam_hint_blurry
                    autoCapture && steady < 10 -> R.string.sc_cam_hint_hold; autoCapture -> R.string.sc_cam_hint_hold
                    else -> R.string.sc_cam_hint_ready
                }),
                Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = Color.White, style = MaterialTheme.typography.bodyMedium,
            )
            if (status.isNotEmpty()) Text(status, Modifier.padding(top = 6.dp).background(Color(0xCC1E6FD9), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }, color = Color.White, style = MaterialTheme.typography.labelLarge)
            // S4: what to photograph for this kind (ID card: front, then back …)
            kindGuide(kind, pages.value.size)?.let { g -> Text(stringResource(g), Modifier.padding(top = 6.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color(0xFFFFE082), style = MaterialTheme.typography.labelLarge) }
            if (pageLimit > 0) Text(stringResource(R.string.sc_cam_limit, pages.value.size, pageLimit), Modifier.padding(top = 6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        LaunchedEffect(status) { if (status.isNotEmpty()) { kotlinx.coroutines.delay(2200); status = "" } }
        // bottom: exposure, shutter, pages
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            // S4: the scan kinds right in the camera (they were chosen on the screen before)
            if (onKind != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScanKind.entries.forEach { k ->
                    val sel = k == kind
                    Text(k.label(), Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Color.White else Color.Black.copy(alpha = 0.45f))
                        .clickable(enabled = !capturing) { onKind(k) }.semantics { selected = sel }.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (sel) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (exposureOpen) {
                val st = controller.cameraInfo?.exposureState
                val lo = st?.exposureCompensationRange?.lower ?: 0; val hi = st?.exposureCompensationRange?.upper ?: 0
                val expLabel = stringResource(R.string.sc_cam_exposure)
                if (hi > lo) Slider(exposure.toFloat(), { v -> exposure = v.roundToInt(); controller.cameraControl?.setExposureCompensationIndex(exposure) },
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp).semantics { contentDescription = expLabel }, valueRange = lo.toFloat()..hi.toFloat(), steps = (hi - lo - 1).coerceAtLeast(0))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // gallery: the pages taken so far are kept, then photos are picked
                if (onGallery != null) FilledIconButton(onClick = { if (!capturing) onGallery(pages.value) }, Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))) {
                    Icon(Icons.Default.PhotoLibrary, stringResource(R.string.t5_from_gallery), tint = Color.White)
                } else Box(Modifier.size(56.dp))
                val shutterLabel = stringResource(R.string.sc_cam_shutter)
                FilledIconButton(onClick = { capture() }, Modifier.size(78.dp).semantics { contentDescription = shutterLabel }, enabled = !capturing, shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White)) {
                    if (capturing) CircularProgressIndicator(Modifier.size(32.dp)) else Box(Modifier.size(62.dp).background(Color.White, CircleShape).padding(3.dp).background(Color.Black, CircleShape).padding(2.dp).background(Color.White, CircleShape))
                }
                if (pages.value.isNotEmpty()) BadgedBox(badge = { Badge { Text(pages.value.size.toString()) } }) {
                    FilledIconButton(onClick = { finish() }, Modifier.size(56.dp), enabled = !capturing) { Icon(Icons.Default.Check, pluralStringResource(R.plurals.sc_cam_done_n, pages.value.size, pages.value.size)) }
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
                        val copied = stringResource(R.string.sc_copied)
                        TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(c.text)); status = copied }) { Text(stringResource(R.string.copy_text)) }
                        if (com.smartdocs.editor.core.tools5.BarcodeScan.isLink(c.text) && !c.text.trim().startsWith("WIFI:", true)) TextButton(onClick = {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(c.text.trim())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text(stringResource(R.string.sc_code_open)) }
                        TextButton(onClick = { qrFound = null }) { Text(stringResource(R.string.sc_cam_qr_again)) }
                    }
                }
            }
        }
    }

    shot?.let { s ->
        CornerEditor(s, leftLabel = R.string.sc_cam_retake, onRetake = { s.file.delete(); s.preview.recycle(); shot = null; armed = true }, onUse = { q ->
            // deep audit E6: crossed / collapsed corners are refused instead of making a garbled page
            val ok = PageQuad.normalize(q)
            if (ok == null) { status = badCorners; return@CornerEditor }
            shot = null; s.preview.recycle()
            addPage(s.file, ok)
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

/** The photo with four draggable corner handles; "use" straightens it, "full" takes the whole photo. */
@Composable
private fun CornerEditor(s: Shot, leftLabel: Int, onRetake: () -> Unit, onUse: (PageQuad.Quad) -> Unit) {
    val detected = remember(s) { s.quad }
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
                    val rad = (if (drag == i) 16.dp else 12.dp).toPx()
                    drawCircle(Color.White, rad, c); drawCircle(Color(0xFF2F8CFF), rad, c, style = Stroke(3.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRetake) { Icon(Icons.Default.Refresh, null, tint = Color.White); Spacer(Modifier.width(4.dp)); Text(stringResource(leftLabel), color = Color.White) }
            // whole photo ⇄ back to the detected edges (the full-photo choice could not be undone)
            val isFull = PageQuad.Quad(qx, qy).distance(PageQuad.Quad.FULL) < 0.002f
            OutlinedButton(onClick = {
                if (isFull) { qx = detected.x.copyOf(); qy = detected.y.copyOf() } else { qx = PageQuad.Quad.FULL.x.copyOf(); qy = PageQuad.Quad.FULL.y.copyOf() }
            }) { Icon(Icons.Default.CropFree, null); Spacer(Modifier.width(4.dp)); Text(stringResource(if (isFull) R.string.sc_cam_detected else R.string.sc_cam_full), color = Color.White) }
            Button(onClick = { onUse(PageQuad.Quad(qx, qy)) }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.sc_cam_use)) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** A camera toggle that says its name and state to TalkBack (state was shown by colour only). */
@Composable
private fun CamToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, state: String?, onClick: () -> Unit) {
    IconButton(onClick = onClick, Modifier.semantics { if (state != null) stateDescription = state }) {
        Icon(icon, label, tint = if (on) Color(0xFF4CD964) else Color.White)
    }
}

/** A 16×12 grey thumbnail of a frame: tells whether the page under the camera changed since the last photo. */
private fun signature(px: IntArray, w: Int, h: Int): IntArray = IntArray(16 * 12) { i ->
    val gx = i % 16; val gy = i / 16
    val x = (gx * w / 16 + w / 32).coerceIn(0, w - 1); val y = (gy * h / 12 + h / 24).coerceIn(0, h - 1)
    val c = px[y * w + x]; (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
}

private fun sigDiff(a: IntArray, b: IntArray): Int { var s = 0; for (i in a.indices) s += kotlin.math.abs(a[i] - b[i]); return s / a.size }

/** Scanner UX audit S2: change the corners of a page later (from the page editor). */
@Composable
internal fun ScanCornerEditor(file: File, initial: FloatArray?, onCancel: () -> Unit, onApply: (FloatArray?) -> Unit) {
    val shot by produceState<Shot?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { openShot(file) }.getOrNull() } }
    val bad = stringResource(R.string.sc_cam_bad_corners)
    var err by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val s = shot
        if (s == null) Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else {
            val start = remember(s) { initial?.takeIf { it.size == 8 }?.let { PageQuad.Quad(it.copyOfRange(0, 4), it.copyOfRange(4, 8)) } }
            val shown = remember(s) { Shot(s.file, s.preview, s.fullW, s.fullH, start ?: s.quad) }
            DisposableEffect(s) { onDispose { s.preview.recycle() } }
            Box {
                CornerEditor(shown, leftLabel = R.string.cancel, onRetake = onCancel, onUse = { q ->
                    if (q.distance(PageQuad.Quad.FULL) < 0.002f) onApply(null)
                    else PageQuad.normalize(q)?.let { onApply(it.x + it.y) } ?: run { err = true }
                })
                if (err) Text(bad, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp).background(Color(0xCCB00020), RoundedCornerShape(12.dp)).padding(10.dp), color = Color.White)
            }
        }
    }
}

/** S4: what to photograph next for [kind] after [taken] photos (null = the general hint is enough). */
private fun kindGuide(kind: ScanKind, taken: Int): Int? = when (kind) {
    ScanKind.ID_CARD, ScanKind.BUSINESS_CARD -> if (taken == 0) R.string.sc_guide_front else R.string.sc_guide_back
    ScanKind.BOOK -> R.string.sc_guide_book
    ScanKind.RECEIPT -> R.string.sc_guide_receipt
    ScanKind.WHITEBOARD -> R.string.sc_guide_board
    ScanKind.FORM -> R.string.sc_guide_form
    ScanKind.BATCH -> R.string.sc_guide_batch
    else -> null
}
