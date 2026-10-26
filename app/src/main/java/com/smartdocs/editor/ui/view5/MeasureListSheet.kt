package com.smartdocs.editor.ui.view5

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.view5.MeasureFormat
import com.smartdocs.editor.core.view5.MeasureRow
import com.smartdocs.editor.core.view5.MeasureType
import com.smartdocs.editor.core.view5.MeasureWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun measureTypeLabel(t: MeasureType): Int = when (t) {
    MeasureType.DISTANCE -> R.string.v5_tool_distance; MeasureType.PERIMETER -> R.string.v5_tool_perimeter; MeasureType.AREA -> R.string.v5_tool_area
    MeasureType.CIRCLE -> R.string.v5_tool_circle; MeasureType.ANGLE -> R.string.v5_tool_angle; MeasureType.COUNT -> R.string.v5_tool_count
}

/** Every measurement saved in the document (ours and other viewers' /Measure annotations), totals and CSV export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureListSheet(v: V5State) {
    val s = v.s
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val found by produceState<List<MeasureWriter.Found>?>(null, s.renderVersion) {
        val f = s.file ?: run { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching { (if (s.password.isNullOrEmpty()) PDDocument.load(f) else PDDocument.load(f, s.password)).use { MeasureWriter.list(it) } }.getOrDefault(emptyList())
        }
    }
    ModalBottomSheet(onDismissRequest = { v.close() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.v5_measurements), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(enabled = !found.isNullOrEmpty(), onClick = {
                    val list = found ?: return@TextButton
                    scope.launch {
                        val rows = list.map { MeasureRow(it.page, it.type, it.value ?: 0.0, it.contents.substringAfter(' ', "").trim(), it.contents, it.author, it.scale) }
                        val csv = MeasureFormat.csv(rows, listOf(ctx.getString(R.string.v5_col_page), ctx.getString(R.string.v5_col_type), ctx.getString(R.string.v5_col_value),
                            ctx.getString(R.string.v5_col_unit), ctx.getString(R.string.v5_col_label), ctx.getString(R.string.v5_col_author), ctx.getString(R.string.v5_col_scale)),
                            ctx.getString(R.string.v5_total))
                        val app = ctx.applicationContext as com.smartdocs.editor.SmartDocsApp
                        val out = withContext(Dispatchers.IO) { app.documents.newTemp((s.file?.nameWithoutExtension ?: "doc") + "_measure", "csv").also { it.writeText(csv, Charsets.UTF_8) } }
                        runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(out), "text/csv"), null)) }
                    }
                }) { Icon(Icons.Default.TableChart, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.v5_export_csv)) }
            }
            val list = found
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(stringResource(R.string.v5_no_measurements), Modifier.padding(vertical = 24.dp))
                else -> {
                    // totals per type
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.groupBy { it.type }.forEach { (t, l) ->
                            AssistChip(onClick = {}, label = { Text(stringResource(measureTypeLabel(t)) + " ×" + l.size) })
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(list) { f ->
                            ListItem(
                                headlineContent = { Text(f.contents.ifBlank { stringResource(measureTypeLabel(f.type)) }) },
                                supportingContent = { Text(stringResource(measureTypeLabel(f.type)) + " · " + stringResource(R.string.v5_page_n, f.page + 1) + (if (f.scale.isNotBlank()) " · " + f.scale else "")) },
                                leadingContent = { Icon(Icons.Default.Straighten, null) },
                                modifier = Modifier.clickable { s.jumpTo(f.page); v.close() })
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
