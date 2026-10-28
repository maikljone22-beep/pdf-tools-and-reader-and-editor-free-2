package com.smartdocs.editor.ui.cloud

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.collab.CollabClient
import com.smartdocs.editor.core.collab.CollabConfig
import com.smartdocs.editor.core.collab.CollabFlows
import com.smartdocs.editor.core.collab.CollabStore
import com.smartdocs.editor.core.collab.ReviewRef
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.launch
import java.io.File

/**
 * Route "review": shared reviews list, create (upload a PDF + optional deadline), join by link/code.
 * With [initialId] (deep link `smartdocs://review/<id>?c=<code>`) it joins/opens that review directly.
 */
@Composable
fun ReviewScreen(nav: NavHostController, initialId: String?, initialCode: String?) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val reviews by CollabStore.reviews.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFile by remember { mutableStateOf<File?>(null) }
    var joinDialog by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<ReviewRef?>(null) }
    val joining = stringResource(R.string.ac_review_joining)
    val creating = stringResource(R.string.ac_review_creating)

    LaunchedEffect(Unit) {
        CollabStore.load(ctx)
        if (CollabStore.authorName(ctx).isBlank() && settings.authorName.isNotBlank()) CollabStore.setAuthorName(ctx, settings.authorName)
    }

    fun join(id: String, code: String) = scope.launch {
        busy = joining; error = null
        runCatching { CollabFlows.join(ctx, id.trim(), code.trim().uppercase()) }
            .onSuccess { openId = it.id }
            .onFailure { error = if (CollabClient.isGone(it)) ctx.getString(R.string.ac_review_gone) else (it.message ?: it.toString()) }
        busy = null
    }

    LaunchedEffect(initialId) {
        val id = initialId ?: return@LaunchedEffect
        val known = CollabStore.get(ctx, id)
        if (known != null) openId = id
        else if (initialCode != null) join(id, initialCode)
    }

    val pick = rememberPdfPicker { newFile = it.firstOrNull() }

    if (!CollabConfig.enabled) {
        Scaffold(topBar = { AppTopBar(stringResource(R.string.ac_review_title), onBack = { nav.popBackStack() }) }) { pad ->
            EmptyState(Icons.Default.CloudOff, stringResource(R.string.ac_review_disabled), Modifier.padding(pad))
        }
        return
    }

    val detail = openId?.let { id -> reviews.firstOrNull { it.id == id } }
    if (detail != null) {
        ReviewDetail(nav, detail, onBack = { openId = null })
    } else Scaffold(
        topBar = { AppTopBar(stringResource(R.string.ac_review_title), onBack = { nav.popBackStack() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = pick, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.ac_review_new)) }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(stringResource(R.string.ac_review_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { joinDialog = true }, shape = PillShape) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ac_review_join)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (reviews.isEmpty()) item { EmptyState(Icons.Default.RateReview, stringResource(R.string.ac_review_empty)) }
            items(reviews.sortedByDescending { it.lastSync }, key = { it.id }) { r ->
                Surface(onClick = { openId = r.id }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(if (r.isOwner) Icons.Default.Outbox else Icons.Default.MoveToInbox, if (r.isOwner) Brand.tiles.purple else Brand.tiles.teal, size = 42.dp, iconSize = 22.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(if (r.isOwner) R.string.ac_review_mine else R.string.ac_review_invited) +
                                (if (r.deadline > 0) " · " + stringResource(R.string.ac_review_due, DocumentRepository.formatDate(r.deadline)) else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronLeft, null)
                    }
                }
            }
        }
    }

    busy?.let { ProgressDialog(it) }

    newFile?.let { f ->
        NewReviewDialog(f, onDismiss = { newFile = null }) { title, deadline, name ->
            newFile = null
            CollabStore.setAuthorName(ctx, name)
            scope.launch {
                busy = creating; error = null
                runCatching { CollabFlows.create(ctx, f, title, deadline) }
                    .onSuccess { (ref, _) -> created = ref; openId = ref.id }
                    .onFailure {
                        error = when (it) {
                            is CollabFlows.TooLarge -> ctx.getString(R.string.ac_review_too_large)
                            is CollabFlows.Encrypted -> ctx.getString(R.string.ac_review_encrypted)
                            else -> it.message ?: it.toString()
                        }
                    }
                busy = null
            }
        }
    }

    if (joinDialog) JoinDialog(initialName = CollabStore.authorName(ctx).ifBlank { settings.authorName }, onDismiss = { joinDialog = false }) { id, code, name ->
        joinDialog = false
        CollabStore.setAuthorName(ctx, name)
        join(id, code)
    }

    created?.let { r -> ShareDialog(r) { created = null } }
}

