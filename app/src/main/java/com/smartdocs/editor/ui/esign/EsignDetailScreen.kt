package com.smartdocs.editor.ui.esign

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.esign.AuditFormat
import com.smartdocs.editor.core.esign.EsignClient
import com.smartdocs.editor.core.esign.EsignComposer
import com.smartdocs.editor.core.esign.EsignRequest
import com.smartdocs.editor.core.esign.EsignStore
import com.smartdocs.editor.core.esign.RequestStatus
import com.smartdocs.editor.core.esign.SignerFlow
import com.smartdocs.editor.core.esign.SignerStatus
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.ResultSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Route "esign/req/{id}": status of a request I sent, reminders, links, audit trail, final copy. */
@Composable
fun EsignDetailScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val ref = remember(id) { EsignStore.load(ctx).let { EsignStore.sent(ctx, id) } }
    var req by remember { mutableStateOf<EsignRequest?>(null) }
    var links by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val remindedMail = stringResource(R.string.cn_reminded_mail)

    LaunchedEffect(reload) {
        val r = ref ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { EsignClient.get(r.id, r.secret) to EsignClient.links(r.id, r.secret) } }
            .onSuccess { (q, l) -> req = q; links = l; error = null
                EsignStore.update(ctx, r.id, true) { it.copy(status = q.status.name.lowercase(), counts = "${q.signers.count { s -> s.status == SignerStatus.SIGNED }}/${q.signers.size}") } }
            .onFailure { error = if (EsignClient.isGone(it)) ctx.getString(R.string.cn_gone) else it.message }
    }

    fun labels() = EsignComposer.CertText(
        title = ctx.getString(R.string.cn_cert_title), document = ctx.getString(R.string.cn_cert_document), requestId = ctx.getString(R.string.cn_cert_id),
        sender = ctx.getString(R.string.cn_cert_sender), created = ctx.getString(R.string.cn_cert_created), originalHash = ctx.getString(R.string.cn_cert_hash),
        signers = ctx.getString(R.string.cn_cert_signers), status = ctx.getString(R.string.cn_cert_status), ip = "IP", device = ctx.getString(R.string.cn_cert_device),
        agent = ctx.getString(R.string.cn_cert_agent), time = ctx.getString(R.string.cn_cert_time), audit = ctx.getString(R.string.cn_cert_audit),
        verify = ctx.getString(R.string.cn_cert_verify), verifyNote = ctx.getString(R.string.cn_cert_verify_note), signed = ctx.getString(R.string.cn_st_signed),
        declined = ctx.getString(R.string.cn_st_declined), pending = ctx.getString(R.string.cn_st_pending),
        kinds = mapOf("created" to ctx.getString(R.string.cn_k_created), "sent" to ctx.getString(R.string.cn_k_sent), "viewed" to ctx.getString(R.string.cn_k_viewed),
            "signed" to ctx.getString(R.string.cn_k_signed), "declined" to ctx.getString(R.string.cn_k_declined), "reminded" to ctx.getString(R.string.cn_k_reminded),
            "cancelled" to ctx.getString(R.string.cn_k_cancelled), "finalized" to ctx.getString(R.string.cn_k_finalized)),
    )

    fun composeFinal() = scope.launch {
        val r = ref ?: return@launch; val q = req ?: return@launch
        busy = ctx.getString(R.string.cn_composing)
        runCatching {
            withContext(Dispatchers.IO) {
                val src = File(r.localPath).takeIf { it.exists() } ?: throw IllegalStateException(ctx.getString(R.string.cn_original_missing))
                val values = EsignClient.values(r.id, r.secret)
                val out = app.documents.uniqueFile(app.documents.docsDir, "${q.title} (" + ctx.getString(R.string.cn_signed_suffix) + ")")
                EsignComposer.compose(src, out, q, values, labels(), EsignClient.verifyPage())
                EsignClient.uploadFinal(r.id, r.secret, out)
                EsignStore.update(ctx, r.id, true) { it.copy(finalPath = out.absolutePath, status = "finalized") }
                out
            }
        }.onSuccess { result = it; reload++ }.onFailure { error = it.message ?: it.toString() }
        busy = null
    }

    Scaffold(topBar = {
        AppTopBar(req?.title ?: ref?.title ?: "", onBack = { nav.popBackStack() }) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.cn_refresh)) }
            IconButton(onClick = { confirm = "delete" }) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.cn_delete)) }
        }
    }) { pad ->
        val q = req
        if (ref == null) { EmptyState(Icons.Default.SearchOff, stringResource(R.string.cn_gone), Modifier.padding(pad)); return@Scaffold }
        if (q == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: CircularProgressIndicator() }
            return@Scaffold
        }
        val active = SignerFlow.active(q.order, q.signers)
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RequestStatusChip(q.status); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (q.order.name == "PARALLEL") R.string.cn_order_par else R.string.cn_order_seq), style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (q.status == RequestStatus.COMPLETED || q.status == RequestStatus.FINALIZED) item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.cn_completed_note), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { composeFinal() }, shape = PillShape) { Icon(Icons.Default.TaskAlt, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cn_make_final)) }
                        val fin = ref.finalPath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }
                        if (fin != null) Row {
                            OutlinedButton(onClick = { nav.navigate(Routes.reader(Uri.fromFile(fin).toString())) }, shape = PillShape) { Text(stringResource(R.string.cn_open)) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { nav.navigate(Routes.tool("digital_sign", Uri.fromFile(fin).toString())) }, shape = PillShape) { Text(stringResource(R.string.cn_pades)) }
                        }
                        if (q.finalSha.isNotEmpty()) Text(stringResource(R.string.cn_final_hash, AuditFormat.short(q.finalSha)), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            item { Text(stringResource(R.string.cn_signers_title), style = MaterialTheme.typography.titleSmall) }
            items(q.signers.size) { i ->
                val s = q.signers[i]
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(signerColor(i), CircleShape)); Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall)
                                if (s.email.isNotBlank()) Text(s.email, style = MaterialTheme.typography.bodySmall)
                            }
                            SignerStatusChip(s.status)
                        }
                        if (s.signedAt > 0) Text(DocumentRepository.formatDate(s.signedAt), style = MaterialTheme.typography.bodySmall)
                        if (s.declineReason.isNotBlank()) Text("«${s.declineReason}»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (i in active && q.status == RequestStatus.OUT) Row(verticalAlignment = Alignment.CenterVertically) {
                            links.getOrNull(i)?.let { l -> LinkActions(l, s.email, q.title, ctx.getString(R.string.cn_invite_text, q.sender, q.title, l)) }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { scope.launch {
                                val mailed = runCatching { withContext(Dispatchers.IO) { EsignClient.remind(ref.id, ref.secret, i) } }.getOrDefault(false)
                                if (mailed) Toast.makeText(ctx, remindedMail, Toast.LENGTH_SHORT).show()
                                else links.getOrNull(i)?.let { ShareHelpers.shareText(ctx, ctx.getString(R.string.cn_remind_text, q.title, it), q.title) }
                                reload++
                            } }) { Text(stringResource(R.string.cn_remind)) }
                        }
                    }
                }
            }
            if (q.audit.isNotEmpty()) {
                item { Text(stringResource(R.string.cn_audit_title), style = MaterialTheme.typography.titleSmall) }
                items(q.audit.sortedByDescending { it.time }) { a ->
                    Text("${DocumentRepository.formatDate(a.time)} · ${auditKind(a.kind)} · ${a.who}" + (if (a.ip.isNotBlank()) " · ${a.ip}" else ""),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (q.originalSha.isNotEmpty()) item {
                Text(stringResource(R.string.cn_original_hash), style = MaterialTheme.typography.labelMedium)
                runCatching { AuditFormat.grouped(q.originalSha) }.getOrNull()?.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
            if (q.status == RequestStatus.OUT) item {
                OutlinedButton(onClick = { confirm = "cancel" }, shape = PillShape) { Text(stringResource(R.string.cn_cancel_request), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    busy?.let { ProgressDialog(it) }
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }) }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(if (what == "delete") R.string.cn_delete_q else R.string.cn_cancel_q)) },
            confirmButton = { TextButton(onClick = {
                confirm = null
                val r = ref ?: return@TextButton
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { if (what == "delete") EsignClient.delete(r.id, r.secret) else EsignClient.cancel(r.id, r.secret) } }
                        .onSuccess { if (what == "delete") { EsignStore.remove(ctx, r.id, true); nav.popBackStack() } else reload++ }
                        .onFailure { e -> if (what == "delete" && EsignClient.isGone(e)) { EsignStore.remove(ctx, r.id, true); nav.popBackStack() } else error = e.message }
                }
            }) { Text(stringResource(R.string.cn_yes)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun auditKind(k: String): String = when (k) {
    "created" -> stringResource(R.string.cn_k_created); "sent" -> stringResource(R.string.cn_k_sent); "viewed" -> stringResource(R.string.cn_k_viewed)
    "signed" -> stringResource(R.string.cn_k_signed); "declined" -> stringResource(R.string.cn_k_declined); "reminded" -> stringResource(R.string.cn_k_reminded)
    "cancelled" -> stringResource(R.string.cn_k_cancelled); "finalized" -> stringResource(R.string.cn_k_finalized); else -> k
}
