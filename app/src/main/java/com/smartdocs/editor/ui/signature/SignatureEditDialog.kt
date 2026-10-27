package com.smartdocs.editor.ui.signature

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
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
import com.smartdocs.editor.core.data.SavedSignature
import com.smartdocs.editor.core.data.SignatureMeta
import com.smartdocs.editor.core.data.SignatureMetaStore
import com.smartdocs.editor.core.fillsign2.SignatureImages
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.rememberImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manage Signatures, Edit (nitro-fillsign2): rename, recolour the ink, or replace the image (picked or pasted; white
 * background removed and cropped like the Image tab). The image file is rewritten in place, so the lock, the
 * recently-used time, the default size and the palette position stay; the caller opens it through the signature gate.
 */
@Composable
fun EditSignatureDialog(sig: SavedSignature, meta: SignatureMeta, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(meta.name) }
    var color by remember { mutableStateOf<Int?>(null) }
    var replaced by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val noImage = stringResource(R.string.nf2_clip_no_image)
    val original by produceState<Bitmap?>(null, sig.path, meta.rev) { value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(sig.path) }.getOrNull() } }

    fun process(f: File): Bitmap? = PdfOps.decodeScaled(f, 1600)?.let { SignatureImage.cropToContent(SignatureImage.removeWhiteBackground(SignatureImage.limit(it, 1600))) }
    val pick = rememberImagePicker(multiple = false) { files ->
        files.firstOrNull()?.let { f -> scope.launch { replaced = withContext(Dispatchers.Default) { process(f) }; error = if (replaced == null) noImage else null } }
    }
    fun paste() = scope.launch {
        val bmp = withContext(Dispatchers.IO) { clipboardImage(ctx, app.documents.newTemp("sig_clip", "img"))?.let { process(it) } }
        if (bmp == null) error = noImage else { replaced = bmp; error = null }
    }

    val preview by produceState<Bitmap?>(null, original, replaced, color) {
        val base = replaced ?: original
        val c = color
        value = if (base == null || c == null) base else withContext(Dispatchers.Default) { recolored(base, c) }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Edit, null) },
        title = { Text(stringResource(R.string.nf2_edit_signature)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(110.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp), contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nf2_sig_name)) },
                    placeholder = { Text(stringResource(if (sig.isInitials) R.string.sig_initials else R.string.sa_filter_signatures)) })
                Text(stringResource(R.string.nf2_recolor), style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) { penColors.forEach { c -> ColorDot(c, color == c) { color = if (color == c) null else c } } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = pick, Modifier.weight(1f)) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nf2_replace_image)) }
                    OutlinedButton(onClick = { paste() }) { Icon(Icons.Default.ContentPaste, stringResource(R.string.nf2_paste_clip)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val img = preview
                            if ((replaced != null || color != null) && img != null) {
                                // write next to the file, then swap, so a failure never leaves a half-written signature
                                val f = File(sig.path); val tmp = File(f.parentFile, f.name + ".edit.png")
                                SignatureImage.savePng(img, tmp)
                                if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
                                SignatureMetaStore.bumpRev(app, sig.path)
                            }
                            if (name.trim() != meta.name) SignatureMetaStore.setName(app, sig.path, name)
                        }.isSuccess
                    }
                    busy = false
                    if (ok) onDismiss() else error = ctx.getString(R.string.nf2_edit_failed)
                }
            }, enabled = !busy) { if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun recolored(src: Bitmap, rgb: Int): Bitmap {
    val w = src.width; val h = src.height
    val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(SignatureImages.recolor(px, rgb), 0, w, 0, 0, w, h) }
}
