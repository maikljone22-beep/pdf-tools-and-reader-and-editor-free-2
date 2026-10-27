package com.smartdocs.editor.ui.nve.shell

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.shell.RecoveryNames
import com.smartdocs.editor.core.theme.Accent
import com.smartdocs.editor.ui.settings.GroupDivider
import com.smartdocs.editor.ui.settings.GroupRow
import kotlinx.coroutines.launch

/** Named colour themes ("skins"): label + accent (Accent.DEFAULT = classic app colours). */
val NVE_SKINS: List<Pair<Int, Long>> = listOf(
    R.string.nl_skin_classic to Accent.DEFAULT, R.string.nl_skin_gray to 0x607D8BL, R.string.nl_skin_colorful to 0xE91E63L,
    R.string.nl_skin_blue to 0x1565C0L, R.string.nl_skin_orange to 0xEF6C00L, R.string.nl_skin_purple to 0x6A1B9AL,
)

/**
 * Settings rows of the shell work (hook in SettingsScreen's General card): accent colour / colour theme, auto-recovery
 * interval, reader toolbar customisation, Custom tab name.
 */
@Composable
fun NveShellSettingsRows() {
    val app = LocalApp.current
    val s = LocalSettings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var toolbar by remember { mutableStateOf(false) }
    var renameTab by remember { mutableStateOf(false) }
    fun setAccent(v: Long) = scope.launch { app.settings.update { it.copy(accentColor = v) } }

    GroupDivider()
    GroupRow(stringResource(R.string.nl_accent), stringResource(R.string.nl_accent_sub), icon = Icons.Default.ColorLens)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NVE_SKINS.forEach { (label, v) -> FilterChip(s.accentColor == v, { setAccent(v) }, label = { Text(stringResource(label)) }) }
        if (Build.VERSION.SDK_INT >= 31) FilterChip(s.accentColor == Accent.DYNAMIC, { setAccent(Accent.DYNAMIC) }, label = { Text(stringResource(R.string.nl_accent_dynamic)) },
            leadingIcon = { Icon(Icons.Default.Wallpaper, null, Modifier.size(16.dp)) })
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Accent.PRESETS.forEach { c ->
            val sel = s.accentColor == c
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or c))
                .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.onSurface else Color(0x33000000), CircleShape)
                .clickable { setAccent(c) }, contentAlignment = Alignment.Center) {
                if (sel) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
    GroupDivider()
    val minutes = remember(NveShellPrefs.version.intValue) { NveShellPrefs.recoveryMinutes(ctx) }
    GroupRow(stringResource(R.string.nl_recovery), stringResource(R.string.nl_recovery_sub), icon = Icons.Default.Restore)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RecoveryNames.INTERVALS.forEach { m ->
            FilterChip(minutes == m, { NveShellPrefs.setRecoveryMinutes(ctx, m) },
                label = { Text(if (m == 0) stringResource(R.string.nl_off) else stringResource(R.string.nl_minutes, m)) })
        }
    }
    GroupDivider()
    GroupRow(stringResource(R.string.nl_customize_toolbar), stringResource(R.string.nl_customize_toolbar_sub), icon = Icons.Default.Tune, onClick = { toolbar = true })
    GroupRow(stringResource(R.string.nl_rename_custom_tab), NveShellPrefs.customTabName(ctx) ?: stringResource(R.string.an_custom_tab), icon = Icons.Default.Edit, onClick = { renameTab = true })

    if (toolbar) NveToolbarCustomizeSheet { toolbar = false }
    if (renameTab) RenameDialog(stringResource(R.string.nl_rename_custom_tab), NveShellPrefs.customTabName(ctx) ?: "", onOk = { NveShellPrefs.setCustomTabName(ctx, it) }) { renameTab = false }
}

/** Small text-entry dialog shared by the rename actions. */
@Composable
fun RenameDialog(title: String, initial: String, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(t, { t = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onOk(t); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Reader Custom tab sheet: rename the tab and remove every tool (hook in ToolsetEditSheet). */
@Composable
fun NveCustomTabActions(onRemoveAll: () -> Unit) {
    val ctx = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { rename = true }, label = { Text(stringResource(R.string.nl_rename_custom_tab)) }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) })
        AssistChip(onClick = onRemoveAll, label = { Text(stringResource(R.string.nl_remove_all)) }, leadingIcon = { Icon(Icons.Default.DeleteSweep, null, Modifier.size(16.dp)) })
    }
    if (rename) RenameDialog(stringResource(R.string.nl_rename_custom_tab), NveShellPrefs.customTabName(ctx) ?: "", onOk = { NveShellPrefs.setCustomTabName(ctx, it) }) { rename = false }
}
