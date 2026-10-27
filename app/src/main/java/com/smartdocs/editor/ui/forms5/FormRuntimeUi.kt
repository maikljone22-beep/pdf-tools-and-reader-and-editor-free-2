package com.smartdocs.editor.ui.forms5

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms5.FormPrefs
import com.smartdocs.editor.core.forms5.FormSubmit
import com.smartdocs.editor.core.forms5.PdfAction
import com.smartdocs.editor.core.forms5.PdfActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Display name of a picked document (falls back to the last path segment). */
internal fun displayName(ctx: android.content.Context, u: Uri): String =
    runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: u.lastPathSegment?.substringAfterLast('/') ?: "file"

/** Copy a picked document into the app's temp folder (keeps its name, so the data format can be detected). */
internal suspend fun copyPicked(ctx: android.content.Context, u: Uri, dir: File): File? = withContext(Dispatchers.IO) {
    runCatching {
        val f = File(dir, "pick_" + System.currentTimeMillis() + "_" + displayName(ctx, u).replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        ctx.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: return@runCatching null
        f
    }.getOrNull()
}

/**
 * Dialogs of the form runtime: consent for action lists (submit, links, reset, import, other files), the
 * submit response viewer, data-file and file-select pickers, and the signing offer of a signature field.
 */
@Composable
internal fun FormRuntimeDialogs(fs: FormFillState, onSign: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- consent for an action list
    val pc = fs.pendingChain
    if (pc != null) {
        val (w, trig, list) = pc
        val first = PdfActions.firstConfirm(list)
        AlertDialog(
            onDismissRequest = { fs.pendingChain = null },
            title = { Text(w?.label ?: stringResource(R.string.f8_action_title)) },
            text = {
                Text(when (first) {
                    is PdfAction.Submit -> if (FormSubmit.isMail(first.submit.url)) stringResource(R.string.f5_submit_mail_q, first.submit.url.removePrefix("mailto:").substringBefore('?'))
                        else stringResource(R.string.f5_submit_q, FormSubmit.host(first.submit.url))
                    is PdfAction.Uri -> stringResource(R.string.f5_open_uri_q, first.uri)
                    is PdfAction.Reset -> stringResource(R.string.f5_reset_q)
                    is PdfAction.ImportData -> stringResource(R.string.f8_import_q, first.file.ifBlank { "?" })
                    is PdfAction.GoToR -> stringResource(R.string.f8_open_file_q, first.file)
                    is PdfAction.Launch -> stringResource(R.string.f8_open_file_q, first.file)
                    else -> stringResource(R.string.f8_run_actions_q, list.size)
                })
            },
            confirmButton = { TextButton(onClick = { fs.pendingChain = null; scope.launch { FormActions.runChain(fs, w, trig, list) } }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { fs.pendingChain = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // ---- submit response (Acrobat opens an HTML answer in a browser window)
    val resp = fs.response
    if (resp != null) {
        val (mime, body) = resp
        AlertDialog(
            onDismissRequest = { fs.response = null },
            title = { Text(stringResource(R.string.f8_response_title)) },
            text = {
                if (mime.contains("html", true)) {
                    AndroidView(factory = { c ->
                        android.webkit.WebView(c).apply {
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            loadDataWithBaseURL(null, body, "text/html", "utf-8", null)
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 460.dp))
                } else SelectionContainer {
                    Text(body, Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { fs.response = null }) { Text(stringResource(R.string.ok)) } },
        )
    }

    // ---- data file for ImportData / importAnFDF
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        fs.importPick = false
        if (u != null) scope.launch {
            val f = copyPicked(ctx, u, fs.reader.app.documents.tempDir) ?: return@launch
            FormActions.importFile(fs, f)
        }
    }
    LaunchedEffect(fs.importPick) { if (fs.importPick) runCatching { importer.launch(arrayOf("*/*")) }.onFailure { fs.importPick = false } }

    // ---- file-select text field: the value is the picked file's name
    var fileFor by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        val n = fileFor; fileFor = null
        if (u != null && n != null) fs.setValue(n, displayName(ctx, u))
    }
    LaunchedEffect(fs.filePickFor) {
        val n = fs.filePickFor ?: return@LaunchedEffect
        fs.filePickFor = null; fileFor = n
        runCatching { filePicker.launch(arrayOf("*/*")) }
    }

    // ---- signature field: open the signing flow for this document
    val sig = fs.signRequest
    if (sig != null) {
        AlertDialog(
            onDismissRequest = { fs.signRequest = null },
            title = { Text(stringResource(R.string.f8_sign_title)) },
            text = { Text(stringResource(R.string.f8_sign_body, sig.substringAfterLast('.'))) },
            confirmButton = {
                TextButton(onClick = {
                    fs.signRequest = null
                    scope.launch { if (fs.reader.dirty) fs.reader.save(); fs.reader.file?.let(onSign) }
                }) { Text(stringResource(R.string.f8_sign_go)) }
            },
            dismissButton = { TextButton(onClick = { fs.signRequest = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Strip above the reader toolbar when no field is active: "this document has fillable fields" (collapse, don't
 * show again) and, with automatic calculation off, "Calculate now".
 */
@Composable
internal fun FormStatusStrip(fs: FormFillState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { FormPrefs.load(ctx) }
    val m = fs.model
    if (m.isEmpty) return
    var closed by rememberSaveable(fs.reader.file?.path) { mutableStateOf(false) }
    val showBanner = FormPrefs.fillableBanner && !closed && m.widgets.any { !it.readOnly && it.kind != com.smartdocs.editor.core.forms5.WKind.BUTTON }
    val showCalc = !FormPrefs.autoCalculate && (m.calc.hasCalculations || fs.scripts?.hasCalculations == true)
    if (!showBanner && !showCalc) return
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showBanner) {
                Icon(Icons.Default.EditNote, null, Modifier.size(20.dp))
                Text(stringResource(R.string.f8_fillable_banner, m.byName.size), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { FormPrefs.setFillableBanner(ctx, false) }) { Text(stringResource(R.string.f8_dont_show)) }
            } else Text(stringResource(R.string.f8_calc_off), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (showCalc) FilledTonalButton(onClick = { fs.calculateNow(); fs.message = FormMessage.Calculated }) {
                Icon(Icons.Default.Calculate, null, Modifier.size(16.dp)); Text(stringResource(R.string.f8_calc_now))
            }
            if (showBanner) IconButton(onClick = { closed = true }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
        }
    }
}
