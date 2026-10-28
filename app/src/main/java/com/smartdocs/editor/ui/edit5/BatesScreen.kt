package com.smartdocs.editor.ui.edit5

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.Bates
import com.smartdocs.editor.core.edit5.BatesMode
import com.smartdocs.editor.core.edit5.BatesSpec
import com.smartdocs.editor.core.edit5.E5Unit
import com.smartdocs.editor.core.edit5.PageSubset
import com.smartdocs.editor.core.edit5.E5TextStyle
import com.smartdocs.editor.core.edit5.HeaderFooterOps
import com.smartdocs.editor.core.edit5.OutProtection
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.ColorChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ResultsList
import com.smartdocs.editor.ui.tools.ToolScaffold
import com.smartdocs.editor.ui.tools.rememberInputFile

/**
 * Bates numbering across several files: prefix/suffix/digits/start, numbering continuing from file to file, one
 * number per document or restarting per document; text template with tokens, colour, opacity, rotation, behind the
 * content, margins per side with units, enlarging the page, the pages of each file, an output folder, and removing
 * Bates numbers again.
 */
@Composable
fun BatesScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    val first by rememberInputFile(uri)
    var files by remember(first) { mutableStateOf(listOfNotNull(first)) }
    var spec by remember { mutableStateOf(BatesSpec(prefix = "DOC-", digits = 6, start = 1)) }
    var layout by remember { mutableStateOf(HeaderFooterOps.BatesLayout()) }
    var unit by remember { mutableStateOf(E5Unit.PT) }
    var naming by remember { mutableStateOf(com.smartdocs.editor.core.edit5.BatesNaming.KEEP) }
    var writeLog by remember { mutableStateOf(false) }
    var replaceSame by remember { mutableStateOf(false) }
    var logFile by remember { mutableStateOf<java.io.File?>(null) }
    val style = layout.style
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { t ->
        val outs = runner.results + listOfNotNull(logFile)
        if (t != null && outs.isNotEmpty()) scope.launch {
            val n = runCatching { com.smartdocs.editor.ui.tools5.saveToTree(ctx, t, outs, replaceSame) }.getOrDefault(0)
            runner.error = ctx.getString(R.string.nt_bates_saved_folder, n)
        }
    }
    var protection by remember { mutableStateOf(OutProtection.KEEP) }
    val unlocker = rememberE5Unlocker()
    // ask for the password of each locked file once, as soon as it is added; a cancelled file leaves the list
    fun unlockNew() = scope.launch {
        val skipped = runCatching { unlocker.unlockAll(files) }.getOrElse { runner.error = it.message ?: it.toString(); emptyList() }
        if (skipped.isNotEmpty()) files = files.filter { f -> skipped.none { it.absolutePath == f.absolutePath } }
    }
    LaunchedEffect(first) { if (files.isNotEmpty()) unlockNew() }
    val processing = stringResource(R.string.processing)
    ToolScaffold(stringResource(R.string.e5_bates_title), nav, runner) {
        E5Section(stringResource(R.string.e5_bates_files, files.size)) {
            files.forEachIndexed { i, f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (unlocker.isLocked(f)) Icon(Icons.Default.Lock, stringResource(R.string.e5_lock_locked), Modifier.size(18.dp))
                    Text("${i + 1}. ${f.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { if (i > 0) files = files.toMutableList().apply { add(i - 1, removeAt(i)) } }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.pc_move_up)) }
                    IconButton(onClick = { files = files.toMutableList().apply { removeAt(i) }; unlocker.forget(f) }) { Icon(Icons.Default.Close, stringResource(R.string.pc_cd_remove)) }
                }
            }
            com.smartdocs.editor.ui.tools5.BatchSources(files) { files = it; unlockNew() }
        }
        E5Section(stringResource(R.string.e5_bates_format)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(spec.prefix, { spec = spec.copy(prefix = it) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.e5_prefix)) })
                OutlinedTextField(spec.suffix, { spec = spec.copy(suffix = it) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.e5_suffix)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatField(spec.digits.toFloat(), stringResource(R.string.e5_digits), Modifier.weight(1f)) { spec = spec.copy(digits = it.toInt().coerceIn(1, 15)) }
                FloatField(spec.start.toFloat(), stringResource(R.string.e5_start_number), Modifier.weight(1f)) { spec = spec.copy(start = it.toLong().coerceAtLeast(0)) }
                FloatField(spec.increment.toFloat(), stringResource(R.string.nt_bates_increment), Modifier.weight(1f)) { spec = spec.copy(increment = it.toLong().takeIf { v -> v != 0L } ?: 1L) }
            }
            if (spec.increment < 0) Text(stringResource(R.string.nt_bates_increment_down), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.e5_example, Bates.format(spec, spec.start)), style = MaterialTheme.typography.bodyMedium)
            ChoiceChips(BatesMode.values().toList(), spec.mode, { stringResource(when (it) {
                BatesMode.CONTINUE -> R.string.nt_bates_continue; BatesMode.PER_DOCUMENT -> R.string.nt_bates_per_doc; BatesMode.RESTART -> R.string.nt_bates_restart }) }) { spec = spec.copy(mode = it) }
            OutlinedTextField(layout.template, { layout = layout.copy(template = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.nt_bates_template)) }, supportingText = { Text(stringResource(R.string.nt_bates_template_help)) })
        }
        E5Section(stringResource(R.string.nt_bates_pages)) {
            RangeSubset(layout.range, { layout = layout.copy(range = it) }, layout.subset) { layout = layout.copy(subset = it) }
            if (layout.range.isNotBlank() || layout.subset != PageSubset.ALL)
                SwitchRow(stringResource(R.string.nt_bates_count_skipped), spec.countSkipped) { spec = spec.copy(countSkipped = it) }
        }
        E5Section(stringResource(R.string.e5_position)) {
            ChoiceChips(HeaderFooterOps.Slot.values().toList(), layout.slot, { stringResource(slotLabel(it)) }) { layout = layout.copy(slot = it) }
            MarginsEditor(unit, layout.marginTop, layout.marginBottom, layout.marginLeft, layout.marginRight, onUnit = { unit = it }) { t, b, l, r ->
                layout = layout.copy(marginTop = t, marginBottom = b, marginLeft = l, marginRight = r)
            }
            SwitchRow(stringResource(R.string.nt_grow_page), layout.growPage) { layout = layout.copy(growPage = it) }
        }
        E5Section(stringResource(R.string.e5_appearance)) {
            FontChoice(style.font) { layout = layout.copy(style = style.copy(font = it)) }
            Text(stringResource(R.string.e5_font_size, style.size.toInt()))
            Slider(style.size, { layout = layout.copy(style = style.copy(size = it)) }, valueRange = 6f..18f)
            ColorChips(TEXT_COLORS, style.color) { layout = layout.copy(style = style.copy(color = it)) }
            SwitchRow(stringResource(R.string.e5_bold), style.bold) { layout = layout.copy(style = style.copy(bold = it)) }
            Text(stringResource(R.string.e5_opacity, (layout.opacity * 100).toInt()))
            Slider(layout.opacity, { layout = layout.copy(opacity = it) }, valueRange = 0.05f..1f)
            Text(stringResource(R.string.e5_rotation, layout.rotation.toInt()))
            Slider(layout.rotation, { layout = layout.copy(rotation = it) }, valueRange = -90f..90f)
            SwitchRow(stringResource(R.string.e5_behind), layout.behind) { layout = layout.copy(behind = it) }
        }
        E5Section(stringResource(R.string.nt_opt_output)) {
            ChoiceChips(com.smartdocs.editor.core.edit5.BatesNaming.values().toList(), naming, { stringResource(when (it) {
                com.smartdocs.editor.core.edit5.BatesNaming.KEEP -> R.string.nt_bates_name_keep
                com.smartdocs.editor.core.edit5.BatesNaming.ADD_RANGE -> R.string.nt_bates_name_add
                com.smartdocs.editor.core.edit5.BatesNaming.REPLACE_WITH_RANGE -> R.string.nt_bates_name_replace }) }) { naming = it }
            val ex = Bates.format(spec, spec.start)
            Text(Bates.outputName("file", ex, ex, naming) + ".pdf", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow(stringResource(R.string.nt_bates_log), writeLog) { writeLog = it }
            SwitchRow(stringResource(R.string.nt_opt_replace_same), replaceSame) { replaceSame = it }
        }
        if (files.any { unlocker.isLocked(it) }) E5Section(stringResource(R.string.e5_lock_section)) { ProtectionChoice(protection) { protection = it } }
        E5UnlockDialog(unlocker)
        PrimaryButton(stringResource(R.string.e5_bates_apply), enabled = files.isNotEmpty(), icon = Icons.Default.Numbers) {
            val list = files; val sp = spec; val lay = layout; val pr = protection; val nm = naming; val log = writeLog
            scope.launch {
                // files added before a cancel, or reopened: make sure every locked one has its password
                val skipped = runCatching { unlocker.unlockAll(list) }.getOrElse { runner.error = it.message ?: it.toString(); return@launch }
                if (skipped.isNotEmpty()) { files = list - skipped.toSet(); return@launch }
                val pw = unlocker.book.lookup()
                runner.runMany(processing) {
                    val done = HeaderFooterOps.bates(ctx, list, { f, a, b -> app.documents.uniqueFile(app.documents.docsDir, Bates.outputName(f.nameWithoutExtension, a, b, nm)) }, sp, lay, pw, pr)
                    logFile = if (log) app.documents.uniqueFile(app.documents.docsDir, "bates_log", "txt").also { f ->
                        f.writeText(Bates.log(sp, done.mapIndexed { i, (out, a, b) -> Triple(list[i].name + " → " + out.name, a, b) }))
                    } else null
                    done.map { it.first }
                }
            }
        }
        // Nitro "Remove Bates Numbering": ours and Acrobat's, on every file of the list
        OutlinedButton(onClick = {
            val list = files
            scope.launch {
                val skipped = runCatching { unlocker.unlockAll(list) }.getOrElse { runner.error = it.message ?: it.toString(); return@launch }
                if (skipped.isNotEmpty()) { files = list - skipped.toSet(); return@launch }
                val pw = unlocker.book.lookup()
                runner.runMany(processing) {
                    list.map { f ->
                        val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_no_bates")
                        HeaderFooterOps.removeBates(f, out, pw(f)); out
                    }
                }
            }
        }, enabled = files.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nt_bates_remove)) }
        ResultsList(runner.results, nav, "application/pdf")
        if (runner.results.isNotEmpty()) {
            Text(stringResource(R.string.e5_bates_done), style = MaterialTheme.typography.bodySmall)
            logFile?.let { Text(stringResource(R.string.nt_bates_log_saved, it.name), style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { tree.launch(null) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.DriveFolderUpload, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nt_bates_to_folder)) }
        }
    }
}

fun slotLabel(s: HeaderFooterOps.Slot) = when (s) {
    HeaderFooterOps.Slot.TOP_LEFT -> R.string.e5_slot_tl; HeaderFooterOps.Slot.TOP_CENTER -> R.string.e5_slot_tc; HeaderFooterOps.Slot.TOP_RIGHT -> R.string.e5_slot_tr
    HeaderFooterOps.Slot.BOTTOM_LEFT -> R.string.e5_slot_bl; HeaderFooterOps.Slot.BOTTOM_CENTER -> R.string.e5_slot_bc; HeaderFooterOps.Slot.BOTTOM_RIGHT -> R.string.e5_slot_br
}

