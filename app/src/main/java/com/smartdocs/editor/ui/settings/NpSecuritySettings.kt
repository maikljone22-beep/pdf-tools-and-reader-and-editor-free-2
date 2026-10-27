package com.smartdocs.editor.ui.settings

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
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.AppSettings
import com.smartdocs.editor.core.security.NetMode
import com.smartdocs.editor.core.security.SecurityPrefs
import com.smartdocs.editor.core.theme.Brand

/**
 * "Document security" card (nitro-protect): run document JavaScript, Internet access of documents (allow all /
 * block all / ask, trusted sites) and file-launch actions.
 */
@Composable
fun NpSecurityCard(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(SecurityPrefs.netMode(ctx)) }
    var sites by remember { mutableStateOf(SecurityPrefs.sites(ctx)) }
    var blockLaunch by remember { mutableStateOf(SecurityPrefs.blockLaunch(ctx)) }
    var editSites by remember { mutableStateOf(false) }
    GroupCard(stringResource(R.string.np_set_title)) {
        SwitchRow(stringResource(R.string.np_set_run_js), s.runJavaScript, stringResource(R.string.np_set_run_js_sub), icon = Icons.Default.Code, tile = Brand.tiles.amber) { v ->
            upd { it.copy(runJavaScript = v) }
        }
        GroupDivider()
        GroupRow(stringResource(R.string.np_set_net), stringResource(R.string.np_set_net_sub), icon = Icons.Default.Language, tile = Brand.tiles.blue)
        G6Choice(
            listOf(NetMode.ASK to stringResource(R.string.np_net_mode_ask), NetMode.ALLOW_ALL to stringResource(R.string.np_net_mode_allow), NetMode.BLOCK_ALL to stringResource(R.string.np_net_mode_block)),
            mode,
        ) { v -> SecurityPrefs.setNetMode(ctx, v); mode = v }
        GroupRow(stringResource(R.string.np_set_sites), stringResource(R.string.np_set_sites_count, sites.size), icon = Icons.Default.VerifiedUser, onClick = { editSites = true })
        GroupDivider()
        SwitchRow(stringResource(R.string.np_set_block_launch), blockLaunch, stringResource(R.string.np_set_block_launch_sub), icon = Icons.Default.Block, tile = Brand.tiles.rose) { v ->
            SecurityPrefs.setBlockLaunch(ctx, v); blockLaunch = v
        }
    }
    if (editSites) TrustedSitesDialog(sites, onChange = { sites = SecurityPrefs.sites(ctx) }, onDismiss = { editSites = false })
}

@Composable
private fun TrustedSitesDialog(sites: List<String>, onChange: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    fun add() { if (SecurityPrefs.addSite(ctx, input)) { input = ""; bad = false; onChange() } else bad = true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.np_set_sites)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it; bad = false }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.np_site_host)) },
                        placeholder = { Text("example.com") }, isError = bad, supportingText = if (bad) { { Text(stringResource(R.string.np_site_invalid)) } } else null)
                    IconButton(onClick = ::add, enabled = input.isNotBlank()) { Icon(Icons.Default.Add, stringResource(R.string.np_site_add)) }
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (sites.isEmpty()) Text(stringResource(R.string.np_sites_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sites.forEach { h ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(h, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            IconButton(onClick = { SecurityPrefs.removeSite(ctx, h); onChange() }) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete)) }
                        }
                    }
                }
                Text(stringResource(R.string.np_sites_hint), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
