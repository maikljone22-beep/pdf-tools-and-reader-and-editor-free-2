package com.smartdocs.editor.ui.scanner

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Document scanner (ML Kit) with page list editing, output options and a list of earlier scans. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(nav: NavHostController) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var pages by remember { mutableStateOf<List<File>>(emptyList()) }
    var paper by remember { mutableStateOf(PaperSize.A4) }
    var mode by remember { mutableStateOf(ColorMode.COLOR) }
    var quality by remember { mutableFloatStateOf(85f) }
    var scannerError by remember { mutableStateOf<String?>(null) }
    var previous by remember { mutableStateOf<List<File>>(emptyList()) }
    var result by remember { mutableStateOf<File?>(null) }
    var lastScan by remember { mutableStateOf<File?>(null) }   // ai6 (B16): offer AI on the saved scan
    var ocrAfter by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf(false) }
    val runner = rememberToolRunner()
    val snack = remember { SnackbarHostState() }

    fun refreshPrevious() { previous = (app.documents.scansDir.listFiles() ?: emptyArray()).filter { it.extension.equals("pdf", true) }.sortedByDescending { it.lastModified() } }
    LaunchedEffect(Unit) { refreshPrevious() }

    /** Copy scanner page URIs into our temp dir so they survive the scanner's cleanup. */
    fun importUris(uris: List<Uri>) {
        scope.launch(Dispatchers.IO) {
            val files = uris.mapIndexedNotNull { i, u ->
                runCatching {
                    val f = app.documents.newTemp("scan_${i}", "jpg")
                    ctx.contentResolver.openInputStream(u)?.use { s -> f.outputStream().use { s.copyTo(it) } }; f
                }.getOrNull()
            }
            withContext(Dispatchers.Main) { pages = pages + files }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val r = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val uris = r?.pages?.map { it.imageUri } ?: emptyList()
            if (uris.isNotEmpty()) importUris(uris)
        }
    }
    val unavailableMsg = stringResource(R.string.pg_scan_unavailable)
    fun launchScanner() {
        val act = ctx.activity() ?: run { scannerError = unavailableMsg; return }
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(50)
                // the app builds the PDF itself (paper size, colour mode, quality): ML Kit's own PDF is not asked for (audit A-L10)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(act)
                .addOnSuccessListener { sender -> runCatching { scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }.onFailure { scannerError = unavailableMsg } }
                .addOnFailureListener { scannerError = unavailableMsg + "\n" + (it.message ?: "") }
        } catch (e: Exception) { scannerError = unavailableMsg }
    }
    val pickGallery = rememberImagePicker { files -> pages = pages + files }

    val savingMsg = stringResource(R.string.pg_scan_saving)
    fun savePdf(name: String) {
        val list = pages.toList()
        if (list.isEmpty()) return
        runner.run(savingMsg) {
            val out = app.documents.uniqueFile(app.documents.scansDir, name.ifBlank { "Scan_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) })
            try { ScanPdfBuilder.build(list, out, paper, mode, quality.toInt()) } catch (e: Throwable) { out.delete(); throw e }
            runCatching { app.documents.touchRecent(out, list.size) }
            // the scanner's page copies in the temp folder are not needed any more (convert audit A-M14 / A-L10)
            val tmp = app.documents.tempDir.canonicalPath
            list.filter { it.name.startsWith("scan_") && runCatching { it.canonicalPath.startsWith(tmp) }.getOrDefault(false) }.forEach { it.delete() }
            withContext(Dispatchers.Main) { pages = emptyList() }
            out
        }
    }
    LaunchedEffect(runner.result) {
        runner.result?.let { f ->
            refreshPrevious()
            if (ocrAfter) { ocrAfter = false; runner.result = null; nav.navigate(Routes.tool("ocr", Uri.fromFile(f).toString())) }
            else { result = f; lastScan = f; runner.result = null }
        }
    }
    LaunchedEffect(runner.error) { runner.error?.let { snack.showSnackbar(it); runner.error = null } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = { lastScan?.takeIf { it.exists() }?.let { f -> ScanAiBar(f, nav) { lastScan = null } } },   // ai6 (B16)
        topBar = { AppTopBar(stringResource(R.string.pg_scan_title), onBack = { nav.popBackStack() }) {
            // tools5: scan studio (ID card, business card, receipt, book, batch, per-page filters, auto OCR)
            IconButton(onClick = { nav.navigate(Routes.tool("scan_studio")) }) { Icon(Icons.Default.AutoAwesome, stringResource(R.string.t5_scan_title)) }
            if (pages.isNotEmpty()) IconButton(onClick = { pages = emptyList() }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.pg_remove)) }
        } },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { launchScanner() }, icon = { Icon(Icons.Default.DocumentScanner, null) },
                text = { Text(stringResource(if (pages.isEmpty()) R.string.scan_start else R.string.pg_scan_add_more)) })
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val tablet = maxWidth > 700.dp
            if (tablet && pages.isNotEmpty()) Row(Modifier.fillMaxSize()) {
                PageList(pages, mode, onChange = { pages = it }, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(340.dp).fillMaxHeight().padding(16.dp)) {
                    com.smartdocs.editor.ui.nve.shell.NveScanPresetsRow(paper.name, mode.name, quality, ocrAfter) { p -> runCatching { paper = PaperSize.valueOf(p.paper); mode = ColorMode.valueOf(p.mode) }; quality = p.quality.toFloat(); ocrAfter = p.ocr }   // NVE-shell: scan presets
                            OptionsPanel(paper, { paper = it }, mode, { mode = it }, quality, { quality = it })
                    Spacer(Modifier.height(16.dp))
                    SaveButtons(enabled = pages.isNotEmpty() && !runner.running, onSave = { nameDialog = true }, onOcr = { ocrAfter = true; savePdf("") })
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (scannerError != null) item {
                    Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(scannerError!!, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = pickGallery) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.pg_scan_pick_gallery)) }
                        }
                    }
                }
                if (pages.isEmpty()) {
                    item { EmptyState(Icons.Default.DocumentScanner, stringResource(R.string.scan_pages_hint), action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { launchScanner() }) { Text(stringResource(R.string.scan_start)) }
                            OutlinedButton(onClick = pickGallery) { Text(stringResource(R.string.pg_scan_pick_gallery)) }
                        }
                    }) }
                } else {
                    item { SectionTitle(stringResource(R.string.pg_scan_pages, pages.size)) }
                    itemsIndexed(pages, key = { _, f -> f.absolutePath }) { i, f ->
                        PageRow(f, i, pages.size, mode,
                            onUp = { pages = pages.toMutableList().apply { add(i - 1, removeAt(i)) } },
                            onDown = { pages = pages.toMutableList().apply { add(i + 1, removeAt(i)) } },
                            onRemove = { pages = pages - f })
                    }
                    item {
                        Column(Modifier.padding(16.dp)) {
                            com.smartdocs.editor.ui.nve.shell.NveScanPresetsRow(paper.name, mode.name, quality, ocrAfter) { p -> runCatching { paper = PaperSize.valueOf(p.paper); mode = ColorMode.valueOf(p.mode) }; quality = p.quality.toFloat(); ocrAfter = p.ocr }   // NVE-shell: scan presets
                            OptionsPanel(paper, { paper = it }, mode, { mode = it }, quality, { quality = it })
                            Spacer(Modifier.height(12.dp))
                            SaveButtons(enabled = !runner.running, onSave = { nameDialog = true }, onOcr = { ocrAfter = true; savePdf("") })
                        }
                    }
                }
                item { SectionTitle(stringResource(R.string.pg_scan_previous)) }
                if (previous.isEmpty()) item { Text(stringResource(R.string.pg_scan_no_previous), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(previous, key = { it.absolutePath }) { f ->
                    PreviousRow(f, onOpen = { nav.navigate(Routes.reader(Uri.fromFile(f).toString())) },
                        onShare = { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f)), null)) },
                        onDelete = { f.delete(); refreshPrevious() },
                        onOcr = { nav.navigate(Routes.tool("ocr", Uri.fromFile(f).toString())) })
                }
            }
        }
    }

    if (runner.running) ProgressDialog(runner.message)
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }) }
    if (nameDialog) {
        var name by remember { mutableStateOf("Scan_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())) }
        AlertDialog(onDismissRequest = { nameDialog = false }, title = { Text(stringResource(R.string.pg_scan_name)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.file_name)) }) },
            confirmButton = { TextButton(onClick = { nameDialog = false; savePdf(name) }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { nameDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun PageList(pages: List<File>, mode: ColorMode, onChange: (List<File>) -> Unit, modifier: Modifier) {
    LazyColumn(modifier) {
        itemsIndexed(pages, key = { _, f -> f.absolutePath }) { i, f ->
            PageRow(f, i, pages.size, mode,
                onUp = { onChange(pages.toMutableList().apply { add(i - 1, removeAt(i)) }) },
                onDown = { onChange(pages.toMutableList().apply { add(i + 1, removeAt(i)) }) },
                onRemove = { onChange(pages - f) })
        }
    }
}

@Composable
private fun PageRow(f: File, index: Int, count: Int, mode: ColorMode, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, f, mode) { value = withContext(Dispatchers.IO) { runCatching { ScanPdfBuilder.preview(f, mode) }.getOrNull() } }
    ListItem(
        leadingContent = {
            Box(Modifier.size(64.dp, 84.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        },
        headlineContent = { Text(stringResource(R.string.pg_page_n, index + 1)) },
        supportingContent = { Text(DocumentRepository.formatSize(f.length()), style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Row {
                IconButton(onClick = onUp, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.pg_scan_move_up)) }
                IconButton(onClick = onDown, enabled = index < count - 1) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.pg_scan_move_down)) }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, stringResource(R.string.pg_remove)) }
            }
        },
    )
}

