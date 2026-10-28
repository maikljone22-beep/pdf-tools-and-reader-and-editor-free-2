package com.smartdocs.editor.ui.rpf

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.rpf.StripEntry
import com.smartdocs.editor.core.rpf.TabGroup
import com.smartdocs.editor.core.rpf.TabGroupOps
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.view.DocTab
import com.smartdocs.editor.ui.reader.view.ReaderTabs
import org.json.JSONArray
import org.json.JSONObject

/** OLD SHL-5: named tab groups (prefs "rpf_tab_groups"); closed groups keep their tabs (name, page) to restore them. */
object RpfTabGroups {
    private const val PREFS = "rpf_tab_groups"
    var groups by mutableStateOf<List<TabGroup>>(emptyList()); private set
    /** Saved tabs of closed groups (uri → tab), so a restore reopens each document at its page. */
    private val saved = HashMap<String, DocTab>()
    var sheetOpen by mutableStateOf(false)
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        groups = runCatching {
            val a = JSONArray(p.getString("groups", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i); val u = o.getJSONArray("uris")
                TabGroup(o.getString("id"), o.getString("name"), (0 until u.length()).map { u.getString(it) }, o.optBoolean("collapsed"), o.optBoolean("closed"), o.optInt("color"))
            }
        }.getOrDefault(emptyList())
        runCatching {
            val a = JSONArray(p.getString("saved", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); saved[o.getString("uri")] = DocTab(o.getString("uri"), o.optString("name"), o.optInt("page")) }
        }
    }

    private fun save(ctx: Context) {
        val a = JSONArray()
        groups.forEach { g -> a.put(JSONObject().put("id", g.id).put("name", g.name).put("uris", JSONArray(g.uris)).put("collapsed", g.collapsed).put("closed", g.closed).put("color", g.color)) }
        val keep = groups.filter { it.closed }.flatMap { it.uris }.toSet()
        saved.keys.retainAll(keep)
        val b = JSONArray(); saved.values.forEach { t -> b.put(JSONObject().put("uri", t.uri).put("name", t.name).put("page", t.page)) }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("groups", a.toString()).putString("saved", b.toString()).apply()
    }

    fun update(ctx: Context, f: (List<TabGroup>) -> List<TabGroup>) { load(ctx); groups = f(groups); save(ctx) }

    /** Hook in ReaderTabs.close: a closed tab leaves its (open) group. */
    fun onClosed(ctx: Context, uri: String) { load(ctx); if (groups.isNotEmpty()) update(ctx) { TabGroupOps.onTabClosed(it, uri) } }

    /** Closes every tab of [g] and keeps the group to restore; the shown document switches to a tab left open. */
    fun closeGroup(ctx: Context, g: TabGroup, current: String, switchTo: (DocTab?) -> Unit) {
        ReaderTabs.tabs.filter { it.uri in g.uris }.forEach { saved[it.uri] = it }
        update(ctx) { TabGroupOps.setClosed(it, g.id, true) }
        val others = ReaderTabs.tabs.toList().filter { it.uri !in g.uris }
        ReaderTabs.retain(ctx, others.firstOrNull { it.uri == current }?.uri ?: others.firstOrNull()?.uri) { it.uri !in g.uris }
        if (current in g.uris) switchTo(ReaderTabs.tabs.firstOrNull())
    }

    /** Reopens the tabs of a closed group and shows its first document. */
    fun restoreGroup(ctx: Context, g: TabGroup, switchTo: (DocTab?) -> Unit) {
        val tabs = g.uris.filter { u -> Uri.parse(u).path?.let { java.io.File(it).exists() } == true }
            .map { u -> saved[u] ?: DocTab(u, Uri.parse(u).lastPathSegment ?: u) }
        ReaderTabs.addTabs(ctx, tabs)
        update(ctx) { TabGroupOps.setClosed(it, g.id, false) }
        tabs.firstOrNull()?.let { switchTo(it.copy(page = it.page.coerceAtLeast(0))) }
    }
}

/**
 * Mouse wheel over the tab strip switches documents (one tab per wheel notch burst). [entries] gives the strip
 * order, [current] the shown document.
 */
fun rpfWheelTabs(entries: () -> List<StripEntry>, current: () -> String?, switchTo: (DocTab?) -> Unit): Modifier = Modifier.pointerInput(Unit) {
    var last = 0L
    awaitPointerEventScope {
        while (true) {
            // initial pass: the wheel switches tabs instead of scrolling the strip
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (e.type != PointerEventType.Scroll) continue
            val d = e.changes.firstOrNull()?.scrollDelta ?: continue
            val v = if (d.y != 0f) d.y else d.x
            if (v == 0f) continue
            e.changes.forEach { it.consume() }
            val now = System.currentTimeMillis()
            if (now - last < 350) continue
            last = now
            val target = TabGroupOps.neighbour(entries(), current(), if (v > 0f) 1 else -1) ?: continue
            ReaderTabs.tabs.firstOrNull { it.uri == target }?.let(switchTo)
        }
    }
}

