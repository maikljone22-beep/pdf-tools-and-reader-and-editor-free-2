package com.smartdocs.editor.ui.reader.annot

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.reader.AnnotTarget
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The videos of one page, played inside the page (MODULE inline video). Must be called inside the page's Box
 * (layout space of [g]); it moves, zooms, rotates and is clipped with the page because it is part of it.
 * [visibleClip] is the visible part of the page in layout fractions (null when off screen).
 */
@Composable
fun InPageVideoLayer(state: ReaderState, page: Int, g: PageGeometry, visibleClip: () -> Rect?) {
    val host = state.video
    val items = host.items(page)
    if (items.isEmpty()) return
    val layout = g.layoutSize
    items.forEach { item ->
        key(item.key) {
            val r = g.pdfRectToLayout(item.rect)
            val activeNow = host.activeKey == item.key
            // a small icon (old attachment form) plays in a comfortable box anchored at it
            val box = if (activeNow && (item.rect.width() < 60f || item.rect.top - item.rect.bottom < 40f)) expanded(r, layout.width, layout.height) else r
            val boxNow by rememberUpdatedState(box)
            val itemNow by rememberUpdatedState(item)
            LaunchedEffect(item.key) {
                snapshotFlow { visibleClip()?.let { c -> overlaps(c, boxNow, layout.width, layout.height) } ?: false }
                    .distinctUntilChanged()
                    .collect { v -> host.onVisibility(itemNow, v) }
            }
            DisposableEffect(item.key) { onDispose { if (host.activeKey == item.key && !host.fullscreen) host.deactivate() } }
            VideoBox(state, item, box, activeNow)
        }
    }
}

private fun expanded(r: Rect, lw: Float, lh: Float): Rect {
    val w = min(lw * 0.8f, max(r.width, lw * 0.5f)); val h = w * 9f / 16f
    val l = (r.left).coerceIn(0f, max(0f, lw - w)); val t = (r.top).coerceIn(0f, max(0f, lh - h))
    return Rect(l, t, l + w, t + h)
}

/** Whether [box] (layout px) intersects the visible fraction [c] of the page. */
private fun overlaps(c: Rect, box: Rect, lw: Float, lh: Float): Boolean {
    if (lw <= 0f || lh <= 0f) return false
    val v = Rect(c.left * lw, c.top * lh, c.right * lw, c.bottom * lh)
    return box.left < v.right && box.right > v.left && box.top < v.bottom && box.bottom > v.top
}

@Composable
private fun VideoBox(state: ReaderState, item: VideoItem, box: Rect, active: Boolean) {
    val host = state.video
    val density = LocalDensity.current
    val wDp = with(density) { box.width.coerceAtLeast(1f).toDp() }; val hDp = with(density) { box.height.coerceAtLeast(1f).toDp() }
    val selected = when (val t = state.edit.target) {
        is AnnotTarget.Pending -> t.id == item.pendingId
        is AnnotTarget.Stored -> t.e == item.stored
        null -> false
    }
    // touches belong to the video only in reading mode; a tool, a placement or the selection frame keep them
    val interactive = state.tool == null && state.placement == null && !selected
    val desc = stringResource(R.string.vid_desc, item.name)
    Box(
        Modifier.offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }.size(wDp, hDp).clipToBounds()
            .semantics { contentDescription = desc },
    ) {
        if (!active) {
            if (!selected) IdlePoster(item)
            if (interactive) Box(Modifier.fillMaxSize().pointerInput(item.key) {
                detectTapGestures(
                    onTap = { host.activate(item) },
                    onLongPress = {
                        // select it for move / resize / options (same as long-pressing any annotation)
                        val pid = item.pendingId
                        if (pid != null) state.annots.firstOrNull { it.id == pid }?.let { state.edit.selectPending(it) }
                        else item.stored?.let { state.edit.selectStored(it) }
                    },
                )
            })
        } else ActivePlayer(state, item, interactive)
    }
}

