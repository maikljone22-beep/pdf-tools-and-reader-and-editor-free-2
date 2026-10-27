package com.smartdocs.editor.ui.signature

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.sign.CertInspector
import com.smartdocs.editor.core.sign.TrustedCerts
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Trusted certificates: import .cer / .crt / .pem, list with details, delete. */
@Composable
fun TrustedCertsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<TrustedCerts.Entry>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<TrustedCerts.Entry?>(null) }
    fun reload() { scope.launch { entries = withContext(Dispatchers.IO) { TrustedCerts.list(ctx) } } }
    LaunchedEffect(Unit) { reload() }
    val addedFmt = stringResource(R.string.g6_certs_added)
    val badFile = stringResource(R.string.g6_certs_bad_file)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var added = 0; var failed = 0
            for (u in uris) {
                runCatching { ctx.contentResolver.openInputStream(u)?.let { TrustedCerts.import(ctx, it) } ?: 0 }
                    .onSuccess { added += it }.onFailure { failed++ }
            }
            message = if (failed > 0 && added == 0) badFile else addedFmt.format(added)
            reload()
        }
    }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.g6_certs_title), onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { runCatching { picker.launch(arrayOf("application/x-x509-ca-cert", "application/pkix-cert", "application/x-pem-file", "application/octet-stream", "*/*")) } },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.g6_certs_import)) })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(stringResource(R.string.g6_certs_note), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            if (entries.isEmpty()) EmptyState(Icons.Default.VerifiedUser, stringResource(R.string.g6_certs_empty))
            else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.sha256 }) { e ->
                    val d = remember(e.sha256) { CertInspector.details(e.cert) }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Verified, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(DigitalSignature.friendlyName(d.subject), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { confirmDelete = e }) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete)) }
                            }
                            CertDetailsBlock(d)
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { e ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.delete)) },
            text = { Text(DigitalSignature.friendlyName(e.cert.subjectX500Principal.name)) },
            confirmButton = { TextButton(onClick = { TrustedCerts.delete(e); confirmDelete = null; reload() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Certificate details lines: issuer, serial, validity, key usage, EKU, algorithm, SHA-256 fingerprint. */
@Composable
fun CertDetailsBlock(d: CertInspector.Details) {
    val small = MaterialTheme.typography.bodySmall
    Text("${stringResource(R.string.sa_cert_subject)}: ${d.subject}", style = small)
    Text("${stringResource(R.string.sa_cert_issuer)}: ${d.issuer}", style = small)
    Text("${stringResource(R.string.g6_cert_serial)}: ${d.serial}", style = small, fontFamily = FontFamily.Monospace)
    Text("${stringResource(R.string.sa_cert_validity)}: ${DocumentRepository.formatDate(d.notBefore.time)} – ${DocumentRepository.formatDate(d.notAfter.time)}",
        style = small, color = if (d.expiredNow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if (d.expiredNow) Text(stringResource(R.string.g6_cert_expired), style = small, color = MaterialTheme.colorScheme.error)
    Text("${stringResource(R.string.g6_cert_key_usage)}: ${if (d.keyUsage.isEmpty()) "—" else d.keyUsage.map { keyUsageLabel(it) }.joinToString("، ")}", style = small)
    if (d.extendedKeyUsage.isNotEmpty()) Text("${stringResource(R.string.g6_cert_eku)}: ${d.extendedKeyUsage.joinToString(", ")}", style = small)
    Text("${stringResource(R.string.g6_cert_algorithm)}: ${d.signatureAlgorithm} · ${d.publicKey}", style = small)
    if (d.selfSigned) Text(stringResource(R.string.g6_cert_self_signed), style = small, color = MaterialTheme.colorScheme.tertiary)
    Text("SHA-256: ${d.sha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("SHA-1: ${d.sha1}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (d.md5.isNotEmpty()) Text("MD5: ${d.md5}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun keyUsageLabel(b: com.smartdocs.editor.core.sign.CertFormat.KeyUsageBit): String = stringResource(when (b) {
    com.smartdocs.editor.core.sign.CertFormat.KeyUsageBit.DIGITAL_SIGNATURE -> R.string.g6_ku_digital_signature
    com.smartdocs.editor.core.sign.CertFormat.KeyUsageBit.NON_REPUDIATION -> R.string.g6_ku_non_repudiation
    com.smartdocs.editor.core.sign.CertFormat.KeyUsageBit.KEY_ENCIPHERMENT -> R.string.g6_ku_key_encipherment
    com.smartdocs.editor.core.sign.CertFormat.KeyUsageBit.DATA_ENCIPHERMENT -> R.string.g6_ku_data_encipherment
    com.smartdocs.editor.core.sign.CertFormat.KeyUsageBit.KEY_AGREEMENT -> R.string.g6_ku_key_agreement
    com.smartdocs.editor.core.sign.CertFormat.KeyUsageBit.KEY_CERT_SIGN -> R.string.g6_ku_cert_sign
    com.smartdocs.editor.core.sign.CertFormat.KeyUsageBit.CRL_SIGN -> R.string.g6_ku_crl_sign
    com.smartdocs.editor.core.sign.CertFormat.KeyUsageBit.ENCIPHER_ONLY -> R.string.g6_ku_encipher_only
    com.smartdocs.editor.core.sign.CertFormat.KeyUsageBit.DECIPHER_ONLY -> R.string.g6_ku_decipher_only
})
