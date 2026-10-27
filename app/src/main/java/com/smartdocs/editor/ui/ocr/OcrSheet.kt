package com.smartdocs.editor.ui.ocr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R

/**
 * The OCR dialog of the reader, as one sheet: page range (all | from–to, or locked to one page), languages
 * (multi-select, remembered), mode (searchable layer | editable text) and one primary button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OcrSheet(
    pageCount: Int,
    currentPage: Int,
    lockedPage: Int? = null,
    isInstalled: (String) -> Boolean,
    onRun: (OcrOptions) -> Unit,
    onDismiss: () -> Unit,
    /** Nitro zone OCR: recognise a selected area (opens the area tool); null hides the button. */
    onArea: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    // 0 = current page (default), 1 = all pages, 2 = from–to
    var scope by remember { mutableStateOf(0) }
    val allPages = scope == 1
    var from by remember { mutableStateOf((currentPage + 1).toString()) }
    var to by remember { mutableStateOf(pageCount.toString()) }
    val langs = remember { mutableStateListOf<String>().apply { addAll(OcrPrefs.languages(ctx)) } }
    var editable by remember { mutableStateOf(OcrPrefs.editable(ctx)) }
    var tuning by remember { mutableStateOf(com.smartdocs.editor.core.ocr.OcrTuning.load(ctx)) }
    var moreLangs by remember { mutableStateOf(false) }

    fun clamp(v: String) = v.filter { it.isDigit() }.take(6).toIntOrNull()?.coerceIn(1, pageCount.coerceAtLeast(1))
    val fromN = clamp(from) ?: 1
    val toN = clamp(to) ?: pageCount

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.qk_ocr_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
            }
            Text(stringResource(R.string.qk_ocr_offline_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ---- page range ----
            SheetSection(stringResource(R.string.qk_ocr_range))
            if (lockedPage != null) {
                Text(stringResource(R.string.qk_ocr_page_locked, lockedPage + 1), style = MaterialTheme.typography.bodyLarge)
            } else {
                Row(Modifier.fillMaxWidth().selectable(scope == 0, role = Role.RadioButton) { scope = 0 }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(scope == 0, { scope = 0 }); Text(stringResource(R.string.qk_ocr_current, currentPage + 1))
                }
                Row(Modifier.fillMaxWidth().selectable(scope == 1, role = Role.RadioButton) { scope = 1 }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(scope == 1, { scope = 1 }); Text(stringResource(R.string.qk_ocr_all))
                }
                Row(Modifier.fillMaxWidth().selectable(scope == 2, role = Role.RadioButton) { scope = 2 }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(scope == 2, { scope = 2 }); Text(stringResource(R.string.qk_ocr_from_to), Modifier.padding(end = 12.dp))
                    OutlinedTextField(from, { v -> from = v.filter { it.isDigit() }.take(6); clamp(from)?.let { f -> if (f > toN) to = f.toString() }; scope = 2 },
                        label = { Text(stringResource(R.string.qk_ocr_from)) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(to, { v -> to = v.filter { it.isDigit() }.take(6); clamp(to)?.let { t -> if (t < fromN) from = t.toString() }; scope = 2 },
                        label = { Text(stringResource(R.string.qk_ocr_to)) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }

            // ---- languages ----
            SheetSection(stringResource(R.string.qk_ocr_langs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OCR_LANGUAGES.forEach { (code, label) ->
                    val sel = code in langs
                    FilterChip(sel, { if (sel) langs.remove(code) else langs.add(code) }, label = { Text(stringResource(label)) },
                        leadingIcon = { Icon(if (sel) Icons.Default.Check else if (isInstalled(code)) Icons.Default.Check else Icons.Default.CloudDownload, null, Modifier.size(16.dp)) })
                }
                // Nitro: every downloadable language (the extra ones picked show here)
                langs.filter { c -> OCR_LANGUAGES.none { it.first == c } }.forEach { code ->
                    FilterChip(true, { langs.remove(code) }, label = { Text(com.smartdocs.editor.core.ocr.OcrEngine.label(code)) },
                        leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) })
                }
                AssistChip(onClick = { moreLangs = true }, label = { Text(stringResource(R.string.nt_ocr_more_langs)) }, leadingIcon = { Icon(Icons.Default.Language, null, Modifier.size(16.dp)) })
            }
            if (langs.isEmpty()) Text(stringResource(R.string.qk_ocr_need_lang), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (langs.any { !isInstalled(it) }) Text(stringResource(R.string.qk_ocr_will_download), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

            // ---- mode ----
            SheetSection(stringResource(R.string.qk_ocr_mode))
            ModeCard(Icons.Default.ManageSearch, stringResource(R.string.qk_ocr_searchable), stringResource(R.string.qk_ocr_searchable_desc), !editable) { editable = false }
            Spacer(Modifier.height(8.dp))
            ModeCard(Icons.Default.EditNote, stringResource(R.string.qk_ocr_editable), stringResource(R.string.qk_ocr_editable_desc), editable) { editable = true }
            Spacer(Modifier.height(12.dp))
            OcrTuningSection(tuning) { tuning = it }
            if (onArea != null) OutlinedButton(onClick = onArea, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Default.Crop, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nt_ocr_zone))
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val ordered = OCR_LANGUAGES.map { it.first }.filter { it in langs } + langs.filter { c -> OCR_LANGUAGES.none { it.first == c } }
                    OcrPrefs.save(ctx, ordered, editable)
                    com.smartdocs.editor.core.ocr.OcrTuning.save(ctx, tuning)
                    val pages = when {
                        lockedPage != null -> listOf(lockedPage)
                        scope == 0 -> listOf(currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
                        allPages -> null
                        else -> (minOf(fromN, toN) - 1..maxOf(fromN, toN) - 1).toList()
                    }
                    onRun(OcrOptions(pages, ordered, editable, tuning))
                },
                enabled = langs.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.qk_ocr_run), style = MaterialTheme.typography.titleMedium) }
        }
    }
    if (moreLangs) OcrLanguagePicker(langs.toSet(), onDismiss = { moreLangs = false }) { set -> langs.clear(); langs.addAll(OCR_LANGUAGES.map { it.first }.filter { it in set } + set.filter { c -> OCR_LANGUAGES.none { it.first == c } }); moreLangs = false }
}

@Composable
private fun SheetSection(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected, onClick)
        }
    }
}
