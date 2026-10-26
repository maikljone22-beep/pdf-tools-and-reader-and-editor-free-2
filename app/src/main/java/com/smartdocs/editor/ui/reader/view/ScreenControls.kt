package com.smartdocs.editor.ui.reader.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.AppSettings
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Hardware-key hook for the activity. The reader registers a handler while it is shown; MainActivity should
 * forward unhandled keys: `override fun onKeyDown(c: Int, e: KeyEvent) = ReaderKeyBus.handle(c) || super.onKeyDown(c, e)`.
 * (Compose only sees keys while the page area has focus; this catches volume keys in every case.)
 */
object ReaderKeyBus {
    @Volatile var handler: ((Int) -> Boolean)? = null
    fun handle(keyCode: Int): Boolean = handler?.invoke(keyCode) == true
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Records touches anywhere in the reader (never consumes) so the keep-screen-on timeout restarts on interaction. */
fun Modifier.readerTouchWatcher(state: ReaderState): Modifier = this.then(Modifier.pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            val now = SystemClock.uptimeMillis()
            if (now - state.vx.lastTouch > 1000L) state.vx.lastTouch = now
        }
    }
})

/**
 * Platform side of the view options, called once from the reader screen:
 * - keep screen on with a timeout that restarts on every touch (the reference counts from resume only),
 *   always on during auto-flip;
 * - orientation lock (applied while the reader is shown, released on exit);
 * - hardware keys via [ReaderKeyBus];
 * - multi-document tabs bookkeeping and per-document crop restore.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReaderViewEffects(state: ReaderState, settings: AppSettings, rtl: Boolean) {
    val view = LocalView.current
    val ctx = LocalContext.current
    val vx = state.vx

    // ---- keep screen on ----
    val flipping = state.autoFlipSeconds > 0
    LaunchedEffect(settings.keepScreenOn, flipping, vx.screenTimeoutSec, vx.lastTouch) {
        if (flipping) { view.keepScreenOn = true; return@LaunchedEffect }
        if (!settings.keepScreenOn) { view.keepScreenOn = false; return@LaunchedEffect }
        val timeout = vx.screenTimeoutSec
        if (timeout < 0) { view.keepScreenOn = true; return@LaunchedEffect }
        // the system timeout still runs after the flag is cleared: hold it only for the difference
        val sys = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(30_000).toLong()
        val hold = timeout * 1000L - sys
        if (hold <= 0) { view.keepScreenOn = false; return@LaunchedEffect }
        view.keepScreenOn = true
        delay(hold)
        view.keepScreenOn = false
    }
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }

    // ---- orientation lock ----
    val activity = remember(ctx) { ctx.findActivity() }
    LaunchedEffect(vx.orientationLock, vx.lockedLandscape, activity) {
        activity?.requestedOrientation = vx.requestedOrientation()
    }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // ---- hardware keys ----
    val volume = settings.volumeKeysFlip
    DisposableEffect(state, volume, rtl, settings.volumeKeysAction) {
        val h: (Int) -> Boolean = { code -> com.smartdocs.editor.ui.gaps6.G6Keys.handle(state, code, settings, rtl) }
        ReaderKeyBus.handler = h
        onDispose { if (ReaderKeyBus.handler === h) ReaderKeyBus.handler = null }
    }

    // ---- tabs + per-document view state ----
    val file = state.file
    LaunchedEffect(file, state.session != null) {
        if (file == null || state.session == null) return@LaunchedEffect
        val uri = state.fileUri
        val tab = ReaderTabs.activate(ctx, uri, file.name)
        vx.crop.restore(file.absolutePath)
        // a tab switch restores the zoom the document had in its tab
        if (tab != null && settings.keepZoom && tab.zoom > 1.01f) state.zoom = tab.zoom
    }
    LaunchedEffect(file) {
        if (file == null) return@LaunchedEffect
        val uri = state.fileUri
        snapshotFlow { state.currentPage to state.zoom }.distinctUntilChanged().debounce(400).collect { (p, z) ->
            if (state.session != null) ReaderTabs.update(ctx, uri, p, z)
        }
    }
    // restore-last-session flag: set while the app is in the background with a document open
    @Suppress("DEPRECATION") val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true) ReaderTabs.setInteract(ctx, true)
                Lifecycle.Event.ON_START -> ReaderTabs.setInteract(ctx, false)
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (activity?.isChangingConfigurations != true) ReaderTabs.setInteract(ctx, false)
        }
    }
}

/** Current orientation as "landscape?" for the orientation lock toggle. */
fun Context.isLandscape(): Boolean = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * Floating auto-flip control (the reference's bottom bar): faster (−10 s), circular progress with the
 * interval, slower (+10 s) and stop.
 */
@Composable
fun AutoFlipBar(state: ReaderState, modifier: Modifier = Modifier) {
    val vx = state.vx
    val sec = state.autoFlipSeconds
    if (sec <= 0) return
    fun set(v: Int) { vx.updateAutoFlipInterval(v); state.autoFlipSeconds = vx.autoFlipInterval }
    Surface(modifier, shape = RoundedCornerShape(28.dp), tonalElevation = 4.dp, shadowElevation = 8.dp) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { set(sec - ViewExtras.FLIP_STEP) }, enabled = sec > ViewExtras.FLIP_MIN) {
                Icon(Icons.Default.Add, stringResource(R.string.vw_flip_faster))
            }
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { vx.autoFlipProgress }, modifier = Modifier.fillMaxSize(), strokeWidth = 3.dp)
                Text(stringResource(R.string.vw_seconds_short, sec), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { set(sec + ViewExtras.FLIP_STEP) }, enabled = sec < ViewExtras.FLIP_MAX) {
                Icon(Icons.Default.Remove, stringResource(R.string.vw_flip_slower))
            }
            IconButton(onClick = { state.autoFlipSeconds = 0; vx.autoFlipProgress = 0f }) {
                Icon(Icons.Default.Close, stringResource(R.string.vw_stop))
            }
        }
    }
}

/** Drives the auto-flip timer: progress restarts on every page change and keeps its fraction when the speed changes. */
@Composable
fun AutoFlipEffect(state: ReaderState) {
    val vx = state.vx
    val active = state.autoFlipSeconds > 0
    LaunchedEffect(active, state.currentPage, state.viewMode, state.pageCount) {
        vx.autoFlipProgress = 0f
        if (!active) return@LaunchedEffect
        if (!vx.canFlip(1)) return@LaunchedEffect          // last screen: the indicator stays empty (as the reference)
        var last = SystemClock.uptimeMillis()
        while (vx.autoFlipProgress < 1f) {
            delay(100)
            val now = SystemClock.uptimeMillis()
            val total = (state.autoFlipSeconds.coerceAtLeast(1) * 1000f)
            vx.autoFlipProgress = (vx.autoFlipProgress + (now - last) / total).coerceAtMost(1f)
            last = now
            if (state.autoFlipSeconds <= 0) return@LaunchedEffect
        }
        vx.flip(1)
    }
}
