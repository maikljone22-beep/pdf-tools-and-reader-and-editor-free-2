package com.smartdocs.editor.ui.signature

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.ui.common.SectionTitle
import kotlinx.coroutines.launch

/**
 * Certificate tab: load a PKCS#12 from the file system, use the on-device self-signed certificate,
 * or generate a new self-signed certificate. Reports the loaded identity through [onIdentity].
 */
@Composable
internal fun CertificateTab(identity: DigitalSignature.Identity?, onIdentity: (DigitalSignature.Identity?) -> Unit) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val generatedMsg = stringResource(R.string.sa_cert_generated)
    val pwRequired = stringResource(R.string.sa_password_required)

    // --- import PKCS#12 ---
    var p12Password by rememberSaveable { mutableStateOf("") }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingUri = uri }

    fun loadPicked() {
        val uri = pendingUri ?: return
        if (p12Password.isEmpty()) { error = pwRequired; return }
        scope.launch {
            busy = true; error = null
            runCatching {
                val stream = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open")
                DigitalSignature.loadPkcs12(stream, p12Password.toCharArray())
            }.onSuccess { onIdentity(it); info = null }.onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    // --- own certificate ---
    val ownFile = remember { DigitalSignature.ownKeystoreFile(ctx) }
    var ownExists by remember { mutableStateOf(ownFile.exists()) }
    var ownPassword by rememberSaveable { mutableStateOf("") }
    var genName by rememberSaveable { mutableStateOf(settings.authorName) }
    var genOrg by rememberSaveable { mutableStateOf("") }
    var genEmail by rememberSaveable { mutableStateOf("") }

    fun useOwn() {
        if (ownPassword.isEmpty()) { error = pwRequired; return }
        scope.launch {
            busy = true; error = null
            runCatching { DigitalSignature.loadPkcs12(ownFile, ownPassword.toCharArray()) }
                .onSuccess { onIdentity(it); info = null }.onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    fun generate() {
        if (ownPassword.isEmpty()) { error = pwRequired; return }
        scope.launch {
            busy = true; error = null
            runCatching { DigitalSignature.generateSelfSigned(ownFile, genName.trim(), genOrg.trim(), genEmail.trim(), ownPassword.toCharArray()) }
                .onSuccess { onIdentity(it); ownExists = true; info = generatedMsg }.onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ElevatedCard(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Badge, null, tint = if (identity != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.sa_cert_selected), style = MaterialTheme.typography.labelMedium)
                    Text(identity?.let { DigitalSignature.friendlyName(it.subject) } ?: stringResource(R.string.sa_cert_none), style = MaterialTheme.typography.bodyLarge)
                    identity?.let { Text(it.certificate.notAfter.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        info?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        SectionTitle(stringResource(R.string.sa_pick_cert))
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text(pendingUri?.lastPathSegment ?: stringResource(R.string.sa_pick_cert), maxLines = 1)
        }
        OutlinedTextField(p12Password, { p12Password = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.sa_cert_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(onClick = { loadPicked() }, Modifier.padding(horizontal = 16.dp), enabled = pendingUri != null && !busy) { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open)) }

        SectionTitle(stringResource(R.string.sa_cert_own))
        OutlinedTextField(ownPassword, { ownPassword = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(if (ownExists) R.string.sa_cert_password else R.string.sa_cert_new_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        if (ownExists) Button(onClick = { useOwn() }, Modifier.padding(horizontal = 16.dp), enabled = !busy) { Text(stringResource(R.string.sa_cert_own)) }

        SectionTitle(stringResource(R.string.sa_generate_cert))
        OutlinedTextField(genName, { genName = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.sa_cert_name)) }, singleLine = true)
        OutlinedTextField(genOrg, { genOrg = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.sa_cert_org)) }, singleLine = true)
        OutlinedTextField(genEmail, { genEmail = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.sa_cert_email)) }, singleLine = true)
        Button(onClick = { generate() }, Modifier.padding(16.dp, 4.dp), enabled = !busy) { Text(stringResource(R.string.sa_generate_cert)) }
    }
}
