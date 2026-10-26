package com.smartdocs.editor.ui.cloud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.WifiTransferServer
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import kotlinx.coroutines.delay

/**
 * Route "wifi_transfer": local HTTP transfer protected by a 6-digit PIN, with a QR code of the address and an
 * automatic stop after 10 minutes without requests. The server lives as long as this screen.
 */
@Composable
fun WifiScreen(nav: NavHostController) {
    val app = LocalApp.current
    val view = LocalView.current
    var server by remember { mutableStateOf<WifiTransferServer?>(null) }
    var running by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var uploads by remember { mutableIntStateOf(0) }
    var autoStopped by remember { mutableStateOf(false) }
    var remaining by remember { mutableLongStateOf(0L) }
    val noNetwork = stringResource(R.string.tl_wifi_no_network)
    val noWifi = stringResource(R.string.s6_wifi_need_wifi)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var wifiOnly by remember { mutableStateOf(com.smartdocs.editor.core.sync.SyncPrefs.wifiServerOnly(ctx)) }

    DisposableEffect(Unit) { onDispose { server?.stop(); view.keepScreenOn = false } }
    LaunchedEffect(running) {
        view.keepScreenOn = running
        while (running) {
            val s = server
            if (s == null || !s.isRunning) { running = false; break }
            remaining = s.idleRemainingMs
            delay(1000)
        }
    }

    val appSettings = com.smartdocs.editor.LocalSettings.current
    val policyMsg = stringResource(R.string.s6_policy_wifi_only)
    fun start() {
        error = null; autoStopped = false
        // Settings → transfer network = Wi‑Fi only: refuse on mobile data
        if (!com.smartdocs.editor.core.gaps6.TransferPolicy.allowed(ctx, appSettings)) { error = policyMsg; return }
        // share6: "Wi‑Fi only" listens on the Wi‑Fi/hotspot address only; otherwise every network (USB/Ethernet tethering, other LANs)
        val ip = (if (wifiOnly) WifiTransferServer.wifiIp() else WifiTransferServer.deviceIp()) ?: run { error = if (wifiOnly) noWifi else noNetwork; return }
        val s = WifiTransferServer(app.documents.docsDir)
        if (wifiOnly) s.bindAddress = runCatching { java.net.InetAddress.getByName(ip) }.getOrNull()
        s.onFilesChanged = { uploads++ }
        s.onAutoStop = { autoStopped = true; running = false }
        runCatching { s.start() }
            .onSuccess { server = s; address = "http://$ip:${s.port}"; running = true }
            .onFailure { error = it.message ?: it.toString() }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.wifi_transfer), onBack = { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val addr = address
            val s = server
            if (running && addr != null && s != null) {
                Text(stringResource(R.string.ac_wifi_open_browser), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    SelectionContainer { Text(addr, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    QrImage(addr, Modifier.padding(12.dp).width(220.dp))
                }
                Text(stringResource(R.string.ac_wifi_pin), style = MaterialTheme.typography.titleSmall)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (s.pin ?: "").forEach { d ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(d.toString(), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
                val mins = remaining / 60_000; val secs = (remaining / 1000) % 60
                Text(stringResource(R.string.ac_wifi_idle, "%d:%02d".format(mins, secs)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (uploads > 0) Text(stringResource(R.string.tl_wifi_uploaded, uploads), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { s.stop(); running = false }, Modifier.fillMaxWidth().height(52.dp), shape = PillShape) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wifi_stop))
                }
            } else {
                Icon(Icons.Default.Wifi, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
                Text(stringResource(if (autoStopped) R.string.ac_wifi_auto_stopped else R.string.tl_wifi_stopped), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(stringResource(R.string.ac_wifi_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.s6_wifi_only_transfer), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(if (wifiOnly) R.string.s6_wifi_only_on else R.string.s6_wifi_only_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(wifiOnly, { wifiOnly = it; com.smartdocs.editor.core.sync.SyncPrefs.setWifiServerOnly(ctx, it) })
                }
                Button(onClick = { start() }, Modifier.fillMaxWidth().height(52.dp), shape = PillShape) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wifi_start))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.ac_wifi_security), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
