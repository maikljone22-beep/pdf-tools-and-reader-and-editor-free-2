package com.smartdocs.editor.ui.ai5

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.AiStore
import com.smartdocs.editor.core.ai5.Space
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.rememberPdfPicker
import java.io.File

/**
 * Spaces (route `tool/ai_spaces`): named sets of PDFs questioned together, each with optional instructions and
 * its own saved chats and study creations. Opening a Space goes to the hub with `space:<id>`.
 */
@Composable
fun SpacesScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val store = remember { AiStore(ctx) }
    var spaces by remember { mutableStateOf(store.spaces()) }
    var editing by remember { mutableStateOf<Space?>(null) }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.a5_spaces), onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = Space(AiStore.newId(), "", emptyList()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.a5_new_space)) })
        },
    ) { pad ->
        if (spaces.isEmpty()) EmptyState(Icons.Default.Workspaces, stringResource(R.string.a5_spaces_empty), Modifier.padding(pad).fillMaxSize())
        else LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(spaces, key = { it.id }) { s ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.tool("ai", "space:" + s.id)) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Workspaces, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifBlank { "…" }, style = MaterialTheme.typography.titleSmall)
                            Text(s.files.joinToString("، ") { File(it).nameWithoutExtension }, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editing = s }) { Icon(Icons.Default.Edit, stringResource(R.string.a5_edit)) }
                        IconButton(onClick = { store.deleteSpace(s.id); spaces = store.spaces() }) { Icon(Icons.Default.Delete, stringResource(R.string.a5_delete)) }
                    }
                }
            }
        }
    }
    editing?.let { sp -> SpaceEditor(sp, onSave = { store.saveSpace(it); spaces = store.spaces(); editing = null }, onDismiss = { editing = null }) }
}

@Composable
private fun SpaceEditor(initial: Space, onSave: (Space) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var persona by remember { mutableStateOf(initial.persona) }
    var files by remember { mutableStateOf(initial.files) }
    val pick = rememberPdfPicker(multiple = true) { picked -> files = (files + picked.map { it.absolutePath }).distinct().take(20) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.name.isBlank()) R.string.a5_new_space else R.string.a5_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.a5_space_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(persona, { persona = it }, label = { Text(stringResource(R.string.a5_space_persona)) }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                files.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(File(p).name, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { files = files - p }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }
                    }
                }
                OutlinedButton(onClick = pick) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.a5_add_files)) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(initial.copy(name = name.trim(), persona = persona.trim(), files = files)) }, enabled = name.isNotBlank() && files.isNotEmpty()) { Text(stringResource(R.string.a5_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.a5_cancel)) } })
}
