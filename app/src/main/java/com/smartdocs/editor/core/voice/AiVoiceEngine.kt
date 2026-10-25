package com.smartdocs.editor.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Optional "صوت بشري (يحتاج إنترنت)" engine for ReadAloud. Same shape as `TextToSpeech.speak(...)` + an
 * `UtteranceProgressListener.onDone`, so [com.smartdocs.editor.core.tts.ReadAloudController] can swap engines per
 * utterance. All callbacks arrive on the main thread.
 *
 * Hook (in ReadAloudController, owned by the TTS module — see MODULE_ai2.md):
 * ```
 * // speakCurrent():
 * if (AiVoiceEngine.shouldUse(ctx)) {
 *     AiVoiceEngine.speak(ctx, text, st.rate, id, next = chunks.getOrNull(chunkIdx + 1)) { uid, ok ->
 *         if (ok) listener.onDone(uid) else { AiVoiceEngine.disableForSession(); speakCurrent() } // fall back to system TTS
 *     }
 * } else engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
 * // pause()/stop()/goToPage(): AiVoiceEngine.stop()     setRate(r): AiVoiceEngine.setRate(r)     release(): AiVoiceEngine.release()
 * ```
 */
object AiVoiceEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var job: Job? = null
    private var prefetchJob: Job? = null
    private var currentId: String? = null
    private var rate = 1f
    @Volatile private var sessionDisabled = false

    /** True when the user chose the AI voice, it is configured and the device is online. */
    fun shouldUse(ctx: Context): Boolean = !sessionDisabled && VoicePrefs.load(ctx).readAloudAi && online(ctx)

    /** After a failure, use the system voice until [enableAgain] (e.g. next start of read aloud). */
    fun disableForSession() { sessionDisabled = true }
    fun enableAgain() { sessionDisabled = false }

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Synthesize (or take from cache) and play [text]. [onDone] gets (utteranceId, success) once playback ends or
     * fails; it is not called for an utterance replaced by [stop] or a newer [speak]. [next] is pre-fetched.
     */
    fun speak(ctx: Context, text: String, rate: Float, utteranceId: String, next: String? = null, onDone: (String, Boolean) -> Unit) {
        stopPlayer()
        job?.cancel()
        this.rate = rate
        currentId = utteranceId
        val app = ctx.applicationContext
        job = scope.launch {
            val file = runCatching { synth(app, text) }.getOrNull()
            if (currentId != utteranceId) return@launch
            if (file == null) { onDone(utteranceId, false); return@launch }
            next?.let { prefetch(app, it) }
            play(file, utteranceId, onDone)
        }
    }

    /** Warm the cache for the next chunk. */
    fun prefetch(ctx: Context, text: String) {
        if (prefetchJob?.isActive == true) return
        val app = ctx.applicationContext
        prefetchJob = CoroutineScope(Dispatchers.IO).launch { runCatching { synth(app, text) } }
    }

    private suspend fun synth(ctx: Context, text: String): File {
        val prefs = VoicePrefs.load(ctx)
        return AiSpeech.synthesizeLong(ctx, text, prefs.voice)
    }

    private fun play(file: File, id: String, onDone: (String, Boolean) -> Unit) {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { if (currentId == id) { stopPlayer(); onDone(id, true) } }
            mp.setOnErrorListener { _, _, _ -> if (currentId == id) { stopPlayer(); onDone(id, false) }; true }
            mp.prepare()
            applyRate(mp)
            mp.start()
        } catch (e: Exception) {
            stopPlayer(); onDone(id, false)
        }
    }

    private fun applyRate(mp: MediaPlayer) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(rate.coerceIn(0.5f, 2.5f)) }
    }

    fun setRate(r: Float) { rate = r; player?.let { applyRate(it) } }

    fun pause() { runCatching { player?.pause() } }
    fun resume() { runCatching { player?.start() } }
    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** Stop the current utterance without calling its onDone. */
    fun stop() { currentId = null; job?.cancel(); stopPlayer() }

    private fun stopPlayer() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }; runCatching { p.release() }
    }

    fun release() { stop(); prefetchJob?.cancel(); sessionDisabled = false }
}
