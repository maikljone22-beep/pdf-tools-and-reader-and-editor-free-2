package com.smartdocs.editor.ui.voice

import android.media.MediaPlayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.voice.AiSpeech
import com.smartdocs.editor.core.voice.AiVoiceEngine
import com.smartdocs.editor.core.voice.TtsProfile
import com.smartdocs.editor.core.voice.VoicePrefs
import kotlinx.coroutines.launch

/** Loads the provider's voice list once (proxy → GET /audio/voices). */
@Composable
fun rememberTtsProfile(): TtsProfile? {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    var profile by remember { mutableStateOf<TtsProfile?>(null) }
    LaunchedEffect(settings.aiEndpoint, settings.aiApiKey) { profile = runCatching { AiSpeech.profile(ctx.applicationContext, settings) }.getOrNull() }
    return profile
}

/** Voice chips + a preview button. [label] e.g. "صوت المضيف". */
@Composable
fun AiVoicePicker(label: String, selected: String, onSelect: (String) -> Unit, profile: TtsProfile?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { player?.release() } } }
    val sample = stringResource(R.string.ai2_voice_sample)
    val current = profile?.voiceOr(selected) ?: selected

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(enabled = profile != null && !previewing, onClick = {
                previewing = true; error = null
                scope.launch {
                    try {
                        val f = AiSpeech.synthesize(ctx.applicationContext, sample, current)
                        runCatching { player?.release() }
                        player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); setOnCompletionListener { previewing = false }; start() }
                    } catch (e: Exception) { error = e.message; previewing = false }
                }
            }) {
                if (previewing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ai2_preview))
            }
        }
        if (profile == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            profile.voices.forEach { v -> FilterChip(selected = v == current, onClick = { onSelect(v) }, label = { Text(v.replaceFirstChar { it.uppercase() }) }) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
    }
}

/**
 * Read-aloud voice section ("صوت النظام" / "صوت بشري (يحتاج إنترنت)") to embed in the reader's TTS "more settings"
 * sheet. Stores the choice in [VoicePrefs]; ReadAloudController reads it through [AiVoiceEngine.shouldUse].
 */
@Composable
fun ReadAloudVoiceSection(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    var prefs by remember { mutableStateOf(VoicePrefs.load(ctx)) }
    fun save(p: VoicePrefs) { prefs = p; VoicePrefs.save(ctx, p); AiVoiceEngine.enableAgain() }
    val available = AiClient.available(settings)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai2_voice_title), style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text(stringResource(R.string.ai2_voice_system)) },
            supportingContent = { Text(stringResource(R.string.ai2_voice_system_desc)) },
            leadingContent = { RadioButton(selected = !prefs.readAloudAi, onClick = { save(prefs.copy(readAloudAi = false)) }) },
            trailingContent = { Icon(Icons.Default.RecordVoiceOver, null) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.ai2_voice_ai)) },
            supportingContent = { Text(stringResource(if (available) R.string.ai2_voice_ai_desc else R.string.ai_no_key)) },
            leadingContent = { RadioButton(selected = prefs.readAloudAi, enabled = available, onClick = { save(prefs.copy(readAloudAi = true)) }) },
            trailingContent = { Icon(Icons.Default.CloudQueue, stringResource(R.string.ai2_needs_network)) },
        )
        if (prefs.readAloudAi && available) {
            val profile = rememberTtsProfile()
            AiVoicePicker(stringResource(R.string.ai2_voice_pick), prefs.voice, { save(prefs.copy(voice = it)) }, profile)
        }
    }
}

/** The same section as a bottom sheet (for a reader toolbar button). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudVoiceSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ReadAloudVoiceSection(Modifier.padding(bottom = 24.dp))
    }
}
