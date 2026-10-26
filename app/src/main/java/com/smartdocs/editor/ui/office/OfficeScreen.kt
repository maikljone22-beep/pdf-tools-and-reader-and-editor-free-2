package com.smartdocs.editor.ui.office

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.office5.*
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.ResultSheet
import com.smartdocs.editor.ui.common.rememberWriteBack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Loaded {
    data object Loading : Loaded
    class Failed(val file: File?, val legacy: Boolean, val message: String?) : Loaded
    class Ready(val host: OfficeHost) : Loaded
}

/**
 * Entry point of the Office editors: `office5/{uri}` (file:// uri). Detects the kind (by name, then by
 * content), loads off the main thread and shows the Word / Excel / PowerPoint / text editor with the shared
 * top bar (undo, redo, save into the same file, save as, export to PDF, share, open with).
 */
@Composable
fun OfficeScreen(nav: NavHostController, uri: String) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    var state by remember(uri) { mutableStateOf<Loaded>(Loaded.Loading) }
    LaunchedEffect(uri) {
        state = withContext(Dispatchers.IO) {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            // content:// documents (Files tab tree scans, other apps) are copied with their real extension first
            val raw = if (parsed?.scheme == "content") runCatching { OfficeRoutes.importUri(ctx, app, parsed) }.getOrNull()
                else runCatching { parsed?.path?.let(::File) }.getOrNull()
            if (raw == null || !raw.exists()) return@withContext Loaded.Failed(raw, false, null)
            val f = OfficeRoutes.repairName(app, raw)
            try {
                val host: OfficeHost = when (OfficeRoutes.kindOf(f)) {
                    OfficeDocKind.WORD -> WordState(WordDoc.open(f), f)
                    OfficeDocKind.SHEET -> SheetState(XlsxIO5.load(f), f)
                    OfficeDocKind.CSV -> SheetState(CsvIO.load(f), f)
                    OfficeDocKind.SLIDES -> SlideState(PptDoc.open(f), f)
                    OfficeDocKind.TEXT -> TextState(f)
                    OfficeDocKind.IMPORT -> ImportState(f, PlainImport.read(f))
                    OfficeDocKind.LEGACY -> return@withContext Loaded.Failed(f, true, null)
                    OfficeDocKind.NONE -> return@withContext Loaded.Failed(f, false, null)
                }
                runCatching { app.documents.touchRecent(f) }
                Loaded.Ready(host)
            } catch (e: Throwable) {
                Loaded.Failed(f, false, e.message ?: e.toString())
            }
        }
    }
    when (val s = state) {
        Loaded.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Loaded.Failed -> FailedScreen(nav, s)
        is Loaded.Ready -> OfficeChrome(nav, s.host) { host -> state = Loaded.Ready(host) }
    }
}

