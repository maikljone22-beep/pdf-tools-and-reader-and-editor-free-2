package com.smartdocs.editor.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.DocMeta
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner

private enum class CompressPreset(val quality: Int, val maxDim: Int) { LOW(40, 1000), MEDIUM(60, 1400), HIGH(80, 2000) }

/** Tool "compress": re-encode embedded images. */
@Composable
fun CompressScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    var preset by remember { mutableStateOf(CompressPreset.MEDIUM) }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val processing = stringResource(R.string.processing)
    val before = file?.length() ?: 0L
    val extra = runner.result?.let { stringResource(R.string.size_before_after, DocumentRepository.formatSize(before), DocumentRepository.formatSize(it.length())) }
    val wrongPw = stringResource(R.string.password_wrong)
    // audit 5 T-09 / C-4: the reader's password, or a prompt
    val lock = rememberToolPassword(file) { file = null }

    ToolScaffold(stringResource(R.string.tool_compress), nav, runner, resultExtra = extra) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        file?.let { Text(stringResource(R.string.file_size) + ": " + DocumentRepository.formatSize(it.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FieldLabel(stringResource(R.string.compress_quality))
        ChoiceChips(CompressPreset.entries, preset, {
            when (it) { CompressPreset.LOW -> stringResource(R.string.compress_low); CompressPreset.MEDIUM -> stringResource(R.string.compress_medium); CompressPreset.HIGH -> stringResource(R.string.compress_high) }
        }) { preset = it }
        Text(stringResource(R.string.tl_compress_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(stringResource(R.string.tl_compress), enabled = file != null && lock.ready, icon = Icons.Default.Compress) {
            val src = file ?: return@PrimaryButton
            val p = preset; val pw = lock.password
            runner.run(processing) { withPasswordMessage(wrongPw) {
                val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, "compressed"))
                try { PdfOps.compress(src, out, p.quality, p.maxDim, pw) } catch (e: Exception) { out.delete(); throw e }
                // audit 5 E-1 / C-2: the result is the source transformed, so "Save in original" may replace it
                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
            } }
        }
    }
}

/** Tool "page_numbers". */
@Composable
fun PageNumbersScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    var position by remember { mutableStateOf(PdfOps.NumberPosition.BOTTOM_CENTER) }
    var start by remember { mutableStateOf("1") }
    var fontSize by remember { mutableFloatStateOf(11f) }
    var arabicDigits by remember { mutableStateOf(false) }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val processing = stringResource(R.string.processing)
    val wrongPw = stringResource(R.string.password_wrong)
    val lock = rememberToolPassword(file) { file = null }

    ToolScaffold(stringResource(R.string.tool_page_numbers), nav, runner) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        FieldLabel(stringResource(R.string.tl_position))
        ChoiceChips(PdfOps.NumberPosition.entries, position, {
            when (it) {
                PdfOps.NumberPosition.BOTTOM_CENTER -> stringResource(R.string.tl_pos_bottom_center)
                PdfOps.NumberPosition.BOTTOM_RIGHT -> stringResource(R.string.tl_pos_bottom_right)
                PdfOps.NumberPosition.BOTTOM_LEFT -> stringResource(R.string.tl_pos_bottom_left)
                PdfOps.NumberPosition.TOP_CENTER -> stringResource(R.string.tl_pos_top_center)
            }
        }) { position = it }
        NumberField(start, stringResource(R.string.tl_start_number), { start = it }, Modifier.fillMaxWidth())
        LabeledSlider(stringResource(R.string.prop_font_size), fontSize, 8f..24f, { fontSize = it })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tl_arabic_digits), Modifier.weight(1f)); Switch(arabicDigits, { arabicDigits = it })
        }
        PrimaryButton(stringResource(R.string.tl_add_numbers), enabled = file != null && lock.ready, icon = Icons.Default.FormatListNumbered) {
            val src = file ?: return@PrimaryButton
            val pos = position; val st = start.toIntOrNull() ?: 1; val fs = fontSize; val ar = arabicDigits; val pw = lock.password
            runner.run(processing) { withPasswordMessage(wrongPw) {
                val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, "numbered"))
                PdfOps.addPageNumbers(ctx, src, out, pos, st, fs, ar, pw)
                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
            } }
        }
    }
}

/** Tool "doc_info": view and edit document properties. */
@Composable
fun DocInfoScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    var meta by remember { mutableStateOf<DocMeta?>(null) }
    var pages by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf(PdfOps.Info()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val processing = stringResource(R.string.processing)
    val needPassword = stringResource(R.string.password_enter)
    val wrongPw = stringResource(R.string.password_wrong)
    val lock = rememberToolPassword(file) { file = null }

    LaunchedEffect(file, lock.ready, lock.password) {
        val f = file ?: return@LaunchedEffect
        meta = null; loadError = null
        if (!lock.ready) return@LaunchedEffect
        runCatching {
            val s = PdfSession.open(ctx, f, lock.password)
            try { pages = s.pageCount; val m = s.meta(); meta = m; info = PdfOps.Info(m.title, m.author, m.subject, m.keywords, m.creator, m.producer) } finally { s.closeAsync() }
        }.onFailure { loadError = if (it is PdfSession.Companion.PasswordRequiredException) needPassword else (it.message ?: it.toString()) }
    }

    ToolScaffold(stringResource(R.string.tool_doc_info), nav, runner) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val f = file; val m = meta
        if (f != null && m != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow(stringResource(R.string.file_name), f.name)
                    InfoRow(stringResource(R.string.file_size), DocumentRepository.formatSize(f.length()))
                    InfoRow(stringResource(R.string.file_pages), pages.toString())
                    InfoRow(stringResource(R.string.file_modified), DocumentRepository.formatDate(f.lastModified()))
                    if (m.creationDate.isNotBlank()) InfoRow(stringResource(R.string.info_created), m.creationDate)
                    if (m.modDate.isNotBlank()) InfoRow(stringResource(R.string.info_modified), m.modDate)
                    InfoRow(stringResource(R.string.file_path), f.absolutePath)
                }
            }
            OutlinedTextField(info.title, { info = info.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.info_title)) }, singleLine = true)
            OutlinedTextField(info.author, { info = info.copy(author = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.info_author)) }, singleLine = true)
            OutlinedTextField(info.subject, { info = info.copy(subject = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.info_subject)) }, singleLine = true)
            OutlinedTextField(info.keywords, { info = info.copy(keywords = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.info_keywords)) }, singleLine = true)
            OutlinedTextField(info.creator, { info = info.copy(creator = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.info_creator)) }, singleLine = true)
            OutlinedTextField(info.producer, { info = info.copy(producer = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.info_producer)) }, singleLine = true)
            PrimaryButton(stringResource(R.string.tl_doc_info_save), icon = Icons.Default.Save) {
                val i = info.copy(); val pw = lock.password
                runner.run(processing) { withPasswordMessage(wrongPw) {
                    val out = app.documents.uniqueFile(app.documents.docsDir, outName(f, "info"))
                    PdfOps.setInfo(f, i, out, pw)
                    com.smartdocs.editor.core.pdf.ToolOutputs.record(out, f)
                } }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
