package com.smartdocs.editor.ui.view5

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.util.PdfPrint
import com.smartdocs.editor.core.view5.PrintLayout
import com.smartdocs.editor.core.view5.PrintOptions
import com.smartdocs.editor.core.view5.PrintPrep
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.launch
import java.io.File

/**
 * Print options before the system dialog: all / current / range, odd / even, reverse, with or without
 * comments, pages per sheet (1–16), booklet (right or left binding), paper and orientation, borders.
 * [outputFile] gives the file with pending annotations (same as the reader's share / print).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSheet5(v: V5State, outputFile: suspend () -> File?, onMessage: (String) -> Unit) {
    val s = v.s
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var which by remember { mutableIntStateOf(0) }           // 0 all, 1 current, 2 range
    var range by remember { mutableStateOf("") }
    // NVE-file: one options object (custom N x M, booklet sides, scaling, markups only, grey, summary, duplex),
    // defaults from the document's /ViewerPreferences print preset, user presets
    var opts by remember { mutableStateOf(PrintOptions()) }
    var docPreset by remember { mutableStateOf<com.smartdocs.editor.core.nve.PrintPresets.DocPreset?>(null) }
    LaunchedEffect(s.file) {
        val f = s.file ?: return@LaunchedEffect
        val p = com.smartdocs.editor.ui.nve.file.NvePrint.docPreset(f, s.password) ?: return@LaunchedEffect
        if (p.isEmpty) return@LaunchedEffect
        docPreset = p
        opts = com.smartdocs.editor.core.nve.PrintPresets.applyDocPreset(opts, p)
        if (!p.range.isNullOrBlank()) { which = 2; range = p.range }
    }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = { v.close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.v5_print_options), style = MaterialTheme.typography.titleLarge)
            if (docPreset != null) Text(stringResource(R.string.nf_doc_preset_applied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            com.smartdocs.editor.ui.nve.file.NvePresetRow(opts) { o -> opts = o }
            Text(stringResource(R.string.v5_pages), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(which == 0, { which = 0 }, label = { Text(stringResource(R.string.v5_all_pages)) })
                FilterChip(which == 1, { which = 1 }, label = { Text(stringResource(R.string.v5_current_page, s.currentPage + 1)) })
                FilterChip(which == 2, { which = 2 }, label = { Text(stringResource(R.string.v5_range)) })
            }
            if (which == 2) OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.v5_range_hint)) })
            com.smartdocs.editor.ui.nve.file.NvePrintOptionsEditor(opts) { opts = it }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch {
                    val res = runCatching {
                        val src = outputFile() ?: error("no file")
                        val o = opts.copy(range = when (which) { 1 -> (s.currentPage + 1).toString(); 2 -> range; else -> "" })
                        com.smartdocs.editor.ui.nve.file.NvePrint.print(ctx, src, s.password, o, s.pageCount, s.file?.nameWithoutExtension ?: "print")
                    }
                    busy = false
                    res.onSuccess { v.close() }.onFailure { onMessage(it.message ?: ctx.getString(R.string.error_generic)) }
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.print)) }
            // NVE-file (PRN-3): print several files with these options
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { v.close(); v.env?.nav?.navigate(com.smartdocs.editor.ui.Routes.tool("print_batch")) }) { Text(stringResource(R.string.nf_print_other_files)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
