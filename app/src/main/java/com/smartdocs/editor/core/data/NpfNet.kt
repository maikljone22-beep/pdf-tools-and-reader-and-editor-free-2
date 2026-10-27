package com.smartdocs.editor.core.data

import okhttp3.Interceptor
import java.io.IOException

/**
 * nav-prefs (PREF-7): offline mode. One switch in Settings turns off every feature that needs the Internet.
 * The gate is in three places:
 *  - [interceptor] is added to every OkHttp client of the app (AI, cloud / collab / connected, licence, form submit,
 *    Google account, OCR language download, timestamp server), so no request leaves the device;
 *  - background work (sync, cloud upload, review checks, licence check) asks [allowed] and stops quietly;
 *  - the UI hides AI entries ([com.smartdocs.editor.core.gaps6.AiGate]) and shows [OfflineException]'s message.
 */
object NpfNet {
    /** Thrown by the HTTP clients while offline mode is on. */
    class OfflineException : IOException("offline mode is on")

    fun allowed(): Boolean = !NpfPrefs.offline

    /** Throws [OfflineException] when offline mode is on. */
    fun check() { if (!allowed()) throw OfflineException() }

    val interceptor: Interceptor = Interceptor { chain: Interceptor.Chain ->
        check()
        chain.proceed(chain.request())
    }
}
