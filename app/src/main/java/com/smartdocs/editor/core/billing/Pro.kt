package com.smartdocs.editor.core.billing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Play Console product ids. Create two auto-renewing subscriptions with these ids (one base plan each). */
object ProProducts {
    const val MONTHLY = "pro_monthly"
    const val YEARLY = "pro_yearly"
    val ALL = listOf(MONTHLY, YEARLY)
}

/**
 * Pro entitlement. Sources: a Play subscription (verified with Play Billing on every app start) or an offline
 * activation code ([ActivationCodes]). The entitlement is cached so the app works offline.
 *
 * Gating is OFF by default ([ENFORCE] = false): nothing is locked until Play Console products exist.
 * Feature code should ask `Pro.allows(context)` (true whenever gating is off) rather than `Pro.isActive`.
 */
object Pro {
    /** Flip to true only after the Play Console subscriptions are live. */
    const val ENFORCE = false

    /** How long a Play entitlement stays valid without re-verification (offline grace). */
    private const val PLAY_GRACE_MS = 30L * 24 * 3600 * 1000

    enum class Source { NONE, PLAY, CODE }
    data class Entitlement(val source: Source = Source.NONE, val product: String? = null, val expires: Long = 0L, val verifiedAt: Long = 0L, val autoRenewing: Boolean = false) {
        fun activeAt(now: Long): Boolean = when (source) {
            Source.NONE -> false
            Source.PLAY -> now - verifiedAt < PLAY_GRACE_MS
            Source.CODE -> expires == 0L || now < expires
        }
    }

    private val _state = MutableStateFlow(Entitlement())
    val state: StateFlow<Entitlement> = _state
    private val _active = MutableStateFlow(false)
    /** Observable "has Pro" flag. */
    val isActiveFlow: StateFlow<Boolean> = _active
    @Volatile private var loaded = false

    val isActive: Boolean get() = _active.value

    /** True when a Pro-only feature may be used: gating disabled, or the user has Pro. */
    fun allows(context: Context): Boolean { load(context); return !ENFORCE || _active.value }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("pro_entitlement", Context.MODE_PRIVATE)

    fun load(context: Context): Entitlement {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val p = prefs(context)
                val e = Entitlement(
                    source = runCatching { Source.valueOf(p.getString("source", "NONE")!!) }.getOrDefault(Source.NONE),
                    product = p.getString("product", null), expires = p.getLong("expires", 0L), verifiedAt = p.getLong("verified", 0L),
                    autoRenewing = p.getBoolean("renew", false),
                )
                publish(e); loaded = true
            }
        }
        return _state.value
    }

    internal fun set(context: Context, e: Entitlement) {
        prefs(context).edit().putString("source", e.source.name).putString("product", e.product).putLong("expires", e.expires)
            .putLong("verified", e.verifiedAt).putBoolean("renew", e.autoRenewing).apply()
        publish(e); loaded = true
    }

    private fun publish(e: Entitlement) { _state.value = e; _active.value = e.activeAt(System.currentTimeMillis()) }

    /** Removes an activation-code entitlement (a Play entitlement is controlled by Play). */
    fun deactivateCode(context: Context) { if (load(context).source == Source.CODE) set(context, Entitlement()) }
}
