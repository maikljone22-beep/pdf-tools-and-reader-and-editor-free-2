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
    var subset by remember { mutableStateOf(PrintLayout.Subset.ALL) }
    var reverse by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf(true) }
    var perSheet by remember { mutableIntStateOf(1) }
    var booklet by remember { mutableStateOf(false) }
    var rightBinding by remember { mutableStateOf(true) }
    var paper by remember { mutableIntStateOf(0) }          // 0 A4, 1 Letter, 2 A3, 3 A5
    var landscape by remember { mutableStateOf(false) }
    var borders by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // section 7.71 / 7.72: append a comments summary (with its own sort order and options)
    var withSummary by remember { mutableStateOf(false) }
    var sumOpts by remember { mutableStateOf(com.smartdocs.editor.core.annot.SummaryOptions()) }
    val labels = com.smartdocs.editor.ui.reader.annot.summaryLabels()
    val papers = listOf("A4" to PDRectangle.A4, "Letter" to PDRectangle.LETTER, "A3" to PDRectangle.A3, "A5" to PDRectangle.A5)

    ModalBottomSheet(onDismissRequest = { v.close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.v5_print_options), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.v5_pages), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(which == 0, { which = 0 }, label = { Text(stringResource(R.string.v5_all_pages)) })
                FilterChip(which == 1, { which = 1 }, label = { Text(stringResource(R.string.v5_current_page, s.currentPage + 1)) })
                FilterChip(which == 2, { which = 2 }, label = { Text(stringResource(R.string.v5_range)) })
            }
            if (which == 2) OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.v5_range_hint)) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(subset == PrintLayout.Subset.ALL, { subset = PrintLayout.Subset.ALL }, label = { Text(stringResource(R.string.v5_odd_even_all)) })
                FilterChip(subset == PrintLayout.Subset.ODD, { subset = PrintLayout.Subset.ODD }, label = { Text(stringResource(R.string.v5_odd)) })
                FilterChip(subset == PrintLayout.Subset.EVEN, { subset = PrintLayout.Subset.EVEN }, label = { Text(stringResource(R.string.v5_even)) })
                FilterChip(reverse, { reverse = !reverse }, label = { Text(stringResource(R.string.v5_reverse)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(comments, { comments = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.v5_print_comments)) }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(withSummary, { withSummary = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.cx_print_summary)) }
            if (withSummary) com.smartdocs.editor.ui.reader.annot.SummaryOptionsFields(sumOpts, s.pageCount) { sumOpts = it }
            HorizontalDivider()
            Text(stringResource(R.string.v5_layout), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(booklet, { booklet = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.v5_booklet)) }
            if (booklet) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(rightBinding, { rightBinding = true }, label = { Text(stringResource(R.string.v5_bind_right)) })
                FilterChip(!rightBinding, { rightBinding = false }, label = { Text(stringResource(R.string.v5_bind_left)) })
            } else {
                Text(stringResource(R.string.v5_per_sheet), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 4, 6, 9, 16).forEach { n -> FilterChip(perSheet == n, { perSheet = n }, label = { Text(n.toString()) }) }
                }
                if (perSheet > 1) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(borders, { borders = it }); Text(stringResource(R.string.v5_borders)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                papers.forEachIndexed { i, (n, _) -> FilterChip(paper == i, { paper = i }, label = { Text(n) }) }
                if (!booklet) FilterChip(landscape, { landscape = !landscape }, label = { Text(stringResource(R.string.v5_landscape)) })
            }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch {
                    val res = runCatching {
                        val src = outputFile() ?: error("no file")
                        val opts = PrintOptions(
                            range = when (which) { 1 -> (s.currentPage + 1).toString(); 2 -> range; else -> "" },
                            subset = subset, reverse = reverse, perSheet = perSheet, booklet = booklet, rightBinding = rightBinding,
                            paper = papers[paper].second, landscape = landscape, borders = borders, annotations = comments)
                        val app = ctx.applicationContext as SmartDocsApp
                        var file = src; var pages = s.pageCount; var pw = s.password
                        if (!PrintPrep.isPlain(opts, s.pageCount)) {
                            val out = app.documents.newTemp("print")
                            pages = PrintPrep.build(src, s.password, opts, out); file = out; pw = null
                        }
                        if (withSummary) {
                            val sum = app.documents.newTemp("summary")
                            val n = com.smartdocs.editor.core.annot.CommentSummary.build(ctx, src, s.password, sum, labels,
                                pageSize = { s.pageSize(it) }, thumb = { p, w, hh -> runCatching { s.session?.render(p, w, hh, false, true) }.getOrNull() },
                                opts = sumOpts.copy(pageCount = s.pageCount))
                            if (n > 0) {
                                val merged = app.documents.newTemp("print_sum")
                                pages = com.smartdocs.editor.core.annot.CommentSummary.appendSummary(file, pw, sum, merged); file = merged
                            }
                        }
                        PdfPrint.print(ctx, file, s.file?.nameWithoutExtension ?: src.nameWithoutExtension, pages)
                    }
                    busy = false
                    res.onSuccess { v.close() }.onFailure { onMessage(it.message ?: ctx.getString(R.string.error_generic)) }
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.print)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