@Composable
private fun FailedScreen(nav: NavHostController, s: Loaded.Failed) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(if (s.legacy) Icons.Default.Description else Icons.Default.ErrorOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(if (s.legacy) R.string.o5_legacy_title else R.string.o5_open_failed), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(if (s.legacy) R.string.o5_legacy_text else R.string.o5_open_failed_text), textAlign = TextAlign.Center)
        s.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        s.file?.let { f ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { OfficeActions.openWith(ctx, app, f) }) { Text(stringResource(R.string.o5_open_with)) }
                OutlinedButton(onClick = { OfficeActions.share(ctx, app, f) }) { Text(stringResource(R.string.o5_share)) }
            }
        }
        TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.o5_close)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfficeChrome(nav: NavHostController, host: OfficeHost, replaceHost: (OfficeHost) -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val writeBack = rememberWriteBack()
    var busy by remember { mutableStateOf<Int?>(null) }
    var askLeave by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var pdf by remember { mutableStateOf<File?>(null) }
    val tick = host.tick

    fun save(after: () -> Unit = {}) {
        host.commitPending()
        busy = R.string.o5_saving
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { host.saveTo(host.file) } }
            busy = null
            r.onSuccess {
                writeBack(host.file)
                com.smartdocs.editor.core.cloud.SaveHooks.onSaved(ctx, host.file)
                runCatching { app.documents.touchRecent(host.file) }
                Toast.makeText(ctx, ctx.getString(R.string.o5_saved), Toast.LENGTH_SHORT).show()
                after()
            }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.o5_save_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OfficeRoutes.mimeOf(host.ext))) { target ->
        if (target != null) {
            host.commitPending(); busy = R.string.o5_saving
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val tmp = app.documents.newTemp("o5save", host.ext)
                        host.saveTo(tmp); app.documents.exportTo(tmp, target); tmp.delete()
                    }
                }
                busy = null
                Toast.makeText(ctx, if (r.isSuccess) ctx.getString(R.string.o5_saved) else ctx.getString(R.string.o5_save_failed, r.exceptionOrNull()?.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun leave() { if (host.modified) askLeave = true else nav.popBackStack() }
    BackHandler { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(host.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.o5_back)) } },
                actions = {
                    IconButton(onClick = { host.undo() }, enabled = host.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.o5_undo)) }
                    IconButton(onClick = { host.redo() }, enabled = host.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.o5_redo)) }
                    IconButton(onClick = { save() }) { Icon(Icons.Default.Save, stringResource(R.string.o5_save), tint = if (host.modified) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.o5_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.o5_save_as)) }, { menu = false; saveAs.launch(host.file.nameWithoutExtension + "." + host.ext) }, leadingIcon = { Icon(Icons.Default.SaveAs, null) })
                            if (host is SheetState && host.wb.kind == Workbook5.Kind.CSV) DropdownMenuItem({ Text(stringResource(R.string.o5_save_as_xlsx)) }, {
                                menu = false
                                scope.launch {
                                    val f = withContext(Dispatchers.IO) { runCatching { val x = CsvIO.toXlsx(host.wb, host.sheet.rtl); val out = app.documents.uniqueFile(app.documents.docsDir, host.file.nameWithoutExtension, "xlsx"); XlsxIO5.save(x, out); out }.getOrNull() }
                                    if (f != null) { OfficeRoutes.open(nav, f) }
                                }
                            }, leadingIcon = { Icon(Icons.Default.TableChart, null) })
                            DropdownMenuItem({ Text(stringResource(R.string.o5_export_pdf)) }, {
                                menu = false; host.commitPending(); busy = R.string.o5_exporting
                                scope.launch {
                                    val r = runCatching { OfficeActions.exportPdf(ctx, app, host) }
                                    busy = null
                                    r.onSuccess { pdf = it }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.o5_export_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
                                }
                            }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) })
                            DropdownMenuItem({ Text(stringResource(R.string.o5_share)) }, {
                                menu = false; host.commitPending()
                                scope.launch { val f = withContext(Dispatchers.IO) { runCatching { OfficeActions.currentCopy(app, host) }.getOrNull() }; if (f != null) OfficeActions.share(ctx, app, f) }
                            }, leadingIcon = { Icon(Icons.Default.Share, null) })
                            DropdownMenuItem({ Text(stringResource(R.string.o5_open_with)) }, {
                                menu = false; host.commitPending()
                                scope.launch { val f = withContext(Dispatchers.IO) { runCatching { OfficeActions.currentCopy(app, host) }.getOrNull() }; if (f != null) OfficeActions.openWith(ctx, app, f) }
                            }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) })
                            if (host is ImportState) DropdownMenuItem({ Text(stringResource(R.string.o5_edit_as_docx)) }, {
                                menu = false
                                scope.launch {
                                    val f = withContext(Dispatchers.IO) { runCatching { host.toDocx(app.documents.uniqueFile(app.documents.docsDir, host.file.nameWithoutExtension, "docx")) }.getOrNull() }
                                    if (f != null) OfficeRoutes.open(nav, f)
                                }
                            }, leadingIcon = { Icon(Icons.Default.EditNote, null) })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (host) {
                is WordState -> WordEditor(host)
                is SheetState -> SheetEditor(host)
                is SlideState -> SlideEditor(host)
                is TextState -> TextEditor(host)
                is ImportState -> ImportView(host)
            }
        }
    }

    busy?.let { ProgressDialog(stringResource(it)) }
    pdf?.let { ResultSheet(it, nav, onDismiss = { pdf = null }) }
    if (askLeave) UnsavedDialog(
        onSave = { askLeave = false; save { nav.popBackStack() } },
        onDiscard = { askLeave = false; nav.popBackStack() },
        onDismiss = { askLeave = false },
    )
}

/** Save-free actions shared by the editors. */
object OfficeActions {
    /** The current state written to a temp file with the document's real name (for share / open with / PDF). */
    fun currentCopy(app: com.smartdocs.editor.SmartDocsApp, host: OfficeHost): File {
        val dir = File(app.documents.tempDir, "o5share_${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, host.file.nameWithoutExtension + "." + host.ext)
        if (host.modified) host.saveTo(f) else host.file.copyTo(f, overwrite = true)
        return f
    }

    fun share(ctx: android.content.Context, app: com.smartdocs.editor.SmartDocsApp, f: File) {
        runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f), OfficeRoutes.mimeOf(f.extension)), null)) }
    }

    fun openWith(ctx: android.content.Context, app: com.smartdocs.editor.SmartDocsApp, f: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(app.documents.shareUri(f), OfficeRoutes.mimeOf(f.extension)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, null)) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.o5_no_app), Toast.LENGTH_SHORT).show() }
    }

    /** Converts the current state to a PDF in the documents folder through the existing any-format pipeline. */
    suspend fun exportPdf(ctx: android.content.Context, app: com.smartdocs.editor.SmartDocsApp, host: OfficeHost): File {
        val src = withContext(Dispatchers.IO) {
            val dir = File(app.documents.tempDir, "o5pdf_${System.nanoTime()}").apply { mkdirs() }
            // text-like documents export through their editable form
            val ext = if (host is ImportState) "docx" else host.ext
            File(dir, host.file.nameWithoutExtension + "." + ext).also { host.saveTo(it) }
        }
        val out = app.documents.uniqueFile(app.documents.docsDir, host.file.nameWithoutExtension)
        com.smartdocs.editor.core.tools5.ToPdf.convert(ctx, src, out)
        return out
    }
}
