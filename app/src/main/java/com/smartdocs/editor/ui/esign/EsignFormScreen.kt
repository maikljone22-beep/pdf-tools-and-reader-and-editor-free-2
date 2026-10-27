package com.smartdocs.editor.ui.esign

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.esign.EsignClient
import com.smartdocs.editor.core.esign.EsignComposer
import com.smartdocs.editor.core.esign.EsignForm
import com.smartdocs.editor.core.esign.EsignFormStore
import com.smartdocs.editor.core.esign.FormCopies
import com.smartdocs.editor.core.esign.FormState
import com.smartdocs.editor.core.esign.FormSubmission
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.ResultSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FormStateChip(s: FormState) {
    val (label, color) = when (s) {
        FormState.ACTIVE -> stringResource(R.string.ne2_form_state_active) to androidx.compose.ui.graphics.Color(0xFF15803D)
        FormState.DISABLED -> stringResource(R.string.ne2_form_state_disabled) to MaterialTheme.colorScheme.outline
        FormState.FULL -> stringResource(R.string.ne2_form_state_full) to MaterialTheme.colorScheme.tertiary
        FormState.DRAFT -> stringResource(R.string.ne2_form_state_draft) to MaterialTheme.colorScheme.outline
    }
    Chip(label, color)
}

/** Route "esign/form/{id}": the public link, accepting/stopping responses, the responses and each person's signed copy. */
@Composable
fun EsignFormScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val ref = remember(id) { EsignFormStore.load(ctx).let { EsignFormStore.get(ctx, id) } }
    var form by remember { mutableStateOf<EsignForm?>(null) }
    var subs by remember { mutableStateOf<List<FormSubmission>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        val r = ref ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { EsignClient.getForm(r.id, r.secret) to EsignClient.formSubmissions(r.id, r.secret) } }
            .onSuccess { (f, l) -> form = f; subs = l; error = null
                EsignFormStore.update(ctx, r.id) { it.copy(state = f.state.name.lowercase(), count = l.size, max = f.maxResponses, link = f.link.ifBlank { it.link }) } }
            .onFailure { error = if (EsignClient.isGone(it)) ctx.getString(R.string.cn_gone) else it.message }
    }

    fun labels() = EsignComposer.CertText(
        title = ctx.getString(R.string.cn_cert_title), document = ctx.getString(R.string.cn_cert_document), requestId = ctx.getString(R.string.cn_cert_id),
        sender = ctx.getString(R.string.cn_cert_sender), created = ctx.getString(R.string.cn_cert_created), originalHash = ctx.getString(R.string.cn_cert_hash),
        signers = ctx.getString(R.string.cn_cert_signers), status = ctx.getString(R.string.cn_cert_status), ip = "IP", device = ctx.getString(R.string.cn_cert_device),
        agent = ctx.getString(R.string.cn_cert_agent), time = ctx.getString(R.string.cn_cert_time), audit = ctx.getString(R.string.cn_cert_audit),
        verify = ctx.getString(R.string.cn_cert_verify), verifyNote = ctx.getString(R.string.cn_cert_verify_note), signed = ctx.getString(R.string.cn_st_signed),
        declined = ctx.getString(R.string.cn_st_declined), pending = ctx.getString(R.string.cn_st_pending),
        kinds = mapOf("created" to ctx.getString(R.string.cn_k_created), "signed" to ctx.getString(R.string.cn_k_signed)),
    )

    /** Builds one person's completed copy (values + certificate) and registers its hash for /verify. */
    fun makeCopy(s: FormSubmission) = scope.launch {
        val r = ref ?: return@launch; val f = form ?: return@launch
        busy = ctx.getString(R.string.cn_composing)
        runCatching {
            withContext(Dispatchers.IO) {
                val src = File(r.localPath).takeIf { it.exists() } ?: throw IllegalStateException(ctx.getString(R.string.cn_original_missing))
                val full = EsignClient.formSubmission(r.id, r.secret, s.sid)
                val (req, values) = FormCopies.asRequest(f, full)
                val out = app.documents.uniqueFile(app.documents.docsDir, "${f.title} - ${full.name} (" + ctx.getString(R.string.cn_signed_suffix) + ")")
                EsignComposer.compose(src, out, req, values, labels(), EsignClient.verifyPage())
                EsignClient.uploadFormFinal(r.id, r.secret, s.sid, out)
                out
            }
        }.onSuccess { result = it; reload++ }.onFailure { error = it.message ?: it.toString() }
        busy = null
    }

    fun setEnabled(on: Boolean) = scope.launch {
        val r = ref ?: return@launch
        runCatching { withContext(Dispatchers.IO) { EsignClient.setFormEnabled(r.id, r.secret, on) } }
            .onSuccess { reload++ }.onFailure { error = it.message ?: it.toString() }
    }

    Scaffold(topBar = {
        AppTopBar(form?.title ?: ref?.title ?: "", onBack = { nav.popBackStack() }) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.cn_refresh)) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.cn_delete)) }
        }
    }) { pad ->
        val f = form
        if (ref == null) { EmptyState(Icons.Default.SearchOff, stringResource(R.string.cn_gone), Modifier.padding(pad)); return@Scaffold }
        if (f == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: CircularProgressIndicator() }
            return@Scaffold
        }
        val link = f.link.ifBlank { ref.link }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FormStateChip(f.state); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ne2_form_counter, FormCopies.counter(f)), style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (link.isNotBlank()) item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.ne2_form_link), style = MaterialTheme.typography.titleSmall)
                        Text(link, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        LinkActions(link, "", f.title, ctx.getString(R.string.ne2_form_invite, f.title, link))
                        if (f.state == FormState.ACTIVE || f.state == FormState.FULL) OutlinedButton(onClick = { setEnabled(false) }, shape = PillShape) {
                            Icon(Icons.Default.Block, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ne2_form_disable))
                        }
                        if (f.state == FormState.DISABLED) Button(onClick = { setEnabled(true) }, shape = PillShape) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ne2_form_enable))
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.ne2_form_responses, subs.size.toString()), style = MaterialTheme.typography.titleSmall) }
            if (subs.isEmpty()) item { Text(stringResource(R.string.ne2_form_no_responses), style = MaterialTheme.typography.bodySmall) }
            items(subs.sortedByDescending { it.time }, key = { it.sid }) { s ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            Text(listOf(s.email, DocumentRepository.formatDate(s.time)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            if (s.finalSha.isNotBlank()) Text(stringResource(R.string.ne2_form_copy_done), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { makeCopy(s) }) { Text(stringResource(R.string.ne2_form_make_copy)) }
                    }
                }
            }
        }
    }

    busy?.let { ProgressDialog(it) }
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text(stringResource(R.string.ne2_form_delete_q)) },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            val r = ref ?: return@TextButton
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { EsignClient.deleteForm(r.id, r.secret) } }
                    .onSuccess { EsignFormStore.remove(ctx, r.id); nav.popBackStack() }
                    .onFailure { e -> if (EsignClient.isGone(e)) { EsignFormStore.remove(ctx, r.id); nav.popBackStack() } else error = e.message }
            }
        }) { Text(stringResource(R.string.cn_yes)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
