package com.smartdocs.editor.ui.signature

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.sign.CertInspector
import com.smartdocs.editor.core.sign.TrustPath
import java.security.cert.X509Certificate

/** Trust status (chain ends at a trusted certificate?) + expandable certificate details, inside a verify card. */
@Composable
fun CertTrustSection(cert: CertInspector.SigCert, onTrust: (X509Certificate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val trusted = cert.trust == TrustPath.Status.TRUSTED
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (trusted) Icons.Default.GppGood else Icons.Default.GppMaybe, null, Modifier.size(20.dp),
            tint = if (trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(6.dp))
        Text(
            if (trusted) stringResource(R.string.g6_trusted, cert.anchor ?: "") + (if (cert.systemAnchor) " · " + stringResource(R.string.ns_trust_system_source) else "")
            else stringResource(R.string.g6_untrusted),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            color = if (trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        )
        if (cert.details != null) IconButton(onClick = { open = !open }) { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(R.string.g6_cert_details)) }
    }
    if (cert.chain.size > 1) Text(stringResource(R.string.g6_cert_chain, cert.chain.joinToString(" ← ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (open && cert.details != null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { CertDetailsBlock(cert.details) }
    }
    val signer = cert.signer
    if (!trusted && signer != null) TextButton(onClick = { onTrust(signer) }) { Text(stringResource(R.string.g6_trust_this)) }
}
