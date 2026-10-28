package com.smartdocs.editor.ui.reader.view

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.smartdocs.editor.ui.onboarding.CoachIds
import com.smartdocs.editor.ui.onboarding.coachTarget
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One document tab: its file (file:// uri), name, last page and zoom. Only the active tab holds a PdfSession. */
data class DocTab(val uri: String, val name: String, val page: Int = 0, val zoom: Float = 1f)

/**
 * Multi-document tabs (reference: up to 5, FIFO, only one document loaded; switching closes the current one and
 * opens the other with its reading state). Persisted in SharedPreferences "tabs".
 */
object ReaderTabs {
    /** nav-prefs (SHL-7): the tab limit is a preference (5–10). */
    val MAX: Int get() = com.smartdocs.editor.core.data.NpfPrefs.tabLimit
    private const val PREFS = "tabs"
    val tabs = mutableStateListOf<DocTab>()
    var active by mutableStateOf<String?>(null); private set
    private var loaded = false
    /** Set once per process so a restore never fires again after the activity is recreated. */
    @Volatile private var restoreChecked = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        val list = runCatching {
            val arr = JSONArray(p.getString("list", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DocTab(o.getString("uri"), o.optString("name"), o.optInt("page", 0), o.optDouble("zoom", 1.0).toFloat())
            }
        }.getOrDefault(emptyList())
        tabs.clear(); tabs += list.filter { usable(it.uri) }.take(MAX)
        active = p.getString("active", null)?.takeIf { a -> tabs.any { it.uri == a } }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        tabs.forEach { t -> arr.put(JSONObject().put("uri", t.uri).put("name", t.name).put("page", t.page).put("zoom", t.zoom.toDouble())) }
        prefs(ctx).edit().putString("list", arr.toString()).putString("active", active).apply()
    }

    /** Only real local files outside the cache are worth a tab. */
    private fun usable(uri: String): Boolean {
        val u = Uri.parse(uri)
        if (u.scheme != "file") return false
        val path = u.path ?: return false
        return !path.contains("/cache/") && File(path).exists()
    }

    /**
     * Mark [uri] as the open document, adding a tab if needed (the oldest other tab is dropped beyond [MAX]).
     * Returns the tab as it was before (null when it is new), so the caller can restore its zoom.
     */
    fun activate(ctx: Context, uri: String, name: String): DocTab? {
        load(ctx)
        if (!usable(uri)) return null
        val i = tabs.indexOfFirst { it.uri == uri }
        val before = tabs.getOrNull(i)
        if (i < 0) {
            tabs += DocTab(uri, name)
            while (tabs.size > MAX) { val drop = tabs.indexOfFirst { it.uri != uri && !com.smartdocs.editor.ui.nve.NveTabs.isPinned(ctx, it.uri) }.let { d -> if (d < 0) tabs.indexOfFirst { it.uri != uri } else d }; if (drop < 0) break; tabs.removeAt(drop) }   // NVE: pinned tabs stay
        } else if (tabs[i].name != name) tabs[i] = tabs[i].copy(name = name)
        active = uri
        save(ctx)
        return before
    }

    fun update(ctx: Context, uri: String, page: Int, zoom: Float) {
        load(ctx)
        val i = tabs.indexOfFirst { it.uri == uri }; if (i < 0) return
        if (tabs[i].page == page && tabs[i].zoom == zoom) return
        tabs[i] = tabs[i].copy(page = page, zoom = zoom)
        save(ctx)
    }

    /** Remove the tab of [uri]; returns the tab to show next when it was the active one (null = none left / not active). */
    fun close(ctx: Context, uri: String): DocTab? {
        load(ctx)
        val i = tabs.indexOfFirst { it.uri == uri }; if (i < 0) return null
        val wasActive = active == uri
        tabs.removeAt(i)
        com.smartdocs.editor.ui.rpf.RpfTabGroups.onClosed(ctx, uri)      // reader-prefs: the tab leaves its group
        val next = if (wasActive) tabs.getOrNull(i) ?: tabs.getOrNull(i - 1) else null
        if (wasActive) active = next?.uri
        save(ctx)
        return next
    }

    /** reader-prefs: reopen tabs (a restored tab group) without changing the active one; the oldest go beyond [MAX]. */
    fun addTabs(ctx: Context, list: List<DocTab>) {
        load(ctx)
        list.filter { t -> usable(t.uri) && tabs.none { it.uri == t.uri } }.forEach { tabs += it }
        while (tabs.size > MAX) { val drop = tabs.indexOfFirst { it.uri != active && list.none { l -> l.uri == it.uri } }.let { d -> if (d < 0) tabs.indexOfFirst { it.uri != active } else d }; if (drop < 0) break; tabs.removeAt(drop) }
        save(ctx)
    }

    fun clear(ctx: Context) { load(ctx); tabs.clear(); active = null; save(ctx) }

    /** NVE: keep only the tabs matching [keep] (close others / left / right / all); the active one moves to [newActive]. */
    fun retain(ctx: Context, newActive: String?, keep: (DocTab) -> Boolean) {
        load(ctx); tabs.retainAll(keep); active = newActive?.takeIf { a -> tabs.any { it.uri == a } } ?: tabs.firstOrNull()?.uri; save(ctx)
    }

    fun setInteract(ctx: Context, open: Boolean) { prefs(ctx).edit().putBoolean("interact", open).apply() }

    /** Tab to reopen at launch: the app was stopped (and killed) while a document was open. */
    fun restoreTarget(ctx: Context): DocTab? {
        if (restoreChecked) return null
        restoreChecked = true
        load(ctx)
        if (!prefs(ctx).getBoolean("interact", false)) return null
        return tabs.firstOrNull { it.uri == active }?.takeIf { usable(it.uri) }
    }
}

/**
 * Switch the reader to another document (null = leave the reader). Pending edits are saved first (better than
 * the reference's save/discard prompt); the current reader is replaced so only one PdfSession stays open.
 */
@Composable
fun rememberTabSwitcher(nav: NavHostController, state: ReaderState, onSaveFailed: (DocTab?) -> Unit = {}): (DocTab?) -> Unit {
    val scope = rememberCoroutineScope()
    val failed by rememberUpdatedState(onSaveFailed)
    return remember(nav, state) {
        { target ->
            scope.launch {
                // tab-view-core V-02: a failed save no longer traps the user: the screen offers save a copy / discard
                if (state.dirty && !state.discarded && !state.save()) { failed(target); return@launch }
                if (target == null) { nav.popBackStack(); return@launch }
                if (target.uri == state.fileUri) return@launch
                nav.navigate(Routes.reader(target.uri, target.page)) {
                    popUpTo(Routes.READER) { inclusive = true }
                    launchSingleTop = false
                }
            }
        }
    }
}

/**
 * Tablet: horizontal tab strip under the top bar (current tab tinted, × closes, + opens a file).
 * Shown when more than one document is open, or always on tablets.
 */
@Composable
fun ReaderTabStrip(state: ReaderState, switchTo: (DocTab?) -> Unit, alwaysShow: Boolean) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ReaderTabs.load(ctx) }
    val tabs = ReaderTabs.tabs
    if (tabs.size < 2 && !alwaysShow) return
    val pick = rememberPdfPicker { files -> files.firstOrNull()?.let { switchTo(DocTab(Uri.fromFile(it).toString(), it.name, page = -1)) } }
    val current = state.fileUri
    // reader-prefs (OLD SHL-5): group headers + members (collapsed groups hide theirs); mouse wheel switches tabs
    val entries = com.smartdocs.editor.core.rpf.TabGroupOps.stripEntries(com.smartdocs.editor.ui.rpf.RpfTabGroups.groups, tabs.map { it.uri }, current)
    val entriesNow by rememberUpdatedState(entries)
    Surface(Modifier.coachTarget(CoachIds.READER_DOC_TABS), tonalElevation = 1.dp) {
        LazyRow(
            Modifier.fillMaxWidth().height(40.dp).then(com.smartdocs.editor.ui.rpf.rpfWheelTabs({ entriesNow }, { state.fileUri }, switchTo)),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(entries, key = { e -> when (e) { is com.smartdocs.editor.core.rpf.StripEntry.Header -> "group:" + e.group.id; is com.smartdocs.editor.core.rpf.StripEntry.Tab -> e.uri } }) { e ->
                if (e is com.smartdocs.editor.core.rpf.StripEntry.Header) { com.smartdocs.editor.ui.rpf.RpfGroupHeader(e.group, e.count); return@items }
                val t = tabs.firstOrNull { it.uri == (e as com.smartdocs.editor.core.rpf.StripEntry.Tab).uri } ?: return@items
                val sel = t.uri == current
                val groupColor = com.smartdocs.editor.ui.rpf.rpfGroupColor(t.uri)
                Row(
                    Modifier.widthIn(max = 200.dp).height(32.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .border(if (groupColor != null) 2.dp else 1.dp, groupColor ?: if (sel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .then(com.smartdocs.editor.ui.nve.NveTabs.menuModifier(t) { switchTo(t) })   // NVE: tap switches, long press / right click opens the tab menu
                        .padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (com.smartdocs.editor.ui.nve.NveTabs.isPinned(ctx, t.uri)) Icons.Default.PushPin else Icons.Default.PictureAsPdf, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    com.smartdocs.editor.ui.nve.NveTabMenu(t, state, switchTo)
                    Text(t.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    IconButton(onClick = { closeTab(ctx, t, current, switchTo) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.vw_close_tab), Modifier.size(16.dp))
                    }
                }
            }
            item {
                IconButton(onClick = { pick() }, modifier = Modifier.size(32.dp), enabled = true) {
                    Icon(Icons.Default.Add, stringResource(R.string.vw_open_another))
                }
            }
        }
    }
}

private fun closeTab(ctx: Context, t: DocTab, current: String, switchTo: (DocTab?) -> Unit) {
    val next = ReaderTabs.close(ctx, t.uri)
    if (t.uri == current) switchTo(next)      // closing the shown document opens its neighbour, or leaves
}

/** Phone: top-bar icon with the number of open documents; opens the "recently viewed files" list. */
@Composable
fun ReaderTabsAction(state: ReaderState, switchTo: (DocTab?) -> Unit, showIcon: Boolean = true, externalOpen: Boolean = false, onExternalClose: () -> Unit = {}) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ReaderTabs.load(ctx) }
    // tab-view-core V-03: without room in the top bar the icon is hidden and the ⋮ menu opens this menu ([externalOpen])
    var open by remember { mutableStateOf(false) }
    fun closeMenu() { open = false; onExternalClose() }
    val pick = rememberPdfPicker { files -> files.firstOrNull()?.let { switchTo(DocTab(Uri.fromFile(it).toString(), it.name, page = -1)) } }
    val tabs = ReaderTabs.tabs
    val current = state.fileUri
    Box(Modifier.coachTarget(CoachIds.READER_DOC_TABS)) {
        if (showIcon) IconButton(onClick = { open = true }) {
            BadgedBox(badge = { if (tabs.size > 1) Badge { Text(tabs.size.toString()) } }) { Icon(Icons.Default.Tab, stringResource(R.string.vw_tabs)) }
        }
        DropdownMenu(open || externalOpen, { closeMenu() }) {
            Text(stringResource(R.string.vw_tabs_recent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            tabs.toList().forEachIndexed { i, t ->
                val sel = t.uri == current
                DropdownMenuItem(
                    text = { Text("${i + 1}. ${t.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { if (sel) Icon(Icons.Default.Check, null) else Icon(Icons.Default.PictureAsPdf, null) },
                    trailingIcon = { IconButton(onClick = { closeMenu(); closeTab(ctx, t, current, switchTo) }) { Icon(Icons.Default.Close, stringResource(R.string.vw_close_tab)) } },
                    onClick = { closeMenu(); if (!sel) switchTo(t) },
                )
            }
            HorizontalDivider()
            // nav-prefs (SHL-7): close the other tabs / all tabs
            if (tabs.size > 1) DropdownMenuItem(text = { Text(stringResource(R.string.npf_tabs_close_others)) }, leadingIcon = { Icon(Icons.Default.Close, null) },
                onClick = { closeMenu(); tabs.toList().filter { it.uri != current }.forEach { ReaderTabs.close(ctx, it.uri) } })
            if (tabs.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.npf_tabs_close_all)) }, leadingIcon = { Icon(Icons.Default.Close, null) },
                onClick = { closeMenu(); tabs.toList().filter { it.uri != current }.forEach { ReaderTabs.close(ctx, it.uri) }; tabs.firstOrNull { it.uri == current }?.let { closeTab(ctx, it, current, switchTo) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.vw_open_another)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { closeMenu(); pick() })
            com.smartdocs.editor.ui.nve.NveTabsMenuItems(state, switchTo) { closeMenu() }   // NVE: close others / all, pin, new window, copy path
        }
    }
}

/**
 * "Restore previous session": put once in the nav host (after the NavHost). When the setting is on, the app was
 * not opened by an external intent ([enabled]), and the previous run was stopped with a document open, the active
 * tab is reopened 100 ms after start (as the reference). All tabs come back with it.
 */
@Composable
fun RestoreLastSession(nav: NavHostController, enabled: Boolean = true) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (!enabled) return@LaunchedEffect
        val target = ReaderTabs.restoreTarget(ctx) ?: return@LaunchedEffect
        val restore = runCatching { app.settings.settings.first().restoreSession }.getOrDefault(false)
        if (!restore) return@LaunchedEffect
        delay(100)
        if (nav.currentDestination?.route == Routes.READER) return@LaunchedEffect
        runCatching { nav.navigate(Routes.reader(target.uri, target.page)) }
    }
}
