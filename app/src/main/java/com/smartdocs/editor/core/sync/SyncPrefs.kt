package com.smartdocs.editor.core.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-wide transfer preferences (share6). The settings screen can show them with `ui.cloud.SyncSettingsSection()`.
 *  - [wifiOnly]: cloud transfers (new sync folders, app-data sync, review checks in the background) and the Wi‑Fi
 *    transfer server use Wi‑Fi only (default) or also mobile data / other networks.
 *  - [autoSync]: master switch for automatic sync (periodic, near-real-time, on save). "Sync now" always works.
 *  - [liveSync]: near-real-time sync while the app is open (watch local folders + cloud change feeds).
 */
object SyncPrefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    /** Bumped on every change so Compose screens can re-read. */
    val version: StateFlow<Int> = _version

    fun wifiOnly(c: Context): Boolean = appTransferWifiOnly || p(c).getBoolean("wifi_only", true)
    fun setWifiOnly(c: Context, v: Boolean) { p(c).edit().putBoolean("wifi_only", v).apply(); _version.value++ }

    /** Mirrors of the app settings (Settings screen: AppSettings.autoSync / transferNetwork), kept fresh by [SyncLive.install]. */
    @Volatile var appAutoSync: Boolean = true
    @Volatile var appTransferWifiOnly: Boolean = true

    fun autoSync(c: Context): Boolean = appAutoSync && p(c).getBoolean("auto", true)
    fun setAutoSync(c: Context, v: Boolean) { p(c).edit().putBoolean("auto", v).apply(); _version.value++; SyncWorker.schedule(c) }

    fun liveSync(c: Context): Boolean = p(c).getBoolean("live", true)
    fun setLiveSync(c: Context, v: Boolean) { p(c).edit().putBoolean("live", v).apply(); _version.value++; SyncLive.refresh(c) }

    /** Wi‑Fi transfer (local web page): listen on Wi‑Fi / hotspot only (default) or on every network interface. */
    fun wifiServerOnly(c: Context): Boolean = p(c).getBoolean("wifi_server_only", true)
    fun setWifiServerOnly(c: Context, v: Boolean) { p(c).edit().putBoolean("wifi_server_only", v).apply(); _version.value++ }

    fun syncOnSave(c: Context): Boolean = p(c).getBoolean("on_save", true)
    fun setSyncOnSave(c: Context, v: Boolean) { p(c).edit().putBoolean("on_save", v).apply(); _version.value++ }
}
