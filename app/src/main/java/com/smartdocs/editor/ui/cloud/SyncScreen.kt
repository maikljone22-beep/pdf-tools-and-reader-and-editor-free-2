package com.smartdocs.editor.ui.cloud

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import com.smartdocs.editor.core.cloud.AuthRequiredException
import com.smartdocs.editor.core.cloud.CloudItem
import com.smartdocs.editor.core.cloud.GoogleDrive
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.sync.SyncPair
import com.smartdocs.editor.core.sync.SyncStatus
import com.smartdocs.editor.core.sync.SyncStore
import com.smartdocs.editor.core.sync.SyncWorker
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.IconTile
import kotlinx.coroutines.launch

/** Route "sync": local folder ↔ Google Drive folder pairs with status, "sync now" and options. */
@Composable
fun SyncScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val pairs by SyncStore.pairs.collectAsStateWithLifecycle()
    val drive = remember { GoogleDrive.get(ctx) }
    var pickedTree by remember { mutableStateOf<Uri?>(null) }
    var confirmDelete by remember { mutableStateOf<SyncPair?>(null) }
    LaunchedEffect(Unit) { SyncStore.load(ctx) }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            pickedTree = uri
        }
    }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.ac_sync_title), onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            if (drive.isConnected()) ExtendedFloatingActionButton(onClick = { pickTree.launch(null) }, icon = { Icon(Icons.Default.CreateNewFolder, null) }, text = { Text(stringResource(R.string.ac_sync_add)) })
        },
    ) { pad ->
        when {
            !drive.isConnected() -> EmptyState(Icons.Default.AddToDrive, stringResource(R.string.ac_sync_need_drive), Modifier.padding(pad)) {
                Button(onClick = { nav.navigate(Routes.CLOUD) }, shape = PillShape) { Text(stringResource(R.string.ac_connect_drive)) }
            }
            pairs.isEmpty() -> EmptyState(Icons.Default.Sync, stringResource(R.string.ac_sync_empty), Modifier.padding(pad))
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.ac_sync_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(pairs, key = { it.id }) { p -> PairCard(p, onDelete = { confirmDelete = p }) }
            }
        }
    }

    pickedTree?.let { tree ->
        DriveFolderPicker(drive, onDismiss = { pickedTree = null }) { folder ->
            val name = DocumentFile.fromTreeUri(ctx, tree)?.name ?: tree.lastPathSegment ?: "?"
            val pair = SyncPair(treeUri = tree.toString(), localName = name, remoteFolderId = folder.id, remoteName = folder.name)
            SyncStore.add(ctx, pair)
            SyncWorker.schedule(ctx)
            SyncWorker.syncNow(ctx, pair)
            pickedTree = null
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
}

@Composable
private fun PairCard(p: SyncPair, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val tiles = Brand.tiles
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Default.PhoneAndroid, tiles.blue, size = 36.dp, iconSize = 20.dp)
                Text(p.localName, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Icon(Icons.Default.SyncAlt, null, tint = MaterialTheme.colorScheme.primary)
                Text(p.remoteName, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                IconTile(Icons.Default.AddToDrive, tiles.green, size = 36.dp, iconSize = 20.dp)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(p.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (p.lastSync > 0) stringResource(R.string.ac_sync_last, DocumentRepository.formatDate(p.lastSync)) else stringResource(R.string.ac_sync_never),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (p.status == SyncStatus.SYNCED && (p.uploaded + p.downloaded + p.conflicts) > 0)
                Text(stringResource(R.string.ac_sync_counts, p.uploaded, p.downloaded, p.conflicts), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            p.message?.let { m ->
                val text = when (m) { SyncWorker.MSG_AUTH -> stringResource(R.string.ac_reconnect_needed); SyncWorker.MSG_PERMISSION -> stringResource(R.string.ac_sync_perm_lost); else -> m }
                Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().clickable {
                SyncStore.update(ctx, p.id) { it.copy(wifiOnly = !it.wifiOnly) }; SyncWorker.schedule(ctx)
            }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ac_sync_wifi_only), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(p.wifiOnly, { v -> SyncStore.update(ctx, p.id) { it.copy(wifiOnly = v) }; SyncWorker.schedule(ctx) })
            }
            Row(Modifier.fillMaxWidth().clickable {
                SyncStore.update(ctx, p.id) { it.copy(auto = !it.auto) }; SyncWorker.schedule(ctx)
            }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ac_sync_auto), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(p.auto, { v -> SyncStore.update(ctx, p.id) { it.copy(auto = v) }; SyncWorker.schedule(ctx) })
            }
            Row {
                FilledTonalButton(onClick = { SyncWorker.syncNow(ctx, p) }, enabled = p.status != SyncStatus.RUNNING && p.status != SyncStatus.PENDING) {
                    Icon(Icons.Default.Sync, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ac_sync_now))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text(stringResource(R.string.ac_sync_remove), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun StatusChip(s: SyncStatus) {
    val (label, color) = when (s) {
        SyncStatus.IDLE -> stringResource(R.string.ac_st_idle) to MaterialTheme.colorScheme.outline
        SyncStatus.PENDING -> stringResource(R.string.ac_st_pending) to MaterialTheme.colorScheme.tertiary
        SyncStatus.RUNNING -> stringResource(R.string.ac_st_running) to MaterialTheme.colorScheme.primary
        SyncStatus.SYNCED -> stringResource(R.string.ac_st_synced) to MaterialTheme.colorScheme.primary
        SyncStatus.FAILED -> stringResource(R.string.ac_st_failed) to MaterialTheme.colorScheme.error
    }
    Surface(shape = PillShape, color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s == SyncStatus.RUNNING || s == SyncStatus.PENDING) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color); Spacer(Modifier.width(6.dp)) }
            Text(label, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Dialog to navigate Google Drive folders and choose one (or create one). */
@Composable
fun DriveFolderPicker(drive: GoogleDrive, onDismiss: () -> Unit, onPick: (CloudItem) -> Unit) {
    val scope = rememberCoroutineScope()
    val rootName = stringResource(R.string.ac_my_drive)
    val stack = remember { mutableStateListOf(CloudItem(drive.rootId, rootName, true)) }
    var folders by remember { mutableStateOf<List<CloudItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val authMsg = stringResource(R.string.ac_reconnect_needed)
    val cur = stack.last()
    LaunchedEffect(cur.id) {
        loading = true; error = null
        folders = try { drive.list(cur.id).filter { it.isFolder } } catch (e: AuthRequiredException) { error = authMsg; emptyList() } catch (e: Exception) { error = e.message; emptyList() }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ac_pick_drive_folder)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Text(stack.joinToString(" › ") { it.name }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (stack.size > 1) item {
                        ListItem(headlineContent = { Text("..") }, leadingContent = { Icon(Icons.Default.ArrowUpward, null) }, modifier = Modifier.clickable { stack.removeAt(stack.lastIndex) })
                    }
                    items(folders, key = { it.id }) { f ->
                        ListItem(headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(Icons.Default.Folder, null) }, modifier = Modifier.clickable { stack += f })
                    }
                }
                if (creating) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.new_folder)) })
                    IconButton(enabled = newName.isNotBlank(), onClick = {
                        scope.launch {
                            runCatching { drive.createFolder(cur.id, newName.trim()) }.onSuccess { stack += it; creating = false; newName = "" }.onFailure { error = it.message }
                        }
                    }) { Icon(Icons.Default.Check, null) }
                } else TextButton(onClick = { creating = true }) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.new_folder)) }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(cur) }, enabled = !loading && error == null) { Text(stringResource(R.string.ac_choose_folder)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