/** Poster of a pending video (the page bitmap does not have it yet), or a placeholder for videos without a picture. */
@Composable
private fun IdlePoster(item: VideoItem) {
    if (item.drawnByPage && item.supported) return
    val bmp by produceState<android.graphics.Bitmap?>(null, item.posterPath) {
        value = item.posterPath?.let { p -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(p) }.getOrNull() } }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        Canvas(Modifier.fillMaxSize()) { drawPlayButton(Rect(0f, 0f, size.width, size.height)) }
        if (!item.supported) Text(
            stringResource(if (item.mime.contains("flash")) R.string.vid_unsupported_flash else R.string.vid_no_file),
            Modifier.align(Alignment.BottomCenter).padding(4.dp), color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The player in its box: picture, loading / error, and the controls. */
@Composable
private fun ActivePlayer(state: ReaderState, item: VideoItem, interactive: Boolean) {
    val host = state.video
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (!host.fullscreen) VideoSurface(host, Modifier.fillMaxSize())
        else Icon(Icons.Default.Fullscreen, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
        if (host.loading) CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
        host.error?.let { e ->
            Text(e, Modifier.padding(8.dp), color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        val showBar = item.options.controls || !host.isPlaying
        // taps: show the controls (or play / pause when controls are off); double tap: full screen
        Box(Modifier.fillMaxSize().pointerInput(item.key, interactive) {
            if (!interactive) return@pointerInput
            detectTapGestures(
                onTap = { if (item.options.controls && !host.controlsVisible) host.controlsVisible = true else host.togglePlay() },
                onDoubleTap = { host.fullscreen = true },
            )
        })
        AutoHide(host)
        if (showBar && host.controlsVisible && !host.fullscreen) PlayerControls(host, compactAllowed = true, Modifier.align(Alignment.BottomCenter))
        if (!host.fullscreen && host.controlsVisible) IconButton(onClick = { host.deactivate() }, Modifier.align(Alignment.TopEnd).size(36.dp)) {
            Icon(Icons.Default.Close, stringResource(R.string.vid_close), tint = Color.White)
        }
    }
}

/** Hide the controls 3 s after playback starts or after the last change. */
@Composable
internal fun AutoHide(host: VideoHost) {
    LaunchedEffect(host.isPlaying, host.controlsVisible) {
        if (host.isPlaying && host.controlsVisible) { delay(3000); if (host.isPlaying) host.controlsVisible = false }
    }
}

/** TextureView bound to the host's player (drawn inside Compose, so it is clipped and moved with the page). */
@Composable
internal fun VideoSurface(host: VideoHost, modifier: Modifier) {
    val player = host.player
    val aspect = host.aspect
    val rot = host.rotation
    Box(modifier, contentAlignment = Alignment.Center) {
        if (player == null) return@Box
        AndroidView(
            factory = { ctx -> TextureView(ctx).apply { addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> applyRotation(v as TextureView, host.rotation) } } },
            modifier = if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize(),
            update = { tv -> player.setVideoTextureView(tv); applyRotation(tv, rot) },
            onRelease = { tv -> runCatching { player.clearVideoTextureView(tv) } },
        )
    }
}

/** Rotation the decoder did not apply (TextureView output), like media3's PlayerView does it. */
private fun applyRotation(tv: TextureView, degrees: Int) {
    val w = tv.width.toFloat(); val h = tv.height.toFloat()
    val m = Matrix()
    if (w > 0f && h > 0f && degrees % 360 != 0) {
        val px = w / 2f; val py = h / 2f
        m.postRotate(degrees.toFloat(), px, py)
        val src = android.graphics.RectF(0f, 0f, w, h); val dst = android.graphics.RectF()
        m.mapRect(dst, src)
        m.postScale(w / dst.width(), h / dst.height(), px, py)
    }
    tv.setTransform(m)
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
}

/**
 * Play / pause, seek bar, time, mute, loop and full-screen buttons. A narrow box ([compactAllowed]) keeps play,
 * seek and full screen only. [fullscreenUi]: the full-screen dialog's variant (exit + rotate buttons).
 */
@Composable
internal fun PlayerControls(host: VideoHost, compactAllowed: Boolean, modifier: Modifier, fullscreenUi: Boolean = false, onRotate: (() -> Unit)? = null) {
    val p = host.player
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(p) {
        while (p != null) {
            if (!dragging) { pos = p.currentPosition; dur = p.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L }
            delay(250)
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))) {
        val compact = compactAllowed && maxWidth < 260.dp
        val btn = if (compact) 32.dp else 40.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            val playing = host.isPlaying
            IconButton(onClick = { host.togglePlay(); host.controlsVisible = true }, Modifier.size(btn)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.vid_pause else R.string.vid_play), tint = Color.White)
            }
            if (!compact) Text(fmt(if (dragging) (dragPos * dur).toLong() else pos) + " / " + fmt(dur), color = Color.White, fontSize = 11.sp, maxLines = 1)
            val seekDesc = stringResource(R.string.vid_seek)
            Slider(
                value = if (dragging) dragPos else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = true; dragPos = it; host.controlsVisible = true },
                onValueChangeFinished = { host.seekTo((dragPos * dur).toLong()); pos = (dragPos * dur).toLong(); dragging = false },
                enabled = dur > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { contentDescription = seekDesc },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = Color.White.copy(alpha = 0.35f)),
            )
            if (!compact) {
                IconButton(onClick = { host.toggleMute(); host.controlsVisible = true }, Modifier.size(btn)) {
                    Icon(if (host.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        stringResource(if (host.muted) R.string.vid_unmute else R.string.vid_mute), tint = Color.White)
                }
                IconButton(onClick = { host.toggleLoop(); host.controlsVisible = true }, Modifier.size(btn)) {
                    Icon(if (host.loop) Icons.Default.RepeatOne else Icons.Default.Repeat, stringResource(R.string.vid_loop),
                        tint = if (host.loop) MaterialTheme.colorScheme.primary else Color.White)
                }
            }
            if (fullscreenUi && onRotate != null) IconButton(onClick = onRotate, Modifier.size(btn)) {
                Icon(Icons.Default.ScreenRotation, stringResource(R.string.vid_rotate), tint = Color.White)
            }
            IconButton(onClick = { host.fullscreen = !host.fullscreen; host.controlsVisible = true }, Modifier.size(btn)) {
                Icon(if (fullscreenUi) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    stringResource(if (fullscreenUi) R.string.vid_exit_fullscreen else R.string.vid_fullscreen), tint = Color.White)
            }
        }
    }
}
