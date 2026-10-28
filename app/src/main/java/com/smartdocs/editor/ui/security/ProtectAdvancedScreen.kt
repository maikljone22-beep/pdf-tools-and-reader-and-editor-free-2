package com.smartdocs.editor.ui.security

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.redact.ChangeLevel
import com.smartdocs.editor.core.redact.EncAlgo
import com.smartdocs.editor.core.redact.EncScope
import com.smartdocs.editor.core.redact.Permissions
import com.smartdocs.editor.core.redact.PrintLevel
import com.smartdocs.editor.core.redact.ProtectAdvanced
import com.smartdocs.editor.core.redact.ProtectOptions
import com.smartdocs.editor.core.redact.SecurityProfile
import com.smartdocs.editor.ui.common.*
import com.smartdocs.editor.ui.search.PasswordDialog
import com.smartdocs.editor.ui.search.fileOf
import kotlinx.coroutines.launch
import java.io.File

/** Tool `protect_advanced` (J6): algorithm, open / permissions passwords, print and change levels, the 8 permissions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectAdvancedScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val runner = rememberToolRunner()
    var file by remember { mutableStateOf(fileOf(uri)) }
    // T-09: the password the reader opened this document with
    var current by remember { mutableStateOf(com.smartdocs.editor.core.pdf.SessionPasswords.get(file)) }
    var signed by remember { mutableStateOf(false) }
    /** audit 5 P-L: a generated owner password, shown in a dialog with a copy action before the result sheet. */
    var generatedOwner by remember { mutableStateOf<String?>(null) }
    var encrypted by remember { mutableStateOf(false) }
    var askCurrent by remember { mutableStateOf(false) }
    var openPw by remember { mutableStateOf("") }
    var openPw2 by remember { mutableStateOf("") }
    var ownerPw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var algo by remember { mutableStateOf(EncAlgo.AES_256) }
    var perms by remember { mutableStateOf(Permissions()) }
    var restrict by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf(EncScope.ALL) }
    var result by remember { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var showProfiles by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> file = f; current = null } }

    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        // an open password makes the plain load fail: the file is encrypted and its password is needed
        val needPassword = runCatching { PdfOps.isEncrypted(f); false }.getOrElse { it is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException }
        encrypted = needPassword || runCatching { PdfOps.isEncrypted(f) }.getOrDefault(false)
        if (needPassword && current == null) askCurrent = true
    }
    // audit 5 R-4: a signed input gets a warning (the protected copy keeps no signature values)
    LaunchedEffect(file, current) {
        val f = file ?: run { signed = false; return@LaunchedEffect }
        val pw = current
        signed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.smartdocs.editor.core.pdf.SignedSave.isSigned(f, pw) }
    }
    val wrongPwMsg = stringResource(R.string.ttrd_wrong_password)
    val failedMsg = stringResource(R.string.ttrd_failed)
    val latinMsg = stringResource(R.string.ttrd_latin1_only)
    // nitro-protect2 "Change Settings": the current algorithm, permissions and metadata option (passwords stay empty)
    var needsOwner by remember { mutableStateOf(false) }
    var prefilled by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file, current, encrypted) {
        val f = file ?: return@LaunchedEffect
        if (!encrypted) { needsOwner = false; return@LaunchedEffect }
        val pw = current
        // audit 5 P-11: a wrong current password is said and asked again (it was swallowed, and Protect failed later)
        val read = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { PdfOps.loadRaw(f, pw).use { com.smartdocs.editor.core.security.SecurityInfoReader.of(it) } }
        }
        val info = read.getOrElse { e ->
            if (e is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException && pw != null) {
                current = null; askCurrent = true
                snack.showSnackbar(wrongPwMsg)
            }
            return@LaunchedEffect
        }
        // opened without the permissions password: the author's restrictions may not be changed (or dropped) here
        needsOwner = com.smartdocs.editor.core.security.ReaderRestrictions.restricted(info)
        val key = f.absolutePath + "|" + pw
        if (prefilled == key) return@LaunchedEffect
        com.smartdocs.editor.core.redact.ProtectPrefills.from(info)?.let { p ->
            algo = p.algo; perms = p.permissions; restrict = p.restrict; scope = p.scope; prefilled = key
        }
    }
    val mismatch = openPw.isNotEmpty() && openPw != openPw2
    val effective = if (restrict) perms else Permissions()
    // audit 5b L2: "restrict" with every permission still allowed and no open password protects nothing (it failed later)
    val noRestriction = openPw.isEmpty() && restrict && perms == Permissions()
    val nothing = (openPw.isEmpty() && !restrict) || noRestriction || (scope == EncScope.ATTACHMENTS_ONLY && openPw.isEmpty())
    // audit 5 P-2: revision 2-4 algorithms would turn non-Latin (e.g. Arabic) characters into '?'
    val notLatin = !ProtectAdvanced.passwordsFit(ProtectOptions(openPw, if (restrict) ownerPw else "", algo))
    // audit 5 P-5: an owner password equal to the open password would let anyone who opens the file lift the restrictions
    val ownerSame = restrict && ownerPw.isNotEmpty() && ownerPw == openPw
    val msg = stringResource(R.string.s5_protecting)
    val ownerFmt = stringResource(R.string.s5_owner_generated)
    fun protect() {
        val src = file ?: return
        val o = ProtectOptions(openPw, if (restrict) ownerPw else "", algo, effective, scope)
        val typedOwner = if (restrict) ownerPw else ""
        val pw = current
        runner.run(msg) {
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_protected")
            val owner = try { ProtectAdvanced.protect(src, out, pw, o) }
                catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw IllegalStateException(wrongPwMsg, e) }
                catch (e: com.smartdocs.editor.core.redact.NonLatinPasswordException) { throw IllegalStateException(latinMsg, e) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { throw IllegalStateException(failedMsg, e) }
            // a generated owner password (none typed, or it equalled the open password) must reach the user
            val generated = restrict && owner != typedOwner
            info = if (generated) ownerFmt.format(owner) else null
            generatedOwner = if (generated) owner else null
            runCatching { app.documents.touchRecent(out) }
            com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
        }
    }
    LaunchedEffect(runner.result) { runner.result?.let { result = it; runner.result = null } }
    LaunchedEffect(runner.error) { runner.error?.let { snack.showSnackbar(it); runner.error = null } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            AppTopBar(stringResource(R.string.s5_protect_title), onBack = { nav.popBackStack() }) {
                IconButton(onClick = { showProfiles = true }) { Icon(Icons.Default.Bookmarks, stringResource(R.string.np_profiles_title)) }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(onClick = ::protect, enabled = file != null && !mismatch && !nothing && !runner.running && !needsOwner && !notLatin && !ownerSame, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Icon(Icons.Default.EnhancedEncryption, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s5_protect_apply))
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            FilePickRow(file, stringResource(R.string.open_file), pick)
            if (encrypted) Text(stringResource(R.string.s5_already_encrypted), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (prefilled != null) Text(stringResource(R.string.np2_change_prefilled), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (needsOwner) Text(stringResource(R.string.np2_change_needs_owner), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (needsOwner) TextButton(onClick = { askCurrent = true }) { Text(stringResource(R.string.np2_change_enter_owner)) }
            if (signed) Text(stringResource(R.string.ttrd_signed_warning), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Section(stringResource(R.string.s5_algorithm))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val all = EncAlgo.values()
                all.forEachIndexed { i, a -> SegmentedButton(algo == a, { algo = a; if (!a.aes) scope = EncScope.ALL }, SegmentedButtonDefaults.itemShape(i, all.size)) { Text(algoLabel(a), maxLines = 1) } }
            }
            if (algo == EncAlgo.RC4_128) Text(stringResource(R.string.s5_rc4_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            if (algo == EncAlgo.RC4_40) Rc40Warning()
            Section(stringResource(R.string.s5_enc_scope))
            EncScope.values().forEach { sc ->
                RadioRow(scope == sc, enabled = sc == EncScope.ALL || algo.aes, onSelect = { scope = sc }) {
                    Column {
                        Text(scopeLabel(sc), style = MaterialTheme.typography.bodyMedium)
                        if (sc != EncScope.ALL) Text(stringResource(if (sc == EncScope.ALL_BUT_METADATA) R.string.s5_scope_meta_hint else R.string.s5_scope_att_hint),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (scope == EncScope.ATTACHMENTS_ONLY && openPw.isEmpty()) Text(stringResource(R.string.s5_scope_att_needs_pw), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Section(stringResource(R.string.s5_open_password))
            val vt = if (show) VisualTransformation.None else PasswordVisualTransformation()
            OutlinedTextField(openPw, { openPw = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.password)) }, visualTransformation = vt,
                trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (show) R.string.pc_cd_hide else R.string.pc_cd_show)) } })
            OutlinedTextField(openPw2, { openPw2 = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.s5_confirm_password)) }, visualTransformation = vt,
                isError = mismatch, supportingText = if (mismatch) { { Text(stringResource(R.string.s5_password_mismatch)) } } else null)
            if (notLatin) Text(latinMsg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.s5_restrict), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Switch(restrict, { restrict = it })
            }
            if (restrict) {
                OutlinedTextField(ownerPw, { ownerPw = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.s5_owner_password)) }, visualTransformation = vt,
                    isError = ownerSame, supportingText = { Text(stringResource(if (ownerSame) R.string.ttrd_owner_same else R.string.s5_owner_hint)) })
                Section(stringResource(R.string.s5_printing))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val all = PrintLevel.values()
                    val labels = listOf(R.string.s5_print_none, R.string.s5_print_low, R.string.s5_print_high)
                    all.forEachIndexed { i, l -> SegmentedButton(perms.printLevel == l, { perms = perms.withPrint(l) }, SegmentedButtonDefaults.itemShape(i, all.size)) { Text(stringResource(labels[i]), maxLines = 1) } }
                }
                Section(stringResource(R.string.s5_changes))
                ChangeLevel.values().forEach { l ->
                    RadioRow(perms.changeLevel == l, onSelect = { perms = perms.withChanges(l) }) {
                        Text(changeLabel(l), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Section(stringResource(R.string.s5_permissions))
                PermRow(stringResource(R.string.s5_perm_print), perms.print) { perms = perms.copy(print = it, printHigh = it && perms.printHigh) }
                PermRow(stringResource(R.string.s5_perm_print_high), perms.printHigh, enabled = perms.print) { perms = perms.copy(printHigh = it) }
                PermRow(stringResource(R.string.s5_perm_modify), perms.modify) { perms = perms.copy(modify = it) }
                PermRow(stringResource(R.string.s5_perm_assemble), perms.assemble) { perms = perms.copy(assemble = it) }
                PermRow(stringResource(R.string.s5_perm_copy), perms.copy) { perms = perms.copy(copy = it) }
                PermRow(stringResource(R.string.s5_perm_access), perms.accessibility) { perms = perms.copy(accessibility = it) }
                PermRow(stringResource(R.string.s5_perm_annotate), perms.annotate) { perms = perms.copy(annotate = it) }
                PermRow(stringResource(R.string.s5_perm_forms), perms.fillForms) { perms = perms.copy(fillForms = it) }
                if (noRestriction) Text(stringResource(R.string.ttrd_nothing_to_protect), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.s5_protect_note), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showProfiles) {
        val appliedFmt = stringResource(R.string.np_profile_applied)
        val scopeSnack = rememberCoroutineScope()
        SecurityProfilesDialog(
            current = SecurityProfile("", algo, scope, restrict, perms),
            summary = { profileSummary(it) },
            onApply = { p ->
                // passwords are left as typed: a profile never carries them
                algo = p.algo; scope = if (p.algo.aes) p.scope else EncScope.ALL; restrict = p.restrict; perms = p.permissions
                showProfiles = false
                scopeSnack.launch { snack.showSnackbar(appliedFmt.format(p.name)) }
            },
            onDismiss = { showProfiles = false },
        )
    }
    if (askCurrent) PasswordDialog(onDismiss = { askCurrent = false }) { current = it; askCurrent = false }
    if (runner.running) ProgressDialog(runner.message)
    val owner = generatedOwner
    if (owner != null && result != null) OwnerPasswordDialog(owner) { generatedOwner = null }
    else result?.let { ResultSheet(it, nav, onDismiss = { result = null }, extraInfo = info) }
}

/** Strong warning for 40-bit ARC4: anyone can remove it, so it only fits files for very old readers. */
@Composable
private fun Rc40Warning() {
    Card(Modifier.fillMaxWidth().padding(top = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(R.string.np_rc4_40_warn_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.np_rc4_40_warn_body), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One line under a profile name: algorithm, scope and whether editing / printing are restricted. */
@Composable
private fun profileSummary(p: SecurityProfile): String {
    val parts = mutableListOf(algoLabel(p.algo), scopeLabel(p.scope))
    parts += if (p.restrict) stringResource(R.string.np_profile_restricted) else stringResource(R.string.np_profile_unrestricted)
    return parts.joinToString(" · ")
}

@Composable
private fun Section(t: String) = Text(t, Modifier.padding(top = 14.dp, bottom = 6.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

/** audit 5 P-L: one toggleable row, so TalkBack reads the permission with its checkbox. */
@Composable
private fun PermRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) = ToggleRow(label, checked, enabled, onChange)

/** audit 5 P-L: the generated owner password with a copy action (it is not shown again). */
@Composable
private fun OwnerPasswordDialog(owner: String, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ttrd_owner_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ttrd_owner_body), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(owner, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (copied) Text(stringResource(R.string.ttrd_copied), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(owner)); copied = true }) {
                Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ttrd_copy))
            }
        })
}

@Composable
private fun algoLabel(a: EncAlgo) = stringResource(when (a) { EncAlgo.AES_256 -> R.string.s5_aes256; EncAlgo.AES_128 -> R.string.s5_aes128; EncAlgo.RC4_128 -> R.string.s5_rc4; EncAlgo.RC4_40 -> R.string.np_rc4_40 })

@Composable
private fun scopeLabel(sc: EncScope) = stringResource(when (sc) {
    EncScope.ALL -> R.string.s5_scope_all
    EncScope.ALL_BUT_METADATA -> R.string.s5_scope_meta
    EncScope.ATTACHMENTS_ONLY -> R.string.s5_scope_att
})

@Composable
private fun changeLabel(l: ChangeLevel) = stringResource(when (l) {
    ChangeLevel.NONE -> R.string.s5_change_none
    ChangeLevel.ASSEMBLY -> R.string.s5_change_assembly
    ChangeLevel.FORMS -> R.string.s5_change_forms
    ChangeLevel.ANNOTATE_FORMS -> R.string.s5_change_annotate
    ChangeLevel.ALL_BUT_EXTRACT -> R.string.s5_change_all
})
