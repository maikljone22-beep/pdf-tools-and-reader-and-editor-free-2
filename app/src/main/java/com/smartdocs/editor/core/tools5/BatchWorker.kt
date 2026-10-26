package com.smartdocs.editor.core.tools5

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** A queued batch: files (with optional passwords) × recipe (or "convert each to PDF"). */
data class BatchJob(
    val id: String, val title: String, val created: Long, val files: List<Pair<String, String?>>, val recipe: Recipe?,
    val toPdfOnly: Boolean, val outDir: String, val workId: String = "",
)

/** Job specs and per-file logs as JSON files in filesDir/t5_batch. */
object BatchJobs {
    const val TAG = "t5_batch"
    private val json = Json { ignoreUnknownKeys = true }

    fun dir(ctx: Context) = File(ctx.filesDir, "t5_batch").apply { mkdirs() }
    private fun specFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    fun logFile(ctx: Context, id: String) = File(dir(ctx), "${id}_log.json")

    private fun toJson(j: BatchJob): String = buildJsonObject {
        put("id", j.id); put("title", j.title); put("created", j.created); put("toPdfOnly", j.toPdfOnly); put("outDir", j.outDir); put("workId", j.workId)
        put("files", buildJsonArray { j.files.forEach { (p, pw) -> add(buildJsonObject { put("path", p); if (pw != null) put("pw", pw) }) } })
        j.recipe?.let { put("recipe", RecipeJson.toJsonObject(it)) }
    }.toString()

    private fun fromJson(s: String): BatchJob? = runCatching {
        val o = json.parseToJsonElement(s).jsonObject
        BatchJob(
            id = o["id"]!!.jsonPrimitive.content, title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            created = o["created"]?.jsonPrimitive?.longOrNull ?: 0L,
            files = o["files"]?.jsonArray.orEmpty().map { f -> val fo = f.jsonObject; fo["path"]!!.jsonPrimitive.content to fo["pw"]?.jsonPrimitive?.contentOrNull },
            recipe = (o["recipe"] as? JsonObject)?.let { RecipeJson.fromJsonObject(it) },
            toPdfOnly = o["toPdfOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
            outDir = o["outDir"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            workId = o["workId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }.getOrNull()

    fun load(ctx: Context, id: String): BatchJob? = specFile(ctx, id).takeIf { it.exists() }?.let { fromJson(it.readText()) }

    fun all(ctx: Context): List<BatchJob> = (dir(ctx).listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".json") && !it.name.endsWith("_log.json") }
        .mapNotNull { fromJson(it.readText()) }.sortedByDescending { it.created }

    fun delete(ctx: Context, id: String) {
        load(ctx, id)?.workId?.takeIf { it.isNotEmpty() }?.let { runCatching { WorkManager.getInstance(ctx).cancelWorkById(UUID.fromString(it)) } }
        specFile(ctx, id).delete(); logFile(ctx, id).delete()
    }

    /** Queue a batch in WorkManager; returns the job. Passwords are stored only in app-private storage for the run. */
    fun enqueue(ctx: Context, title: String, files: List<Pair<File, String?>>, recipe: Recipe?, toPdfOnly: Boolean, outDir: File): BatchJob {
        val id = "b" + System.currentTimeMillis().toString(36)
        val req = OneTimeWorkRequestBuilder<BatchWorker>().setInputData(workDataOf(BatchWorker.KEY_ID to id)).addTag(TAG).build()
        val job = BatchJob(id, title, System.currentTimeMillis(), files.map { it.first.absolutePath to it.second }, recipe, toPdfOnly, outDir.absolutePath, req.id.toString())
        specFile(ctx, id).writeText(toJson(job))
        WorkManager.getInstance(ctx).enqueue(req)
        return job
    }

    fun writeLog(ctx: Context, id: String, entries: List<LogEntry>) {
        logFile(ctx, id).writeText(JsonArray(entries.map { e ->
            buildJsonObject {
                put("input", e.input); put("ok", e.ok); put("output", e.output); put("message", e.message); put("ms", e.millis); put("skipped", e.skipped)
            }
        }).toString())
    }

    fun readLog(ctx: Context, id: String): List<LogEntry> = runCatching {
        json.parseToJsonElement(logFile(ctx, id).readText()).jsonArray.map { el ->
            val o = el.jsonObject
            LogEntry(
                o["input"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["ok"]?.jsonPrimitive?.booleanOrNull == true,
                (o["output"] as? JsonPrimitive)?.contentOrNull, o["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                o["ms"]?.jsonPrimitive?.longOrNull ?: 0L, o["skipped"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }.getOrDefault(emptyList())
}

/** WorkManager worker: runs every file of a [BatchJob] through [RecipeRunner], writing the log after each file. */
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val job = BatchJobs.load(applicationContext, id) ?: return Result.failure()
        val temp = File(applicationContext.cacheDir, "t5_batch_tmp").apply { mkdirs() }
        val runner = RecipeRunner(applicationContext, temp)
        val log = ArrayList<LogEntry>()
        val label = job.recipe?.name ?: "pdf"
        try {
            job.files.forEachIndexed { i, (path, pw) ->
                setProgress(workDataOf(KEY_DONE to i, KEY_TOTAL to job.files.size, KEY_CURRENT to File(path).name))
                notifyProgress(job, i)
                val f = File(path)
                log += if (!f.exists()) LogEntry(f.name, false, null, "missing", 0L, skipped = true)
                else runner.run(f, pw, job.recipe, label, File(job.outDir), i, job.toPdfOnly)
                BatchJobs.writeLog(applicationContext, id, log)
            }
        } finally { runner.close(); temp.listFiles()?.forEach { runCatching { it.delete() } } }
        setProgress(workDataOf(KEY_DONE to job.files.size, KEY_TOTAL to job.files.size))
        notifyDone(job, log)
        return Result.success(workDataOf(KEY_DONE to job.files.size, KEY_TOTAL to job.files.size, KEY_FAILED to log.count { !it.ok }))
    }

    private fun nm(): NotificationManager? = applicationContext.getSystemService(NotificationManager::class.java)?.also {
        if (Build.VERSION.SDK_INT >= 26) it.createNotificationChannel(NotificationChannel(CHANNEL, "Batch", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notifyProgress(job: BatchJob, done: Int) = runCatching {
        val n = android.app.Notification.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(job.title).setContentText("${done + 1} / ${job.files.size}").setProgress(job.files.size, done, false).setOngoing(true).build()
        nm()?.notify(job.id.hashCode(), n)
    }

    private fun notifyDone(job: BatchJob, log: List<LogEntry>) = runCatching {
        val ok = log.count { it.ok }
        val n = android.app.Notification.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(job.title).setContentText("$ok / ${job.files.size} ✓").setAutoCancel(true).build()
        nm()?.notify(job.id.hashCode(), n)
    }

    companion object {
        const val KEY_ID = "id"; const val KEY_DONE = "done"; const val KEY_TOTAL = "total"; const val KEY_CURRENT = "current"; const val KEY_FAILED = "failed"
        const val CHANNEL = "t5_batch"
    }
}
