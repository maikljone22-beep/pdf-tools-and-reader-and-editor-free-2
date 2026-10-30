package com.smartdocs.editor.core.util

import android.content.Context
import android.os.Build
import com.smartdocs.editor.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to a file so the next launch can offer to send it.
 * Keeps the last report only; the default handler still runs afterwards (the system crash dialog is unchanged).
 */
object CrashLog {
    private const val NAME = "last_crash.txt"
    private const val DOC = "last_crash_doc.txt"
    /** NVE-shell: the document open in the reader (set by the reader), so the crash report can attach it. */
    @Volatile var currentDocument: String? = null

    /** The document that was open when the app crashed (null: none / already cleared). */
    fun crashedDocument(context: Context): File? = File(context.filesDir, DOC).takeIf { it.exists() }?.readText()?.trim()?.let { File(it) }?.takeIf { it.exists() }

    fun file(context: Context) = File(context.filesDir, NAME)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            com.smartdocs.editor.core.errors.ErrorCenter.record(error, "crash", crash = true)
            runCatching {
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    pw.println("Thread: ${thread.name}")
                    currentDocument?.let { pw.println("Document: ${File(it).name}") }   // NVE-shell
                    pw.println()
                    error.printStackTrace(pw)
                }
                file(app).writeText(sw.toString())
                DebugLog.log("crash", sw.toString())   // fc-settings: debug mode keeps the crash in its log too
                currentDocument?.let { File(app.filesDir, DOC).writeText(it) } ?: File(app.filesDir, DOC).delete()   // NVE-shell
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? = file(context).takeIf { it.exists() && it.length() > 0 }?.readText()

    fun clear(context: Context) { file(context).delete(); File(context.filesDir, DOC).delete() }
}
