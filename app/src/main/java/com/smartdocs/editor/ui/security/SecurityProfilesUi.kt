package com.smartdocs.editor.ui.security

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.redact.SecurityProfile
import com.smartdocs.editor.core.redact.SecurityProfiles
import kotlinx.coroutines.launch

private sealed interface NameAsk {
    data object New : NameAsk
    data class Rename(val old: String) : NameAsk
}

/**
 * Security profiles of the detailed protect tool: apply one, save the current settings as a new profile, update a
 * profile with the current settings (edit), rename and delete. [current] carries the screen's settings (its name
 * is ignored). Passwords are never stored.
 */
@Composable
fun SecurityProfilesDialog(current: SecurityProfile, summary: @Composable (SecurityProfile) -> String, onApply: (SecurityProfile) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<SecurityProfile>?>(null) }
    var ask by remember { mutableStateOf<NameAsk?>(null) }
    var confirmDelete by remember { mutableStateOf<SecurityProfile?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { list = SecurityProfiles.load(ctx) }
    val updatedFmt = stringResource(R.string.np_profile_updated)
    fun commit(new: List<SecurityProfile>) { list = new; scope.launch { SecurityProfiles.save(ctx, new) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Bookmarks, null) },
        title = { Text(stringResource(R.string.np_profiles_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val l = list
                when {
                    l == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    l.isEmpty() -> Text(stringResource(R.string.np_profiles_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> l.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { onApply(p) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(summary(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                            Box {
                                IconButton(onClick = { menuFor = p.name }) { Icon(Icons.Default.MoreVert, stringResource(R.string.np_profile_more)) }
                                DropdownMenu(menuFor == p.name, { menuFor = null }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.np_profile_apply)) }, leadingIcon = { Icon(Icons.Default.Check, null) },
                                        onClick = { menuFor = null; onApply(p) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.np_profile_update)) }, leadingIcon = { Icon(Icons.Default.Save, null) },
                                        onClick = { menuFor = null; commit(SecurityProfiles.upsert(l, current.copy(name = p.name))); note = updatedFmt.format(p.name) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.np_profile_rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = { menuFor = null; ask = NameAsk.Rename(p.name) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                        onClick = { menuFor = null; confirmDelete = p })
                                }
                            }
                        }
                    }
                }
                note?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Text(stringResource(R.string.np_profiles_no_passwords), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { ask = NameAsk.New }, enabled = list != null) { Text(stringResource(R.string.np_profile_save_current)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )

    ask?.let { a ->
        val l = list.orEmpty()
        val initial = (a as? NameAsk.Rename)?.old ?: ""
        var name by remember(a) { mutableStateOf(initial) }
        val clash = SecurityProfiles.find(l, name)?.takeIf { !it.name.equals(initial, true) }
        val blank = name.isBlank()
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(if (a is NameAsk.Rename) R.string.np_profile_rename else R.string.np_profile_save_current)) },
            text = {
                OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.np_profile_name)) },
                    isError = clash != null && a is NameAsk.Rename,
                    supportingText = if (clash != null) { { Text(stringResource(if (a is NameAsk.Rename) R.string.np_profile_name_used else R.string.np_profile_will_replace)) } } else null)
            },
            confirmButton = {
                TextButton(enabled = !blank && !(clash != null && a is NameAsk.Rename), onClick = {
                    ask = null
                    commit(if (a is NameAsk.Rename) SecurityProfiles.rename(l, a.old, name) else SecurityProfiles.upsert(l, current.copy(name = name)))
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    confirmDelete?.let { p ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.delete)) }, text = { Text(p.name) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; commit(SecurityProfiles.delete(list.orEmpty(), p.name)) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