@Composable
private fun ReviewDetail(nav: NavHostController, ref: ReviewRef, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf<com.smartdocs.editor.core.collab.ReviewMeta?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var share by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val gone = stringResource(R.string.ac_review_gone)
    val newComments = stringResource(R.string.ac_review_new_comments)
    val published = stringResource(R.string.ac_review_published)
    val nothing = stringResource(R.string.ac_review_nothing_new)

    suspend fun refresh(manual: Boolean) {
        runCatching {
            meta = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CollabClient.meta(ref.id, ref.code) }
            val n = CollabStore.fetch(ctx, ref)
            if (n > 0) status = newComments.format(n) else if (manual) status = nothing
        }.onFailure { status = if (CollabClient.isGone(it)) gone else it.message }
    }
    // poll every 30 s while visible
    LaunchedEffect(ref.id) { while (true) { refresh(false); kotlinx.coroutines.delay(30_000) } }
    com.smartdocs.editor.ui.share5.ReviewConsentGate(ref)   // share6: privacy consent / anonymous choice

    Scaffold(topBar = {
        AppTopBar(ref.title, onBack = onBack, actions = {
            IconButton(onClick = { share = true }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
            IconButton(onClick = { nav.navigate(com.smartdocs.editor.ui.share5.Share5Routes.review(ref.id)) }) { Icon(Icons.Default.ManageAccounts, stringResource(R.string.pc_cd_reviewers)) }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val m = meta
                val ended = m?.expired == true || ref.closed
                Surface(shape = RoundedCornerShape(20.dp), color = if (ended) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(if (ended) R.string.ac_review_ended else R.string.ac_review_active), style = MaterialTheme.typography.titleMedium)
                        val dl = m?.deadline ?: ref.deadline
                        Text(if (dl > 0) stringResource(R.string.ac_review_due, DocumentRepository.formatDate(dl)) else stringResource(R.string.ac_review_no_deadline), style = MaterialTheme.typography.bodySmall)
                        if (ref.lastSync > 0) Text(stringResource(R.string.ac_review_synced, DocumentRepository.formatDate(ref.lastSync)), style = MaterialTheme.typography.bodySmall)
                        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
            }
            item {
                Button(onClick = { nav.navigate(Routes.reader(Uri.fromFile(File(ref.localPath)).toString())) }, Modifier.fillMaxWidth().height(52.dp), shape = PillShape) {
                    Icon(Icons.Default.MenuBook, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ac_review_open_doc))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch { runCatching { CollabStore.publish(ctx, ref) }.onSuccess { status = if (it > 0) published.format(it) else nothing }.onFailure { status = if (CollabClient.isGone(it)) gone else it.message } }
                    }, Modifier.weight(1f)) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ac_review_publish)) }
                    FilledTonalButton(onClick = { scope.launch { refresh(true) } }, Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ac_review_check))
                    }
                }
                Text(stringResource(R.string.ac_review_save_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            item { Text(stringResource(R.string.ac_review_reviewers), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            val rv = meta?.reviewers.orEmpty()
            if (rv.isEmpty()) item { Text(stringResource(R.string.ac_review_no_reviewers), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rv) { r ->
                ListItem(
                    leadingContent = { Icon(Icons.Default.Person, null) },
                    headlineContent = { Text(r.name.ifBlank { stringResource(R.string.ac_anonymous) }) },
                    supportingContent = { Text(stringResource(R.string.ac_review_reviewer_line, r.count, DocumentRepository.formatDate(r.lastSeen))) },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                if (ref.isOwner) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!ref.closed) OutlinedButton(onClick = { confirm = "close" }) { Text(stringResource(R.string.ac_review_close)) }
                    OutlinedButton(onClick = { confirm = "delete" }) { Text(stringResource(R.string.ac_review_delete), color = MaterialTheme.colorScheme.error) }
                } else TextButton(onClick = { confirm = "leave" }) { Text(stringResource(R.string.ac_review_leave)) }
            }
        }
    }

    if (share) ShareDialog(ref) { share = false }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(when (what) { "close" -> R.string.ac_review_close; "delete" -> R.string.ac_review_delete; else -> R.string.ac_review_leave })) },
            text = { Text(stringResource(when (what) { "close" -> R.string.ac_review_close_q; "delete" -> R.string.ac_review_delete_q; else -> R.string.ac_review_leave_q })) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                when (what) {
                                    "close" -> { CollabClient.close(ref.id, ref.ownerKey!!); CollabStore.upsert(ctx, ref.copy(closed = true)) }
                                    "delete" -> { CollabClient.delete(ref.id, ref.ownerKey!!); CollabStore.remove(ctx, ref.id) }
                                    else -> CollabStore.remove(ctx, ref.id)
                                }
                            }
                        }.onFailure { status = it.message }
                        if (what != "close") onBack()
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewReviewDialog(file: File, onDismiss: () -> Unit, onCreate: (title: String, deadline: Long, name: String) -> Unit) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    var title by remember { mutableStateOf(file.nameWithoutExtension) }
    var name by remember { mutableStateOf(CollabStore.authorName(ctx).ifBlank { settings.authorName }) }
    var deadline by remember { mutableLongStateOf(0L) }
    var pickDate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ac_review_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.ac_review_subject)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ac_your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (deadline > 0) stringResource(R.string.ac_review_due, DocumentRepository.formatDate(deadline)) else stringResource(R.string.ac_review_no_deadline), Modifier.weight(1f))
                    if (deadline > 0) IconButton(onClick = { deadline = 0 }) { Icon(Icons.Default.Close, stringResource(R.string.pc_cd_remove)) }
                    TextButton(onClick = { pickDate = true }) { Text(stringResource(R.string.ac_review_add_deadline)) }
                }
                Text(stringResource(R.string.ac_review_link_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && name.isNotBlank(), onClick = { onCreate(title.trim(), deadline, name.trim()) }) { Text(stringResource(R.string.ac_review_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis() + 7L * 24 * 3600 * 1000)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { deadline = endOfDayLocal(it) }; pickDate = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

/** The picker returns UTC midnight of the chosen day; the deadline is 23:59:59 of that day in local time. */
private fun endOfDayLocal(utcMidnight: Long): Long {
    val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
    return java.util.Calendar.getInstance().apply {
        clear(); set(utc.get(java.util.Calendar.YEAR), utc.get(java.util.Calendar.MONTH), utc.get(java.util.Calendar.DAY_OF_MONTH), 23, 59, 59)
    }.timeInMillis
}

@Composable
private fun JoinDialog(initialName: String, onDismiss: () -> Unit, onJoin: (id: String, code: String, name: String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialName) }
    // accepts a share link (https://…/r/<id>?c=<code>), a deep link, or a bare id
    val parsed = remember(input) {
        val u = runCatching { Uri.parse(input.trim()) }.getOrNull()
        val segs = u?.pathSegments.orEmpty()
        when {
            u?.scheme == "smartdocs" -> (segs.firstOrNull() ?: "") to (u.getQueryParameter("c") ?: "")
            u?.scheme?.startsWith("http") == true && segs.size >= 2 && segs[segs.size - 2] == "r" -> segs.last() to (u.getQueryParameter("c") ?: "")
            else -> input.trim() to ""
        }
    }
    val effectiveCode = parsed.second.ifBlank { code }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ac_review_join)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(input, { input = it }, label = { Text(stringResource(R.string.ac_review_link_or_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (parsed.second.isBlank()) OutlinedTextField(code, { code = it.uppercase() }, label = { Text(stringResource(R.string.ac_review_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ac_your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = parsed.first.length == 10 && effectiveCode.length == 6 && name.isNotBlank(), onClick = { onJoin(parsed.first, effectiveCode, name.trim()) }) { Text(stringResource(R.string.ac_review_join)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ShareDialog(ref: ReviewRef, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val link = CollabClient.shareUrl(ref.id, ref.code)
    val copied = stringResource(R.string.ac_copied)
    val shareText = stringResource(R.string.ac_review_share_text, ref.title, link, ref.id, ref.code)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Share, null) },
        title = { Text(stringResource(R.string.ac_review_share)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QrImage(link, Modifier.width(180.dp))
                Text(stringResource(R.string.ac_review_id_code, ref.id, ref.code), style = MaterialTheme.typography.titleSmall)
                Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) }, null)) }
                onDismiss()
            }) { Text(stringResource(R.string.share)) }
        },
        dismissButton = {
            TextButton(onClick = { copy(ctx, link); Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show() }) { Text(stringResource(R.string.ac_copy_link)) }
        },
    )
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("link", text))
}
