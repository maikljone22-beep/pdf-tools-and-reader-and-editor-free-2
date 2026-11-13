package com.smartdocs.editor.core.util

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Where the time goes between the process starting and the first screen being drawn. Each step of
 * SmartDocsApp.onCreate is timed, and one line is logged under the tag StartupTrace when the first frame is ready
 * (the CI speed job reads it). Cold starts only; main thread only.
 */
object StartupTrace {
    private val steps = ArrayList<Pair<String, Long>>()   // name, microseconds
    private var appStart = 0L
    private var appEnd = 0L
    private var activityStart = 0L
    private var reported = false

    /** The line of the last cold start. */
    @Volatile var last: String? = null; private set

    fun appCreateBegins() { appStart = SystemClock.elapsedRealtime() }
    fun appCreateEnds() { appEnd = SystemClock.elapsedRealtime() }
    fun activityCreateBegins() { if (activityStart == 0L) activityStart = SystemClock.elapsedRealtime() }

    inline fun <T> step(name: String, block: () -> T): T {
        val t = SystemClock.elapsedRealtimeNanos()
        try { return block() } finally { record(name, (SystemClock.elapsedRealtimeNanos() - t) / 1000) }
    }

    @PublishedApi internal fun record(name: String, micros: Long) { steps.add(name to micros) }

    /** Call when the first frame is about to be drawn. Logs once per process. */
    fun firstFrame() {
        if (reported || appStart == 0L) return
        reported = true
        val line = summary(Process.getStartElapsedRealtime(), appStart, appEnd, activityStart, SystemClock.elapsedRealtime(), steps)
        last = line
        runCatching { Log.i("StartupTrace", line) }
    }

    /** Pure: the report line. Times are elapsed-realtime milliseconds; [steps] are (name, microseconds), listed slowest first. */
    fun summary(processStart: Long, appStart: Long, appEnd: Long, activityStart: Long, firstFrame: Long, steps: List<Pair<String, Long>>): String {
        val slow = steps.sortedByDescending { it.second }.filter { it.second >= 1000 }
            .joinToString(", ") { (n, us) -> String.format(Locale.US, "%s %.1f", n, us / 1000.0) }
        return "first frame after ${firstFrame - processStart} ms | process to app ${appStart - processStart} | app setup ${appEnd - appStart}" +
            " | app to activity ${activityStart - appEnd} | activity to first frame ${firstFrame - activityStart}" +
            " | setup steps over 1 ms: ${slow.ifEmpty { "none" }}"
    }
}
