package com.smartdocs.editor.ui.security

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.sign.CertEncryption
import com.smartdocs.editor.core.sign.CertPolicies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Certificate-protection policies (Foxit §28): save the current recipients, permissions, cipher and metadata option
 * under a name; apply or delete saved ones. Hook: CertEncryptTab in ui/tools/SecurityTools.kt.
 */
@Composable
fun CertPolicyRow(
    recipients: List<CertEncryption.Recipient>, aes256: Boolean, encryptMetadata: Boolean,
    onApply: (CertPolicies.Policy) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<CertPolicies.Policy>>(emptyList()) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { runCatching { CertPolicies.load(ctx) }.getOrDefault(emptyList()) } }
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<String?>(null) }
    fun store(l: List<CertPolicies.Policy>) { list = l; scope.launch(Dispatchers.IO) { runCatching { CertPolicies.save(ctx, l) } } }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }) { Icon(Icons.Default.Policy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.fsg_policies)) }
        TextButton(onClick = { naming = "" }, enabled = recipients.isNotEmpty()) { Text(stringResource(R.string.fsg_policy_save)) }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.fsg_policies)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (list.isEmpty()) Text(stringResource(R.string.fsg_policy_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(p.members.joinToString(", ") { DigitalSignature.friendlyName(it.certificate.subjectX500Principal.name) } + " · " + (if (p.aes256) "AES-256" else "AES-128"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        TextButton(onClick = { onApply(p); open = false }) { Text(stringResource(R.string.fsg_policy_apply)) }
                        IconButton(onClick = { store(CertPolicies.delete(list, p.name)) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } },
    )
    naming?.let { initial ->
        var text by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(stringResource(R.string.fsg_policy_save)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.fsg_policy_name)) }) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    store(CertPolicies.upsert(list, CertPolicies.Policy(text.trim(), recipients.map { CertPolicies.Member(it.certificate, it.perms) }, aes256, encryptMetadata)))
                    naming = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
