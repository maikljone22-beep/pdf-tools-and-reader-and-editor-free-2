package com.smartdocs.editor.ui.signature

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.sign.CertExport
import com.smartdocs.editor.core.sign.OwnPublicCert
import com.smartdocs.editor.core.sign.TrustedCerts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Writes [bytes] as [name] in the app's share folder (cache, served by the FileProvider). */
private fun shareFile(app: SmartDocsApp, name: String, bytes: ByteArray): File {
    val dir = File(app.documents.tempDir, "certs").apply { mkdirs() }
    return File(dir, name).apply { writeBytes(bytes) }
}

/**
 * Export dialog of Trusted Contacts: pick one or more certificates and a format (.cer for exactly one, .pem,
 * .p7b, .p7c), then save through the system file picker or share.
 */
@Composable
fun CertExportDialog(entries: List<TrustedCerts.Entry>, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val chosen = remember { mutableStateListOf<String>().apply { if (entries.size == 1) add(entries.single().sha256) } }
    var kind by remember { mutableStateOf(if (entries.size == 1) CertExport.Kind.CER else CertExport.Kind.P7B) }
    val certs = entries.filter { it.sha256 in chosen }.map { it.cert }
    val usable = certs.isNotEmpty() && (kind.multi || certs.size == 1)
    val savedMsg = stringResource(R.string.fx_saved_copy)
    val failedMsg = stringResource(R.string.np_export_failed)
    val contract = remember(kind) { ActivityResultContracts.CreateDocument(kind.mime) }
    // the bytes are built when the user confirms, so a later selection change cannot reach the saved file
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val saver = rememberLauncherForActivityResult(contract) { uri ->
        val bytes = pending; pending = null
        if (uri != null && bytes != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.isSuccess }
            Toast.makeText(ctx, if (ok) savedMsg else failedMsg, Toast.LENGTH_SHORT).show()
            if (ok) onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.np_export_certs)) },
        text = {
            Column {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    entries.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(e.sha256 in chosen, { on -> if (on) chosen += e.sha256 else chosen -= e.sha256 })
                            Text(DigitalSignature.friendlyName(e.cert.subjectX500Principal.name), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                    }
                }
                Text(stringResource(R.string.np_export_format), Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CertExport.Kind.values().forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(".${k.ext}") }, enabled = k.multi || certs.size <= 1)
                    }
                }
                Text(stringResource(when (kind) {
                    CertExport.Kind.CER -> R.string.np_export_cer_hint
                    CertExport.Kind.PEM -> R.string.np_export_pem_hint
                    CertExport.Kind.P7B, CertExport.Kind.P7C -> R.string.np_export_p7_hint
                }), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!kind.multi && certs.size > 1) Text(stringResource(R.string.np_export_cer_one), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = usable, onClick = {
                val bytes = runCatching { CertExport.encode(certs, kind) }.getOrNull()
                if (bytes == null) { Toast.makeText(ctx, failedMsg, Toast.LENGTH_SHORT).show(); return@TextButton }
                pending = bytes
                runCatching { saver.launch(CertExport.fileName(certs, kind)) }.onFailure { pending = null }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton(enabled = usable, onClick = {
                    val k = kind; val list = certs
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { runCatching { shareFile(app, CertExport.fileName(list, k), CertExport.encode(list, k)) }.getOrNull() }
                        if (f == null) { Toast.makeText(ctx, failedMsg, Toast.LENGTH_SHORT).show(); return@launch }
                        runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f), k.mime), null)) }
                    }
                }) { Text(stringResource(R.string.share)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/**
 * "Request certificate by e-mail": an e-mail asking the contact to send their certificate (.cer). The user's own
 * public certificate is attached when it is known without the ID password ([OwnPublicCert]); otherwise the body
 * says that it is not attached.
 */
fun requestCertificateByMail(ctx: Context) {
    val app = ctx.applicationContext as SmartDocsApp
    val own = runCatching { OwnPublicCert.get(ctx) }.getOrNull()
    val attachment = own?.let { c -> runCatching { shareFile(app, CertExport.fileName(listOf(c), CertExport.Kind.CER), c.encoded) }.getOrNull() }
    val body = buildString {
        append(ctx.getString(R.string.np_request_body))
        append("\n\n")
        append(ctx.getString(if (attachment != null) R.string.np_request_body_attached else R.string.np_request_body_not_attached))
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.np_request_subject))
        putExtra(Intent.EXTRA_TEXT, body)
        if (attachment != null) {
            val uri = app.documents.shareUri(attachment)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(attachment.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching { ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.np_request_cert))) }
        .onFailure { Toast.makeText(ctx, ctx.getString(R.string.np_no_mail_app), Toast.LENGTH_LONG).show() }
}
