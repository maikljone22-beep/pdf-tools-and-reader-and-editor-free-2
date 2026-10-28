package com.smartdocs.editor.ui.tools5

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.AttachmentOps
import com.smartdocs.editor.core.util.PdfPrint
import com.smartdocs.editor.core.view5.PrintPipeline
import com.smartdocs.editor.core.view5.PrintPresets
import com.smartdocs.editor.core.view5.PrintSettings
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import com.smartdocs.editor.ui.view5.PrintOptionsForm
import com.smartdocs.editor.ui.view5.printSummaryLabels
import java.io.File

/**
 * Batch Print (Foxit / Nitro): files, a folder (with sub-folders) or the open tabs; the same page range and print
 * options for all; PDF attachments printed too on request; one print job per file or all merged into one job.
 */
@Composable
fun BatchPrintScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val job = rememberT5Job()
    var files by remember { mutableStateOf(listOfNotNull(fileOf(uri))) }
    var range by remember { mutableStateOf("") }
    var merged by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(PrintPresets.last(ctx) ?: PrintSettings()) }
    var copying by remember { mutableStateOf(false) }
    val pick = rememberPdfPicker(multiple = true) { picked -> files = (files + picked).distinctBy { it.absolutePath } }
    val folder = rememberFolderSource({ File(app.documents.tempDir, "nbt_print_in_" + System.currentTimeMillis()) }, onBusy = { copying = it }) { picked ->
        files = (files + picked.map { it.file }).distinctBy { it.absolutePath }
    }
    val noTabs = stringResource(R.string.nbt_no_open_tabs)
    val preparing = stringResource(R.string.nbt_print_preparing)

    ToolScaffold(stringResource(R.string.nbt_batch_print), nav, snackbar = snackbar) {
        OutlinedButton(onClick = pick, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_add_files)) }
        BatchSourceButtons(onFolder = folder, onOpenTabs = {
            val tabs = openTabFiles(ctx)
            if (tabs.isEmpty()) job.error = noTabs else files = (files + tabs).distinctBy { it.absolutePath }
        })
        if (copying) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (files.isNotEmpty()) T5Card(stringResource(R.string.t5_files_n, files.size)) { FileListEditor(files, { files = it }) }
        T5Card(stringResource(R.string.v5_print_options), Icons.Default.Print) {
            OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbt_range_all_files)) },
                supportingText = { Text(stringResource(R.string.v5_range_hint)) })
            PrintOptionsForm(settings) { settings = it }
            HorizontalDivider()
            SwitchRow(stringResource(R.string.nbt_print_attachments), attachments) { attachments = it }
            SwitchRow(stringResource(R.string.nbt_print_merged), merged, stringResource(R.string.nbt_print_merged_sub)) { merged = it }
        }
        PrimaryButton(stringResource(R.string.nbt_print_n, files.size), enabled = files.isNotEmpty() && !job.running, icon = Icons.Default.Print) {
            val list = files
            job.launch(preparing) {
                PrintPresets.remember(ctx, settings)
                val tmp = File(app.documents.tempDir, "nbt_batch_print").apply { mkdirs() }
                // PDF attachments of each file are printed after it
                val inputs = ArrayList<File>()
                list.forEach { f ->
                    inputs += f
                    if (attachments) runCatching {
                        AttachmentOps.list(f).filter { it.name.endsWith(".pdf", true) }.forEach { a -> AttachmentOps.extract(f, a, tmp)?.let { inputs += it } }
                    }
                }
                val ready = ArrayList<Triple<File, Int, String>>()
                val failed = ArrayList<String>()
                inputs.forEachIndexed { i, f ->
                    step(i, inputs.size)
                    runCatching { PrintPipeline.prepare(ctx, f, null, settings.toOptions(range), settings.extras(), tmp, printSummaryLabels(ctx)) }
                        .onSuccess { (out, n) -> ready += Triple(out, n, f.nameWithoutExtension) }.onFailure { failed += f.name }
                }
                if (merged && ready.size > 1) {
                    val out = File(tmp, "batch_print_${System.currentTimeMillis()}.pdf")
                    val n = PrintPipeline.merge(ready.map { it.first }, out)
                    PdfPrint.print(ctx, out, ctx.getString(R.string.nbt_batch_print), n, mono = settings.grayscale, media = settings.paper)
                } else ready.forEach { (out, n, name) -> PdfPrint.print(ctx, out, name, n, mono = settings.grayscale, media = settings.paper) }
                if (failed.isNotEmpty()) error(ctx.getString(R.string.nbt_print_failed, failed.joinToString("، ")))
            }
        }
        Text(stringResource(R.string.nbt_batch_print_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    T5JobUi(job, nav, snackbar)
}
