package com.smartdocs.editor.ui.tools5

import android.net.Uri
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.FieldLabel
import kotlinx.coroutines.launch
import java.io.File

/** Foxit optimizer panels: compatibility, downsampling, fonts, scans, transparency and the discard / clean-up switches. */
@Composable
fun FoxitOptimizeOptions(o: OptimizeOptions, onChange: (OptimizeOptions) -> Unit) {
    var open by remember { mutableStateOf(false) }
    T5Card(stringResource(R.string.nt_opt_advanced), Icons.Default.Settings) {
        TextButton(onClick = { open = !open }) {
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null); Spacer(Modifier.width(6.dp))
            Text(stringResource(if (open) R.string.nt_opt_hide else R.string.nt_opt_show))
        }
        if (!open) return@T5Card
        FieldLabel(stringResource(R.string.nt_opt_version))
        ChoiceChips(listOf("", "1.4", "1.5", "1.6", "1.7", "2.0"), o.pdfVersion, { if (it.isEmpty()) stringResource(R.string.nt_opt_version_keep) else it }) { onChange(o.copy(pdfVersion = it)) }
        FieldLabel(stringResource(R.string.nt_opt_downsample))
        ChoiceChips(Downsample.entries, o.downsample, { stringResource(when (it) {
            Downsample.OFF -> R.string.nt_ds_off; Downsample.AVERAGE -> R.string.nt_ds_average; Downsample.SUBSAMPLE -> R.string.nt_ds_subsample; Downsample.BICUBIC -> R.string.nt_ds_bicubic }) }) { onChange(o.copy(downsample = it)) }
        if (o.downsample != Downsample.OFF) {
            LabeledSlider(if (o.aboveDpi == 0) stringResource(R.string.nt_opt_above_auto) else stringResource(R.string.nt_opt_above, o.aboveDpi),
                o.aboveDpi.toFloat(), 0f..900f, { onChange(o.copy(aboveDpi = (it / 25).toInt() * 25)) })
        }
        FieldLabel(stringResource(R.string.nt_opt_fonts))
        SwitchRow(stringResource(R.string.nt_opt_subset), o.subsetFonts, stringResource(R.string.nt_opt_subset_help)) { onChange(o.copy(subsetFonts = it)) }
        FieldLabel(stringResource(R.string.nt_opt_scans))
        SwitchRow(stringResource(R.string.nt_opt_mrc), o.mrc, stringResource(R.string.nt_opt_mrc_help)) { onChange(o.copy(mrc = it)) }
        SwitchRow(stringResource(R.string.nt_opt_merge_fragments), o.mergeFragments) { onChange(o.copy(mergeFragments = it)) }
        FieldLabel(stringResource(R.string.nt_opt_transparency))
        ChoiceChips(listOf(0, 150, 300, 600), o.flattenTransparencyDpi, { stringResource(when (it) {
            0 -> R.string.nt_opt_transp_off; 150 -> R.string.nt_opt_transp_low; 300 -> R.string.nt_opt_transp_medium; else -> R.string.nt_opt_transp_high }) }) { onChange(o.copy(flattenTransparencyDpi = it)) }
        if (o.flattenTransparencyDpi > 0) Text(stringResource(R.string.nt_opt_transp_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FieldLabel(stringResource(R.string.nt_opt_discard))
        val c = o.cleanup
        fun set(n: CleanupOptions) = onChange(o.copy(cleanup = n))
        SwitchRow(stringResource(R.string.nt_cl_form_actions), c.formActions) { set(c.copy(formActions = it)) }
        SwitchRow(stringResource(R.string.nt_cl_multimedia), c.multimedia) { set(c.copy(multimedia = it)) }
        SwitchRow(stringResource(R.string.nt_cl_external), c.externalLinks) { set(c.copy(externalLinks = it)) }
        SwitchRow(stringResource(R.string.nt_cl_alternates), c.alternateImages) { set(c.copy(alternateImages = it)) }
        SwitchRow(stringResource(R.string.nt_cl_tags), c.tags, stringResource(R.string.nt_san_structure_note)) { set(c.copy(tags = it)) }
        SwitchRow(stringResource(R.string.nt_cl_object_data), c.objectData) { set(c.copy(objectData = it)) }
        SwitchRow(stringResource(R.string.nt_cl_print), c.printSettings) { set(c.copy(printSettings = it)) }
        SwitchRow(stringResource(R.string.nt_cl_search), c.searchIndex) { set(c.copy(searchIndex = it)) }
        SwitchRow(stringResource(R.string.nt_cl_layers), c.flattenLayers) { set(c.copy(flattenLayers = it)) }
        FieldLabel(stringResource(R.string.nt_opt_cleanup))
        SwitchRow(stringResource(R.string.nt_cl_lzw), c.lzwToFlate) { set(c.copy(lzwToFlate = it)) }
        SwitchRow(stringResource(R.string.nt_cl_invalid), c.invalidLinks) { set(c.copy(invalidLinks = it)) }
        SwitchRow(stringResource(R.string.nt_cl_unused_dests), c.unusedDests) { set(c.copy(unusedDests = it)) }
        SwitchRow(stringResource(R.string.nt_cl_content), c.optimizeContent) { set(c.copy(optimizeContent = it)) }
        SwitchRow(stringResource(R.string.nt_cl_curves), c.linesToCurves) { set(c.copy(linesToCurves = it)) }
    }
}

/**
 * Foxit "Reduce File Size" for several files: add files or a whole folder, reorder and remove them; output name
 * suffix, an output folder and what to do with files of the same name there.
 */
class OptimizeBatch {
    var files by mutableStateOf<List<File>>(emptyList())
    var suffix by mutableStateOf("_optimized")
    var folder by mutableStateOf<Uri?>(null)
    var replaceSameName by mutableStateOf(false)
}

@Composable
fun OptimizeBatchSection(batch: OptimizeBatch) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberPdfPicker(multiple = true) { picked -> batch.files = (batch.files + picked).distinctBy { it.absolutePath } }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: return@launch
            val pdfs = dir.listFiles().filter { it.isFile && (it.name ?: "").endsWith(".pdf", true) }.sortedBy { it.name?.lowercase() }
            val local = pdfs.mapNotNull { runCatching { app.documents.localFile(it.uri) }.getOrNull() }
            batch.files = (batch.files + local).distinctBy { it.absolutePath }
        }
    }
    val outFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            batch.folder = tree
        }
    }
    T5Card(stringResource(R.string.nt_opt_batch, batch.files.size), Icons.Default.LibraryBooks) {
        Text(stringResource(R.string.nt_opt_batch_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        batch.files.forEachIndexed { i, f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${f.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { batch.files = batch.files.toMutableList().apply { add(i - 1, removeAt(i)) } }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, null) }
                IconButton(onClick = { batch.files = batch.files - f }) { Icon(Icons.Default.Close, null) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = pick) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.e5_add_files)) }
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nt_opt_add_folder)) }
        }
        FieldLabel(stringResource(R.string.nt_opt_output))
        OutlinedTextField(batch.suffix, { batch.suffix = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nt_opt_suffix)) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(batch.folder?.let { androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, it)?.name } ?: stringResource(R.string.nt_opt_folder_docs),
                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { outFolder.launch(null) }) { Text(stringResource(R.string.nt_opt_folder_pick)) }
            if (batch.folder != null) IconButton(onClick = { batch.folder = null }) { Icon(Icons.Default.Close, null) }
        }
        if (batch.folder != null) SwitchRow(stringResource(R.string.nt_opt_replace_same), batch.replaceSameName) { batch.replaceSameName = it }
    }
}

/** Copies [files] into the tree [folder]; with [replace] a file of the same name there is overwritten. Returns how many. */
suspend fun saveToTree(ctx: android.content.Context, folder: Uri, files: List<File>, replace: Boolean): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, folder) ?: return@withContext 0
    var n = 0
    files.forEach { f ->
        runCatching {
            val existing = dir.findFile(f.name)
            val target = if (existing != null && replace) existing else dir.createFile("application/pdf", if (existing != null) f.nameWithoutExtension + "_" + System.currentTimeMillis() % 100000 else f.nameWithoutExtension)
            target?.uri?.let { u -> ctx.contentResolver.openOutputStream(u, "wt")?.use { os -> f.inputStream().use { it.copyTo(os) } }; n++ }
        }
    }
    n
}
