package com.smartdocs.editor.ui.reader.annot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.InkRecognizer
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.launch
import java.util.Locale

private enum class HwPhase { CHECKING, NEED_DOWNLOAD, DOWNLOADING, RECOGNIZING, RESULT, FAILED }

/**
 * Handwriting → text: language chips (Arabic first when the UI is Arabic), one-time model download with a
 * progress indicator, recognised text shown for editing, then the ink is replaced by a free-text comment.
 */
@Composable
fun HandwritingDialog(state: ReaderState, req: Handwriting2) {
    val h = state.annot2
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val uiAr = settings.language == "ar" || Locale.getDefault().language == "ar"
    var lang by remember { mutableStateOf(if (uiAr) "ar" else "en") }
    var phase by remember { mutableStateOf(HwPhase.CHECKING) }
    var strokes by remember { mutableStateOf<List<List<Pair<Float, Float>>>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun recognize() {
        phase = HwPhase.RECOGNIZING
        if (strokes.isEmpty()) strokes = h.strokesOf(req)
        val res = runCatching { InkRecognizer.recognize(lang, strokes) }
        res.onSuccess { c -> candidates = c; text = c.firstOrNull() ?: ""; phase = if (c.isEmpty()) HwPhase.FAILED else HwPhase.RESULT }
            .onFailure { error = it.message; phase = HwPhase.FAILED }
    }

    LaunchedEffect(lang) {
        error = null; candidates = emptyList()
        phase = HwPhase.CHECKING
        if (InkRecognizer.isDownloaded(lang)) recognize() else phase = HwPhase.NEED_DOWNLOAD
    }

    AlertDialog(
        onDismissRequest = { h.handwriting = null },
        icon = { Icon(Icons.Default.Gesture, null) },
        title = { Text(stringResource(R.string.an_handwriting)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.an_hw_language), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ordered = if (uiAr) InkRecognizer.LANGUAGES else listOf("en") + InkRecognizer.LANGUAGES.filter { it != "en" }
                    ordered.forEach { tag ->
                        FilterChip(lang == tag, { if (phase != HwPhase.DOWNLOADING) lang = tag },
                            label = { Text(Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it) }.ifBlank { tag }) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (phase) {
                    HwPhase.CHECKING, HwPhase.RECOGNIZING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.an_hw_recognizing))
                    }
                    HwPhase.NEED_DOWNLOAD -> Column {
                        Text(stringResource(R.string.an_hw_need_net), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = {
                            phase = HwPhase.DOWNLOADING
                            scope.launch {
                                runCatching { InkRecognizer.download(lang) }
                                    .onSuccess { recognize() }
                                    .onFailure { error = it.message; phase = HwPhase.FAILED }
                            }
                        }) {
                            Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.an_hw_download, Locale.forLanguageTag(lang).let { it.getDisplayLanguage(it) }))
                        }
                    }
                    HwPhase.DOWNLOADING -> Column {
                        Text(stringResource(R.string.an_hw_downloading))
                        Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    HwPhase.RESULT -> Column {
                        if (candidates.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            candidates.take(5).forEach { c -> SuggestionChip(onClick = { text = c }, label = { Text(c, maxLines = 1) }) }
                        }
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.an_hw_result)) }, minLines = 2)
                    }
                    HwPhase.FAILED -> Column {
                        Text(stringResource(R.string.an_hw_failed), color = MaterialTheme.colorScheme.error)
                        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { scope.launch { if (InkRecognizer.isDownloaded(lang)) recognize() else phase = HwPhase.NEED_DOWNLOAD } }) {
                            Text(stringResource(R.string.an_hw_retry))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { h.applyHandwriting(req, strokes, text.trim()) }, enabled = phase == HwPhase.RESULT && text.isNotBlank()) {
                Text(stringResource(R.string.an_hw_apply))
            }
        },
        dismissButton = { TextButton(onClick = { h.handwriting = null }) { Text(stringResource(R.string.cancel)) } },
    )
}
