package com.smartdocs.editor.ui.edit5

import android.graphics.RectF
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
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
import com.smartdocs.editor.core.edit5.BoxKind
import com.smartdocs.editor.core.edit5.Margins
import com.smartdocs.editor.core.edit5.PageBoxOps
import com.smartdocs.editor.core.edit5.PageSet
import com.smartdocs.editor.core.edit5.PageSubset
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch

private fun E5Doc.targets(range: String, subset: PageSubset): List<Int> = PageSet.filter(PageSet.parse(range, pageCount), subset) { infos.getOrNull(it)?.landscape == true }

/** Real crop (CropBox / Trim / Bleed / Art) by drawing or margins, automatic white-margin removal, reset. */
@Composable
fun CropScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var page by remember { mutableIntStateOf(0) }
    var rect by remember { mutableStateOf<RectF?>(null) }
    var margins by remember { mutableStateOf(Margins()) }
    var useMargins by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf(BoxKind.CROP) }
    var range by remember { mutableStateOf("") }
    var subset by remember { mutableStateOf(PageSubset.ALL) }
    var pad by remember { mutableFloatStateOf(6f) }
    var same by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Float?>(null) }
    val processing = stringResource(R.string.processing)
    val autoDone = stringResource(R.string.e5_crop_auto_done)
    fun run(op: suspend (java.io.File, java.io.File, String?) -> Unit) {
        busy = 0f
        scope.launch { runCatching { doc.apply(app.documents.tempDir, op) }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }; busy = null; rect = null }
    }
    ToolScaffold(stringResource(R.string.e5_crop_title), nav, runner, snackbar = snack, actions = {
        IconButton(onClick = { scope.launch { doc.undo() } }, enabled = doc.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        val info = doc.infos.getOrNull(page)
        if (doc.work != null && info != null) E5TwoPane(options = {
            Text(stringResource(R.string.e5_crop_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow(stringResource(R.string.e5_crop_by_margins), useMargins) { useMargins = it }
            if (useMargins) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FloatField(margins.right, stringResource(R.string.e5_right), Modifier.weight(1f)) { margins = margins.copy(right = it) }
                FloatField(margins.top, stringResource(R.string.e5_top), Modifier.weight(1f)) { margins = margins.copy(top = it) }
                FloatField(margins.left, stringResource(R.string.e5_left), Modifier.weight(1f)) { margins = margins.copy(left = it) }
                FloatField(margins.bottom, stringResource(R.string.e5_bottom), Modifier.weight(1f)) { margins = margins.copy(bottom = it) }
            }
            ChoiceChips(BoxKind.values().toList(), box, { stringResource(when (it) { BoxKind.CROP -> R.string.e5_box_crop; BoxKind.TRIM -> R.string.e5_box_trim; BoxKind.BLEED -> R.string.e5_box_bleed; BoxKind.ART -> R.string.e5_box_art }) }) { box = it }
            RangeSubset(range, { range = it }, subset) { subset = it }
            PrimaryButton(stringResource(R.string.e5_crop_apply), enabled = (useMargins || rect != null), icon = Icons.Default.Crop) {
                val r = if (useMargins) null else rect; val m = if (useMargins) margins else null; val b = box; val t = doc.targets(range, subset)
                run { s, o, p -> PageBoxOps.crop(s, o, p, t, r, m, b) }
            }
            E5Section(stringResource(R.string.e5_crop_auto)) {
                FloatField(pad, stringResource(R.string.e5_crop_pad)) { pad = it.coerceIn(0f, 72f) }
                SwitchRow(stringResource(R.string.e5_crop_same), same) { same = it }
                OutlinedButton(onClick = {
                    val t = doc.targets(range, subset); val pd = pad; val sm = same
                    busy = 0f
                    scope.launch {
                        var n = 0
                        runCatching { doc.apply(app.documents.tempDir) { s, o, p -> n = PageBoxOps.autoCrop(ctx, s, o, p, t, pd, sm) { busy = it } } }
                            .onFailure { snack.showSnackbar(it.message ?: it.toString()) }
                        busy = null; snack.showSnackbar(autoDone.format(n))
                    }
                }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_crop_auto_run)) }
            }
            OutlinedButton(onClick = { val t = doc.targets(range, subset); run { s, o, p -> PageBoxOps.resetBoxes(s, o, p, t) } }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CropFree, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_crop_reset))
            }
        }, preview = {
            PageStepper(page, doc.pageCount) { page = it; rect = null }
            E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth(), onRect = { rect = it; useMargins = false }) { map ->
                val r = if (useMargins) RectF(margins.left, info.displayH - margins.top, info.displayW - margins.right, margins.bottom) else rect
                r?.let { e5Box(map(it), Color(0xFFE91E63), fill = false) }
            }
            Text(stringResource(R.string.e5_page_size_pt, info.displayW.toInt(), info.displayH.toInt()), style = MaterialTheme.typography.bodySmall)
        })
        PrimaryButton(stringResource(R.string.e5_save_copy), enabled = doc.changed, icon = Icons.Default.Save) {
            runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "cropped") }
        }
    }
    busy?.let { ProgressDialog(processing, it.takeIf { p -> p > 0f }) }
}

