package com.smartdocs.editor.ui.ocr

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.ocr.OcrPage
import com.smartdocs.editor.core.ocr.SearchablePdfWriter
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class OcrMode { SEARCHABLE, TEXT }

/** OCR tool: Tesseract (ara/eng) → searchable PDF (invisible text layer) or plain text per page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val engine = remember { OcrEngine(ctx.applicationContext) }
    // close() waits for a running recognition; abort it first and free the engine off the main thread
    DisposableEffect(Unit) { onDispose { engine.cancel(); kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { engine.close() } } }

    var file by remember { mutableStateOf(uri?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull() }) }
    var langs by remember { mutableStateOf(settings.ocrLanguages.ifBlank { "ara+eng" }) }
    var mode by remember { mutableStateOf(if (com.smartdocs.editor.core.data.NpfPrefs.ocrOutput == "text") OcrMode.TEXT else OcrMode.SEARCHABLE) }   // nav-prefs: OCR default
    var range by remember { mutableStateOf("") }
    var password by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }

    var job by remember { mutableStateOf<Job?>(null) }
    var progressMsg by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var textPages by remember { mutableStateOf<List<Pair<Int, OcrPage>>>(emptyList()) }
    var result by remember { mutableStateOf<File?>(null) }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> file = f; textPages = emptyList() } }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it); error = null } }

    val downloadFailed = stringResource(R.string.pg_ocr_download_failed)
    val initFailed = stringResource(R.string.pg_ocr_init_failed)
    val cancelledMsg = stringResource(R.string.pg_ocr_cancelled)
    var running by remember { mutableStateOf(false) }

    fun start() {
        val src = file ?: return
        if (running) return
        textPages = emptyList(); progress = null; progressMsg = ""; running = true
        job = scope.launch {
            try {
                // 1. language packs
                try {
                    engine.ensureLanguages(langs) { lang, f -> scope.launch { progressMsg = ctx.getString(R.string.ocr_downloading, OcrEngine.label(lang)); progress = f } }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { error = downloadFailed; return@launch }
                if (!engine.init(langs)) { error = initFailed; return@launch }
                // 2. pages
                // broken Arabic fonts are repaired first, so their pages count as having text (see RepairedInput)
                val input = if (mode == OcrMode.SEARCHABLE) com.smartdocs.editor.core.ocr.RepairedInput.of(ctx.cacheDir, src, password, rerun = emptyList()) else null
                val work = input?.file ?: src
                val session = try { PdfSession.open(ctx, work, password) } catch (e: PdfSession.Companion.PasswordRequiredException) { input?.close(); askPassword = true; return@launch }
                val writer = if (mode == OcrMode.SEARCHABLE) withContext(Dispatchers.IO) { SearchablePdfWriter(ctx, work, password) } else null
                val collected = ArrayList<Pair<Int, OcrPage>>()
                try {
                    val pages = PdfOps.parseRange(range, session.pageCount)
                    val plan = if (writer != null) com.smartdocs.editor.core.ocr.MixedPageOcr.plan(session, work, password, pages) else null
                    pages.forEachIndexed { n, pi ->
                        progressMsg = ctx.getString(R.string.ocr_recognizing, pi + 1); progress = n.toFloat() / pages.size
                        val (w, h) = session.pageSize(pi)
                        var scale = 300f / 72f
                        if (maxOf(w, h) * scale > 3500f) scale = 3500f / maxOf(w, h)
                        val bmp = session.render(pi, (w * scale).toInt(), (h * scale).toInt(), annotations = false)
                        val page = withContext(Dispatchers.Default) { engine.recognize(bmp, wantWords = writer != null) }
                        session.invalidateCache()
                        // a page that already has some selectable text only gets the words that are new
                        if (writer != null) writer.addLayer(pi, com.smartdocs.editor.core.ocr.MixedPageOcr.dropExisting(session, pi, page, plan))
                        collected += pi to page
                        if (mode == OcrMode.TEXT) textPages = collected.toList()
                    }
                    if (writer != null) {
                        val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_ocr")
                        writer.save(out)
                        runCatching { app.documents.touchRecent(out, session.pageCount) }
                        result = out
                    }
                } finally { session.closeAsync(); writer?.close(); input?.close() }
            } catch (e: CancellationException) {
                error = cancelledMsg
            } catch (e: Exception) { error = e.message ?: e.toString() } finally { progress = null; progressMsg = ""; running = false }
        }
    }
    fun cancel() { engine.cancel(); job?.cancel() }

    val fullText = remember(textPages) { textPages.joinToString("\n\n") { (pi, p) -> "— ${pi + 1} —\n${p.text.trim()}" } }
    val txtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { u ->
        if (u != null) scope.launch(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(u, "wt")?.use { it.write(fullText.toByteArray()) } } }
    }
    val copiedMsg = stringResource(R.string.pg_ocr_copied)

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { AppTopBar(stringResource(R.string.pg_ocr_title), onBack = { nav.popBackStack() }) {
            // tools5: batch OCR, auto language, pre-clean and word review
            IconButton(onClick = { nav.navigate(com.smartdocs.editor.ui.Routes.tool("ocr_plus", file?.let { android.net.Uri.fromFile(it).toString() })) }) { Icon(Icons.Default.DynamicFeed, stringResource(R.string.t5_ocr_title)) }
            if (textPages.isNotEmpty()) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(fullText)); scope.launch { snack.showSnackbar(copiedMsg) } }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy_text)) }
                IconButton(onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, fullText) }, null)) }) { Icon(Icons.Default.Share, stringResource(R.string.share_text)) }
                IconButton(onClick = { txtSaver.launch((file?.nameWithoutExtension ?: "ocr") + ".txt") }) { Icon(Icons.Default.SaveAlt, stringResource(R.string.pg_ocr_save_txt)) }
            }
        } },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val tablet = maxWidth > 700.dp
            val options: @Composable ColumnScope.() -> Unit = {
                FilePickRow(file, stringResource(R.string.open_file), pick)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.ocr_language), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ara+eng" to R.string.pg_ocr_both, "ara" to R.string.ocr_arabic, "eng" to R.string.ocr_english).forEach { (code, label) ->
                        FilterChip(langs == code, { langs = code }, label = { Text(stringResource(label)) },
                            leadingIcon = if (code.split('+').all { engine.isLanguageInstalled(it) }) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.ocr_mode), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(mode == OcrMode.SEARCHABLE, { mode = OcrMode.SEARCHABLE }, label = { Text(stringResource(R.string.ocr_searchable)) })
                    FilterChip(mode == OcrMode.TEXT, { mode = OcrMode.TEXT }, label = { Text(stringResource(R.string.ocr_text_only)) })
                }
                Spacer(Modifier.height(12.dp))
                PageRangeField(range) { range = it }
                Text(stringResource(R.string.pg_ocr_page_range_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { start() }, Modifier.fillMaxWidth(), enabled = file != null && !running) { Icon(Icons.Default.TextFields, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pg_ocr_start)) }
            }
            if (tablet) Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(360.dp).padding(16.dp).fillMaxHeight(), content = options)
                TextResults(textPages, Modifier.weight(1f).fillMaxHeight())
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item { Column(content = options) }
                if (textPages.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.pg_ocr_result)) }
                    itemsIndexed(textPages, key = { _, p -> p.first }) { _, (pi, page) -> TextPageCard(pi, page) }
                }
            }
        }
    }

    if (running) Dialog(onDismissRequest = {}) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(24.dp).widthIn(min = 240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val p = progress
                if (p == null || p <= 0f) CircularProgressIndicator() else CircularProgressIndicator(progress = { p })
                Spacer(Modifier.height(16.dp))
                Text(progressMsg.ifBlank { stringResource(R.string.processing) }, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { cancel() }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }) }
    if (askPassword) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { askPassword = false }, title = { Text(stringResource(R.string.password_enter)) },
            text = { OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text(stringResource(R.string.password)) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()) },
            confirmButton = { TextButton(onClick = { password = pw; askPassword = false; start() }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { askPassword = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun TextResults(pages: List<Pair<Int, OcrPage>>, modifier: Modifier) {
    if (pages.isEmpty()) EmptyState(Icons.Default.TextFields, stringResource(R.string.ocr_no_text), modifier)
    else LazyColumn(modifier, contentPadding = PaddingValues(16.dp)) {
        itemsIndexed(pages, key = { _, p -> p.first }) { _, (pi, page) -> TextPageCard(pi, page) }
    }
}

@Composable
private fun TextPageCard(pageIndex: Int, page: OcrPage) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pg_ocr_page_header, pageIndex + 1), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (page.words.isNotEmpty()) Text(stringResource(R.string.pg_ocr_words, page.words.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            SelectionContainer { Text(page.text.trim().ifBlank { stringResource(R.string.ocr_no_text) }, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
