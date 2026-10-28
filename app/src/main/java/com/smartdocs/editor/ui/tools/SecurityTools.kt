package com.smartdocs.editor.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.sign.CertEncryption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (show) R.string.pc_cd_hide else R.string.pc_cd_show)) } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }
}

/** Tool "protect": encrypt with open / permissions passwords. */
@Composable
fun ProtectScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    var openPwd by remember { mutableStateOf("") }
    var ownerPwd by remember { mutableStateOf("") }
    var print by remember { mutableStateOf(true) }
    var copy by remember { mutableStateOf(true) }
    var modify by remember { mutableStateOf(false) }
    var annotate by remember { mutableStateOf(true) }
    var fill by remember { mutableStateOf(true) }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val processing = stringResource(R.string.processing)
    val needOne = stringResource(R.string.tl_password_required)
    val sameMsg = stringResource(R.string.ttcx_owner_same)
    val wrongPw = stringResource(R.string.password_wrong)
    val copiedMsg = stringResource(R.string.ttcx_copied)
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // audit 5 T-09 / C-4: an already protected input opens with the reader's password or asks for one
    val lock = rememberToolPassword(file) { file = null }
    // audit 5 P-2 / P-5: the owner password PdfOps.protect picked when none was typed (shown once, with a copy action)
    var generatedOwner by remember { mutableStateOf<String?>(null) }

    ToolScaffold(stringResource(R.string.tool_protect), nav, runner, snackbar = snackbar) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        PasswordField(openPwd, stringResource(R.string.password_open)) { openPwd = it }
        Text(stringResource(R.string.tl_password_open_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PasswordField(ownerPwd, stringResource(R.string.password_permissions)) { ownerPwd = it }
        Text(stringResource(R.string.tl_password_perm_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SwitchRow(stringResource(R.string.perm_print), print) { print = it }
                SwitchRow(stringResource(R.string.perm_copy), copy) { copy = it }
                SwitchRow(stringResource(R.string.perm_modify), modify) { modify = it }
                SwitchRow(stringResource(R.string.perm_annotate), annotate) { annotate = it }
                SwitchRow(stringResource(R.string.perm_fill_forms), fill) { fill = it }
            }
        }
        if (ownerPwd.isNotEmpty() && ownerPwd == openPwd) Text(sameMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        generatedOwner?.let { g ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ttcx_owner_generated), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(g, style = MaterialTheme.typography.titleSmall) }
                    }
                    IconButton(onClick = {
                        runCatching { clipboard.setText(androidx.compose.ui.text.AnnotatedString(g)) }
                        scope.launch { snackbar.showSnackbar(copiedMsg) }
                    }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.ttcx_copy_owner)) }
                }
            }
        }
        PrimaryButton(stringResource(R.string.tl_protect), enabled = file != null && lock.ready, icon = Icons.Default.Lock) {
            val src = file ?: return@PrimaryButton
            if (openPwd.isEmpty() && ownerPwd.isEmpty()) { runner.error = needOne; return@PrimaryButton }
            // audit 5 P-2: an owner password equal to the open one would give every reader full rights
            if (ownerPwd == openPwd) { runner.error = sameMsg; return@PrimaryButton }
            val o = openPwd; val w = ownerPwd; val p = print; val c = copy; val m = modify; val a = annotate; val f = fill; val cur = lock.password
            generatedOwner = null
            runner.run(processing) { withPasswordMessage(wrongPw) {
                val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, "protected"))
                val used = PdfOps.protect(src, out, o, w, p, c, m, a, f, currentPassword = cur)
                if (w.isEmpty()) withContext(Dispatchers.Main) { generatedOwner = used }
                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
            } }
        }
    }
}

/** Tool "unlock": remove encryption given the password. */
@Composable
fun UnlockScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    // audit 5 T-09: start from the reader's password for the file
    var pwd by remember(file) { mutableStateOf(com.smartdocs.editor.core.pdf.SessionPasswords.get(file).orEmpty()) }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val processing = stringResource(R.string.processing)
    val wrong = stringResource(R.string.password_wrong)

    ToolScaffold(stringResource(R.string.tool_unlock), nav, runner) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        val certProtected = remember(file) { file?.let { CertEncryption.isCertificateEncrypted(it) } == true }
        if (certProtected) Text(stringResource(R.string.ps_cert_unlock_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        PasswordField(pwd, stringResource(R.string.password)) { pwd = it }
        Text(stringResource(R.string.tl_unlock_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(stringResource(R.string.remove_security), enabled = file != null, icon = Icons.Default.LockOpen) {
            val src = file ?: return@PrimaryButton
            val p = pwd
            runner.run(processing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, "unlocked"))
                try { PdfOps.removeSecurity(src, p, out) } catch (e: Exception) {
                    out.delete()
                    val msg = e.message.orEmpty().lowercase()
                    if (e is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException || "password" in msg || "decrypt" in msg) throw IllegalStateException(wrong)
                    throw e
                }
                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
            }
        }
    }
}

