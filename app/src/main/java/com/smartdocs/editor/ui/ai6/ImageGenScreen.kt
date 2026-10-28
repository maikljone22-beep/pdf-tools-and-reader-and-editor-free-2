package com.smartdocs.editor.ui.ai6

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai6.AiMedia
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.ResultSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pure placement maths: an image of [iw]×[ih] fitted into a [frac] share of a [pw]×[ph] page, centred (PDF points, origin bottom-left). */
object ImagePlacement {
    fun centred(iw: Int, ih: Int, pw: Float, ph: Float, frac: Float = 0.6f): FloatArray {
        val maxW = pw * frac; val maxH = ph * frac
        val s = minOf(maxW / iw.coerceAtLeast(1), maxH / ih.coerceAtLeast(1))
        val w = iw * s; val h = ih * s
        return floatArrayOf((pw - w) / 2f, (ph - h) / 2f, w, h)
    }
}

/**
 * ai6 (AI-8): route "ai_image". Prompt + size → [AiMedia.generateImage] → preview; save to the documents folder
 * and the gallery, share, make a one-page PDF, or (with a document [uri]) place it centred on a chosen page of a
 * copy of the document — the result sheet can then replace the original (the old version goes to the trash).
 * aor: a generated or picked image can be edited by a prompt ([AiMedia.editImage], `/images/edits`; each edit can be
 * undone here), and "insert on the page" hands it to the reader ([AiImageInsert]) as one undoable content edit.
 */