/** Resize pages to a paper size (content scaled to fit / fill / centred / stretched). */
@Composable
fun ResizeScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    var paper by remember { mutableStateOf("A4") }
    var w by remember { mutableFloatStateOf(595.28f) }
    var h by remember { mutableFloatStateOf(841.89f) }
    var mode by remember { mutableStateOf(PageBoxOps.ResizeMode.FIT) }
    var force by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var margin by remember { mutableFloatStateOf(0f) }
    var range by remember { mutableStateOf("") }
    var subset by remember { mutableStateOf(PageSubset.ALL) }
    val processing = stringResource(R.string.processing)
    val custom = stringResource(R.string.e5_custom)
    ToolScaffold(stringResource(R.string.e5_resize_title), nav, runner) {
        E5FileRow(doc)
        E5Section(stringResource(R.string.e5_paper)) {
            ChoiceChips(PageBoxOps.PAPERS.map { it.first } + custom, paper, { it }) { p ->
                paper = p; PageBoxOps.PAPERS.firstOrNull { it.first == p }?.second?.let { (a, b) -> w = a; h = b }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatField(w / 72f * 25.4f, stringResource(R.string.e5_width_mm), Modifier.weight(1f)) { w = it * 72f / 25.4f; paper = custom }
                FloatField(h / 72f * 25.4f, stringResource(R.string.e5_height_mm), Modifier.weight(1f)) { h = it * 72f / 25.4f; paper = custom }
            }
            SwitchRow(stringResource(R.string.e5_force_orientation), force) { force = it }
            if (force) SwitchRow(stringResource(R.string.e5_landscape), landscape) { landscape = it }
        }
        E5Section(stringResource(R.string.e5_content)) {
            ChoiceChips(PageBoxOps.ResizeMode.values().toList(), mode, { stringResource(when (it) {
                PageBoxOps.ResizeMode.FIT -> R.string.e5_rs_fit; PageBoxOps.ResizeMode.FILL -> R.string.e5_rs_fill
                PageBoxOps.ResizeMode.CENTER -> R.string.e5_rs_center; PageBoxOps.ResizeMode.STRETCH -> R.string.e5_rs_stretch }) }) { mode = it }
            FloatField(margin, stringResource(R.string.e5_margin)) { margin = it.coerceAtLeast(0f) }
            RangeSubset(range, { range = it }, subset) { subset = it }
        }
        PrimaryButton(stringResource(R.string.apply), enabled = doc.work != null, icon = Icons.Default.AspectRatio) {
            val t = doc.targets(range, subset)
            val (tw, th) = if (force) (if (landscape) maxOf(w, h) to minOf(w, h) else minOf(w, h) to maxOf(w, h)) else w to h
            val md = mode; val fo = force; val mg = margin
            runner.run(processing) {
                doc.apply(app.documents.tempDir) { s, o, p -> PageBoxOps.resize(s, o, p, t, tw, th, md, fo, mg) }
                doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "resized")
            }
        }
    }
}
