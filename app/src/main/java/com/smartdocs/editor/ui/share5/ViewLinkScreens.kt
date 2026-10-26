package com.smartdocs.editor.ui.share5

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.share5.LinkInfo
import com.smartdocs.editor.core.share5.LinkRules
import com.smartdocs.editor.core.share5.S5Client
import com.smartdocs.editor.core.share5.S5Config
import com.smartdocs.editor.core.share5.ViewLinkRef
import com.smartdocs.editor.core.share5.ViewLinkStore
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun linkStatusLabel(expires: Long, disabled: Boolean): Pair<String, androidx.compose.ui.graphics.Color> {
    val now = System.currentTimeMillis()
    return when (LinkRules.status(expires, disabled, now)) {
        LinkRules.Status.DISABLED -> stringResource(R.string.sh5_link_paused) to MaterialTheme.colorScheme.outline
        LinkRules.Status.EXPIRED -> stringResource(R.string.sh5_link_expired) to MaterialTheme.colorScheme.error
        LinkRules.Status.ACTIVE -> (if (expires > 0) stringResource(R.string.sh5_link_days_left, LinkRules.daysLeft(expires, now)) else stringResource(R.string.sh5_link_active)) to MaterialTheme.colorScheme.primary
    }
}

/** Route "share5/views": view-only links created on this device. */
@Composable
fun ViewLinksScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val links by ViewLinkStore.links.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { ViewLinkStore.load(ctx) }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.sh5_views), onBack = { nav.popBackStack() }) },
        floatingActionButton = { if (S5Config.enabled) ExtendedFloatingActionButton(onClick = { nav.navigate(Share5Routes.viewNew(null)) }, icon = { Icon(Icons.Default.AddLink, null) }, text = { Text(stringResource(R.string.sh5_new)) }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!S5Config.enabled) NotConfiguredNote(Modifier.padding(16.dp))
            if (links.isEmpty()) EmptyState(Icons.Default.Link, stringResource(R.string.sh5_views_empty))
            else LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(links, key = { it.id }) { l ->
                    Surface(onClick = { nav.navigate(Share5Routes.view(l.id)) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(if (l.hasPassword) Icons.Default.Lock else Icons.Default.Link, Brand.tiles.amber, size = 42.dp, iconSize = 22.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(l.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(S5Share.date(l.created), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val (label, color) = linkStatusLabel(l.expires, l.disabled)
                            StatusPill(label, color)
                        }
                    }
                }
            }
        }
    }
}

/** Route "share5/view_new?uri=": expiry, password, download toggle (O2). */
@Composable
fun ViewLinkCreateScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(uri?.let { Uri.parse(it).path }?.let(::File)) }
    var title by remember { mutableStateOf(file?.nameWithoutExtension.orEmpty()) }
    var expires by remember { mutableLongStateOf(LinkRules.expiryFromPreset(7, System.currentTimeMillis())) }
    var password by remember { mutableStateOf("") }
    var allowDownload by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberPdfPicker { f -> file = f.firstOrNull(); if (title.isBlank()) title = file?.nameWithoutExtension.orEmpty() }
    val pwBad = !LinkRules.passwordOk(password)
    Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_view_new), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!S5Config.enabled) NotConfiguredNote()
            FilePickRow(file, stringResource(R.string.sh5_pick_pdf), pick)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sh5_link_title)) }, singleLine = true)
            Text(stringResource(R.string.sh5_expiry), style = MaterialTheme.typography.titleSmall)
            DateChooser(expires, { expires = it }, LinkRules.PRESET_DAYS.filter { it > 0 }, stringResource(R.string.sh5_never))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sh5_password_opt)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = pwBad,
                supportingText = { Text(stringResource(R.string.sh5_password_rule)) })
            SwitchRow(stringResource(R.string.sh5_allow_download), stringResource(R.string.sh5_allow_download_sub), allowDownload, true) { allowDownload = it }
            Text(stringResource(R.string.sh5_view_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = S5Config.enabled && file != null && !pwBad && !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = PillShape, onClick = {
                val f = file ?: return@Button
                scope.launch {
                    busy = true; error = null
                    runCatching { withContext(Dispatchers.IO) { S5Client.createLink(f, title.trim().ifBlank { f.nameWithoutExtension }, expires, password, allowDownload) } }
                        .onSuccess { c ->
                            ViewLinkStore.upsert(ctx, ViewLinkRef(c.id, c.ownerKey, c.url, title.trim().ifBlank { f.nameWithoutExtension }, f.name, System.currentTimeMillis(), expires, password.isNotEmpty(), allowDownload))
                            S5Share.copy(ctx, c.url)
                            nav.popBackStack(); nav.navigate(Share5Routes.view(c.id))
                        }
                        .onFailure { error = it.message ?: it.toString() }
                    busy = false
                }
            }) { Icon(Icons.Default.AddLink, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sh5_create_link)) }
        }
    }
    if (busy) ProgressDialog(stringResource(R.string.sh5_uploading))
}

