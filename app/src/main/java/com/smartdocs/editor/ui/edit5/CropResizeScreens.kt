package com.smartdocs.editor.ui.edit5

import android.graphics.RectF
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.smartdocs.editor.core.edit5.BoxTools
import com.smartdocs.editor.core.edit5.BoxUnit
import com.smartdocs.editor.core.edit5.Margins
import com.smartdocs.editor.core.edit5.PageBoxOps
import com.smartdocs.editor.core.edit5.PageSet
import com.smartdocs.editor.core.edit5.PageSubset
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch

private fun boxColor(k: String) = when (k) {
    "MEDIA" -> Color(0xFF607D8B); "CROP" -> Color(0xFFE91E63); "BLEED" -> Color(0xFF2196F3); "TRIM" -> Color(0xFF4CAF50); else -> Color(0xFFFF9800)
}

private fun boxName(k: String) = when (k) {
    "MEDIA" -> R.string.pc_box_media; "CROP" -> R.string.e5_box_crop; "BLEED" -> R.string.e5_box_bleed; "TRIM" -> R.string.e5_box_trim; else -> R.string.e5_box_art
}

@Composable
private fun UnitChips(unit: BoxUnit, onUnit: (BoxUnit) -> Unit) =
    ChoiceChips(BoxUnit.values().toList(), unit, { stringResource(when (it) { BoxUnit.PT -> R.string.pc_unit_pt; BoxUnit.MM -> R.string.pc_unit_mm; BoxUnit.IN -> R.string.pc_unit_in }) }, onUnit)

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
    var unit by remember { mutableStateOf(BoxUnit.MM) }
    var lockRatio by remember { mutableStateOf(false) }
    var showBoxes by remember { mutableStateOf(false) }
    var boxes by remember { mutableStateOf<Map<String, FloatArray>>(emptyMap()) }
    LaunchedEffect(doc.work, doc.version, page, showBoxes) {
        val w = doc.work
        boxes = if (showBoxes && w != null) runCatching { BoxTools.readBoxes(w, doc.password, page) }.getOrDefault(emptyMap()) else emptyMap()
    }
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
            if (useMargins) {
                UnitChips(unit) { unit = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FloatField(unit.fromPt(margins.right), stringResource(R.string.e5_right), Modifier.weight(1f)) { margins = margins.copy(right = unit.toPt(it)) }
                    FloatField(unit.fromPt(margins.top), stringResource(R.string.e5_top), Modifier.weight(1f)) { margins = margins.copy(top = unit.toPt(it)) }
                    FloatField(unit.fromPt(margins.left), stringResource(R.string.e5_left), Modifier.weight(1f)) { margins = margins.copy(left = unit.toPt(it)) }
                    FloatField(unit.fromPt(margins.bottom), stringResource(R.string.e5_bottom), Modifier.weight(1f)) { margins = margins.copy(bottom = unit.toPt(it)) }
                }
            } else SwitchRow(stringResource(R.string.pc_constrain), lockRatio) { lockRatio = it }
            SwitchRow(stringResource(R.string.pc_show_boxes), showBoxes) { showBoxes = it }
            ChoiceChips(BoxKind.values().toList(), box, { stringResource(when (it) { BoxKind.CROP -> R.string.e5_box_crop; BoxKind.TRIM -> R.string.e5_box_trim; BoxKind.BLEED -> R.string.e5_box_bleed; BoxKind.ART -> R.string.e5_box_art }) }) { box = it }
            RangeSubset(range, { range = it }, subset) { subset = it }
            PrimaryButton(stringResource(R.string.e5_crop_apply), enabled = (useMargins || rect != null), icon = Icons.Default.Crop) {
                val r = if (useMargins) null else rect; val m = if (useMargins) margins else null; val b = box; val t = doc.targets(range, subset)
                run { s, o, p -> PageBoxOps.crop(s, o, p, t, r, m, b) }
            }
            // FINAL_CHECK_2 Low: change the page size (fixed sizes / custom) with the content centred, no scaling
            E5Section(stringResource(R.string.e5_crop_page_size)) {
                var preset by remember { mutableIntStateOf(0) }
                var pw by remember { mutableFloatStateOf(595.28f) }; var ph by remember { mutableFloatStateOf(841.89f) }
                var centerIt by remember { mutableStateOf(true) }
                var land by remember { mutableStateOf(false) }
                val presets = listOf("A4" to (595.28f to 841.89f), "Letter" to (612f to 792f), "Legal" to (612f to 1008f), "A3" to (841.89f to 1190.55f), "A5" to (419.53f to 595.28f), "B5" to (498.9f to 708.66f))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEachIndexed { i, (n, s) -> FilterChip(preset == i, { preset = i; pw = s.first; ph = s.second }, label = { Text(n) }) }
                    FilterChip(preset == presets.size, { preset = presets.size }, label = { Text(stringResource(R.string.pg_size_custom)) })
                }
                if (preset == presets.size) {
                    UnitChips(unit) { unit = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FloatField(unit.fromPt(pw), stringResource(R.string.pg_width), Modifier.weight(1f)) { pw = unit.toPt(it).coerceIn(18f, 14400f) }
                        FloatField(unit.fromPt(ph), stringResource(R.string.pg_height), Modifier.weight(1f)) { ph = unit.toPt(it).coerceIn(18f, 14400f) }
                    }
                }
                SwitchRow(stringResource(R.string.pg_landscape), land) { land = it }
                SwitchRow(stringResource(R.string.e5_crop_center_content), centerIt) { centerIt = it }
                OutlinedButton(onClick = {
                    val t = doc.targets(range, subset); val c = centerIt
                    val (w, h) = if (land == (pw > ph)) pw to ph else ph to pw
                    run { s, o, p -> PageBoxOps.setPageSize(s, o, p, t, w, h, c) }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.e5_crop_page_size_apply)) }
            }
            E5Section(stringResource(R.string.e5_crop_auto)) {
                FloatField(pad, stringResource(R.string.e5_crop_pad)) { pad = it.coerceIn(0f, 72f) }
                SwitchRow(stringResource(R.string.e5_crop_same), same) { same = it }
                OutlinedButton(onClick = {
                    val t = doc.targets(range, subset); val pd = pad; val sm = same
                    busy = 0f
                    scope.launch {
                        var n = 0
                        val r = runCatching { doc.apply(app.documents.tempDir) { s, o, p -> n = PageBoxOps.autoCrop(ctx, s, o, p, t, pd, sm) { busy = it } } }
                        busy = null
                        // audit 5 E-14: "done" only after a success (no "done 0" after the failure message)
                        r.onSuccess { snack.showSnackbar(autoDone.format(n)) }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }
                    }
                }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_crop_auto_run)) }
            }
            OutlinedButton(onClick = { val t = doc.targets(range, subset); run { s, o, p -> PageBoxOps.resetBoxes(s, o, p, t) } }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CropFree, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_crop_reset))
            }
        }, preview = {
            PageStepper(page, doc.pageCount) { page = it; rect = null }
            E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth(), onRect = { d ->
                // constrain proportions: the drawn box keeps the page's width / height ratio
                rect = if (lockRatio) BoxTools.constrain(d.left, d.bottom, d.right, d.top, info.displayW / info.displayH).let { a -> RectF(a[0], a[3], a[2], a[1]) } else d
                useMargins = false
            }) { map ->
                boxes.forEach { (k, a) -> e5Box(map(info.rectToDisplay(RectF(a[0], a[3], a[2], a[1]))), boxColor(k), fill = false) }
                val r = if (useMargins) RectF(margins.left, info.displayH - margins.top, info.displayW - margins.right, margins.bottom) else rect
                r?.let { e5Box(map(it), Color(0xFFE91E63), fill = false) }
            }
            if (boxes.isNotEmpty()) {
                val names = HashMap<String, String>(); boxes.keys.forEach { k -> names[k] = stringResource(boxName(k)) }
                Text(boxes.entries.joinToString("  ·  ") { (k, a) -> "${names[k]} ${"%.1f".format(unit.fromPt(a[2] - a[0]))}×${"%.1f".format(unit.fromPt(a[3] - a[1]))}" },
                    style = MaterialTheme.typography.bodySmall)
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
    val ctx = LocalContext.current
    // audit 5 E-10: locked batch files ask for their password once each
    val unlocker = rememberE5Unlocker()
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    var lock by remember { mutableStateOf(false) }
    var unit by remember { mutableStateOf(BoxUnit.MM) }
    val batch = remember { mutableStateListOf<java.io.File>() }
    val pickBatch = rememberPdfPicker(multiple = true) { fs -> batch += fs.filter { it !in batch } }
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
    ToolScaffold(stringResource(R.string.e5_resize_title), nav, runner, actions = {
        // reset = step back to the size before the last resize
        IconButton(onClick = { scope.launch { doc.undo() } }, enabled = doc.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        E5UnlockDialog(unlocker)
        E5Section(stringResource(R.string.e5_paper)) {
            ChoiceChips(PageBoxOps.PAPERS.map { it.first } + custom, paper, { it }) { p ->
                paper = p; PageBoxOps.PAPERS.firstOrNull { it.first == p }?.second?.let { (a, b) -> w = a; h = b }
            }
            UnitChips(unit) { unit = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatField(unit.fromPt(w), stringResource(R.string.pc_width), Modifier.weight(1f)) { v -> val nw = unit.toPt(v); if (lock) h = BoxTools.lockedHeight(nw, w, h); w = nw; paper = custom }
                FloatField(unit.fromPt(h), stringResource(R.string.pc_height), Modifier.weight(1f)) { v -> val nh = unit.toPt(v); if (lock) w = BoxTools.lockedHeight(nh, h, w); h = nh; paper = custom }
            }
            SwitchRow(stringResource(R.string.pc_constrain), lock) { lock = it }
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
        E5Section(stringResource(R.string.pc_batch_files)) {
            batch.forEach { f ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    IconButton(onClick = { batch.remove(f) }) { Icon(Icons.Default.Close, stringResource(R.string.pc_remove)) }
                }
            }
            OutlinedButton(onClick = pickBatch) { Text(stringResource(R.string.pc_add_pdfs)) }
        }
        val (tw, th) = if (force) (if (landscape) maxOf(w, h) to minOf(w, h) else minOf(w, h) to maxOf(w, h)) else w to h
        val unique: (java.io.File, String) -> java.io.File = { d, b -> app.documents.uniqueFile(d, b) }
        // audit 5 E-9: the document resizes through the working copy and shows the result sheet ("save in original");
        // Undo steps back and "save copy" writes the state after it
        PrimaryButton(stringResource(R.string.apply), enabled = doc.work != null, icon = Icons.Default.AspectRatio) {
            val md = mode; val fo = force; val mg = margin; val rg = range; val sb = subset; val ww = tw; val hh = th
            if (rg.isNotBlank() && PageSet.parse(rg, doc.pageCount).isEmpty()) { runner.error = ctx.getString(R.string.tte5_bad_range); return@PrimaryButton }
            runner.run(processing) {
                val t = doc.targets(rg, sb)
                doc.apply(app.documents.tempDir) { s, o, p -> PageBoxOps.resize(s, o, p, t, ww, hh, md, fo, mg) }
                doc.export(app.documents.docsDir, unique, "resized")
            }
        }
        if (doc.canUndo || doc.changed) OutlinedButton(onClick = {
            runner.run(processing) { doc.export(app.documents.docsDir, unique, "resized") }
        }, enabled = doc.changed, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_save_copy)) }
        // batch: the same size for every page of each extra file; a locked file asks for its password, a failing
        // file is reported and does not stop the others (audit 5 E-10)
        OutlinedButton(onClick = {
            val md = mode; val fo = force; val mg = margin; val rg = range; val sb = subset; val more = batch.toList(); val ww = tw; val hh = th
            scope.launch {
                val skipped = runCatching { unlocker.unlockAll(more) }.getOrElse { emptyList() }
                skipped.forEach { batch.remove(it) }
                val list = more - skipped.toSet()
                if (list.isEmpty()) return@launch
                val pw = unlocker.book.lookup()
                runner.runMany(processing) {
                    val outs = ArrayList<java.io.File>(); val failed = ArrayList<String>()
                    list.forEach { f ->
                        val o = unique(app.documents.docsDir, f.nameWithoutExtension + "_resized")
                        try {
                            val n = com.smartdocs.editor.core.pdf.PdfOps.pageCount(f, pw(f))
                            PageBoxOps.resize(f, o, pw(f), PageSet.filter(PageSet.parse(rg, n), sb), ww, hh, md, fo, mg)
                            outs += o
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { o.delete(); failed += f.name }
                    }
                    if (failed.isNotEmpty()) runner.error = ctx.getString(R.string.tte5_batch_failed, failed.joinToString(", "))
                    outs
                }
            }
        }, enabled = batch.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AspectRatio, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.tte5_resize_batch_run)) }
        com.smartdocs.editor.ui.tools.ResultsList(runner.results, nav, "application/pdf")
    }
}
