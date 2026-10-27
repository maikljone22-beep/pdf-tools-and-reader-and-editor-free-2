package com.smartdocs.editor.ui.signature

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.sign.SigFields
import com.smartdocs.editor.core.sign.SigRevisions
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.forms5.FormFill
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Hand-over from the reader's signature-field sheet to the digital signature screen (field to sign / tab). */
object SigFieldRequest {
    data class Request(val path: String, val field: String?, val verify: Boolean)

    @Volatile var pending: Request? = null

    /** Takes the request if it is for [file]. */
    fun take(file: File?): Request? = pending?.takeIf { file != null && it.path == file.path }?.also { pending = null }
}

/**
 * Nitro signature-field actions in the reader: tapping a signature field offers Sign (empty field), Validate,
 * View signed version, Clear signature and Properties. Also keeps the validity badges of signed fields
 * ([com.smartdocs.editor.ui.forms5.FormFillState.sigStatus]) up to date. Hook: ReaderScreen, next to InPageFormBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.SigFieldSheet(reader: ReaderState, nav: NavHostController) {
    val fs = remember(reader) { FormFill.of(reader) }
    val scope = rememberCoroutineScope()
    val file = reader.file
    // Foxit "verify signatures when the document is opened": badges + the signed / certified bar
    var results by remember(file) { mutableStateOf<List<DigitalSignature.VerifyResult>>(emptyList()) }
    var bannerClosed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file, reader.renderVersion) {
        val f = file ?: return@LaunchedEffect
        results = withContext(Dispatchers.IO) {
            runCatching { if (SigFields.list(f, reader.password).none { it.signed }) emptyList() else DigitalSignature.verify(f) }.getOrDefault(emptyList())
        }
        fs.sigStatus = results.mapNotNull { v -> v.fieldName?.let { it to (v.signatureValid && v.error == null) } }.toMap()
    }
    if (results.isNotEmpty() && !bannerClosed && file != null) SignedBar(results, Modifier.align(Alignment.TopCenter),
        onOpen = { SigFieldRequest.pending = SigFieldRequest.Request(file.path, null, verify = true); nav.navigate(Routes.tool("digital_sign", reader.fileUri)) },
        onClose = { bannerClosed = true })
    val w = fs.sigTapped ?: return
    if (file == null) { fs.sigTapped = null; return }
    var field by remember(w) { mutableStateOf<SigFields.Field?>(null) }
    var result by remember(w) { mutableStateOf<DigitalSignature.VerifyResult?>(null) }
    var busy by remember(w) { mutableStateOf(true) }
    var error by remember(w) { mutableStateOf<String?>(null) }
    var confirmClear by remember(w) { mutableStateOf(false) }
    var showProps by remember(w) { mutableStateOf(false) }
    var moving by remember(w) { mutableStateOf(false) }
    var confirmDeleteField by remember(w) { mutableStateOf(false) }
    fun close() { fs.sigTapped = null }
    /** Rewrites the document in place through a temporary copy (incremental on signed files), then reloads. */
    fun edit(op: (File, File) -> Unit) {
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(reader.context.cacheDir, "sigfield_${System.nanoTime()}.pdf")
                    try { op(file, tmp); tmp.copyTo(file, overwrite = true) } finally { tmp.delete() }
                }
            }
            busy = false
            ok.onSuccess { close(); reader.reloadIfChanged() }.onFailure { error = it.message ?: it.toString() }
        }
    }
    LaunchedEffect(w) {
        withContext(Dispatchers.IO) {
            runCatching {
                field = SigFields.list(file, reader.password).firstOrNull { it.name == w.name }
                if (field?.signed == true) result = DigitalSignature.verify(file).firstOrNull { it.fieldName == w.name }
            }.onFailure { error = it.message ?: it.toString() }
        }
        busy = false
    }
    val unsavedMsg = stringResource(R.string.ns_field_unsaved)

    ModalBottomSheet(onDismissRequest = { close() }) {
        Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ns_field_title, w.label.ifBlank { w.name }), style = MaterialTheme.typography.titleMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val f = field
            val r = result
            when {
                f == null && !busy -> Text(stringResource(R.string.ns_field_missing), color = MaterialTheme.colorScheme.error)
                f != null && !f.signed -> Text(stringResource(R.string.ns_field_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                r != null -> Text(
                    stringResource(if (r.signatureValid) R.string.ns_field_signed_valid else R.string.ns_field_signed_invalid, DigitalSignature.friendlyName(r.name)),
                    color = if (r.signatureValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (f != null && !f.signed) Button(onClick = {
                if (reader.dirty) { error = unsavedMsg; return@Button }
                SigFieldRequest.pending = SigFieldRequest.Request(file.path, f.name, verify = false)
                close(); nav.navigate(Routes.tool("digital_sign", reader.fileUri))
            }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Draw, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ns_field_sign)) }
            if (f != null && !f.signed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (reader.dirty) error = unsavedMsg else moving = true }) { Text(stringResource(R.string.ns2_field_move)) }
                OutlinedButton(onClick = { if (reader.dirty) error = unsavedMsg else confirmDeleteField = true }) { Text(stringResource(R.string.ns2_field_delete)) }
            }
            if (f?.signed == true) {
                OutlinedButton(onClick = {
                    SigFieldRequest.pending = SigFieldRequest.Request(file.path, f.name, verify = true)
                    close(); nav.navigate(Routes.tool("digital_sign", reader.fileUri))
                }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Verified, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ns_field_validate)) }
                if (r != null && r.signedLength > 0) OutlinedButton(onClick = {
                    scope.launch {
                        val out = withContext(Dispatchers.IO) { signedVersion(reader.context.cacheDir, file, r) }
                        close(); nav.navigate(Routes.reader(android.net.Uri.fromFile(out).toString()))
                    }
                }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.History, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ns_view_signed_version) + " · " + stringResource(R.string.ns_revision_of, r.revision, r.revisionCount))
                }
                OutlinedButton(onClick = { if (reader.dirty) error = unsavedMsg else confirmClear = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ns_field_clear))
                }
            }
            if (f != null) TextButton(onClick = { showProps = !showProps }) { Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ns_field_properties)) }
            if (showProps && f != null) FieldProperties(f, r)
        }
    }
    val fl = field
    if (moving && fl != null && fl.page >= 0) {
        val initial = remember(fl) {
            runCatching {
                com.smartdocs.editor.core.pdf.PdfOps.loadRaw(file, reader.password).use { d ->
                    val p = d.getPage(fl.page)
                    fl.rect?.let { r -> SigFields.toDisplayed(r, p.cropBox, p.rotation).let { v -> PlaceBox(v[0], v[1], v[2], v[3]) } }
                }
            }.getOrNull()
        }
        SigPlacementDialog(file, fl.page, initial, password = reader.password, onDismiss = { moving = false }, onDone = { _, b ->
            moving = false
            edit { src, out ->
                val r = com.smartdocs.editor.core.pdf.PdfOps.loadRaw(src, reader.password).use { d -> val p = d.getPage(fl.page); SigFields.toUserSpace(b.l, b.t, b.w, b.h, p.cropBox, p.rotation) }
                SigFields.move(src, out, fl.name, r, reader.password)
            }
        })
    }
    if (confirmDeleteField && fl != null) AlertDialog(
        onDismissRequest = { confirmDeleteField = false },
        title = { Text(stringResource(R.string.ns2_field_delete)) },
        text = { Text(stringResource(R.string.ns2_field_delete_confirm, fl.name)) },
        confirmButton = { TextButton(onClick = { confirmDeleteField = false; edit { src, out -> SigFields.delete(src, out, fl.name, reader.password) } }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDeleteField = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.ns_field_clear)) },
        text = { Text(stringResource(R.string.ns_field_clear_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val tmp = File(reader.context.cacheDir, "sigclear_${System.nanoTime()}.pdf")
                            try { SigFields.clear(file, tmp, w.name, reader.password); tmp.copyTo(file, overwrite = true) } finally { tmp.delete() }
                        }
                    }
                    busy = false
                    ok.onSuccess { close(); reader.reloadIfChanged() }.onFailure { error = it.message ?: it.toString() }
                }
            }) { Text(stringResource(R.string.ns_field_clear)) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The revision a signature covers, as its own file ("View signed version"). */
internal fun signedVersion(cacheDir: File, file: File, r: DigitalSignature.VerifyResult): File {
    val dir = File(cacheDir, "signed_versions").apply { mkdirs() }
    val out = File(dir, "${file.nameWithoutExtension}_rev${r.revision}.pdf")
    SigRevisions.extract(file, r.signedLength, out)
    return out
}

@Composable
private fun FieldProperties(f: SigFields.Field, r: DigitalSignature.VerifyResult?) {
    val small = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${stringResource(R.string.ns_prop_name)}: ${f.name}", style = small)
        if (f.page >= 0) Text("${stringResource(R.string.ns_prop_page)}: ${f.page + 1}", style = small)
        f.rect?.let { Text("${stringResource(R.string.ns_prop_rect)}: ${it.joinToString(", ") { v -> "%.0f".format(v) }}", style = small) }
        f.lock?.let { Text(lockText(it), style = small) }
        r?.let {
            it.signDate?.let { d -> Text("${stringResource(R.string.sa_sign_date)}: ${DocumentRepository.formatDate(d.time)}", style = small) }
            if (it.reason.isNotBlank()) Text("${stringResource(R.string.sa_sign_reason)}: ${it.reason}", style = small)
            if (it.location.isNotBlank()) Text("${stringResource(R.string.sa_sign_location)}: ${it.location}", style = small)
            if (it.contactInfo.isNotBlank()) Text("${stringResource(R.string.ns_contact)}: ${it.contactInfo}", style = small)
        }
    }
}

