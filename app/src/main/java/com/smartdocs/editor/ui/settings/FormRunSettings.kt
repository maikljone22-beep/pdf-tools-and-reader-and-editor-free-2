package com.smartdocs.editor.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms5.AutocompleteFile
import com.smartdocs.editor.core.forms5.FormPrefs

/**
 * Settings -> Forms: automatic calculation, autocomplete (on / off, manage the remembered values, clear),
 * field highlight and required-field colours, the "fillable form" banner.
 */
@Composable
fun FormRunSettings() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { FormPrefs.load(ctx) }
    var manage by remember { mutableStateOf(false) }
    GroupCard(stringResource(R.string.f8_set_forms)) {
        SwitchRow(stringResource(R.string.f8_set_auto_calc), FormPrefs.autoCalculate, subtitle = stringResource(R.string.f8_set_auto_calc_sub), icon = Icons.Default.Calculate) {
            FormPrefs.setAutoCalculate(ctx, it)
        }
        SwitchRow(stringResource(R.string.f8_set_autocomplete), FormPrefs.autocomplete, icon = Icons.Default.History) { FormPrefs.setAutocomplete(ctx, it) }
        GroupRow(stringResource(R.string.f8_set_ac_manage), icon = Icons.Default.ListAlt, onClick = { manage = true })
        SwitchRow(stringResource(R.string.f8_set_banner), FormPrefs.fillableBanner, icon = Icons.Default.EditNote) { FormPrefs.setFillableBanner(ctx, it) }
        GroupDivider()
        GroupRow(stringResource(R.string.f8_set_hl_color), icon = Icons.Default.Palette)
        ColorRow(FormPrefs.highlightColor and 0xFFFFFF) { FormPrefs.setHighlightColor(ctx, (0x3D shl 24) or it) }
        GroupRow(stringResource(R.string.f8_set_req_color), icon = Icons.Default.Palette)
        ColorRow(FormPrefs.requiredColor and 0xFFFFFF) { FormPrefs.setRequiredColor(ctx, (0xCC shl 24) or it) }
    }
    if (manage) AutocompleteManager { manage = false }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 72.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormPrefs.PALETTE.forEach { c ->
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
                .clickable { onPick(c) })
        }
    }
}

/** List / edit / delete the remembered autocomplete values, or clear them all. */
@Composable
private fun AutocompleteManager(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val file = remember { AutocompleteFile.shared(FormPrefs.autocompleteFile(ctx)) }
    var version by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<Pair<String, String>?>(null) }
    val entries = remember(version) { runCatching { file.store().entries() }.getOrDefault(emptyMap()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.f8_set_ac_manage)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) Text(stringResource(R.string.f8_none))
                entries.forEach { (key, list) ->
                    Text(key, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    list.forEach { e ->
                        ListItem(headlineContent = { Text(e.value) }, supportingContent = { Text(stringResource(R.string.f8_used_n, e.count)) },
                            trailingContent = {
                                IconButton(onClick = { file.store().removeEntry(key, e.value); file.save(); version++ }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                            },
                            modifier = Modifier.clickable { edit = key to e.value })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = { file.store().clear(); file.save(); version++ }, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.f8_set_ac_clear)) } },
    )
    edit?.let { (key, old) ->
        var v by remember(key, old) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(key) },
            text = { OutlinedTextField(v, { v = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { file.store().editEntry(key, old, v); file.save(); edit = null; version++ }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
