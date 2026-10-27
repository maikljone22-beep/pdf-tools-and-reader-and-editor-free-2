package com.smartdocs.editor.ui.signature

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SigTarget
import com.smartdocs.editor.core.sign.Revocation
import com.smartdocs.editor.core.sign.SigFields
import com.smartdocs.editor.core.sign.SignPrefs
import com.smartdocs.editor.core.sign.SignProfiles
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.*
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Certificate-based signing + verification. Tabs: sign / verify / certificate. */
@Composable
fun DigitalSignScreen(nav: NavHostController, uri: String?) {
    var file by remember { mutableStateOf(uri?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull() }) }
    // a tap on a signature field in the reader: sign into it, or validate it
    val request = remember { SigFieldRequest.take(file) }
    var tab by rememberSaveable { mutableIntStateOf(if (request?.verify == true) 1 else 0) }
    var identity by remember { mutableStateOf<DigitalSignature.Identity?>(null) }
    val pick = rememberPdfPicker { files -> files.firstOrNull()?.let { file = it } }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.sa_digital_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab) {
                listOf(R.string.sa_tab_sign, R.string.sa_tab_verify, R.string.sa_tab_certificate).forEachIndexed { i, s ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(s)) })
                }
            }
            when (tab) {
                0 -> SignTab(nav, file, identity, request?.field?.takeIf { !request.verify }, onPick = pick, onNeedCert = { tab = 2 })
                1 -> VerifyTab(nav, file, request?.field?.takeIf { request.verify }, onPick = pick)
                else -> CertificateTab(identity) { identity = it }
            }
        }
    }
}

/** Nitro reason presets; the field stays free text. */
private val REASONS = listOf(R.string.ns_reason_author, R.string.ns_reason_reviewed, R.string.ns_reason_approving, R.string.ns_reason_attest, R.string.ns_reason_agree)

private enum class LockMode { NONE, ALL, INCLUDE, EXCLUDE }

