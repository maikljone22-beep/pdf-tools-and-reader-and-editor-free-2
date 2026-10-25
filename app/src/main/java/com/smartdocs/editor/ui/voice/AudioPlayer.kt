package com.smartdocs.editor.ui.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.voice.PodcastGenerator
import com.smartdocs.editor.core.voice.TimedLine
import kotlinx.coroutines.delay
import java.io.File

/** Small MediaPlayer wrapper for the podcast / summary player (position polled by the composable). */
class AudioPlayerState {
    private var mp: MediaPlayer? = null
    var file by mutableStateOf<File?>(null); private set
    var playing by mutableStateOf(false); private set
    var position by mutableLongStateOf(0L); private set
    var duration by mutableLongStateOf(0L); private set
    var speed by mutableFloatStateOf(1f); private set
    var error by mutableStateOf<String?>(null)

    fun load(f: File) {
        release()
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(f.absolutePath)
            p.prepare()
            p.setOnCompletionListener { playing = false; position = duration }
            mp = p; file = f; duration = p.duration.toLong().coerceAtLeast(0); position = 0; error = null
        } catch (e: Exception) { error = e.message ?: e.toString() }
    }

    fun toggle() { if (playing) pause() else play() }

    fun play() {
        val p = mp ?: return
        if (position >= duration - 50) { p.seekTo(0); position = 0 }
        runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
        p.start(); playing = true
    }

    fun pause() { runCatching { mp?.pause() }; playing = false }

    fun seekTo(ms: Long) { val p = mp ?: return; val t = ms.coerceIn(0, duration); p.seekTo(t.toInt()); position = t }

    fun skip(deltaMs: Long) = seekTo(position + deltaMs)

    fun changeSpeed(s: Float) {
        speed = s
        val p = mp ?: return
        if (playing) runCatching { p.playbackParams = p.playbackParams.setSpeed(s) }
    }

    /** Called by the UI loop. */
    fun tick() { val p = mp ?: return; if (playing) position = runCatching { p.currentPosition.toLong() }.getOrDefault(position) }

    fun release() { runCatching { mp?.stop() }; runCatching { mp?.release() }; mp = null; playing = false; file = null; position = 0; duration = 0 }
}

@Composable
fun rememberAudioPlayer(): AudioPlayerState {
    val st = remember { AudioPlayerState() }
    DisposableEffect(st) { onDispose { st.release() } }
    LaunchedEffect(st.playing) { while (st.playing) { st.tick(); delay(200) } }
    return st
}

val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Play / pause, ±10 s, seek bar with times and the six reference speeds. */
@Composable
fun PlayerControls(player: AudioPlayerState, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(modifier) {
        val dur = player.duration.coerceAtLeast(1L).toFloat()
        Slider(
            value = dragging ?: (player.position / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { player.seekTo((it * dur).toLong()) }; dragging = null },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(PodcastGenerator.fmt(dragging?.let { (it * dur).toLong() } ?: player.position), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(PodcastGenerator.fmt(player.duration), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.skip(-10_000) }) { Icon(Icons.Default.Replay10, stringResource(R.string.ai2_back10)) }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(onClick = { player.toggle() }, modifier = Modifier.size(64.dp)) {
                Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (player.playing) R.string.ai2_pause else R.string.ai2_play), Modifier.size(36.dp))
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = { player.skip(10_000) }) { Icon(Icons.Default.Forward10, stringResource(R.string.ai2_fwd10)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            SPEEDS.forEach { s ->
                FilterChip(selected = player.speed == s, onClick = { player.changeSpeed(s) }, label = { Text(if (s == 1f) "1.0×" else "${s}×") })
            }
        }
    }
}

/** Transcript: every line with its speaker; the line being played is highlighted and kept in view; tap = seek. */
@Composable
fun TranscriptView(lines: List<TimedLine>, player: AudioPlayerState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val current = lines.indexOfLast { player.position >= it.startMs }.coerceAtLeast(0)
    val twoVoices = lines.any { it.speaker == 1 }
    LaunchedEffect(current, lines.size) { if (lines.isNotEmpty() && player.playing) listState.animateScrollToItem((current - 1).coerceAtLeast(0)) }
    if (lines.isEmpty()) {
        Text(stringResource(R.string.ai2_no_transcript), modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(lines) { i, l ->
            val active = i == current && (player.playing || player.position > 0)
            val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
            Column(
                Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).clickable { player.seekTo(l.startMs); if (!player.playing) player.play() }.padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (twoVoices) Text(
                        stringResource(if (l.speaker == 1) R.string.ai2_guest else R.string.ai2_host),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = if (l.speaker == 1) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(PodcastGenerator.fmt(l.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(l.text, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content), fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
