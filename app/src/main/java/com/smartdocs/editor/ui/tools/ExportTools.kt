package com.smartdocs.editor.ui.tools

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.PageRangeField
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import kotlinx.coroutines.launch
import java.io.File

/** Tool "pdf_to_images": render pages to JPG / PNG files. */
@Composable
fun PdfToImagesScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    var png by remember { mutableStateOf(false) }
    var dpi by remember { mutableIntStateOf(150) }
    var range by remember { mutableStateOf("") }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val processing = stringResource(R.string.processing)

    ToolScaffold(stringResource(R.string.tool_pdf_to_images), nav, runner) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        FieldLabel(stringResource(R.string.export_format))
        ChoiceChips(listOf(false, true), png, { if (it) stringResource(R.string.format_png) else stringResource(R.string.format_jpg) }) { png = it }
        FieldLabel(stringResource(R.string.tl_dpi))
        ChoiceChips(listOf(72, 150, 300), dpi, { "$it" }) { dpi = it }
        PageRangeField(range) { range = it }
        PrimaryButton(stringResource(R.string.tl_export), enabled = file != null, icon = Icons.Default.Collections) {
            val src = file ?: return@PrimaryButton
            val usePng = png; val d = dpi; val spec = range
            runner.runMany(processing) { renderPages(ctx, app.documents.docsDir, src, usePng, d, spec) }
        }
        ResultsList(runner.results, nav, "image/*")
    }
}

/** Render [spec] pages of [src] at [dpi] into `<docsDir>/<name>_images/`. */
suspend fun renderPages(ctx: android.content.Context, docsDir: File, src: File, png: Boolean, dpi: Int, spec: String): List<File> {
    val dir = File(docsDir, src.nameWithoutExtension + "_images").apply { mkdirs() }
    val s = PdfSession.open(ctx, src)
    try {
        val pages = PdfOps.parseRange(spec, s.pageCount)
        return pages.map { i ->
            val (w, h) = s.pageSize(i)
            val bmp = s.render(i, (w * dpi / 72f).toInt().coerceAtLeast(1), (h * dpi / 72f).toInt().coerceAtLeast(1))
            val out = File(dir, "page_${i + 1}." + if (png) "png" else "jpg")
            out.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
            out
        }
    } finally { s.closeAsync() }
}

/** Extract all text of a PDF (PDFium first, PdfBox as fallback). */
suspend fun extractAllText(ctx: android.content.Context, src: File): List<String> {
    val viaPdfium = runCatching {
        val s = PdfSession.open(ctx, src)
        try { s.allText() } finally { s.closeAsync() }
    }.getOrNull()
    if (viaPdfium != null && viaPdfium.any { it.isNotBlank() }) return viaPdfium
    return runCatching { PdfOps.extractText(src).split("\u000C") }.getOrDefault(viaPdfium ?: emptyList())
}

/** Tool "pdf_to_text": extract text, copy / share / save as .txt. */
@Composable
fun PdfToTextScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    var file by rememberInputFile(uri)
    var text by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    val pick = rememberPdfPicker { file = it.firstOrNull(); text = null }
    val processing = stringResource(R.string.processing)
    val copied = stringResource(R.string.tl_copied)
    val needPassword = stringResource(R.string.password_enter)

    fun extract() {
        val f = file ?: return
        extracting = true
        scope.launch {
            runCatching { extractAllText(ctx, f) }
                .onSuccess { pages -> text = pages.mapIndexed { i, t -> if (pages.size > 1) "— ${i + 1} —\n$t" else t }.joinToString("\n\n") }
                .onFailure { runner.error = if (it is PdfSession.Companion.PasswordRequiredException) needPassword else (it.message ?: it.toString()) }
            extracting = false
        }
    }
    LaunchedEffect(file) { if (file != null && text == null) extract() }

    ToolScaffold(stringResource(R.string.tool_pdf_to_text), nav, runner, snackbar = snackbar, scrollable = false, actions = {
        val t = text
        if (!t.isNullOrBlank()) {
            IconButton(onClick = { clipboard.setText(AnnotatedString(t)); scope.launch { snackbar.showSnackbar(copied) } }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy_text)) }
            IconButton(onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t) }, null)) }) { Icon(Icons.Default.Share, stringResource(R.string.share_text)) }
        }
    }) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        val t = text
        when {
            extracting -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            t == null -> Box(Modifier.weight(1f))
            t.isBlank() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(stringResource(R.string.tl_no_text_found), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> Card(Modifier.fillMaxWidth().weight(1f)) {
                val scroll = rememberScrollState()
                SelectionContainer {
                    Text(t, Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        PrimaryButton(stringResource(R.string.tl_save_txt), enabled = !t.isNullOrBlank(), icon = Icons.Default.SaveAlt) {
            val src = file ?: return@PrimaryButton
            val content = t ?: return@PrimaryButton
            runner.run(processing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension, "txt")
                out.writeText(content)
                out
            }
        }
    }
}
