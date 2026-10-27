package com.smartdocs.editor.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ai6v (B6): voice-activity gate for barge-in. Pure logic (unit tested): it learns the noise floor (which includes
 * the leak of our own voice through the speaker) during the first [calibrationFrames] frames, then reports speech
 * once [onsetFrames] consecutive frames are at least [marginDb] above that floor and above [minDb].
 */
class VadGate(
    private val calibrationFrames: Int = 15,     // ~300 ms of 20 ms frames
    private val onsetFrames: Int = 6,            // ~120 ms of speech
    private val marginDb: Double = 12.0,
    private val minDb: Double = 45.0,
) {
    private var frames = 0
    private var floorSum = 0.0
    var floorDb = 0.0; private set
    private var run = 0

    /** Feed one frame level in dB (0..~90); true when the user started speaking. */
    fun feed(db: Double): Boolean {
        frames++
        if (frames <= calibrationFrames) {
            floorSum += db; floorDb = floorSum / frames
            return false
        }
        val loud = db >= floorDb + marginDb && db >= minDb
        if (loud) run++ else {
            run = 0
            // the floor follows slowly (playback volume changes between sentences)
            floorDb = floorDb * 0.98 + db * 0.02
        }
        return run >= onsetFrames
    }

    fun reset() { frames = 0; floorSum = 0.0; floorDb = 0.0; run = 0 }

    companion object {
        /** RMS level of 16-bit PCM in dB SPL-like scale (0 = silence, ~90 = full scale). */
        fun levelDb(pcm: ShortArray, n: Int = pcm.size): Double {
            if (n <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) { val v = pcm[i].toDouble(); sum += v * v }
            val rms = sqrt(sum / n)
            return if (rms < 1.0) 0.0 else 20.0 * log10(rms)
        }
    }
}

/**
 * ai6v (B6): listens to the microphone while the assistant is speaking (echo canceller when the device has one)
 * and calls [onSpeech] once on the main thread when the user starts talking. Needs RECORD_AUDIO.
 */
class BargeInDetector(private val onSpeech: () -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val rate = 16000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) { running = false; return@Thread }
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate / 5))
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { runCatching { rec?.release() }; running = false; return@Thread }
            val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.setEnabled(true) } }.getOrNull() else null
            val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.also { it.setEnabled(true) } }.getOrNull() else null
            val gate = VadGate()
            val frame = ShortArray(rate / 50)   // 20 ms
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    if (gate.feed(VadGate.levelDb(frame, n))) {
                        running = false
                        main.post { onSpeech() }
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { rec.stop() }; runCatching { rec.release() }
                runCatching { aec?.release() }; runCatching { ns?.release() }
                running = false
            }
        }.also { it.isDaemon = true; it.name = "barge-in"; it.start() }
    }

    fun stop() { running = false; thread = null }
}
