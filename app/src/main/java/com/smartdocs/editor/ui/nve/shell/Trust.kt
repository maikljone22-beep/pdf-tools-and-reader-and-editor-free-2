package com.smartdocs.editor.ui.nve.shell

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.shell.TrustPolicy

/**
 * Safe reading (trust manager): links to untrusted hosts and document JavaScript ask first (once / always for this
 * host or document / block). Trusted hosts and documents are kept in prefs "nve_shell". The reader calls
 * [guardLink] / [guardScript]; [NveTrustDialogHost] (root of the app) shows the prompt.
 */
object NveTrust {
    private class Pending(val title: String, val detail: String, val host: String?, val docKey: String?, val allow: () -> Unit)
    private var pending by mutableStateOf<Pending?>(null)

    private fun p(ctx: Context) = NveShellPrefs.prefs(ctx)
    fun linkMode(ctx: Context) = TrustPolicy.Mode.entries.getOrElse(p(ctx).getInt("trust_links", 0)) { TrustPolicy.Mode.ASK }
    fun scriptMode(ctx: Context) = TrustPolicy.Mode.entries.getOrElse(p(ctx).getInt("trust_js", 0)) { TrustPolicy.Mode.ASK }
    fun setLinkMode(ctx: Context, m: TrustPolicy.Mode) { p(ctx).edit().putInt("trust_links", m.ordinal).apply(); NveShellPrefs.version.intValue++ }
    fun setScriptMode(ctx: Context, m: TrustPolicy.Mode) { p(ctx).edit().putInt("trust_js", m.ordinal).apply(); NveShellPrefs.version.intValue++ }
    fun hosts(ctx: Context): Set<String> = p(ctx).getStringSet("trusted_hosts", emptySet())!!.toSet()
    fun docs(ctx: Context): Set<String> = p(ctx).getStringSet("trusted_docs", emptySet())!!.toSet()
    fun trustHost(ctx: Context, h: String) { p(ctx).edit().putStringSet("trusted_hosts", hosts(ctx) + h).apply(); NveShellPrefs.version.intValue++ }
    fun untrustHost(ctx: Context, h: String) { p(ctx).edit().putStringSet("trusted_hosts", hosts(ctx) - h).apply(); NveShellPrefs.version.intValue++ }
    fun trustDoc(ctx: Context, d: String) { p(ctx).edit().putStringSet("trusted_docs", docs(ctx) + d).apply(); NveShellPrefs.version.intValue++ }
    fun untrustDoc(ctx: Context, d: String) { p(ctx).edit().putStringSet("trusted_docs", docs(ctx) - d).apply(); NveShellPrefs.version.intValue++ }

    /** Open [raw] (URL / mailto / tel) through [open] when allowed; otherwise ask first (or block). */
    fun guardLink(ctx: Context, raw: String, open: () -> Unit) {
        when (TrustPolicy.link(raw, hosts(ctx), linkMode(ctx))) {
            TrustPolicy.Decision.ALLOW -> open()
            TrustPolicy.Decision.BLOCK -> android.widget.Toast.makeText(ctx, ctx.getString(R.string.nl_trust_blocked, raw), android.widget.Toast.LENGTH_SHORT).show()
            TrustPolicy.Decision.ASK -> pending = Pending(ctx.getString(R.string.nl_trust_link_title), raw, TrustPolicy.hostOf(raw), null, open)
        }
    }

    /** Run document JavaScript of [docKey] (e.g. the file path) through [run] when allowed; otherwise ask. */
    fun guardScript(ctx: Context, docKey: String, docName: String, run: () -> Unit) {
        when (TrustPolicy.script(docKey, docs(ctx), scriptMode(ctx))) {
            TrustPolicy.Decision.ALLOW -> run()
            TrustPolicy.Decision.BLOCK -> {}
            TrustPolicy.Decision.ASK -> pending = Pending(ctx.getString(R.string.nl_trust_js_title), docName, null, docKey, run)
        }
    }

    @Composable
    internal fun PromptDialog() {
        val ctx = LocalContext.current
        val pd = pending ?: return
        AlertDialog(onDismissRequest = { pending = null }, icon = { Icon(Icons.Default.Shield, null) }, title = { Text(pd.title) },
            text = { Column { Text(pd.detail, style = MaterialTheme.typography.bodyMedium); Text(stringResource(R.string.nl_trust_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) } },
            confirmButton = {
                Row {
                    TextButton(onClick = { pending = null; pd.allow() }) { Text(stringResource(R.string.nl_trust_once)) }
                    TextButton(onClick = {
                        pending = null; pd.host?.let { trustHost(ctx, it) }; pd.docKey?.let { trustDoc(ctx, it) }; pd.allow()
                    }) { Text(stringResource(if (pd.host != null) R.string.nl_trust_always_host else R.string.nl_trust_always_doc)) }
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.nl_trust_block)) } })
    }
}

/** The trust prompt host (once, at the app root). */
@Composable
fun NveTrustDialogHost() = NveTrust.PromptDialog()

/** Settings: link / JavaScript trust mode and the trusted hosts / documents lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NveTrustSettingsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val v = NveShellPrefs.version.intValue
    val hosts = remember(v) { NveTrust.hosts(ctx).sorted() }
    val docs = remember(v) { NveTrust.docs(ctx).sorted() }
    var newHost by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 16.dp)) {
            item {
                Text(stringResource(R.string.nl_trust_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.nl_trust_links), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                ModeRow(remember(v) { NveTrust.linkMode(ctx) }) { NveTrust.setLinkMode(ctx, it) }
                Text(stringResource(R.string.nl_trust_js), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                ModeRow(remember(v) { NveTrust.scriptMode(ctx) }) { NveTrust.setScriptMode(ctx, it) }
                Text(stringResource(R.string.nl_trust_hosts), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Row {
                    OutlinedTextField(newHost, { newHost = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("example.com") })
                    IconButton(onClick = { TrustPolicy.hostOf(newHost)?.let { NveTrust.trustHost(ctx, it) }; newHost = "" }, enabled = newHost.isNotBlank()) { Icon(Icons.Default.Add, stringResource(R.string.nl_add)) }
                }
            }
            items(hosts, key = { "h$it" }) { h -> ListItem(headlineContent = { Text(h) }, trailingContent = { IconButton(onClick = { NveTrust.untrustHost(ctx, h) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } }) }
            item { Text(stringResource(R.string.nl_trust_docs), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp)) }
            items(docs, key = { "d$it" }) { d -> ListItem(headlineContent = { Text(java.io.File(d).name) }, supportingContent = { Text(d, maxLines = 1) }, trailingContent = { IconButton(onClick = { NveTrust.untrustDoc(ctx, d) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } }) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ModeRow(mode: TrustPolicy.Mode, onMode: (TrustPolicy.Mode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TrustPolicy.Mode.ASK to R.string.nl_trust_ask, TrustPolicy.Mode.ALWAYS to R.string.nl_trust_allow_all, TrustPolicy.Mode.NEVER to R.string.nl_trust_block_all).forEach { (m, l) ->
            FilterChip(mode == m, { onMode(m) }, label = { Text(stringResource(l)) })
        }
    }
}
