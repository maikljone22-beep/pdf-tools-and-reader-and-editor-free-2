package com.smartdocs.editor.core.ai6

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume

/** 16-bit PCM read from a WAV file. */
class Pcm(val sampleRate: Int, val channels: Int, val samples: ShortArray)

/** Pure WAV / PCM helpers (unit tested). */
object WavPcm {
    /** Parse a RIFF/WAVE file with 16-bit PCM data; null for anything else. */
    fun parse(b: ByteArray): Pcm? {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12; var rate = 0; var ch = 0; var bits = 0; var fmt = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII); val len = bb.getInt(pos + 4)
            val body = pos + 8
            if (len < 0) return null
            if (id == "fmt ") { fmt = bb.getShort(body).toInt(); ch = bb.getShort(body + 2).toInt(); rate = bb.getInt(body + 4); bits = bb.getShort(body + 14).toInt() }
            else if (id == "data") {
                if (bits != 16 || (fmt != 1 && fmt != -2) || ch <= 0 || rate <= 0) return null
                val end = minOf(b.size, body + len)
                val n = (end - body) / 2
                val s = ShortArray(n) { i -> bb.getShort(body + i * 2) }
                return Pcm(rate, ch, s)
            }
            pos = body + len + (len and 1)
        }
        return null
    }

    fun toMono(p: Pcm): ShortArray {
        if (p.channels == 1) return p.samples
        val n = p.samples.size / p.channels
        return ShortArray(n) { i -> var sum = 0; for (c in 0 until p.channels) sum += p.samples[i * p.channels + c]; (sum / p.channels).toShort() }
    }

    /** Linear resampling of mono samples. */
    fun resample(s: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || s.isEmpty()) return s
        val n = (s.size.toLong() * to / from).toInt().coerceAtLeast(1)
        return ShortArray(n) { i ->
            val x = i.toDouble() * from / to
            val a = x.toInt().coerceAtMost(s.size - 1); val b = (a + 1).coerceAtMost(s.size - 1)
            val f = x - a
            (s[a] * (1 - f) + s[b] * f).toInt().toShort()
        }
    }

    /** Joins mono tracks (all at the same rate) into one. */
    fun concat(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size }); var at = 0
        parts.forEach { System.arraycopy(it, 0, out, at, it.size); at += it.size }
        return out
    }

    /** Little-endian 16-bit PCM bytes to samples (an odd trailing byte is dropped). */
    fun shorts(b: ByteArray, len: Int = b.size): ShortArray = ShortArray(len / 2) { i -> ((b[i * 2].toInt() and 0xFF) or (b[i * 2 + 1].toInt() shl 8)).toShort() }

    /** Seconds a slide stays on screen: its narration plus a pause, never shorter than [min]. */
    fun slideSeconds(samples: Int, rate: Int, pause: Double = 0.7, min: Double = 3.0): Double = maxOf(min, samples.toDouble() / rate + pause)

    /** ARGB pixels → I420 planes (BT.601 limited range). [w] and [h] even. */
    fun argbToI420(px: IntArray, w: Int, h: Int): Triple<ByteArray, ByteArray, ByteArray> {
        val y = ByteArray(w * h); val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
        for (j in 0 until h) for (i in 0 until w) {
            val c = px[j * w + i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val bl = c and 0xFF
            y[j * w + i] = (((66 * r + 129 * g + 25 * bl + 128) shr 8) + 16).coerceIn(0, 255).toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                val k = (j / 2) * (w / 2) + i / 2
                u[k] = (((-38 * r - 74 * g + 112 * bl + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                v[k] = (((112 * r - 94 * g - 18 * bl + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            }
        }
        return Triple(y, u, v)
    }
}

/**
 * ai6 (B15): narrated video summary. Each slide of a [SlideDeck] is drawn by [SlideRender], its narration is
 * synthesised with an AI voice through the speech route of the AI client / proxy when `aiVoice` is set (aor: MP3 /
 * WAV decoded to PCM with [MediaExtractor] + [MediaCodec]); any slide the server cannot voice, or every slide when
 * offline / not configured, falls back to the offline system [TextToSpeech] (WAV). Then the video is encoded with [MediaCodec]
 * (H.264, two frames per slide — the frame is held for the narration's length) and the audio with AAC, and both
 * are muxed into an MP4 with [MediaMuxer]. No new dependencies; no network.
 */
object VideoSummary {
    const val W = 1280
    const val H = 720
    const val AUDIO_RATE = 44100

    class Progress(val step: Int, val total: Int)

    /**
     * [aiVoice] = voice name for the AI speech route (blank = the provider default), or null for the device voice only.
     * [usedAi] is told whether at least one slide was voiced by the server.
     */
    suspend fun make(ctx: Context, deck: SlideDeck, out: File, speechRate: Float = 1f, aiVoice: String? = null,
                     usedAi: (Boolean) -> Unit = {}, progress: (Progress) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val n = SlideRender.count(deck)
        val all = listOf(SlideSpec(deck.title, emptyList())) + deck.slides
        val tmp = File(ctx.cacheDir, "ai6_video").apply { deleteRecursively(); mkdirs() }
        val texts = all.mapIndexed { i, s -> if (i == 0) deck.title else SlidesGen.narration(s) }
        // 1. narration per slide: AI voice first (aor, B15), the system TTS for what is still missing
        val ai: List<ShortArray?> = if (aiVoice != null) aiTracks(ctx, texts, aiVoice, speechRate) { i -> progress(Progress(i, n * 2)) } else texts.map { null }
        usedAi(ai.any { it != null })
        val missing = texts.indices.filter { ai[it] == null }
        val device: Map<Int, File?> = if (missing.isEmpty()) emptyMap() else {
            val files = synthAll(ctx, missing.map { texts[it] }, tmp, deck.rtl, speechRate) { k -> progress(Progress(missing[k], n * 2)) }
            missing.mapIndexed { k, i -> i to files[k] }.toMap()
        }
        val tracks = texts.indices.map { i ->
            ai[i] ?: run {
                val pcm = device[i]?.let { runCatching { WavPcm.parse(it.readBytes()) }.getOrNull() }
                if (pcm == null) ShortArray(0) else WavPcm.resample(WavPcm.toMono(pcm), pcm.sampleRate, AUDIO_RATE)
            }
        }
        val seconds = tracks.map { WavPcm.slideSeconds(it.size, AUDIO_RATE) }
        // 2. one continuous audio track with each slide's narration at its slide start
        val totalSamples = seconds.sumOf { (it * AUDIO_RATE).toLong() }.toInt()
        val audio = ShortArray(totalSamples)
        var at = 0
        tracks.forEachIndexed { i, t -> System.arraycopy(t, 0, audio, at, minOf(t.size, audio.size - at)); at += (seconds[i] * AUDIO_RATE).toInt() }
        val (audioFormat, audioSamples) = encodeAac(audio)
        // 3. video, then mux
        encodeVideoAndMux(deck, seconds, audioFormat, audioSamples, out) { i -> progress(Progress(n + i, n * 2)) }
        tmp.deleteRecursively()
        out
    }

    /**
     * aor (B15): each text through [com.smartdocs.editor.core.voice.AiSpeech] (split by the provider's limit), decoded
     * to mono [AUDIO_RATE] PCM. A failed slide is null; when nothing has worked yet and a slide fails (offline, route
     * missing), the rest is skipped so the device voice takes over at once.
     */
    private suspend fun aiTracks(ctx: Context, texts: List<String>, voice: String, rate: Float, step: (Int) -> Unit): List<ShortArray?> {
        val sp = com.smartdocs.editor.core.voice.AiSpeech
        val settings = runCatching { sp.settings(ctx) }.getOrNull() ?: return texts.map { null }
        if (!sp.available(settings)) return texts.map { null }
        val prof = runCatching { sp.profile(ctx, settings) }.getOrNull() ?: return texts.map { null }
        val out = ArrayList<ShortArray?>()
        var anyOk = false
        for ((i, t) in texts.withIndex()) {
            step(i)
            val pieces = sp.split(t, prof.maxChars)
            val track: ShortArray? = try {
                if (pieces.isEmpty()) ShortArray(0)
                else WavPcm.concat(pieces.map { p ->
                    val f = sp.synthesize(ctx, p, voice, rate.coerceIn(0.25f, 4f), settings = settings)
                    decodeToMono(f) ?: throw IllegalStateException("decode")
                })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (track != null) anyOk = true
            out += track
            if (!anyOk && track == null) { while (out.size < texts.size) out += null; break }
        }
        return out
    }

    /** Any audio file (WAV directly, MP3 / AAC / Opus through the platform decoder) to mono [AUDIO_RATE] samples. */
    fun decodeToMono(f: File): ShortArray? {
        val pcm = WavPcm.parse(f.readBytes()) ?: decodeCompressed(f) ?: return null
        return WavPcm.resample(WavPcm.toMono(pcm), pcm.sampleRate, AUDIO_RATE)
    }

    private fun decodeCompressed(f: File): Pcm? {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(f.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec = codec
            codec.configure(fmt, null, null, 0)
            codec.start()
            val pcm = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var done = false; var idle = 0
            while (!done && idle < 500) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(idx, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    o >= 0 -> {
                        idle = 0
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.size > 0) { val b = ByteArray(info.size); buf.position(info.offset); buf.get(b, 0, info.size); pcm.write(b) }
                        codec.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    }
                    else -> idle++
                }
            }
            val b = pcm.toByteArray()
            if (b.isEmpty() || rate <= 0 || ch <= 0) return null
            return Pcm(rate, ch, WavPcm.shorts(b))
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            ex.release()
        }
    }

    private suspend fun synthAll(ctx: Context, texts: List<String>, dir: File, rtl: Boolean, rate: Float, step: (Int) -> Unit): List<File?> {
        val tts = initTts(ctx) ?: return texts.map { null }
        try {
            if (rtl) runCatching { tts.setLanguage(Locale("ar")) }
            tts.setSpeechRate(rate)
            return texts.mapIndexed { i, t ->
                step(i)
                val f = File(dir, "s$i.wav")
                val ok = withTimeoutOrNull(120_000L) { synth(tts, t.take(3900), f) } ?: false
                if (ok && f.length() > 44) f else null
            }
        } finally { runCatching { tts.shutdown() } }
    }

    private suspend fun initTts(ctx: Context): TextToSpeech? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine<TextToSpeech?> { cont ->
                var engine: TextToSpeech? = null
                engine = TextToSpeech(ctx.applicationContext) { status ->
                    if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) engine else null)
                }
                cont.invokeOnCancellation { runCatching { engine?.shutdown() } }
            }
        }
    }

    private suspend fun synth(tts: TextToSpeech, text: String, f: File): Boolean = suspendCancellableCoroutine { cont ->
        val id = "ai6_" + System.nanoTime()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(false) }
        })
        val r = tts.synthesizeToFile(text, Bundle(), f, id)
        if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
    }

    private class Sample(val data: ByteArray, val pts: Long, val flags: Int)

    /** AAC-LC mono encode of the whole track, kept in memory (≈12 KB per second). */
    private fun encodeAac(pcm: ShortArray): Pair<MediaFormat, List<Sample>> {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val out = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0; var inputDone = false; var done = false
            while (!done) {
                if (!inputDone) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = enc.getInputBuffer(idx)!!
                        buf.clear()
                        val pts = pos.toLong() * 1_000_000L / AUDIO_RATE
                        val count = minOf(buf.remaining() / 2, 2048, pcm.size - pos)
                        if (count <= 0) { enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            for (k in 0 until count) { val s = pcm[pos + k].toInt(); buf.put((s and 0xFF).toByte()); buf.put(((s shr 8) and 0xFF).toByte()) }
                            pos += count
                            enc.queueInputBuffer(idx, 0, count * 2, pts, 0)
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = enc.outputFormat
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            val bytes = ByteArray(info.size); buf.position(info.offset); buf.get(bytes, 0, info.size)
                            out += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        enc.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    }
                }
            }
        } finally { runCatching { enc.stop() }; enc.release() }
        return (outFormat ?: throw IllegalStateException("aac format")) to out
    }

    private fun fillImage(img: android.media.Image, y: ByteArray, u: ByteArray, v: ByteArray) {
        val planes = img.planes
        fun plane(p: android.media.Image.Plane, data: ByteArray, w: Int, h: Int) {
            val buf = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
            if (ps == 1) { for (r in 0 until h) { buf.position(r * rs); buf.put(data, r * w, w) } }
            else for (r in 0 until h) for (c in 0 until w) buf.put(r * rs + c * ps, data[r * w + c])
        }
        plane(planes[0], y, W, H); plane(planes[1], u, W / 2, H / 2); plane(planes[2], v, W / 2, H / 2)
    }

    private fun encodeVideoAndMux(deck: SlideDeck, seconds: List<Double>, audioFormat: MediaFormat, audio: List<Sample>, out: File, step: (Int) -> Unit) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 1)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var videoTrack = -1
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            // frames: each slide at its start, held, plus a closing frame at its end so the last slide lasts too
            val starts = ArrayList<Long>(); var t = 0.0
            seconds.forEach { s -> starts += (t * 1_000_000).toLong(); t += s }
            val endUs = (t * 1_000_000).toLong()
            data class Frame(val slide: Int, val pts: Long)
            val frames = ArrayList<Frame>()
            starts.forEachIndexed { i, s -> frames += Frame(i, s); val mid = s + ((seconds[i] * 1_000_000).toLong() / 2); frames += Frame(i, mid) }
            frames += Frame(starts.size - 1, endUs - 50_000)
            var fi = 0; var inputDone = false; var done = false
            var cached: Triple<ByteArray, ByteArray, ByteArray>? = null; var cachedSlide = -1
            val info = MediaCodec.BufferInfo()
            while (!done) {
                if (!inputDone) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        if (fi >= frames.size) { enc.queueInputBuffer(idx, 0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            val fr = frames[fi++]
                            if (fr.slide != cachedSlide) {
                                step(fr.slide)
                                val bmp: Bitmap = SlideRender.bitmap(deck, fr.slide, W, H)
                                val px = IntArray(W * H); bmp.getPixels(px, 0, W, 0, 0, W, H); bmp.recycle()
                                cached = WavPcm.argbToI420(px, W, H); cachedSlide = fr.slide
                                runCatching { enc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
                            }
                            val (y, u, v) = cached!!
                            val img = enc.getInputImage(idx)
                            if (img != null) fillImage(img, y, u, v)
                            else enc.getInputBuffer(idx)?.let { b -> b.clear(); b.put(y); b.put(u); b.put(v) }
                            enc.queueInputBuffer(idx, 0, W * H * 3 / 2, fr.pts, 0)
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(enc.outputFormat)
                        val audioTrack = muxer.addTrack(audioFormat)
                        muxer.start(); started = true
                        val ai = MediaCodec.BufferInfo()
                        audio.forEach { s -> ai.set(0, s.data.size, s.pts, s.flags); muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), ai) }
                    }
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && started) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    }
                }
            }
        } finally {
            runCatching { enc.stop() }; enc.release()
            runCatching { if (started) muxer.stop() }; runCatching { muxer.release() }
        }
        if (!started) throw IllegalStateException("video encoder")
    }
}
