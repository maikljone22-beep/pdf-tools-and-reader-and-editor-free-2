package com.smartdocs.editor.ui.esign

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.esign.BulkItem
import com.smartdocs.editor.core.esign.BulkSender
import com.smartdocs.editor.core.esign.BulkState
import com.smartdocs.editor.core.esign.BulkTemplate
import com.smartdocs.editor.core.esign.CsvRecipients
import com.smartdocs.editor.core.esign.EsignBrandStore
import com.smartdocs.editor.core.esign.EsignClient
import com.smartdocs.editor.core.esign.EsignConfig
import com.smartdocs.editor.core.esign.EsignField
import com.smartdocs.editor.core.esign.EsignFormRef
import com.smartdocs.editor.core.esign.EsignFormStore
import com.smartdocs.editor.core.esign.EsignRef
import com.smartdocs.editor.core.esign.EsignStore
import com.smartdocs.editor.core.esign.SignOrder
import com.smartdocs.editor.core.esign.Signer
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.connected.ConnRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_BYTES = 20L * 1024 * 1024

/** What the create flow makes: a normal request, one request per row of a list, or an online form (one public link). */
enum class EsignCreateMode { REQUEST, BULK, FORM;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: REQUEST }
}

/** Route "esign/create?uri=&mode=": document & options, signers (or recipient list / form options), fields, send. */
@Composable
fun EsignCreateScreen(nav: NavHostController, uri: String?, mode: EsignCreateMode = EsignCreateMode.REQUEST) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var file by remember { mutableStateOf(uri?.let { Uri.parse(it).path }?.let(::File)?.takeIf { it.exists() }) }
    var title by remember { mutableStateOf(file?.nameWithoutExtension.orEmpty()) }
    var message by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf(settings.authorName) }
    var senderEmail by remember { mutableStateOf("") }
    var order by remember { mutableStateOf(SignOrder.SEQUENTIAL) }
    var days by remember { mutableStateOf("30") }
    var mail by remember { mutableStateOf(true) }
    val signers = remember { mutableStateListOf(Signer("")) }
    val fields = remember { mutableStateListOf<EsignField>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    // bulk
    var bulkText by remember { mutableStateOf("") }
    val parsed = remember(bulkText) { CsvRecipients.parse(bulkText) }
    var bulkItems by remember { mutableStateOf<List<BulkItem>>(emptyList()) }
    var bulkProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var bulkResult by remember { mutableStateOf(false) }
    var bulkCopy by remember { mutableStateOf<File?>(null) }
    // form
    var maxResponses by remember { mutableStateOf("1000") }
    var requireEmail by remember { mutableStateOf(false) }
    var notify by remember { mutableStateOf(true) }
    val placerSigners = when (mode) {
        EsignCreateMode.REQUEST -> signers
        EsignCreateMode.BULK -> listOf(Signer(stringResource(R.string.ne2_each_recipient)))
        EsignCreateMode.FORM -> listOf(Signer(stringResource(R.string.ne2_each_person)))
    }
    val session = rememberPdfSession(if (step >= 2) file else null)
    val tooBig = stringResource(R.string.cn_too_big)
    val encrypted = stringResource(R.string.cn_encrypted)
    val noFields = stringResource(R.string.cn_no_fields_for)
    val pick = rememberPdfPicker { l -> l.firstOrNull()?.let { file = it; if (title.isBlank()) title = it.nameWithoutExtension; fields.clear() } }

    fun validate(): String? = when (step) {
        0 -> when {
            file == null -> ctx.getString(R.string.cn_pick_doc)
            file!!.length() > MAX_BYTES -> tooBig
            title.isBlank() -> ctx.getString(R.string.cn_need_title)
            else -> null
        }
        1 -> when (mode) {
            EsignCreateMode.BULK -> if (parsed.rows.isEmpty()) ctx.getString(R.string.ne2_bulk_need_rows) else null
            EsignCreateMode.FORM -> if ((maxResponses.toIntOrNull() ?: 0) !in 1..5000) ctx.getString(R.string.ne2_form_bad_max) else null
            EsignCreateMode.REQUEST -> if (signers.any { it.name.isBlank() }) ctx.getString(R.string.cn_need_names)
                else if (mail && signers.any { it.email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(it.email.trim()).matches() }) ctx.getString(R.string.cn_bad_email)
                else null
        }
        2 -> placerSigners.indices.firstOrNull { i -> fields.none { it.signer == i } }?.let { noFields.format(placerSigners[it].name) }
        else -> null
    }

    fun send() = scope.launch {
        val f = file ?: return@launch
        busy = true; error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val pages = PdfSession.open(ctx, f).use { it.pageCount }
                val c = EsignClient.create(f, title.trim(), sender.trim(), senderEmail.trim(), message.trim(), order,
                    signers.map { it.copy(name = it.name.trim(), email = it.email.trim()) }, fields.toList(), pages, days.toIntOrNull() ?: 30, mail,
                    EsignBrandStore.current(ctx))
                val keep = File(ctx.filesDir, "esign/${c.id}.pdf").apply { parentFile?.mkdirs() }
                f.copyTo(keep, overwrite = true)
                EsignStore.upsert(ctx, EsignRef(c.id, title.trim(), true, c.ownerKey, System.currentTimeMillis(), keep.absolutePath, "out", counts = "0/${signers.size}"))
                c.id to c.links
            }
        }.onSuccess { created = it }
            .onFailure { error = if (it is PdfSession.Companion.PasswordRequiredException) encrypted else (it.message ?: it.toString()) }
        busy = false
    }

    /** One request per recipient; rows already sent are skipped, so calling it again retries only the failures. */
    fun sendBulk() = scope.launch {
        val f = file ?: return@launch
        error = null
        val items = bulkItems.ifEmpty { parsed.rows.map { BulkItem(it) } }
        bulkProgress = 0 to items.count { it.state != BulkState.SENT }
        runCatching {
            withContext(Dispatchers.IO) {
                val pages = PdfSession.open(ctx, f).use { it.pageCount }
                val keep = bulkCopy ?: File(ctx.filesDir, "esign/bulk_${System.currentTimeMillis()}.pdf").apply { parentFile?.mkdirs(); f.copyTo(this, overwrite = true) }
                bulkCopy = keep
                val brand = EsignBrandStore.current(ctx)
                val expiry = days.toIntOrNull() ?: 30
                val ctxJob = coroutineContext
                BulkSender.run(items, send = { r ->
                    ctxJob.ensureActive()
                    val t = BulkTemplate.fill(title.trim(), r).take(120)
                    val c = EsignClient.create(f, t, sender.trim(), senderEmail.trim(), BulkTemplate.fill(message.trim(), r), SignOrder.SEQUENTIAL,
                        listOf(Signer(r.name, r.email)), fields.map { it.copy(signer = 0) }, pages, expiry, mail && r.email.isNotBlank(), brand)
                    EsignStore.upsert(ctx, EsignRef(c.id, t, true, c.ownerKey, System.currentTimeMillis(), keep.absolutePath, "out", counts = "0/1"))
                    BulkSender.Sent(c.id, c.links.firstOrNull().orEmpty())
                }, progress = { d, t -> bulkProgress = d to t }, retryable = EsignClient::isTransient)
            }
        }.onSuccess { bulkItems = it; bulkResult = true }
            .onFailure { error = if (it is PdfSession.Companion.PasswordRequiredException) encrypted else (it.message ?: it.toString()) }
        bulkProgress = null
    }

    fun sendForm() = scope.launch {
        val f = file ?: return@launch
        busy = true; error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val pages = PdfSession.open(ctx, f).use { it.pageCount }
                val max = maxResponses.toIntOrNull() ?: 1000
                val (id, key, link) = EsignClient.createForm(f, title.trim(), sender.trim(), senderEmail.trim(), message.trim(), fields.map { it.copy(signer = 0) },
                    pages, max, requireEmail, notify, EsignBrandStore.current(ctx))
                val keep = File(ctx.filesDir, "esign/form_$id.pdf").apply { parentFile?.mkdirs() }
                f.copyTo(keep, overwrite = true)
                EsignFormStore.upsert(ctx, EsignFormRef(id, title.trim(), key, System.currentTimeMillis(), link, keep.absolutePath, "active", 0, max))
                id
            }
        }.onSuccess { id -> nav.popBackStack(); nav.navigate(ConnRoutes.esignForm(id)) }
            .onFailure { error = if (it is PdfSession.Companion.PasswordRequiredException) encrypted else (it.message ?: it.toString()) }
        busy = false
    }

    val titles = listOf(R.string.cn_step_doc, when (mode) {
        EsignCreateMode.REQUEST -> R.string.cn_step_signers
        EsignCreateMode.BULK -> R.string.ne2_step_recipients
        EsignCreateMode.FORM -> R.string.ne2_step_form_options
    }, R.string.cn_step_fields, R.string.cn_step_send)
    Scaffold(
        topBar = { AppTopBar(stringResource(titles[step]), onBack = { if (step > 0) step-- else nav.popBackStack() }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cn_step_n, step + 1, 4), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    if (step > 0) OutlinedButton(onClick = { step-- }, shape = PillShape) { Text(stringResource(R.string.cn_back)) }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy && bulkProgress == null && EsignConfig.enabled, shape = PillShape, onClick = {
                        val v = validate()
                        if (v != null) error = v else { error = null; if (step < 3) step++ else when (mode) {
                            EsignCreateMode.REQUEST -> send()
                            EsignCreateMode.BULK -> sendBulk()
                            EsignCreateMode.FORM -> sendForm()
                        } }
                    }) { Text(stringResource(if (step < 3) R.string.cn_next else R.string.cn_send)) }
                }
            }
        },
    ) { pad ->
        if (!EsignConfig.enabled) { NotConfigured(Modifier.padding(pad)); return@Scaffold }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState(), enabled = step != 2).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { NoteCard(it, Icons.Default.ErrorOutline, error = true) }
            when (step) {
                0 -> {
                    FilePickRow(file, stringResource(R.string.cn_document), pick)
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.cn_req_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.cn_message)) }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    OutlinedTextField(sender, { sender = it }, label = { Text(stringResource(R.string.cn_sender_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    OutlinedTextField(senderEmail, { senderEmail = it }, label = { Text(stringResource(R.string.cn_sender_email)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    if (mode == EsignCreateMode.REQUEST) {
                        Text(stringResource(R.string.cn_order), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(order == SignOrder.SEQUENTIAL, { order = SignOrder.SEQUENTIAL }, label = { Text(stringResource(R.string.cn_order_seq)) })
                            FilterChip(order == SignOrder.PARALLEL, { order = SignOrder.PARALLEL }, label = { Text(stringResource(R.string.cn_order_par)) })
                        }
                    }
                    if (mode != EsignCreateMode.FORM) {
                        OutlinedTextField(days, { days = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.cn_expiry_days)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.cn_send_mail), Modifier.weight(1f))
                            Switch(mail, { mail = it })
                        }
                    }
                    when (mode) {
                        EsignCreateMode.BULK -> NoteCard(stringResource(R.string.ne2_bulk_title_hint), Icons.Default.DataObject)
                        EsignCreateMode.FORM -> NoteCard(stringResource(R.string.ne2_form_mode_desc), Icons.Default.Link)
                        EsignCreateMode.REQUEST -> {}
                    }
                    NoteCard(stringResource(if (mode == EsignCreateMode.FORM) R.string.ne2_form_link_note else R.string.cn_identity_note))
                }
                1 -> if (mode == EsignCreateMode.BULK) BulkRecipientsEditor(bulkText, { bulkText = it; bulkItems = emptyList() }, parsed)
                else if (mode == EsignCreateMode.FORM) {
                    OutlinedTextField(maxResponses, { maxResponses = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.ne2_form_max)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ne2_form_require_email), Modifier.weight(1f))
                        Switch(requireEmail, { requireEmail = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ne2_form_notify), Modifier.weight(1f))
                        Switch(notify, { notify = it })
                    }
                    NoteCard(stringResource(R.string.ne2_form_link_note))
                } else {
                    signers.forEachIndexed { i, s ->
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(14.dp).background(signerColor(i), CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (order == SignOrder.SEQUENTIAL) stringResource(R.string.cn_signer_n, i + 1) else stringResource(R.string.cn_signer), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    if (order == SignOrder.SEQUENTIAL && i > 0) IconButton(onClick = { signers.add(i - 1, signers.removeAt(i)); remap(fields, i, i - 1) }) { Icon(Icons.Default.ArrowUpward, null) }
                                    if (signers.size > 1) IconButton(onClick = { signers.removeAt(i); fields.removeAll { it.signer == i }; shiftDown(fields, i) }) { Icon(Icons.Default.Close, stringResource(R.string.cn_delete)) }
                                }
                                OutlinedTextField(s.name, { v -> signers[i] = signers[i].copy(name = v) }, label = { Text(stringResource(R.string.cn_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(s.email, { v -> signers[i] = signers[i].copy(email = v) }, label = { Text(stringResource(R.string.cn_email_opt)) }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (signers.size < 20) OutlinedButton(onClick = { signers += Signer("") }, shape = PillShape) {
                        Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cn_add_signer))
                    }
                }
                2 -> session?.let { s -> Column(Modifier.verticalScroll(rememberScrollState())) { EsignFieldPlacer(s, placerSigners, fields) } }
                    ?: Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                3 -> {
                    val sample = parsed.rows.firstOrNull()
                    val shownTitle = if (mode == EsignCreateMode.BULK && sample != null) BulkTemplate.fill(title.trim(), sample) else title
                    Text(shownTitle, style = MaterialTheme.typography.titleMedium)
                    when (mode) {
                        EsignCreateMode.REQUEST -> {
                            Text(stringResource(if (order == SignOrder.SEQUENTIAL) R.string.cn_order_seq else R.string.cn_order_par), style = MaterialTheme.typography.bodyMedium)
                            signers.forEachIndexed { i, s ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(12.dp).background(signerColor(i), CircleShape)); Spacer(Modifier.width(8.dp))
                                    Text("${s.name}  ${s.email}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(R.string.cn_fields_count, fields.count { it.signer == i }), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            NoteCard(stringResource(if (mail) R.string.cn_send_note_mail else R.string.cn_send_note_links))
                        }
                        EsignCreateMode.BULK -> {
                            Text(stringResource(R.string.ne2_bulk_summary, parsed.rows.size), style = MaterialTheme.typography.bodyMedium)
                            sample?.let { Text(stringResource(R.string.ne2_bulk_sample, "${it.name}  ${it.email}"), style = MaterialTheme.typography.bodySmall) }
                            Text(stringResource(R.string.cn_fields_count, fields.size), style = MaterialTheme.typography.labelMedium)
                            NoteCard(stringResource(if (mail) R.string.cn_send_note_mail else R.string.cn_send_note_links))
                        }
                        EsignCreateMode.FORM -> {
                            Text(stringResource(R.string.ne2_form_summary, maxResponses.toIntOrNull() ?: 1000), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.cn_fields_count, fields.size), style = MaterialTheme.typography.labelMedium)
                            NoteCard(stringResource(R.string.ne2_form_link_note))
                        }
                    }
                    val brand = remember(step) { EsignBrandStore.current(ctx) }
                    BrandPreview(brand, EsignBrandStore.logoFile(ctx).takeIf { brand?.hasLogo == true }, shownTitle.ifBlank { title }, sender.trim(),
                        onEdit = { nav.navigate(ConnRoutes.ESIGN_BRAND) })
                }
            }
        }
    }

    if (busy) ProgressDialog(stringResource(if (mode == EsignCreateMode.FORM) R.string.ne2_form_creating else R.string.cn_sending))
    bulkProgress?.let { (d, t) -> ProgressDialog(stringResource(R.string.ne2_bulk_sending, (d + 1).coerceAtMost(t), t), if (t > 0) d.toFloat() / t else null) }
    if (bulkResult) BulkResultDialog(bulkItems, onRetry = { bulkResult = false; sendBulk() }, onDone = { bulkResult = false; nav.popBackStack() })
    created?.let { (id, links) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.cn_sent_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cn_sent_body), style = MaterialTheme.typography.bodySmall)
                    links.forEachIndexed { i, l ->
                        val s = signers.getOrNull(i)
                        Text(s?.name.orEmpty(), style = MaterialTheme.typography.titleSmall)
                        LinkActions(l, s?.email.orEmpty(), title, ctx.getString(R.string.cn_invite_text, sender.ifBlank { "" }, title, l))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { created = null; nav.popBackStack(); nav.navigate(ConnRoutes.esignDetail(id)) }) { Text(stringResource(R.string.cn_done)) } },
        )
    }
}

/** Swaps signer indices [a] and [b] in the field list (after reordering signers). */
private fun remap(fields: MutableList<EsignField>, a: Int, b: Int) {
    for (i in fields.indices) fields[i] = when (fields[i].signer) { a -> fields[i].copy(signer = b); b -> fields[i].copy(signer = a); else -> fields[i] }
}

private fun shiftDown(fields: MutableList<EsignField>, removed: Int) {
    for (i in fields.indices) if (fields[i].signer > removed) fields[i] = fields[i].copy(signer = fields[i].signer - 1)
}

