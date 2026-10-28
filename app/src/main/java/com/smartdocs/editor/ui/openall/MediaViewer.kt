package com.smartdocs.editor.ui.openall

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.openall.FType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Audio / video player (open-all) on media3 ExoPlayer with own controls: MP3, M4A/AAC, OGG/Opus, WAV, FLAC, AMR,
 * MP4, MKV, WebM, 3GP, MOV, TS… Media from other apps is streamed from its content:// source (never copied whole).
 */
@Composable
fun MediaViewer(nav: NavHostController, v: ViewFile) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(v.uri) {
        ExoPlayer.Builder(ctx.applicationContext).build().apply { setMediaItem(MediaItem.fromUri(v.uri)); prepare(); playWhenReady = true }
    }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableFloatStateOf(0f) }
    var hasVideo by remember { mutableStateOf(v.type == FType.VIDEO) }
    var loop by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var controls by remember { mutableStateOf(true) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName }
            override fun onVideoSizeChanged(s: VideoSize) { if (s.width > 0 && s.height > 0) { hasVideo = true; aspect = s.width * s.pixelWidthHeightRatio / s.height } }
        }
        player.addListener(l)
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); player.removeListener(l); player.release() }
    }
    LaunchedEffect(player) { while (true) { pos = player.currentPosition; dur = player.duration.coerceAtLeast(0L); delay(300) } }
    LaunchedEffect(playing, controls) { if (playing && controls && hasVideo) { delay(3500); controls = false } }
    val meta by produceState<Triple<String?, String?, android.graphics.Bitmap?>?>(null, v.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    if (v.file != null) r.setDataSource(v.file.absolutePath) else r.setDataSource(ctx, v.uri)
                    val art = r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    Triple(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), art)
                } finally { runCatching { r.release() } }
            }.getOrNull()
        }
    }
    val actions = listOf(
        ViewerAction(R.string.oa_loop, Icons.Default.Repeat) { loop = !loop; player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF },
        ViewerAction(R.string.oa_speed, Icons.Default.Speed) { speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; 2f -> 0.75f; else -> 1f }; player.setPlaybackSpeed(speed) },
    )
    ViewerScaffold(nav, v, meta?.first?.takeIf { it.isNotBlank() } ?: v.name, actions = actions) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(if (hasVideo) Color.Black else MaterialTheme.colorScheme.surface)) {
            Box(Modifier.weight(1f).fillMaxWidth().clickable { controls = !controls }, contentAlignment = Alignment.Center) {
                if (error != null) ErrorPane(stringResource(R.string.oa_media_error, error ?: ""))
                else if (hasVideo) AndroidView(
                    factory = { c -> TextureView(c) },
                    modifier = if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize(),
                    update = { tv -> player.setVideoTextureView(tv) },
                    onRelease = { tv -> runCatching { player.clearVideoTextureView(tv) } },
                ) else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    val art = meta?.third
                    if (art != null) Image(art.asImageBitmap(), null, Modifier.size(240.dp), contentScale = ContentScale.Crop)
                    else Icon(Icons.Default.MusicNote, null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(meta?.first?.takeIf { it.isNotBlank() } ?: v.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    meta?.second?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (controls || !hasVideo) Surface(color = if (hasVideo) Color.Black.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainer, contentColor = if (hasVideo) Color.White else MaterialTheme.colorScheme.onSurface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Slider(value = if (dur > 0) pos.toFloat() / dur else 0f, onValueChange = { if (dur > 0) player.seekTo((it * dur).toLong()) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(clock(pos), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, stringResource(R.string.pc_cd_back10)) }
                        FilledIconButton(onClick = { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.oa_pause else R.string.oa_play))
                        }
                        IconButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) { Icon(Icons.Default.Forward10, stringResource(R.string.pc_cd_fwd10)) }
                        Spacer(Modifier.weight(1f))
                        Text((if (speed != 1f) "${speed}x  " else "") + clock(dur), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
