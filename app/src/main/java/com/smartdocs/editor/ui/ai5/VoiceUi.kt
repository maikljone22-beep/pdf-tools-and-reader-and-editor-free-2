package com.smartdocs.editor.ui.ai5

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.AiLang
import com.smartdocs.editor.core.ai5.AnswerSpeaker
import com.smartdocs.editor.core.ai5.SpeechInput

/** Dictation language for the mic (Arabic first). */
object VoicePrefs5 {
    var language: String = "ar-SA"
}

/**
 * Mic button: asks for RECORD_AUDIO, then dictates with the in-app [SpeechInput] (partial results shown in a
 * small bubble); without an in-app recognizer it opens the system dictation activity.
 */
@Composable
fun MicButton(enabled: Boolean, onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val input = remember { SpeechInput(ctx) }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    DisposableEffect(input) { onDispose { input.stop() } }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
    }
    fun start() {
        if (!SpeechInput.available(ctx)) { runCatching { system.launch(SpeechInput.systemIntent(VoicePrefs5.language)) }; return }
        input.onPartial = { partial = it }
        input.onFinal = { t -> partial = ""; if (t.isNotBlank()) onText(t) }
        input.onError = { partial = "" }
        input.onEnd = { listening = false }
        listening = true
        input.start(VoicePrefs5.language)
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) start() }
    Box {
        IconButton(onClick = {
            if (listening) { input.stop(); listening = false; return@IconButton }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else perm.launch(Manifest.permission.RECORD_AUDIO)
        }, enabled = enabled) {
            Icon(if (listening) Icons.Default.Mic else Icons.Default.MicNone, stringResource(R.string.a5_dictate),
                tint = if (listening) MaterialTheme.colorScheme.error else LocalContentColor.current)
        }
        if (listening && partial.isNotBlank()) Popup5(partial)
    }
}

@Composable
private fun Popup5(text: String) {
    androidx.compose.ui.window.Popup(alignment = Alignment.TopStart, offset = androidx.compose.ui.unit.IntOffset(0, -140)) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp, shadowElevation = 6.dp) {
            Text(text, Modifier.padding(10.dp).widthIn(max = 280.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Voice chat: listen → ask [onAsk] → speak the answer → listen again. Tap the orb to interrupt / restart.
 * [answerFor] reads the latest answer text once [busy] turns false.
 */
@Composable
fun VoiceChatDialog(busy: Boolean, onAsk: (String) -> Unit, latestAnswer: () -> String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val input = remember { SpeechInput(ctx) }
    val speaker = remember { AnswerSpeaker(ctx) }
    var phase by remember { mutableStateOf(0) } // 0 idle, 1 listening, 2 thinking, 3 speaking
    var heard by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0f) }
    var asked by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // ai6v (B6): barge-in (speaking interrupts the answer) and a mic mute toggle
    var muted by remember { mutableStateOf(false) }
    var bargeIn by remember { mutableStateOf(true) }
    val listenRef = remember { arrayOfNulls<() -> Unit>(1) }   // plain holder: no state write during composition
    val detector = remember { com.smartdocs.editor.core.voice.BargeInDetector { if (phase == 3) { speaker.stop(); listenRef[0]?.invoke() } } }
    DisposableEffect(Unit) { onDispose { detector.stop(); input.stop(); speaker.release() } }

    fun listen() {
        detector.stop()
        if (muted) { speaker.stop(); phase = 0; return }
        if (!granted) { perm.launch(Manifest.permission.RECORD_AUDIO); return }
        speaker.stop()
        heard = ""; phase = 1
        input.onPartial = { heard = it }
        input.onLevel = { level = it }
        input.onFinal = { t -> heard = t; if (t.isNotBlank()) { phase = 2; asked = true; onAsk(t) } else phase = 0 }
        input.onError = { phase = 0 }
        input.onEnd = { level = 0f }
        input.start(VoicePrefs5.language)
    }
    listenRef[0] = { listen() }
    LaunchedEffect(granted) { if (granted && phase == 0 && SpeechInput.available(ctx)) listen() }
    LaunchedEffect(busy) {
        if (!busy && asked && phase == 2) {
            asked = false
            val a = latestAnswer()
            if (a.isNullOrBlank()) { phase = 0; return@LaunchedEffect }
            phase = 3
            if (bargeIn && !muted && granted) detector.start()
            speaker.speak(a, AiLang.detect(a).ifBlank { "ar" }) { detector.stop(); if (phase == 3) { if (muted) phase = 0 else listen() } }
        }
    }
    val scale by animateFloatAsState(if (phase == 1) 1f + level * 0.35f else if (phase == 3) 1.1f else 1f, label = "orb")
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.a5_close)) } },
        title = { Text(stringResource(R.string.a5_voice_chat)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!SpeechInput.available(ctx)) Text(stringResource(R.string.a5_no_recognizer), color = MaterialTheme.colorScheme.error)
                Box(Modifier.size(120.dp).scale(scale).background(
                    if (phase == 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable { if (phase == 1) { input.stop(); phase = 0 } else { muted = false; listen() } }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(48.dp))
                }
                Text(stringResource(when (phase) { 1 -> R.string.a5_listening; 2 -> R.string.a5_thinking; 3 -> R.string.a5_speaking; else -> R.string.a5_tap_to_talk }),
                    style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = muted, onClick = {
                        muted = !muted
                        if (muted) { detector.stop(); if (phase == 1) { input.stop(); phase = 0 } }
                    }, label = { Text(stringResource(if (muted) R.string.ai6v_mic_muted else R.string.ai6v_mic_on)) },
                        leadingIcon = { Icon(if (muted) Icons.Default.MicOff else Icons.Default.Mic, null, Modifier.size(18.dp)) })
                    FilterChip(selected = bargeIn, onClick = { bargeIn = !bargeIn; if (!bargeIn) detector.stop() else if (phase == 3 && !muted && granted) detector.start() },
                        label = { Text(stringResource(R.string.ai6v_barge_in)) })
                }
                if (phase == 3) TextButton(onClick = { detector.stop(); speaker.stop(); listen() }) { Text(stringResource(R.string.ai6v_interrupt)) }
                if (heard.isNotBlank()) Text(heard, textAlign = TextAlign.Center, modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium)
            }
        })
}
