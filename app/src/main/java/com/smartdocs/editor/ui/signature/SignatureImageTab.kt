package com.smartdocs.editor.ui.signature

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.fillsign2.SignatureImages
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Image / Camera tab: picks (or shoots) a picture, pastes one from the clipboard (nitro-fillsign2), or crops a PDF
 * page (nitro-quicksign), optionally removes the white paper background (threshold slider) and crops to the ink
 * bounds. [blackWhite] previews the black-and-white result. Reports the processed bitmap through [onBitmap] and, for
 * a picked or pasted image file, its natural width in points (pixels and DPI; "Keep original size") through [onNaturalWidth].
 */
@Composable
internal fun ImageTab(camera: Boolean, onBitmap: (Bitmap?) -> Unit, blackWhite: Boolean = false, onNaturalWidth: (Float?) -> Unit = {}) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var removeBg by remember { mutableStateOf(true) }
    var threshold by remember { mutableFloatStateOf(200f) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var pdfFile by remember { mutableStateOf<File?>(null) }
    // nitro-fillsign2: resolution of the picked / pasted file and its pixels per decoded pixel (null = no natural size)
    var sourceDpi by remember { mutableStateOf<Float?>(null) }
    var sourceScale by remember { mutableStateOf<Float?>(null) }
    var pasteError by remember { mutableStateOf<String?>(null) }
    val noImage = stringResource(R.string.nf2_clip_no_image)
    val pickPdf = rememberPdfPicker { files -> pdfFile = files.firstOrNull() }

    fun loadFile(f: File) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                val bmp = PdfOps.decodeScaled(f, 1600) ?: return@withContext null
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, o)
                val head = runCatching { f.inputStream().use { s -> ByteArray(65536).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } } }.getOrNull()
                Triple(bmp, head?.let { SignatureImages.dpi(it) }, if (o.outWidth > 0) o.outWidth.toFloat() / bmp.width.coerceAtLeast(1) else 1f)
            }
            if (r == null) { pasteError = noImage; return@launch }
            sourceDpi = r.second; sourceScale = r.third; source = r.first; pasteError = null
        }
    }

    val pickImage = rememberImagePicker(multiple = false) { files -> files.firstOrNull()?.let { loadFile(it) } }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraFile?.let { f -> sourceScale = null; source = PdfOps.decodeScaled(f, 1600) }
    }
    fun launchCamera() {
        val f = app.documents.newTemp("sig_cam", "jpg")
        cameraFile = f
        val uri: Uri = app.documents.shareUri(f)
        runCatching { takePicture.launch(uri) }
    }
    /** nitro-fillsign2: an image on the clipboard (content URI item, or text that is an image URI) goes the same way as a picked file. */
    fun paste() {
        scope.launch {
            val f = withContext(Dispatchers.IO) { clipboardImage(ctx, app.documents.newTemp("sig_clip", "img")) }
            if (f == null) pasteError = noImage else loadFile(f)
        }
    }

    val processed by produceState<Bitmap?>(null, source, removeBg, threshold.toInt(), blackWhite) {
        val src = source
        value = if (src == null) null else withContext(Dispatchers.Default) {
            val limited = SignatureImage.limit(src, 1600)
            val bg = if (removeBg) SignatureImage.removeWhiteBackground(limited, threshold.toInt()) else limited
            val cropped = SignatureImage.cropToContent(bg)
            val scale = sourceScale
            onNaturalWidth(if (scale == null) null else SignatureImages.widthPt((cropped.width * scale * src.width / limited.width.coerceAtLeast(1)).roundToInt(), sourceDpi))
            if (blackWhite) SignatureImage.toBlackAndWhite(cropped) else cropped
        }
        onBitmap(value)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(180.dp).background(Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            val p = processed
            if (p != null) Image(p.asImageBitmap(), null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            else Icon(if (camera) Icons.Default.PhotoCamera else Icons.Default.AddPhotoAlternate, null, Modifier.size(48.dp), tint = Color.LightGray)
        }
        Spacer(Modifier.height(8.dp))
        if (camera) {
            Button(onClick = { launchCamera() }, Modifier.fillMaxWidth()) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sa_take_photo)) }
        } else {
            Button(onClick = pickImage, Modifier.fillMaxWidth()) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sa_pick_image)) }
            OutlinedButton(onClick = { paste() }, Modifier.fillMaxWidth()) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nf2_paste_clip)) }
            OutlinedButton(onClick = pickPdf, Modifier.fillMaxWidth()) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nq_from_pdf)) }
            pasteError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(removeBg, { removeBg = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sig_remove_bg))
        }
        if (removeBg) LabeledSlider(stringResource(R.string.sa_threshold), threshold, 120f..250f, { threshold = it })
    }
    pdfFile?.let { f -> PdfPageCropDialog(f, onDismiss = { pdfFile = null }) { bmp -> sourceScale = null; source = bmp; pdfFile = null } }
}

/**
 * Copies the image on the clipboard into [out]: a clip item with an image URI, or text holding a content:// or
 * file:// URI of an image. Null when there is none or it cannot be read.
 */
internal fun clipboardImage(ctx: Context, out: File): File? {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
    for (i in 0 until clip.itemCount) {
        val item = clip.getItemAt(i)
        val uri = item.uri ?: item.text?.toString()?.trim()?.let { t -> Uri.parse(t).takeIf { it.scheme == "content" || it.scheme == "file" } } ?: continue
        val ok = runCatching {
            val type = ctx.contentResolver.getType(uri)
            if (type != null && !type.startsWith("image/")) return@runCatching false
            ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } != null &&
                BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(out.absolutePath, it) }.outWidth > 0
        }.getOrDefault(false)
        if (ok) return out
    }
    out.delete()
    return null
}
