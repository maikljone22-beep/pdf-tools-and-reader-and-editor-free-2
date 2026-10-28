package com.smartdocs.editor.ui.openall

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.ImageIo6
import com.smartdocs.editor.core.openall.FType
import com.smartdocs.editor.core.openall.FileSniffer
import com.smartdocs.editor.core.openall.SafeXml
import com.smartdocs.editor.core.openall.UnsafeXmlException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/** SVG through AndroidSVG after [SafeXml] (no entities, no external DTD); AndroidSVG runs no scripts and loads no files. */
object SvgRender {
    fun load(f: File): SVG {
        require(f.length() <= 30L * 1024 * 1024) { "SVG too large" }
        var b = f.readBytes()
        if (b.size > 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte()) b = GZIPInputStream(ByteArrayInputStream(b)).use { it.readNBytesCompat(60 shl 20) }
        return SVG.getFromInputStream(ByteArrayInputStream(SafeXml.sanitize(b)))
    }

    /** Natural size in CSS px (width/height, else the viewBox, else 512). */
    fun size(svg: SVG): Pair<Float, Float> {
        val vb = svg.documentViewBox
        var w = svg.documentWidth; var h = svg.documentHeight
        if (w <= 0f || h <= 0f) { w = vb?.width() ?: 512f; h = vb?.height() ?: 512f }
        return w.coerceAtLeast(1f) to h.coerceAtLeast(1f)
    }

    fun bitmap(f: File, maxDim: Int): Bitmap {
        val svg = load(f)
        val (w, h) = size(svg)
        val s = maxDim / maxOf(w, h)
        val bw = (w * s).toInt().coerceIn(1, maxDim); val bh = (h * s).toInt().coerceIn(1, maxDim)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, w, h)
        svg.renderToCanvas(android.graphics.Canvas(bmp), RenderOptions().viewPort(0f, 0f, bw.toFloat(), bh.toFloat()))
        return bmp
    }

    suspend fun toPdf(f: File, out: File): File {
        val svg = withContext(Dispatchers.IO) { load(f) }
        val (w, h) = size(svg)
        val pw = w * 0.75f; val ph = h * 0.75f
        if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, w, h)
        return canvasToPdf(out, listOf(pw to ph)) { c, _ -> svg.renderToCanvas(c, RenderOptions().viewPort(0f, 0f, pw, ph)) }
    }
}

/** Platform decoding for the viewer and thumbnails: EXIF-rotated, sampled to [maxDim]; SVG / TIFF / HEIF / AVIF / DNG. */
object ImageLoad {
    fun decode(ctx: Context, f: File, maxDim: Int): Bitmap? = runCatching {
        when (FileSniffer.of(f)) {
            FType.SVG -> SvgRender.bitmap(f, maxDim)
            else -> ImageIo6.decode(f, maxDim)
        }
    }.getOrNull()

    /** Animated GIF / WebP (Android 9+), or null. */
    fun animated(f: File): Drawable? {
        if (Build.VERSION.SDK_INT < 28) return null
        val head = FileSniffer.readHead(f, 64)
        val gif = head.size > 6 && String(head, 0, 3, Charsets.ISO_8859_1) == "GIF"
        val webp = head.size > 12 && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" && String(head, 12, 4, Charsets.ISO_8859_1) == "VP8X" && (head.getOrNull(20)?.toInt() ?: 0) and 0x02 != 0
        if (!gif && !webp) return null
        return runCatching {
            val d = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(f))
            if (d is android.graphics.drawable.AnimatedImageDrawable) d.also { it.start() } else null
        }.getOrNull()
    }
}

private fun isRaster(t: FType) = t == FType.IMAGE || t == FType.TIFF || t == FType.HEIF || t == FType.AVIF || t == FType.RAW

