package com.smartdocs.editor.ui.voice

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.voice.PodcastGenerator
import com.smartdocs.editor.core.voice.PodcastLength
import com.smartdocs.editor.core.voice.PodcastOptions
import com.smartdocs.editor.core.voice.PodcastScript
import com.smartdocs.editor.core.voice.ScriptLine
import com.smartdocs.editor.core.voice.SpokenAudio
import com.smartdocs.editor.core.voice.VoicePrefs
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Route "podcast": audio summary ("قراءة الملخص") and two-voice smart podcast ("بودكاست ذكي") of a PDF, with a
 * player (play / pause / seek / speed), a transcript that follows playback, and save / share of the audio.
 */
@Composable
fun PodcastScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val player = rememberAudioPlayer()
    val profile = rememberTtsProfile()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val hasKey = AiClient.available(settings)

    var file by remember { mutableStateOf(uri?.takeIf { it.isNotBlank() }?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull() }) }
    var mode by remember { mutableStateOf(PodcastGenerator.Mode.PODCAST) }
    var prefs by remember { mutableStateOf(VoicePrefs.load(ctx)) }
    var result by remember { mutableStateOf<SpokenAudio?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val pick = rememberPdfPicker { files -> files.firstOrNull()?.let { file = it } }
    // ai6v (B14): focus / length options, the editable script and the editor mode
    var focus by remember { mutableStateOf("") }
    var length by remember { mutableStateOf(PodcastLength.MEDIUM) }
    var script by remember { mutableStateOf<List<ScriptLine>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }

    val msgReading = stringResource(R.string.ai2_pc_reading)
    val msgScript = stringResource(R.string.ai2_pc_script)
    val msgVoiceFmt = stringResource(R.string.ai2_pc_voice)
    val msgSaved = stringResource(R.string.ai2_pc_saved)
    val snackbar = remember { SnackbarHostState() }

    fun savePrefs(p: VoicePrefs) { prefs = p; VoicePrefs.save(ctx, p) }

    // Show the last generated audio for this file / mode when there is one.
    LaunchedEffect(file, mode) {
        player.release(); result = null; script = emptyList(); editing = false
        val f = file ?: return@LaunchedEffect
        val existing = withContext(Dispatchers.IO) { PodcastGenerator.loadExisting(PodcastGenerator.outputFor(ctx, f.name, mode)) }
        if (existing != null) {
            result = existing; player.load(existing.file)
            script = withContext(Dispatchers.IO) { PodcastScript.loadScript(existing.file, existing.lines) }
        }
    }

    /** (first voice, guest voice) for the current mode. */
    fun voices(): Pair<String, String> {
        val host = profile?.voiceOr(prefs.hostVoice) ?: prefs.hostVoice
        val guest = profile?.let { p -> p.voices.firstOrNull { it == prefs.guestVoice } ?: p.voices.firstOrNull { it != host } ?: host } ?: prefs.guestVoice
        val voiceForSummary = profile?.voiceOr(prefs.voice) ?: prefs.voice
        return (if (mode == PodcastGenerator.Mode.SUMMARY) voiceForSummary else host) to guest
    }

    /** ai6v (B14): synthesize the (edited) script again into the same audio; unchanged lines come from the cache. */
    fun rerender() {
        val f = file ?: return
        val lines = PodcastScript.clean(script)
        if (working != null || lines.isEmpty()) return
        val target = result?.file ?: PodcastGenerator.outputFor(ctx, f.name, mode)
        player.release(); error = null
        job = scope.launch {
            try {
                working = msgVoiceFmt.format(0, lines.size); progress = 0f
                val (first, guest) = voices()
                val spoken = PodcastGenerator.render(ctx.applicationContext, lines, first, guest, target, f.nameWithoutExtension) { done, total ->
                    scope.launch { working = msgVoiceFmt.format(done, total); progress = done / total.toFloat() }
                }
                result = spoken; script = lines; editing = false
                player.load(spoken.file); player.play()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = com.smartdocs.editor.core.ai6.AiMedia.userMessage(ctx, e)
            } finally { working = null; progress = null }
        }
    }

    /** ai6v (B14): copy the audio with its transcript and script; the copy becomes the current one. */
    fun duplicate() {
        val r = result ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { PodcastScript.duplicate(r.file) } }
                .onSuccess { copy ->
                    player.release()
                    val loaded = withContext(Dispatchers.IO) { PodcastGenerator.loadExisting(copy) }
                    if (loaded != null) { result = loaded; player.load(loaded.file); script = withContext(Dispatchers.IO) { PodcastScript.loadScript(copy, loaded.lines) } }
                    snackbar.showSnackbar(ctx.getString(R.string.ai6v_duplicated, copy.name))
                }.onFailure { error = it.message }
        }
    }

    fun generate() {
        val f = file ?: return
        if (working != null) return
        player.release(); error = null
        job = scope.launch {
            try {
                working = msgReading; progress = null
                // MODULE_ai5: cover the whole book (condensed per segment, cached) instead of the first 12k characters
                val docText = withContext(Dispatchers.IO) {
                    val idx = com.smartdocs.editor.core.ai5.DocIndexer.get(ctx, f)
                    com.smartdocs.editor.core.ai5.WholeBook.material(ctx, client, idx)
                }
                if (docText.isBlank()) throw IllegalStateException(ctx.getString(R.string.ai2_err_no_text))
                working = msgScript
                val lines = PodcastGenerator.script(client, docText, mode, f.nameWithoutExtension, PodcastOptions(focus, length))   // ai6v: focus + length
                working = msgVoiceFmt.format(0, lines.size); progress = 0f
                val out = PodcastGenerator.outputFor(ctx, f.name, mode)
                val (first, guest) = voices()
                val spoken = PodcastGenerator.render(
                    ctx.applicationContext, lines, first, guest, out, f.nameWithoutExtension,
                ) { done, total -> scope.launch { working = msgVoiceFmt.format(done, total); progress = done / total.toFloat() } }
                result = spoken; script = lines; editing = false
                player.load(spoken.file)
                player.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally { working = null; progress = null }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { target ->
        val r = result
        if (target != null && r != null) scope.launch {
            runCatching { app.documents.exportTo(r.file, target) }
                .onSuccess { snackbar.showSnackbar(msgSaved) }.onFailure { error = it.message }
        }
    }

    fun share() {
        val r = result ?: return
        val mime = if (r.file.extension == "wav") "audio/wav" else "audio/mpeg"
        val files = listOf(r.file) + listOfNotNull(r.transcript.takeIf { it.exists() })   // ai6v: transcript too
        runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(files, if (files.size > 1) "*/*" else mime), null)) }.onFailure { error = it.message }
    }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.ai2_podcast_title), onBack = { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val wide = maxWidth > 700.dp
            val setup: @Composable ColumnScope.() -> Unit = {
                FilePickRow(file, stringResource(R.string.open_file), pick)
                if (!hasKey) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(stringResource(R.string.ai_no_key), Modifier.padding(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == PodcastGenerator.Mode.SUMMARY, onClick = { mode = PodcastGenerator.Mode.SUMMARY },
                        label = { Text(stringResource(R.string.ai2_mode_summary)) }, leadingIcon = { Icon(Icons.Default.Subject, null, Modifier.size(18.dp)) })
                    FilterChip(selected = mode == PodcastGenerator.Mode.PODCAST, onClick = { mode = PodcastGenerator.Mode.PODCAST },
                        label = { Text(stringResource(R.string.ai2_mode_podcast)) }, leadingIcon = { Icon(Icons.Default.Forum, null, Modifier.size(18.dp)) })
                }
                Text(stringResource(if (mode == PodcastGenerator.Mode.SUMMARY) R.string.ai2_mode_summary_desc else R.string.ai2_mode_podcast_desc),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PodcastOptionsRow(focus, { focus = it }, length, { length = it })   // ai6v (B14)
                if (mode == PodcastGenerator.Mode.SUMMARY) {
                    AiVoicePicker(stringResource(R.string.ai2_voice_pick), prefs.voice, { savePrefs(prefs.copy(voice = it)) }, profile)
                } else {
                    AiVoicePicker(stringResource(R.string.ai2_host_voice), prefs.hostVoice, { savePrefs(prefs.copy(hostVoice = it)) }, profile)
                    val guestDefault = profile?.voices?.firstOrNull { it != profile.voiceOr(prefs.hostVoice) } ?: ""
                    AiVoicePicker(stringResource(R.string.ai2_guest_voice), prefs.guestVoice.ifBlank { guestDefault }, { savePrefs(prefs.copy(guestVoice = it)) }, profile)
                }
                Button(onClick = { generate() }, enabled = hasKey && file != null && working == null, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (result == null) Icons.Default.AutoAwesome else Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (result == null) R.string.ai2_generate else R.string.ai2_regenerate))
                }
                Text(stringResource(R.string.ai2_pc_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                player.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            val playerCard: @Composable (Modifier) -> Unit = { m ->
                result?.let { r ->
                    ElevatedCard(m) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                                Text(r.file.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                                IconButton(onClick = { saveLauncher.launch(r.file.name) }) { Icon(Icons.Default.Download, stringResource(R.string.ai2_save_audio)) }
                                IconButton(onClick = { share() }) { Icon(Icons.Default.Share, stringResource(R.string.ai2_share)) }
                            }
                            PlayerControls(player)
                            ChapterRow(r.lines, player)   // ai6v (B14)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { if (script.isEmpty()) script = r.lines.map { ScriptLine(it.speaker, it.text, it.chapter) }; editing = !editing; player.pause() }) {
                                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                    Text(stringResource(if (editing) R.string.ai6v_edit_close else R.string.ai6v_edit))
                                }
                                OutlinedButton(onClick = { duplicate() }) {
                                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ai6v_duplicate))
                                }
                            }
                        }
                    }
                }
            }
            // ai6v (B14): the transcript, or the script editor with "synthesize again"
            val transcriptArea: @Composable (Modifier) -> Unit = { m ->
                if (!editing) TranscriptView(result?.lines.orEmpty(), player, m)
                else Column(m) {
                    val old = result?.lines.orEmpty().map { ScriptLine(it.speaker, it.text) }
                    val changed = PodcastScript.changedCount(old, PodcastScript.clean(script))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ai6v_changed, changed), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Button(onClick = { rerender() }, enabled = working == null && PodcastScript.clean(script).isNotEmpty()) {
                            Icon(Icons.Default.RecordVoiceOver, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ai6v_resynth))
                        }
                    }
                    PodcastScriptEditor(script, mode == PodcastGenerator.Mode.PODCAST, { script = it }, Modifier.weight(1f).fillMaxWidth())
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        setup(); playerCard(Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text(stringResource(R.string.ai2_transcript), style = MaterialTheme.typography.titleMedium)
                        transcriptArea(Modifier.fillMaxSize())
                    }
                }
            } else {
                var showTranscript by remember { mutableStateOf(true) }
                Column(Modifier.fillMaxSize()) {
                    if (result == null || (!showTranscript && !editing)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            setup(); playerCard(Modifier.fillMaxWidth())
                            if (result != null) TextButton(onClick = { showTranscript = true }) { Text(stringResource(R.string.ai2_transcript)) }
                        }
                    } else {
                        Column(Modifier.padding(16.dp, 8.dp)) {
                            playerCard(Modifier.fillMaxWidth())
                            TextButton(onClick = { showTranscript = false }) { Text(stringResource(R.string.ai2_settings_back)) }
                        }
                        transcriptArea(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }

    working?.let { msg ->
        ProgressDialog(msg, progress)
    }
}