/** "Locks: all fields" / "Locks: a, b" / "Locks all except: a" (+ "no changes allowed"). */
@Composable
internal fun lockText(l: SigFields.Lock): String {
    val base = when (l.action) {
        SigFields.LockAction.ALL -> stringResource(R.string.ns_lock_all)
        SigFields.LockAction.INCLUDE -> stringResource(R.string.ns_lock_include, l.fields.joinToString(", "))
        SigFields.LockAction.EXCLUDE -> stringResource(R.string.ns_lock_exclude, l.fields.joinToString(", "))
    }
    val p = when (l.docPermission) {
        1 -> stringResource(R.string.ns_lock_p1); 2 -> stringResource(R.string.ns_lock_p2); 3 -> stringResource(R.string.ns_lock_p3); else -> null
    }
    return if (p == null) base else "$base · $p"
}

/** Signed / certified document bar shown when a signed PDF opens (Foxit notification bar, Acrobat signature bar). */
@Composable
private fun SignedBar(results: List<DigitalSignature.VerifyResult>, modifier: Modifier, onOpen: () -> Unit, onClose: () -> Unit) {
    val allValid = results.all { it.signatureValid && it.error == null }
    val certified = results.firstOrNull { it.certifyLevel != null }
    val text = when {
        !allValid -> stringResource(R.string.ns2_bar_problem)
        certified != null -> stringResource(R.string.ns2_bar_certified, DigitalSignature.friendlyName(certified.name)) + " · " + stringResource(
            when (certified.certifyLevel) { 1 -> R.string.ns_lock_p1; 2 -> R.string.ns_lock_p2; else -> R.string.ns_lock_p3 })
        else -> stringResource(R.string.ns2_bar_signed, results.count { !it.isDocTimestamp })
    }
    Surface(modifier.padding(8.dp).fillMaxWidth(), shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp, shadowElevation = 4.dp,
        color = if (allValid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (allValid) Icons.Default.Verified else Icons.Default.Info, null)
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpen) { Text(stringResource(R.string.ns2_bar_details)) }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
    }
}
