package com.smartdocs.editor.ui.view5

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.util.PdfPrint
import com.smartdocs.editor.core.view5.DocPrintPrefs
import com.smartdocs.editor.core.view5.PrintLayout
import com.smartdocs.editor.core.view5.PrintPipeline
import com.smartdocs.editor.core.view5.PrintPresets
import com.smartdocs.editor.core.view5.PrintSettings
import com.smartdocs.editor.ui.edit5.rememberPageLabels
import kotlinx.coroutines.launch
import java.io.File

/**
 * Print options before the system dialog: all / current / range (page numbers or page labels), odd / even, reverse,
 * what to print, one page per sheet with a scale, N-up / N×M, booklet (binding, fronts / backs, sheets), poster tiles,
 * paper, orientation, margins, greyscale, print as image, comment summary, presets and a preview. The document's own
 * print preferences (/ViewerPreferences) are applied first. [outputFile] gives the file with pending annotations
 * (same as the reader's share / print).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSheet5(v: V5State, outputFile: suspend () -> File?, onMessage: (String) -> Unit) {
    val s = v.s
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = ctx.applicationContext as SmartDocsApp
    var which by remember { mutableIntStateOf(0) }           // 0 all, 1 current, 2 range, 3 current view (reader-prefs §36)
    val view = remember { com.smartdocs.editor.ui.rpf.RpfPrintView.visible(s) }
    var range by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(PrintPresets.last(ctx) ?: PrintSettings()) }
    var docPrefs by remember { mutableStateOf(DocPrintPrefs()) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<File?>(null) }
    var sumOpts by remember { mutableStateOf(com.smartdocs.editor.core.annot.SummaryOptions()) }
    val labels = rememberPageLabels(s)
    LaunchedEffect(s.file) {
        val f = s.file ?: return@LaunchedEffect
        val p = DocPrintPrefs.read(f, s.password)
        docPrefs = p
        if (p.noScaling) settings = settings.copy(scale = PrintLayout.Scale.ACTUAL)
        if (p.range.isNotBlank()) { which = 2; range = p.range }
    }

    /** Prepare the file to print with the current choices (null on error, reported). */
    suspend fun prepared(): Pair<File, Int>? = runCatching {
        val doc = outputFile() ?: error("no file")
        // reader-prefs §36: "current view" prints the visible part of the page on screen (a one-page copy cut to it)
        val src = if (which == 3 && view != null) File(app.documents.tempDir, "print_view_${System.currentTimeMillis()}.pdf").also { out ->
            com.smartdocs.editor.ui.rpf.RpfPrintView.write(doc, s.password, view.first, view.second, s.rotation, out)
        } else doc
        val spec = if (which == 3) "1" else when (which) { 1 -> (s.currentPage + 1).toString(); 2 -> PrintLayout.labelRange(range, labels); else -> "" }
        PrintPipeline.prepare(ctx, src, s.password, settings.toOptions(spec), settings.extras(), app.documents.tempDir, printSummaryLabels(ctx),
            summaryOptions = sumOpts.copy(pageCount = s.pageCount))
    }.onFailure { onMessage(it.message ?: ctx.getString(R.string.error_generic)) }.getOrNull()

    ModalBottomSheet(onDismissRequest = { v.close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.v5_print_options), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.v5_pages), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(which == 0, { which = 0 }, label = { Text(stringResource(R.string.v5_all_pages)) })
                FilterChip(which == 1, { which = 1 }, label = { Text(stringResource(R.string.v5_current_page, s.currentPage + 1)) })
                FilterChip(which == 2, { which = 2 }, label = { Text(stringResource(R.string.v5_range)) })
                if (view != null) FilterChip(which == 3, { which = 3 }, label = { Text(stringResource(R.string.rpf_print_view)) })
            }
            if (which == 2) OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.v5_range_hint)) },
                supportingText = if (labels != null) ({ Text(stringResource(R.string.nbt_range_labels)) }) else null)
            if (docPrefs.copies > 1) Text(stringResource(R.string.nbt_doc_copies, docPrefs.copies), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrintOptionsForm(settings) { settings = it }
            if (settings.summary) com.smartdocs.editor.ui.reader.annot.SummaryOptionsFields(sumOpts, s.pageCount) { sumOpts = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                    busy = true
                    scope.launch { prepared()?.let { preview = it.first }; busy = false }
                }) { Text(stringResource(R.string.nbt_preview)) }
                Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                    busy = true
                    scope.launch {
                        val r = prepared()
                        if (r != null) runCatching {
                            PrintPresets.remember(ctx, settings)
                            PdfPrint.print(ctx, r.first, s.file?.nameWithoutExtension ?: "print", r.second, mono = settings.grayscale, media = settings.paper,
                                duplex = DocPrintPrefs.duplexMode(docPrefs.duplex))
                        }.onSuccess { v.close() }.onFailure { onMessage(it.message ?: ctx.getString(R.string.error_generic)) }
                        busy = false
                    }
                }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.print)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    preview?.let { f -> PrintPreviewDialog(f) { preview = null } }
}
