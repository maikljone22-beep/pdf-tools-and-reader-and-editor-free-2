package com.smartdocs.editor.ui.ai5

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.AiLang
import com.smartdocs.editor.core.ai5.TextAction
import com.smartdocs.editor.core.ai5.TextTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun textActionLabel(a: TextAction): String = stringResource(when (a) {
    TextAction.REWRITE -> R.string.a5_tt_rewrite; TextAction.GRAMMAR -> R.string.a5_tt_grammar; TextAction.EXPLAIN -> R.string.a5_tt_explain
    TextAction.SIMPLIFY -> R.string.a5_tt_simplify; TextAction.FORMAL -> R.string.a5_tt_formal; TextAction.SHORTER -> R.string.a5_tt_shorter
    TextAction.LONGER -> R.string.a5_tt_longer; TextAction.SUMMARIZE -> R.string.a5_tt_summarize; TextAction.DEFINE -> R.string.a5_tt_define
    TextAction.TRANSLATE -> R.string.a5_tt_translate
})

/**
 * Rewrite / grammar fix / explain / translate… for selected text. [onReplace] (only when the caller can edit the
 * text in place, e.g. content-edit mode) shows "استبدال"; [onAddNote] adds the result as a note annotation.
 * [docContext] (optional) is surrounding page text for better explanations.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TextToolsSheet(text: String, initial: TextAction? = null, docContext: String? = null, onReplace: ((String) -> Unit)? = null, onAddNote: ((String) -> Unit)? = null,
                   onShare: ((String) -> Unit)? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf(initial) }
    var target by remember { mutableStateOf(if (AiLang.detect(text) == "ar") "en" else "ar") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val available = AiClient.available(settings)

    fun run(a: TextAction) {
        job?.cancel(); action = a; out = ""; error = null; busy = true
        job = scope.launch {
            try { TextTools.run(ctx, client, a, text, target, docContext).collect { out += it } ; if (a in TextTools.REPLACING) out = TextTools.clean(out) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (AiCalls.isQuota(e)) ctx.getString(R.string.a5_err_quota) else ctx.getString(R.string.a5_err_failed, e.message ?: "") }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { initial?.let { if (available) run(it) } }

    ModalBottomSheet(onDismissRequest = { job?.cancel(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.a5_text_tools), style = MaterialTheme.typography.titleMedium)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Text(text.take(400) + if (text.length > 400) "…" else "", Modifier.padding(10.dp).heightIn(max = 90.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
            }
            if (!available) Text(stringResource(R.string.a5_no_ai), color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextAction.entries.forEach { a -> FilterChip(selected = action == a, onClick = { run(a) }, enabled = available, label = { Text(textActionLabel(a)) }) }
            }
            if (action == TextAction.TRANSLATE) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.a5_target_language)); LanguagePicker(target, { target = it; run(TextAction.TRANSLATE) }, allowAuto = false)
            }
            if (busy && out.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (out.isNotBlank()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    SelectionContainer { Text(out, Modifier.padding(12.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onReplace != null && action in TextTools.REPLACING) Button(onClick = { onReplace(out); onDismiss() }, enabled = !busy) {
                        Icon(Icons.Default.FindReplace, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.a5_replace))
                    }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(out)) }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.a5_copy)) }
                    if (onAddNote != null) OutlinedButton(onClick = { onAddNote(out); onDismiss() }, enabled = !busy) {
                        Icon(Icons.Default.NoteAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.a5_add_note))
                    }
                    if (onShare != null) OutlinedButton(onClick = { onShare(out) }) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.a5_share)) }
                }
            }
        }
    }
}
