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
 * Pro entitlement. Sources: a Play subscription or an activation code ([ActivationCodes]). Cached so the app works offline.
 *
 * Two modes:
 *  - client mode (no licence server, `LICENSE_URL` empty): Play purchases are trusted for 30 days after the last
 *    Play check, codes are verified offline (HMAC secret in the APK) — the original behaviour;
 *  - server mode ([LicenseClient.configured]): only Ed25519-signed licences count ([LicenseRules]); a Play purchase
 *    the server could not verify yet counts for 3 days ("provisional"); unsigned codes do not count.
 *
 * Gating is OFF by default ([ENFORCE] = false). Feature code should ask `Pro.allows(context)`.
 */
object Pro {
    /** Flip to true only after the Play Console subscriptions are live. */
    const val ENFORCE = false

    /** Client mode: how long a Play entitlement stays valid without re-verification (offline grace). */
    private const val PLAY_GRACE_MS = 30L * 24 * 3600 * 1000

    enum class Source { NONE, PLAY, CODE }
    data class Entitlement(
        val source: Source = Source.NONE, val product: String? = null, val expires: Long = 0L, val verifiedAt: Long = 0L,
        val autoRenewing: Boolean = false,
        /** Signed licence token from the licence server (server mode). */
        val license: String? = null,
        /** Play purchase not yet confirmed by the server. */
        val provisional: Boolean = false,
    ) {
        /** Claims of [license] after signature + device verification (set by [Pro.load] / [Pro.set]). */
        @Transient internal var claims: LicenseToken.Claims? = null
        @Transient internal var device: String = ""
        @Transient internal var lastCheck: Long = 0L

        fun activeAt(now: Long): Boolean {
            if (source == Source.NONE) return false
            if (LicenseClient.configured) {
                if (claims != null) return LicenseRules.signedActive(claims, device, lastCheck, now)
                return source == Source.PLAY && provisional && LicenseRules.provisionalActive(verifiedAt, now)
            }
            return when (source) {
                Source.NONE -> false
                Source.PLAY -> now - verifiedAt < PLAY_GRACE_MS
                Source.CODE -> expires == 0L || now < expires
            }
        }
    }

    private val _state = MutableStateFlow(Entitlement())
    val state: StateFlow<Entitlement> = _state
    private val _active = MutableStateFlow(false)
    /** Observable "has Pro" flag (refreshed on every [load]/[set] and when [isActive] is read). */
    val isActiveFlow: StateFlow<Boolean> = _active
    @Volatile private var loaded = false

    /** Re-evaluated against the clock on every read (grace periods end while the app runs). */
    val isActive: Boolean get() {
        val now = _state.value.activeAt(System.currentTimeMillis())
        if (_active.value != now) _active.value = now
        return now
    }

    /** True when a Pro-only feature may be used: gating disabled, or the user has Pro. */
    fun allows(context: Context): Boolean { load(context); return !ENFORCE || isActive }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("pro_entitlement", Context.MODE_PRIVATE)

    fun load(context: Context): Entitlement {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val p = prefs(context)
                val e = Entitlement(
                    source = runCatching { Source.valueOf(p.getString("source", "NONE")!!) }.getOrDefault(Source.NONE),
                    product = p.getString("product", null), expires = p.getLong("expires", 0L), verifiedAt = p.getLong("verified", 0L),
                    autoRenewing = p.getBoolean("renew", false), license = p.getString("license", null), provisional = p.getBoolean("provisional", false),
                )
                publish(context, e); loaded = true
            }
        }
        return _state.value
    }

    internal fun set(context: Context, e: Entitlement) {
        prefs(context).edit().putString("source", e.source.name).putString("product", e.product).putLong("expires", e.expires)
            .putLong("verified", e.verifiedAt).putBoolean("renew", e.autoRenewing).putString("license", e.license)
            .putBoolean("provisional", e.provisional).apply()
        publish(context, e); loaded = true
    }

    /** Re-verifies the stored licence (signature and device) before it is used. */
    private fun publish(context: Context, e: Entitlement) {
        if (LicenseClient.configured) {
            e.device = LicenseClient.deviceId(context)
            e.claims = LicenseClient.claimsFor(context, e.license)
            e.lastCheck = LicenseClient.prefs(context).getLong("last_check", 0L)
        }
        _state.value = e; _active.value = e.activeAt(System.currentTimeMillis())
    }

    /** Called after a successful server check: refreshes the grace window. */
    internal fun touchChecked(context: Context) { publish(context, load(context).copy()) }

    /** The activation code entered on this device (kept so it can be moved to the server later). */
    fun storedCode(context: Context): String? = prefs(context).getString("code", null)
    internal fun storeCode(context: Context, code: String) = prefs(context).edit().putString("code", code).apply()

    /** Removes an activation-code entitlement (a Play entitlement is controlled by Play). */
    fun deactivateCode(context: Context) { if (load(context).source == Source.CODE) set(context, Entitlement()) }
}
