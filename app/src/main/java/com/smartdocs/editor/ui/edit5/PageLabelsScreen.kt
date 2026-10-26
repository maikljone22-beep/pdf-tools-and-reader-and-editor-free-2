package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.LabelRange
import com.smartdocs.editor.core.edit5.LabelStyle
import com.smartdocs.editor.core.edit5.PageBoxOps
import com.smartdocs.editor.core.edit5.PageLabelFormat
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold

/** Page labels (i, ii, iii … 1, 2, 3 … A-1 …): ranges with style, prefix and start number, shown by every PDF viewer. */
@Composable
fun PageLabelsScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    var ranges by remember { mutableStateOf<List<LabelRange>>(emptyList()) }
    val processing = stringResource(R.string.processing)
    LaunchedEffect(doc.work) {
        val w = doc.work ?: return@LaunchedEffect
        ranges = runCatching { PageBoxOps.readLabels(w, doc.password).first }.getOrDefault(emptyList())
    }
    val n = doc.pageCount
    ToolScaffold(stringResource(R.string.e5_labels_title), nav, runner) {
        E5FileRow(doc)
        if (n > 0) {
            Text(stringResource(R.string.e5_labels_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { ranges = listOf(LabelRange(0, LabelStyle.ROMAN_LOWER), LabelRange(minOf(4, n - 1), LabelStyle.DECIMAL)) }, label = { Text(stringResource(R.string.e5_labels_preset_front)) })
                AssistChip(onClick = { ranges = emptyList() }, label = { Text(stringResource(R.string.e5_labels_clear)) })
            }
            ranges.sortedBy { it.startPage }.forEachIndexed { i, r ->
                E5Section(stringResource(R.string.e5_labels_range, i + 1)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FloatField((r.startPage + 1).toFloat(), stringResource(R.string.e5_labels_from_page), Modifier.weight(1f)) { v -> ranges = ranges.map { if (it === r) it.copy(startPage = (v.toInt() - 1).coerceIn(0, (n - 1).coerceAtLeast(0))) else it } }
                        FloatField(r.start.toFloat(), stringResource(R.string.e5_start_number), Modifier.weight(1f)) { v -> ranges = ranges.map { if (it === r) it.copy(start = v.toInt().coerceAtLeast(1)) else it } }
                        IconButton(onClick = { ranges = ranges.filter { it !== r } }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    }
                    OutlinedTextField(r.prefix, { v -> ranges = ranges.map { if (it === r) it.copy(prefix = v) else it } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_prefix)) })
                    ChoiceChips(LabelStyle.values().toList(), r.style, { stringResource(styleLabel(it)) }) { s -> ranges = ranges.map { if (it === r) it.copy(style = s) else it } }
                }
            }
            OutlinedButton(onClick = { val last = ranges.maxOfOrNull { it.startPage } ?: -1; ranges = ranges + LabelRange((last + 1).coerceIn(0, (n - 1).coerceAtLeast(0))) }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_labels_add))
            }
            val labels = remember(ranges, n) { PageLabelFormat.labels(ranges, n) }
            E5Section(stringResource(R.string.e5_preview)) {
                Text(labels.take(40).mapIndexed { i, l -> "${i + 1}→$l" }.joinToString("   ") + if (n > 40) " …" else "", style = MaterialTheme.typography.bodySmall)
            }
            PrimaryButton(stringResource(R.string.apply), icon = Icons.Default.Label) {
                val rs = ranges
                runner.run(processing) {
                    doc.apply(app.documents.tempDir) { s, o, p -> PageBoxOps.writeLabels(s, o, p, rs) }
                    doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "labels")
                }
            }
        }
    }
}

private fun styleLabel(s: LabelStyle) = when (s) {
    LabelStyle.DECIMAL -> R.string.e5_ls_decimal; LabelStyle.ROMAN_UPPER -> R.string.e5_ls_roman_u; LabelStyle.ROMAN_LOWER -> R.string.e5_ls_roman_l
    LabelStyle.LETTERS_UPPER -> R.string.e5_ls_letters_u; LabelStyle.LETTERS_LOWER -> R.string.e5_ls_letters_l; LabelStyle.NONE -> R.string.e5_ls_none
}
