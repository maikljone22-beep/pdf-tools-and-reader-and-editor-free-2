package com.smartdocs.editor.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf

/**
 * fc-settings: the Settings switches added for the final check (Foxit §33 / §37): ConnectedPDF off and the debug log.
 * Own SharedPreferences "fcs_prefs", read synchronously; [version] makes composables recompose on a change.
 * Before [init] (JVM tests) every getter returns its default.
 */
object FcsPrefs {
    private var sp: SharedPreferences? = null
    val version = mutableIntStateOf(0)

    fun init(context: Context) { if (sp == null) sp = context.applicationContext.getSharedPreferences("fcs_prefs", Context.MODE_PRIVATE) }

    private fun b(k: String, d: Boolean): Boolean { version.intValue; return sp?.getBoolean(k, d) ?: d }
    private fun setB(k: String, v: Boolean) { sp?.edit()?.putBoolean(k, v)?.apply(); version.intValue++ }

    /** Foxit "Disable ConnectedPDF": no connected-document server call (status, register, version check, tracking). */
    val connectedOff: Boolean get() = b("connected_off", false)
    fun setConnectedOff(v: Boolean) = setB("connected_off", v)

    /** Foxit "Startup: debug mode": the app keeps a debug log ([com.smartdocs.editor.core.util.DebugLog]). */
    val debugLog: Boolean get() = b("debug_log", false)
    fun setDebugLog(v: Boolean) = setB("debug_log", v)
}
