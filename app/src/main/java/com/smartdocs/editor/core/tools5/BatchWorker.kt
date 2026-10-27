package com.smartdocs.editor.core.tools5

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
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

/**
 * One input of a batch: local [path] (+ password), the original [name] when it was copied in, and the SAF [folder]
 * it came from (a picked folder; outputs can be written back there).
 */
data class BatchFile(val path: String, val pw: String? = null, val name: String = "", val folder: String = "")

/**
 * A queued batch: files (with optional passwords) × recipe (or "convert each to PDF"). [conflict] is the name
 * conflict choice made before the run (null = the recipe's own).
 */
data class BatchJob(
    val id: String, val title: String, val created: Long, val items: List<BatchFile>, val recipe: Recipe?,
    val toPdfOnly: Boolean, val outDir: String, val workId: String = "", val conflict: Conflict? = null,
) {
    val files: List<Pair<String, String?>> get() = items.map { it.path to it.pw }

    /** The recipe ends with a print request (after nesting): the batch screen sends the outputs to the printer. */
    fun printRequest(lookup: (String) -> Recipe?): Step? = recipe?.let { r -> RecipeOps.expand(r, lookup = lookup).lastOrNull { it.type == StepType.PRINT } }
}

/** Job specs and per-file logs as JSON files in filesDir/t5_batch. */
object BatchJobs {
    const val TAG = "t5_batch"
    private val json = Json { ignoreUnknownKeys = true }

    fun dir(ctx: Context) = File(ctx.filesDir, "t5_batch").apply { mkdirs() }
    private fun specFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    fun logFile(ctx: Context, id: String) = File(dir(ctx), "${id}_log.json")

    /** Local copies of folder inputs and staged outputs of a job (removed with the job). */
    fun inputsDir(ctx: Context, id: String) = File(dir(ctx), "${id}_in")
    fun stageDir(ctx: Context, id: String) = File(dir(ctx), "${id}_out")

    fun newId(): String = "b" + System.currentTimeMillis().toString(36)

    private fun toJson(j: BatchJob): String = buildJsonObject {
        put("id", j.id); put("title", j.title); put("created", j.created); put("toPdfOnly", j.toPdfOnly); put("outDir", j.outDir); put("workId", j.workId)
        j.conflict?.let { put("conflict", it.name) }
        put("files", buildJsonArray {
            j.items.forEach { f ->
                add(buildJsonObject { put("path", f.path); if (f.pw != null) put("pw", f.pw); if (f.name.isNotEmpty()) put("name", f.name); if (f.folder.isNotEmpty()) put("folder", f.folder) })
            }
        })
        j.recipe?.let { put("recipe", RecipeJson.toJsonObject(it)) }
    }.toString()

