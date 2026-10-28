package com.smartdocs.editor.ui.signature

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
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
import com.smartdocs.editor.core.sign.CertInspector
import com.smartdocs.editor.core.sign.DigitalIds
import com.smartdocs.editor.ui.common.SectionTitle
import com.smartdocs.editor.ui.reader.view.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.cert.X509Certificate

/**
 * Certificate tab ("My Digital IDs"): several IDs (created here, imported PKCS#12 files, Android KeyChain
 * aliases), unlock one for signing, view its details, export it (.p12 with its password, or the public .cer),
 * share it, delete it. A new self-signed ID takes name, organization, unit, e-mail, country, usage, key type
 * and validity. Reports the unlocked identity through [onIdentity].
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
    val pwMismatch = stringResource(R.string.ns_id_password_mismatch)
    val exportedMsg = stringResource(R.string.ns_id_exported)

    var ids by remember { mutableStateOf(DigitalIds.list(ctx)) }
    fun refresh() { ids = DigitalIds.list(ctx) }
    var selected by rememberSaveable { mutableStateOf(ids.firstOrNull()?.id) }
    var password by remember { mutableStateOf("") }   // audit D17: secrets never go into saved state
    var details by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DigitalIds.Entry?>(null) }
    val entry = ids.firstOrNull { it.id == selected }

    fun unlock() {
        val e = entry ?: return
        if (e.kind == DigitalIds.Kind.FILE && password.isEmpty()) { error = pwRequired; return }
        scope.launch {
            busy = true; error = null
            runCatching { DigitalIds.open(ctx, e, password.toCharArray()) }
                .onSuccess { onIdentity(it); info = null; refresh(); password = "" }.onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    // --- import PKCS#12 into the list ---
    var importPassword by remember { mutableStateOf("") }   // audit D17: secrets never go into saved state
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingUri = uri }
    fun importPicked() {
        val uri = pendingUri ?: return
        if (importPassword.isEmpty()) { error = pwRequired; return }
        scope.launch {
            busy = true; error = null
            runCatching {
                val stream = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open")
                DigitalIds.importPkcs12(ctx, stream, importPassword.toCharArray())
            }.onSuccess { (e, id) -> onIdentity(id); refresh(); selected = e.id; pendingUri = null; info = null; importPassword = "" }.onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    // --- export ---
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = exportBytes; exportBytes = null
        if (uri != null && bytes != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("cannot write") } }
                .onSuccess { info = exportedMsg }.onFailure { error = it.message ?: it.toString() }
        }
    }
    fun certOf(e: DigitalIds.Entry): X509Certificate? = e.cert ?: identity?.certificate?.takeIf { e.kind == DigitalIds.Kind.KEYCHAIN || e.id == "own" }
    fun share(file: File, mime: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }
    fun shareFile(name: String, bytes: ByteArray, mime: String) {
        val dir = File(ctx.cacheDir, "share_ids").apply { mkdirs() }
        share(File(dir, name).apply { writeBytes(bytes) }, mime)
    }

    // --- create ---
    var genName by rememberSaveable { mutableStateOf(settings.authorName) }
    var genOrg by rememberSaveable { mutableStateOf("") }
    var genUnit by rememberSaveable { mutableStateOf("") }
    var genEmail by rememberSaveable { mutableStateOf("") }
    var genCountry by rememberSaveable { mutableStateOf("") }
    var genUsage by rememberSaveable { mutableStateOf(DigitalIds.Usage.BOTH) }
    var genKey by rememberSaveable { mutableStateOf(DigitalIds.KeyType.RSA_2048) }
    var genYears by rememberSaveable { mutableIntStateOf(5) }
    var genPw by remember { mutableStateOf("") }   // audit D17: secrets never go into saved state
    var genPw2 by remember { mutableStateOf("") }   // audit D17: secrets never go into saved state
    fun generate() {
        if (genPw.isEmpty()) { error = pwRequired; return }
        if (genPw != genPw2) { error = pwMismatch; return }
        val spec = DigitalIds.Spec(genName.trim(), genOrg.trim(), genUnit.trim(), genEmail.trim(), genCountry.trim(), genUsage, genKey, genYears)
        scope.launch {
            busy = true; error = null
            runCatching { DigitalIds.create(ctx, spec, genPw.toCharArray()) }
                .onSuccess { (e, id) -> onIdentity(id); refresh(); selected = e.id; info = generatedMsg; genPw = ""; genPw2 = "" }
                .onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ElevatedCard(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Badge, null, tint = if (identity != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sa_cert_selected), style = MaterialTheme.typography.labelMedium)
                        Text(identity?.let { DigitalSignature.friendlyName(it.subject) } ?: stringResource(R.string.sa_cert_none), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (identity != null) TextButton(onClick = { details = !details }) { Text(stringResource(R.string.g6_cert_details)) }
                }
                if (details && identity != null) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CertDetailsBlock(CertInspector.details(identity.certificate))
                    if (identity.chain.size > 1) Text(stringResource(R.string.g6_cert_chain, identity.chain.joinToString(" ← ") { DigitalSignature.friendlyName(it.subjectX500Principal.name) }), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        info?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        SectionTitle(stringResource(R.string.ns_my_ids))
        if (ids.isEmpty()) Text(stringResource(R.string.ns_ids_empty), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        ids.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == e.id, { selected = e.id; password = "" })
                Column(Modifier.weight(1f)) {
                    Text(e.label.ifBlank { stringResource(R.string.sa_cert_own) }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        (if (e.kind == DigitalIds.Kind.KEYCHAIN) stringResource(R.string.ns_id_keychain) else stringResource(R.string.ns_id_file)) +
                            (e.cert?.let { " · " + stringResource(R.string.ns_id_expires, com.smartdocs.editor.core.files.DocumentRepository.formatDate(it.notAfter.time)) } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { confirmDelete = e }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
        }
        if (entry != null) {
            if (entry.kind == DigitalIds.Kind.FILE) OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp),
                label = { Text(stringResource(R.string.sa_cert_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { unlock() }, enabled = !busy) { Icon(Icons.Default.Key, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ns_id_use)) }
                val cert = certOf(entry)
                if (entry.kind == DigitalIds.Kind.FILE) OutlinedButton(onClick = {
                    exportBytes = entry.file?.readBytes(); exporter.launch((entry.label.ifBlank { "my_id" }) + ".p12")
                }) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ns_id_export_p12)) }
                if (cert != null) OutlinedButton(onClick = {
                    exportBytes = cert.encoded; exporter.launch(DigitalSignature.friendlyName(cert.subjectX500Principal.name) + ".cer")
                }) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ns_id_export_cer)) }
                if (cert != null) OutlinedButton(onClick = {
                    runCatching { shareFile(DigitalSignature.friendlyName(cert.subjectX500Principal.name).replace(Regex("[^\\p{L}\\p{N}_ -]"), "_") + ".cer", cert.encoded, "application/pkix-cert") }
                        .onFailure { error = it.message }
                }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ns_id_share_cer)) }
                if (entry.kind == DigitalIds.Kind.FILE) OutlinedButton(onClick = {
                    runCatching { entry.file?.let { shareFile((entry.label.ifBlank { "my_id" }).replace(Regex("[^\\p{L}\\p{N}_ -]"), "_") + ".p12", it.readBytes(), "application/x-pkcs12") } }
                        .onFailure { error = it.message }
                }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ns_id_share_p12)) }
            }
            if (entry.kind == DigitalIds.Kind.FILE) Text(stringResource(R.string.ns_id_export_note), Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle(stringResource(R.string.ns_id_keychain_add))
        OutlinedButton(onClick = {
            val act = ctx.findActivity() ?: return@OutlinedButton
            DigitalIds.chooseKeyChain(act) { alias ->
                if (alias != null) scope.launch {
                    refresh(); selected = "kc:$alias"
                    busy = true
                    runCatching { DigitalIds.loadKeyChain(ctx, alias) }.onSuccess { onIdentity(it) }.onFailure { error = it.message ?: it.toString() }
                    busy = false
                }
            }
        }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Icon(Icons.Default.PhoneAndroid, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ns_id_keychain_pick)) }
        Text(stringResource(R.string.ns_id_keychain_note), Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle(stringResource(R.string.sa_pick_cert))
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text(pendingUri?.lastPathSegment ?: stringResource(R.string.sa_pick_cert), maxLines = 1)
        }
        OutlinedTextField(importPassword, { importPassword = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.sa_cert_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(onClick = { importPicked() }, Modifier.padding(horizontal = 16.dp), enabled = pendingUri != null && !busy) { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open)) }

        SectionTitle(stringResource(R.string.sa_generate_cert))
        OutlinedTextField(genName, { genName = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.sa_cert_name)) }, singleLine = true)
        OutlinedTextField(genOrg, { genOrg = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.sa_cert_org)) }, singleLine = true)
        OutlinedTextField(genUnit, { genUnit = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.ns_id_unit)) }, singleLine = true)
        OutlinedTextField(genEmail, { genEmail = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.sa_cert_email)) }, singleLine = true)
        OutlinedTextField(genCountry, { genCountry = it.filter { c -> c.isLetter() }.take(2).uppercase() }, Modifier.fillMaxWidth().padding(16.dp, 4.dp),
            label = { Text(stringResource(R.string.ns_id_country)) }, singleLine = true)
        Text(stringResource(R.string.ns_id_usage), Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(DigitalIds.Usage.SIGNING to R.string.ns_id_usage_sign, DigitalIds.Usage.ENCRYPTION to R.string.ns_id_usage_encrypt, DigitalIds.Usage.BOTH to R.string.ns_id_usage_both)
                .forEach { (u, s) -> FilterChip(genUsage == u, { genUsage = u }, label = { Text(stringResource(s)) }) }
        }
        Text(stringResource(R.string.ns_id_key), Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DigitalIds.KeyType.entries.forEach { k -> FilterChip(genKey == k, { genKey = k }, label = { Text(when { k == DigitalIds.KeyType.ED25519 -> "Ed25519"; k.curve != null -> "EC P-${k.size}"; else -> "RSA ${k.size}" }) }) }
        }
        if (genKey.algorithm != "RSA") Text(stringResource(if (genKey == DigitalIds.KeyType.ED25519) R.string.ns2_id_ed_note else R.string.ns_id_ec_note), Modifier.padding(16.dp, 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ns_id_validity, genYears), Modifier.width(140.dp))
            Slider(genYears.toFloat(), { genYears = it.toInt().coerceIn(1, 30) }, valueRange = 1f..30f, steps = 28, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(genPw, { genPw = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.sa_cert_new_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(genPw2, { genPw2 = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), label = { Text(stringResource(R.string.ns_id_password_again)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(onClick = { generate() }, Modifier.padding(16.dp, 4.dp), enabled = !busy) { Text(stringResource(R.string.sa_generate_cert)) }
    }
    confirmDelete?.let { e ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(if (e.kind == DigitalIds.Kind.KEYCHAIN) R.string.ns_id_delete_keychain else R.string.ns_id_delete_confirm)) },
            confirmButton = { TextButton(onClick = { DigitalIds.delete(ctx, e); if (selected == e.id) selected = null; refresh(); confirmDelete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
