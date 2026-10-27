package com.smartdocs.editor.ui.security

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.sign.DigitalIds

/** The digital ID (file or KeyChain) that opens a certificate-protected file, and its password for a file ID. */
class IdUnlockState {
    var entry by mutableStateOf<DigitalIds.Entry?>(null)
    var password by mutableStateOf("")
    val ready: Boolean get() = entry?.let { it.kind == DigitalIds.Kind.KEYCHAIN || password.isNotEmpty() } == true

    suspend fun identity(ctx: Context): DigitalSignature.Identity =
        DigitalIds.open(ctx, entry ?: error("no digital ID"), password.toCharArray())
}

/**
 * "Change settings" of certificate protection (row 4): the source is certificate protected, so the user picks the
 * digital ID it was encrypted for. The recipient list below then re-encrypts it into a new file; [onRemove] writes an
 * unprotected copy instead.
 */
@Composable
fun CertChangeSection(state: IdUnlockState, enabled: Boolean, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val ids = remember { runCatching { DigitalIds.list(ctx) }.getOrDefault(emptyList()) }
    LaunchedEffect(ids) { if (state.entry == null) state.entry = ids.firstOrNull() }
    var open by remember { mutableStateOf(false) }
    val ownLabel = stringResource(R.string.np2_cert_own_id)
    fun label(e: DigitalIds.Entry) = e.label.ifBlank { e.cert?.let { DigitalSignature.friendlyName(it.subjectX500Principal.name) } ?: ownLabel }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VerifiedUser, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.np2_cert_change_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.np2_cert_change_hint), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ids.isEmpty()) {
                Text(stringResource(R.string.np2_cert_no_ids), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Box {
                OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
                    Text(state.entry?.let { e -> label(e) + if (e.kind == DigitalIds.Kind.KEYCHAIN) " · " + stringResource(R.string.np2_cert_keychain) else "" }
                        ?: stringResource(R.string.np2_cert_pick_id), Modifier.weight(1f), maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(open, { open = false }) {
                    ids.forEach { e ->
                        DropdownMenuItem(text = { Text(label(e) + if (e.kind == DigitalIds.Kind.KEYCHAIN) " · " + stringResource(R.string.np2_cert_keychain) else "", maxLines = 1) },
                            onClick = { state.entry = e; state.password = ""; open = false })
                    }
                }
            }
            if (state.entry?.kind == DigitalIds.Kind.FILE) OutlinedTextField(state.password, { state.password = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.ps_cert_key_password)) }, visualTransformation = PasswordVisualTransformation())
            OutlinedButton(onClick = onRemove, enabled = enabled && state.ready, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Icon(Icons.Default.LockOpen, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.np2_cert_remove))
            }
        }
    }
}
