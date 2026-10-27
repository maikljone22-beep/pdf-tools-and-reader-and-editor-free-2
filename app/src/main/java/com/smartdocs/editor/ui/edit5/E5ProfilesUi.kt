package com.smartdocs.editor.ui.edit5

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import com.smartdocs.editor.core.edit5.E5Profile
import com.smartdocs.editor.core.edit5.E5ProfileList
import com.smartdocs.editor.core.edit5.E5Profiles

/**
 * Saved profiles of a watermark / background / header & footer (Nitro profiles): load one, save the current settings
 * as a new or existing profile, rename, copy, delete, and import or export them as a JSON file.
 * [current] gives the JSON of the settings on screen; [onLoad] receives a profile's JSON.
 */
@Composable
fun E5ProfileBar(kind: String, current: () -> String, onLoad: (String) -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    var list by remember(kind) { mutableStateOf(E5Profiles.list(ctx, kind)) }
    var selected by remember(kind) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<String?>(null) }   // "save" or "rename"
    fun store(next: List<E5Profile>) { list = next; E5Profiles.save(ctx, kind, next) }
    val importedMsg = stringResource(R.string.nt_profiles_imported)
    val badFileMsg = stringResource(R.string.nt_profiles_bad_file)
    val exportedMsg = stringResource(R.string.nt_profiles_exported)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(E5ProfileList.export(kind, list).toByteArray()) } }
            .onSuccess { onMessage(exportedMsg) }.onFailure { onMessage(it.message ?: it.toString()) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            E5ProfileList.import(kind, list, text)
        }.onSuccess { next -> val added = next.size - list.size; store(next); onMessage(importedMsg.format(added)) }.onFailure { onMessage(badFileMsg) }
    }
    E5Section(stringResource(R.string.nt_profiles)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { pick = true }, Modifier.fillMaxWidth(), enabled = list.isNotEmpty()) {
                    Text(selected ?: stringResource(if (list.isEmpty()) R.string.nt_profiles_none else R.string.nt_profiles_pick), Modifier.weight(1f), maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(pick, { pick = false }) {
                    list.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { pick = false; selected = p.name; onLoad(p.json) }) }
                }
            }
            IconButton(onClick = { naming = "save" }) { Icon(Icons.Default.Save, stringResource(R.string.nt_profiles_save)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(menu, { menu = false }) {
                    val sel = selected
                    DropdownMenuItem(text = { Text(stringResource(R.string.nt_profiles_rename)) }, leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                        enabled = sel != null, onClick = { menu = false; naming = "rename" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.nt_profiles_copy)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        enabled = sel != null, onClick = { menu = false; sel?.let { store(E5ProfileList.duplicate(list, it)) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.nt_profiles_delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        enabled = sel != null, onClick = { menu = false; sel?.let { store(E5ProfileList.delete(list, it)); selected = null } })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.nt_profiles_import)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) },
                        onClick = { menu = false; importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.nt_profiles_export)) }, leadingIcon = { Icon(Icons.Default.IosShare, null) },
                        enabled = list.isNotEmpty(), onClick = { menu = false; exporter.launch("smartdocs_${kind}_profiles.json") })
                }
            }
        }
    }
    naming?.let { what ->
        var name by remember(what) { mutableStateOf(if (what == "rename") selected.orEmpty() else selected.orEmpty()) }
        val exists = list.any { it.name == name.trim() }
        AlertDialog(onDismissRequest = { naming = null },
            title = { Text(stringResource(if (what == "save") R.string.nt_profiles_save else R.string.nt_profiles_rename)) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.nt_profiles_name)) })
                    if (what == "save" && exists) Text(stringResource(R.string.nt_profiles_overwrite), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank() && !(what == "rename" && exists), onClick = {
                if (what == "save") store(E5ProfileList.put(list, name, current())) else selected?.let { store(E5ProfileList.rename(list, it, name)) }
                selected = name.trim(); naming = null
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
