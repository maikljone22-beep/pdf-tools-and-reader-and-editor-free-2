package com.smartdocs.editor.ui.ai5

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.AiLang
import com.smartdocs.editor.core.ai5.Audience
import com.smartdocs.editor.core.ai5.StructuredSummary
import com.smartdocs.editor.core.ai5.SummaryFormat
import com.smartdocs.editor.core.ai5.SummaryGen
import com.smartdocs.editor.core.ai5.SummaryLength
import com.smartdocs.editor.core.ai5.SummaryOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Language dropdown ("" = document language). */
@Composable
fun LanguagePicker(value: String, onChange: (String) -> Unit, allowAuto: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val auto = stringResource(R.string.a5_lang_auto)
    Box {
        OutlinedButton(onClick = { open = true }) { Text(if (value.isBlank()) auto else AiLang.name(value)) }
        DropdownMenu(open, { open = false }) {
            if (allowAuto) DropdownMenuItem(text = { Text(auto) }, onClick = { onChange(""); open = false })
            AiLang.NAMES.forEach { (code, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onChange(code); open = false }) }
        }
    }
}

@Composable
private fun <T> ChipChoice(values: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { v -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label(v)) }) }
    }
}

/**
 * Customisable summary of the whole document: length, bullets/paragraphs, language, audience, focus; sections
 * carry page chips (open the reader at that page) and suggested questions go to the chat.
 */
