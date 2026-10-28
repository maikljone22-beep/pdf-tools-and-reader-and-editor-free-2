package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.ImposeOps
import com.smartdocs.editor.core.edit5.ImposeOrder
import com.smartdocs.editor.core.edit5.OutProtection
import kotlinx.coroutines.launch
import com.smartdocs.editor.core.edit5.PageBoxOps
import com.smartdocs.editor.core.edit5.PageSet
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import com.smartdocs.editor.ui.tools.rememberInputFile
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker

private enum class ImposeMode { NUP, BOOKLET, POSTER }

/** N-up (2–16 per sheet), saddle-stitch booklet (right or left binding) and poster tiles, into a new PDF. */
@Composable
fun ImposeScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    val unlocker = rememberE5Unlocker()
    val scope = rememberCoroutineScope()
    var protection by remember { mutableStateOf(OutProtection.KEEP) }
    // a locked source asks for its password once when picked; cancelling clears the choice
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        val ok = runCatching { unlocker.unlock(f) }.getOrElse { runner.error = it.message ?: it.toString(); true }
        if (!ok) file = null
    }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    var mode by remember { mutableStateOf(ImposeMode.NUP) }
    var n by remember { mutableIntStateOf(2) }
    var paperName by remember { mutableStateOf("A4") }
    var landscape by remember { mutableStateOf(true) }
    var rtl by remember { mutableStateOf(true) }
    var colFirst by remember { mutableStateOf(false) }
    var borders by remember { mutableStateOf(false) }
    var margin by remember { mutableFloatStateOf(18f) }
    var gap by remember { mutableFloatStateOf(8f) }
    var creep by remember { mutableFloatStateOf(0f) }
    var range by remember { mutableStateOf("") }
    var scale by remember { mutableFloatStateOf(2f) }
    var overlap by remember { mutableFloatStateOf(18f) }
    var marks by remember { mutableStateOf(true) }
    val auto = stringResource(R.string.e5_paper_auto)
    val processing = stringResource(R.string.processing)
    fun paper(): Pair<Float, Float>? = PageBoxOps.PAPERS.firstOrNull { it.first == paperName }?.second
    ToolScaffold(stringResource(R.string.e5_impose_title), nav, runner) {
        FilePickRow(file, stringResource(R.string.e5_document), pick)
        E5UnlockDialog(unlocker)
        file?.takeIf { unlocker.isLocked(it) }?.let { f -> ProtectionChoice(protection, unlocker.canRemove(f)) { protection = it } }
        ChoiceChips(ImposeMode.values().toList(), mode, { stringResource(when (it) { ImposeMode.NUP -> R.string.e5_imp_nup; ImposeMode.BOOKLET -> R.string.e5_imp_booklet; ImposeMode.POSTER -> R.string.e5_imp_poster }) }) { mode = it }
        E5Section(stringResource(R.string.e5_paper)) {
            val names = (if (mode == ImposeMode.POSTER) emptyList() else listOf(auto)) + PageBoxOps.PAPERS.map { it.first }
            LaunchedEffect(mode) { if (mode == ImposeMode.POSTER && paperName == auto) paperName = "A4" }
            ChoiceChips(names, paperName, { it }) { paperName = it }
            if (mode != ImposeMode.BOOKLET) SwitchRow(stringResource(R.string.e5_landscape), landscape) { landscape = it }
            FloatField(margin, stringResource(R.string.e5_margin)) { margin = it.coerceIn(0f, 100f) }
        }
        when (mode) {
            ImposeMode.NUP -> E5Section(stringResource(R.string.e5_imp_nup)) {
                ChoiceChips(listOf(2, 4, 6, 8, 9, 16), n, { it.toString() }) { n = it }
                SwitchRow(stringResource(R.string.e5_rtl_order), rtl) { rtl = it }
                SwitchRow(stringResource(R.string.e5_column_first), colFirst) { colFirst = it }
                SwitchRow(stringResource(R.string.e5_borders), borders) { borders = it }
                FloatField(gap, stringResource(R.string.e5_gap)) { gap = it.coerceIn(0f, 72f) }
                OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) })
                val (c, r) = ImposeOrder.gridFor(n, landscape)
                NUpDiagram(c, r, rtl, colFirst)
            }
            ImposeMode.BOOKLET -> E5Section(stringResource(R.string.e5_imp_booklet)) {
                SwitchRow(stringResource(R.string.e5_right_binding), rtl) { rtl = it }
                FloatField(creep, stringResource(R.string.e5_creep)) { creep = it.coerceIn(0f, 20f) }
                Text(stringResource(R.string.e5_booklet_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ImposeMode.POSTER -> E5Section(stringResource(R.string.e5_imp_poster)) {
                Text(stringResource(R.string.e5_poster_scale, (scale * 100).toInt()))
                Slider(scale, { scale = it }, valueRange = 1.25f..6f)
                FloatField(overlap, stringResource(R.string.e5_overlap)) { overlap = it.coerceIn(0f, 72f) }
                SwitchRow(stringResource(R.string.e5_cut_marks), marks) { marks = it }
                OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) })
            }
        }
        PrimaryButton(stringResource(R.string.e5_impose_run), enabled = file != null, icon = Icons.Default.ViewModule) {
            val src = file ?: return@PrimaryButton
            val p = paper(); val m = mode; val nn = n; val ls = landscape; val r = rtl; val cf = colFirst; val b = borders; val mg = margin; val g = gap; val cr = creep; val rg = range
            val sc = scale; val ov = overlap; val mk = marks; val pr = protection
            scope.launch {
                if (!runCatching { unlocker.unlock(src) }.getOrElse { runner.error = it.message ?: it.toString(); return@launch }) return@launch
                val pw = unlocker.password(src)
                runner.run(processing) {
                    val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_" + m.name.lowercase())
                    val count = com.smartdocs.editor.core.pdf.PdfOps.pageCount(src, pw)
                    val pages = if (rg.isBlank()) null else PageSet.parse(rg, count)
                    when (m) {
                        ImposeMode.NUP -> { val (c, rr) = ImposeOrder.gridFor(nn, ls); ImposeOps.nUp(src, out, pw, c, rr, p, ls, r, cf, mg, g, b, pages, pr) }
                        ImposeMode.BOOKLET -> ImposeOps.booklet(src, out, pw, r, p, mg, cr, pr)
                        ImposeMode.POSTER -> ImposeOps.poster(src, out, pw, pages ?: listOf(0), sc, p ?: (595.28f to 841.89f), ls, ov, mk, mg, pr)
                    }
                    out
                }
            }
        }
    }
}

/** Small numbered grid showing the reading order of one N-up sheet. */
@Composable
private fun NUpDiagram(cols: Int, rows: Int, rtl: Boolean, colFirst: Boolean) {
    val cells = ImposeOrder.nUp(cols * rows, cols, rows, rtl, colFirst).firstOrNull().orEmpty()
    Column(Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in 0 until rows) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // draw left to right regardless of the UI direction so the diagram matches the printed sheet
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in 0 until cols) {
                        val p = cells.firstOrNull { it.col == c && it.row == r }?.page
                        Box(Modifier.size(28.dp, 36.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            Text(((p ?: -1) + 1).toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
