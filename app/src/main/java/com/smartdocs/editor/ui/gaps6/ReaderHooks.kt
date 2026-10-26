package com.smartdocs.editor.ui.gaps6

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.smartdocs.editor.core.data.AppSettings
import com.smartdocs.editor.core.gaps6.ReaderViewPrefs
import com.smartdocs.editor.ui.reader.MAX_ZOOM
import com.smartdocs.editor.ui.reader.MIN_ZOOM
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ViewMode
import com.smartdocs.editor.ui.reader.view.findActivity

/**
 * Reader glue for gaps6 settings + foldables. One call from ReaderScreen (see MODULE_gaps6_r5.md, hook R1):
 * - book posture / dual screen with a vertical separating hinge → two pages side by side (FACING), restored after;
 * - "full-screen top bar" → status bar hidden while the reader is shown;
 * - "remember screen orientation" off → the orientation lock is cleared when the reader closes.
 */
@Composable
fun G6ReaderEffects(state: ReaderState, settings: AppSettings, fold: FoldState = rememberFoldState()) {
    // ---- foldable: two pages across the hinge ----
    val before = remember { mutableStateOf<ViewMode?>(null) }
    LaunchedEffect(fold.isBook, state.session != null) {
        if (state.session == null) return@LaunchedEffect
        if (fold.isBook) {
            if (state.viewMode != ViewMode.FACING && state.viewMode != ViewMode.COVER) {
                before.value = state.viewMode
                state.viewMode = ViewMode.FACING; state.zoom = 1f
            }
        } else before.value?.let { m -> state.viewMode = m; state.zoom = 1f; before.value = null }
    }

    // ---- full screen: hide the status bar ----
    val view = LocalView.current
    val ctx = LocalContext.current
    DisposableEffect(settings.fullScreenReader, view) {
        val window = ctx.findActivity()?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        if (ctl != null && settings.fullScreenReader) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose { if (settings.fullScreenReader) ctl?.show(WindowInsetsCompat.Type.statusBars()) }
    }

    // ---- remember orientation off: forget the lock on close ----
    DisposableEffect(settings.rememberOrientation) {
        onDispose { if (!settings.rememberOrientation) ReaderViewPrefs.setOrientation(ctx, ReaderViewPrefs.Orientation.AUTO) }
    }
}

/**
 * Hardware keys with the gaps6 volume action. Replaces `state.vx.handleKeyCode(code, settings.volumeKeysFlip, rtl)`
 * (hooks R2/R3): with "zoom" the volume keys zoom in (up) / out (down); otherwise unchanged behaviour.
 */
object G6Keys {
    const val ZOOM_STEP = 1.25f

    fun handle(state: ReaderState, code: Int, settings: AppSettings, rtl: Boolean): Boolean {
        val volume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volume && settings.volumeKeysFlip && settings.volumeKeysAction == "zoom") {
            if (state.session == null) return false
            val z = if (code == KeyEvent.KEYCODE_VOLUME_UP) state.zoom * ZOOM_STEP else state.zoom / ZOOM_STEP
            state.zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
            return true
        }
        return state.vx.handleKeyCode(code, settings.volumeKeysFlip, rtl)
    }
}

/**
 * Tabletop posture (horizontal hinge, half open): document in the top half, [panel] in the bottom half, split at
 * the hinge. Without tabletop it just shows [content]. Optional reader hook R4.
 */
@Composable
fun TabletopSplit(fold: FoldState, showPanel: Boolean, content: @Composable () -> Unit, panel: @Composable () -> Unit) {
    val hinge = fold.hinge
    if (!fold.isTabletop || !showPanel || hinge == null) { content(); return }
    val density = LocalDensity.current
    var top by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().onGloballyPositioned { top = it.boundsInWindow().top.toInt() }) {
        val topDp = with(density) { (hinge.top - top).coerceAtLeast(0).toDp() }
        Box(Modifier.fillMaxWidth().height(topDp)) { content() }
        HorizontalDivider()
        Surface(Modifier.fillMaxWidth().weight(1f), tonalElevation = 1.dp) { panel() }
    }
}