@Composable
fun SummaryPanel(docs: DocSet, client: AiClient, onPage: (filePath: String, page: Int) -> Unit, onAsk: (String) -> Unit, onShare: (String) -> Unit, onExportPdf: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var opts by remember { mutableStateOf(SummaryOptions()) }
    var result by remember { mutableStateOf<StructuredSummary?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    val idx = docs.indexes.firstOrNull()
    LaunchedEffect(idx, opts) { result = idx?.let { SummaryGen.cached(ctx, it, opts) } }
    // ai6v (§35 Summary Reading): read the summary aloud (AI voice when enabled, else the system voice)
    val speaker = remember { com.smartdocs.editor.core.ai5.AnswerSpeaker(ctx) }
    var speaking by remember { mutableStateOf(false) }
    DisposableEffect(speaker) { onDispose { speaker.release() } }
    LaunchedEffect(result) { if (result == null && speaking) { speaker.stop(); speaking = false } }
    // ai6v: audio summary file (AI voice) of the document or the whole Space, then share it
    val app = com.smartdocs.editor.LocalApp.current
    var audioBusy by remember { mutableStateOf(false) }
    fun audioFile(s: StructuredSummary) {
        if (audioBusy) return
        val text = s.spokenText(); if (text.isBlank()) return
        audioBusy = true; error = null
        scope.launch {
            try {
                val ac = ctx.applicationContext
                val prefs = com.smartdocs.editor.core.voice.VoicePrefs.load(ac)
                val voice = runCatching { com.smartdocs.editor.core.voice.AiSpeech.profile(ac).voiceOr(prefs.voice) }.getOrDefault(prefs.voice)
                val base = docs.title.substringBeforeLast('.').ifBlank { "summary" }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
                val out = java.io.File(com.smartdocs.editor.core.voice.PodcastGenerator.audioDir(ac), base + "_summary_audio.mp3")
                val spoken = com.smartdocs.editor.core.voice.PodcastGenerator.render(ac, com.smartdocs.editor.core.voice.PodcastGenerator.summaryLines(text), voice, voice, out, docs.title)
                val mime = if (spoken.file.extension == "wav") "audio/wav" else "audio/mpeg"
                runCatching { ctx.startActivity(android.content.Intent.createChooser(app.documents.shareIntent(listOf(spoken.file), mime), null)) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = com.smartdocs.editor.core.ai6.AiMedia.userMessage(ctx, e)
            } finally { audioBusy = false }
        }
    }
    fun toggleSpeak(s: StructuredSummary) {
        if (speaking) { speaker.stop(); speaking = false; return }
        val text = s.spokenText()
        if (text.isBlank()) return
        speaking = true
        speaker.speak(text, opts.language.ifBlank { AiLang.detect(text).ifBlank { "ar" } }) { speaking = false }
    }

    fun run(force: Boolean) {
        val i = idx ?: return
        busy = true; error = null; progress = 0f
        scope.launch {
            try { result = SummaryGen.generate(ctx, client, i, opts, force) { a, b -> progress = a / b.toFloat().coerceAtLeast(1f) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (AiCalls.isQuota(e)) ctx.getString(R.string.a5_err_quota) else ctx.getString(R.string.a5_err_failed, e.message ?: "") }
            finally { busy = false }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.a5_summary_presets), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { opts = SummaryOptions(SummaryLength.SHORT, SummaryFormat.BULLETS) }, label = { Text(stringResource(R.string.a5_preset_quick)) })
            AssistChip(onClick = { opts = SummaryOptions(SummaryLength.MEDIUM, SummaryFormat.PARAGRAPHS, audience = Audience.EXECUTIVE) }, label = { Text(stringResource(R.string.a5_preset_exec)) })
            AssistChip(onClick = { opts = SummaryOptions(SummaryLength.LONG, SummaryFormat.BULLETS, audience = Audience.STUDENT) }, label = { Text(stringResource(R.string.a5_preset_study)) })
        }
        Text(stringResource(R.string.a5_length), style = MaterialTheme.typography.labelLarge)
        ChipChoice(SummaryLength.entries, opts.length, { stringResource(when (it) { SummaryLength.SHORT -> R.string.a5_len_short; SummaryLength.MEDIUM -> R.string.a5_len_medium; SummaryLength.LONG -> R.string.a5_len_long }) }) { opts = opts.copy(length = it) }
        ChipChoice(SummaryFormat.entries, opts.format, { stringResource(if (it == SummaryFormat.BULLETS) R.string.a5_fmt_bullets else R.string.a5_fmt_paragraphs) }) { opts = opts.copy(format = it) }
        Text(stringResource(R.string.a5_audience), style = MaterialTheme.typography.labelLarge)
        ChipChoice(Audience.entries, opts.audience, { stringResource(when (it) {
            Audience.GENERAL -> R.string.a5_aud_general; Audience.STUDENT -> R.string.a5_aud_student; Audience.EXPERT -> R.string.a5_aud_expert
            Audience.CHILD -> R.string.a5_aud_child; Audience.EXECUTIVE -> R.string.a5_aud_exec }) }) { opts = opts.copy(audience = it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.a5_language), style = MaterialTheme.typography.labelLarge)
            LanguagePicker(opts.language, { opts = opts.copy(language = it) })
        }
        OutlinedTextField(opts.focus, { opts = opts.copy(focus = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.a5_focus)) }, singleLine = true)
        if (idx != null) Text(stringResource(R.string.a5_requests_needed, SummaryGen.requestsNeeded(idx)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { run(false) }, enabled = docs.ready && !busy) { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.a5_summarize)) }
            if (result != null) OutlinedButton(onClick = { run(true) }, enabled = !busy) { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.a5_regenerate)) }
        }
        if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        result?.let { s -> SummaryView(s, idx?.path.orEmpty(), onPage, onAsk, onShare, onExportPdf, stringResource(R.string.a5_page_short), speaking, onAudio = { audioFile(s) }, audioBusy = audioBusy) { toggleSpeak(s) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryView(s: StructuredSummary, path: String, onPage: (String, Int) -> Unit, onAsk: (String) -> Unit, onShare: (String) -> Unit, onExportPdf: (String) -> Unit, pageWord: String,
                        speaking: Boolean = false, onAudio: () -> Unit = {}, audioBusy: Boolean = false, onSpeak: () -> Unit = {}) {
    val dir = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (audioBusy) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
        else IconButton(onClick = onAudio) { Icon(Icons.Default.Headphones, stringResource(R.string.ai6v_audio_summary)) }   // ai6v: audio file
        IconButton(onClick = onSpeak) {   // ai6v: read the summary aloud / stop
            Icon(if (speaking) Icons.Default.Stop else Icons.Default.VolumeUp, stringResource(if (speaking) R.string.ai6v_stop_reading else R.string.ai6v_read_summary))
        }
        IconButton(onClick = { onShare(s.asText(pageWord)) }) { Icon(Icons.Default.Share, stringResource(R.string.a5_share)) }
        IconButton(onClick = { onExportPdf(s.asText(pageWord)) }) { Icon(Icons.Default.Description, stringResource(R.string.a5_export_pdf)) }
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.overview.isNotBlank()) Text(s.overview, style = dir)
            s.points.forEach { Text("• $it", style = dir) }
        }
    }
    s.sections.forEach { sec ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sec.title, style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(sec.text, style = dir) }
                if (sec.pages.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sec.pages.take(6).forEach { p -> AssistChip(onClick = { onPage(path, (p - 1).coerceAtLeast(0)) }, label = { Text(stringResource(R.string.a5_page_n, p)) }) }
                }
            }
        }
    }
    if (s.questions.isNotEmpty()) {
        Text(stringResource(R.string.a5_suggested), style = MaterialTheme.typography.labelLarge)
        SuggestionRow(s.questions, true, onAsk)
    }
}