/** Pinch / double-tap zoom, pan; single-finger horizontal swipes call [onSwipe] (+1 next, -1 previous) when not zoomed. */
@Composable
fun ZoomBox(modifier: Modifier = Modifier, rotation: Float = 0f, resetKey: Any? = null, onSwipe: (Int) -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val swipe = rememberUpdatedState(onSwipe)
    Box(
        modifier
            .pointerInput(resetKey) {
                detectTapGestures(onDoubleTap = { if (scale > 1.1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
            }
            .pointerInput(resetKey, rtl) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f; var multi = false
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size > 1) multi = true
                        val z = ev.calculateZoom(); val p = ev.calculatePan()
                        if (multi || scale > 1.01f) {
                            scale = (scale * z).coerceIn(1f, 10f)
                            val maxX = size.width * (scale - 1) / 2; val maxY = size.height * (scale - 1) / 2
                            offset = Offset((offset.x + p.x).coerceIn(-maxX, maxX), (offset.y + p.y).coerceIn(-maxY, maxY))
                            ev.changes.forEach { it.consume() }
                        } else dx += p.x
                    } while (ev.changes.any { it.pressed })
                    if (!multi && scale <= 1.01f && kotlin.math.abs(dx) > size.width / 6f) swipe.value(if ((dx < 0) != rtl) 1 else -1)
                    if (scale <= 1.01f) { scale = 1f; offset = Offset.Zero }
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y; rotationZ = rotation },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Image viewer (open-all): JPEG/PNG/GIF/WebP (animated), BMP/ICO, HEIF, AVIF, DNG/RAW previews, multi-page TIFF and SVG;
 * zoom/pan, rotate, details/EXIF, slideshow through the folder, Convert to PDF, OCR, share, edit.
 */
@Composable
fun ImageViewer(nav: NavHostController, v: ViewFile) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val start = v.file!!
    // the folder's pictures for swiping / slideshow (not for files inside archives or the cache)
    val siblings = remember(start) {
        if (OpenAll.isTemporary(ctx, start)) listOf(start)
        else (start.parentFile?.listFiles()?.filter { it.isFile && FileSniffer.byExtension(it.name).isImage }?.sortedWith { a, b -> com.smartdocs.editor.core.openall.NaturalOrder.compare(a.name, b.name) } ?: listOf(start))
            .ifEmpty { listOf(start) }
    }
    var index by remember { mutableIntStateOf(siblings.indexOf(start).coerceAtLeast(0)) }
    val file = siblings.getOrElse(index) { start }
    val type = remember(file) { if (file == start) v.type else FileSniffer.of(file) }
    var rotation by remember(file) { mutableFloatStateOf(0f) }
    var page by remember(file) { mutableIntStateOf(0) }
    var details by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    val anim = remember(file) { ImageLoad.animated(file) }
    val pages by produceState<Result<List<Bitmap>>?>(null, file) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (anim != null) emptyList()
                else if (type == FType.TIFF) ImageIo6.decodeAll(file, 2048).take(300)
                else if (type == FType.SVG) listOf(SvgRender.bitmap(file, 2048))
                else listOf(ImageIo6.decode(file, 3072))
            }
        }
    }
    LaunchedEffect(playing, index) {
        if (!playing) return@LaunchedEffect
        delay(3000)
        if (siblings.size > 1) index = (index + 1) % siblings.size else playing = false
    }
    val cur = ViewFile(file, type, Uri.fromFile(file), file.name, v.depth)
    val actions = buildList {
        add(ViewerAction(R.string.oa_rotate, Icons.Default.RotateRight) { rotation = (rotation + 90f) % 360f })
        if (rotation != 0f && type != FType.SVG) add(ViewerAction(R.string.oa_rotate_save, Icons.Default.Save) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val src = pages?.getOrNull()?.getOrNull(page) ?: throw IllegalStateException("no image")
                        val m = Matrix().apply { postRotate(rotation) }
                        val r = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                        val png = file.extension.equals("png", true)
                        val out = app.documents.uniqueFile(app.documents.docsDir, file.nameWithoutExtension + "_rotated", if (png) "png" else "jpg")
                        out.outputStream().use { r.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
                        out
                    }.getOrNull()
                }
                Toast.makeText(ctx, if (saved != null) ctx.getString(R.string.oa_saved_as, saved.name) else ctx.getString(R.string.error_generic), Toast.LENGTH_LONG).show()
            }
        })
        add(ViewerAction(R.string.oa_image_info, Icons.Default.Info) { details = true })
        if (siblings.size > 1) add(ViewerAction(if (playing) R.string.oa_stop_slideshow else R.string.oa_slideshow, Icons.Default.Slideshow) { playing = !playing })
        if (isRaster(type)) add(ViewerAction(R.string.oa_ocr, Icons.Default.DocumentScanner) { nav.navigate(com.smartdocs.editor.ui.Routes.tool("ocr_plus", Uri.fromFile(file).toString())) })
        add(ViewerAction(R.string.oa_edit_other, Icons.Default.Edit) {
            val i = Intent(Intent.ACTION_EDIT).setDataAndType(app.documents.shareUri(file), FileSniffer.mimeOf(type, file.extension.lowercase()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { ctx.startActivity(Intent.createChooser(i, null)) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.o5_no_app), Toast.LENGTH_SHORT).show() }
        })
    }
    val bmps = pages?.getOrNull()
    val title = if (siblings.size > 1) "${file.name}  (${index + 1}/${siblings.size})" else file.name
    ViewerScaffold(nav, cur, title, actions = actions, convert = { out -> ViewerConvert.toPdf(ctx, cur, out) },
        bottomBar = {
            val n = bmps?.size ?: 0
            if (n > 1) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.oa_prev)) }
                    Text(stringResource(R.string.oa_page_of, page + 1, n), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { page = (page + 1).coerceAtMost(n - 1) }, enabled = page < n - 1) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.oa_next)) }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            val swipe: (Int) -> Unit = { d ->
                val n = bmps?.size ?: 0
                if (n > 1) page = (page + d).coerceIn(0, n - 1)
                else if (siblings.size > 1) index = (index + d + siblings.size) % siblings.size
            }
            when {
                anim != null -> ZoomBox(Modifier.fillMaxSize(), rotation, file, swipe) {
                    AndroidView(factory = { c -> ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER } }, update = { it.setImageDrawable(anim) }, modifier = Modifier.fillMaxSize())
                }
                pages == null -> CenteredProgress()
                bmps == null || bmps.isEmpty() -> {
                    val e = pages?.exceptionOrNull()
                    val why = when {
                        e is UnsafeXmlException -> ctx.getString(R.string.oa_svg_unsafe)
                        type == FType.HEIF && Build.VERSION.SDK_INT < 28 -> ctx.getString(R.string.oa_heic_needs)
                        type == FType.AVIF && Build.VERSION.SDK_INT < 31 -> ctx.getString(R.string.oa_avif_needs)
                        else -> ctx.getString(R.string.oa_image_unsupported, typeLabel(type, file.extension.lowercase()))
                    }
                    ErrorPane(why)
                }
                else -> ZoomBox(Modifier.fillMaxSize(), rotation, file to page, swipe) {
                    Image(bmps[page.coerceIn(0, bmps.size - 1)].asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }
    }
    if (details) ImageDetails(file, bmps?.getOrNull(page)) { details = false }
}

