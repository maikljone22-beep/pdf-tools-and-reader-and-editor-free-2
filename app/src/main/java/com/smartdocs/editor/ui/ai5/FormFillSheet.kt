package com.smartdocs.editor.ui.ai5

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.DocIndexer
import com.smartdocs.editor.core.ai5.FillSuggestion
import com.smartdocs.editor.core.ai5.FormFillAi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Form-fill suggestions: profile matches + AI proposals, each editable and toggleable. [onValues] receives the
 * accepted map (field name → value) when the caller fills in memory (reader forms bar); otherwise [onFile]
 * receives a filled copy written to the documents folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormFillSheet(file: File, password: String?, onValues: ((Map<String, String>) -> Unit)? = null, onFile: (File) -> Unit = {}, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<FillSuggestion>?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    val on = remember { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    val useAi = AiClient.available(settings)
    LaunchedEffect(file) {
        runCatching {
            val profile = app.db.fillProfile().all().first()
            val idx = runCatching { DocIndexer.get(ctx, file, password) }.getOrNull()
            FormFillAi.suggest(ctx, client, file, password, profile, idx, useAi)
        }.onSuccess { l -> list = l; l.forEach { values[it.field.name] = it.value; on[it.field.name] = true } }
            .onFailure { error = if (AiCalls.isQuota(it)) ctx.getString(R.string.a5_err_quota) else ctx.getString(R.string.a5_err_failed, it.message ?: "") }
        busy = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.a5_form_fill), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.a5_form_fill_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val l = list
            if (l != null && l.isEmpty()) Text(stringResource(R.string.a5_form_none))
            if (!l.isNullOrEmpty()) {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(l, key = { it.field.name }) { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on[s.field.name] == true, { on[s.field.name] = it })
                            OutlinedTextField(values[s.field.name].orEmpty(), { values[s.field.name] = it }, Modifier.weight(1f), singleLine = true,
                                label = { Text(s.field.label.ifBlank { s.field.name } + if (s.fromProfile) " · " + stringResource(R.string.a5_from_profile) else " · AI") })
                        }
                    }
                }
                Button(onClick = {
                    val accepted = l.filter { on[it.field.name] == true }.associate { it.field.name to values[it.field.name].orEmpty() }
                    if (onValues != null) { onValues(accepted); onDismiss(); return@Button }
                    busy = true
                    scope.launch {
                        runCatching {
                            val out = app.documents.uniqueFile(app.documents.docsDir, file.nameWithoutExtension + "_filled")
                            FormFillAi.apply(ctx, file, password, accepted, out); out
                        }.onSuccess { onFile(it); onDismiss() }.onFailure { error = it.message }
                        busy = false
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.a5_apply)) }
            }
        }
    }
}
