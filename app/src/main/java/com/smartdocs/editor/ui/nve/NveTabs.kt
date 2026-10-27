package com.smartdocs.editor.ui.nve

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.view.DocTab
import com.smartdocs.editor.ui.reader.view.ReaderTabs

/** Document tab extras: menu (close others / left / right / all, pin, new window, copy path), pinning. */
object NveTabs {
    private const val PREFS = "nve_tabs"
    var menuFor by mutableStateOf<String?>(null)
    var pinVersion by mutableIntStateOf(0)

    fun isPinned(ctx: Context, uri: String): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("pinned", emptySet())!!.contains(uri)
    fun setPinned(ctx: Context, uri: String, on: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("pinned", emptySet())!!.toMutableSet()
        if (on) set.add(uri) else set.remove(uri)
        p.edit().putStringSet("pinned", set).apply()
        // pinned tabs go first
        val tabs = ReaderTabs.tabs.toList()
        val ordered = tabs.filter { isPinned(ctx, it.uri) } + tabs.filter { !isPinned(ctx, it.uri) }
        if (ordered != tabs) { ReaderTabs.tabs.clear(); ReaderTabs.tabs.addAll(ordered) }
        pinVersion++
    }

    /** Close tabs (pinned ones survive "others" / "all"); [current] is the shown document. */
    fun close(ctx: Context, which: String, anchor: DocTab, current: String, switchTo: (DocTab?) -> Unit) {
        val tabs = ReaderTabs.tabs.toList()
        val i = tabs.indexOfFirst { it.uri == anchor.uri }
        val keep: (DocTab) -> Boolean = when (which) {
            "others" -> { t -> t.uri == anchor.uri || isPinned(ctx, t.uri) }
            "left" -> { t -> tabs.indexOf(t) >= i || isPinned(ctx, t.uri) }
            "right" -> { t -> tabs.indexOf(t) <= i || isPinned(ctx, t.uri) }
            else -> { t -> isPinned(ctx, t.uri) }
        }
        val currentKept = tabs.firstOrNull { it.uri == current }?.let(keep) ?: false
        val next = if (currentKept) current else if (keep(anchor)) anchor.uri else tabs.firstOrNull(keep)?.uri
        ReaderTabs.retain(ctx, next, keep)
        if (!currentKept) switchTo(ReaderTabs.tabs.firstOrNull { it.uri == next })
    }

    /** Tap = [onClick]; long press or right click = tab menu. */
    @OptIn(ExperimentalFoundationApi::class)
    fun menuModifier(t: DocTab, onClick: () -> Unit): Modifier =
        Modifier.combinedClickable(onClick = onClick, onLongClick = { menuFor = t.uri })
            .pointerInput(t.uri) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) { menuFor = t.uri; e.changes.forEach { it.consume() } }
                    }
                }
            }
}

/** The tab menu anchored on one tab of the strip (hook in ReaderTabStrip). */
@Composable
fun NveTabMenu(t: DocTab, state: ReaderState, switchTo: (DocTab?) -> Unit) {
    if (NveTabs.menuFor != t.uri) return
    DropdownMenu(true, { NveTabs.menuFor = null }) { TabMenuItems(t, state, switchTo) { NveTabs.menuFor = null } }
}

@Composable
private fun TabMenuItems(t: DocTab, state: ReaderState, switchTo: (DocTab?) -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current
    val current = state.fileUri
    @Suppress("UNUSED_VARIABLE") val pv = NveTabs.pinVersion
    val pinned = NveTabs.isPinned(ctx, t.uri)
    DropdownMenuItem(text = { Text(stringResource(if (pinned) R.string.nv_tab_unpin else R.string.nv_tab_pin)) }, leadingIcon = { Icon(Icons.Default.PushPin, null) },
        onClick = { close(); NveTabs.setPinned(ctx, t.uri, !pinned) })
    DropdownMenuItem(text = { Text(stringResource(R.string.vw_close_tab)) }, leadingIcon = { Icon(Icons.Default.Close, null) },
        onClick = { close(); val next = ReaderTabs.close(ctx, t.uri); if (t.uri == current) switchTo(next) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_tab_close_others)) }, onClick = { close(); NveTabs.close(ctx, "others", t, current, switchTo) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_tab_close_left)) }, onClick = { close(); NveTabs.close(ctx, "left", t, current, switchTo) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_tab_close_right)) }, onClick = { close(); NveTabs.close(ctx, "right", t, current, switchTo) })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_tab_close_all)) }, leadingIcon = { Icon(Icons.Default.ClearAll, null) }, onClick = { close(); NveTabs.close(ctx, "all", t, current, switchTo) })
    HorizontalDivider()
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_tab_new_window)) }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = {
        close()
        android.net.Uri.parse(t.uri).path?.let { p -> com.smartdocs.editor.ui.nve.shell.NveWindows.openInNewWindow(ctx, java.io.File(p)) }
    })
    DropdownMenuItem(text = { Text(stringResource(R.string.nv_tab_copy_path)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
        close(); com.smartdocs.editor.ui.reader.ReaderIntents.copy(ctx, android.net.Uri.parse(t.uri).path ?: t.uri, "path"); NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_copied))
    })
}

/** Items for the phone tab dropdown (hook in ReaderTabsAction): the menu of the current tab. */
@Composable
fun NveTabsMenuItems(state: ReaderState, switchTo: (DocTab?) -> Unit, close: () -> Unit) {
    val cur = ReaderTabs.tabs.firstOrNull { it.uri == state.fileUri } ?: return
    HorizontalDivider()
    TabMenuItems(cur, state, switchTo, close)
}
