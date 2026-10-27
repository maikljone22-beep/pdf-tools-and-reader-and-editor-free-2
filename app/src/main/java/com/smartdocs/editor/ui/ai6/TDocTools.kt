package com.smartdocs.editor.ui.ai6

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.AnswerPdf
import com.smartdocs.editor.core.ai5.DocIndexer
import com.smartdocs.editor.core.ai6.AiMedia
import com.smartdocs.editor.core.ai6.CsvText
import com.smartdocs.editor.core.ai6.DocReview
import com.smartdocs.editor.core.ai6.ExtractedTable
import com.smartdocs.editor.core.ai6.ReviewResult
import com.smartdocs.editor.core.ai6.SlideDeck
import com.smartdocs.editor.core.ai6.SlideRender
import com.smartdocs.editor.core.ai6.SlidesGen
import com.smartdocs.editor.core.ai6.SlidesPptx
import com.smartdocs.editor.core.ai6.VideoSummary
import com.smartdocs.editor.ui.ai5.LanguagePicker
import com.smartdocs.editor.ui.ai5.parsePages
import com.smartdocs.editor.ui.common.ResultSheet
import com.smartdocs.editor.ui.common.mimeFor
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Open / share / save-as row for a non-PDF result (CSV, Markdown, MP4, TXT…). PDFs and PPTX go through [ResultSheet]. */
@Composable
fun TFileActions(file: File) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val mime = remember(file.extension) { mimeFor(file.extension) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching { app.documents.exportTo(file, uri) }.isSuccess
            Toast.makeText(ctx, ctx.getString(if (ok) R.string.fx_saved_copy else R.string.ai6t_failed_save), Toast.LENGTH_SHORT).show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(file.name, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(app.documents.shareUri(file), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    .onFailure { Toast.makeText(ctx, ctx.getString(R.string.ai6t_no_app), Toast.LENGTH_SHORT).show() }
            }) { Text(stringResource(R.string.ai6t_open)) }
            OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(file), mime), null)) } }) { Text(stringResource(R.string.share)) }
            OutlinedButton(onClick = { saver.launch(file.name) }) { Text(stringResource(R.string.save_as)) }
        }
    }
}

internal fun tError(ctx: Context, e: Throwable): String =
    if (e.message == "parse") ctx.getString(R.string.a5_err_parse) else AiMedia.userMessage(ctx, e)

// ------------------------------------------------------------------ B13 / B15: slides + narrated video

