package com.smartdocs.editor.ui.nve.shell

import android.content.Context
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
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.shell.ScanPreset

object NveScanPresets {
    fun user(ctx: Context): List<ScanPreset> = ScanPreset.parse(NveShellPrefs.prefs(ctx).getString("scan_presets", null))
    fun save(ctx: Context, list: List<ScanPreset>) { NveShellPrefs.prefs(ctx).edit().putString("scan_presets", ScanPreset.format(list)).apply(); NveShellPrefs.version.intValue++ }
}

@Composable
private fun label(p: ScanPreset): String = if (!p.builtIn) p.name else stringResource(when (p.name) {
    "good" -> R.string.nl_scan_good; "high" -> R.string.nl_scan_high; "print" -> R.string.nl_scan_print; else -> R.string.nl_scan_text
})

/**
 * Scan presets row above the scanner options: apply a preset, save the current options as a preset, rename / copy /
 * delete user presets. [current] = paper, colour mode, quality and OCR of the scanner now.
 */
@Composable
fun NveScanPresetsRow(paper: String, mode: String, quality: Float, ocr: Boolean, onApply: (ScanPreset) -> Unit) {
    val ctx = LocalContext.current
    val v = NveShellPrefs.version.intValue
    val user = remember(v) { NveScanPresets.user(ctx) }
    var saveDialog by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<ScanPreset?>(null) }
    val copyName = stringResource(R.string.nl_copy)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.nl_scan_presets), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (ScanPreset.BUILT_IN + user).forEach { p ->
                val on = p.paper == paper && p.mode == mode && p.quality == quality.toInt() && p.ocr == ocr
                FilterChip(on, { onApply(p) }, label = { Text(label(p)) })
            }
            AssistChip(onClick = { saveDialog = true }, label = { Text(stringResource(R.string.nl_scan_save_preset)) }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
            if (user.isNotEmpty()) AssistChip(onClick = { manage = true }, label = { Text(stringResource(R.string.nl_manage)) }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) })
        }
    }
    if (saveDialog) RenameDialog(stringResource(R.string.nl_scan_save_preset), "", onOk = { n ->
        if (n.isNotBlank()) NveScanPresets.save(ctx, ScanPreset.upsert(user, ScanPreset(n.trim(), paper, mode, quality.toInt(), ocr)))
    }) { saveDialog = false }
    if (manage) AlertDialog(onDismissRequest = { manage = false }, title = { Text(stringResource(R.string.nl_scan_presets)) },
        text = {
            Column {
                user.forEach { p ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f))
                        IconButton(onClick = { rename = p }) { Icon(Icons.Default.Edit, stringResource(R.string.nl_rename)) }
                        IconButton(onClick = { NveScanPresets.save(ctx, user + ScanPreset.copyOf(user, p, p.name.ifBlank { copyName })) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.nl_copy)) }
                        IconButton(onClick = { NveScanPresets.save(ctx, user.map { if (it == p) it.copy(paper = paper, mode = mode, quality = quality.toInt(), ocr = ocr) else it }) }) { Icon(Icons.Default.Save, stringResource(R.string.nl_update_preset)) }
                        IconButton(onClick = { NveScanPresets.save(ctx, user - p) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { manage = false }) { Text(stringResource(R.string.close)) } })
    rename?.let { p -> RenameDialog(stringResource(R.string.nl_rename), p.name, onOk = { n -> if (n.isNotBlank()) NveScanPresets.save(ctx, user.map { if (it == p) it.copy(name = n.trim()) else it }) }) { rename = null } }
}
