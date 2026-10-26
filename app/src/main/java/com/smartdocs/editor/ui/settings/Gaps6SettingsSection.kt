package com.smartdocs.editor.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.AppSettings
import com.smartdocs.editor.core.gaps6.LayoutDir
import com.smartdocs.editor.core.gaps6.ReaderViewPrefs
import com.smartdocs.editor.core.gaps6.TransferPolicy
import com.smartdocs.editor.core.review.AcctPrefs
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape

/** Chip choice under a row title (start-aligned with the row text). */
@Composable
internal fun <T> G6Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 72.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label, maxLines = 1) }, shape = PillShape) }
    }
}

/** Extra rows of the "PDF view" card: full screen, volume action, screen timeout, orientation. */
@Composable
fun G6ViewRows(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val ctx = LocalContext.current
    SwitchRow(stringResource(R.string.g6_fullscreen), s.fullScreenReader, stringResource(R.string.g6_fullscreen_sub), icon = Icons.Default.Fullscreen) { v -> upd { it.copy(fullScreenReader = v) } }
    if (s.volumeKeysFlip) {
        GroupRow(stringResource(R.string.g6_volume_action), icon = Icons.Default.Tune)
        G6Choice(listOf("flip" to stringResource(R.string.g6_volume_flip), "zoom" to stringResource(R.string.g6_volume_zoom)), s.volumeKeysAction) { v -> upd { it.copy(volumeKeysAction = v) } }
    }
    if (s.keepScreenOn) {
        var timeout by remember { mutableIntStateOf(ReaderViewPrefs.timeout(ctx)) }
        GroupRow(stringResource(R.string.g6_screen_timeout), icon = Icons.Default.Timer)
        G6Choice(ReaderViewPrefs.TIMEOUTS.map { sec -> sec to timeoutLabel(sec) }, timeout) { v -> ReaderViewPrefs.setTimeout(ctx, v); timeout = v }
    }
    var orient by remember { mutableStateOf(ReaderViewPrefs.orientation(ctx)) }
    GroupRow(stringResource(R.string.g6_orientation), icon = Icons.Default.ScreenRotation)
    G6Choice(
        listOf(ReaderViewPrefs.Orientation.AUTO to stringResource(R.string.g6_orient_auto), ReaderViewPrefs.Orientation.PORTRAIT to stringResource(R.string.g6_orient_portrait),
            ReaderViewPrefs.Orientation.LANDSCAPE to stringResource(R.string.g6_orient_landscape)),
        orient,
    ) { v -> ReaderViewPrefs.setOrientation(ctx, v); orient = v }
    SwitchRow(stringResource(R.string.g6_remember_orientation), s.rememberOrientation, stringResource(R.string.g6_remember_orientation_sub), icon = Icons.Default.ScreenLockRotation) { v -> upd { it.copy(rememberOrientation = v) } }
}

@Composable
private fun timeoutLabel(sec: Int): String = when {
    sec < 0 -> stringResource(R.string.g6_never)
    else -> stringResource(R.string.g6_minutes, sec / 60)
}

/** Extra rows of the "General" card: layout direction, product improvement. */
@Composable
fun G6GeneralRows(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val ctx = LocalContext.current
    GroupDivider()
    GroupRow(stringResource(R.string.g6_layout_dir), icon = Icons.Default.FormatTextdirectionRToL, tile = Brand.tiles.blue)
    G6Choice(listOf(LayoutDir.AUTO to stringResource(R.string.g6_dir_auto), LayoutDir.RTL to stringResource(R.string.g6_dir_rtl), LayoutDir.LTR to stringResource(R.string.g6_dir_ltr)), s.layoutDirection) { v ->
        upd { it.copy(layoutDirection = v) }
    }
    GroupDivider()
    var analytics by remember { mutableStateOf(AcctPrefs.analyticsOptIn(ctx)) }
    SwitchRow(stringResource(R.string.g6_data_collection), analytics, stringResource(R.string.g6_data_collection_sub), icon = Icons.Default.Insights, tile = Brand.tiles.green) { v ->
        AcctPrefs.setAnalyticsOptIn(ctx, v); analytics = v
    }
}

/** "Sync & transfer" card: global auto sync, transfer network, storage screen. */
@Composable
fun G6SyncCard(s: AppSettings, nav: NavHostController, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val tiles = Brand.tiles
    GroupCard(stringResource(R.string.g6_sync_section)) {
        SwitchRow(stringResource(R.string.g6_auto_sync), s.autoSync, stringResource(R.string.g6_auto_sync_sub), icon = Icons.Default.Sync, tile = tiles.blue) { v -> upd { it.copy(autoSync = v) } }
        GroupDivider()
        GroupRow(stringResource(R.string.g6_transfer_net), icon = Icons.Default.NetworkWifi, tile = tiles.teal)
        G6Choice(listOf(TransferPolicy.WIFI to stringResource(R.string.g6_net_wifi), TransferPolicy.ANY to stringResource(R.string.g6_net_any)), s.transferNetwork) { v -> upd { it.copy(transferNetwork = v) } }
        GroupDivider()
        GroupRow(stringResource(R.string.g6_storage), stringResource(R.string.g6_storage_sub), icon = Icons.Default.Storage, tile = tiles.amber, onClick = {
            runCatching { nav.navigate("storage") }   // route registered by the sharing/cloud module
        })
    }
}

/** AI master switch row (top of the AI card). */
@Composable
fun G6AiSwitch(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    SwitchRow(stringResource(R.string.g6_ai_enabled), s.aiEnabled, stringResource(R.string.g6_ai_enabled_sub), icon = Icons.Default.AutoAwesome, tile = Brand.tiles.rose) { v -> upd { it.copy(aiEnabled = v) } }
}
