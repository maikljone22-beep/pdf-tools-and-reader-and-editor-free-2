package com.smartdocs.editor.ui.reader.annot

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.VideoPdf
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Warn before embedding a video larger than this. */
const val LARGE_VIDEO_BYTES = 200L * 1024 * 1024

/**
 * Video picker, large-file warning, options dialog, save launcher, full-screen player and the lifecycle hooks of
 * the in-page player. Called once from [Annot2Dialogs].
 */
@Composable
fun VideoDialogs(state: ReaderState) {
    val host = state.video
    val h = state.annot2
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- background / leaving the reader: pause, then free the player ----
    @Suppress("DEPRECATION") val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) host.pause() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); host.deactivate() }
    }

    // ---- picker (Video tool) ----
    val failed = stringResource(R.string.vid_import_failed)
    fun import(uri: Uri) {
        val tap = h.pendingTap; h.pendingTap = null
        host.importing = true
        scope.launch {
            val r = MediaIntents.importUri(ctx, uri, h.mediaDir())
            host.importing = false
            if (r == null) { h.message = failed; return@launch }
            host.onPicked(r.first, r.second, tap)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { h.pendingTap = null; return@rememberLauncherForActivityResult }
        scope.launch {
            val size = withContext(Dispatchers.IO) { sizeOf(ctx, uri) }
            if (size > LARGE_VIDEO_BYTES) host.largeWarning = uri to size else import(uri)
        }
    }
    var saveFile by remember { mutableStateOf<File?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/*")) { uri ->
        val f = saveFile; saveFile = null
        if (uri != null && f != null) scope.launch {
            runCatching { MediaIntents.writeTo(ctx, f, uri) }.onSuccess { h.message = ctx.getString(R.string.vid_saved) }.onFailure { state.error = it.message }
        }
    }
    androidx.compose.runtime.SideEffect {
        h.launchVideo = { picker.launch(arrayOf("video/*")) }
        host.launchSave = { name, f -> saveFile = f; saver.launch(name) }
    }

    host.largeWarning?.let { (uri, size) ->
        AlertDialog(
            onDismissRequest = { host.largeWarning = null; h.pendingTap = null },
            title = { Text(stringResource(R.string.vid_large_title)) },
            text = { Text(stringResource(R.string.vid_large_msg, android.text.format.Formatter.formatShortFileSize(ctx, size))) },
            confirmButton = { Button(onClick = { host.largeWarning = null; import(uri) }) { Text(stringResource(R.string.vid_continue)) } },
            dismissButton = { TextButton(onClick = { host.largeWarning = null; h.pendingTap = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (host.importing) com.smartdocs.editor.ui.common.ProgressDialog(stringResource(R.string.vid_preparing))
    if (host.optionsOpen) VideoOptionsDialog(host.selectedOptions(), onOk = { host.applyOptions(it) }, onDismiss = { host.optionsOpen = false })
    if (host.fullscreen && host.active != null) FullscreenPlayer(host)
}

private fun sizeOf(ctx: Context, uri: Uri): Long = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
}.getOrDefault(-1L)

@Composable
private fun VideoOptionsDialog(initial: VideoPdf.Options, onOk: (VideoPdf.Options) -> Unit, onDismiss: () -> Unit) {
    var o by remember { mutableStateOf(initial) }
    @Composable
    fun row(label: Int, checked: Boolean, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { set(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, set); Text(stringResource(label))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vid_options)) },
        text = {
            Column {
                row(R.string.vid_opt_autoplay, o.autoplay) { o = o.copy(autoplay = it) }
                row(R.string.vid_opt_loop, o.loop) { o = o.copy(loop = it) }
                row(R.string.vid_opt_muted, o.muted) { o = o.copy(muted = it) }
                row(R.string.vid_opt_controls, o.controls) { o = o.copy(controls = it) }
            }
        },
        confirmButton = { Button(onClick = { onOk(o) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Full-screen player inside the app (same player: playback continues where it was). Back / the exit button return
 * to the page at the same scroll position. Any orientation; the rotate button forces landscape and back.
 */
@Composable
private fun FullscreenPlayer(host: VideoHost) {
    val ctx = LocalContext.current
    val activity = remember { ctx.activity() }
    val original = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    var landscape by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { activity?.requestedOrientation = original }
    }
    Dialog(
        onDismissRequest = { host.fullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val w = (view.parent as? DialogWindowProvider)?.window
            if (w != null) {
                WindowCompat.setDecorFitsSystemWindows(w, false)
                WindowCompat.getInsetsController(w, view).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose {}
        }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            VideoSurface(host, Modifier.fillMaxSize())
            if (host.loading) CircularProgressIndicator(Modifier.size(40.dp), color = Color.White)
            host.error?.let { Text(it, Modifier.padding(24.dp), color = Color.White) }
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { host.controlsVisible = !host.controlsVisible }, onDoubleTap = { host.fullscreen = false })
            })
            AutoHide(host)
            if (host.controlsVisible) PlayerControls(
                host, compactAllowed = false, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 8.dp, end = 8.dp),
                fullscreenUi = true,
                onRotate = {
                    landscape = !landscape
                    activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                },
            )
        }
    }
}