@Composable
private fun ImageDetails(f: File, bmp: Bitmap?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val rows = remember(f) {
        val out = ArrayList<Pair<String, String>>()
        out += ctx.getString(R.string.oa_info_name) to f.name
        out += ctx.getString(R.string.oa_info_size) to com.smartdocs.editor.core.files.DocumentRepository.formatSize(f.length())
        runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth > 0) out += ctx.getString(R.string.oa_px) to "${o.outWidth} × ${o.outHeight}" + (o.outMimeType?.let { "  ($it)" } ?: "")
        }
        runCatching {
            val ex = androidx.exifinterface.media.ExifInterface(f.absolutePath)
            listOf(
                ctx.getString(R.string.oa_exif_camera) to listOfNotNull(ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE), ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL)).joinToString(" "),
                ctx.getString(R.string.oa_exif_date) to ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL).orEmpty().ifEmpty { ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME).orEmpty() },
                ctx.getString(R.string.oa_exif_exposure) to ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME).orEmpty(),
                ctx.getString(R.string.oa_exif_fnumber) to ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER).orEmpty(),
                "ISO" to ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY).orEmpty(),
                ctx.getString(R.string.oa_exif_focal) to ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH).orEmpty(),
                ctx.getString(R.string.oa_exif_orientation) to ex.rotationDegrees.takeIf { it != 0 }?.let { "$it°" }.orEmpty(),
                ctx.getString(R.string.oa_exif_gps) to (ex.latLong?.let { "%.5f, %.5f".format(java.util.Locale.US, it[0], it[1]) } ?: ""),
                ctx.getString(R.string.oa_exif_software) to ex.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE).orEmpty(),
            ).filter { it.second.isNotBlank() }.forEach { out += it }
        }
        out.toList()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.oa_image_info)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { (k, value) -> Row(Modifier.padding(vertical = 2.dp)) { Text(k, Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge); Text(value) } }
                bmp?.let { Text(stringResource(R.string.oa_shown_size, it.width, it.height), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.oa_close)) } })
}
