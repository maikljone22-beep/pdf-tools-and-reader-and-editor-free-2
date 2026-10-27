package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.E5Unit
import com.smartdocs.editor.core.edit5.HFSpec
import com.smartdocs.editor.core.edit5.HeaderFooterOps
import com.smartdocs.editor.core.edit5.TokenContext
import com.smartdocs.editor.core.edit5.Tokens
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.ResultsList
import kotlinx.coroutines.launch
import com.smartdocs.editor.ui.tools.ColorChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold

internal val TEXT_COLORS = listOf(0x212121, 0x616161, 0x9E9E9E, 0x1565C0, 0xC62828, 0x2E7D32)

/** Presets: (label, spec transform). */
private data class HFPreset(val label: Int, val apply: (HFSpec, String) -> HFSpec)

private val PRESETS = listOf(
    HFPreset(R.string.e5_hf_p_page) { it, _ -> it.copy(topLeft = "", topCenter = "", topRight = "", bottomLeft = "", bottomCenter = "{page}", bottomRight = "") },
    HFPreset(R.string.e5_hf_p_page_of) { it, pageOf -> it.copy(bottomCenter = pageOf, bottomLeft = "", bottomRight = "") },
    HFPreset(R.string.e5_hf_p_title_date) { it, _ -> it.copy(topRight = "{title}", topLeft = "{date}", bottomCenter = "{page}") },
    HFPreset(R.string.e5_hf_p_hijri) { it, _ -> it.copy(topRight = "{hijri}", topLeft = "{date}", bottomCenter = "{page}") },
    HFPreset(R.string.e5_hf_p_book) { it, _ -> it.copy(topCenter = "{title}", bottomRight = "{page}", bottomCenter = "", mirrorEven = true) },
)

