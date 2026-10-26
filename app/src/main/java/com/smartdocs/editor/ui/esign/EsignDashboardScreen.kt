package com.smartdocs.editor.ui.esign

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
import com.smartdocs.editor.R
import com.smartdocs.editor.core.esign.EsignClient
import com.smartdocs.editor.core.esign.EsignConfig
import com.smartdocs.editor.core.esign.EsignRef
import com.smartdocs.editor.core.esign.EsignStore
import com.smartdocs.editor.core.esign.RequestStatus
import com.smartdocs.editor.core.esign.SignerStatus
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.connected.ConnDeepLinks
import com.smartdocs.editor.ui.connected.ConnRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Route "esign": requests I sent and requests I was asked to sign. */
@Composable
fun EsignDashboardScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by EsignStore.items.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { EsignStore.load(ctx) }

    fun refresh() = scope.launch {
        refreshing = true
        withContext(Dispatchers.IO) {
            EsignStore.load(ctx).forEach { r ->
                runCatching {
                    if (r.sent) {
                        val q = EsignClient.get(r.id, r.secret)
                        val signed = q.signers.count { it.status == SignerStatus.SIGNED }
                        EsignStore.update(ctx, r.id, true) { it.copy(status = q.status.name.lowercase(), lastCheck = System.currentTimeMillis(), counts = "$signed/${q.signers.size}") }
                    } else {
                        val v = EsignClient.signerView(r.id, r.secret)
                        val st = v.request.signers.getOrNull(v.me)?.status?.name?.lowercase() ?: "out"
                        EsignStore.update(ctx, r.id, false) { it.copy(status = if (v.canSign) "todo" else st, lastCheck = System.currentTimeMillis()) }
                    }
                }.onFailure { e -> if (EsignClient.isGone(e)) EsignStore.update(ctx, r.id, r.sent) { it.copy(status = "gone") } }
            }
        }
        refreshing = false
    }
    LaunchedEffect(Unit) { if (EsignConfig.enabled) refresh() }

    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.cn_esign_title), onBack = { nav.popBackStack() }) {
                IconButton(onClick = { paste = true }) { Icon(Icons.Default.Link, stringResource(R.string.cn_open_link)) }
                IconButton(onClick = { refresh() }, enabled = !refreshing) { Icon(Icons.Default.Refresh, stringResource(R.string.cn_refresh)) }
            }
        },
        floatingActionButton = {
            if (EsignConfig.enabled) ExtendedFloatingActionButton(onClick = { nav.navigate(ConnRoutes.esignCreate()) },
                icon = { Icon(Icons.Default.HistoryEdu, null) }, text = { Text(stringResource(R.string.cn_new_request)) })
        },
    ) { pad ->
        if (!EsignConfig.enabled) { NotConfigured(Modifier.padding(pad)); return@Scaffold }
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.cn_tab_sent)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.cn_tab_received)) })
            }
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            val list = items.filter { it.sent == (tab == 0) }.sortedByDescending { it.created }
            if (list.isEmpty()) EmptyState(Icons.Default.Draw, stringResource(if (tab == 0) R.string.cn_empty_sent else R.string.cn_empty_received))
            else LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { (if (it.sent) "s" else "r") + it.id }) { r -> RefCard(r) {
                    if (r.sent) nav.navigate(ConnRoutes.esignDetail(r.id)) else nav.navigate(ConnRoutes.esignSign(r.id, r.secret))
                } }
            }
        }
    }

    if (paste) {
        var text by remember { mutableStateOf("") }
        val bad = stringResource(R.string.cn_bad_link)
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { paste = false },
            title = { Text(stringResource(R.string.cn_open_link)) },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.cn_paste_link)) }, singleLine = true, shape = RoundedCornerShape(16.dp))
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = {
                val p = ConnDeepLinks.parseSignLink(text)
                if (p == null) err = bad else { paste = false; nav.navigate(ConnRoutes.esignSign(p.first, p.second)) }
            }) { Text(stringResource(R.string.cn_open)) } },
            dismissButton = { TextButton(onClick = { paste = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RefCard(r: EsignRef, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (r.sent) Icons.Default.Outbox else Icons.Default.MoveToInbox, if (r.sent) Brand.tiles.purple else Brand.tiles.teal, size = 42.dp, iconSize = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DocumentRepository.formatDate(r.created) + (if (r.counts.isNotEmpty()) " · " + r.counts else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (r.status) {
                "todo" -> Chip(stringResource(R.string.cn_to_sign), MaterialTheme.colorScheme.primary)
                "gone" -> Chip(stringResource(R.string.cn_gone), MaterialTheme.colorScheme.outline)
                "signed" -> SignerStatusChip(SignerStatus.SIGNED)
                "declined" -> if (r.sent) RequestStatusChip(RequestStatus.DECLINED) else SignerStatusChip(SignerStatus.DECLINED)
                "waiting" -> SignerStatusChip(SignerStatus.WAITING)
                else -> RequestStatusChip(RequestStatus.of(r.status))
            }
        }
    }
}
