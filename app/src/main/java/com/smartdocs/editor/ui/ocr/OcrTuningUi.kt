package com.smartdocs.editor.ui.ocr

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.ocr.OcrModels
import com.smartdocs.editor.core.ocr.OcrThreshold
import com.smartdocs.editor.core.ocr.OcrTuning

/**
 * Nitro OCR options as one collapsible section: recognition quality, pre-processing (de-skew, text orientation,
 * threshold, smoothing), numbers only, and the output images (downsample, compression).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OcrTuningSection(t: OcrTuning, onChange: (OcrTuning) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nt_ocr_options), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (!open) return@Column
            Text(stringResource(R.string.nt_ocr_quality), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!t.fast, { onChange(t.copy(fast = false)) }, label = { Text(stringResource(R.string.nt_ocr_quality_best)) })
                FilterChip(t.fast, { onChange(t.copy(fast = true)) }, label = { Text(stringResource(R.string.nt_ocr_quality_fast)) })
            }
            Text(stringResource(R.string.nt_ocr_pre), style = MaterialTheme.typography.labelLarge)
            Check(stringResource(R.string.nt_ocr_deskew), t.deskew) { onChange(t.copy(deskew = it)) }
            Check(stringResource(R.string.nt_ocr_orientation), t.orientation) { onChange(t.copy(orientation = it)) }
            Check(stringResource(R.string.nt_ocr_smooth), t.smooth) { onChange(t.copy(smooth = it)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OcrThreshold.entries.forEach { m ->
                    FilterChip(t.threshold == m, { onChange(t.copy(threshold = m)) }, label = { Text(stringResource(when (m) {
                        OcrThreshold.NONE -> R.string.nt_ocr_thr_none; OcrThreshold.FIXED -> R.string.nt_ocr_thr_fixed; OcrThreshold.ADAPTIVE -> R.string.nt_ocr_thr_adaptive })) })
                }
            }
            if (t.threshold == OcrThreshold.FIXED) {
                Text(stringResource(R.string.nt_ocr_thr_value, t.thresholdValue), style = MaterialTheme.typography.bodySmall)
                Slider(t.thresholdValue.toFloat(), { onChange(t.copy(thresholdValue = it.toInt())) }, valueRange = 40f..230f)
            }
            Check(stringResource(R.string.nt_ocr_numeric), t.numericOnly) { onChange(t.copy(numericOnly = it)) }
            Text(stringResource(R.string.nt_ocr_output), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 300, 150, 96, 72).forEach { d ->
                    FilterChip(t.outputDpi == d, { onChange(t.copy(outputDpi = d)) }, label = { Text(if (d == 0) stringResource(R.string.nt_ocr_out_same) else "$d dpi") })
                }
            }
            if (t.outputDpi > 0) {
                Text(stringResource(R.string.nt_ocr_out_quality, t.outputQuality), style = MaterialTheme.typography.bodySmall)
                Slider(t.outputQuality.toFloat(), { onChange(t.copy(outputQuality = it.toInt())) }, valueRange = 30f..95f)
            }
            Text(stringResource(R.string.nt_ocr_special_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange); Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Every downloadable OCR language and script (Nitro: all languages), searchable; returns the chosen codes. */
@Composable
fun OcrLanguagePicker(selected: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    var q by remember { mutableStateOf("") }
    val all = remember { (OcrModels.languages() + OcrModels.scripts()).filter { it != OcrEngine.ARABIC_SCRIPT && it != "script/" + OcrEngine.ARABIC_SCRIPT } }
    val shown = all.filter { q.isBlank() || it.contains(q, true) || OcrEngine.label(it).contains(q, true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.nt_ocr_all_langs)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(stringResource(R.string.nt_ocr_lang_search)) })
                Text(stringResource(R.string.nt_ocr_lang_count, chosen.size), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it }) { code ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (code in chosen) chosen - code else chosen + code }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(code in chosen, { chosen = if (it) chosen + code else chosen - code })
                            Column {
                                Text(OcrEngine.label(code), style = MaterialTheme.typography.bodyMedium)
                                Text(code + " · " + "%.1f MB".format((OcrModels.BEST[code]?.size ?: 0L) / 1_048_576.0), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen) }, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