    private fun fromJson(s: String): BatchJob? = runCatching {
        val o = json.parseToJsonElement(s).jsonObject
        BatchJob(
            id = o["id"]!!.jsonPrimitive.content, title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            created = o["created"]?.jsonPrimitive?.longOrNull ?: 0L,
            items = o["files"]?.jsonArray.orEmpty().map { f ->
                val fo = f.jsonObject
                BatchFile(fo["path"]!!.jsonPrimitive.content, fo["pw"]?.jsonPrimitive?.contentOrNull, fo["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), fo["folder"]?.jsonPrimitive?.contentOrNull.orEmpty())
            },
            recipe = (o["recipe"] as? JsonObject)?.let { RecipeJson.fromJsonObject(it) },
            toPdfOnly = o["toPdfOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
            outDir = o["outDir"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            workId = o["workId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            conflict = Conflict.of(o["conflict"]?.jsonPrimitive?.contentOrNull),
        )
    }.getOrNull()

    fun load(ctx: Context, id: String): BatchJob? = specFile(ctx, id).takeIf { it.exists() }?.let { fromJson(it.readText()) }

    fun all(ctx: Context): List<BatchJob> = (dir(ctx).listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".json") && !it.name.endsWith("_log.json") }
        .mapNotNull { f -> runCatching { fromJson(f.readText()) }.getOrNull() }.sortedByDescending { it.created }

    fun delete(ctx: Context, id: String) {
        load(ctx, id)?.workId?.takeIf { it.isNotEmpty() }?.let { runCatching { WorkManager.getInstance(ctx).cancelWorkById(UUID.fromString(it)) } }
        specFile(ctx, id).delete(); logFile(ctx, id).delete()
        inputsDir(ctx, id).deleteRecursively(); stageDir(ctx, id).deleteRecursively()
    }

    /** Queue a batch in WorkManager; returns the job. Passwords are stored only in app-private storage for the run. */
    fun enqueue(ctx: Context, title: String, files: List<Pair<File, String?>>, recipe: Recipe?, toPdfOnly: Boolean, outDir: File): BatchJob =
        enqueueItems(ctx, newId(), title, files.map { BatchFile(it.first.absolutePath, it.second) }, recipe, toPdfOnly, outDir)

    /** Queue a batch of [items] under [id] (the id of [inputsDir] when inputs were copied in first). */
    fun enqueueItems(ctx: Context, id: String, title: String, items: List<BatchFile>, recipe: Recipe?, toPdfOnly: Boolean, outDir: File, conflict: Conflict? = null): BatchJob {
        val req = OneTimeWorkRequestBuilder<BatchWorker>().setInputData(workDataOf(BatchWorker.KEY_ID to id)).addTag(TAG).build()
        val job = BatchJob(id, title, System.currentTimeMillis(), items, recipe, toPdfOnly, outDir.absolutePath, req.id.toString(), conflict)
        // called from click handlers on the main thread: a storage / WorkManager failure must not crash the app
        runCatching { specFile(ctx, id).writeText(toJson(job)); WorkManager.getInstance(ctx).enqueue(req) }
        return job
    }

    /** Resume a stopped / failed job: the files already in its log are kept, the others run again. */
    fun resume(ctx: Context, id: String) {
        val job = load(ctx, id) ?: return
        val req = OneTimeWorkRequestBuilder<BatchWorker>().setInputData(workDataOf(BatchWorker.KEY_ID to id)).addTag(TAG).build()
        runCatching { specFile(ctx, id).writeText(toJson(job.copy(workId = req.id.toString()))); WorkManager.getInstance(ctx).enqueue(req) }
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

/**
 * WorkManager worker: runs every file of a [BatchJob] through [RecipeRunner], writing the log after each file.
 * A resumed job starts after the files already logged. Outputs for a picked folder (the recipe's destination, or
 * the input's own folder with "same folder") are staged locally and then written there.
 */
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val job = BatchJobs.load(ctx, id) ?: return Result.failure()
        val temp = File(ctx.cacheDir, "t5_batch_tmp").apply { mkdirs() }
        val runner = RecipeRunner(ctx, temp)
        val log = ArrayList(BatchJobs.readLog(ctx, id).take(job.items.size))
        val label = job.recipe?.name ?: "pdf"
        val out = job.recipe?.output
        val conflict = job.conflict ?: out?.conflict ?: Conflict.RENAME
        val destTree = out?.folderUri?.takeIf { it.isNotEmpty() }?.let { runCatching { SafFolders.root(Uri.parse(it)) }.getOrNull() }
        try {
            for (i in log.size until job.items.size) {
                val item = job.items[i]
                setProgress(workDataOf(KEY_DONE to i, KEY_TOTAL to job.items.size, KEY_CURRENT to item.name.ifEmpty { File(item.path).name }))
                notifyProgress(job, i)
                val f = File(item.path)
                val display = item.name.ifEmpty { f.name }
                val folder = if (out?.sameFolder == true && item.folder.isNotEmpty()) Uri.parse(item.folder) else destTree
                // staged per file, so outputs of same-named inputs from different folders keep their own names
                val outDir = if (folder != null) File(BatchJobs.stageDir(ctx, id), i.toString()) else File(job.outDir)
                var e = if (!f.exists()) LogEntry(display, false, null, "missing", 0L, skipped = true)
                // a staged output must not take the place of the input copy (both live in the job's folders)
                else runner.run(f, item.pw, job.recipe?.let { if (folder != null) it.copy(output = it.output.copy(sameFolder = false)) else it }, label, outDir, i, job.toPdfOnly,
                    if (folder != null) Conflict.RENAME else conflict, display)
                val produced = e.output?.let(::File)
                if (e.ok && folder != null && produced != null) {
                    e = runCatching { SafFolders.export(ctx, folder, produced, conflict) }.fold(
                        onSuccess = { name -> if (name == null) e.copy(ok = false, skipped = true, message = ctx.getString(com.smartdocs.editor.R.string.nbt_log_exists)) else e.copy(message = listOf(e.message, "→ $name").filter { it.isNotBlank() }.joinToString(" · ")) },
                        onFailure = { t -> e.copy(ok = false, message = t.message ?: t.javaClass.simpleName) },
                    )
                }
                log += e
                BatchJobs.writeLog(ctx, id, log)
            }
        } finally { runner.close(); temp.listFiles()?.forEach { runCatching { it.delete() } } }
        setProgress(workDataOf(KEY_DONE to job.items.size, KEY_TOTAL to job.items.size))
        if (com.smartdocs.editor.core.data.NpfPrefs.notifyTasks) notifyDone(job, log)   // nav-prefs: notification toggle
        return Result.success(workDataOf(KEY_DONE to job.items.size, KEY_TOTAL to job.items.size, KEY_FAILED to log.count { !it.ok }))
    }

    private fun nm(): NotificationManager? = applicationContext.getSystemService(NotificationManager::class.java)?.also {
        if (Build.VERSION.SDK_INT >= 26) it.createNotificationChannel(NotificationChannel(CHANNEL, "Batch", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notifyProgress(job: BatchJob, done: Int) = runCatching {
        val n = android.app.Notification.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(job.title).setContentText("${done + 1} / ${job.items.size}").setProgress(job.items.size, done, false).setOngoing(true).build()
        nm()?.notify(job.id.hashCode(), n)
    }

    private fun notifyDone(job: BatchJob, log: List<LogEntry>) = runCatching {
        val ok = log.count { it.ok }
        val n = android.app.Notification.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(job.title).setContentText("$ok / ${job.items.size} ✓").setAutoCancel(true).build()
        nm()?.notify(job.id.hashCode(), n)
    }

    companion object {
        const val KEY_ID = "id"; const val KEY_DONE = "done"; const val KEY_TOTAL = "total"; const val KEY_CURRENT = "current"; const val KEY_FAILED = "failed"
        const val CHANNEL = "t5_batch"
    }
}
