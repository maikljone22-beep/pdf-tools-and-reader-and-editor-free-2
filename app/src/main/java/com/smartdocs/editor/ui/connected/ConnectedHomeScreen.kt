package com.smartdocs.editor.ui.connected

import android.net.Uri
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.connected.ConnStore
import com.smartdocs.editor.core.connected.ConnectedConfig
import com.smartdocs.editor.core.connected.ConnectedDocs
import com.smartdocs.editor.core.connected.ConnectedStamp
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.esign.NoteCard
import com.smartdocs.editor.ui.esign.NotConfigured
import kotlinx.coroutines.launch
import java.io.File

/** Route "connected?uri=": documents I registered; with [uri] the register sheet opens for that file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedHomeScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val owned by ConnStore.owned.collectAsStateWithLifecycle()
    var register by remember { mutableStateOf(uri != null) }
    LaunchedEffect(Unit) { ConnStore.load(ctx) }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.cn_connected_title), onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            if (ConnectedConfig.enabled) ExtendedFloatingActionButton(onClick = { register = true }, icon = { Icon(Icons.Default.Link, null) }, text = { Text(stringResource(R.string.cn_register)) })
        },
    ) { pad ->
        if (!ConnectedConfig.enabled) { NotConfigured(Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(stringResource(R.string.cn_connected_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (owned.isEmpty()) item { EmptyState(Icons.Default.Link, stringResource(R.string.cn_connected_empty)) }
            items(owned.sortedByDescending { it.created }, key = { it.id }) { d ->
                Surface(onClick = { nav.navigate(ConnRoutes.connectedDoc(d.id)) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(if (d.protected) Icons.Default.Lock else Icons.Default.Link, if (d.protected) Brand.tiles.rose else Brand.tiles.blue, size = 42.dp, iconSize = 22.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.cn_version_n, d.lastVersion) + " · " + DocumentRepository.formatDate(d.created),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronLeft, null)
                    }
                }
            }
        }
    }
    if (register) RegisterSheet(uri?.let { Uri.parse(it).path }?.let(::File)?.takeIf { it.exists() }, onDismiss = { register = false }) { id -> register = false; nav.navigate(ConnRoutes.connectedDoc(id)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegisterSheet(initial: File?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(initial) }
    var title by remember { mutableStateOf(initial?.nameWithoutExtension.orEmpty()) }
    var protected by remember { mutableStateOf(false) }
    var approval by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf(true) }
    var days by remember { mutableStateOf("7") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val encrypted = stringResource(R.string.cn_encrypted)
    val pick = rememberPdfPicker { l -> l.firstOrNull()?.let { file = it; if (title.isBlank()) title = it.nameWithoutExtension } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.cn_register), style = MaterialTheme.typography.titleMedium)
            FilePickRow(file, stringResource(R.string.cn_document), pick)
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.cn_req_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchRow(stringResource(R.string.cn_protected), stringResource(R.string.cn_protected_sub), protected) { protected = it }
            SwitchRow(stringResource(R.string.cn_require_approval), stringResource(R.string.cn_require_approval_sub), approval) { approval = it }
            SwitchRow(stringResource(R.string.cn_upload_versions), stringResource(R.string.cn_upload_versions_sub), upload) { upload = it }
            if (protected) OutlinedTextField(days, { days = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.cn_offline_days)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            NoteCard(stringResource(R.string.cn_register_note))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(enabled = file != null && title.isNotBlank() && !busy, shape = PillShape, modifier = Modifier.fillMaxWidth(), onClick = {
                val f = file ?: return@Button
                scope.launch {
                    busy = true; error = null
                    runCatching { ConnectedDocs.register(ctx, f, app.documents.docsDir, title.trim(), settings.authorName, protected, approval, days.toIntOrNull() ?: 7, upload) }
                        .onSuccess { onDone(it.id) }
                        .onFailure { error = if (it is ConnectedStamp.EncryptedSource) encrypted else (it.message ?: it.toString()) }
                    busy = false
                }
            }) { Text(stringResource(R.string.cn_register)) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (busy) ProgressDialog(stringResource(R.string.cn_registering))
}

@Composable
fun SwitchRow(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, onChange)
    }
}