/** Route "share5/view/{id}": analytics + management of one link. */
@Composable
fun ViewLinkDetailScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ref = remember(id) { ViewLinkStore.get(ctx, id) }
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var editExpiry by remember { mutableStateOf(false) }
    var editPw by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        val r = ref ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { S5Client.link(r.id, r.ownerKey) } }
            .onSuccess { i -> info = i; error = null; ViewLinkStore.upsert(ctx, r.copy(title = i.title, expires = i.expires, hasPassword = i.hasPassword, allowDownload = i.allowDownload, disabled = i.disabled)) }
            .onFailure { error = if (S5Client.isGone(it)) ctx.getString(R.string.sh5_link_gone) else it.message }
    }
    if (ref == null) { Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_views), onBack = { nav.popBackStack() }) }) { pad -> EmptyState(Icons.Default.LinkOff, stringResource(R.string.sh5_link_gone), Modifier.padding(pad)) }; return }
    fun patch(block: () -> Unit) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { reload++ }.onFailure { Toast.makeText(ctx, it.message ?: it.toString(), Toast.LENGTH_LONG).show() }
    }
    val i = info
    Scaffold(topBar = { AppTopBar(ref.title, onBack = { nav.popBackStack() }) { IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.sh5_refresh)) } } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    val (label, color) = linkStatusLabel(i?.expires ?: ref.expires, i?.disabled ?: ref.disabled)
                    StatusPill(label, color)
                    Spacer(Modifier.height(8.dp))
                    Text(ref.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    InviteActions(ctx.getString(R.string.sh5_link_share_text, ref.title, ref.url), ref.url, ref.title)
                    TextButton(onClick = { S5Share.openUrl(ctx, ref.url) }) { Icon(Icons.Default.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sh5_open_browser)) }
                }
            }
            if (i != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox(stringResource(R.string.sh5_views_count), i.stats.views, Modifier.weight(1f))
                    StatBox(stringResource(R.string.sh5_unique), i.stats.unique, Modifier.weight(1f))
                    StatBox(stringResource(R.string.sh5_downloads), i.stats.downloads, Modifier.weight(1f))
                }
                if (i.stats.days.isNotEmpty()) {
                    Text(stringResource(R.string.sh5_by_day), style = MaterialTheme.typography.titleSmall)
                    val max = i.stats.days.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                    i.stats.days.toSortedMap().entries.reversed().take(14).forEach { (d, n) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(88.dp))
                            Box(Modifier.weight(1f).height(14.dp)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(n.toFloat() / max).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
                            }
                            Text(" $n", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (i.stats.last > 0) Text(stringResource(R.string.sh5_last_view, S5Share.date(i.stats.last)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.sh5_settings), style = MaterialTheme.typography.titleSmall)
                ListItem(headlineContent = { Text(stringResource(R.string.sh5_expiry)) }, supportingContent = { Text(if (i.expires > 0) S5Share.date(i.expires) else stringResource(R.string.sh5_never)) },
                    trailingContent = { TextButton(onClick = { editExpiry = true }) { Text(stringResource(R.string.sh5_change)) } })
                ListItem(headlineContent = { Text(stringResource(R.string.sh5_password)) }, supportingContent = { Text(stringResource(if (i.hasPassword) R.string.sh5_pw_on else R.string.sh5_pw_off)) },
                    trailingContent = { TextButton(onClick = { editPw = true }) { Text(stringResource(R.string.sh5_change)) } })
                SwitchRow(stringResource(R.string.sh5_allow_download), null, i.allowDownload, true) { v -> patch { S5Client.patchLink(ref.id, ref.ownerKey, allowDownload = v) } }
                SwitchRow(stringResource(R.string.sh5_pause_link), stringResource(R.string.sh5_pause_link_sub), i.disabled, true) { v -> patch { S5Client.patchLink(ref.id, ref.ownerKey, disabled = v) } }
            }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(), shape = PillShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sh5_delete_link))
            }
        }
    }
    if (editExpiry) {
        var e by remember { mutableLongStateOf(info?.expires ?: ref.expires) }
        AlertDialog(onDismissRequest = { editExpiry = false }, title = { Text(stringResource(R.string.sh5_expiry)) },
            text = { DateChooser(e, { e = it }, LinkRules.PRESET_DAYS.filter { it > 0 }, stringResource(R.string.sh5_never)) },
            confirmButton = { TextButton(onClick = { editExpiry = false; patch { S5Client.patchLink(ref.id, ref.ownerKey, expires = e) } }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { editExpiry = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (editPw) {
        var p by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { editPw = false }, title = { Text(stringResource(R.string.sh5_password)) },
            text = { OutlinedTextField(p, { p = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), isError = !LinkRules.passwordOk(p),
                supportingText = { Text(stringResource(R.string.sh5_password_empty_removes)) }) },
            confirmButton = { TextButton(enabled = LinkRules.passwordOk(p), onClick = { editPw = false; patch { S5Client.patchLink(ref.id, ref.ownerKey, password = p) } }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { editPw = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.sh5_delete_link)) }, text = { Text(stringResource(R.string.sh5_delete_link_q)) },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            scope.launch {
                val r = runCatching { withContext(Dispatchers.IO) { S5Client.deleteLink(ref.id, ref.ownerKey) } }
                if (r.isSuccess || S5Client.isGone(r.exceptionOrNull() ?: Exception())) { ViewLinkStore.remove(ctx, ref.id); nav.popBackStack() }
                else Toast.makeText(ctx, r.exceptionOrNull()?.message ?: "", Toast.LENGTH_LONG).show()
            }
        }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun StatBox(label: String, value: Int, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), modifier = modifier) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
