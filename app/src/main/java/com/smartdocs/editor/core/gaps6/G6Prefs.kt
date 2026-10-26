package com.smartdocs.editor.core.gaps6

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.smartdocs.editor.core.data.AppSettings

/** AI master switch (Settings → AI assistant). Every AI entry point asks [enabled] / [visibleTools]. */
object AiGate {
    fun enabled(s: AppSettings): Boolean = s.aiEnabled
    /** Tool ids that need AI (Tools tab, wizard, command search). */
    val TOOL_IDS: Set<String> = ToolWizard.AI_TOOLS + "ai_settings"
    fun allowsTool(s: AppSettings, id: String): Boolean = s.aiEnabled || id !in TOOL_IDS
    fun <T> filter(s: AppSettings, items: List<T>, id: (T) -> String): List<T> = if (s.aiEnabled) items else items.filter { id(it) !in TOOL_IDS }
}

/** Layout direction override (Settings → layout direction). Replaces `settings.language == "ar"` in MainActivity. */
object LayoutDir {
    const val AUTO = "auto"; const val RTL = "rtl"; const val LTR = "ltr"
    fun isRtl(s: AppSettings): Boolean = when (s.layoutDirection) {
        RTL -> true
        LTR -> false
        else -> s.language == "ar"
    }
}

/** File-transfer network policy (Settings → transfer network): "wifi" = Wi-Fi/Ethernet only, "any" = also mobile data. */
object TransferPolicy {
    const val WIFI = "wifi"; const val ANY = "any"

    enum class Net { NONE, WIFI, CELLULAR, OTHER }

    fun current(context: Context): Net {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Net.NONE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Net.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Net.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Net.CELLULAR
            else -> Net.OTHER
        }
    }

    /** May a transfer start now? Wi-Fi only → refuse on mobile data. */
    fun allowed(context: Context, s: AppSettings): Boolean {
        val n = current(context)
        return when (s.transferNetwork) {
            ANY -> n != Net.NONE
            else -> n == Net.WIFI || n == Net.OTHER
        }
    }
}

/**
 * Reader view options stored by ui/reader/view/ViewExtras in SharedPreferences "view" (same keys), so the Settings
 * screen can change them without touching the reader: keep-screen-on timeout and orientation lock.
 */
object ReaderViewPrefs {
    private const val PREFS = "view"
    private const val K_TIMEOUT = "screen_on_time"
    private const val K_LOCK = "orientation_lock"
    private const val K_LOCK_LAND = "orientation_lock_landscape"
    /** Settings choices in seconds; -1 = never turn the screen off. */
    val TIMEOUTS = listOf(60, 300, 600, 1800, -1)

    enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun timeout(c: Context): Int = p(c).getInt(K_TIMEOUT, 600)
    fun setTimeout(c: Context, sec: Int) = p(c).edit().putInt(K_TIMEOUT, sec).apply()
    fun orientation(c: Context): Orientation = when {
        !p(c).getBoolean(K_LOCK, false) -> Orientation.AUTO
        p(c).getBoolean(K_LOCK_LAND, false) -> Orientation.LANDSCAPE
        else -> Orientation.PORTRAIT
    }
    fun setOrientation(c: Context, o: Orientation) =
        p(c).edit().putBoolean(K_LOCK, o != Orientation.AUTO).putBoolean(K_LOCK_LAND, o == Orientation.LANDSCAPE).apply()
}