/** A group header in the tab strip: colour, name, count; tap collapses / expands, long press opens the groups sheet. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RpfGroupHeader(g: TabGroup, count: Int) {
    val ctx = LocalContext.current
    val c = Color(0xFF000000.toInt() or g.color)
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.22f))
            .combinedClickable(onClick = { RpfTabGroups.update(ctx) { TabGroupOps.toggleCollapsed(it, g.id) } }, onLongClick = { RpfTabGroups.sheetOpen = true })
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c))
        Spacer(Modifier.width(6.dp))
        Text(g.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (g.collapsed) "+$count" else "$count", style = MaterialTheme.typography.labelSmall)
        Icon(if (g.collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, stringResource(if (g.collapsed) R.string.rpf_group_expand else R.string.rpf_group_collapse), Modifier.size(16.dp))
    }
}

/** Colour of the group of [uri] (for the tab's border), or null. */
fun rpfGroupColor(uri: String): Color? = TabGroupOps.groupOf(RpfTabGroups.groups, uri)?.let { Color(0xFF000000.toInt() or it.color) }

/** Tab menu items (hook in the NVE tab menu): new group, add to a group, remove from its group, groups sheet. */
@Composable
fun RpfTabMenuItems(t: DocTab, close: () -> Unit) {
    val ctx = LocalContext.current
    var naming by remember { mutableStateOf(false) }
    HorizontalDivider()
    val groups = RpfTabGroups.groups.filter { !it.closed }
    val mine = TabGroupOps.groupOf(groups, t.uri)
    DropdownMenuItem(text = { Text(stringResource(R.string.rpf_group_new_with)) }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) }, onClick = { naming = true })
    groups.filter { it.id != mine?.id }.forEach { g ->
        DropdownMenuItem(text = { Text(stringResource(R.string.rpf_group_add_to, g.name)) }, leadingIcon = { Icon(Icons.Default.Folder, null) },
            onClick = { close(); RpfTabGroups.update(ctx) { TabGroupOps.assign(it, g.id, t.uri) } })
    }
    if (mine != null) DropdownMenuItem(text = { Text(stringResource(R.string.rpf_group_remove)) }, leadingIcon = { Icon(Icons.Default.FolderOff, null) },
        onClick = { close(); RpfTabGroups.update(ctx) { TabGroupOps.unassign(it, t.uri) } })
    DropdownMenuItem(text = { Text(stringResource(R.string.rpf_groups)) }, leadingIcon = { Icon(Icons.Default.Workspaces, null) }, onClick = { close(); RpfTabGroups.sheetOpen = true })
    if (naming) NameDialog(stringResource(R.string.rpf_group_new), "", onDone = { name ->
        naming = false; close()
        if (name != null) RpfTabGroups.update(ctx) { TabGroupOps.create(it, name, listOf(t.uri)) }
    })
}

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = { onDone(null) }, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.rpf_group_name)) }) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } })
}

/** The tab groups sheet: groups with their documents; collapse, close (keep to restore), restore, rename, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabGroupsSheet(s: ReaderState, switchTo: (DocTab?) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var renaming by remember { mutableStateOf<TabGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    val groups = RpfTabGroups.groups
    val names = ReaderTabs.tabs.associate { it.uri to it.name }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rpf_groups), style = MaterialTheme.typography.titleLarge)
            if (groups.isEmpty()) Text(stringResource(R.string.rpf_groups_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(groups, key = { it.id }) { g ->
                    val c = Color(0xFF000000.toInt() or g.color)
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(c))
                                Spacer(Modifier.width(8.dp))
                                Text(g.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).clickable { renaming = g })
                                if (g.closed) Text(stringResource(R.string.rpf_group_closed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            g.uris.forEach { u ->
                                Text("• " + (names[u] ?: Uri.parse(u).lastPathSegment ?: u), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (g.closed) TextButton(onClick = { onClose(); RpfTabGroups.restoreGroup(ctx, g, switchTo) }) { Text(stringResource(R.string.rpf_group_restore)) }
                                else {
                                    TextButton(onClick = { RpfTabGroups.update(ctx) { TabGroupOps.toggleCollapsed(it, g.id) } }) {
                                        Text(stringResource(if (g.collapsed) R.string.rpf_group_expand else R.string.rpf_group_collapse))
                                    }
                                    TextButton(onClick = { RpfTabGroups.closeGroup(ctx, g, s.fileUri, switchTo) }) { Text(stringResource(R.string.rpf_group_close)) }
                                }
                                TextButton(onClick = { renaming = g }) { Text(stringResource(R.string.rpf_group_rename)) }
                                TextButton(onClick = { RpfTabGroups.update(ctx) { TabGroupOps.delete(it, g.id) } }) { Text(stringResource(R.string.rpf_group_delete)) }
                            }
                        }
                    }
                }
            }
            if (ReaderTabs.tabs.size > 1) OutlinedButton(onClick = { creating = true }) {
                Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.rpf_group_from_open))
            }
            Text(stringResource(R.string.rpf_groups_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    renaming?.let { g -> NameDialog(stringResource(R.string.rpf_group_rename), g.name) { n -> renaming = null; if (n != null) RpfTabGroups.update(ctx) { TabGroupOps.rename(it, g.id, n) } } }
    if (creating) NameDialog(stringResource(R.string.rpf_group_new), "") { n ->
        creating = false
        if (n != null) RpfTabGroups.update(ctx) { TabGroupOps.create(it, n, ReaderTabs.tabs.map { t -> t.uri }) }
    }
}
