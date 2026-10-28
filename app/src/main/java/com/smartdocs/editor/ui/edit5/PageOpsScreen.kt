package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.PageOps5
import com.smartdocs.editor.core.edit5.PageSet
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ResultsList
import com.smartdocs.editor.ui.tools.ToolScaffold
import java.io.File

enum class PageOp { REVERSE, DUPLICATE, REPLACE, SPLIT_PAGE, SMART_SPLIT, INTERLEAVE, COPY_PAGES }

private fun opLabel(o: PageOp) = when (o) {
    PageOp.REVERSE -> R.string.e5_op_reverse; PageOp.DUPLICATE -> R.string.e5_op_duplicate; PageOp.REPLACE -> R.string.e5_op_replace
    PageOp.SPLIT_PAGE -> R.string.e5_op_split_page; PageOp.SMART_SPLIT -> R.string.e5_op_smart_split; PageOp.INTERLEAVE -> R.string.e5_op_interleave; PageOp.COPY_PAGES -> R.string.e5_op_copy_pages
}

private enum class SplitBy { BLANK, MARKER }

/**
 * Page operations that keep bookmarks/links of the kept pages: reverse, duplicate, replace (destinations re-pointed),
 * split one page into parts (lossless crop), smart split at blank pages / text markers, interleave, copy pages.
 */
