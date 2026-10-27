package com.smartdocs.editor.ui.nve.file

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the unified Save As produces. */
enum class SaveAsKind { COPY, OPTIMIZED, PDFA, FLATTENED, NO_ANNOTS, APP_FOLDER, IMAGES, WORD, TEXT, OTHER_FORMAT }

/**
 * NVE-file (FILE-10): one "Save as" sheet for the open document: a PDF copy anywhere, an optimized copy, a PDF/A-2b
 * copy, a flattened copy, a copy without comments, a renamed copy in the app folder, or another format (routed to
 * the conversion tools). [exportCopy] writes the document with its pending changes into a file (the reader's
 * state.exportWithAnnots). The open document itself is never changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NveSaveAsSheet(state: ReaderState, nav: NavHostController, exportCopy: suspend (File) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as SmartDocsApp
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(SaveAsKind.COPY) }
    var naming by remember { mutableStateOf(false) }
    val base = state.file?.nameWithoutExtension ?: "document"

    /** The document with pending changes, transformed for [kind], in a temp file. */
    suspend fun produce(kind: SaveAsKind): File = withContext(Dispatchers.IO) {
        val copy = app.documents.newTemp("saveas")
        exportCopy(copy)
        val pw = state.password
        when (kind) {
            SaveAsKind.OPTIMIZED -> app.documents.newTemp("saveas_opt").also { out ->
                com.smartdocs.editor.core.tools5.Optimizer.optimize(copy, pw, out, com.smartdocs.editor.core.tools5.OptimizeOptions()); copy.delete()
                if (!out.exists() || out.length() == 0L) error("optimize failed")
            }
            SaveAsKind.PDFA -> app.documents.newTemp("saveas_pdfa").also { out -> com.smartdocs.editor.core.tools5.PdfA.convert(ctx, copy, pw, out, title = state.file?.nameWithoutExtension); copy.delete() }
            SaveAsKind.FLATTENED -> app.documents.newTemp("saveas_flat").also { out -> com.smartdocs.editor.core.pdf.PdfOps.flattenAnnotations(copy, out, pw); copy.delete() }
            SaveAsKind.NO_ANNOTS -> app.documents.newTemp("saveas_clean").also { out ->
                com.smartdocs.editor.core.pdf.PdfOps.load(copy, pw).use { d ->
                    d.pages.forEach { p -> runCatching { p.annotations = p.annotations.filter { it.subtype == "Widget" || it.subtype == "Link" } } }
                    d.save(out)
                }
                copy.delete()
            }
            else -> copy
        }
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val kind = pending
        busy = true
        scope.launch {
            runCatching { val f = produce(kind); app.documents.exportTo(f, uri); f.delete() }
                .onSuccess { msg = ctx.getString(R.string.success) }.onFailure { msg = it.message ?: ctx.getString(R.string.error_generic) }
            busy = false
        }
    }

    fun suffix(k: SaveAsKind) = when (k) {
        SaveAsKind.OPTIMIZED -> "_optimized"; SaveAsKind.PDFA -> "_pdfa"; SaveAsKind.FLATTENED -> "_flattened"; SaveAsKind.NO_ANNOTS -> "_clean"; else -> ""
    }
    fun pdf(k: SaveAsKind) { pending = k; create.launch("$base${suffix(k)}.pdf") }
    fun route(id: String) {
        scope.launch {
            if (state.dirty && !state.save()) return@launch
            onDismiss(); nav.navigate(Routes.tool(id, state.fileUri))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.nf_saveas_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp)) }
            Item(Icons.Default.SaveAlt, R.string.nf_saveas_copy, R.string.nf_saveas_copy_sub, !busy) { pdf(SaveAsKind.COPY) }
            Item(Icons.Default.DriveFileRenameOutline, R.string.nf_saveas_app, R.string.nf_saveas_app_sub, !busy) { naming = true }
            Item(Icons.Default.Compress, R.string.nf_saveas_opt, R.string.nf_saveas_opt_sub, !busy) { pdf(SaveAsKind.OPTIMIZED) }
            Item(Icons.Default.Inventory, R.string.nf_saveas_pdfa, R.string.nf_saveas_pdfa_sub, !busy) { pdf(SaveAsKind.PDFA) }
            Item(Icons.Default.Layers, R.string.nf_saveas_flat, R.string.nf_saveas_flat_sub, !busy) { pdf(SaveAsKind.FLATTENED) }
            Item(Icons.Default.CommentsDisabled, R.string.nf_saveas_noannots, R.string.nf_saveas_noannots_sub, !busy) { pdf(SaveAsKind.NO_ANNOTS) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Item(Icons.Default.Description, R.string.nf_saveas_word, null, !busy) { route("convert_hub") }
            Item(Icons.Default.Collections, R.string.nf_saveas_images, null, !busy) { route("pdf_to_images") }
            Item(Icons.Default.Subject, R.string.nf_saveas_text, null, !busy) { route("pdf_to_text") }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("${base}_copy") }
        AlertDialog(onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.nf_saveas_app)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.nf_file_name)) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                naming = false; busy = true
                scope.launch {
                    runCatching {
                        val f = produce(SaveAsKind.COPY)
                        val dst = app.documents.uniqueFile(app.documents.docsDir, name.trim().removeSuffix(".pdf"))
                        withContext(Dispatchers.IO) { f.copyTo(dst, overwrite = false); f.delete() }
                        runCatching { app.documents.touchRecent(dst) }
                        dst
                    }.onSuccess { dst -> msg = ctx.getString(R.string.nf_saved_as, dst.name); onDismiss(); nav.navigate(Routes.reader(Uri.fromFile(dst).toString())) }
                        .onFailure { msg = it.message ?: ctx.getString(R.string.error_generic) }
                    busy = false
                }
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun Item(icon: ImageVector, title: Int, sub: Int?, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = sub?.let { { Text(stringResource(it)) } },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}
