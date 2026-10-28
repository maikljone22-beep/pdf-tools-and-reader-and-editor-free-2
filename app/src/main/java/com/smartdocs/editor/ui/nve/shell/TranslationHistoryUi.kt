package com.smartdocs.editor.ui.nve.shell

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.shell.TranslationEntry
import com.smartdocs.editor.core.nve.shell.TranslationHistory

/** Translation history store (prefs "nve_shell"). */
object NveTranslations {
    fun all(ctx: Context): List<TranslationEntry> = TranslationHistory.fromJson(NveShellPrefs.prefs(ctx).getString("translations", null))
    fun add(ctx: Context, source: String, target: String, text: String, result: String) {
        if (text.isBlank() || result.isBlank()) return
        val l = TranslationHistory.add(all(ctx), TranslationEntry(System.currentTimeMillis(), source, target, text, result))
        NveShellPrefs.prefs(ctx).edit().putString("translations", TranslationHistory.toJson(l)).apply(); NveShellPrefs.version.intValue++
    }
    fun clear(ctx: Context) { NveShellPrefs.prefs(ctx).edit().remove("translations").apply(); NveShellPrefs.version.intValue++ }
}

/** "History" chip for the AI text tools (shown with Translate): opens the translation history list. */
@Composable
fun NveTranslationHistoryChip() {
    var open by remember { mutableStateOf(false) }
    AssistChip(onClick = { open = true }, label = { Text(stringResource(R.string.nl_tr_history)) }, leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(16.dp)) })
    if (open) NveTranslationHistoryDialog { open = false }
}

@Composable
fun NveTranslationHistoryDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val v = NveShellPrefs.version.intValue
    var q by remember { mutableStateOf("") }
    val list = remember(v, q) { TranslationHistory.search(NveTranslations.all(ctx), q) }
    var shown by remember { mutableStateOf<TranslationEntry?>(null) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Translate, null) }, title = { Text(stringResource(R.string.nl_tr_history)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
                if (list.isEmpty()) Text(stringResource(R.string.nl_tr_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list, key = { it.time.toString() + it.target }) { e ->
                        ListItem(headlineContent = { Text(e.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("→ ${e.target}: " + e.result, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { IconButton(onClick = { clipboard.setText(AnnotatedString(e.result)) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy_text)) } },
                            modifier = Modifier.clickable { shown = e })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = { NveTranslations.clear(ctx) }, enabled = list.isNotEmpty()) { Text(stringResource(R.string.nl_clear)) } })
    shown?.let { e ->
        AlertDialog(onDismissRequest = { shown = null }, title = { Text("→ ${e.target}") },
            text = { Column { Text(e.text, style = MaterialTheme.typography.bodySmall); HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text(e.result) } },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(e.result)); shown = null }) { Text(stringResource(R.string.copy_text)) } })
    }
}