@Composable
fun PageOpsScreen(nav: NavHostController, uri: String?, initial: PageOp = PageOp.REVERSE) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    var op by remember { mutableStateOf(initial) }
    var range by remember { mutableStateOf("") }
    var times by remember { mutableFloatStateOf(1f) }
    var atEnd by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf<File?>(null) }
    var otherRange by remember { mutableStateOf("") }
    var at by remember { mutableFloatStateOf(1f) }
    var cols by remember { mutableIntStateOf(2) }
    var rows by remember { mutableIntStateOf(1) }
    var rtl by remember { mutableStateOf(true) }
    // cut lines as fractions: columns from the left, rows from the top (any number of tiles)
    var xCuts by remember { mutableStateOf(listOf(0.5f)) }
    var yCuts by remember { mutableStateOf(emptyList<Float>()) }
    var reverseB by remember { mutableStateOf(false) }
    var splitBy by remember { mutableStateOf(SplitBy.BLANK) }
    var marker by remember { mutableStateOf("") }
    var drop by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(0) }
    val unlocker = rememberE5Unlocker()
    // the second file asks for its password once when picked; cancelling clears it
    LaunchedEffect(other) {
        val f = other ?: return@LaunchedEffect
        val ok = runCatching { unlocker.unlock(f) }.getOrElse { runner.error = it.message ?: it.toString(); true }
        if (!ok) other = null
    }
    val pickOther = rememberPdfPicker { other = it.firstOrNull() }
    val processing = stringResource(R.string.processing)
    val n = doc.pageCount
    ToolScaffold(stringResource(R.string.e5_pageops_title), nav, runner) {
        E5FileRow(doc)
        // audit 5 E-17: composed for the whole screen, so switching the operation never hides a pending prompt
        E5UnlockDialog(unlocker)
        ChoiceChips(PageOp.values().toList(), op, { stringResource(opLabel(it)) }) { op = it }
        when (op) {
            PageOp.REVERSE -> E5Section(stringResource(R.string.e5_op_reverse)) {
                OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.e5_all_pages)) })
            }
            PageOp.DUPLICATE -> E5Section(stringResource(R.string.e5_op_duplicate)) {
                OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) })
                FloatField(times, stringResource(R.string.e5_copies)) { times = it.coerceIn(1f, 100f) }
                SwitchRow(stringResource(R.string.e5_copies_at_end), atEnd) { atEnd = it }
            }
            PageOp.REPLACE, PageOp.COPY_PAGES, PageOp.INTERLEAVE -> E5Section(stringResource(opLabel(op))) {
                FilePickRow(other, stringResource(if (op == PageOp.INTERLEAVE) R.string.e5_second_file else R.string.e5_from_file), pickOther)
                other?.takeIf { unlocker.isLocked(it) }?.let { Text(stringResource(R.string.e5_lock_second_ok), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (op != PageOp.INTERLEAVE) OutlinedTextField(otherRange, { otherRange = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_pages_from_other)) }, placeholder = { Text(stringResource(R.string.e5_all_pages)) })
                when (op) {
                    PageOp.REPLACE -> OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_pages_to_replace)) })
                    PageOp.COPY_PAGES -> FloatField(at, stringResource(R.string.e5_insert_before_page, n + 1)) { at = it.coerceIn(1f, (n + 1).toFloat()) }
                    else -> SwitchRow(stringResource(R.string.e5_reverse_second), reverseB) { reverseB = it }
                }
                if (op == PageOp.INTERLEAVE) Text(stringResource(R.string.e5_interleave_help), style = MaterialTheme.typography.bodySmall)
            }
            PageOp.SPLIT_PAGE -> E5Section(stringResource(R.string.e5_op_split_page)) {
                fun grid(c: Int, r: Int) { cols = c; rows = r; xCuts = com.smartdocs.editor.core.edit5.BoxTools.evenCuts(c); yCuts = com.smartdocs.editor.core.edit5.BoxTools.evenCuts(r) }
                ChoiceChips(listOf(2 to 1, 1 to 2, 2 to 2, 3 to 1), cols to rows, { stringResource(when (it) { 2 to 1 -> R.string.e5_split_halves_v; 1 to 2 -> R.string.e5_split_halves_h; 2 to 2 -> R.string.e5_split_quarters; else -> R.string.e5_split_thirds }) }) { grid(it.first, it.second) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatField(cols.toFloat(), stringResource(R.string.pc_columns), Modifier.weight(1f)) { v -> v.toInt().coerceIn(1, 8).let { if (it != cols) grid(it, rows) } }
                    FloatField(rows.toFloat(), stringResource(R.string.pc_rows), Modifier.weight(1f)) { v -> v.toInt().coerceIn(1, 8).let { if (it != rows) grid(cols, it) } }
                }
                xCuts.forEachIndexed { i, f ->
                    Text(stringResource(R.string.pc_cut_v, i + 1, (f * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                    Slider(f, { xCuts = com.smartdocs.editor.core.edit5.BoxTools.moveCut(xCuts, i, it) })
                }
                yCuts.forEachIndexed { i, f ->
                    Text(stringResource(R.string.pc_cut_h, i + 1, (f * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                    Slider(f, { yCuts = com.smartdocs.editor.core.edit5.BoxTools.moveCut(yCuts, i, it) })
                }
                SwitchRow(stringResource(R.string.e5_rtl_order), rtl) { rtl = it }
                OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.e5_all_pages)) })
                val info = doc.infos.getOrNull(page)
                if (doc.work != null && info != null) {
                    PageStepper(page, n) { page = it }
                    E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth(0.7f)) { map ->
                        val full = map(android.graphics.RectF(0f, info.displayH, info.displayW, 0f))
                        val xs = xCuts; val ys = yCuts
                        xs.forEach { f -> drawLine(Color.Red, androidx.compose.ui.geometry.Offset(full.left + full.width * f, 0f), androidx.compose.ui.geometry.Offset(full.left + full.width * f, size.height), 3f) }
                        ys.forEach { f -> drawLine(Color.Red, androidx.compose.ui.geometry.Offset(0f, full.top + full.height * f), androidx.compose.ui.geometry.Offset(size.width, full.top + full.height * f), 3f) }
                    }
                }
            }
            PageOp.SMART_SPLIT -> E5Section(stringResource(R.string.e5_op_smart_split)) {
                ChoiceChips(SplitBy.values().toList(), splitBy, { stringResource(if (it == SplitBy.BLANK) R.string.e5_split_blank else R.string.e5_split_marker) }) { splitBy = it }
                if (splitBy == SplitBy.MARKER) OutlinedTextField(marker, { marker = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_marker_text)) })
                SwitchRow(stringResource(R.string.e5_drop_separators), drop) { drop = it }
            }
        }
        val w = doc.work
        val otherReady = other?.let { unlocker.has(it) } == true
        val ready = w != null && when (op) { PageOp.REPLACE -> otherReady && range.isNotBlank(); PageOp.COPY_PAGES, PageOp.INTERLEAVE -> otherReady; PageOp.SMART_SPLIT -> splitBy == SplitBy.BLANK || marker.isNotBlank(); PageOp.DUPLICATE -> range.isNotBlank(); else -> true }
        PrimaryButton(stringResource(R.string.e5_run), enabled = ready, icon = Icons.Default.PlayArrow) {
            val o = op; val rg = range; val tm = times.toInt(); val ae = atEnd; val of = other; val orr = otherRange; val a = at.toInt() - 1
            val c = cols; val r = rows; val rt = rtl; val xcs = xCuts; val ycs = yCuts; val rb = reverseB; val sb = splitBy; val mk = marker; val dr = drop
            val opw = of?.let { unlocker.password(it) }
            val sel = PageSet.parse(rg, n)
            // audit 5 E-15: a range that selects nothing is an error, not a "success" with an unchanged copy
            val usesRange = o in setOf(PageOp.REVERSE, PageOp.DUPLICATE, PageOp.REPLACE, PageOp.SPLIT_PAGE)
            if (usesRange && rg.isNotBlank() && sel.isEmpty()) { runner.error = ctx.getString(R.string.tte5_bad_range); return@PrimaryButton }
            if (o == PageOp.SMART_SPLIT) runner.runMany(processing) {
                val src = doc.work!!
                val seps = if (sb == SplitBy.BLANK) PageOps5.blankPages(ctx, src, doc.password) else PageOps5.markerPages(ctx, src, doc.password, mk)
                PageOps5.splitAt(src, doc.password, seps, dr && sb == SplitBy.BLANK, app.documents.docsDir, (doc.source?.nameWithoutExtension ?: "part") + "_part")
            } else runner.run(processing) {
                doc.apply(app.documents.tempDir) { s, out, p ->
                    when (o) {
                        PageOp.REVERSE -> PageOps5.reverse(s, out, p, if (rg.isBlank()) null else sel)
                        PageOp.DUPLICATE -> PageOps5.duplicate(s, out, p, sel, tm, ae)
                        PageOp.REPLACE -> {
                            val oc = PdfOps.pageCount(of!!, opw); val with = PageSet.parse(orr, oc)
                            // audit 5 E-15: every target page needs a replacement (extra replacement pages are inserted after the last target)
                            if (orr.isNotBlank() && with.isEmpty()) throw IllegalArgumentException(ctx.getString(R.string.tte5_bad_range_other))
                            if (with.size < sel.size) throw IllegalArgumentException(ctx.getString(R.string.tte5_replace_count, sel.size, with.size))
                            PageOps5.replace(s, out, p, sel, of, opw, with)
                        }
                        PageOp.COPY_PAGES -> {
                            val oc = PdfOps.pageCount(of!!, opw); val pagesB = PageSet.parse(orr, oc)
                            if (orr.isNotBlank() && pagesB.isEmpty()) throw IllegalArgumentException(ctx.getString(R.string.tte5_bad_range_other))
                            PageOps5.copyPages(s, out, p, of, opw, pagesB, a)
                        }
                        PageOp.INTERLEAVE -> PageOps5.interleave(s, of!!, out, p, opw, rb)
                        PageOp.SPLIT_PAGE -> {
                            // splitParts takes row cuts from the bottom of the page
                            PageOps5.splitPages(s, out, p, sel, c, r, rt, xcs, ycs.map { 1f - it })
                        }
                        PageOp.SMART_SPLIT -> Unit
                    }
                }
                doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, o.name.lowercase())
            }
        }
        ResultsList(runner.results, nav, "application/pdf")
    }
}
