package com.smartdocs.editor.core.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Audio focus for read aloud (MODULE view5): reading pauses when another app or a phone call takes the
 * audio focus, or when headphones are unplugged, and resumes when a transient loss ends (call finished).
 * Repeat modes live here too so the controller only needs two small hooks.
 */
object ReadAloudFocus {
    enum class Repeat { OFF, PAGE, DOCUMENT }

    @Volatile var repeat: Repeat = Repeat.OFF
    @Volatile var pauseOnFocusLoss: Boolean = true

    private var am: AudioManager? = null
    private var request: AudioFocusRequest? = null
    private var held = false
    private var pausedByFocus = false
    private var noisyRegistered = false
    private var appCtx: Context? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { held = false; if (pauseOnFocusLoss && ReadAloudController.status.value.state == ReadAloudController.State.PLAYING) { pausedByFocus = false; ReadAloudController.pause() } }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (pauseOnFocusLoss && ReadAloudController.status.value.state == ReadAloudController.State.PLAYING) { pausedByFocus = true; ReadAloudController.pause() }
            }
            AudioManager.AUDIOFOCUS_GAIN -> { held = true; if (pausedByFocus) { pausedByFocus = false; ReadAloudController.resume() } }
        }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && ReadAloudController.status.value.state == ReadAloudController.State.PLAYING) {
                pausedByFocus = false; ReadAloudController.pause()
            }
        }
    }

    /** Called before speaking: takes the audio focus once per session. */
    fun acquire(context: Context) {
        appCtx = context.applicationContext
        if (!noisyRegistered) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) appCtx!!.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
                else appCtx!!.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                noisyRegistered = true
            }
        }
        if (held) return
        val m = am ?: (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.also { am = it } ?: return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener(listener).setWillPauseWhenDucked(true).build()
        request = req
        held = runCatching { m.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
    }

    /** Called when reading stops for good. */
    fun release() {
        pausedByFocus = false
        request?.let { r -> runCatching { am?.abandonAudioFocusRequest(r) } }
        request = null; held = false
        if (noisyRegistered) { runCatching { appCtx?.unregisterReceiver(noisy) }; noisyRegistered = false }
    }

    /**
     * Where to go when [page] (of [pageCount]) has been read to the end: the same page (repeat page),
     * page 0 after the last page (repeat document), the next page, or null to stop.
     */
    fun nextAfter(page: Int, pageCount: Int): Int? = when {
        repeat == Repeat.PAGE -> page
        page + 1 < pageCount -> page + 1
        repeat == Repeat.DOCUMENT && pageCount > 0 -> 0
        else -> null
    }
}
