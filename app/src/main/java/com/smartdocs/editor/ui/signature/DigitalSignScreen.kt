package com.smartdocs.editor.ui.signature

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
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
import com.smartdocs.editor.ui.common.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Certificate-based signing + verification. Tabs: sign / verify / certificate. */
@Composable
fun DigitalSignScreen(nav: NavHostController, uri: String?) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var file by remember { mutableStateOf(uri?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull() }) }
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
                0 -> SignTab(nav, file, identity, onPick = pick, onNeedCert = { tab = 2 })
                1 -> VerifyTab(file, onPick = pick)
                else -> CertificateTab(identity) { identity = it }
            }
        }
    }
}

private enum class Corner { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT }

@Composable
private fun SignTab(nav: NavHostController, file: File?, identity: DigitalSignature.Identity?, onPick: () -> Unit, onNeedCert: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val runner = rememberToolRunner()
    var name by rememberSaveable(identity) { mutableStateOf(identity?.let { DigitalSignature.friendlyName(it.subject) } ?: settings.authorName) }
    var location by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("1") }
    var visibleText by rememberSaveable { mutableStateOf("") }
    var corner by rememberSaveable { mutableStateOf(Corner.BOTTOM_RIGHT) }
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

    fun doSign() {
        val f = file ?: run { localError = needFile; return }
        val id = identity ?: run { onNeedCert(); return }
        localError = null
        adv.persist()
        val opts = adv.options(DigitalSignature.SignOptions(name.trim(), location.trim(), reason.trim()))
        val seal = if (adv.seal) adv.sealOptions() ?: run { localError = sealMissing; return } else null
        runner.run(signingMsg) {
            val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_signed")
            val vis = if (!visible) null else withContext(Dispatchers.IO) {
                val pageIdx = (page.toIntOrNull() ?: 1) - 1
                val (pw, ph, count) = PDDocument.load(f).use { d -> val b = d.getPage(pageIdx.coerceIn(0, d.numberOfPages - 1)).mediaBox; Triple(b.width, b.height, d.numberOfPages) }
                val img: Bitmap? = if (useSaved) savedPath?.let { BitmapFactory.decodeFile(it) } else null
                val w = 160f; val h = if (img != null) w * img.height / img.width else 20f
                val (x, y) = when (corner) {
                    Corner.BOTTOM_RIGHT -> pw - w - 36f to 48f
                    Corner.BOTTOM_LEFT -> 36f to 48f
                    Corner.TOP_RIGHT -> pw - w - 36f to ph - h - 48f
                    Corner.TOP_LEFT -> 36f to ph - h - 48f
                }
                val label = visibleText.ifBlank { name.ifBlank { DigitalSignature.friendlyName(id.subject) } }
                DigitalSignature.VisibleSignature(pageIdx.coerceIn(0, count - 1), x, y, w, h, image = img, text = label)
            }
            signReport = DigitalSignature.sign(ctx, f, out, id, opts, vis, pagingSeal = seal)
            out
        }
    }

    fun sign() {
        if (file == null) { localError = needFile; return }
        if (identity == null) { onNeedCert(); return }
        val r = report
        if (r != null && r.hasFindings(adv.certify) && (!adv.skipWarning || r.mustWarn(adv.certify))) showWarning = true else doSign()
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilePickRow(file, stringResource(R.string.open_file), onPick)
        if (identity == null) {
            OutlinedCard(onClick = onNeedCert, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sa_cert_none), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text("${stringResource(R.string.sa_cert_selected)}: ${DigitalSignature.friendlyName(identity.subject)}", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sa_sign_name)) }, singleLine = true)
        OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sa_sign_location)) }, singleLine = true)
        OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sa_sign_reason)) }, singleLine = true)

        Row(verticalAlignment = Alignment.CenterVertically) { Switch(visible, { visible = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sa_visible_signature)) }
        if (visible) {
            OutlinedTextField(page, { page = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sa_visible_page)) }, singleLine = true)
            OutlinedTextField(visibleText, { visibleText = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sa_visible_text)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Corner.entries.forEach { c -> FilterChip(corner == c, { corner = c }, label = { Text(c.name.lowercase().replace('_', ' ')) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(useSaved, { useSaved = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sa_visible_use_saved)) }
            if (useSaved) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                signatures.forEach { s -> FilterChip(savedPath == s.path, { savedPath = s.path }, label = { Text(File(s.path).nameWithoutExtension.takeLast(8)) }) }
            }
        }
        AdvancedSignOptions(adv, signatures, canCertify = report?.canCertify ?: true)
        (localError ?: runner.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { sign() }, Modifier.fillMaxWidth(), enabled = !runner.running) { Text(stringResource(R.string.sa_sign_now)) }
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
    val r = report
    if (showWarning && r != null) PreSignDialog(
        report = r, certifyLevel = adv.certify, dontShow = adv.skipWarning, onDontShow = { adv.skipWarning = it },
        onConfirm = { showWarning = false; doSign() }, onDismiss = { showWarning = false },
    )
}

