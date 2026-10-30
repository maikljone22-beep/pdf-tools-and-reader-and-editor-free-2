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
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SmartDisplay
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.roundToInt
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
    val importClip: suspend (Uri) -> VideoPdf.Alternate? = { uri ->
        MediaIntents.importUri(ctx, uri, h.mediaDir())?.let { (f, n) -> VideoPdf.Alternate(f.absolutePath, n, VideoPdf.videoMime(n).ifBlank { "video/mp4" }) }
    }
    host.placement?.let { VideoPlacementDialog(onPick = { host.placeChosen(it) }, onDismiss = { host.placement = null; host.picked = it.video; host.message(R.string.vid_tool_hint) }) }
    if (host.optionsOpen) VideoOptionsDialog(host.selectedProps(), importClip, onOk = { host.applyOptions(it) }, onDismiss = { host.optionsOpen = false })
    if (host.fullscreen && host.active != null) FullscreenPlayer(host)
}

private fun sizeOf(ctx: Context, uri: Uri): Long = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
}.getOrDefault(-1L)

/**
 * FINAL_CHECK_2 #15 (§19): video properties in three tabs, as in Acrobat: Properties (title, alt text, locked, poster:
 * a frame of the video / none / an image), Playback (controls, volume, repeat, keep open, full screen) and Actions
 * (start on page open / visible, stop when the page is hidden or closed).
 */