private enum class FlattenMode { FORMS, ALL }

/** Tool "flatten": bake forms (or everything) into static page content. */
@Composable
fun FlattenScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    var mode by remember { mutableStateOf(FlattenMode.FORMS) }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val processing = stringResource(R.string.processing)
    val wrongPw = stringResource(R.string.password_wrong)
    val lock = rememberToolPassword(file) { file = null }

    ToolScaffold(stringResource(R.string.tool_flatten), nav, runner) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        FlattenOption(stringResource(R.string.tl_flatten_forms), stringResource(R.string.tl_flatten_forms_desc), mode == FlattenMode.FORMS) { mode = FlattenMode.FORMS }
        FlattenOption(stringResource(R.string.tl_flatten_all), stringResource(R.string.tl_flatten_all_desc), mode == FlattenMode.ALL) { mode = FlattenMode.ALL }
        PrimaryButton(stringResource(R.string.tl_flatten), enabled = file != null && lock.ready, icon = Icons.Default.Layers) {
            val src = file ?: return@PrimaryButton
            val m = mode; val pw = lock.password
            runner.run(processing) { withPasswordMessage(wrongPw) {
                val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, "flat"))
                if (m == FlattenMode.FORMS) PdfOps.flattenForms(src, out, pw) else PdfOps.flatten(ctx, src, out, password = pw)
                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
            } }
        }
    }
}

@Composable
private fun FlattenOption(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(onClick = onClick, Modifier.fillMaxWidth(), colors = colors) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ============================================================== certificate (public-key) protection

private enum class KeyPrompt { NONE, P12_RECIPIENT, OWN_RECIPIENT }

/**
 * Tool "cert_protect": encrypt a PDF for one or more recipients' certificates (AES-256, per-recipient
 * permissions), and open a certificate-protected PDF with a PKCS#12 key (writes a decrypted copy).
 */
@Composable
fun CertificateProtectScreen(nav: NavHostController, uri: String?) {
    var tab by remember { mutableIntStateOf(0) }
    val preset = rememberInputFile(uri)
    val startTab = remember(preset.value) { if (preset.value?.let { CertEncryption.isCertificateEncrypted(it) } == true) 1 else 0 }
    LaunchedEffect(startTab) { tab = startTab }
    val runner = rememberToolRunner()
    ToolScaffold(stringResource(R.string.ps_cert_protect_title), nav, runner) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.ps_cert_tab_encrypt)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.ps_cert_tab_open)) })
        }
        if (tab == 0) CertEncryptTab(preset, runner) else CertOpenTab(preset, runner)
    }
}

