package com.smartdocs.editor.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.security.AttachRule
import com.smartdocs.editor.core.security.AttachmentPolicy
import com.smartdocs.editor.core.security.Privileged
import com.smartdocs.editor.core.security.PvMode
import com.smartdocs.editor.core.security.TrustPrefs
import com.smartdocs.editor.core.sign.TrustLists
import com.smartdocs.editor.core.theme.Brand
import kotlinx.coroutines.launch

/**
 * "Trust Manager" card (nitro-protect2): Safe Reading Mode, privileged locations, Protected View, remembered
 * JavaScript decisions, attachment security and the EU trusted list for signature validation.
 */
@Composable
fun Np2TrustCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var safe by remember { mutableStateOf(TrustPrefs.safeMode(ctx)) }
    var priv by remember { mutableStateOf(TrustPrefs.privileged(ctx)) }
    var pv by remember { mutableStateOf(TrustPrefs.pvMode(ctx)) }
    var decisions by remember { mutableIntStateOf(TrustPrefs.decisionCount(ctx)) }
    var useEutl by remember { mutableStateOf(TrustLists.useEutl(ctx)) }
    var autoEutl by remember { mutableStateOf(TrustLists.autoUpdate(ctx)) }
    var eutlCount by remember { mutableIntStateOf(TrustLists.count(ctx)) }
    var updating by remember { mutableStateOf(false) }
    var eutlMessage by remember { mutableStateOf<String?>(null) }
    var editPriv by remember { mutableStateOf(false) }
    var editAttach by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val doneFmt = stringResource(R.string.np2_eutl_done)
    val failFmt = stringResource(R.string.np2_eutl_failed)
    GroupCard(stringResource(R.string.np2_tm_title)) {
        SwitchRow(stringResource(R.string.np2_safe_title), safe, stringResource(R.string.np2_safe_sub), icon = Icons.Default.Shield, tile = Brand.tiles.green) { v ->
            TrustPrefs.setSafeMode(ctx, v); safe = v
        }
        GroupRow(stringResource(R.string.np2_priv_title), stringResource(R.string.np2_priv_count, priv.folders.size, priv.hosts.size),
            icon = Icons.Default.FolderSpecial, tile = Brand.tiles.teal, onClick = { editPriv = true })
        GroupDivider()
        GroupRow(stringResource(R.string.np2_pv_set), stringResource(R.string.np2_pv_set_sub), icon = Icons.Default.Visibility, tile = Brand.tiles.purple)
        G6Choice(listOf(PvMode.OFF to stringResource(R.string.np2_pv_off), PvMode.UNSAFE to stringResource(R.string.np2_pv_unsafe), PvMode.ALL to stringResource(R.string.np2_pv_all)), pv) { v ->
            TrustPrefs.setPvMode(ctx, v); pv = v
        }
        GroupDivider()
        GroupRow(stringResource(R.string.np2_trusted_docs), stringResource(R.string.np2_trusted_docs_count, decisions), icon = Icons.Default.Code,
            onClick = if (decisions > 0) ({ confirmClear = true }) else null)
        GroupRow(stringResource(R.string.np2_att_set), stringResource(R.string.np2_att_set_sub), icon = Icons.Default.AttachFile, tile = Brand.tiles.rose, onClick = { editAttach = true })
        GroupDivider()
        SwitchRow(stringResource(R.string.np2_eutl_use), useEutl, stringResource(R.string.np2_eutl_use_sub), icon = Icons.Default.VerifiedUser, tile = Brand.tiles.blue) { v ->
            TrustLists.setUseEutl(ctx, v); useEutl = v
        }
        SwitchRow(stringResource(R.string.np2_eutl_auto), autoEutl, stringResource(R.string.np2_eutl_auto_sub), icon = Icons.Default.Update) { v ->
            TrustLists.setAutoUpdate(ctx, v); autoEutl = v
        }
        val status = if (eutlCount > 0) stringResource(R.string.np2_eutl_status, eutlCount, TrustLists.countries(ctx), DocumentRepository.formatDate(TrustLists.lastUpdate(ctx)))
            else stringResource(R.string.np2_eutl_never)
        GroupRow(stringResource(R.string.np2_eutl_update), eutlMessage ?: status, icon = Icons.Default.CloudDownload,
            onClick = if (updating) null else ({
                updating = true; eutlMessage = null
                scope.launch {
                    eutlMessage = runCatching { TrustLists.updateNow(ctx) }.fold(
                        { r -> eutlCount = r.certificates; doneFmt.format(r.certificates, r.countries) },
                        { e -> failFmt.format(e.message ?: e.javaClass.simpleName) })
                    updating = false
                }
            }),
            trailing = { if (updating) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) })
        Text(stringResource(R.string.np2_eutl_note), Modifier.padding(start = 72.dp, end = 16.dp, bottom = 10.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (editPriv) PrivilegedDialog(onChange = { priv = TrustPrefs.privileged(ctx) }, onDismiss = { editPriv = false })
    if (editAttach) AttachmentRulesDialog(onDismiss = { editAttach = false })
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.np2_trusted_docs)) },
        text = { Text(stringResource(R.string.np2_trusted_docs_clear_q)) },
        confirmButton = { TextButton(onClick = { TrustPrefs.clearDecisions(ctx); decisions = 0; confirmClear = false }) { Text(stringResource(R.string.np2_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PrivilegedDialog(onChange: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var p by remember { mutableStateOf(TrustPrefs.privileged(ctx)) }
    var folder by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var badFolder by remember { mutableStateOf(false) }
    var badHost by remember { mutableStateOf(false) }
    fun refresh() { p = TrustPrefs.privileged(ctx); onChange() }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) { badFolder = !TrustPrefs.addFolder(ctx, u.toString()); refresh() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.np2_priv_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.np2_priv_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.np2_priv_app), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(p.appStorage, { TrustPrefs.setAppStoragePrivileged(ctx, it); refresh() })
                }
                Text(stringResource(R.string.np2_priv_folders), Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                p.folders.forEach { f -> EntryRow(f) { TrustPrefs.removeFolder(ctx, f); refresh() } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(folder, { folder = it; badFolder = false }, Modifier.weight(1f), singleLine = true, isError = badFolder,
                        label = { Text(stringResource(R.string.np2_priv_folder_path)) }, placeholder = { Text("/storage/emulated/0/Documents") })
                    IconButton(onClick = { if (TrustPrefs.addFolder(ctx, folder)) { folder = ""; refresh() } else badFolder = true }, enabled = folder.isNotBlank()) {
                        Icon(Icons.Default.Add, stringResource(R.string.np_site_add))
                    }
                }
                TextButton(onClick = { runCatching { tree.launch(null) } }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.np2_priv_pick_folder)) }
                if (badFolder) Text(stringResource(R.string.np2_priv_folder_bad), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.np2_priv_hosts), Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                p.hosts.forEach { h -> EntryRow(h) { TrustPrefs.removeHost(ctx, h); refresh() } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(host, { host = it; badHost = false }, Modifier.weight(1f), singleLine = true, isError = badHost,
                        label = { Text(stringResource(R.string.np_site_host)) }, placeholder = { Text("example.com") })
                    IconButton(onClick = { if (TrustPrefs.addHost(ctx, host)) { host = ""; refresh() } else badHost = true }, enabled = host.isNotBlank()) {
                        Icon(Icons.Default.Add, stringResource(R.string.np_site_add))
                    }
                }
                if (badHost) Text(stringResource(R.string.np_site_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun EntryRow(text: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
        IconButton(onClick = onRemove) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete)) }
    }
}

