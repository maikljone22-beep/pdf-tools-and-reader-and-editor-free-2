package com.smartdocs.editor.core.ai5

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.smartdocs.editor.core.voice.AiVoiceEngine
import java.util.Locale

/**
 * Dictation with Android's [SpeechRecognizer] (Arabic by default, partial results). Must be used from the main
 * thread. [onPartial] gets the running transcript, [onFinal] the final one, [onError] an error code
 * ([SpeechRecognizer] ERROR_*), [onLevel] the microphone level 0..1 for the UI.
 */
class SpeechInput(private val ctx: Context) {
    private var rec: SpeechRecognizer? = null
    var listening = false; private set

    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onError: (Int) -> Unit = {}
    var onLevel: (Float) -> Unit = {}
    var onEnd: () -> Unit = {}

    companion object {
        fun available(ctx: Context) = SpeechRecognizer.isRecognitionAvailable(ctx)

        /** Intent for the system dictation activity (fallback when no in-app recognizer is available). */
        fun systemIntent(language: String = "ar-SA"): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    }

    fun start(language: String = "ar-SA") {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false; onError(error); onEnd() }
            override fun onResults(results: Bundle?) {
                listening = false
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                onFinal(t); onEnd()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = systemIntent(language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        listening = true
        r.startListening(i)
    }

    fun stop() {
        listening = false
        rec?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        rec = null
    }
}

/**
 * Speaks answers in the voice chat: the AI voice ([AiVoiceEngine], when the user enabled it and is online) or the
 * system [TextToSpeech]. [onDone] runs on completion (true) or failure (false).
 */
class AnswerSpeaker(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, (Boolean) -> Unit>? = null
    private var seq = 0

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { st ->
            ready = st == TextToSpeech.SUCCESS
            pending?.let { (t, cb) -> pending = null; if (ready) speakSystem(t, cb) else cb(false) }
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun speak(text: String, language: String, rate: Float = 1f, onDone0: (Boolean) -> Unit) {
        val onDone: (Boolean) -> Unit = { ok -> main.post { onDone0(ok) } }
        val clean = CitationFormat.plain(text).replace(Regex("[*#`_>]+"), "").trim()
        if (clean.isEmpty()) { onDone(true); return }
        if (AiVoiceEngine.shouldUse(ctx)) {
            val id = "a5-" + (++seq)
            AiVoiceEngine.speak(ctx, clean, rate, id) { _, ok -> if (ok) onDone(true) else speakFallback(clean, language, onDone) }
            return
        }
        speakFallback(clean, language, onDone)
    }

    private var lang = "ar"
    private fun speakFallback(text: String, language: String, onDone: (Boolean) -> Unit) {
        lang = language
        ensureTts()
        if (!ready) { pending = text to onDone; return }
        speakSystem(text, onDone)
    }

    private fun speakSystem(text: String, onDone: (Boolean) -> Unit) {
        val t = tts ?: return onDone(false)
        runCatching { t.language = Locale.forLanguageTag(if (lang == "ar") "ar" else lang) }
        val id = "a5s-" + (++seq)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) onDone(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) onDone(false) }
        })
        // TextToSpeech has a per-call limit; long answers are queued in pieces
        val max = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3500)
        val parts = text.chunked(max)
        parts.forEachIndexed { i, p -> t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) id else "$id-$i") }
    }

    fun stop() { AiVoiceEngine.stop(); runCatching { tts?.stop() }; pending = null }

    fun release() { stop(); runCatching { tts?.shutdown() }; tts = null; ready = false }
}
