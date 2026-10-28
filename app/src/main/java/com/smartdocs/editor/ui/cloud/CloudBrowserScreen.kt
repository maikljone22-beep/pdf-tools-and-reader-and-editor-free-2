package com.smartdocs.editor.ui.cloud

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.cloud.AuthRequiredException
import com.smartdocs.editor.core.cloud.CloudItem
import com.smartdocs.editor.core.cloud.CloudLinks
import com.smartdocs.editor.core.cloud.Clouds
import com.smartdocs.editor.core.cloud.ProviderId
import com.smartdocs.editor.core.cloud.SaveHooks
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Route "cloud_browser/{provider}": browse, search, open, upload, new folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudBrowserScreen(nav: NavHostController, providerKey: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = ProviderId.of(providerKey) ?: ProviderId.DRIVE
    val provider = remember(id) { Clouds.provider(ctx, id) }
    val tiles = Brand.tiles
    // folder stack: (id, name); null id = root
    val stack = remember { mutableStateListOf<Pair<String?, String>>(null to id.label) }
    var items by remember { mutableStateOf<List<CloudItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var authNeeded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Pair<String, Float?>?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val current = stack.last().first
    val uploadedMsg = stringResource(R.string.ac_uploaded)
    val downloadedMsg = stringResource(R.string.ac_downloaded_to)
    val busyOpen = stringResource(R.string.ac_downloading)
    val busyUpload = stringResource(R.string.ac_uploading)

    suspend fun <T> guard(block: suspend () -> T): T? = try { error = null; block() }
        catch (e: AuthRequiredException) { authNeeded = true; null }
        catch (e: Exception) { error = e.message ?: e.toString(); null }

    LaunchedEffect(current, reload, searching) {
        if (searching) return@LaunchedEffect
        loading = true
        items = guard { provider.list(current) } ?: items.takeIf { error == null }.orEmpty()
        loading = false
    }
    // upload results of reader saves
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { CloudLinks.resumePending(ctx) } // DI-24: dirty copies whose upload stopped

        SaveHooks.events.collect { e ->
            if (e is SaveHooks.Event.Uploaded && e.provider == id) { Toast.makeText(ctx, uploadedMsg, Toast.LENGTH_SHORT).show(); reload++ }
            if (e is SaveHooks.Event.UploadFailed && e.provider == id) Toast.makeText(ctx, ctx.getString(R.string.ac_upload_failed, e.error.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    val appSettings = com.smartdocs.editor.LocalSettings.current
    val policyMsg = stringResource(R.string.s6_policy_wifi_only)
    val upload = rememberPdfPicker { files ->
        val f = files.firstOrNull() ?: return@rememberPdfPicker
        // Settings → transfer network = Wi‑Fi only
        if (!com.smartdocs.editor.core.gaps6.TransferPolicy.allowed(ctx, appSettings)) { Toast.makeText(ctx, policyMsg, Toast.LENGTH_LONG).show(); return@rememberPdfPicker }
        scope.launch {
            progress = busyUpload to null
            guard { provider.upload(f, current) }?.let { Toast.makeText(ctx, uploadedMsg, Toast.LENGTH_SHORT).show(); reload++ }
            progress = null
        }
    }

    fun open(item: CloudItem) {
        if (item.isFolder) { stack += item.id to item.name; searching = false; query = ""; return }
        scope.launch {
            progress = busyOpen to 0f
            val opened = guard { CloudLinks.open(ctx, provider, item) { done, total -> if (total > 0) progress = busyOpen to (done.toFloat() / total) } }
            progress = null
            // DI-23: a copy with edits that are not uploaded yet was kept (not overwritten by the cloud version)
            when (opened?.action) {
                com.smartdocs.editor.core.cloud.CloudCopy.OpenAction.KEEP_LOCAL -> Toast.makeText(ctx, R.string.fix7_opened_local, Toast.LENGTH_LONG).show()
                com.smartdocs.editor.core.cloud.CloudCopy.OpenAction.CONFLICT -> Toast.makeText(ctx, R.string.fix7_opened_conflict, Toast.LENGTH_LONG).show()
                else -> Unit
            }
            val local = opened?.file
            if (local != null) {
                if (local.extension.equals("pdf", true)) nav.navigate(Routes.reader(Uri.fromFile(local).toString()))
                else Toast.makeText(ctx, downloadedMsg.format(local.parentFile?.name ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            if (searching) TopAppBar(
                navigationIcon = { IconButton(onClick = { searching = false; query = ""; reload++ }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    TextField(query, { query = it }, placeholder = { Text(stringResource(R.string.search)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                        trailingIcon = {
                            IconButton(onClick = { if (query.isNotBlank()) scope.launch { loading = true; items = guard { provider.search(query.trim()) } ?: emptyList(); loading = false } }) { Icon(Icons.Default.Search, stringResource(R.string.pa_cd_search)) }
                        })
                },
            ) else TopAppBar(
                navigationIcon = { IconButton(onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stack.last().second, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { searching = true; items = emptyList() }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.ac_refresh)) }
                    IconButton(onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.new_folder)) }
                },
            )
        },
        floatingActionButton = {
            if (!searching) ExtendedFloatingActionButton(onClick = upload, icon = { Icon(Icons.Default.Upload, null) }, text = { Text(stringResource(R.string.ac_upload)) })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (stack.size > 1 && !searching) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(stack.toList()) { i, (_, name) ->
                    AssistChip(onClick = { while (stack.size > i + 1) stack.removeAt(stack.lastIndex) }, label = { Text(name, maxLines = 1) })
                }
            }
            // fc-sync: Dropbox team space / OneDrive SharePoint libraries
            CloudScopeRow(provider, atRoot = stack.size == 1 && !searching, onRootChanged = { reload++ }) { fid, name -> stack += fid to name }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                authNeeded -> EmptyState(Icons.Default.Lock, stringResource(R.string.ac_reconnect_needed)) {
                    Button(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.ac_reconnect)) }
                }
                error != null && items.isEmpty() -> EmptyState(Icons.Default.CloudOff, error ?: "") {
                    OutlinedButton(onClick = { reload++ }) { Text(stringResource(R.string.ac_retry)) }
                }
                !loading && items.isEmpty() -> EmptyState(Icons.Default.FolderOpen, stringResource(if (searching) R.string.ds_no_results else R.string.ac_folder_empty))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp)) {
                    items(items, key = { it.id }) { item ->
                        ListItem(
                            modifier = Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp)).clickable { open(item) },
                            leadingContent = {
                                when {
                                    item.isFolder -> IconTile(Icons.Default.Folder, tiles.amber, size = 40.dp, iconSize = 22.dp)
                                    Clouds.isPdf(item) || item.mime?.startsWith("application/vnd.google-apps.") == true -> IconTile(Icons.Default.PictureAsPdf, tiles.rose, size = 40.dp, iconSize = 22.dp)
                                    else -> IconTile(Icons.Default.InsertDriveFile, tiles.blue, size = 40.dp, iconSize = 22.dp)
                                }
                            },
                            headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                if (!item.isFolder) Text(listOfNotNull(
                                    item.size.takeIf { it > 0 }?.let { DocumentRepository.formatSize(it) },
                                    item.modified.takeIf { it > 0 }?.let { DocumentRepository.formatDate(it) },
                                ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            },
                            trailingContent = {
                                if (item.isFolder) Icon(Icons.Default.ChevronLeft, null)
                                else if (provider.supportsLinks) LinkMenu(provider, item) { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            tonalElevation = 0.dp,
                        )
                    }
                }
            }
        }
    }

    progress?.let { (msg, p) -> ProgressDialog(msg, p) }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text(stringResource(R.string.new_folder)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, shape = RoundedCornerShape(16.dp)) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    newFolder = false
                    scope.launch { guard { withContext(Dispatchers.IO) { provider.createFolder(current, name.trim()) } }?.let { reload++ } }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** share6: "copy link" / "share link" for providers that can make shareable links. */
@Composable
private fun LinkMenu(provider: com.smartdocs.editor.core.cloud.CloudProvider, item: CloudItem, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.s6_link_copied)
    val failed = stringResource(R.string.s6_link_failed)
    fun make(share: Boolean, anyone: Boolean) {
        open = false; busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { provider.shareLink(item, anyone) } }
                .onSuccess { link ->
                    if (share) ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, link).putExtra(android.content.Intent.EXTRA_SUBJECT, item.name), null))
                    else {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText(item.name, link)); toast(copied)
                    }
                }
                .onFailure { toast(failed + ": " + (it.message ?: "")) }
            busy = false
        }
    }
    Box {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.s6_link_menu)) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.s6_copy_link)) }, leadingIcon = { Icon(Icons.Default.Link, null) }, onClick = { make(share = false, anyone = true) })
            DropdownMenuItem(text = { Text(stringResource(R.string.s6_share_link)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { make(share = true, anyone = true) })
            DropdownMenuItem(text = { Text(stringResource(R.string.s6_copy_private_link)) }, leadingIcon = { Icon(Icons.Default.Lock, null) }, onClick = { make(share = false, anyone = false) })
        }
    }
}