@Composable
private fun VerifyTab(file: File?, onPick: () -> Unit) {
    val scope = rememberCoroutineScope()
    var results by remember(file) { mutableStateOf<List<DigitalSignature.VerifyResult>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun verify() {
        val f = file ?: return
        scope.launch {
            busy = true; error = null
            runCatching { DigitalSignature.verify(f) }.onSuccess { results = it }.onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilePickRow(file, stringResource(R.string.open_file), onPick)
        Button(onClick = { verify() }, Modifier.fillMaxWidth(), enabled = file != null && !busy) { Text(stringResource(R.string.sa_verify_now)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val r = results
        if (r != null && r.isEmpty()) Text(stringResource(R.string.sa_no_signatures_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r != null) LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(r) { VerifyCard(it) } }
    }
}

@Composable
private fun VerifyCard(r: DigitalSignature.VerifyResult) {
    val ok = r.signatureValid
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Default.Verified else Icons.Default.Cancel, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(if (ok) stringResource(R.string.sig_valid) else stringResource(R.string.sig_invalid), style = MaterialTheme.typography.titleMedium)
            }
            if (r.name.isNotBlank()) Text(r.name, style = MaterialTheme.typography.bodyLarge)
            r.signDate?.let { Text("${stringResource(R.string.sa_sign_date)}: ${DocumentRepository.formatDate(it.time)}", style = MaterialTheme.typography.bodySmall) }
            if (r.reason.isNotBlank()) Text("${stringResource(R.string.sa_sign_reason)}: ${r.reason}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!r.coversWholeDocument) Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(stringResource(if (r.coversWholeDocument) R.string.sa_covers_whole else R.string.sa_covers_partial), style = MaterialTheme.typography.bodySmall)
            }
            if (r.certSubject.isNotBlank()) Text("${stringResource(R.string.sa_cert_subject)}: ${DigitalSignature.friendlyName(r.certSubject)}", style = MaterialTheme.typography.bodySmall)
            if (r.certIssuer.isNotBlank()) Text("${stringResource(R.string.sa_cert_issuer)}: ${DigitalSignature.friendlyName(r.certIssuer)}", style = MaterialTheme.typography.bodySmall)
            if (r.validFrom != null && r.validTo != null) Text("${stringResource(R.string.sa_cert_validity)}: ${DocumentRepository.formatDate(r.validFrom.time)} – ${DocumentRepository.formatDate(r.validTo.time)}", style = MaterialTheme.typography.bodySmall)
            if (r.isDocTimestamp) Text(stringResource(R.string.ps_verify_doc_ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            r.timestampDate?.let { t ->
                Text(stringResource(if (r.timestampValid) R.string.ps_verify_ts_ok else R.string.ps_verify_ts_bad, DocumentRepository.formatDate(t.time)),
                    style = MaterialTheme.typography.bodySmall, color = if (r.timestampValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            r.certifyLevel?.let { Text(stringResource(R.string.ps_verify_certified, it), style = MaterialTheme.typography.bodySmall) }
            if (r.ltvData) Text(stringResource(R.string.ps_verify_ltv), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (r.subFilter.isNotBlank()) Text(r.subFilter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
