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
import kotlinx.coroutines.ensureActive
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
    val wrongPw = stringResource(R.string.password_wrong)
    // audit 5 C-4: a protected input is opened with the reader's password or asks for one
    val lock = rememberToolPassword(file) { file = null }

    ToolScaffold(stringResource(R.string.tool_pdf_to_images), nav, runner) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        FieldLabel(stringResource(R.string.export_format))
        ChoiceChips(listOf(false, true), png, { if (it) stringResource(R.string.format_png) else stringResource(R.string.format_jpg) }) { png = it }
        FieldLabel(stringResource(R.string.tl_dpi))
        ChoiceChips(listOf(72, 150, 300), dpi, { "$it" }) { dpi = it }
        PageRangeField(range) { range = it }
        PrimaryButton(stringResource(R.string.tl_export), enabled = file != null && lock.ready, icon = Icons.Default.Collections) {
            val src = file ?: return@PrimaryButton
            val usePng = png; val d = dpi; val spec = range; val pw = lock.password
            runner.runMany(processing) { withPasswordMessage(wrongPw) { renderPages(ctx, app.documents.docsDir, src, usePng, d, spec, pw) } }
        }
        ResultsList(runner.results, nav, "image/*")
    }
}

/** A directory "[base]" in [parent] that does not exist yet ("[base] (2)", …): an export never overwrites an earlier one. */
internal fun uniqueDir(parent: File, base: String): File {
    var d = File(parent, base); var k = 2
    while (d.exists()) d = File(parent, "$base ($k)").also { k++ }
    return d
}

/**
 * Render [spec] pages of [src] at [dpi] into a new `<docsDir>/<name>_images/` folder. audit 5 C-4 / C-13: opens with
 * [password]; each page bitmap is dropped from the session cache and recycled once written (300 dpi ≈ 35 MB a
 * page); an earlier export is never overwritten (a new folder each time).
 */
suspend fun renderPages(ctx: android.content.Context, docsDir: File, src: File, png: Boolean, dpi: Int, spec: String, password: String? = null): List<File> {
    val s = PdfSession.open(ctx, src, password)
    try {
        val pages = PdfOps.parseRange(spec, s.pageCount)
        val dir = uniqueDir(docsDir, src.nameWithoutExtension + "_images").apply { mkdirs() }
        return pages.map { i ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val (w, h) = s.pageSize(i)
            val bmp = s.render(i, (w * dpi / 72f).toInt().coerceAtLeast(1), (h * dpi / 72f).toInt().coerceAtLeast(1))
            val out = File(dir, "page_${i + 1}." + if (png) "png" else "jpg")
            try {
                out.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
            } finally {
                s.invalidateCache()     // the cache must not hand out the recycled bitmap
                bmp.recycle()
            }
            out
        }
    } finally { s.closeAsync() }
}

/**
 * Extract all text of a PDF (PDFium first, PdfBox as fallback). audit 5 C-5: a missing / wrong [password] is
 * not swallowed (it used to show "No text found"): it throws [PdfSession.Companion.PasswordRequiredException]; other
 * failures of both readers throw too.
 */
suspend fun extractAllText(ctx: android.content.Context, src: File, password: String? = null): List<String> {
    val viaPdfium = try {
        val s = PdfSession.open(ctx, src, password)
        try { s.allText() } finally { s.closeAsync() }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: PdfSession.Companion.PasswordRequiredException) { throw e
    } catch (e: Exception) { null }
    if (viaPdfium != null && viaPdfium.any { it.isNotBlank() }) return viaPdfium
    return try { PdfOps.extractText(src, password).split("\u000C") }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw PdfSession.Companion.PasswordRequiredException() }
    catch (e: Exception) { viaPdfium ?: throw e }
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
    val failed = stringResource(R.string.ttcx_text_failed)
    // audit 5 C-4 / C-5: a protected input is opened with the reader's password or asks for one
    val lock = rememberToolPassword(file) { file = null; text = null }

    fun extract() {
        val f = file ?: return
        val pw = lock.password
        extracting = true
        scope.launch {
            try {
                val pages = extractAllText(ctx, f, pw)
                text = pages.mapIndexed { i, t -> if (pages.size > 1) "— ${i + 1} —\n$t" else t }.joinToString("\n\n")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                if (e is PdfSession.Companion.PasswordRequiredException) { runner.error = needPassword; lock.reask() }
                else runner.error = failed
            } finally { extracting = false }
        }
    }
    LaunchedEffect(file, lock.ready, lock.password) { if (file != null && lock.ready && text == null) extract() }

    ToolScaffold(stringResource(R.string.tool_pdf_to_text), nav, runner, snackbar = snackbar, scrollable = false, actions = {
        val t = text
        if (!t.isNullOrBlank()) {
            IconButton(onClick = { runCatching { clipboard.setText(AnnotatedString(t.take(200_000))) }; scope.launch { snackbar.showSnackbar(copied) } }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy_text)) }
            // EXTRA_TEXT travels through a binder transaction (~1 MB limit): long documents threw TransactionTooLargeException
            IconButton(onClick = { runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t.take(100_000)) }, null)) } }) { Icon(Icons.Default.Share, stringResource(R.string.share_text)) }
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
                    // one Text over a multi-MB string freezes / runs out of memory in layout: show the start, "save as TXT" keeps everything
                    Text(if (t.length > 300_000) t.take(300_000) + "\n…" else t, Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp), style = MaterialTheme.typography.bodyMedium)
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
