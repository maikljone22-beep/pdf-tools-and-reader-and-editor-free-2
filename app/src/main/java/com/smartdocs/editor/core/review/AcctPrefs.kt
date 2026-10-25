package com.smartdocs.editor.core.review

import android.content.Context
import android.content.SharedPreferences
import com.smartdocs.editor.BuildConfig

/**
 * Small synchronous preferences used by the account module (onboarding, consent, coach marks, rating counters).
 * SharedPreferences (not DataStore) on purpose: the first-run gate must be known before the first frame,
 * otherwise returning users would see the onboarding flash while DataStore loads.
 */
object AcctPrefs {
    /** Bump when the privacy policy text changes materially: users see the consent page again. */
    const val CONSENT_VERSION = 1

    private const val FILE = "acct_prefs"
    fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- first run / consent ----
    fun onboardingDone(context: Context): Boolean = prefs(context).getInt("consent_version", 0) >= CONSENT_VERSION

    fun completeOnboarding(context: Context, analyticsOptIn: Boolean) {
        prefs(context).edit()
            .putInt("consent_version", CONSENT_VERSION)
            .putBoolean("analytics_opt_in", analyticsOptIn)
            .putLong("consent_time", System.currentTimeMillis())
            .apply()
        installTime(context) // make sure the install time is recorded
    }

    /** Anonymous usage statistics opt-in. Default OFF. No collector ships today; this only records the choice. */
    fun analyticsOptIn(context: Context): Boolean = prefs(context).getBoolean("analytics_opt_in", false)
    fun setAnalyticsOptIn(context: Context, on: Boolean) = prefs(context).edit().putBoolean("analytics_opt_in", on).apply()

    // ---- coach marks ----
    fun coachSeen(context: Context, key: String): Boolean = prefs(context).getBoolean("coach_$key", false)
    fun setCoachSeen(context: Context, key: String) = prefs(context).edit().putBoolean("coach_$key", true).apply()
    fun resetCoachMarks(context: Context) {
        val p = prefs(context); val e = p.edit()
        p.all.keys.filter { it.startsWith("coach_") }.forEach { e.remove(it) }
        e.apply()
    }

    // ---- install time (for the rating prompt) ----
    fun installTime(context: Context): Long {
        val p = prefs(context)
        val t = p.getLong("install_time", 0L)
        if (t > 0) return t
        val first = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrDefault(System.currentTimeMillis())
        p.edit().putLong("install_time", first).apply()
        return first
    }

    // ---- local profile (account is local; Google is only used for Drive authorization) ----
    fun profilePhoto(context: Context): String? = prefs(context).getString("profile_photo", null)
    fun setProfilePhoto(context: Context, path: String?) = prefs(context).edit().putString("profile_photo", path).apply()
}

/**
 * Reads optional BuildConfig fields by reflection so this module compiles before the lead adds them to
 * `app/build.gradle.kts` (see MODULE_acct.md). Missing field → default.
 */
object BuildCfg {
    fun string(name: String, default: String = ""): String = runCatching {
        BuildConfig::class.java.getField(name).get(null) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: default
}