@Composable
private fun VideoOptionsDialog(initial: VideoProps, importClip: suspend (Uri) -> VideoPdf.Alternate?, onOk: (VideoProps) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var o by remember { mutableStateOf(initial.options) }
    var title by remember { mutableStateOf(initial.title) }
    var locked by remember { mutableStateOf(initial.locked) }
    var poster by remember { mutableStateOf(initial.posterPath) }
    var tab by remember { mutableIntStateOf(0) }
    val pickClip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { importClip(uri)?.let { a -> o = o.copy(alternates = o.alternates + a) } }
    }
    val pickPoster = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { posterFromImage(ctx, uri) }
            if (f != null) { poster = f.absolutePath; o = o.copy(poster = VideoPdf.POSTER_FILE) }
        }
    }
    @Composable
    fun row(label: Int, checked: Boolean, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { set(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, set); Text(stringResource(label))
        }
    }
    @Composable
    fun chips(values: List<Pair<Int, Int>>, selected: Int, pick: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { (v, label) -> FilterChip(selected == v, { pick(v) }, label = { Text(stringResource(label)) }) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vid_options)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScrollableTabRow(tab, edgePadding = 0.dp) {
                    listOf(R.string.vid_tab_props, R.string.vid_tab_play, R.string.vid_tab_actions, R.string.vid_tab_renditions).forEachIndexed { i, l ->
                        Tab(tab == i, { tab = i }, text = { Text(stringResource(l), maxLines = 1) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (tab) {
                    0 -> {
                        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.vid_title)) })
                        OutlinedTextField(o.alt, { o = o.copy(alt = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.vid_alt)) })
                        row(R.string.vid_locked, locked) { locked = it }
                        Text(stringResource(R.string.vid_form), style = MaterialTheme.typography.labelLarge)
                        chips(listOf(0 to R.string.vid_form_box, 1 to R.string.vid_form_icon), if (o.icon) 1 else 0) { v -> o = o.copy(icon = v == 1) }
                        Text(stringResource(R.string.vid_poster), style = MaterialTheme.typography.labelLarge)
                        chips(listOf(VideoPdf.POSTER_AUTO to R.string.vid_poster_auto, VideoPdf.POSTER_NONE to R.string.vid_poster_none,
                            VideoPdf.POSTER_FILE to R.string.vid_poster_file), o.poster) { v ->
                            if (v == VideoPdf.POSTER_FILE) runCatching { pickPoster.launch("image/*") } else o = o.copy(poster = v)
                        }
                        if (o.poster == VideoPdf.POSTER_FILE) TextButton(onClick = { runCatching { pickPoster.launch("image/*") } }) { Text(stringResource(R.string.vid_poster_pick)) }
                    }
                    1 -> {
                        row(R.string.vid_opt_controls, o.controls) { o = o.copy(controls = it) }
                        row(R.string.vid_opt_muted, o.muted) { o = o.copy(muted = it) }
                        Text(stringResource(R.string.vid_volume, o.volume))
                        Slider(o.volume.toFloat(), { o = o.copy(volume = it.roundToInt()) }, enabled = !o.muted, valueRange = 0f..100f)
                        row(R.string.vid_opt_loop, o.loop) { o = o.copy(loop = it) }
                        if (!o.loop) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.vid_repeat), Modifier.weight(1f))
                            TextButton(onClick = { o = o.copy(repeat = (o.repeat - 1).coerceAtLeast(1)) }) { Text("−") }
                            Text(o.repeat.toString())
                            TextButton(onClick = { o = o.copy(repeat = (o.repeat + 1).coerceAtMost(VideoPdf.MAX_REPEAT)) }) { Text("+") }
                        }
                        row(R.string.vid_keep_open, o.keepOpen) { o = o.copy(keepOpen = it) }
                        row(R.string.vid_fullscreen, o.fullscreen) { o = o.copy(fullscreen = it) }
                    }
                    3 -> {
                        // renditions editor: the main clip, then alternates tried in order; each with selection criteria
                        Text(stringResource(R.string.vid_rend_hint), style = MaterialTheme.typography.bodySmall)
                        CriteriaEditor(stringResource(R.string.vid_rend_main), o.criteria) { o = o.copy(criteria = it) }
                        o.alternates.forEachIndexed { k, a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${k + 2}. ${a.name}", Modifier.weight(1f), maxLines = 1)
                                TextButton(onClick = { o = o.copy(alternates = o.alternates.toMutableList().apply { add(k - 1, removeAt(k)) }) }, enabled = k > 0) { Text("↑") }
                                TextButton(onClick = { o = o.copy(alternates = o.alternates.toMutableList().apply { add(k + 1, removeAt(k)) }) }, enabled = k < o.alternates.lastIndex) { Text("↓") }
                                TextButton(onClick = { o = o.copy(alternates = o.alternates.filterIndexed { j, _ -> j != k }) }) { Text("✕") }
                            }
                            CriteriaEditor(null, a.criteria) { c -> o = o.copy(alternates = o.alternates.mapIndexed { j, x -> if (j == k) x.copy(criteria = c) else x }) }
                        }
                        TextButton(onClick = { runCatching { pickClip.launch(arrayOf("video/*")) } }) { Text(stringResource(R.string.vid_rend_add)) }
                    }
                    else -> {
                        row(R.string.vid_opt_autoplay, o.autoplay) { o = o.copy(autoplay = it) }
                        if (o.autoplay) chips(listOf(VideoPdf.START_VISIBLE to R.string.vid_start_visible, VideoPdf.START_OPEN to R.string.vid_start_open), o.startOn) { o = o.copy(startOn = it) }
                        row(R.string.vid_stop_hidden, o.stopWhenHidden) { o = o.copy(stopWhenHidden = it) }
                        Text(stringResource(R.string.vid_actions_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onOk(VideoProps(o, title, locked, poster)) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Selection criteria of one clip: minimum bandwidth (kbit/s), languages (comma separated), minimum screen size. */
@Composable
private fun CriteriaEditor(title: String?, c: VideoPdf.Criteria, onChange: (VideoPdf.Criteria) -> Unit) {
    fun num(s: String) = s.filter { it.isDigit() }.take(7).toIntOrNull() ?: 0
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(if (c.bitrate > 0) (c.bitrate / 1000).toString() else "", { onChange(c.copy(bitrate = num(it) * 1000)) }, Modifier.weight(1f),
                singleLine = true, label = { Text(stringResource(R.string.vid_rend_kbps)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            OutlinedTextField(c.languages.joinToString(", "), { s -> onChange(c.copy(languages = s.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() })) }, Modifier.weight(1f),
                singleLine = true, label = { Text(stringResource(R.string.vid_rend_lang)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(if (c.minWidth > 0) c.minWidth.toString() else "", { onChange(c.copy(minWidth = num(it))) }, Modifier.weight(1f), singleLine = true,
                label = { Text(stringResource(R.string.vid_rend_min_w)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            OutlinedTextField(if (c.minHeight > 0) c.minHeight.toString() else "", { onChange(c.copy(minHeight = num(it))) }, Modifier.weight(1f), singleLine = true,
                label = { Text(stringResource(R.string.vid_rend_min_h)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        }
    }
}

/** A picked image as a baseline JPEG poster (at most 1280 px) in the cache, or null. */
private fun posterFromImage(ctx: Context, uri: Uri): File? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    try {
        File(File(ctx.cacheDir, "video_posters").apply { mkdirs() }, "poster_${System.nanoTime()}.jpg").also { f ->
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        }
    } finally { bmp.recycle() }
}.getOrNull()

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Full-screen player inside the app (same player: playback continues where it was). Back / the exit button return
 * to the page at the same scroll position. Any orientation; the rotate button forces landscape and back.
 */
/** After a video was picked and its place tapped: an in-page player box, or an icon that opens the player. */
@Composable
private fun VideoPlacementDialog(onPick: (icon: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vid_place_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlacementChoice(Icons.Default.SmartDisplay, R.string.vid_form_box, R.string.vid_form_box_sub) { onPick(false) }
                PlacementChoice(Icons.Default.Movie, R.string.vid_form_icon, R.string.vid_form_icon_sub) { onPick(true) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PlacementChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, sub: Int, onClick: () -> Unit) {
    Card(onClick = onClick, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column { Text(stringResource(title), style = MaterialTheme.typography.titleSmall); Text(stringResource(sub), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

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
        onDismissRequest = { host.exitFullscreen() },
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
                detectTapGestures(onTap = { host.controlsVisible = !host.controlsVisible }, onDoubleTap = { host.exitFullscreen() })
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