@Composable
private fun OptionsPanel(paper: PaperSize, onPaper: (PaperSize) -> Unit, mode: ColorMode, onMode: (ColorMode) -> Unit, quality: Float, onQuality: (Float) -> Unit) {
    Column {
        Text(stringResource(R.string.pg_scan_paper), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PaperSize.entries.forEach { p ->
                FilterChip(paper == p, { onPaper(p) }, label = { Text(stringResource(when (p) { PaperSize.A4 -> R.string.pg_paper_a4; PaperSize.LETTER -> R.string.pg_paper_letter; PaperSize.FIT -> R.string.pg_paper_fit })) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.pg_color_mode), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorMode.entries.forEach { m ->
                FilterChip(mode == m, { onMode(m) }, label = { Text(stringResource(when (m) { ColorMode.COLOR -> R.string.pg_color_color; ColorMode.GRAY -> R.string.pg_color_gray; ColorMode.BW -> R.string.pg_color_bw })) })
            }
        }
        Spacer(Modifier.height(8.dp))
        LabeledSlider(stringResource(R.string.pg_quality), quality, 30f..100f, onQuality)
    }
}

@Composable
private fun SaveButtons(enabled: Boolean, onSave: () -> Unit, onOcr: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Button(onClick = onSave, Modifier.fillMaxWidth(), enabled = enabled) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pg_scan_save_pdf)) }
        OutlinedButton(onClick = onOcr, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = enabled) { Icon(Icons.Default.TextFields, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pg_scan_ocr_now)) }
    }
}

@Composable
private fun PreviousRow(f: File, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onOcr: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(f.name, maxLines = 1) },
        supportingContent = { Text("${DocumentRepository.formatSize(f.length())} · ${DocumentRepository.formatDate(f.lastModified())}", maxLines = 1) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.open)) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_ocr)) }, onClick = { menu = false; onOcr() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}
