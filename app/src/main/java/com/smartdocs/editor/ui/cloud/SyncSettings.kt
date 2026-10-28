package com.smartdocs.editor.ui.cloud

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartdocs.editor.R
import com.smartdocs.editor.core.sync.SyncPrefs
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.ui.settings.GroupCard
import com.smartdocs.editor.ui.settings.GroupDivider
import com.smartdocs.editor.ui.settings.SwitchRow

/**
 * Global sync / transfer options (share6). Shown on the Sync screen ("options") and meant to be dropped into the
 * Settings screen as a section: `com.smartdocs.editor.ui.cloud.SyncSettingsSection()`.
 */
@Composable
fun SyncSettingsSection(title: String? = null) {
    val ctx = LocalContext.current
    val v by SyncPrefs.version.collectAsStateWithLifecycle()
    val tiles = Brand.tiles
    val auto = remember(v) { SyncPrefs.autoSync(ctx) }
    val live = remember(v) { SyncPrefs.liveSync(ctx) }
    val onSave = remember(v) { SyncPrefs.syncOnSave(ctx) }
    val wifi = remember(v) { SyncPrefs.wifiOnly(ctx) }
    GroupCard(title ?: stringResource(R.string.s6_settings_title)) {
        SwitchRow(stringResource(R.string.s6_pref_auto), auto, stringResource(R.string.s6_pref_auto_sub), Icons.Default.Sync, tiles.green) { SyncPrefs.setAutoSync(ctx, it) }
        GroupDivider()
        SwitchRow(stringResource(R.string.s6_pref_live), live && auto, stringResource(R.string.s6_pref_live_sub), Icons.Default.Bolt, tiles.amber) { if (auto) SyncPrefs.setLiveSync(ctx, it) }
        // fc-sync: how cloud changes are found right now (notifications / delta feed), per provider
        val modes by com.smartdocs.editor.core.sync.ChangeWatch.modes.collectAsStateWithLifecycle()
        if (live && auto && modes.isNotEmpty()) androidx.compose.material3.Text(
            modes.entries.sortedBy { it.key.ordinal }.joinToString("
") { (p, m) -> p.label + ": " + watchLabel(ctx, m) },
            androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        GroupDivider()
        SwitchRow(stringResource(R.string.s6_pref_on_save), onSave && auto, stringResource(R.string.s6_pref_on_save_sub), Icons.Default.Save, tiles.blue) { if (auto) SyncPrefs.setSyncOnSave(ctx, it) }
        GroupDivider()
        SwitchRow(stringResource(R.string.s6_pref_wifi), wifi, stringResource(if (wifi) R.string.s6_pref_wifi_on else R.string.s6_pref_wifi_off), Icons.Default.Wifi, tiles.teal) { SyncPrefs.setWifiOnly(ctx, it) }
    }
}

private fun watchLabel(ctx: android.content.Context, m: com.smartdocs.editor.core.sync.ChangeWatch.Mode): String = ctx.getString(when (m) {
    com.smartdocs.editor.core.sync.ChangeWatch.Mode.NOTIFY -> R.string.fcy_watch_notify
    com.smartdocs.editor.core.sync.ChangeWatch.Mode.DELTA_POLL -> R.string.fcy_watch_poll
    com.smartdocs.editor.core.sync.ChangeWatch.Mode.WAITING_NETWORK -> R.string.fcy_watch_net
    com.smartdocs.editor.core.sync.ChangeWatch.Mode.BACKING_OFF -> R.string.fcy_watch_backoff
    com.smartdocs.editor.core.sync.ChangeWatch.Mode.SIGN_IN -> R.string.fcy_watch_signin
})
