package com.smartdocs.editor.ui.view5

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.reader.MAX_ZOOM
import com.smartdocs.editor.ui.reader.MIN_ZOOM
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.flow.MutableStateFlow

/** Hardware keyboard shortcuts of the reader (hook in ReaderScreen's onPreviewKeyEvent). */
object V5Keys {
    private data class K(val key: Int, val ctrl: Boolean, val shift: Boolean, val alt: Boolean, val label: Int)

    private val list = listOf(
        K(KeyEvent.KEYCODE_F, true, false, false, R.string.search),
        K(KeyEvent.KEYCODE_P, true, false, false, R.string.print),
        K(KeyEvent.KEYCODE_S, true, false, false, R.string.save),
        K(KeyEvent.KEYCODE_S, true, true, false, R.string.save_as),
        K(KeyEvent.KEYCODE_G, true, false, false, R.string.go_to_page),
        K(KeyEvent.KEYCODE_Z, true, false, false, R.string.undo),
        K(KeyEvent.KEYCODE_Y, true, false, false, R.string.redo),
        K(KeyEvent.KEYCODE_Z, true, true, false, R.string.redo),       // MODULE_history: Ctrl+Shift+Z = redo too
        K(KeyEvent.KEYCODE_EQUALS, true, false, false, R.string.v5_zoom_in),
        K(KeyEvent.KEYCODE_MINUS, true, false, false, R.string.v5_zoom_out),
        K(KeyEvent.KEYCODE_0, true, false, false, R.string.v5_zoom_reset),
        K(KeyEvent.KEYCODE_DPAD_LEFT, false, false, true, R.string.v5_back_position),
        K(KeyEvent.KEYCODE_DPAD_RIGHT, false, false, true, R.string.v5_forward_position),
        K(KeyEvent.KEYCODE_K, true, false, false, R.string.v5_tell_me),
        K(KeyEvent.KEYCODE_L, true, false, false, R.string.v5_presentation),
        K(KeyEvent.KEYCODE_M, true, false, false, R.string.v5_measure),
        K(KeyEvent.KEYCODE_C, true, true, false, R.string.v5_comment_manager),
        K(KeyEvent.KEYCODE_R, true, true, false, R.string.v5_reading_ruler),
        K(KeyEvent.KEYCODE_4, true, false, false, R.string.v5_reflow),
        K(KeyEvent.KEYCODE_N, true, false, false, R.string.night_mode),
        K(KeyEvent.KEYCODE_SLASH, true, false, false, R.string.v5_keyboard_shortcuts),
        K(KeyEvent.KEYCODE_MOVE_HOME, false, false, false, R.string.v5_first_page),
        K(KeyEvent.KEYCODE_MOVE_END, false, false, false, R.string.v5_last_page),
    )

    /** Handle a key down; true when consumed. */
    fun onKey(s: ReaderState, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val v = V5State.of(s)
        val env = v.env
        val a = env?.actions
        val ctrl = e.isCtrlPressed; val shift = e.isShiftPressed; val alt = e.isAltPressed
        if (e.keyCode == KeyEvent.KEYCODE_F5) { v.open(V5Surface.PRESENT); return true }
        if (e.keyCode == KeyEvent.KEYCODE_ESCAPE) return v.onBack()
        val k = list.firstOrNull { it.key == e.keyCode && it.ctrl == ctrl && it.shift == shift && it.alt == alt } ?: return false
        when (k.label) {
            R.string.search -> a?.search?.invoke()
            R.string.print -> v.open(V5Surface.PRINT)
            R.string.save -> a?.save?.invoke()
            R.string.save_as -> a?.saveAs?.invoke()
            R.string.go_to_page -> a?.goToPage?.invoke()
            R.string.undo -> s.undo()          // unified history (core/history): annotations, form values, file changes
            R.string.redo -> s.redo()
            R.string.v5_zoom_in -> s.zoom = (s.zoom * 1.25f).coerceIn(MIN_ZOOM, MAX_ZOOM)
            R.string.v5_zoom_out -> s.zoom = (s.zoom / 1.25f).coerceIn(MIN_ZOOM, MAX_ZOOM)
            R.string.v5_zoom_reset -> s.zoom = 1f
            R.string.v5_back_position -> v.back()
            R.string.v5_forward_position -> v.forward()
            R.string.v5_tell_me -> v.open(V5Surface.COMMANDS)
            R.string.v5_presentation -> v.open(V5Surface.PRESENT)
            R.string.v5_measure -> { v.workbenchTool = WTool.DISTANCE; v.open(V5Surface.WORKBENCH) }
            R.string.v5_comment_manager -> v.open(V5Surface.COMMENTS)
            R.string.v5_reading_ruler -> v.readingRuler = !v.readingRuler
            R.string.v5_reflow -> s.reflow = !s.reflow
            R.string.night_mode -> s.nightMode = !s.nightMode
            R.string.v5_keyboard_shortcuts -> v.open(V5Surface.SHORTCUTS)
            R.string.v5_first_page -> s.jumpTo(0)
            R.string.v5_last_page -> s.jumpTo(s.pageCount - 1)
            else -> return false
        }
        return true
    }

