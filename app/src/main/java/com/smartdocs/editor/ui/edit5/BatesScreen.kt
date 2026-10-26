package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.Bates
import com.smartdocs.editor.core.edit5.BatesSpec
import com.smartdocs.editor.core.edit5.E5TextStyle
import com.smartdocs.editor.core.edit5.HeaderFooterOps
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ResultsList
import com.smartdocs.editor.ui.tools.ToolScaffold
import com.smartdocs.editor.ui.tools.rememberInputFile

/** Bates numbering across several files: prefix/suffix/digits/start, numbering continues from file to file. */
@Composable
fun BatesScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val runner = rememberToolRunner()
    val first by rememberInputFile(uri)
    var files by remember(first) { mutableStateOf(listOfNotNull(first)) }
    var spec by remember { mutableStateOf(BatesSpec(prefix = "DOC-", digits = 6, start = 1)) }
    var slot by remember { mutableStateOf(HeaderFooterOps.Slot.BOTTOM_RIGHT) }
    var style by remember { mutableStateOf(E5TextStyle(size = 9f, color = 0x212121)) }
    var margin by remember { mutableFloatStateOf(20f) }
    val pick = rememberPdfPicker(multiple = true) { picked -> files = (files + picked).distinctBy { it.absolutePath } }
    val processing = stringResource(R.string.processing)
    ToolScaffold(stringResource(R.string.e5_bates_title), nav, runner) {
        E5Section(stringResource(R.string.e5_bates_files, files.size)) {
            files.forEachIndexed { i, f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${f.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { if (i > 0) files = files.toMutableList().apply { add(i - 1, removeAt(i)) } }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, null) }
                    IconButton(onClick = { files = files.toMutableList().apply { removeAt(i) } }) { Icon(Icons.Default.Close, null) }
                }
            }
            OutlinedButton(onClick = pick) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_add_files)) }
        }
        E5Section(stringResource(R.string.e5_bates_format)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(spec.prefix, { spec = spec.copy(prefix = it) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.e5_prefix)) })
                OutlinedTextField(spec.suffix, { spec = spec.copy(suffix = it) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.e5_suffix)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatField(spec.digits.toFloat(), stringResource(R.string.e5_digits), Modifier.weight(1f)) { spec = spec.copy(digits = it.toInt().coerceIn(1, 12)) }
                FloatField(spec.start.toFloat(), stringResource(R.string.e5_start_number), Modifier.weight(1f)) { spec = spec.copy(start = it.toLong().coerceAtLeast(0)) }
            }
            Text(stringResource(R.string.e5_example, Bates.format(spec, spec.start)), style = MaterialTheme.typography.bodyMedium)
        }
        E5Section(stringResource(R.string.e5_position)) {
            ChoiceChips(HeaderFooterOps.Slot.values().toList(), slot, { stringResource(slotLabel(it)) }) { slot = it }
            FloatField(margin, stringResource(R.string.e5_margin)) { margin = it }
            FontChoice(style.font) { style = style.copy(font = it) }
            Text(stringResource(R.string.e5_font_size, style.size.toInt()))
            Slider(style.size, { style = style.copy(size = it) }, valueRange = 6f..18f)
            SwitchRow(stringResource(R.string.e5_bold), style.bold) { style = style.copy(bold = it) }
        }
        PrimaryButton(stringResource(R.string.e5_bates_apply), enabled = files.isNotEmpty(), icon = Icons.Default.Numbers) {
            val list = files; val sp = spec; val sl = slot; val st = style; val m = margin
            runner.runMany(processing) {
                HeaderFooterOps.bates(ctx, list, { f -> app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_bates") }, sp, sl, st, m).map { it.first }
            }
        }
        ResultsList(runner.results, nav, "application/pdf")
        if (runner.results.isNotEmpty()) Text(stringResource(R.string.e5_bates_done), style = MaterialTheme.typography.bodySmall)
    }
}

fun slotLabel(s: HeaderFooterOps.Slot) = when (s) {
    HeaderFooterOps.Slot.TOP_LEFT -> R.string.e5_slot_tl; HeaderFooterOps.Slot.TOP_CENTER -> R.string.e5_slot_tc; HeaderFooterOps.Slot.TOP_RIGHT -> R.string.e5_slot_tr
    HeaderFooterOps.Slot.BOTTOM_LEFT -> R.string.e5_slot_bl; HeaderFooterOps.Slot.BOTTOM_CENTER -> R.string.e5_slot_bc; HeaderFooterOps.Slot.BOTTOM_RIGHT -> R.string.e5_slot_br
}