/** Generate a presentation from [files] (one document or a Space) and export it as PPTX / PDF / Markdown / narrated MP4. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlidesSheet(nav: NavHostController, files: List<File>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    var count by remember { mutableStateOf(10f) }
    var focus by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("") }
    var deck by remember { mutableStateOf<SlideDeck?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pdfOrPptx by remember { mutableStateOf<File?>(null) }
    var other by remember { mutableStateOf<File?>(null) }
    val base = files.firstOrNull()?.nameWithoutExtension ?: "slides"
    val working = stringResource(R.string.a5_working)

    fun export(kind: String) {
        val d = deck ?: return
        busy = if (kind == "mp4") ctx.getString(R.string.ai6t_video_working) else working
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    when (kind) {
                        "pptx" -> app.documents.uniqueFile(app.documents.docsDir, base + "_slides", "pptx").also { f -> f.outputStream().use { SlidesPptx.write(d, it) } }
                        "pdf" -> app.documents.uniqueFile(app.documents.docsDir, base + "_slides", "pdf").also { f -> SlideRender.pdf(d, f) }
                        "md" -> app.documents.uniqueFile(app.documents.docsDir, base + "_slides", "md").also { f -> f.writeText(SlidesGen.markdown(d)) }
                        else -> app.documents.uniqueFile(app.documents.docsDir, base + "_video", "mp4").also { f ->
                            VideoSummary.make(ctx, d, f, settings.ttsRate) { p -> busy = ctx.getString(R.string.ai6t_video_progress, p.step + 1, p.total) }
                        }
                    }
                }
                if (kind == "pptx" || kind == "pdf") pdfOrPptx = f else other = f
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.message ?: e.toString() } finally { busy = null }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai6t_slides), style = MaterialTheme.typography.titleMedium)
            val d = deck
            if (d == null) {
                Text(stringResource(R.string.ai6t_slides_count, count.toInt()))
                Slider(count, { count = it }, valueRange = 4f..20f, steps = 15)
                OutlinedTextField(focus, { focus = it }, label = { Text(stringResource(R.string.ai6t_focus_optional)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.a5_target_language)); LanguagePicker(lang, { lang = it })
                }
                Button(enabled = busy == null && files.isNotEmpty(), onClick = {
                    busy = working; error = null
                    scope.launch {
                        try {
                            val idx = DocIndexer.getAll(ctx, files)
                            deck = SlidesGen.generate(ctx, client, idx, count.toInt(), lang, focus)
                        } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = tError(ctx, e) } finally { busy = null }
                    }
                }) { Text(stringResource(R.string.ai6t_generate)) }
            } else {
                Text(d.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(d.slides) { i, s ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text("${i + 1}. ${s.title}", style = MaterialTheme.typography.labelLarge)
                                s.bullets.forEach { b -> Text("• $b", style = MaterialTheme.typography.bodySmall) }
                                if (s.notes.isNotBlank()) Text(s.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(enabled = busy == null, onClick = { export("pptx") }) { Text("PPTX") }
                    FilledTonalButton(enabled = busy == null, onClick = { export("pdf") }) { Text("PDF") }
                    FilledTonalButton(enabled = busy == null, onClick = { export("md") }) { Text("Markdown") }
                    FilledTonalButton(enabled = busy == null, onClick = { export("mp4") }) { Text(stringResource(R.string.ai6t_video)) }
                    TextButton(enabled = busy == null, onClick = { deck = null }) { Text(stringResource(R.string.ai6t_again)) }
                }
                Text(stringResource(R.string.ai6t_video_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                other?.let { TFileActions(it) }
            }
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(it) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    pdfOrPptx?.let { f -> ResultSheet(f, nav, onDismiss = { pdfOrPptx = null }) }
}

// ------------------------------------------------------------------ B17: contract review / analysis / risk / compare

@Composable
private fun levelColor(level: String) = when (level) {
    "high", "عالي", "مرتفع" -> MaterialTheme.colorScheme.error
    "medium", "متوسط" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun ReviewCards(r: ReviewResult, onPage: ((Int) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r.summary.isNotBlank()) Text(r.summary, style = MaterialTheme.typography.bodyMedium)
        r.sections.forEach { s ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.title.isNotBlank()) Text(s.title, style = MaterialTheme.typography.titleSmall)
                    s.items.forEach { i ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (i.label.isNotBlank()) Text(i.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
                                if (i.level.isNotBlank()) { Spacer(Modifier.width(6.dp)); Text(i.level, style = MaterialTheme.typography.labelSmall, color = levelColor(i.level)) }
                            }
                            if (i.value.isNotBlank()) SelectionContainer { Text(i.value, style = MaterialTheme.typography.bodySmall) }
                            if (i.pages.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                i.pages.distinct().take(6).forEach { p ->
                                    AssistChip(onClick = { onPage?.invoke(p - 1) }, enabled = onPage != null, label = { Text(stringResource(R.string.a5_page_n, p)) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Contract review, document analysis, risk analysis, or comparison of up to 10 contracts (extra files picked here). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(nav: NavHostController, file: File, kind: DocReview.Kind, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    var files by remember { mutableStateOf(listOf(file)) }
    var result by remember { mutableStateOf<ReviewResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pdf by remember { mutableStateOf<File?>(null) }
    var csv by remember { mutableStateOf<File?>(null) }
    val pick = rememberPdfPicker(multiple = true) { picked -> files = (files + picked).distinctBy { it.absolutePath }.take(DocReview.MAX_COMPARE) }
    val title = stringResource(when (kind) {
        DocReview.Kind.CONTRACT -> R.string.ai6t_contract; DocReview.Kind.ANALYSIS -> R.string.ai6t_analysis
        DocReview.Kind.RISK -> R.string.ai6t_risk; DocReview.Kind.COMPARE -> R.string.ai6t_compare
    })
    fun startReview() {
        busy = true; error = null
        scope.launch {
            try { result = DocReview.review(ctx, client, DocIndexer.getAll(ctx, files), kind) }
            catch (e: CancellationException) { throw e } catch (e: Throwable) { error = tError(ctx, e) } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { if (kind != DocReview.Kind.COMPARE) startReview() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (kind == DocReview.Kind.COMPARE) {
                Text(stringResource(R.string.ai6t_compare_files, files.size, DocReview.MAX_COMPARE), style = MaterialTheme.typography.bodySmall)
                files.forEach { f -> Text("• " + f.name, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy && files.size < DocReview.MAX_COMPARE, onClick = pick) { Text(stringResource(R.string.ai6t_add_files)) }
                    Button(enabled = !busy && files.size >= 2, onClick = { startReview() }) { Text(stringResource(R.string.ai6t_compare_run)) }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { r ->
                val jump: ((Int) -> Unit)? = if (files.size == 1) { p: Int -> onDismiss(); nav.navigate(com.smartdocs.editor.ui.Routes.reader(Uri.fromFile(file).toString(), p)) } else null
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { ReviewCards(r, onPage = jump) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            runCatching {
                                val out = app.documents.uniqueFile(app.documents.docsDir, file.nameWithoutExtension + "_" + kind.name.lowercase(), "pdf")
                                AnswerPdf.write(ctx, title, title + "\n\n" + DocReview.text(r), out); out
                            }.onSuccess { pdf = it }.onFailure { error = it.message }
                        }
                    }) { Text("PDF") }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    app.documents.uniqueFile(app.documents.docsDir, file.nameWithoutExtension + "_" + kind.name.lowercase(), "csv").also { it.writeText(CsvText.BOM + DocReview.csv(r)) }
                                }
                            }.onSuccess { csv = it }.onFailure { error = it.message }
                        }
                    }) { Text("CSV") }
                    OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, DocReview.text(r)), null)) } }) { Text(stringResource(R.string.share)) }
                }
                csv?.let { TFileActions(it) }
            }
        }
    }
    pdf?.let { f -> ResultSheet(f, nav, onDismiss = { pdf = null }) }
}

// ------------------------------------------------------------------ B17: table → CSV

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesSheet(file: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    var pages by remember { mutableStateOf("") }
    var focus by remember { mutableStateOf("") }
    var tables by remember { mutableStateOf<List<ExtractedTable>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var out by remember { mutableStateOf<File?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai6t_tables), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(pages, { pages = it }, label = { Text(stringResource(R.string.a5_pages_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(focus, { focus = it }, label = { Text(stringResource(R.string.ai6t_focus_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy, onClick = {
                busy = true; error = null; out = null
                scope.launch {
                    try {
                        val idx = DocIndexer.get(ctx, file)
                        val list = DocReview.tables(ctx, client, idx, pages.takeIf { it.isNotBlank() }?.let { parsePages(it) }, focus)
                        tables = list
                        if (list.isEmpty()) error = ctx.getString(R.string.ai6t_no_tables)
                    } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = tError(ctx, e) } finally { busy = false }
                }
            }) { Text(stringResource(R.string.ai6t_extract)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            tables?.takeIf { it.isNotEmpty() }?.let { list ->
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(list) { _, t ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp).horizontalScroll(rememberScrollState())) {
                                Text(t.title.ifBlank { stringResource(R.string.ai6t_table) } + (t.page?.let { " · " + ctx.getString(R.string.a5_page_n, it) } ?: ""), style = MaterialTheme.typography.labelLarge)
                                t.rows.take(30).forEachIndexed { ri, r ->
                                    Row { r.forEach { c -> Text(c, Modifier.width(110.dp).padding(2.dp), style = MaterialTheme.typography.bodySmall, fontWeight = if (ri == 0) FontWeight.Bold else null, maxLines = 2) } }
                                }
                            }
                        }
                    }
                }
                FilledTonalButton(onClick = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { app.documents.uniqueFile(app.documents.docsDir, file.nameWithoutExtension + "_tables", "csv").also { it.writeText(CsvText.BOM + DocReview.tablesCsv(list)) } }
                        }.onSuccess { out = it }.onFailure { error = it.message }
                    }
                }) { Text(stringResource(R.string.ai6t_save_csv)) }
                out?.let { TFileActions(it) }
            }
        }
    }
}
