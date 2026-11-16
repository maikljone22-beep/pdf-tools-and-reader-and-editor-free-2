package com.smartdocs.editor.core.updates

import android.content.Context
import com.smartdocs.editor.BuildConfig

object UpdatePreferences {
    private const val RELEASES = "https://github.com/maikljone22-beep/pdf-tools-and-reader-and-editor-free-2/releases/"
    fun defaultUrl() = RELEASES + if (BuildConfig.DEBUG) "download/test/update-test.json" else "latest/download/update.json"
    private fun prefs(c: Context) = c.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    fun url(c: Context): String = prefs(c).getString("url", null) ?: defaultUrl()
    fun saveUrl(c: Context, url: String) { prefs(c).edit().putString("url", UpdateUrls.requireHttps(url)).apply() }
    fun automatic(c: Context) = prefs(c).getBoolean("automatic", true)
    fun setAutomatic(c: Context, enabled: Boolean) { prefs(c).edit().putBoolean("automatic", enabled).apply() }
    fun shouldCheck(c: Context, now: Long): Boolean = automatic(c) && now - prefs(c).getLong("attempt", 0) >= 24 * 60 * 60 * 1000L
    fun markAttempt(c: Context, now: Long) { prefs(c).edit().putLong("attempt", now).apply() }
}
