package com.smartdocs.editor.ui.ai6

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import com.smartdocs.editor.core.ai.AnswerPdf
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai6.AiMedia
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.ResultSheet
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Transcript helpers (pure). */
object TranscriptText {
    /** Split a long transcript into paragraphs of about [chars] characters at sentence ends (Arabic and Latin punctuation). */
    fun paragraphs(text: String, chars: Int = 600): String {
        val sentences = Regex("(?<=[.!?؟。])\\s+").split(text.trim()).filter { it.isNotBlank() }
        val out = StringBuilder(); var cur = 0
        sentences.forEach { s ->
            if (cur > 0 && cur + s.length > chars) { out.append("\n\n"); cur = 0 } else if (cur > 0) out.append(' ')
            out.append(s); cur += s.length + 1
        }
        return out.toString()
    }
}

/**
 * ai6: route "ai_transcribe". Pick an audio file (voice note, lecture, meeting) → [AiMedia.transcribe]
 * (Whisper through the proxy or the own endpoint) → editable text; save as PDF or TXT, summarise it, or ask a
 * question about it. Shows the "not configured" message when the proxy has no speech-to-text route.
 */
@Composable
fun TranscribeScreen(nav: NavHostController, @Suppress("UNUSED_PARAMETER") uri: String?) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    var audio by remember { mutableStateOf<File?>(null) }
    var lang by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var pdf by remember { mutableStateOf<File?>(null) }
    var txt by remember { mutableStateOf<File?>(null) }
    val pick = rememberPdfPicker(mimes = arrayOf("audio/*", "video/mp4", "video/webm")) { files -> files.firstOrNull()?.let { audio = it; text = ""; answer = null } }

    fun ask(prompt: String) {
        if (text.isBlank() || busy) return
        busy = true; error = null
        scope.launch {
            try { answer = AiCalls.ask(ctx, client, AiCalls.SYSTEM, prompt + "\n\n" + text.take(14_000)) }
            catch (e: CancellationException) { throw e } catch (e: Throwable) { error = tError(ctx, e) } finally { busy = false }
        }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.ai6t_transcribe), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!settings.aiEnabled) { Text(stringResource(R.string.ai6_off)); return@Column }
            Text(stringResource(R.string.ai6t_transcribe_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = pick) { Text(audio?.name ?: stringResource(R.string.ai6t_pick_audio)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ai6t_spoken_language))
                listOf("" to R.string.a5_lang_auto, "ar" to R.string.ai6t_lang_ar, "en" to R.string.ai6t_lang_en).forEach { (v, l) ->
                    FilterChip(lang == v, { lang = v }, label = { Text(stringResource(l)) })
                }
            }
            Button(enabled = audio != null && !busy, onClick = {
                val f = audio ?: return@Button
                busy = true; error = null
                scope.launch {
                    try {
                        if (f.length() > 24L * 1024 * 1024) throw IllegalStateException(ctx.getString(R.string.ai6t_audio_too_big))
                        text = TranscriptText.paragraphs(AiMedia.transcribe(client, f, lang))
                    } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = if (e is IllegalStateException) e.message else tError(ctx, e) } finally { busy = false }
                }
            }) { Text(stringResource(R.string.ai6t_transcribe_run)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (text.isNotBlank()) {
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.ai6t_transcript)) }, minLines = 6, modifier = Modifier.fillMaxWidth())
                val base = audio?.nameWithoutExtension ?: "transcript"
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            runCatching { app.documents.uniqueFile(app.documents.docsDir, base, "pdf").also { AnswerPdf.write(ctx, base, text, it) } }
                                .onSuccess { pdf = it }.onFailure { error = it.message }
                        }
                    }) { Text("PDF") }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { app.documents.uniqueFile(app.documents.docsDir, base, "txt").also { it.writeText(text) } } }
                                .onSuccess { txt = it }.onFailure { error = it.message }
                        }
                    }) { Text("TXT") }
                    OutlinedButton(enabled = !busy, onClick = { ask(ctx.getString(R.string.ai6t_summarize_prompt)) }) { Text(stringResource(R.string.ai6t_summarize)) }
                }
                txt?.let { TFileActions(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(question, { question = it }, label = { Text(stringResource(R.string.ai6t_ask_about)) }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    Button(enabled = question.isNotBlank() && !busy, onClick = { ask(ctx.getString(R.string.ai6t_answer_from_transcript, question.trim())) }) { Text(stringResource(R.string.ai6t_ask)) }
                }
                answer?.let { a -> OutlinedCard(Modifier.fillMaxWidth()) { SelectionContainer { Text(a, Modifier.padding(12.dp)) } } }
            }
        }
    }
    pdf?.let { f -> ResultSheet(f, nav, onDismiss = { pdf = null }) }
}