@Composable
private fun CertEncryptTab(fileState: MutableState<java.io.File?>, runner: com.smartdocs.editor.ui.common.ToolRunner) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by fileState
    var currentPwd by remember { mutableStateOf(com.smartdocs.editor.core.pdf.SessionPasswords.get(fileState.value).orEmpty()) }   // audit 5 T-09
    val recipients = remember { mutableStateListOf<CertEncryption.Recipient>() }
    var editing by remember { mutableStateOf<Int?>(null) }
    var prompt by remember { mutableStateOf(KeyPrompt.NONE) }
    var pendingP12 by remember { mutableStateOf<ByteArray?>(null) }
    var aes256 by remember { mutableStateOf(true) }
    var localError by remember { mutableStateOf<String?>(null) }
    var pickContacts by remember { mutableStateOf(false) }
    var noMeta by remember { mutableStateOf(false) }                       // nitro-protect2: "don't encrypt metadata"
    val unlock = remember { com.smartdocs.editor.ui.security.IdUnlockState() }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    // nitro-protect2: an already certificate-protected source is opened with the user's digital ID (change / remove)
    val certSource = remember(file) { file?.let { CertEncryption.isCertificateEncrypted(it) } == true }
    val processing = stringResource(R.string.processing)
    val readFailed = stringResource(R.string.ps_cert_read_failed)
    val blocked = stringResource(R.string.ps_cert_blocked)

    fun addAll(certs: List<java.security.cert.X509Certificate>) {
        certs.forEach { c -> if (recipients.none { it.certificate == c }) recipients += CertEncryption.Recipient(c) }
    }
    val cerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val certs = withContext(Dispatchers.IO) { runCatching { CertEncryption.readCertificates(ctx.contentResolver.openInputStream(u)!!) }.getOrNull() }
            if (certs.isNullOrEmpty()) localError = readFailed else { localError = null; addAll(certs) }
        }
    }
    val p12Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            pendingP12 = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() } }.getOrNull() }
            if (pendingP12 != null) prompt = KeyPrompt.P12_RECIPIENT else localError = readFailed
        }
    }
    val ownFile = remember { DigitalSignature.ownKeystoreFile(ctx) }

    FilePickRow(file, stringResource(R.string.tl_input_file), pick)
    if (certSource) com.smartdocs.editor.ui.security.CertChangeSection(unlock, enabled = !runner.running) {
        val src = file ?: return@CertChangeSection
        runner.run(processing) {
            val idn = unlock.identity(ctx)
            val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, "decrypted"))
            try { CertEncryption.decryptToFile(src, out, idn) } catch (e: Exception) { out.delete(); throw e }
            out
        }
    } else PasswordField(currentPwd, stringResource(R.string.ps_cert_current_password)) { currentPwd = it }
    FieldLabel(stringResource(R.string.ps_cert_recipients))
    if (recipients.isEmpty()) Text(stringResource(R.string.ps_cert_no_recipients), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    recipients.forEachIndexed { i, r -> RecipientRow(r, onPerms = { editing = i }, onRemove = { recipients.removeAt(i) }) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { cerPicker.launch(arrayOf("application/x-x509-ca-cert", "application/pkix-cert", "application/x-pkcs7-certificates", "application/octet-stream", "*/*")) }, Modifier.weight(1f)) {
            Icon(Icons.Default.Badge, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ps_cert_add_cer), maxLines = 1)
        }
        OutlinedButton(onClick = { p12Picker.launch(arrayOf("application/x-pkcs12", "*/*")) }, Modifier.weight(1f)) {
            Icon(Icons.Default.Key, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ps_cert_add_p12), maxLines = 1)
        }
    }
    OutlinedButton(onClick = { pickContacts = true }, Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Contacts, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.np_recipients_from_contacts))
    }
    if (ownFile.exists()) OutlinedButton(onClick = { prompt = KeyPrompt.OWN_RECIPIENT }, Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Person, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ps_cert_add_own))
    }
    Text(stringResource(R.string.ps_cert_self_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FieldLabel(stringResource(R.string.ps_cert_cipher))
    ChoiceChips(listOf(true, false), aes256, { if (it) "AES-256" else "AES-128" }) { aes256 = it }
    SwitchRow(stringResource(R.string.np2_cert_no_meta), noMeta) { noMeta = it }
    // Foxit §28: certificate-protection policies (recipients + permissions + cipher + metadata)
    com.smartdocs.editor.ui.security.CertPolicyRow(recipients.toList(), aes256, !noMeta) { p ->
        recipients.clear(); p.members.forEach { m -> recipients += CertEncryption.Recipient(m.certificate, m.perms) }
        aes256 = p.aes256; noMeta = !p.encryptMetadata
    }
    Text(stringResource(R.string.np2_cert_no_meta_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    PrimaryButton(stringResource(if (certSource) R.string.np2_cert_change else R.string.ps_cert_encrypt), enabled = file != null && recipients.isNotEmpty() && (!certSource || unlock.ready), icon = Icons.Default.EnhancedEncryption) {
        val src = file ?: return@PrimaryButton
        if (recipients.any { r -> CertEncryption.issues(r.certificate).any { CertEncryption.isBlocking(it) } }) { runner.error = blocked; return@PrimaryButton }
        val list = recipients.toList(); val pw = currentPwd.ifBlank { null }; val bits = if (aes256) 256 else 128
        val meta = !noMeta; val change = certSource
        runner.run(processing) {
            val idn = if (change) unlock.identity(ctx) else null
            val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, "cert"))
            CertEncryption.encrypt(src, out, list, bits, if (change) null else pw, encryptMetadata = meta, unlock = idn)
            out
        }
    }

    if (pickContacts) TrustedRecipientsDialog(already = recipients.map { it.certificate }, onDismiss = { pickContacts = false }) { certs ->
        pickContacts = false; localError = null; addAll(certs)
    }
    editing?.let { idx ->
        recipients.getOrNull(idx)?.let { r -> PermissionsDialog(r.perms, onDismiss = { editing = null }) { p -> recipients[idx] = r.copy(perms = p); editing = null } }
    }
    if (prompt != KeyPrompt.NONE) {
        val failed = stringResource(R.string.password_wrong)
        PasswordPrompt(onDismiss = { prompt = KeyPrompt.NONE }) { pw ->
            val which = prompt
            prompt = KeyPrompt.NONE
            scope.launch {
                val cert = runCatching {
                    if (which == KeyPrompt.OWN_RECIPIENT) CertEncryption.certificateFromPkcs12(ownFile.inputStream(), pw.toCharArray())
                    else CertEncryption.certificateFromPkcs12(java.io.ByteArrayInputStream(pendingP12!!), pw.toCharArray())
                }.getOrNull()
                if (cert == null) localError = failed else {
                    localError = null; addAll(listOf(cert))
                    // the own ID's public certificate, so "request certificate by e-mail" can attach it without the password
                    if (which == KeyPrompt.OWN_RECIPIENT) withContext(Dispatchers.IO) { runCatching { com.smartdocs.editor.core.sign.OwnPublicCert.remember(ctx, cert) } }
                }
            }
        }
    }
}

/**
 * Multi-select of the Trusted Contacts list as recipients. Each row shows the same expiry / key-usage / RSA issues as
 * the recipient list; blocking ones cannot be ticked, so the encrypt checks stay as they were.
 */
@Composable
private fun TrustedRecipientsDialog(already: List<java.security.cert.X509Certificate>, onDismiss: () -> Unit, onAdd: (List<java.security.cert.X509Certificate>) -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<com.smartdocs.editor.core.sign.TrustedCerts.Entry>?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { entries = withContext(Dispatchers.IO) { runCatching { com.smartdocs.editor.core.sign.TrustedCerts.list(ctx) }.getOrDefault(emptyList()) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.np_recipients_from_contacts)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val l = entries
                when {
                    l == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    l.isEmpty() -> Text(stringResource(R.string.np_contacts_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> l.forEach { e ->
                        val issues = remember(e.sha256) { CertEncryption.issues(e.cert) }
                        val blocking = issues.any { CertEncryption.isBlocking(it) }
                        val added = already.any { it == e.cert }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(added || e.sha256 in chosen, { on -> if (on) chosen += e.sha256 else chosen -= e.sha256 }, enabled = !blocking && !added)
                            Column(Modifier.weight(1f)) {
                                Text(DigitalSignature.friendlyName(e.cert.subjectX500Principal.name), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(stringResource(R.string.ps_cert_issuer_valid, DigitalSignature.friendlyName(e.cert.issuerX500Principal.name), com.smartdocs.editor.core.files.DocumentRepository.formatDate(e.cert.notAfter.time)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                issues.forEach { i -> Text(issueLabel(i), style = MaterialTheme.typography.labelSmall, color = if (CertEncryption.isBlocking(i)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onAdd(entries.orEmpty().filter { it.sha256 in chosen }.map { it.cert }) }) {
                Text(stringResource(R.string.np_add_count, chosen.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun issueLabel(i: CertEncryption.CertIssue): String = when (i) {
    CertEncryption.CertIssue.EXPIRED -> stringResource(R.string.ps_cert_issue_expired)
    CertEncryption.CertIssue.NOT_YET_VALID -> stringResource(R.string.ps_cert_issue_not_yet)
    CertEncryption.CertIssue.NO_ENCIPHERMENT -> stringResource(R.string.ps_cert_issue_usage)
    CertEncryption.CertIssue.NOT_RSA -> stringResource(R.string.ps_cert_issue_rsa)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientRow(r: CertEncryption.Recipient, onPerms: () -> Unit, onRemove: () -> Unit) {
    val issues = remember(r.certificate) { CertEncryption.issues(r.certificate) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Badge, null, tint = if (issues.any { CertEncryption.isBlocking(it) }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(stringResource(R.string.ps_cert_issuer_valid, DigitalSignature.friendlyName(r.certificate.issuerX500Principal.name), com.smartdocs.editor.core.files.DocumentRepository.formatDate(r.certificate.notAfter.time)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                if (issues.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    issues.forEach { i ->
                        val label = when (i) {
                            CertEncryption.CertIssue.EXPIRED -> stringResource(R.string.ps_cert_issue_expired)
                            CertEncryption.CertIssue.NOT_YET_VALID -> stringResource(R.string.ps_cert_issue_not_yet)
                            CertEncryption.CertIssue.NO_ENCIPHERMENT -> stringResource(R.string.ps_cert_issue_usage)
                            CertEncryption.CertIssue.NOT_RSA -> stringResource(R.string.ps_cert_issue_rsa)
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, color = if (CertEncryption.isBlocking(i)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                    }
                }
                Text(permSummary(r.perms), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onPerms) { Icon(Icons.Default.Tune, stringResource(R.string.ps_cert_permissions)) }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, stringResource(R.string.tl_remove), tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun permSummary(p: CertEncryption.Perms): String {
    val on = listOfNotNull(
        stringResource(R.string.perm_print).takeIf { p.print }, stringResource(R.string.perm_fill_forms).takeIf { p.fillForms },
        stringResource(R.string.perm_annotate).takeIf { p.annotate }, stringResource(R.string.ps_perm_assemble).takeIf { p.assemble },
        stringResource(R.string.perm_modify).takeIf { p.modify }, stringResource(R.string.perm_copy).takeIf { p.extract },
    )
    return if (on.isEmpty()) stringResource(R.string.ps_perm_read_only) else on.joinToString(" · ")
}

@Composable
private fun PermissionsDialog(initial: CertEncryption.Perms, onDismiss: () -> Unit, onSave: (CertEncryption.Perms) -> Unit) {
    var p by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ps_cert_permissions)) },
        text = {
            Column {
                SwitchRow(stringResource(R.string.perm_print), p.print) { p = p.withPrint(it) }
                SwitchRow(stringResource(R.string.perm_fill_forms), p.fillForms) { p = p.withFill(it) }
                SwitchRow(stringResource(R.string.perm_annotate), p.annotate) { p = p.withAnnotate(it) }
                SwitchRow(stringResource(R.string.ps_perm_assemble), p.assemble) { p = p.withAssemble(it) }
                SwitchRow(stringResource(R.string.perm_modify), p.modify) { p = p.withModify(it) }
                SwitchRow(stringResource(R.string.ps_perm_accessibility), p.copyAccessibility) { p = p.withCopyAccessibility(it) }
                SwitchRow(stringResource(R.string.perm_copy), p.extract) { p = p.withExtract(it) }
                Text(stringResource(R.string.ps_perm_dependency_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(p) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PasswordPrompt(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_enter)) },
        text = { PasswordField(pw, stringResource(R.string.ps_cert_key_password)) { pw = it } },
        confirmButton = { TextButton(onClick = { onConfirm(pw) }, enabled = pw.isNotEmpty()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CertOpenTab(fileState: MutableState<java.io.File?>, runner: com.smartdocs.editor.ui.common.ToolRunner) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by fileState
    var useOwn by remember { mutableStateOf(DigitalSignature.ownKeystoreFile(ctx).exists()) }
    var keyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var keyName by remember { mutableStateOf<String?>(null) }
    var pwd by remember { mutableStateOf("") }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val processing = stringResource(R.string.processing)
    val failed = stringResource(R.string.ps_cert_open_failed)
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            keyBytes = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() } }.getOrNull() }
            keyName = app.documents.displayName(u) ?: u.lastPathSegment
            useOwn = false
        }
    }
    val isCert = remember(file) { file?.let { CertEncryption.isCertificateEncrypted(it) } }
    FilePickRow(file, stringResource(R.string.tl_input_file), pick)
    if (isCert == false) Text(stringResource(R.string.ps_cert_not_cert_file), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    Text(stringResource(R.string.ps_cert_open_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (DigitalSignature.ownKeystoreFile(ctx).exists()) SwitchRow(stringResource(R.string.ps_cert_use_own_key), useOwn) { useOwn = it }
    if (!useOwn) OutlinedButton(onClick = { keyPicker.launch(arrayOf("application/x-pkcs12", "*/*")) }, Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(keyName ?: stringResource(R.string.ps_cert_pick_key), maxLines = 1)
    }
    PasswordField(pwd, stringResource(R.string.ps_cert_key_password)) { pwd = it }
    PrimaryButton(stringResource(R.string.ps_cert_open_now), enabled = file != null && pwd.isNotEmpty() && (useOwn || keyBytes != null), icon = Icons.Default.LockOpen) {
        val src = file ?: return@PrimaryButton
        val own = useOwn; val bytes = keyBytes; val pw = pwd
        runner.run(processing) {
            val key = if (own) DigitalSignature.ownKeystoreFile(ctx).readBytes() else bytes!!
            val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, "decrypted"))
            try { CertEncryption.decryptToFile(src, out, key, pw) } catch (e: Exception) { out.delete(); throw IllegalStateException(failed + (e.message?.let { "\n$it" } ?: "")) }
            out
        }
    }
}
