package com.smartdocs.editor.ui.connected

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.connected.ConnectedConfig
import com.smartdocs.editor.core.connected.ConnectedDocs
import com.smartdocs.editor.core.connected.ConnectedStatus
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.ProgressDialog
import kotlinx.coroutines.launch
import java.io.File

/** Route "connected/open?id=&uri=": reader side — newer version, access request, revoked / offline notices. */
@Composable
fun ConnectedOpenScreen(nav: NavHostController, id: String, uri: String?) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val file = remember(uri) { uri?.let { Uri.parse(it).path }?.let(::File)?.takeIf { it.exists() } }
    var st by remember { mutableStateOf<ConnectedStatus?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var requested by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(id, reload) {
        val prot = file?.let { ConnectedDocs.markOf(it)?.protected } ?: false
        st = ConnectedDocs.statusById(ctx, id, prot, file)
    }

    Scaffold(topBar = { AppTopBar(st?.title?.ifBlank { null } ?: stringResource(R.string.cn_connected_doc), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val s = st
            if (s == null) { CircularProgressIndicator(); return@Column }
            val (icon, text) = when {
                s.status == "revoked" -> Icons.Default.Block to stringResource(R.string.cn_open_revoked)
                s.status == "blocked" -> Icons.Default.Block to stringResource(R.string.cn_open_blocked)
                s.status == "pending" -> Icons.Default.HourglassTop to stringResource(R.string.cn_open_pending)
                s.status == "gone" -> Icons.Default.SearchOff to stringResource(R.string.cn_open_gone)
                s.offline && s.password != null -> Icons.Default.CloudOff to stringResource(R.string.cn_open_offline_ok, DocumentRepository.formatDate(s.offlineUntil))
                s.offline -> Icons.Default.CloudOff to stringResource(if (s.protected) R.string.cn_open_offline_locked else R.string.cn_open_offline)
                s.newer != null -> Icons.Default.NewReleases to stringResource(R.string.cn_open_newer, s.newer.n)
                else -> Icons.Default.VerifiedUser to stringResource(R.string.cn_open_ok)
            }
            Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (s.owner.isNotBlank()) Text(stringResource(R.string.cn_owner_is, s.owner), style = MaterialTheme.typography.bodySmall)
            // fc-sync: the owner's open message
            if (s.message.isNotBlank()) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Campaign, null); Spacer(Modifier.width(8.dp)); Text(s.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            s.newer?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (s.newer != null && s.status == "ok" && s.newer.hasFile) Button(onClick = { scope.launch {
                busy = ctx.getString(R.string.cn_downloading)
                runCatching { ConnectedDocs.downloadLatest(ctx, id, app.documents.docsDir, s.title) }
                    .onSuccess { nav.popBackStack(); nav.navigate(Routes.reader(Uri.fromFile(it).toString())) }
                    .onFailure { error = it.message }
                busy = null
            } }, shape = PillShape, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.cn_download_latest)) }
            if (s.status == "ok" && s.newer == null && file != null && s.isOwner.not() && !s.locked)
                Button(onClick = { nav.popBackStack(); nav.navigate(Routes.reader(Uri.fromFile(file).toString())) }, shape = PillShape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cn_open)) }
            if ((s.status == "pending" || s.status == "blocked") && ConnectedConfig.enabled) {
                if (requested) Text(stringResource(R.string.cn_request_sent), color = MaterialTheme.colorScheme.primary)
                else {
                    OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.cn_request_msg)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    Button(onClick = { scope.launch {
                        runCatching { ConnectedDocs.requestAccess(ctx, id, message.trim()) }.onSuccess { requested = true }.onFailure { error = it.message }
                    } }, shape = PillShape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cn_request_access)) }
                }
            }
            if (s.offline || s.status != "ok") OutlinedButton(onClick = { reload++ }, shape = PillShape) { Text(stringResource(R.string.cn_retry)) }
            Text(stringResource(R.string.cn_reader_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
    busy?.let { ProgressDialog(it) }
}

/** Banner above the pages in the reader. Shows nothing when there is nothing to say. */
@Composable
fun ConnectedBanner(status: ConnectedStatus, nav: NavHostController, fileUri: String?) {
    if (!status.noteworthy || status.isOwner) return
    val text = when {
        status.status == "revoked" -> stringResource(R.string.cn_banner_revoked)
        status.status == "blocked" -> stringResource(R.string.cn_banner_blocked)
        status.status == "pending" -> stringResource(R.string.cn_banner_pending)
        status.status == "gone" -> stringResource(R.string.cn_banner_gone)
        status.offline -> stringResource(R.string.cn_banner_offline)
        status.newer != null -> stringResource(R.string.cn_banner_newer, status.newer.n)
        status.message.isNotBlank() -> status.message   // fc-sync: the owner's open message
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { nav.navigate(ConnRoutes.connectedOpen(status.docId, fileUri)) }) { Text(stringResource(R.string.cn_details)) }
        }
    }
}