@Composable
private fun SignTab(nav: NavHostController, file: File?, identity: DigitalSignature.Identity?, requestedField: String?, onPick: () -> Unit, onNeedCert: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(identity) { mutableStateOf(identity?.let { DigitalSignature.friendlyName(it.subject) } ?: settings.authorName) }
    var location by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var reasonMenu by remember { mutableStateOf(false) }
    var visible by rememberSaveable { mutableStateOf(requestedField != null) }
    var visibleText by rememberSaveable { mutableStateOf("") }
    var useSaved by rememberSaveable { mutableStateOf(false) }
    var savedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val signatures by app.db.signatures().all().collectAsStateWithLifecycle(initialValue = emptyList())
    var showResult by remember { mutableStateOf(false) }
    LaunchedEffect(runner.result) { if (runner.result != null) showResult = true }
    val signingMsg = stringResource(R.string.sa_signing)
    val needFile = stringResource(R.string.sa_select_file_first)
    val signedInfo = stringResource(R.string.sa_signed_info)
    var localError by remember { mutableStateOf<String?>(null) }
    val adv = rememberAdvancedSignState()
    var report by remember(file) { mutableStateOf<com.smartdocs.editor.core.sign.PreSignAnalyzer.Report?>(null) }
    var showWarning by remember { mutableStateOf(false) }
    var signReport by remember { mutableStateOf<DigitalSignature.SignReport?>(null) }
    val sealMissing = stringResource(R.string.ps_sign_seal_none)
    LaunchedEffect(file) { report = file?.let { f -> runCatching { com.smartdocs.editor.core.sign.PreSignAnalyzer.analyze(f) }.getOrNull() } }
    LaunchedEffect(report) { if (report?.canCertify == false && adv.certify > 0) adv.certify = 0 }
    // gaps6: appearance designer + changes since the last signed revision
    val appearance = rememberSigAppearanceState()
    var changes by remember(file) { mutableStateOf<com.smartdocs.editor.core.sign.RevisionSnapshot.Report?>(null) }
    var showChanges by remember { mutableStateOf(false) }
    var changesAck by remember(file) { mutableStateOf(false) }
    var confirmChanges by remember { mutableStateOf(false) }
    LaunchedEffect(file) { changes = file?.let { f -> runCatching { com.smartdocs.editor.core.sign.RevisionSnapshot.changes(f) }.getOrNull() } }
    val today = remember { DocumentRepository.formatDate(System.currentTimeMillis()) }

    // Nitro: signature fields (sign into an empty one), free placement, field lock
    var sigFields by remember(file) { mutableStateOf<List<SigFields.Field>>(emptyList()) }
    var formFields by remember(file) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                PdfOps.loadRaw(f).use { d ->
                    sigFields = SigFields.list(d)
                    formFields = d.documentCatalog.getAcroForm(null)?.fieldTree?.filter { it !is PDNonTerminalField && it !is PDSignatureField }?.map { it.fullyQualifiedName }.orEmpty()
                }
            }
        }
    }
    var targetField by rememberSaveable(file) { mutableStateOf(requestedField) }
    var placePage by rememberSaveable(file) { mutableIntStateOf(0) }
    var placeBox by remember(file) { mutableStateOf<PlaceBox?>(null) }
    var placing by remember { mutableStateOf(false) }
    var lockMode by rememberSaveable { mutableStateOf(LockMode.NONE) }
    var lockFields by remember { mutableStateOf(setOf<String>()) }
    var lockNoChanges by rememberSaveable { mutableStateOf(false) }
    var askName by remember { mutableStateOf(SignPrefs.askFileName(ctx)) }
    var nameDialog by remember { mutableStateOf<String?>(null) }

    // named signature profiles
    val profilesFile = remember { File(ctx.filesDir, "sign_profiles.json") }
    var profiles by remember { mutableStateOf(SignProfiles.load(profilesFile)) }
    var profileMenu by remember { mutableStateOf(false) }
    var saveProfileName by remember { mutableStateOf<String?>(null) }
    fun current(pName: String) = SignProfiles.Profile(
        pName, name, location, reason, contact, visible, adv.certify, adv.pades, adv.tsa, adv.ltv,
        lock = when (lockMode) { LockMode.NONE -> null; LockMode.ALL -> SigFields.LockAction.ALL; LockMode.INCLUDE -> SigFields.LockAction.INCLUDE; LockMode.EXCLUDE -> SigFields.LockAction.EXCLUDE },
        lockFields = lockFields.toList(), lockNoChanges = lockNoChanges,
    )
    fun apply(p: SignProfiles.Profile) {
        if (p.signerName.isNotBlank()) name = p.signerName
        location = p.location; reason = p.reason; contact = p.contact; visible = p.visible
        adv.certify = if (report?.canCertify == false) 0 else p.certify; adv.pades = p.pades; adv.tsa = p.timestamp; adv.ltv = p.ltv
        lockMode = when (p.lock) { null -> LockMode.NONE; SigFields.LockAction.ALL -> LockMode.ALL; SigFields.LockAction.INCLUDE -> LockMode.INCLUDE; SigFields.LockAction.EXCLUDE -> LockMode.EXCLUDE }
        lockFields = p.lockFields.toSet(); lockNoChanges = p.lockNoChanges
    }
    fun storeProfiles(list: List<SignProfiles.Profile>) { profiles = list; scope.launch(Dispatchers.IO) { runCatching { SignProfiles.save(profilesFile, list) } } }

    val emptyFields = sigFields.filter { !it.signed }

    fun lock(): SigFields.Lock? {
        val p = if (lockNoChanges) 1 else null
        return when (lockMode) {
            LockMode.NONE -> p?.let { SigFields.Lock(SigFields.LockAction.ALL, docPermission = it) }
            LockMode.ALL -> SigFields.Lock(SigFields.LockAction.ALL, docPermission = p)
            LockMode.INCLUDE -> SigFields.Lock(SigFields.LockAction.INCLUDE, lockFields.toList(), p)
            LockMode.EXCLUDE -> SigFields.Lock(SigFields.LockAction.EXCLUDE, lockFields.toList(), p)
        }
    }

    fun doSign(outName: String) {
        val f = file ?: run { localError = needFile; return }
        val id = identity ?: run { onNeedCert(); return }
        localError = null
        adv.persist()
        val opts = adv.options(DigitalSignature.SignOptions(name.trim(), location.trim(), reason.trim(), contact.trim())).copy(lock = lock())
        val seal = if (adv.seal) adv.sealOptions() ?: run { localError = sealMissing; return } else null
        val field = targetField?.let { n -> emptyFields.firstOrNull { it.name == n } }
        runner.run(signingMsg) {
            val out = app.documents.uniqueFile(app.documents.docsDir, outName.ifBlank { f.nameWithoutExtension + "_signed" })
            var target: SigTarget? = field?.let { SigTarget(fieldName = it.name) }
            val vis = if (!visible) null else withContext(Dispatchers.IO) {
                val label0 = name.ifBlank { DigitalSignature.friendlyName(id.subject) }
                val designed: Bitmap? = if (appearance.enabled) runCatching {
                    SigAppearanceRenderer.render(appearance.spec, appearance.spec.lines(label0, reason, location, today, id.subject, appearance.labels()), id.subject)
                }.getOrNull() else null
                val label = visibleText.ifBlank { label0 }
                val saved: Bitmap? = if (useSaved) savedPath?.let { BitmapFactory.decodeFile(it) } else null
                val img: Bitmap = designed ?: saved?.let { withLabel(it, label) } ?: labelBitmap(label)
                if (field?.rect != null) {
                    val r = field.rect
                    DigitalSignature.VisibleSignature(field.page, r[0], r[1], r[2], r[3], image = img)
                } else PdfOps.loadRaw(f).use { d ->
                    val pageIdx = placePage.coerceIn(0, d.numberOfPages - 1)
                    val p = d.getPage(pageIdx)
                    val box = placeBox ?: PlaceBox.corner(0)
                    val r = SigFields.toUserSpace(box.l, box.t, box.w, box.h, p.cropBox, p.rotation)
                    if (field != null) target = SigTarget(fieldName = field.name, page = pageIdx, rect = r)
                    DigitalSignature.VisibleSignature(pageIdx, r[0], r[1], r[2], r[3], image = img)
                }
            }
            signReport = DigitalSignature.sign(ctx, f, out, id, opts, vis, pagingSeal = seal, target = target)
            out
        }
    }

    fun sign() {
        val f = file ?: run { localError = needFile; return }
        if (identity == null) { onNeedCert(); return }
        if (!changesAck && changes?.let { it.base != com.smartdocs.editor.core.sign.RevisionSnapshot.Base.NONE && it.changes.isNotEmpty() } == true) { confirmChanges = true; return }
        val r = report
        if (r != null && r.hasFindings(adv.certify) && (!adv.skipWarning || r.mustWarn(adv.certify))) { showWarning = true; return }
        if (askName) nameDialog = f.nameWithoutExtension + if (adv.certify > 0) "_certified" else "_signed" else doSign("")
    }
    fun afterChecks() { val f = file ?: return; if (askName) nameDialog = f.nameWithoutExtension + if (adv.certify > 0) "_certified" else "_signed" else doSign("") }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilePickRow(file, stringResource(R.string.open_file), onPick)
        if (identity == null) {
            OutlinedCard(onClick = onNeedCert, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sa_cert_none), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text("${stringResource(R.string.sa_cert_selected)}: ${DigitalSignature.friendlyName(identity.subject)}", style = MaterialTheme.typography.bodyMedium)
        }
        // profiles
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { profileMenu = true }) { Text(stringResource(R.string.ns_profiles)); Icon(Icons.Default.ArrowDropDown, null) }
                DropdownMenu(profileMenu, { profileMenu = false }) {
                    if (profiles.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.ns_profiles_none)) }, onClick = { profileMenu = false }, enabled = false)
                    profiles.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { apply(p); profileMenu = false }, trailingIcon = {
                            Row {
                                IconButton(onClick = { storeProfiles(SignProfiles.upsert(profiles, p.copy(name = SignProfiles.copyName(profiles, p.name)))) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.ns_profile_duplicate)) }
                                IconButton(onClick = { storeProfiles(profiles - p) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                            }
                        })
                    }
                }
            }
            TextButton(onClick = { saveProfileName = "" }) { Text(stringResource(R.string.ns_profile_save)) }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sa_sign_name)) }, singleLine = true)
        OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sa_sign_location)) }, singleLine = true)
        Box {
            OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sa_sign_reason)) }, singleLine = true,
                trailingIcon = { IconButton(onClick = { reasonMenu = true }) { Icon(Icons.Default.ArrowDropDown, stringResource(R.string.ns_reason_presets)) } })
            val presets = REASONS.map { stringResource(it) }
            DropdownMenu(reasonMenu, { reasonMenu = false }) {
                presets.forEach { t -> DropdownMenuItem(text = { Text(t) }, onClick = { reason = t; reasonMenu = false }) }
            }
        }
        OutlinedTextField(contact, { contact = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ns_contact)) }, singleLine = true)

        if (emptyFields.isNotEmpty()) {
            Text(stringResource(R.string.ns_sign_into_field), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(targetField == null, { targetField = null }, label = { Text(stringResource(R.string.ns_new_field)) })
                emptyFields.forEach { fl ->
                    FilterChip(targetField == fl.name, { targetField = fl.name; if (fl.rect != null) visible = true },
                        label = { Text(stringResource(R.string.ns_field_chip, fl.name, fl.page + 1)) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(visible, { visible = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sa_visible_signature)) }
        if (visible) {
            val chosen = targetField?.let { n -> emptyFields.firstOrNull { it.name == n } }
            if (chosen?.rect == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (placeBox == null) R.string.ns_place_default else R.string.ns_place_set, placePage + 1), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { placing = true }, enabled = file != null) { Text(stringResource(R.string.ns_place_button)) }
            }
            OutlinedTextField(visibleText, { visibleText = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sa_visible_text)) }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(useSaved, { useSaved = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sa_visible_use_saved)) }
            if (useSaved) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                signatures.forEach { s -> FilterChip(savedPath == s.path, { savedPath = s.path }, label = { Text(File(s.path).nameWithoutExtension.takeLast(8)) }) }
            }
        }
        if (visible) SigAppearanceDesigner(appearance, name.ifBlank { identity?.let { DigitalSignature.friendlyName(it.subject) } ?: "" }, reason, location, today, identity?.subject ?: "")

        // lock after signing (FieldMDP)
        Text(stringResource(R.string.ns_lock_title), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(LockMode.NONE to R.string.ns_lock_none, LockMode.ALL to R.string.ns_lock_mode_all, LockMode.INCLUDE to R.string.ns_lock_mode_include, LockMode.EXCLUDE to R.string.ns_lock_mode_exclude)
                .forEach { (m, s) -> FilterChip(lockMode == m, { lockMode = m }, label = { Text(stringResource(s)) }, enabled = m == LockMode.NONE || adv.certify == 0) }
        }
        if ((lockMode == LockMode.INCLUDE || lockMode == LockMode.EXCLUDE)) {
            if (formFields.isEmpty()) Text(stringResource(R.string.ns_lock_no_fields), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                formFields.forEach { n -> FilterChip(n in lockFields, { lockFields = if (n in lockFields) lockFields - n else lockFields + n }, label = { Text(n) }) }
            }
        }
        if (adv.certify == 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(lockNoChanges, { lockNoChanges = it }); Text(stringResource(R.string.ns_lock_document), style = MaterialTheme.typography.bodyMedium)
        }
        AdvancedSignOptions(adv, signatures, canCertify = report?.canCertify ?: true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(askName, { askName = it; SignPrefs.setAskFileName(ctx, it) }); Text(stringResource(R.string.ns_ask_file_name), style = MaterialTheme.typography.bodyMedium)
        }
        SignChangesCard(changes) { showChanges = true }
        (localError ?: runner.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { sign() }, Modifier.fillMaxWidth(), enabled = !runner.running) { Text(stringResource(if (targetField != null) R.string.ns_sign_field_now else R.string.sa_sign_now)) }
    }
    if (runner.running) ProgressDialog(runner.message)
    val tsDone = stringResource(R.string.ps_sign_result_ts)
    val ltvDone = stringResource(R.string.ps_sign_result_ltv)
    val docTsDone = stringResource(R.string.ps_sign_result_doc_ts)
    val warnLabel = stringResource(R.string.ps_sign_result_warnings)
    if (showResult) runner.result?.let {
        val rep = signReport
        val extra = buildString {
            append(signedInfo)
            if (rep?.timestamped == true) append("\n• ").append(tsDone)
            rep?.ltv?.let { l -> append("\n• ").append(ltvDone.format(l.certs, l.ocsps, l.crls)) }
            if (rep?.docTimestamped == true) append("\n• ").append(docTsDone)
            if (!rep?.warnings.isNullOrEmpty()) append("\n").append(warnLabel).append("\n").append(rep!!.warnings.take(4).joinToString("\n") { w -> "– $w" })
        }
        ResultSheet(it, nav, onDismiss = { showResult = false }, extraInfo = extra)
    }
    changes?.let { c ->
        if (showChanges) SignChangesDialog(c, onDismiss = { showChanges = false })
        if (confirmChanges) SignChangesDialog(c, onDismiss = { confirmChanges = false }, onConfirm = { confirmChanges = false; changesAck = true; sign() })
    }
    val r = report
    if (showWarning && r != null) PreSignDialog(
        report = r, certifyLevel = adv.certify, dontShow = adv.skipWarning, onDontShow = { adv.skipWarning = it },
        onConfirm = { showWarning = false; afterChecks() }, onDismiss = { showWarning = false },
    )
    if (placing) file?.let { f ->
        SigPlacementDialog(f, placePage, placeBox,
            taken = { pg -> sigFields.filter { it.page == pg && it.rect != null }.mapNotNull { fl -> runCatching { displayedBox(f, fl) }.getOrNull() } },
            onDone = { pg, b -> placePage = pg; placeBox = b; placing = false; targetField = null }, onDismiss = { placing = false })
    }
    nameDialog?.let { initial ->
        var text by remember(initial) { mutableStateOf(initial) }
        AlertDialog(onDismissRequest = { nameDialog = null }, title = { Text(stringResource(R.string.ns_ask_file_name)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, suffix = { Text(".pdf") }) },
            confirmButton = { TextButton(onClick = { nameDialog = null; doSign(text.trim().removeSuffix(".pdf")) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text(stringResource(R.string.cancel)) } })
    }
    saveProfileName?.let { initial ->
        var text by remember(initial) { mutableStateOf(initial) }
        AlertDialog(onDismissRequest = { saveProfileName = null }, title = { Text(stringResource(R.string.ns_profile_save)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.ns_profile_name)) }) },
            confirmButton = { TextButton(onClick = { storeProfiles(SignProfiles.upsert(profiles, current(text.trim()))); saveProfileName = null }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { saveProfileName = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** An existing field's rectangle as displayed fractions (for the placement preview). */
private fun displayedBox(f: File, fl: SigFields.Field): PlaceBox? = PdfOps.loadRaw(f).use { d ->
    val p = d.getPage(fl.page)
    val v = SigFields.toDisplayed(fl.rect ?: return null, p.cropBox, p.rotation)
    PlaceBox(v[0], v[1], v[2], v[3])
}

/** The signer's name as an appearance image (no designed appearance and no saved signature). */
private fun labelBitmap(text: String): Bitmap {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt(); textSize = 72f; typeface = android.graphics.Typeface.SERIF }
    val fm = paint.fontMetrics
    val w = (paint.measureText(text) + 48f).toInt().coerceAtLeast(64)
    val h = (fm.descent - fm.ascent + 32f).toInt()
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { android.graphics.Canvas(it).drawText(text, 24f, 16f - fm.ascent, paint) }
}

/** A saved hand signature with the signer's name below it. */
private fun withLabel(sig: Bitmap, text: String): Bitmap {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textSize = (sig.height / 5f).coerceIn(24f, 96f) }
    val fm = paint.fontMetrics
    val th = fm.descent - fm.ascent
    val w = maxOf(sig.width, (paint.measureText(text) + 16f).toInt())
    val out = Bitmap.createBitmap(w, (sig.height + th + 8f).toInt(), Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).apply {
        drawBitmap(sig, (w - sig.width) / 2f, 0f, null)
        drawText(text, (w - paint.measureText(text)) / 2f, sig.height + 4f - fm.ascent, paint)
    }
    return out
}

@Composable
private fun VerifyTab(nav: NavHostController, file: File?, focusField: String?, onPick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var results by remember(file) { mutableStateOf<List<DigitalSignature.VerifyResult>?>(null) }
    var certs by remember(file) { mutableStateOf<List<com.smartdocs.editor.core.sign.CertInspector.SigCert>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revocation by remember { mutableStateOf(SignPrefs.checkRevocation(ctx)) }
    var systemRoots by remember { mutableStateOf(SignPrefs.trustSystemRoots(ctx)) }
    var sigIcons by remember { mutableStateOf(SignPrefs.showSignatureIcons(ctx)) }
    var clearing by remember { mutableStateOf<String?>(null) }
    fun verify() {
        val f = file ?: return
        scope.launch {
            busy = true; error = null
            runCatching { DigitalSignature.verify(f, revocation = revocation) }.onSuccess { results = it }.onFailure { error = it.message ?: it.toString() }
            certs = runCatching { com.smartdocs.editor.core.sign.CertInspector.inspect(ctx, f, system = systemRoots) }.getOrDefault(emptyList())   // gaps6
            busy = false
        }
    }
    LaunchedEffect(file, focusField) { if (file != null && focusField != null) verify() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilePickRow(file, stringResource(R.string.open_file), onPick)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(revocation, { revocation = it; SignPrefs.setCheckRevocation(ctx, it) }); Text(stringResource(R.string.ns_check_revocation), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(systemRoots, { systemRoots = it; SignPrefs.setTrustSystemRoots(ctx, it) }); Text(stringResource(R.string.ns_trust_system), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(sigIcons, { sigIcons = it; SignPrefs.setShowSignatureIcons(ctx, it) }); Text(stringResource(R.string.ns_show_sig_icons), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { verify() }, Modifier.fillMaxWidth(), enabled = file != null && !busy) { Text(stringResource(R.string.sa_verify_now)) }
        OutlinedButton(onClick = { nav.navigate(com.smartdocs.editor.ui.gaps6.G6Routes.TRUSTED_CERTS) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.g6_certs_title)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val r = results
        if (r != null && r.isEmpty()) Text(stringResource(R.string.sa_no_signatures_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r != null) LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // the field tapped in the reader first
            val order = r.indices.sortedBy { if (focusField != null && r[it].fieldName == focusField) 0 else 1 }
            itemsIndexed(order) { _, i ->
                val v = r[i]
                VerifyCard(v, certs.getOrNull(i), onTrust = { c -> if (com.smartdocs.editor.core.sign.TrustedCerts.add(ctx, c)) verify() },
                    onSignedVersion = if (file != null && v.signedLength > 0 && !v.coversWholeDocument) ({
                        scope.launch {
                            val out = withContext(Dispatchers.IO) { runCatching { signedVersion(ctx.cacheDir, file, v) }.getOrNull() }
                            if (out != null) nav.navigate(Routes.reader(Uri.fromFile(out).toString()))
                        }
                    }) else null,
                    onClear = if (file != null && v.fieldName != null && !v.isDocTimestamp) ({ clearing = v.fieldName }) else null)
            }
        }
    }
    clearing?.let { fieldName ->
        AlertDialog(onDismissRequest = { clearing = null }, title = { Text(stringResource(R.string.ns_field_clear)) },
            text = { Text(stringResource(R.string.ns_field_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    clearing = null
                    val f = file ?: return@TextButton
                    scope.launch {
                        busy = true
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val tmp = File(ctx.cacheDir, "sigclear_${System.nanoTime()}.pdf")
                                try { SigFields.clear(f, tmp, fieldName); tmp.copyTo(f, overwrite = true) } finally { tmp.delete() }
                            }
                        }.onFailure { error = it.message ?: it.toString() }
                        busy = false
                        verify()
                    }
                }) { Text(stringResource(R.string.ns_field_clear)) }
            },
            dismissButton = { TextButton(onClick = { clearing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun VerifyCard(
    r: DigitalSignature.VerifyResult, cert: com.smartdocs.editor.core.sign.CertInspector.SigCert? = null, onTrust: (java.security.cert.X509Certificate) -> Unit = {},
    onSignedVersion: (() -> Unit)? = null, onClear: (() -> Unit)? = null,
) {
    val ok = r.signatureValid
    val small = MaterialTheme.typography.bodySmall
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Default.Verified else Icons.Default.Cancel, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(if (ok) stringResource(R.string.sig_valid) else stringResource(R.string.sig_invalid), style = MaterialTheme.typography.titleMedium)
            }
            if (r.name.isNotBlank()) Text(DigitalSignature.friendlyName(r.name), style = MaterialTheme.typography.bodyLarge)
            r.fieldName?.let { Text("${stringResource(R.string.ns_prop_name)}: $it", style = small) }
            if (r.revisionCount > 0) Text(stringResource(R.string.ns_revision_of, r.revision, r.revisionCount), style = small)
            r.signDate?.let { Text("${stringResource(R.string.sa_sign_date)}: ${DocumentRepository.formatDate(it.time)}", style = small) }
            if (r.reason.isNotBlank()) Text("${stringResource(R.string.sa_sign_reason)}: ${r.reason}", style = small)
            if (r.location.isNotBlank()) Text("${stringResource(R.string.sa_sign_location)}: ${r.location}", style = small)
            if (r.contactInfo.isNotBlank()) Text("${stringResource(R.string.ns_contact)}: ${r.contactInfo}", style = small)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!r.coversWholeDocument) Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(stringResource(if (r.coversWholeDocument) R.string.sa_covers_whole else R.string.sa_covers_partial), style = small)
            }
            if (r.certSubject.isNotBlank()) Text("${stringResource(R.string.sa_cert_subject)}: ${DigitalSignature.friendlyName(r.certSubject)}", style = small)
            if (r.certIssuer.isNotBlank()) Text("${stringResource(R.string.sa_cert_issuer)}: ${DigitalSignature.friendlyName(r.certIssuer)}", style = small)
            if (r.validFrom != null && r.validTo != null) Text("${stringResource(R.string.sa_cert_validity)}: ${DocumentRepository.formatDate(r.validFrom.time)} – ${DocumentRepository.formatDate(r.validTo.time)}", style = small)
            if (r.isDocTimestamp) Text(stringResource(R.string.ps_verify_doc_ts), style = small, color = MaterialTheme.colorScheme.primary)
            r.timestampDate?.let { t ->
                Text(stringResource(if (r.timestampValid) R.string.ps_verify_ts_ok else R.string.ps_verify_ts_bad, DocumentRepository.formatDate(t.time)),
                    style = small, color = if (r.timestampValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            r.certifyLevel?.let { Text(stringResource(R.string.ps_verify_certified, it), style = small) }
            r.lock?.let { Text(lockText(it), style = small) }
            if (r.counterSigners.isNotEmpty()) Text(stringResource(R.string.ns_counter_signed, r.counterSigners.joinToString(", ")), style = small, color = MaterialTheme.colorScheme.primary)
            if (r.signedLaterBy.isNotEmpty()) Text(stringResource(R.string.ns_signed_later, r.signedLaterBy.joinToString(", ")), style = small)
            if (r.ltvData) Text(stringResource(R.string.ps_verify_ltv), style = small, color = MaterialTheme.colorScheme.primary)
            r.revocation?.let { RevocationLine(it, r.timestampDate ?: r.signDate) }
            if (r.subFilter.isNotBlank()) Text(r.subFilter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            r.error?.let { Text(it, style = small, color = MaterialTheme.colorScheme.error) }
            if (cert != null) CertTrustSection(cert, onTrust)   // gaps6: trust + certificate details
            if (onSignedVersion != null || onClear != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onSignedVersion?.let { TextButton(onClick = it) { Text(stringResource(R.string.ns_view_signed_version)) } }
                onClear?.let { TextButton(onClick = it) { Text(stringResource(R.string.ns_field_clear)) } }
            }
        }
    }
}

@Composable
private fun RevocationLine(rev: Revocation.Result, signedAt: java.util.Date?) {
    val small = MaterialTheme.typography.bodySmall
    val (text, color) = when (rev.status) {
        Revocation.Status.GOOD -> stringResource(R.string.ns_rev_good) to MaterialTheme.colorScheme.primary
        Revocation.Status.REVOKED -> {
            val at = rev.certs.firstOrNull { it.status == Revocation.Status.REVOKED }?.revokedAt?.let { DocumentRepository.formatDate(it.time) } ?: "?"
            stringResource(if (rev.revokedAfter(signedAt)) R.string.ns_rev_revoked_after else R.string.ns_rev_revoked, at) to MaterialTheme.colorScheme.error
        }
        Revocation.Status.UNKNOWN -> stringResource(R.string.ns_rev_unknown) to MaterialTheme.colorScheme.tertiary
    }
    Text(text, style = small, color = color)
    rev.certs.forEach { c ->
        val src = when (c.source) {
            Revocation.Source.OCSP -> "OCSP"; Revocation.Source.CRL -> "CRL"
            Revocation.Source.EMBEDDED_OCSP -> "OCSP (DSS)"; Revocation.Source.EMBEDDED_CRL -> "CRL (DSS)"; Revocation.Source.NONE -> "—"
        }
        Text("• ${c.subject}: ${c.status.name.lowercase()} · $src" + (c.problem?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
