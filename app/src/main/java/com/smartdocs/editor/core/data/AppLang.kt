package com.smartdocs.editor.core.data

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * fc-settings: the app language — "system" (follow the device), "ar" or "en".
 *
 * Android 13+ keeps the choice as the per-app language ([android.app.LocaleManager], also editable in the system
 * "App languages" screen through `res/xml/locales_config.xml`). Older versions get it through
 * [wrap] in `MainActivity.attachBaseContext`, read synchronously from the "fcs_lang" SharedPreferences mirror.
 * [AppSettings.language] holds the same value for the Settings row and the code that asks "is the UI Arabic?".
 */
object AppLang {
    const val SYSTEM = "system"; const val AR = "ar"; const val EN = "en"
    private const val PREFS = "fcs_lang"; private const val KEY = "lang"

    /** Normalises a stored value: anything that is not "ar" / "en" follows the system. */
    fun normalize(v: String?): String = when (v) { AR, EN -> v; else -> SYSTEM }

    /**
     * The value for a store that has no "fcs_app_lang" yet. The old "lang" key defaulted to "ar" and was written
     * with every settings change, so "ar" there is not a real choice (the UI followed the device anyway): only an
     * explicit "en" is kept.
     */
    fun migrate(oldLang: String?): String = if (oldLang == EN) EN else SYSTEM

    /** "ar" or "en" for [setting], resolving "system" with the language of [systemLocale]. */
    fun resolve(setting: String, systemLocale: Locale): String = when (normalize(setting)) {
        AR -> AR; EN -> EN
        else -> if (systemLocale.language == AR) AR else EN
    }

    /** The device language (not the app's own override). */
    fun systemLocale(): Locale = runCatching { android.content.res.Resources.getSystem().configuration.locales[0] }.getOrNull() ?: Locale.getDefault()

    /** True when the UI shows Arabic text for these settings. */
    fun isArabic(s: AppSettings): Boolean = resolve(s.language, systemLocale()) == AR

    /** The mirror value (synchronous; for attachBaseContext and code without the settings at hand). */
    fun stored(ctx: Context): String = normalize(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /** False until the first [apply] / [remember] (first start after the update). */
    fun hasStored(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

    /** Context with the chosen language for Android 12 and older (13+ uses the per-app language). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val lang = stored(base)
        if (lang == SYSTEM) return base
        val loc = Locale(lang)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(loc))
        return base.createConfigurationContext(cfg)
    }

    /** Applies [lang] now: per-app language on 13+, otherwise the mirror + recreating [activity]. */
    fun apply(activity: Activity, lang: String) {
        val v = normalize(lang)
        val changed = stored(activity) != v
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val lm = activity.getSystemService(android.app.LocaleManager::class.java)
                lm.applicationLocales = if (v == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(v)
            }
        } else if (changed) activity.recreate()
    }

    /**
     * Android 13+: the language picked in the system "App languages" screen, as "ar" / "en" / "system";
     * null when unknown (older versions).
     */
    fun fromSystemPicker(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        return runCatching {
            val l = ctx.getSystemService(android.app.LocaleManager::class.java).applicationLocales
            if (l.isEmpty) SYSTEM else normalize(l[0].language)
        }.getOrNull()
    }

    /** Keeps the mirror equal to [v] without applying it (used when the system picker changed it). */
    fun remember(ctx: Context, v: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, normalize(v)).apply() }
}
