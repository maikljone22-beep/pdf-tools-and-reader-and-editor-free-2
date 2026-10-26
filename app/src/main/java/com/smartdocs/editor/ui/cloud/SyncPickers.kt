package com.smartdocs.editor.ui.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.smartdocs.editor.R
import com.smartdocs.editor.core.cloud.AuthRequiredException
import com.smartdocs.editor.core.cloud.CloudItem
import com.smartdocs.editor.core.cloud.CloudProvider
import com.smartdocs.editor.core.cloud.Clouds
import com.smartdocs.editor.core.cloud.GoogleDrive
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.ui.common.IconTile
import kotlinx.coroutines.launch

/** Step 1 of "add sync folder": which connected cloud. */
@Composable
fun SyncProviderChooser(providers: List<CloudProvider>, onDismiss: () -> Unit, onPick: (CloudProvider) -> Unit) {
    val tiles = Brand.tiles
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.s6_pick_cloud)) },
        text = {
            Column {
                providers.forEach { p ->
                    val (icon, tile) = providerLook(p.id, tiles)
                    ListItem(
                        headlineContent = { Text(p.id.label) },
                        supportingContent = { Clouds.accountOf(p)?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        leadingContent = { IconTile(icon, tile, size = 36.dp, iconSize = 20.dp) },
                        modifier = Modifier.clickable { onPick(p) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Dialog to navigate the folders of any [CloudProvider] and choose one (or create one). */
@Composable
fun CloudFolderPicker(provider: CloudProvider, onDismiss: () -> Unit, onPick: (CloudItem) -> Unit) {
    val scope = rememberCoroutineScope()
    val rootName = provider.id.label
    val stack = remember(provider.id) { mutableStateListOf(CloudItem(provider.rootId, rootName, true)) }
    var folders by remember { mutableStateOf<List<CloudItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val authMsg = stringResource(R.string.ac_reconnect_needed)
    val cur = stack.last()
    LaunchedEffect(cur.id) {
        loading = true; error = null
        folders = try { provider.list(cur.id).filter { it.isFolder } } catch (e: AuthRequiredException) { error = authMsg; emptyList() } catch (e: Exception) { error = e.message; emptyList() }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.s6_pick_cloud_folder, provider.id.label)) },
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
                            runCatching { provider.createFolder(cur.id, newName.trim()) }.onSuccess { stack += it; creating = false; newName = "" }.onFailure { error = it.message }
                        }
                    }) { Icon(Icons.Default.Check, null) }
                } else TextButton(onClick = { creating = true }) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.new_folder)) }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(cur) }, enabled = !loading && error == null) { Text(stringResource(R.string.ac_choose_folder)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Kept for callers of the Drive-only picker (MODULE_acct). */
@Composable
fun DriveFolderPicker(drive: GoogleDrive, onDismiss: () -> Unit, onPick: (CloudItem) -> Unit) = CloudFolderPicker(drive, onDismiss, onPick)

/** Small "Drive / OneDrive / Dropbox / Box" badge. */
@Composable
fun ProviderBadge(provider: CloudProvider) {
    val (icon, tile) = providerLook(provider.id, Brand.tiles)
    IconTile(icon, tile, size = 36.dp, iconSize = 20.dp, contentDescription = provider.id.label)
}

@Composable
internal fun rememberConnectedProviders(version: Int): List<CloudProvider> {
    val ctx = LocalContext.current
    return remember(version) { Clouds.all(ctx).filter { it.isConfigured() && it.isConnected() } }
}