/** Per-extension allow / ask / block list for attachments opened or saved with another app, with "Restore defaults". */
@Composable
private fun AttachmentRulesDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(TrustPrefs.attachRules(ctx)) }
    var filter by remember { mutableStateOf("") }
    var newExt by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    fun store(next: Map<String, AttachRule>) { rules = next; TrustPrefs.setAttachRules(ctx, next) }
    val label: @Composable (AttachRule) -> String = { r ->
        stringResource(when (r) { AttachRule.ALLOW -> R.string.np2_att_allow; AttachRule.ASK -> R.string.np2_att_ask; AttachRule.BLOCK -> R.string.np2_att_block })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.np2_att_set)) },
        text = {
            Column {
                Text(stringResource(R.string.np2_att_rules_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newExt, { newExt = it; bad = false }, Modifier.weight(1f), singleLine = true, isError = bad,
                        label = { Text(stringResource(R.string.np2_att_ext)) }, placeholder = { Text("zip") })
                    IconButton(onClick = {
                        val e = AttachmentPolicy.normalizeExt(newExt)
                        if (e == null) bad = true else { store(rules + (e to (rules[e] ?: AttachRule.ASK))); newExt = ""; filter = e }
                    }, enabled = newExt.isNotBlank()) { Icon(Icons.Default.Add, stringResource(R.string.np_site_add)) }
                }
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                    label = { Text(stringResource(R.string.search)) })
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    rules.entries.filter { filter.isBlank() || it.key.contains(filter.trim().trimStart('.').lowercase()) }.sortedBy { it.key }.forEach { (ext, r) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(".$ext", Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            AttachRule.values().forEach { v ->
                                FilterChip(selected = v == r, onClick = { store(rules + (ext to v)) }, label = { Text(label(v), maxLines = 1) }, modifier = Modifier.padding(end = 4.dp))
                            }
                            IconButton(onClick = { store(rules - ext) }) { Icon(Icons.Default.Close, stringResource(R.string.delete)) }
                        }
                    }
                }
                Text(stringResource(R.string.np2_att_other), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = { TrustPrefs.restoreAttachDefaults(ctx); rules = TrustPrefs.attachRules(ctx) }) { Text(stringResource(R.string.np2_att_restore)) } },
    )
}