/** Header & footer: six cells with tokens, fonts, margins, Hijri dates; update or remove later (tagged content). */
@Composable
fun HeaderFooterScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    var spec by remember { mutableStateOf(HFSpec(user = settings.authorName)) }
    var existing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var page by remember { mutableIntStateOf(0) }
    var batch by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    val unlocker = rememberE5Unlocker()
    val scope = rememberCoroutineScope()
    fun unlockBatch() = scope.launch { val skipped = runCatching { unlocker.unlockAll(batch) }.getOrDefault(emptyList()); if (skipped.isNotEmpty()) batch = batch - skipped.toSet() }
    val output = remember { com.smartdocs.editor.ui.tools5.OptimizeBatch().apply { suffix = "_header" } }
    var logFile by remember { mutableStateOf<java.io.File?>(null) }
    val pickImage = rememberImagePicker(multiple = false) { it.firstOrNull()?.let { f -> spec = spec.copy(imagePath = f.absolutePath, imagePage = 0) } }
    val pickPdf = rememberPdfPicker { it.firstOrNull()?.let { f -> spec = spec.copy(imagePath = f.absolutePath, imagePage = 0) } }
    val processing = stringResource(R.string.processing)
    LaunchedEffect(doc.work) {
        val w = doc.work ?: return@LaunchedEffect
        runCatching { HeaderFooterOps.stored(w, doc.password) }.getOrNull()?.let { spec = it.copy(user = settings.authorName) }
        existing = runCatching { HeaderFooterOps.detect(w, doc.password) }.getOrDefault(emptySet())
    }
    ToolScaffold(stringResource(R.string.e5_hf_title), nav, runner) {
        E5FileRow(doc)
        if (existing.isNotEmpty()) Text(stringResource(if ("hf" in existing) R.string.e5_hf_found_ours else R.string.e5_hf_found_other), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        E5ProfileBar("hf", current = { spec.toJson() }, onLoad = { j -> HFSpec.fromJson(j)?.let { spec = it.copy(user = settings.authorName) } }, onMessage = { runner.error = it })
        E5TwoPane(options = {
            E5Section(stringResource(R.string.e5_presets)) {
                val pageOf = stringResource(R.string.e5_hf_tpl_page_of)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PRESETS.forEach { p -> AssistChip(onClick = { spec = p.apply(spec, pageOf) }, label = { Text(stringResource(p.label)) }) } }
            }
            E5Section(stringResource(R.string.e5_hf_header)) {
                CellRow(spec.topRight, spec.topCenter, spec.topLeft) { r, c, l -> spec = spec.copy(topRight = r, topCenter = c, topLeft = l) }
            }
            E5Section(stringResource(R.string.e5_hf_footer)) {
                CellRow(spec.bottomRight, spec.bottomCenter, spec.bottomLeft) { r, c, l -> spec = spec.copy(bottomRight = r, bottomCenter = c, bottomLeft = l) }
            }
            TokenHelp()
            val cellsText = listOf(spec.topLeft, spec.topCenter, spec.topRight, spec.bottomLeft, spec.bottomCenter, spec.bottomRight)
            if (cellsText.any { com.smartdocs.editor.core.edit5.Tokens.usesBates(it) }) E5Section(stringResource(R.string.nt_hf_bates_format)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(spec.bates.prefix, { spec = spec.copy(bates = spec.bates.copy(prefix = it)) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.e5_prefix)) })
                    OutlinedTextField(spec.bates.suffix, { spec = spec.copy(bates = spec.bates.copy(suffix = it)) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.e5_suffix)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatField(spec.bates.digits.toFloat(), stringResource(R.string.e5_digits), Modifier.weight(1f)) { spec = spec.copy(bates = spec.bates.copy(digits = it.toInt().coerceIn(1, 12))) }
                    FloatField(spec.bates.start.toFloat(), stringResource(R.string.e5_start_number), Modifier.weight(1f)) { spec = spec.copy(bates = spec.bates.copy(start = it.toLong().coerceAtLeast(0))) }
                }
                Text(stringResource(R.string.e5_example, com.smartdocs.editor.core.edit5.Bates.format(spec.bates, spec.bates.start)), style = MaterialTheme.typography.bodySmall)
            }
            E5Section(stringResource(R.string.nt_hf_picture)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = pickImage, Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.e5_src_image), maxLines = 1) }
                    OutlinedButton(onClick = pickPdf, Modifier.weight(1f)) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nt_src_pdf), maxLines = 1) }
                }
                if (spec.imagePath.isNotBlank()) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(java.io.File(spec.imagePath).name, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { spec = spec.copy(imagePath = "") }) { Icon(Icons.Default.Close, null) }
                    }
                    if (spec.imagePath.endsWith(".pdf", true)) FloatField((spec.imagePage + 1).toFloat(), stringResource(R.string.nt_pdf_page_no)) { spec = spec.copy(imagePage = (it.toInt() - 1).coerceAtLeast(0)) }
                    ChoiceChips((0..5).toList(), spec.imageSlot, { stringResource(slotLabel(com.smartdocs.editor.core.edit5.HeaderFooterOps.Slot.values()[it])) }) { spec = spec.copy(imageSlot = it) }
                    UnitField(spec.imageHeight, spec.unit, stringResource(R.string.nt_picture_height)) { spec = spec.copy(imageHeight = it.coerceAtLeast(4f)) }
                }
            }
            E5Section(stringResource(R.string.e5_text_style)) {
                FontChoice(spec.style.font) { spec = spec.copy(style = spec.style.copy(font = it)) }
                Text(stringResource(R.string.e5_font_size, spec.style.size.toInt()))
                Slider(spec.style.size, { spec = spec.copy(style = spec.style.copy(size = it)) }, valueRange = 6f..24f)
                ColorChips(TEXT_COLORS, spec.style.color) { spec = spec.copy(style = spec.style.copy(color = it)) }
                SwitchRow(stringResource(R.string.e5_bold), spec.style.bold) { spec = spec.copy(style = spec.style.copy(bold = it)) }
                SwitchRow(stringResource(R.string.e5_underline), spec.style.underline) { spec = spec.copy(style = spec.style.copy(underline = it)) }
                SwitchRow(stringResource(R.string.nt_italic), spec.style.italic) { spec = spec.copy(style = spec.style.copy(italic = it)) }
                Text(stringResource(R.string.e5_opacity, (spec.opacity * 100).toInt()))
                Slider(spec.opacity, { spec = spec.copy(opacity = it) }, valueRange = 0.05f..1f)
                Text(stringResource(R.string.e5_rotation, spec.rotation.toInt()))
                Slider(spec.rotation, { spec = spec.copy(rotation = it) }, valueRange = -90f..90f)
                SwitchRow(stringResource(R.string.e5_behind), spec.behind) { spec = spec.copy(behind = it) }
            }
            E5Section(stringResource(R.string.e5_layout)) {
                MarginsEditor(spec.unit, spec.marginTop, spec.marginBottom, spec.marginSide, spec.rightMargin, onUnit = { spec = spec.copy(unit = it) }) { t, b, l, r ->
                    spec = spec.copy(marginTop = t, marginBottom = b, marginSide = l, marginRight = r)
                }
                SwitchRow(stringResource(R.string.nt_grow_page), spec.growPage) { spec = spec.copy(growPage = it) }
                FloatField(spec.startNumber.toFloat(), stringResource(R.string.e5_start_number)) { spec = spec.copy(startNumber = it.toInt().coerceAtLeast(0)) }
                SwitchRow(stringResource(R.string.e5_arabic_digits), spec.arabicDigits) { spec = spec.copy(arabicDigits = it) }
                SwitchRow(stringResource(R.string.e5_month_names_ar), spec.arabicNames) { spec = spec.copy(arabicNames = it) }
                SwitchRow(stringResource(R.string.e5_umm_al_qura), spec.ummAlQura) { spec = spec.copy(ummAlQura = it) }
                SwitchRow(stringResource(R.string.e5_mirror_even), spec.mirrorEven) { spec = spec.copy(mirrorEven = it) }
                Text(stringResource(R.string.e5_shrink, (spec.shrink * 100).toInt()))
                Slider(spec.shrink, { spec = spec.copy(shrink = it) }, valueRange = 0f..0.2f)
                RangeSubset(spec.range, { spec = spec.copy(range = it) }, spec.subset) { spec = spec.copy(subset = it) }
                EveryNPages(spec.everyStart, spec.everyN) { st, n -> spec = spec.copy(everyStart = st, everyN = n) }
                SwitchRow(stringResource(R.string.nt_ignore_rotation), spec.ignoreRotation) { spec = spec.copy(ignoreRotation = it) }
            }
            E5Section(stringResource(R.string.nt_hf_batch, batch.size)) {
                Text(stringResource(R.string.nt_hf_batch_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                batch.forEachIndexed { i, f ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("${i + 2}. ${f.name}", Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { batch = batch - f; unlocker.forget(f) }) { Icon(Icons.Default.Close, null) }
                    }
                }
                com.smartdocs.editor.ui.tools5.BatchSources(batch) { batch = it; unlockBatch() }
                if (batch.isNotEmpty()) com.smartdocs.editor.ui.tools5.BatchOutputOptions(output)
            }
        }, preview = {
            val info = doc.infos.getOrNull(page)
            if (doc.work != null && info != null) {
                PageStepper(page, doc.pageCount) { page = it }
                val tctx = TokenContext(page = spec.startNumber + page, pages = doc.pageCount + spec.startNumber - 1, file = doc.source?.name ?: "", user = spec.user, arabic = spec.arabicNames, ummAlQura = spec.ummAlQura,
                    path = doc.source?.absolutePath ?: "", bates = com.smartdocs.editor.core.edit5.Bates.format(spec.bates, spec.bates.start + page), batesTotal = doc.pageCount.toString())
                Box {
                    E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth()) { map ->
                        val s = map(android.graphics.RectF(0f, info.displayH, info.displayW, 0f)).width / info.displayW
                        val top = spec.marginTop * s; val bottom = size.height - spec.marginBottom * s
                        drawLine(Color(0x552196F3), androidx.compose.ui.geometry.Offset(0f, top + spec.style.size * s), androidx.compose.ui.geometry.Offset(size.width, top + spec.style.size * s))
                        drawLine(Color(0x552196F3), androidx.compose.ui.geometry.Offset(0f, bottom - spec.style.size * s), androidx.compose.ui.geometry.Offset(size.width, bottom - spec.style.size * s))
                    }
                }
                listOf(spec.topRight, spec.topCenter, spec.topLeft, spec.bottomRight, spec.bottomCenter, spec.bottomLeft).filter { it.isNotBlank() }.forEach {
                    Text("• " + Tokens.expand(it, tctx, spec.arabicDigits), style = MaterialTheme.typography.bodySmall)
                }
            }
        })
        E5UnlockDialog(unlocker)
        val w = doc.work
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                runner.run(processing) {
                    doc.apply(app.documents.tempDir) { s, o, p -> HeaderFooterOps.remove(s, o, p, others = true) }
                    doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "no_header")
                }
            }, enabled = w != null && existing.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_remove)) }
            // Foxit batch "Remove All" with a log file
            if (batch.isNotEmpty()) OutlinedButton(onClick = {
                val extra = batch; val title = ctx.getString(R.string.nt_batch_remove_all)
                runner.runMany(processing) {
                    val book = unlocker.book.lookup()
                    val (outs, log) = com.smartdocs.editor.ui.tools5.runBatchWithLog(ctx, app.documents.docsDir, { d, b, e -> app.documents.uniqueFile(d, b, e) }, extra,
                        com.smartdocs.editor.ui.tools5.OptimizeBatch().apply { suffix = "_no_header"; folder = output.folder; replaceSameName = output.replaceSameName }, title) { s, o ->
                        "${HeaderFooterOps.remove(s, o, book(s), others = true)} p."
                    }
                    logFile = log; outs
                }
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.LayersClear, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nt_batch_remove_all)) }
        }
        PrimaryButton(stringResource(if ("hf" in existing) R.string.e5_update else R.string.apply), enabled = w != null, icon = Icons.Default.ViewHeadline) {
            val sp = spec
            val src = doc.source
            if (batch.isEmpty()) runner.run(processing) {
                doc.apply(app.documents.tempDir) { s, o, p -> HeaderFooterOps.apply(ctx, s, o, p, sp, replace = true, fileName = src?.name ?: s.name, path = src?.absolutePath ?: "") }
                doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "header")
            } else {
                val work = w!!; val extra = batch; val pw = doc.password
                val suffix = output.suffix.ifBlank { "_header" }
                runner.runMany(processing) {
                    val book = unlocker.book.lookup()
                    val outs = HeaderFooterOps.applyBatch(ctx, listOf(work) + extra,
                        { f -> app.documents.uniqueFile(app.documents.docsDir, (if (f == work) src?.nameWithoutExtension ?: "document" else f.nameWithoutExtension) + suffix) },
                        sp, passwords = { f -> if (f == work) pw else book(f) }, sourceOf = { f -> if (f == work) src ?: f else f })
                    output.folder?.let { com.smartdocs.editor.ui.tools5.saveToTree(ctx, it, outs, output.replaceSameName) }
                    outs
                }
            }
        }
        ResultsList(runner.results, nav, "application/pdf")
        logFile?.let { Text(stringResource(R.string.nt_bates_log_saved, it.name), style = MaterialTheme.typography.bodySmall) }
    }
}

