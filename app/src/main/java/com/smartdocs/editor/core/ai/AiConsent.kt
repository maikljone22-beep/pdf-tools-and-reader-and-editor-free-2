package com.smartdocs.editor.core.ai

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Thrown when document content would be sent to an AI service without the user's consent (declined, or no UI to ask). */
class AiConsentRequired(message: String) : AiException(message)

/**
 * Audit C7: the single gate in front of every [AiClient] request that carries user content.
 * Consent is persisted (default: not given). When missing, [ensure] asks through [AiConsentHost]
 * (a one-time dialog) and suspends until the user answers; declining throws [AiConsentRequired].
 */
object AiConsent {
    interface Store { fun get(): Boolean; fun set(value: Boolean) }

    private class PrefsStore(context: Context) : Store {
        private val sp = context.applicationContext.getSharedPreferences("ai_consent", Context.MODE_PRIVATE)
        override fun get() = sp.getBoolean(KEY, false)
        override fun set(value: Boolean) { sp.edit().putBoolean(KEY, value).apply() }
    }

    private class MemoryStore : Store {
        @Volatile private var v = false
        override fun get() = v
        override fun set(value: Boolean) { v = value }
    }

    /** One pending question for the UI host: which provider, and where to put the answer. */
    data class Ask(val provider: String, val answer: CompletableDeferred<Boolean>)

    private const val KEY = "content_consent"
    @Volatile private var store: Store = MemoryStore()
    @Volatile private var declinedMessage: String = "AI consent not given"
    private val mutex = Mutex()
    private val _granted = MutableStateFlow(false)
    private val _pending = MutableStateFlow<Ask?>(null)
    @Volatile internal var hosts = 0

    val granted: StateFlow<Boolean> get() = _granted
    val pending: StateFlow<Ask?> get() = _pending

    fun init(context: Context) {
        install(PrefsStore(context), context.getString(com.smartdocs.editor.R.string.ai_consent_declined))
    }

    /** Test seam / init helper. */
    internal fun install(s: Store, declined: String) { store = s; declinedMessage = declined; _granted.value = s.get(); _pending.value = null; hosts = 0 }

    fun isGranted(): Boolean = store.get()

    /** Grant (from the dialog or Settings) or withdraw (Settings) consent. */
    fun set(value: Boolean) { store.set(value); _granted.value = value }

    /** "api.openai.com" from an endpoint URL; the whole string when it is not a URL. */
    fun providerName(endpoint: String): String =
        runCatching { java.net.URI(endpoint.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: endpoint.trim()

    /** Returns when consent is given; otherwise asks the UI host and waits. Throws [AiConsentRequired] when declined or nobody can ask. */
    suspend fun ensure(provider: String) {
        if (store.get()) return
        if (hosts <= 0) throw AiConsentRequired(declinedMessage)
        mutex.withLock {
            if (store.get()) return   // another request was just allowed
            val ask = Ask(provider, CompletableDeferred())
            _pending.value = ask
            val ok = try { ask.answer.await() } finally { _pending.value = null }
            if (ok) set(true) else throw AiConsentRequired(declinedMessage)
        }
    }

    /** Called by the dialog. */
    fun answer(ask: Ask, allow: Boolean) { ask.answer.complete(allow) }
}