@Composable
fun ImageGenScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val doc = remember(uri) { uri?.takeIf { it.isNotBlank() }?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull() }?.takeIf { it.exists() && it.extension.equals("pdf", true) } }
    var prompt by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("1024x1024") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<File?>(null) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var pageText by remember { mutableStateOf("1") }
    var result by remember { mutableStateOf<File?>(null) }
    var other by remember { mutableStateOf<File?>(null) }
    // aor: prompt edits; [history] holds the versions before each edit (undo)
    var editPrompt by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<Pair<File, Bitmap>>() }
    fun show(f: File, b: Bitmap, keepOld: Boolean) {
        val old = image; val oldBmp = bmp
        if (keepOld && old != null && oldBmp != null) history.add(old to oldBmp) else history.clear()
        image = f; bmp = b
    }
    val pickToEdit = com.smartdocs.editor.ui.common.rememberImagePicker(multiple = false) { picked ->
        val src = picked.firstOrNull() ?: return@rememberImagePicker
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val b = PdfOps.decodeScaled(src, 1536) ?: throw IllegalStateException("image")
                    val out = app.documents.uniqueFile(app.documents.docsDir, "ai_image", "png")
                    out.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    out to b
                }
            }
            r.onSuccess { (f, b) -> show(f, b, keepOld = false) }.onFailure { error = ctx.getString(R.string.aor_img_unreadable) }
        }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.ai6t_image_gen), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!settings.aiEnabled) { Text(stringResource(R.string.ai6_off)); return@Column }
            OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.ai6t_image_prompt)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1024x1024" to R.string.ai6t_square, "1536x1024" to R.string.ai6t_landscape, "1024x1536" to R.string.ai6t_portrait).forEach { (v, l) ->
                    FilterChip(size == v, { size = v }, label = { Text(stringResource(l)) })
                }
            }
            Button(enabled = !busy && prompt.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val bytes = AiMedia.generateImage(client, prompt.trim(), size)
                        var generated: Bitmap? = null
                        val f = withContext(Dispatchers.IO) {
                            app.documents.uniqueFile(app.documents.docsDir, "ai_image", "png").also { out ->
                                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("image")
                                out.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                generated = b
                            }
                        }
                        generated?.let { b -> show(f, b, keepOld = false) } ?: run { image = f }
                    } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = tError(ctx, e) } finally { busy = false }
                }
            }) { Text(stringResource(R.string.ai6t_generate)) }
            OutlinedButton(enabled = !busy, onClick = pickToEdit) { Text(stringResource(R.string.aor_edit_pick)) }   // aor
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            bmp?.let { b -> Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit, alignment = Alignment.Center) }
            image?.let { f ->
                // aor (AI-8): prompt-based edit of the shown image
                OutlinedTextField(editPrompt, { editPrompt = it }, label = { Text(stringResource(R.string.aor_edit_prompt)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(enabled = !busy && editPrompt.isNotBlank(), onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val src = withContext(Dispatchers.IO) { f.readBytes() }
                                val bytes = AiMedia.editImage(client, src, editPrompt.trim(), size)
                                val (nf, nb) = withContext(Dispatchers.IO) {
                                    val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("image")
                                    val out = app.documents.uniqueFile(app.documents.docsDir, "ai_image_edit", "png")
                                    out.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    out to b
                                }
                                show(nf, nb, keepOld = true)
                            } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = tError(ctx, e) } finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.aor_edit_apply)) }
                    if (history.isNotEmpty()) OutlinedButton(enabled = !busy, onClick = {
                        val (pf, pb) = history.removeAt(history.lastIndex)
                        image = pf; bmp = pb
                    }) { Text(stringResource(R.string.aor_edit_undo)) }
                }
                TFileActions(f)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { saveToGallery(ctx, f) }.isSuccess }
                            Toast.makeText(ctx, ctx.getString(if (ok) R.string.ai6t_saved_gallery else R.string.ai6t_failed_save), Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.ai6t_save_gallery)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching {
                                val out = app.documents.uniqueFile(app.documents.docsDir, "ai_image", "pdf")
                                PdfOps.imagesToPdf(listOf(f), out); out
                            }.onSuccess { result = it }.onFailure { error = it.message }
                        }
                    }) { Text(stringResource(R.string.ai6t_as_pdf)) }
                }
                if (doc != null) {
                    HorizontalDivider()
                    Text(stringResource(R.string.ai6t_insert_into, doc.name), style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(pageText, { pageText = it.filter { c -> c.isDigit() }.take(5) }, label = { Text(stringResource(R.string.ai6t_page)) }, singleLine = true, modifier = Modifier.width(110.dp))
                        Button(enabled = bmp != null, onClick = {
                            val b = bmp ?: return@Button
                            scope.launch {
                                runCatching {
                                    val s = PdfSession.open(ctx, doc)
                                    val p = ((pageText.toIntOrNull() ?: 1) - 1).coerceIn(0, (s.pageCount - 1).coerceAtLeast(0))
                                    val (pw, ph) = try { s.pageSize(p) } finally { s.closeAsync() }
                                    val r = ImagePlacement.centred(b.width, b.height, pw, ph)
                                    val out = app.documents.uniqueFile(app.documents.docsDir, doc.nameWithoutExtension + "_image", "pdf")
                                    PdfOps.stampImage(doc, p, b, r[0], r[1], r[2], r[3], out)
                                    com.smartdocs.editor.core.pdf.ToolOutputs.record(out, doc)   // audit 5 E-1: the document with the image
                                }.onSuccess { result = it }.onFailure { error = it.message }
                            }
                        }) { Text(stringResource(R.string.ai6t_insert)) }
                    }
                    // aor (AI-8): place it on the page in the reader as one undoable step (no copy)
                    Button(enabled = !busy, onClick = {
                        val p = ((pageText.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
                        AiImageInsert.pending = AiImageInsert.Request(doc.absolutePath, p, f.absolutePath)
                        if (!nav.popBackStack(com.smartdocs.editor.ui.Routes.READER, inclusive = false))
                            nav.navigate(com.smartdocs.editor.ui.Routes.reader(Uri.fromFile(doc).toString(), p))
                    }) { Text(stringResource(R.string.aor_insert_page)) }
                }
            }
            other?.let { TFileActions(it) }
            Text(stringResource(R.string.ai6t_image_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    result?.let { f -> androidx.compose.runtime.CompositionLocalProvider(com.smartdocs.editor.ui.common.LocalToolSource provides doc) { ResultSheet(f, nav, onDismiss = { result = null }) } }
}

/** Copy a PNG into Pictures/SmartDocs (MediaStore on API 29+, the public folder before). */
fun saveToGallery(ctx: android.content.Context, f: File) {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartDocs")
        }
        val u = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("insert")
        ctx.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } } ?: throw IllegalStateException("open")
    } else {
        @Suppress("DEPRECATION") val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "SmartDocs").apply { mkdirs() }
        f.copyTo(File(dir, f.name), overwrite = true)
    }
}
