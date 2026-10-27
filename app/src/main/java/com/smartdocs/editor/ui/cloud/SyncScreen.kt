package com.smartdocs.editor.ui.cloud

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.cloud.CloudProvider
import com.smartdocs.editor.core.cloud.Clouds
import com.smartdocs.editor.core.sync.SyncLive
import com.smartdocs.editor.core.sync.SyncPair
import com.smartdocs.editor.core.sync.SyncPrefs
import com.smartdocs.editor.core.sync.SyncProgress
import com.smartdocs.editor.core.sync.SyncQueue
import com.smartdocs.editor.core.sync.SyncRuntime
import com.smartdocs.editor.core.sync.SyncStatus
import com.smartdocs.editor.core.sync.SyncStore
import com.smartdocs.editor.core.sync.SyncWorker
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.IconTile
import kotlinx.coroutines.delay

/**
 * Route "sync": local folder ↔ cloud folder pairs (Google Drive, OneDrive, Dropbox, Box) with live status, per-file
 * queue (pending / synced / failed + retry), "sync now", Wi‑Fi-only and auto switches, and the global sync options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val pairs by SyncStore.pairs.collectAsStateWithLifecycle()
    val queues by SyncStore.queues.collectAsStateWithLifecycle()
    val progress by SyncRuntime.progress.collectAsStateWithLifecycle()
    val authVersion by CloudAuthEvents.version.collectAsStateWithLifecycle()
    val connected = rememberConnectedProviders(authVersion)
    var chooseProvider by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<CloudProvider?>(null) }
    var pickedTree by remember { mutableStateOf<Uri?>(null) }
    var confirmDelete by remember { mutableStateOf<SyncPair?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    // ticks the relative "last sync" times
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        SyncStore.load(ctx)
        SyncLive.install(ctx, inForeground = true)
        SyncStore.load(ctx).forEach { SyncStore.queue(ctx, it.id) }
        while (true) { delay(30_000); tick++ }
    }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            pickedTree = uri
        } else provider = null
    }
    fun startAdd() {
        when (connected.size) {
            0 -> nav.navigate(Routes.CLOUD)
            1 -> { provider = connected.first(); pickTree.launch(null) }
            else -> chooseProvider = true
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.ac_sync_title), onBack = { nav.popBackStack() }) {
                if (pairs.isNotEmpty()) IconButton(onClick = { SyncWorker.syncAllNow(ctx) }) { Icon(Icons.Default.Sync, stringResource(R.string.s6_sync_all)) }
                IconButton(onClick = { showOptions = true }) { Icon(Icons.Default.Tune, stringResource(R.string.s6_options)) }
            }
        },
        floatingActionButton = {
            if (connected.isNotEmpty()) ExtendedFloatingActionButton(onClick = { startAdd() }, icon = { Icon(Icons.Default.CreateNewFolder, null) }, text = { Text(stringResource(R.string.ac_sync_add)) })
        },
    ) { pad ->
        when {
            connected.isEmpty() && pairs.isEmpty() -> EmptyState(Icons.Default.CloudSync, stringResource(R.string.s6_sync_need_cloud), Modifier.padding(pad)) {
                Button(onClick = { nav.navigate(Routes.CLOUD) }, shape = PillShape) { Text(stringResource(R.string.s6_connect_cloud)) }
            }
            pairs.isEmpty() -> EmptyState(Icons.Default.Sync, stringResource(R.string.s6_sync_empty), Modifier.padding(pad))
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.s6_sync_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(pairs, key = { it.id }) { p ->
                    key(tick) { PairCard(p, queues[p.id].orEmpty(), progress[p.id], onDelete = { confirmDelete = p }) }
                }
            }
        }
    }

    if (chooseProvider) SyncProviderChooser(connected, onDismiss = { chooseProvider = false }) { p ->
        chooseProvider = false; provider = p; pickTree.launch(null)
    }
    val prov = provider
    val tree = pickedTree
    if (prov != null && tree != null) {
        CloudFolderPicker(prov, onDismiss = { pickedTree = null; provider = null }) { folder ->
            val name = DocumentFile.fromTreeUri(ctx, tree)?.name ?: tree.lastPathSegment ?: "?"
            val pair = SyncPair(treeUri = tree.toString(), localName = name, remoteFolderId = folder.id, remoteName = folder.name,
                provider = prov.id, wifiOnly = SyncPrefs.wifiOnly(ctx))
            SyncStore.add(ctx, pair)
            SyncWorker.schedule(ctx)
            SyncWorker.syncNow(ctx, pair)
            pickedTree = null; provider = null
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.ac_sync_remove)) },
            text = { Text(stringResource(R.string.ac_sync_remove_q)) },
            confirmButton = { TextButton(onClick = { SyncWorker.cancel(ctx, p.id); SyncStore.remove(ctx, p.id); SyncWorker.schedule(ctx); confirmDelete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showOptions) ModalBottomSheet(onDismissRequest = { showOptions = false }) {
        Column(Modifier.padding(bottom = 24.dp)) { SyncSettingsSection() }
    }
}

@Composable
private fun PairCard(p: SyncPair, queue: List<SyncQueue.QItem>, live: SyncProgress?, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val tiles = Brand.tiles
    val provider = remember(p.provider) { Clouds.provider(ctx, p.provider) }
    var showFiles by remember { mutableStateOf(false) }
    val running = live != null || p.status == SyncStatus.RUNNING
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Default.PhoneAndroid, tiles.blue, size = 36.dp, iconSize = 20.dp)
                Text(p.localName, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Icon(Icons.Default.SyncAlt, null, tint = MaterialTheme.colorScheme.primary)
                Text(p.remoteName, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                ProviderBadge(provider)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(if (live != null) SyncStatus.RUNNING else p.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (p.lastSync > 0) stringResource(R.string.ac_sync_last, DateUtils.getRelativeTimeSpanString(p.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
                    else stringResource(R.string.ac_sync_never),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (live != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(live.currentFile?.let { stringResource(R.string.s6_sync_current, it, live.done) } ?: stringResource(R.string.s6_sync_listing),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            } else if (p.status == SyncStatus.PENDING) {
                val note = when {
                    !SyncRuntime.networkOk(ctx, p.wifiOnly) -> stringResource(if (p.wifiOnly) R.string.s6_wait_wifi else R.string.s6_wait_network)
                    !SyncRuntime.batteryOk(ctx) -> stringResource(R.string.s6_wait_battery)
                    else -> null
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp)) }
            }
            if (!running && (p.uploaded + p.downloaded + p.conflicts) > 0)
                Text(stringResource(R.string.ac_sync_counts, p.uploaded, p.downloaded, p.conflicts), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            p.message?.takeIf { !running }?.let { m ->
                val text = when (m) {
                    SyncWorker.MSG_AUTH -> stringResource(R.string.ac_reconnect_needed)
                    SyncWorker.MSG_PERMISSION -> stringResource(R.string.ac_sync_perm_lost)
                    SyncWorker.MSG_FILES_FAILED -> pluralStringResourceCompat(R.plurals.s6_files_failed, p.failed)
                    SyncWorker.MSG_CONFIRM_DELETES -> pluralStringResourceCompat(R.plurals.fix7_deletes_withheld, p.pendingDeletes)
                    else -> m
                }
                Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            // DI-25: the mass-deletion guard withheld this pass's deletions; the user decides
            if (!running && p.status == SyncStatus.NEEDS_CONFIRMATION) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { SyncWorker.syncNow(ctx, p, com.smartdocs.editor.core.sync.DeleteGuard.Policy.RESTORE) }) { Text(stringResource(R.string.fix7_deletes_keep)) }
                TextButton(onClick = { SyncWorker.syncNow(ctx, p, com.smartdocs.editor.core.sync.DeleteGuard.Policy.ALLOW) }) {
                    Text(stringResource(R.string.fix7_deletes_allow), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(6.dp))
            SwitchLine(stringResource(R.string.ac_sync_wifi_only), p.wifiOnly) { v -> SyncStore.update(ctx, p.id) { it.copy(wifiOnly = v) }; SyncWorker.schedule(ctx) }
            SwitchLine(stringResource(R.string.ac_sync_auto), p.auto) { v -> SyncStore.update(ctx, p.id) { it.copy(auto = v) }; SyncWorker.schedule(ctx) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { SyncWorker.syncNow(ctx, p) }, enabled = !running && p.status != SyncStatus.PENDING) {
                    Icon(Icons.Default.Sync, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ac_sync_now))
                }
                Spacer(Modifier.width(6.dp))
                val failed = queue.count { it.state == SyncQueue.State.FAILED }
                TextButton(onClick = { showFiles = !showFiles }) {
                    Text(stringResource(R.string.s6_files_count, queue.size) + if (failed > 0) " · " + stringResource(R.string.s6_failed_count, failed) else "")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Default.LinkOff, stringResource(R.string.ac_sync_remove), tint = MaterialTheme.colorScheme.error) }
            }
            if (showFiles) QueueList(p, queue)
        }
    }
}

@Composable
private fun QueueList(p: SyncPair, queue: List<SyncQueue.QItem>) {
    val ctx = LocalContext.current
    if (queue.isEmpty()) { Text(stringResource(R.string.s6_queue_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    val failed = queue.count { it.state == SyncQueue.State.FAILED }
    if (failed > 1) TextButton(onClick = { SyncStore.retry(ctx, p.id, null); SyncWorker.request(ctx, p.id) }) {
        Icon(Icons.Default.Replay, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s6_retry_all))
    }
    val order = listOf(SyncQueue.State.RUNNING, SyncQueue.State.FAILED, SyncQueue.State.PENDING, SyncQueue.State.SYNCED)
    Column {
        queue.sortedWith(compareBy({ order.indexOf(it.state) }, { -it.updated })).take(200).forEach { q ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when (q.state) {
                    SyncQueue.State.PENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.tertiary
                    SyncQueue.State.RUNNING -> Icons.Default.Sync to MaterialTheme.colorScheme.primary
                    SyncQueue.State.SYNCED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                    SyncQueue.State.FAILED -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(q.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(opLabel(q.op) + (q.error?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall,
                        color = if (q.state == SyncQueue.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (q.state == SyncQueue.State.FAILED) TextButton(onClick = { SyncStore.retry(ctx, p.id, q.path); SyncWorker.syncNow(ctx, p) }) { Text(stringResource(R.string.ac_retry)) }
            }
        }
    }
}

@Composable
private fun opLabel(op: SyncQueue.Op): String = stringResource(when (op) {
    SyncQueue.Op.UPLOAD -> R.string.s6_op_upload
    SyncQueue.Op.DOWNLOAD -> R.string.s6_op_download
    SyncQueue.Op.CONFLICT -> R.string.s6_op_conflict
    SyncQueue.Op.DELETE_LOCAL -> R.string.s6_op_delete_local
    SyncQueue.Op.DELETE_REMOTE -> R.string.s6_op_delete_remote
})

@Composable
private fun SwitchLine(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange)
    }
}

@Composable
internal fun pluralStringResourceCompat(id: Int, count: Int): String = androidx.compose.ui.res.pluralStringResource(id, count, count)

@Composable
private fun StatusChip(s: SyncStatus) {
    val (label, color) = when (s) {
        SyncStatus.IDLE -> stringResource(R.string.ac_st_idle) to MaterialTheme.colorScheme.outline
        SyncStatus.PENDING -> stringResource(R.string.ac_st_pending) to MaterialTheme.colorScheme.tertiary
        SyncStatus.RUNNING -> stringResource(R.string.ac_st_running) to MaterialTheme.colorScheme.primary
        SyncStatus.SYNCED -> stringResource(R.string.ac_st_synced) to MaterialTheme.colorScheme.primary
        SyncStatus.FAILED -> stringResource(R.string.ac_st_failed) to MaterialTheme.colorScheme.error
        SyncStatus.NEEDS_CONFIRMATION -> stringResource(R.string.fix7_st_confirm) to MaterialTheme.colorScheme.error
    }
    Surface(shape = PillShape, color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s == SyncStatus.RUNNING || s == SyncStatus.PENDING) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color); Spacer(Modifier.width(6.dp)) }
            Text(label, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}