    private fun meta(k: K) = (if (k.ctrl) KeyEvent.META_CTRL_ON else 0) or (if (k.shift) KeyEvent.META_SHIFT_ON else 0) or (if (k.alt) KeyEvent.META_ALT_ON else 0)

    /** For Activity.onProvideKeyboardShortcuts (Meta+/ helper on Android 7+). */
    fun groups(ctx: Context): List<KeyboardShortcutGroup> {
        val items = list.map { k -> KeyboardShortcutInfo(ctx.getString(k.label), k.key, meta(k)) } + KeyboardShortcutInfo(ctx.getString(R.string.v5_presentation), KeyEvent.KEYCODE_F5, 0)
        return listOf(KeyboardShortcutGroup(ctx.getString(R.string.v5_keyboard_shortcuts), items))
    }

    /** Readable "Ctrl+Shift+S" style labels for the help sheet. */
    fun rows(ctx: Context): List<Pair<String, String>> = list.map { k ->
        val keys = buildList {
            if (k.ctrl) add("Ctrl"); if (k.shift) add("Shift"); if (k.alt) add("Alt")
            add(when (k.key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> "←"; KeyEvent.KEYCODE_DPAD_RIGHT -> "→"; KeyEvent.KEYCODE_EQUALS -> "+"; KeyEvent.KEYCODE_MINUS -> "−"
                KeyEvent.KEYCODE_SLASH -> "/"; KeyEvent.KEYCODE_MOVE_HOME -> "Home"; KeyEvent.KEYCODE_MOVE_END -> "End"
                else -> KeyEvent.keyCodeToString(k.key).removePrefix("KEYCODE_")
            })
        }.joinToString("+")
        ctx.getString(k.label) to keys
    } + (ctx.getString(R.string.v5_presentation) to "F5") + (ctx.getString(R.string.close) to "Esc")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutsSheet(v: V5State) {
    val ctx = LocalContext.current
    val rows = remember { V5Keys.rows(ctx) }
    ModalBottomSheet(onDismissRequest = { v.close() }) {
        Text(stringResource(R.string.v5_keyboard_shortcuts), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(rows) { (label, keys) ->
                ListItem(headlineContent = { Text(label) }, trailingContent = { Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) { Text(keys, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge) } })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * App-icon shortcuts (long-press the launcher icon): last document, files, scan, tools, trash. Published as
 * dynamic shortcuts (no manifest change); MainActivity hands the intent to [handleIntent] and
 * [V5ShellHost] navigates once the NavHost exists.
 */
object V5Shortcuts {
    const val ACTION = "com.smartdocs.editor.action.V5_SHORTCUT"
    const val EXTRA = "v5_target"
    val pending = MutableStateFlow<String?>(null)

    fun publish(activity: Activity) {
        runCatching {
            val ctx = activity.applicationContext
            fun sc(id: String, label: Int, icon: Int) = ShortcutInfoCompat.Builder(ctx, "v5_$id")
                .setShortLabel(ctx.getString(label)).setLongLabel(ctx.getString(label))
                .setIcon(IconCompat.createWithResource(ctx, icon))
                .setIntent(Intent(ctx, activity::class.java).setAction(ACTION).putExtra(EXTRA, id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                .build()
            val list = listOf(
                sc("last", R.string.v5_sc_last, android.R.drawable.ic_menu_recent_history),
                sc("scan", R.string.v5_sc_scan, android.R.drawable.ic_menu_camera),
                sc("files", R.string.v5_sc_files, android.R.drawable.ic_menu_agenda),
                sc("trash", R.string.v5_trash, android.R.drawable.ic_menu_delete),
            )
            ShortcutManagerCompat.setDynamicShortcuts(ctx, list.take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx).coerceAtLeast(1)))
        }
    }

    /** True when the intent was one of our shortcuts (consumed). */
    fun handleIntent(activity: Activity, intent: Intent?): Boolean {
        if (intent?.action != ACTION) return false
        pending.value = intent.getStringExtra(EXTRA) ?: return true
        return true
    }
}

/** Navigates for a pending app-icon shortcut (hook after AppNavHost in MainActivity). */
@Composable
fun V5ShellHost(nav: NavHostController) {
    val target by V5Shortcuts.pending.collectAsState()
    val settings = com.smartdocs.editor.LocalSettings.current
    val ctx = LocalContext.current
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        V5Shortcuts.pending.value = null
        runCatching {
            when (t) {
                "last" -> settings.lastOpenedUri.takeIf { it.isNotBlank() }?.let { nav.navigate(Routes.reader(it)) } ?: nav.navigate(Routes.FILES)
                "scan" -> nav.navigate(Routes.tool("scan"))
                "files" -> nav.navigate(Routes.FILES)
                "trash" -> nav.navigate(V5Routes.TRASH)
            }
        }
        runCatching { Trash5.purge(ctx) }
    }
    LaunchedEffect(Unit) { runCatching { Trash5.purge(ctx) } }
}
