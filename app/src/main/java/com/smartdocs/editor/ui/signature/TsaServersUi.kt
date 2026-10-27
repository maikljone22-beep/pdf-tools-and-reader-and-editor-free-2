package com.smartdocs.editor.ui.signature

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.sign.TsaServer
import com.smartdocs.editor.core.sign.TsaServers

/** Time-stamp server chips (tap = use for this signature) + a manager: add / edit / delete / set default (nitro-quicksign). */
@Composable
internal fun TsaServerPicker(state: AdvancedSignState) {
    var manage by remember { mutableStateOf(false) }
    val current = TsaServers.find(state.tsaServers, state.tsaUrl)
    Text(current?.url ?: state.tsaUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = { manage = true }, label = { Text(stringResource(R.string.nq_tsa_manage)) }, leadingIcon = { Icon(Icons.Default.Settings, null, Modifier.size(18.dp)) })
        state.tsaServers.forEach { s ->
            val icon = when {
                s.id == state.tsaDefault -> Icons.Default.Star
                s.requireTrusted -> Icons.Default.VerifiedUser
                s.username.isNotBlank() -> Icons.Default.Lock
                else -> null
            }
            FilterChip(current?.id == s.id, { state.tsaUrl = s.url }, label = { Text(s.name, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                leadingIcon = icon?.let { { Icon(it, null, Modifier.size(16.dp)) } })
        }
    }
    if (manage) TsaServersDialog(state) { manage = false }
}

@Composable
private fun TsaServersDialog(state: AdvancedSignState, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<TsaServer?>(null) }
    var confirmDelete by remember { mutableStateOf<TsaServer?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nq_tsa_servers)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.tsaServers.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            val tags = listOfNotNull(
                                if (s.builtIn) stringResource(R.string.nq_tsa_builtin) else null,
                                if (s.username.isNotBlank()) stringResource(R.string.nq_tsa_login_set) else null,
                                if (s.requireTrusted) stringResource(R.string.nq_tsa_trusted_only) else null,
                            )
                            Text(tags.joinToString(" · ").ifEmpty { s.url }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = { state.setDefaultTsa(if (state.tsaDefault == s.id) null else s.id) }) {
                            Icon(if (state.tsaDefault == s.id) Icons.Default.Star else Icons.Default.StarBorder, stringResource(R.string.nq_tsa_set_default))
                        }
                        IconButton(onClick = { editing = s }) { Icon(Icons.Default.Edit, stringResource(R.string.nq_edit)) }
                        if (!s.builtIn) IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            TextButton(onClick = { editing = TsaServer(TsaServers.newId(), "", "") }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nq_tsa_add))
            }
        },
    )
    editing?.let { s ->
        TsaServerEditDialog(s, isNew = state.tsaServers.none { it.id == s.id }, hasPassword = state.hasTsaPassword(s.id), onDismiss = { editing = null }) { edited, pw ->
            state.saveTsaServer(edited, pw); editing = null
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.nq_tsa_delete_q, s.name)) },
            confirmButton = { TextButton(onClick = { state.deleteTsaServer(s.id); confirmDelete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Add / edit one server. The password field starts empty: empty keeps the stored password; "remove" clears it. */
@Composable
private fun TsaServerEditDialog(server: TsaServer, isNew: Boolean, hasPassword: Boolean, onDismiss: () -> Unit, onSave: (TsaServer, String?) -> Unit) {
    var name by remember { mutableStateOf(server.name) }
    var url by remember { mutableStateOf(server.url) }
    var user by remember { mutableStateOf(server.username) }
    var password by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    var trusted by remember { mutableStateOf(server.requireTrusted) }
    val u = url.trim()
    val urlOk = server.builtIn || ((u.startsWith("http://", true) || u.startsWith("https://", true)) && TsaServers.hostOf(u).contains('.'))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.nq_tsa_add else R.string.nq_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !server.builtIn, label = { Text(stringResource(R.string.nq_name)) })
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !server.builtIn, isError = !urlOk,
                    label = { Text(stringResource(R.string.ps_sign_tsa_url)) })
                Text(stringResource(R.string.nq_tsa_login_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nq_tsa_user)) })
                OutlinedTextField(password, { password = it; clearPassword = false }, Modifier.fillMaxWidth(), singleLine = true, enabled = user.isNotBlank(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(R.string.nq_tsa_password)) },
                    supportingText = if (hasPassword && !clearPassword && user.isNotBlank()) { { Text(stringResource(R.string.nq_tsa_password_kept)) } } else null)
                if (hasPassword && user.isNotBlank())
                    TextButton(onClick = { clearPassword = true; password = "" }, enabled = !clearPassword) { Text(stringResource(R.string.nq_tsa_password_remove)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.nq_tsa_require_trusted), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.nq_tsa_require_trusted_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(trusted, { trusted = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(server.copy(name = name.trim().ifBlank { TsaServers.hostOf(u) }, url = u, username = user.trim(), requireTrusted = trusted),
                    when { clearPassword -> ""; password.isNotEmpty() -> password; else -> null })
            }, enabled = urlOk) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
