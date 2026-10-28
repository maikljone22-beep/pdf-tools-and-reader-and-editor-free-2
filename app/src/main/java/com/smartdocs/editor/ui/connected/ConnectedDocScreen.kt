package com.smartdocs.editor.ui.connected

import android.net.Uri
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.connected.ConnDocInfo
import com.smartdocs.editor.core.connected.ConnStore
import com.smartdocs.editor.core.connected.ConnectedClient
import com.smartdocs.editor.core.connected.ConnectedDocs
import com.smartdocs.editor.core.esign.AuditFormat
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.esign.Chip
import com.smartdocs.editor.ui.esign.LinkActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Route "connected/doc/{id}": owner view — opens, readers (approve/block), versions, settings, revoke. */
@Composable
fun ConnectedDocScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val doc = remember(id) { ConnStore.load(ctx).let { ConnStore.get(ctx, id) } }
    var info by remember { mutableStateOf<ConnDocInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var newVersion by remember { mutableStateOf<File?>(null) }
    var editMessage by remember { mutableStateOf(false) }   // fc-sync
    var cloneAsk by remember { mutableStateOf(false) }
    val settings = com.smartdocs.editor.LocalSettings.current

    LaunchedEffect(reload) {
        val d = doc ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { ConnectedClient.info(d.id, d.ownerKey) } }
            .onSuccess { info = it; error = null }.onFailure { error = it.message }
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        busy = ctx.getString(R.string.cn_working)
        runCatching { withContext(Dispatchers.IO) { block() } }.onFailure { error = it.message }
        busy = null; reload++
    }
    val pickVersion = rememberPdfPicker { l -> newVersion = l.firstOrNull() }

    Scaffold(topBar = {
        AppTopBar(doc?.title ?: "", onBack = { nav.popBackStack() }) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.cn_refresh)) }
            IconButton(onClick = { cloneAsk = true }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.fcy_conn_clone)) }
            IconButton(onClick = { confirm = "delete" }) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.cn_delete)) }
        }
    }) { pad ->
        val d = doc
        if (d == null) { EmptyState(Icons.Default.SearchOff, stringResource(R.string.cn_gone), Modifier.padding(pad)); return@Scaffold }
        val i = info
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val local = File(d.localPath).takeIf { it.exists() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (local != null) OutlinedButton(onClick = { nav.navigate(Routes.reader(Uri.fromFile(local).toString())) }, shape = PillShape) { Text(stringResource(R.string.cn_open)) }
                    if (local != null) OutlinedButton(onClick = { ctx.startActivity(android.content.Intent.createChooser(app.documents.shareIntent(listOf(local)), null)) }, shape = PillShape) {
                        Text(stringResource(R.string.cn_share_file))
                    }
                }
                Text(stringResource(R.string.cn_doc_link_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinkActions("smartdocs://doc/${d.id}", subject = d.title)
            }
            if (i == null) { item { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()) }; return@LazyColumn }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.cn_opens_n, i.opens), MaterialTheme.colorScheme.primary)
                    Chip(stringResource(R.string.cn_readers_n, i.readers.size), MaterialTheme.colorScheme.tertiary)
                    if (i.protected) Chip(stringResource(R.string.cn_protected), MaterialTheme.colorScheme.error)
                    if (i.revoked) Chip(stringResource(R.string.cn_revoked), MaterialTheme.colorScheme.error)
                }
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(12.dp)) {
                        SwitchRow(stringResource(R.string.cn_require_approval), null, i.requireApproval) { v -> act { ConnectedClient.settings(d.id, d.ownerKey, v, i.offlineDays, i.revoked) } }
                        if (i.protected) Text(stringResource(R.string.cn_offline_days_n, i.offlineDays), style = MaterialTheme.typography.bodySmall)
                        // fc-sync: open message shown to readers
                        Text(if (i.openMessage.isBlank()) stringResource(R.string.fcy_conn_no_message) else stringResource(R.string.fcy_conn_message_is, i.openMessage),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { editMessage = true }) { Text(stringResource(R.string.fcy_conn_message)) }
                        if (i.revoked) TextButton(onClick = { act { ConnectedClient.settings(d.id, d.ownerKey, i.requireApproval, i.offlineDays, false) } }) { Text(stringResource(R.string.cn_unrevoke)) }
                        else TextButton(onClick = { confirm = "revoke" }) { Text(stringResource(R.string.cn_revoke_all), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item { Text(stringResource(R.string.cn_readers_title), style = MaterialTheme.typography.titleSmall) }
            if (i.readers.isEmpty()) item { Text(stringResource(R.string.cn_no_readers), style = MaterialTheme.typography.bodySmall) }
            items(i.readers, key = { it.rid }) { r ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name.ifBlank { stringResource(R.string.cn_reader_anon, r.rid.take(6)) }, style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.cn_reader_stats, r.opens, DocumentRepository.formatDate(r.last)), style = MaterialTheme.typography.bodySmall)
                            }
                            when (r.status) {
                                "allowed" -> Chip(stringResource(R.string.cn_allowed), MaterialTheme.colorScheme.primary)
                                "blocked" -> Chip(stringResource(R.string.cn_blocked), MaterialTheme.colorScheme.error)
                                else -> Chip(stringResource(R.string.cn_waiting_approval), MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        if (r.message.isNotBlank()) Text("«${r.message}»", style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (r.status != "allowed") TextButton(onClick = { act { ConnectedClient.setReader(d.id, d.ownerKey, r.rid, "allowed") } }) { Text(stringResource(R.string.cn_approve)) }
                            if (r.status != "blocked") TextButton(onClick = { act { ConnectedClient.setReader(d.id, d.ownerKey, r.rid, "blocked") } }) { Text(stringResource(R.string.cn_block), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cn_versions_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    FilledTonalButton(onClick = pickVersion, shape = PillShape) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cn_publish_version)) }
                }
            }
            items(i.versions.reversed(), key = { "v" + it.n }) { v ->
                Column {
                    Text(stringResource(R.string.cn_version_n, v.n) + " · " + DocumentRepository.formatDate(v.time) + " · " + DocumentRepository.formatSize(v.size), style = MaterialTheme.typography.bodyMedium)
                    if (v.note.isNotBlank()) Text(v.note, style = MaterialTheme.typography.bodySmall)
                    Text(AuditFormat.short(v.sha) + if (!v.hasFile) "  " + stringResource(R.string.cn_hash_only) else "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    busy?.let { ProgressDialog(it) }
    if (editMessage) info?.let { i ->
        var text by remember { mutableStateOf(i.openMessage) }
        AlertDialog(onDismissRequest = { editMessage = false }, title = { Text(stringResource(R.string.fcy_conn_message)) },
            text = { Column {
                Text(stringResource(R.string.fcy_conn_message_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { if (it.length <= 500) text = it }, Modifier.fillMaxWidth(), minLines = 2)
            } },
            confirmButton = { TextButton(onClick = {
                val d = doc ?: return@TextButton
                editMessage = false
                act { ConnectedClient.settings(d.id, d.ownerKey, i.requireApproval, i.offlineDays, i.revoked, text.trim()) }
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editMessage = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (cloneAsk) doc?.let { d ->
        var title by remember { mutableStateOf(ctx.getString(R.string.fcy_conn_clone_title, d.title)) }
        var upload by remember { mutableStateOf(true) }
        AlertDialog(onDismissRequest = { cloneAsk = false }, title = { Text(stringResource(R.string.fcy_conn_clone)) },
            text = { Column {
                Text(stringResource(R.string.fcy_conn_clone_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true)
                SwitchRow(stringResource(R.string.cn_upload_versions), null, upload) { upload = it }
            } },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
                cloneAsk = false
                scope.launch {
                    busy = ctx.getString(R.string.cn_working)
                    runCatching { ConnectedDocs.clone(ctx, d, app.documents.docsDir, title.trim(), settings.authorName, info, upload) }
                        .onSuccess { nav.navigate(ConnRoutes.connectedDoc(it.id)) }
                        .onFailure { error = it.message }
                    busy = null
                }
            }) { Text(stringResource(R.string.fcy_conn_clone)) } },
            dismissButton = { TextButton(onClick = { cloneAsk = false }) { Text(stringResource(R.string.cancel)) } })
    }
    newVersion?.let { f ->
        var note by remember { mutableStateOf("") }
        var upload by remember { mutableStateOf(true) }
        AlertDialog(onDismissRequest = { newVersion = null }, title = { Text(stringResource(R.string.cn_publish_version)) },
            text = { Column {
                Text(f.name, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.cn_version_note)) })
                SwitchRow(stringResource(R.string.cn_upload_versions), null, upload) { upload = it }
            } },
            confirmButton = { TextButton(onClick = {
                val d = doc ?: return@TextButton
                newVersion = null
                act { ConnectedDocs.publishVersion(ctx, d, f, app.documents.docsDir, note.trim(), upload) }
            }) { Text(stringResource(R.string.cn_publish)) } },
            dismissButton = { TextButton(onClick = { newVersion = null }) { Text(stringResource(R.string.cancel)) } })
    }
    confirm?.let { what ->
        AlertDialog(onDismissRequest = { confirm = null },
            text = { Text(stringResource(if (what == "delete") R.string.cn_doc_delete_q else R.string.cn_revoke_q)) },
            confirmButton = { TextButton(onClick = {
                confirm = null
                val d = doc ?: return@TextButton
                if (what == "revoke") act { ConnectedClient.revokeAll(d.id, d.ownerKey) }
                else scope.launch {
                    runCatching { withContext(Dispatchers.IO) { ConnectedClient.delete(d.id, d.ownerKey) } }
                        .onSuccess { ConnStore.remove(ctx, d.id); nav.popBackStack() }
                        .onFailure { e -> if (ConnectedClient.isGone(e)) { ConnStore.remove(ctx, d.id); nav.popBackStack() } else error = e.message }
                }
            }) { Text(stringResource(R.string.cn_yes)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
