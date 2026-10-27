package com.smartdocs.editor.ui.nve.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.convert6.Cv6Catalog
import com.smartdocs.editor.core.convert6.Cv6Engine
import com.smartdocs.editor.core.convert6.Cv6Opts
import com.smartdocs.editor.core.nve.shell.FeatureGuides
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ids of the Home quick actions added by the shell work (handled by [rememberNveHomeActions]). */
object NveHomeIds {
    const val PASTE = "nve_paste"
    const val SNAPSHOT = "nve_snapshot"
    const val GUIDES = "nve_guides"
    fun owns(id: String) = id.startsWith("nve_")
}

/** HOME-1: new PDF from the clipboard (text -> text PDF, image -> image PDF). Returns the new file or null. */
object NvePaste {
    suspend fun fromClipboard(ctx: Context, app: SmartDocsApp): File? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val desc = clip.description
        val uri = item.uri
        val mime = uri?.let { runCatching { ctx.contentResolver.getType(it) }.getOrNull() } ?: (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
        if (uri != null && (mime?.startsWith("image/") == true)) return withContext(Dispatchers.IO) {
            val tmp = app.documents.newTemp("paste", "img")
            ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return@withContext null
            val out = app.documents.uniqueFile(app.documents.docsDir, "Clipboard")
            PdfOps.imagesToPdf(listOf(tmp), out)
            tmp.delete()
            out
        }
        if (uri != null && mime == "application/pdf") return withContext(Dispatchers.IO) { runCatching { app.documents.localFile(uri) }.getOrNull() }
        val text = item.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val conv = Cv6Catalog.byId("clipboard_pdf") ?: return null
        return Cv6Engine(ctx, app.documents).run(conv, emptyList(), Cv6Opts(text = text), null) { _, _, _ -> }.files.firstOrNull()
    }
}

/** HOME-2: a whole-page snapshot (PNG) of a PDF page, saved to Pictures/SmartDocs and copied to the clipboard. */
object NveSnapshot {
    suspend fun render(ctx: Context, file: File, page: Int, password: String? = null, longSide: Int = 2400): Bitmap? = runCatching {
        val s = PdfSession.open(ctx, file, password)
        try {
            val p = page.coerceIn(0, s.pageCount - 1)
            val (w, h) = s.pageSize(p)
            val scale = longSide / maxOf(w, h)
            s.render(p, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), false, true)
        } finally { runCatching { s.closeAsync() } }
    }.getOrNull()

    /** Save [bmp] as PNG: returns the share uri (FileProvider, for the clipboard) and whether the gallery copy worked. */
    suspend fun saveAndCopy(ctx: Context, app: SmartDocsApp, bmp: Bitmap, name: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(app.documents.tempDir, "$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shareUri = app.documents.shareUri(f)
        withContext(Dispatchers.Main) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newUri(ctx.contentResolver, name, shareUri))
        }
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartDocs"); put(MediaStore.Images.Media.IS_PENDING, 1) }
            }
            val cr = ctx.contentResolver
            val uri: Uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            val ok = cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
            if (Build.VERSION.SDK_INT >= 29) cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            ok
        }.getOrDefault(false)
    }
}

/** Feature guides: write the sample PDF of [g] into the documents folder (Arabic text with the bundled font). */
object NveGuides {
    suspend fun create(ctx: Context, app: SmartDocsApp, g: FeatureGuides.Guide): File = withContext(Dispatchers.IO) {
        val out = File(app.documents.docsDir, g.fileName)
        FeatureGuides.write(g, out) { doc -> runCatching { PdfOps.arabicFont(ctx, doc) }.getOrNull() }
        runCatching { app.documents.touchRecent(out) }
        out
    }
}

/**
 * Handler of the shell's Home quick actions (paste, page snapshot, feature guides): returns true when [id] was ours.
 * Pickers and dialogs are composed here, so call it once in HomeScreen.
 */
@Composable
fun rememberNveHomeActions(nav: NavHostController): (String) -> Boolean {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var snapFile by remember { mutableStateOf<File?>(null) }
    var guides by remember { mutableStateOf(false) }
    val pick = rememberPdfPicker { files -> snapFile = files.firstOrNull() }
    snapFile?.let { f -> SnapshotDialog(f) { snapFile = null } }
    if (guides) GuidesDialog(onOpen = { g -> guides = false; scope.launch { val f = NveGuides.create(ctx, app, g); nav.navigate(Routes.reader(Uri.fromFile(f).toString())) } }) { guides = false }
    return remember(nav) {
        { id: String ->
            when (id) {
                NveHomeIds.PASTE -> {
                    scope.launch {
                        val f = runCatching { NvePaste.fromClipboard(ctx, app) }.getOrNull()
                        if (f != null) nav.navigate(Routes.reader(Uri.fromFile(f).toString()))
                        else Toast.makeText(ctx, ctx.getString(R.string.nl_paste_empty), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                NveHomeIds.SNAPSHOT -> { pick(); true }
                NveHomeIds.GUIDES -> { guides = true; true }
                else -> false
            }
        }
    }
}

@Composable
private fun SnapshotDialog(file: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("1") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.PhotoCamera, null) },
        title = { Text(stringResource(R.string.nl_snapshot)) },
        text = {
            Column {
                Text(file.name, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(page, { page = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.nl_page_number)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(stringResource(R.string.nl_snapshot_hint), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val p = (page.toIntOrNull() ?: 1) - 1
                    val bmp = NveSnapshot.render(ctx, file, p)
                    val ok = bmp != null && NveSnapshot.saveAndCopy(ctx, app, bmp, file.nameWithoutExtension + "_p" + (p + 1))
                    Toast.makeText(ctx, ctx.getString(if (ok) R.string.nl_snapshot_done else R.string.nl_snapshot_failed), Toast.LENGTH_SHORT).show()
                    busy = false; onDismiss()
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun GuidesDialog(onOpen: (FeatureGuides.Guide) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.School, null) }, title = { Text(stringResource(R.string.nl_guides)) },
        text = {
            Column {
                Text(stringResource(R.string.nl_guides_sub), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                listOf(FeatureGuides.Guide.EDIT to R.string.nl_guide_edit, FeatureGuides.Guide.FORM to R.string.nl_guide_form,
                    FeatureGuides.Guide.COMMENT to R.string.nl_guide_comment, FeatureGuides.Guide.ORGANIZE to R.string.nl_guide_organize).forEach { (g, l) ->
                    ListItem(headlineContent = { Text(stringResource(l)) }, leadingContent = { Icon(Icons.Default.PictureAsPdf, null) },
                        trailingContent = { TextButton(onClick = { onOpen(g) }) { Text(stringResource(R.string.nl_open)) } })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
