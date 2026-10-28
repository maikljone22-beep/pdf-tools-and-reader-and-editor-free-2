package com.smartdocs.editor.core.util

import android.content.Context
import android.os.Build
import com.smartdocs.editor.BuildConfig
import com.smartdocs.editor.core.data.FcsPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * fc-settings: debug mode (Foxit §37 "Startup: debug mode"). While Settings → Debug log is on, [log] lines and
 * uncaught errors go to `files/debug_log.txt` (kept under [MAX_BYTES]: the oldest half is dropped). [export] writes
 * one shareable text file with the device header, that log and the app's own logcat (`logcat -d --pid`).
 */
object DebugLog {
    private const val NAME = "debug_log.txt"
    const val MAX_BYTES = 512 * 1024
    @Volatile private var dir: File? = null

    fun install(context: Context) {
        dir = context.applicationContext.filesDir
        log("app", "start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    val enabled: Boolean get() = FcsPrefs.debugLog

    fun file(context: Context) = File(context.filesDir, NAME)

    /** Appends one line while debug mode is on (never throws). */
    fun log(tag: String, msg: String) {
        if (!enabled) return
        val d = dir ?: return
        runCatching {
            synchronized(this) {
                val f = File(d, NAME)
                f.appendText(line(System.currentTimeMillis(), tag, msg))
                if (f.length() > MAX_BYTES) f.writeText(trimmed(f.readText(), MAX_BYTES))
            }
        }
    }

    fun error(tag: String, t: Throwable) = log(tag, t.stackTraceToString())

    fun clear(context: Context) { runCatching { file(context).delete() } }

    /** One log line: "yyyy-MM-dd HH:mm:ss.SSS [tag] message". */
    internal fun line(time: Long, tag: String, msg: String): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(time)) + " [" + tag + "] " + msg.replace("\r", "") + "\n"

    /** [text] cut to about half of [max] characters, starting at a line start (the newest lines are kept). */
    internal fun trimmed(text: String, max: Int): String {
        if (text.length <= max) return text
        val from = text.length - max / 2
        val nl = text.indexOf('\n', from)
        return if (nl < 0 || nl + 1 >= text.length) text.substring(from) else text.substring(nl + 1)
    }

    /** A text file in the cache with the header, the debug log and the app's logcat; shareable through the FileProvider. */
    fun export(context: Context): File {
        val out = File(context.cacheDir, "smartdocs_debug_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
        val logcat = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=" + android.os.Process.myPid(), "-t", "3000").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
        }.getOrElse { "logcat unavailable: ${it.message}" }
        out.writeText(buildString {
            append("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")
            append("==== debug log ====\n")
            append(runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty())
            append("\n==== logcat ====\n")
            append(logcat)
        })
        return out
    }
}
