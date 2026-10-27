package com.smartdocs.editor.ui.cloud

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.cloud.CloudProvider
import com.smartdocs.editor.core.cloud.Clouds
import com.smartdocs.editor.core.cloud.Dropbox
import com.smartdocs.editor.core.cloud.GoogleDrive
import com.smartdocs.editor.core.cloud.OAuthPkce
import com.smartdocs.editor.core.cloud.OneDrive
import com.smartdocs.editor.core.cloud.ProviderId
import com.smartdocs.editor.core.collab.CollabConfig
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.core.theme.TileColors
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.account.AcctRoutes
import com.smartdocs.editor.ui.common.EmptyIllustration
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.settings.GroupCard
import com.smartdocs.editor.ui.settings.GroupDivider
import com.smartdocs.editor.ui.settings.GroupRow
import kotlinx.coroutines.launch

/**
 * Cloud tab: direct Google Drive / OneDrive / Dropbox / Box accounts, the system picker (any installed provider),
 * sync folders, shared review and Wi‑Fi transfer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberPdfPicker { f -> f.firstOrNull()?.let { nav.navigate(Routes.reader(Uri.fromFile(it).toString())) } }
    val tiles = Brand.tiles
    val authVersion by CloudAuthEvents.version.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<ProviderId?>(null) }
    var confirmDisconnect by remember { mutableStateOf<CloudProvider?>(null) }
    val drive = remember { GoogleDrive.get(ctx) }
    val failMsg = stringResource(R.string.ac_auth_failed)
    val consoleHint = stringResource(R.string.ac_drive_console_hint)

    val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        busy = null
        if (res.resultCode == Activity.RESULT_OK) {
            runCatching { drive.finishAuthorization(res.data) }
                .onSuccess { refresh++; nav.navigate(AcctRoutes.cloudBrowser(ProviderId.DRIVE.key)) }
                .onFailure { Toast.makeText(ctx, failMsg + ": " + (it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    fun connect(p: CloudProvider) {
        when (p.id) {
            ProviderId.DRIVE -> scope.launch {
                busy = p.id
                runCatching { drive.beginAuthorization() }
                    .onSuccess { step ->
                        when (step) {
                            is GoogleDrive.AuthStep.Connected -> { busy = null; refresh++; nav.navigate(AcctRoutes.cloudBrowser(p.id.key)) }
                            is GoogleDrive.AuthStep.NeedsUi -> driveConsent.launch(IntentSenderRequest.Builder(step.pendingIntent.intentSender).build())
                        }
                    }
                    .onFailure { busy = null; Toast.makeText(ctx, "$failMsg\n$consoleHint", Toast.LENGTH_LONG).show() }
            }
            else -> if (!OAuthPkce.start(ctx, p.id)) Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_cloud), style = MaterialTheme.typography.headlineSmall) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }) { pad ->
        // re-read connection state after sign-in / sign-out
        val providers = remember(authVersion, refresh) { Clouds.all(ctx) }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (providers.none { it.isConnected() }) EmptyIllustration(
                R.drawable.ds_empty_cloud, stringResource(R.string.ds_cloud_title), stringResource(R.string.ac_cloud_intro),
                action = {
                    Button(onClick = pick, shape = PillShape, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.cloud_saf))
                    }
                },
            )
            GroupCard(stringResource(R.string.ds_cloud_accounts_section)) {
                providers.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider()
                    val (icon, tile) = providerLook(p.id, tiles)
                    val account = Clouds.accountOf(p)
                    when {
                        !p.isConfigured() -> GroupRow(p.id.label, stringResource(R.string.ac_not_configured), icon = icon, tile = tile)
                        p.isConnected() -> GroupRow(p.id.label, account ?: stringResource(R.string.ac_connected), icon = icon, tile = tile,
                            onClick = { nav.navigate(AcctRoutes.cloudBrowser(p.id.key)) },
                            trailing = { IconButton(onClick = { confirmDisconnect = p }) { Icon(Icons.Default.LinkOff, stringResource(R.string.ac_disconnect)) } })
                        else -> GroupRow(p.id.label, stringResource(R.string.ac_tap_to_connect), icon = icon, tile = tile, onClick = { connect(p) },
                            trailing = { if (busy == p.id) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Login, null) })
                    }
                }
            }
            com.smartdocs.editor.ui.webdav.WebDavPlacesCard(nav)   // nav-attach: WebDAV places ("Add Place")
            GroupCard(stringResource(R.string.ds_cloud_open_section)) {
                GroupRow(stringResource(R.string.cloud_saf), stringResource(R.string.ac_saf_sub), icon = Icons.Default.FolderOpen, tile = tiles.blue, onClick = pick)
                GroupDivider()
                GroupRow(stringResource(R.string.ac_sync_title), stringResource(R.string.s6_sync_sub), icon = Icons.Default.Sync, tile = tiles.green, onClick = { nav.navigate(AcctRoutes.SYNC) })
                GroupDivider()
                GroupRow(stringResource(R.string.ac_review_title), if (CollabConfig.enabled) stringResource(R.string.ac_review_sub) else stringResource(R.string.ac_not_configured),
                    icon = Icons.Default.RateReview, tile = tiles.purple, onClick = if (CollabConfig.enabled) ({ nav.navigate(AcctRoutes.review()) }) else null)
                GroupDivider()
                GroupRow(stringResource(R.string.sh5_hub), stringResource(R.string.sh5_tracker_sub), icon = Icons.Default.Share, tile = tiles.amber,
                    onClick = { nav.navigate(com.smartdocs.editor.ui.share5.Share5Routes.HUB) })
                GroupDivider()
                GroupRow(stringResource(R.string.wifi_transfer), stringResource(R.string.ac_wifi_sub), icon = Icons.Default.Wifi, tile = tiles.teal, onClick = { nav.navigate(AcctRoutes.WIFI) })
            }
            Text(stringResource(R.string.ac_cloud_save_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }

    confirmDisconnect?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDisconnect = null },
            title = { Text(stringResource(R.string.ac_disconnect)) },
            text = { Text(stringResource(R.string.ac_disconnect_q, p.id.label)) },
            confirmButton = { TextButton(onClick = { confirmDisconnect = null; scope.launch { runCatching { p.disconnect() }; refresh++ } }) { Text(stringResource(R.string.ac_disconnect)) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

internal fun providerLook(id: ProviderId, tiles: com.smartdocs.editor.core.theme.BrandTiles): Pair<ImageVector, TileColors> = when (id) {
    ProviderId.DRIVE -> Icons.Default.AddToDrive to tiles.green
    ProviderId.ONEDRIVE -> Icons.Default.Cloud to tiles.blue
    ProviderId.DROPBOX -> Icons.Default.Inventory2 to tiles.purple
    ProviderId.BOX -> Icons.Default.Inbox to tiles.teal
}
