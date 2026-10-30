package com.smartdocs.editor.core.errors

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartdocs.editor.BuildConfig
import com.smartdocs.editor.core.review.BuildCfg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sends errors to the developer's error server (`ai-proxy/errors/`), which files each distinct error as a GitHub issue
 * (repeats become comments). Configured by `errorsUrl` / `errorsToken` in cloud.properties → BuildConfig ERRORS_URL /
 * ERRORS_TOKEN; without a URL nothing is sent and the error centre offers export / share only.
 */
object ErrorReporter {
    private const val PREFS = "error_center"
    private const val KEY_AUTO = "auto_send"
    private const val WORK = "error-center-send"

    val url: String get() = BuildCfg.string("ERRORS_URL").trimEnd('/')
    val configured: Boolean get() = url.isNotEmpty()

    fun autoSend(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, 0).getBoolean(KEY_AUTO, true)
    fun setAutoSend(ctx: Context, on: Boolean) { ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_AUTO, on).apply(); if (on) schedule(ctx) }

    /** Queue a send of the unsent errors for when the device is online (no-op without a server or with auto-send off). */
    fun schedule(ctx: Context) {
        if (!configured || !autoSend(ctx) || ErrorCenter.unsentCount() == 0) return
        runCatching {
            val req = OneTimeWorkRequestBuilder<SendWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(20, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
        }
    }

    /** Sends [entries] now; returns how many were accepted, or throws with the reason. */
    suspend fun send(entries: List<ErrorCenter.Entry>): Int = withContext(Dispatchers.IO) {
        check(configured) { "error server not configured" }
        if (entries.isEmpty()) return@withContext 0
        var sent = 0
        entries.chunked(20).forEach { batch ->
            val body = JsonObject(mapOf(
                "app" to JsonPrimitive(BuildConfig.APPLICATION_ID),
                "version" to JsonPrimitive("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                "device" to JsonPrimitive(ErrorCenter.device()),
                "errors" to JsonArray(batch.map { e -> JsonObject(mapOf(
                    "id" to JsonPrimitive(e.id), "kind" to JsonPrimitive(e.kind), "area" to JsonPrimitive(e.area),
                    "title" to JsonPrimitive(e.title), "detail" to JsonPrimitive(e.detail.take(6000)),
                    "count" to JsonPrimitive(e.count), "firstAt" to JsonPrimitive(e.firstAt), "lastAt" to JsonPrimitive(e.lastAt),
                    "version" to JsonPrimitive(e.version),
                )) }),
            )).toString()
            val req = Request.Builder().url("$url/report")
                .header("X-App-Token", BuildCfg.string("ERRORS_TOKEN", BuildCfg.string("AI_APP_TOKEN")))
                .post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { r -> check(r.isSuccessful) { "HTTP ${r.code}" } }
            ErrorCenter.markSent(batch.map { it.id })
            sent += batch.size
        }
        sent
    }

    private val client by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    class SendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            ErrorCenter.install(applicationContext)
            if (!configured || !autoSend(applicationContext)) return Result.success()
            val unsent = ErrorCenter.all().filter { it.unsent }
            return runCatching { send(unsent) }.fold({ Result.success() }, { if (runAttemptCount < 3) Result.retry() else Result.failure() })
        }
    }
}