/** Right / centre / left cells (Arabic reading order: right first). */
@Composable
private fun CellRow(right: String, center: String, left: String, onChange: (String, String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(right, { onChange(it, center, left) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_cell_right)) })
        OutlinedTextField(center, { onChange(right, it, left) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_cell_center)) })
        OutlinedTextField(left, { onChange(right, center, it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_cell_left)) })
    }
}

@Composable
internal fun TokenHelp() {
    Text(stringResource(R.string.e5_tokens_help) + "  " + Tokens.ALL.joinToString(" ") + "  {date:yyyy-MM-dd} {hijri:d MMMM yyyy}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}


/** A length in points shown and typed in [unit]. */
@Composable
internal fun UnitField(points: Float, unit: E5Unit, label: String, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val shown = (unit.fromPoints(points) * 100).toInt() / 100f
    FloatField(shown, "$label (${unitLabel(unit)})", modifier) { onChange(unit.toPoints(it)) }
}

@Composable
internal fun unitLabel(u: E5Unit) = stringResource(when (u) { E5Unit.PT -> R.string.nt_unit_pt; E5Unit.MM -> R.string.nt_unit_mm; E5Unit.CM -> R.string.nt_unit_cm; E5Unit.IN -> R.string.nt_unit_in })

/** Nitro advanced margins: top, bottom, left, right with a unit choice; values are reported in points. */
@Composable
internal fun MarginsEditor(unit: E5Unit, top: Float, bottom: Float, left: Float, right: Float, onUnit: (E5Unit) -> Unit, onChange: (Float, Float, Float, Float) -> Unit) {
    ChoiceChips(E5Unit.values().toList(), unit, { unitLabel(it) }, onUnit)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UnitField(top, unit, stringResource(R.string.e5_margin_top), Modifier.weight(1f)) { onChange(it, bottom, left, right) }
        UnitField(bottom, unit, stringResource(R.string.e5_margin_bottom), Modifier.weight(1f)) { onChange(top, it, left, right) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UnitField(left, unit, stringResource(R.string.nt_margin_left), Modifier.weight(1f)) { onChange(top, bottom, it, right) }
        UnitField(right, unit, stringResource(R.string.nt_margin_right), Modifier.weight(1f)) { onChange(top, bottom, left, it) }
    }
}
