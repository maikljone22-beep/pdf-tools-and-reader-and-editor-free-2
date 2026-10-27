package com.smartdocs.editor.ui.signature

import android.graphics.Bitmap
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Image / Camera tab: picks (or shoots) a picture, or crops a PDF page (nitro-quicksign), optionally removes the white
 * paper background (threshold slider) and crops to the ink bounds. [blackWhite] previews the black-and-white result.
 * Reports the processed bitmap through [onBitmap].
 */
@Composable
internal fun ImageTab(camera: Boolean, onBitmap: (Bitmap?) -> Unit, blackWhite: Boolean = false) {
    val app = LocalApp.current
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var removeBg by remember { mutableStateOf(true) }
    var threshold by remember { mutableFloatStateOf(200f) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var pdfFile by remember { mutableStateOf<File?>(null) }
    val pickPdf = rememberPdfPicker { files -> pdfFile = files.firstOrNull() }

    val pickImage = rememberImagePicker(multiple = false) { files ->
        files.firstOrNull()?.let { f -> source = PdfOps.decodeScaled(f, 1600) }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraFile?.let { f -> source = PdfOps.decodeScaled(f, 1600) }
    }
    fun launchCamera() {
        val f = app.documents.newTemp("sig_cam", "jpg")
        cameraFile = f
        val uri: Uri = app.documents.shareUri(f)
        runCatching { takePicture.launch(uri) }
    }

    val processed by produceState<Bitmap?>(null, source, removeBg, threshold.toInt(), blackWhite) {
        val src = source
        value = if (src == null) null else withContext(Dispatchers.Default) {
            val limited = SignatureImage.limit(src, 1600)
            val bg = if (removeBg) SignatureImage.removeWhiteBackground(limited, threshold.toInt()) else limited
            val cropped = SignatureImage.cropToContent(bg)
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
            OutlinedButton(onClick = pickPdf, Modifier.fillMaxWidth()) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nq_from_pdf)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(removeBg, { removeBg = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sig_remove_bg))
        }
        if (removeBg) LabeledSlider(stringResource(R.string.sa_threshold), threshold, 120f..250f, { threshold = it })
    }
    pdfFile?.let { f -> PdfPageCropDialog(f, onDismiss = { pdfFile = null }) { bmp -> source = bmp; pdfFile = null } }
}
