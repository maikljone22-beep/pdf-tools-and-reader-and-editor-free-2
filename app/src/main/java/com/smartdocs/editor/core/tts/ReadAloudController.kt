package com.smartdocs.editor.core.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * App-wide "read aloud" engine. Wraps one [TextToSpeech] instance, speaks a document page by page
 * (text is pulled lazily through a provider), detects Arabic text and picks the Arabic voice when installed.
 * The UI observes [status]; [ReadAloudService] keeps the process alive with a media notification.
 */
object ReadAloudController {

    enum class State { IDLE, LOADING, PLAYING, PAUSED }

    data class Status(
        val state: State = State.IDLE,
        val page: Int = 0,
        val pageCount: Int = 0,
        val rate: Float = 1f,
        val noArabic: Boolean = false,
        val docTitle: String = "",
        val chunkIndex: Int = 0,
        val chunkCount: Int = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** ai6 (C1): the sentence being spoken, as [start, end) offsets in the provider's text of [page]. */
    data class Spoken(val page: Int, val start: Int, val end: Int)
    private val _spoken = MutableStateFlow<Spoken?>(null)
    val spoken: StateFlow<Spoken?> = _spoken.asStateFlow()
    private var pieces: List<com.smartdocs.editor.core.ai6.ReadingOrder.Piece> = emptyList()
    private var bodyOffset = 0
    /** Reading-order text of the current page (block navigation searches it). */
    private var pageText = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var provider: (suspend (Int) -> String)? = null
    private var chunks: List<String> = emptyList()
    private var chunkIdx = 0
    private var pitch = 1f
    private var arabicChecked = false
    private var arabicAvailable = false
    private var utteranceSeq = 0L

    val isActive: Boolean get() = _status.value.state != State.IDLE

    /** Start (or restart) reading [page] of a document with [pageCount] pages. */
    fun start(context: Context, docTitle: String, page: Int, pageCount: Int, rate: Float, pitch: Float, provider: suspend (Int) -> String, fromChar: Int = 0) {
        appContext = context.applicationContext
        com.smartdocs.editor.core.voice.AiVoiceEngine.enableAgain()
        this.provider = provider
        this.pitch = pitch
        _status.value = Status(State.LOADING, page, pageCount, rate, _status.value.noArabic, docTitle)
        ensureEngine {
            scope.launch {
                loadPage(page, fromChar)
                speakCurrent()
                startService()
            }
        }
    }

    fun pause() {
        if (_status.value.state != State.PLAYING) return
        tts?.stop(); com.smartdocs.editor.core.voice.AiVoiceEngine.stop()
        _status.value = _status.value.copy(state = State.PAUSED)
        updateService()
    }

    fun resume() {
        if (_status.value.state != State.PAUSED) return
        speakCurrent()
        updateService()
    }

    fun toggle() { if (_status.value.state == State.PLAYING) pause() else if (_status.value.state == State.PAUSED) resume() }

    fun stop() {
        tts?.stop(); com.smartdocs.editor.core.voice.AiVoiceEngine.stop()
        chunks = emptyList(); chunkIdx = 0; provider = null; pieces = emptyList(); _spoken.value = null
        _status.value = _status.value.copy(state = State.IDLE, chunkIndex = 0, chunkCount = 0)
        ReadAloudFocus.release()
        appContext?.let { runCatching { it.stopService(Intent(it, ReadAloudService::class.java)) } }
    }

    fun nextPage() = goToPage(_status.value.page + 1)

    // ---- FINAL_CHECK §19: block navigation (piece = the spoken block; paragraphs / headings of the page text) ----

    /** Next spoken block; past the last one of the page, the next page. */
    fun nextBlock() {
        if (!isActive) return
        if (chunkIdx + 1 < chunks.size) { chunkIdx++; speakCurrent() } else advancePage()
    }

    /** Previous spoken block; before the first one, the last block of the previous page. */
    fun prevBlock() {
        if (!isActive) return
        if (chunkIdx > 0) { chunkIdx--; speakCurrent(); return }
        val s = _status.value
        if (s.page <= 0) { speakCurrent(); return }
        scope.launch { loadPage(s.page - 1, 0); chunkIdx = (chunks.size - 1).coerceAtLeast(0); speakCurrent() }
    }

    /** Say the current block again. */
    fun repeatBlock() { if (isActive) speakCurrent() }

    /** Jump to the next / previous paragraph or heading of the page (the next page when none is left). */
    fun nextBlockOf(kind: ReadBlocks.Kind) = jump(kind, forward = true)
    fun prevBlockOf(kind: ReadBlocks.Kind) = jump(kind, forward = false)

    private fun jump(kind: ReadBlocks.Kind, forward: Boolean) {
        if (!isActive) return
        val s = _status.value
        val here = _spoken.value?.takeIf { it.page == s.page }?.start ?: bodyOffset
        val starts = ReadBlocks.starts(pageText, kind)
        val target = if (forward) ReadBlocks.next(starts, here) else ReadBlocks.previous(starts, here)
        when {
            target != null -> scope.launch { loadPage(s.page, target); speakCurrent() }
            forward -> advancePage()
            s.page > 0 -> scope.launch {
                // the last block of that kind on the previous page
                loadPage(s.page - 1, 0)
                val prev = ReadBlocks.starts(pageText, kind).lastOrNull() ?: 0
                if (prev > 0) loadPage(s.page - 1, prev)
                speakCurrent()
            }
            else -> scope.launch { loadPage(s.page, 0); speakCurrent() }
        }
    }
    fun prevPage() = goToPage(_status.value.page - 1)

    fun goToPage(page: Int) {
        val s = _status.value
        if (page !in 0 until s.pageCount || provider == null) return
        tts?.stop(); com.smartdocs.editor.core.voice.AiVoiceEngine.stop()
        _status.value = s.copy(state = State.LOADING, page = page)
        scope.launch { loadPage(page, 0); speakCurrent() }
    }

    fun setRate(rate: Float) {
        val r = rate.coerceIn(0.5f, 2.5f)
        tts?.setSpeechRate(r); com.smartdocs.editor.core.voice.AiVoiceEngine.setRate(r)
        val wasPlaying = _status.value.state == State.PLAYING
        _status.value = _status.value.copy(rate = r)
        if (wasPlaying) { tts?.stop(); speakCurrent() }
    }

    /** NVE-tts: change the voice pitch while reading (0.5..2). */
    fun setPitch(p: Float) {
        pitch = p.coerceIn(0.5f, 2f)
        tts?.setPitch(pitch)
        if (_status.value.state == State.PLAYING) { tts?.stop(); speakCurrent() }
    }

    fun release() {
        stop()
        com.smartdocs.editor.core.voice.AiVoiceEngine.release()
        tts?.shutdown(); tts = null; ready = false; arabicChecked = false
    }

    // ---------------------------------------------------------------- internals

    private fun ensureEngine(onReady: () -> Unit) {
        val ctx = appContext ?: return
        if (ready && tts != null) { onReady(); return }
        tts?.shutdown(); tts = null
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) { _status.value = _status.value.copy(state = State.IDLE); return@TextToSpeech }
            tts?.setOnUtteranceProgressListener(listener)
            scope.launch { onReady() }
        }
    }

    private suspend fun loadPage(page: Int, fromChar: Int) {
        val text = runCatching { withContext(Dispatchers.IO) { provider?.invoke(page) ?: "" } }.getOrDefault("")
        pageText = text
        val body = if (fromChar in 1 until text.length) text.substring(fromChar) else text
        // ai6 (C1): sentence pieces keep a map back to the page text for the on-page sentence highlight
        bodyOffset = if (fromChar in 1 until text.length) fromChar else 0
        pieces = com.smartdocs.editor.core.ai6.ReadingOrder.pieces(body)
        chunks = pieces.map { it.text }
        chunkIdx = 0
        _status.value = _status.value.copy(page = page, chunkIndex = 0, chunkCount = chunks.size)
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        if (chunks.isEmpty()) { advancePage(); return }
        val st = _status.value
        val text = chunks.getOrNull(chunkIdx) ?: run { advancePage(); return }
        applyLanguage(engine, text)
        engine.setSpeechRate(st.rate); engine.setPitch(pitch)
        _status.value = st.copy(state = State.PLAYING, chunkIndex = chunkIdx)
        markSpoken(chunkIdx, 0, wholePiece = false)   // ai6: first sentence of the piece
        val id = "rd_${++utteranceSeq}"
        val ctx = appContext
        ctx?.let { ReadAloudFocus.acquire(it) }      // MODULE view5: pause on call / audio focus loss
        if (ctx != null && com.smartdocs.editor.core.voice.AiVoiceEngine.shouldUse(ctx)) {
            // human-like AI voice (needs internet); falls back to the system voice for the rest of the session
            markSpoken(chunkIdx, 0, wholePiece = true)   // ai6: no word progress from the AI voice: highlight the piece
            com.smartdocs.editor.core.voice.AiVoiceEngine.speak(ctx, text, st.rate, id, next = chunks.getOrNull(chunkIdx + 1)) { uid, ok ->
                if (ok) listener.onDone(uid) else { com.smartdocs.editor.core.voice.AiVoiceEngine.disableForSession(); if (uid == "rd_$utteranceSeq") speakCurrent() }
            }
        } else engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        updateService()
    }

    private fun advancePage() {
        val s = _status.value
        // MODULE view5: repeat page / repeat document
        val next = ReadAloudFocus.nextAfter(s.page, s.pageCount)
        if (next != null) goToPage(next) else stop()
    }

    /** ai6 (C1): publish the sentence of piece [idx] containing char [at] (or the whole piece). */
    private fun markSpoken(idx: Int, at: Int, wholePiece: Boolean) {
        val p = pieces.getOrNull(idx) ?: return
        if (p.map.isEmpty()) return
        val r = if (wholePiece) 0 until p.text.length else com.smartdocs.editor.core.ai6.ReadingOrder.sentenceAt(p, at)
        val a = p.map[r.first.coerceIn(0, p.map.size - 1)]; val z = p.map[r.last.coerceIn(0, p.map.size - 1)]
        _spoken.value = Spoken(_status.value.page, bodyOffset + minOf(a, z), bodyOffset + maxOf(a, z) + 1)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        // ai6 (C1): word progress from the system voice → highlight its sentence
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            scope.launch {
                if (utteranceId != "rd_$utteranceSeq") return@launch
                markSpoken(chunkIdx, start, wholePiece = false)
            }
        }
        override fun onDone(utteranceId: String?) {
            scope.launch {
                // ignore callbacks of utterances that were stopped/replaced (pause, rate change, page jump)
                if (utteranceId != "rd_$utteranceSeq") return@launch
                if (_status.value.state != State.PLAYING) return@launch
                chunkIdx++
                if (chunkIdx < chunks.size) speakCurrent() else advancePage()
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            scope.launch {
                if (utteranceId != "rd_$utteranceSeq" || _status.value.state != State.PLAYING) return@launch
                chunkIdx++
                if (chunkIdx < chunks.size) speakCurrent() else advancePage()
            }
        }
    }

    private fun applyLanguage(engine: TextToSpeech, text: String) {
        val arabic = text.any { it in '\u0600'..'ۿ' }
        if (arabic) {
            if (!arabicChecked) {
                val r = engine.setLanguage(Locale("ar"))
                arabicAvailable = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                arabicChecked = true
                _status.value = _status.value.copy(noArabic = !arabicAvailable)
                if (!arabicAvailable) engine.language = Locale.getDefault()
            } else if (arabicAvailable) engine.language = Locale("ar")
        } else {
            val en = engine.setLanguage(Locale.ENGLISH)
            if (en == TextToSpeech.LANG_MISSING_DATA || en == TextToSpeech.LANG_NOT_SUPPORTED) engine.language = Locale.getDefault()
        }
    }

    /** Split text into sentence-ish pieces of at most ~400 characters. */
    private fun chunk(text: String, max: Int = 400): List<String> {
        val clean = text.replace('\r', '\n').replace(Regex("[ \t]+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        val parts = clean.split(Regex("(?<=[.!?؟。\n])\\s+"))
        for (p in parts) {
            val piece = p.trim(); if (piece.isEmpty()) continue
            if (sb.length + piece.length + 1 > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            if (piece.length > max) { piece.chunked(max).forEach { out += it } } else { if (sb.isNotEmpty()) sb.append(' '); sb.append(piece) }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun startService() {
        val ctx = appContext ?: return
        // never start a foreground service for an idle controller: the service would have to stop itself
        // without calling startForeground(), which crashes the process on API 26+
        if (_status.value.state == State.IDLE) return
        runCatching {
            val i = Intent(ctx, ReadAloudService::class.java).setAction(ReadAloudService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private fun updateService() {
        val ctx = appContext ?: return
        if (_status.value.state == State.IDLE) return
        runCatching { ctx.startService(Intent(ctx, ReadAloudService::class.java).setAction(ReadAloudService.ACTION_UPDATE)) }
    }
}
