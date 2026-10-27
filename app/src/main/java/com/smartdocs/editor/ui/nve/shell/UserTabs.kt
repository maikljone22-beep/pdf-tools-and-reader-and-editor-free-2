package com.smartdocs.editor.ui.nve.shell

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.shell.Customization
import com.smartdocs.editor.core.nve.shell.UserTab
import com.smartdocs.editor.ui.reader.ToolChip
import com.smartdocs.editor.ui.tools.allTools

/** User-defined reader tabs + export / import of the whole shell customisation (prefs "nve_shell"). */
object NveUserTabs {
    fun tabs(ctx: Context): List<UserTab> = Customization.parseTabs(NveShellPrefs.prefs(ctx).getString("user_tabs", null))
    fun save(ctx: Context, tabs: List<UserTab>) {
        NveShellPrefs.prefs(ctx).edit().putString("user_tabs", Customization.tabsJson(tabs.take(Customization.MAX_USER_TABS))).apply()
        NveShellPrefs.version.intValue++
    }

    fun icon(name: String): ImageVector = when (name) {
        "work" -> Icons.Default.Work; "edit" -> Icons.Default.Edit; "school" -> Icons.Default.School; "bolt" -> Icons.Default.Bolt
        "build" -> Icons.Default.Build; "favorite" -> Icons.Default.Favorite; "folder" -> Icons.Default.Folder; else -> Icons.Default.Star
    }

    /** The current customisation (for export). */
    fun current(ctx: Context) = Customization(
        tabOrder = NveToolbarPrefs.tabOrder(ctx), hiddenTabs = NveToolbarPrefs.hiddenTabs(ctx), hiddenChips = NveToolbarPrefs.hiddenChips(ctx),
        userTabs = tabs(ctx), favorites = NveShellPrefs.favorites(ctx), favoritesTitle = NveShellPrefs.favoritesTitle(ctx), customTabName = NveShellPrefs.customTabName(ctx),
    )

    /** Replace the customisation with an imported one. */
    fun apply(ctx: Context, c: Customization) {
        NveToolbarPrefs.save(ctx, c.tabOrder, c.hiddenTabs, c.hiddenChips)
        save(ctx, c.userTabs)
        NveShellPrefs.setFavorites(ctx, c.favorites)
        NveShellPrefs.setFavoritesTitle(ctx, c.favoritesTitle ?: "")
        NveShellPrefs.setCustomTabName(ctx, c.customTabName ?: "")
    }
}

/**
 * Toolbar of a user tab in the reader (hook for the parent: show a chip row for each user tab next to the built-in
 * tabs; [navigateTool] = ReaderActions.navigateTool).
 */
@Composable
fun NveUserTabToolbar(tabId: String, navigateTool: (String) -> Unit) {
    val ctx = LocalContext.current
    val tab = remember(NveShellPrefs.version.intValue, tabId) { NveUserTabs.tabs(ctx).firstOrNull { it.id == tabId } } ?: return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tab.tools.mapNotNull { id -> allTools.firstOrNull { it.id == id } }.forEach { t -> ToolChip(t.icon, stringResource(t.label)) { navigateTool(t.id) } }
        if (tab.tools.isEmpty()) Text(stringResource(R.string.nl_user_tab_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
    }
}

/** "My tabs" section of the toolbar customisation sheet + export / import. */
@Composable
fun NveUserTabsEditor() {
    val ctx = LocalContext.current
    val v = NveShellPrefs.version.intValue
    val tabs = remember(v) { NveUserTabs.tabs(ctx) }
    var editing by remember { mutableStateOf<UserTab?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(NveUserTabs.current(ctx).toJson().toByteArray()) } }
            .onSuccess { Toast.makeText(ctx, ctx.getString(R.string.nl_cust_exported), Toast.LENGTH_SHORT).show() }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { Customization.fromJson(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }) }
            .onSuccess { NveUserTabs.apply(ctx, it); Toast.makeText(ctx, ctx.getString(R.string.nl_cust_imported), Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, ctx.getString(R.string.nl_cust_import_failed), Toast.LENGTH_SHORT).show() }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.nl_user_tabs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp, 16.dp, 8.dp, 4.dp))
        tabs.forEach { t ->
            ListItem(headlineContent = { Text(t.name) }, supportingContent = { Text(stringResource(R.string.nl_n_tools, t.tools.size)) },
                leadingContent = { Icon(NveUserTabs.icon(t.icon), null) },
                trailingContent = { IconButton(onClick = { NveUserTabs.save(ctx, tabs - t) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } },
                modifier = Modifier.clickable { editing = t })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tabs.size < Customization.MAX_USER_TABS) AssistChip(onClick = { editing = UserTab("u" + System.currentTimeMillis(), "", "star", emptyList()) },
                label = { Text(stringResource(R.string.nl_user_tab_add)) }, leadingIcon = { Icon(Icons.Default.Add, null) })
            AssistChip(onClick = { runCatching { export.launch("smartdocs-customization.json") } }, label = { Text(stringResource(R.string.nl_cust_export)) }, leadingIcon = { Icon(Icons.Default.Upload, null) })
            AssistChip(onClick = { runCatching { import.launch(arrayOf("application/json", "text/plain", "*/*")) } }, label = { Text(stringResource(R.string.nl_cust_import)) }, leadingIcon = { Icon(Icons.Default.Download, null) })
        }
    }
    editing?.let { t -> UserTabDialog(t, onSave = { nt -> NveUserTabs.save(ctx, tabs.filter { it.id != nt.id } + nt); editing = null }) { editing = null } }
}

@Composable
private fun UserTabDialog(tab: UserTab, onSave: (UserTab) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(tab.name) }
    var icon by remember { mutableStateOf(tab.icon) }
    val tools = remember { mutableStateListOf<String>().apply { addAll(tab.tools) } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp).heightIn(max = 600.dp)) {
                Text(stringResource(R.string.nl_user_tab), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text(stringResource(R.string.nl_name)) })
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    Customization.ICONS.forEach { i -> IconToggleButton(icon == i, { icon = i }) { Icon(NveUserTabs.icon(i), i, tint = if (icon == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
                Text(stringResource(R.string.nl_user_tab_tools, tools.size, Customization.MAX_TOOLS_PER_TAB), style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(allTools, key = { it.id }) { t ->
                        val on = t.id in tools
                        Row(Modifier.fillMaxWidth().clickable { if (on) tools.remove(t.id) else if (tools.size < Customization.MAX_TOOLS_PER_TAB) tools.add(t.id) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, null); Icon(t.icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(t.label))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(enabled = name.isNotBlank(), onClick = { onSave(UserTab(tab.id, name.trim(), icon, tools.toList())) }) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}
