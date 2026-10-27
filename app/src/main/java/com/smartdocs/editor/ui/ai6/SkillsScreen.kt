package com.smartdocs.editor.ui.ai6

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai6.Skill
import com.smartdocs.editor.core.ai6.SkillStore
import com.smartdocs.editor.core.ai6.Skills
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.rememberPdfPicker

/** Route `tool/ai_skills` (AI-6): preset + custom skills; enable / disable, add, edit, delete, export and import JSON. */
@Composable
fun SkillsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val store = remember { SkillStore(ctx) }
    val clipboard = LocalClipboardManager.current
    var list by remember { mutableStateOf(store.all()) }
    var editing by remember { mutableStateOf<Skill?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val importFile = rememberPdfPicker(mimes = arrayOf("application/json", "text/plain", "application/octet-stream")) { picked ->
        val n = picked.firstOrNull()?.let { f -> runCatching { store.importJson(f.readText()) }.getOrDefault(0) } ?: 0
        list = store.all(); message = ctx.getString(R.string.ai6h_skills_imported, n)
    }
    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.ai6h_skills), onBack = { nav.popBackStack() }) {
                IconButton(onClick = {
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, store.exportJson()), null)) }
                }) { Icon(Icons.Default.Share, stringResource(R.string.ai6h_export)) }
                IconButton(onClick = {
                    val n = clipboard.getText()?.text?.let { store.importJson(it) } ?: 0
                    if (n == 0) importFile() else { list = store.all(); message = ctx.getString(R.string.ai6h_skills_imported, n) }
                }) { Icon(Icons.Default.ContentPaste, stringResource(R.string.ai6h_import)) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = Skill("", "", "") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.ai6h_new_skill)) })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.ai6h_skills_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.id }) { s ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("/" + s.id + " · " + s.name, style = MaterialTheme.typography.titleSmall)
                            Text(s.instruction, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(s.enabled, { v -> store.upsert(s.copy(enabled = v)); list = store.all() })
                        if (!s.preset) {
                            IconButton(onClick = { editing = s }) { Icon(Icons.Default.Edit, stringResource(R.string.a5_edit)) }
                            IconButton(onClick = { store.delete(s.id); list = store.all() }) { Icon(Icons.Default.Delete, stringResource(R.string.a5_delete)) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { s ->
        var name by remember(s) { mutableStateOf(s.name) }
        var ins by remember(s) { mutableStateOf(s.instruction) }
        var image by remember(s) { mutableStateOf(s.wantsImage) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(stringResource(if (s.id.isEmpty()) R.string.ai6h_new_skill else R.string.a5_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.ai6h_skill_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ins, { ins = it.take(2000) }, label = { Text(stringResource(R.string.ai6h_skill_instruction)) }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(image, { image = it }); Text(stringResource(R.string.ai6h_skill_image), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && ins.isNotBlank(), onClick = {
                    val id = s.id.ifEmpty { Skills.slug(name).let { base -> var c = base; var i = 2; while (list.any { it.id == c }) { c = base + i; i++ }; c } }
                    store.upsert(Skill(id, name.trim(), ins.trim(), enabled = true, preset = false, wantsImage = image)); list = store.all(); editing = null
                }) { Text(stringResource(R.string.a5_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.a5_cancel)) } })
    }
    message?.let { m -> AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.a5_close)) } }) }
}
