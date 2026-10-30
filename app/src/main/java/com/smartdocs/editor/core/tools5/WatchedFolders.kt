package com.smartdocs.editor.core.tools5

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * FINAL_CHECK_2 #22 — watched folders (Foxit / Nitro "watched folder" batch): a folder the user picked (SAF tree,
 * permission kept) is checked for new documents every 15 minutes (WorkManager) and on demand; every new file is run
 * through the watch's recipe as a batch job, and the outputs land in a `SmartDocs Output` sub-folder of the watched
 * folder (which is never scanned). Files already handled are remembered by name, size and modification time.
 */
object WatchedFolders {

    data class Watch(val id: String, val tree: String, val recipeId: String, val recursive: Boolean = false, val enabled: Boolean = true,
                     val lastScan: Long = 0L, val handled: Int = 0)

    const val OUTPUT_DIR = "SmartDocs Output"
    private const val PREFS = "t5_watch"
    private const val WORK_PERIODIC = "t5_watch_periodic"
    private const val WORK_NOW = "t5_watch_now"
    private val json = Json { ignoreUnknownKeys = true }
    private val accepted = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "jpg", "jpeg", "png", "tif", "tiff", "bmp", "webp", "html", "htm")

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Watch> = runCatching {
        json.parseToJsonElement(p(ctx).getString("list", "[]") ?: "[]").jsonArray.map { el ->
            val o = el.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            Watch(s("id"), s("tree"), s("recipe"), o["recursive"]?.jsonPrimitive?.booleanOrNull == true, o["enabled"]?.jsonPrimitive?.booleanOrNull != false,
                o["lastScan"]?.jsonPrimitive?.longOrNull ?: 0L, o["handled"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
        }
    }.getOrDefault(emptyList())

    private fun write(ctx: Context, list: List<Watch>) {
        p(ctx).edit().putString("list", JsonArray(list.map { w ->
            JsonObject(mapOf("id" to JsonPrimitive(w.id), "tree" to JsonPrimitive(w.tree), "recipe" to JsonPrimitive(w.recipeId), "recursive" to JsonPrimitive(w.recursive),
                "enabled" to JsonPrimitive(w.enabled), "lastScan" to JsonPrimitive(w.lastScan), "handled" to JsonPrimitive(w.handled)))
        }).toString()).apply()
    }

    fun add(ctx: Context, tree: Uri, recipeId: String, recursive: Boolean): Watch {
        SafFolders.persist(ctx, tree)
        val w = Watch("w" + System.currentTimeMillis().toString(36), tree.toString(), recipeId, recursive)
        write(ctx, all(ctx) + w)
        schedule(ctx)
        return w
    }

    fun update(ctx: Context, w: Watch) { write(ctx, all(ctx).map { if (it.id == w.id) w else it }); schedule(ctx) }

    fun remove(ctx: Context, id: String) {
        write(ctx, all(ctx).filter { it.id != id })
        seenFile(ctx, id).delete()
        schedule(ctx)
    }

    /** The periodic check runs while at least one watch is enabled; "scan now" queues one immediate pass. */
    fun schedule(ctx: Context) = runCatching {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (all(ctx).any { it.enabled }) {
            val req = PeriodicWorkRequestBuilder<WatchWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
            wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        } else wm.cancelUniqueWork(WORK_PERIODIC)
    }.let { }

    fun scanNow(ctx: Context) = runCatching {
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<WatchWorker>().build())
    }.let { }

    // ---------------------------------------------------------------- the scan

    private fun seenFile(ctx: Context, id: String) = File(File(ctx.filesDir, "t5_watch").apply { mkdirs() }, "$id.seen")

    /** Key of a document as it was when handled: a changed or re-added file is handled again. */
    fun key(name: String, size: Long, modified: Long) = "$name|$size|$modified"

    /** One pass over every enabled watch; returns the number of files queued. */
    fun scan(ctx: Context): Int {
        var queued = 0
        for (w in all(ctx).filter { it.enabled }) {
            val recipe = RecipeStore.find(ctx, w.recipeId) ?: continue
            val tree = runCatching { Uri.parse(w.tree) }.getOrNull() ?: continue
            val seenF = seenFile(ctx, w.id)
            val seen = if (seenF.exists()) seenF.readLines().toHashSet() else HashSet()
            val entries = runCatching {
                SafFolders.list(ctx, tree, w.recursive, accept = { it.substringAfterLast('.', "").lowercase() in accepted }, skipDir = { it == OUTPUT_DIR })
            }.getOrDefault(emptyList())
            val fresh = entries.mapNotNull { e ->
                val (size, modified) = SafFolders.stat(ctx, e.uri)
                val k = key("${e.folder}/${e.name}", size, modified)
                if (k in seen) null else e to k
            }
            if (fresh.isEmpty()) { update(ctx, w.copy(lastScan = System.currentTimeMillis())); continue }
            val outFolder = runCatching { outputFolder(ctx, tree) }.getOrNull() ?: continue
            val id = BatchJobs.newId()
            val inDir = File(BatchJobs.dir(ctx), id).apply { mkdirs() }
            val items = fresh.mapNotNull { (e, _) -> runCatching { SafFolders.copyIn(ctx, e, inDir) }.getOrNull()?.let { f -> BatchFile(f.absolutePath, null, e.name, outFolder.toString()) } }
            if (items.isNotEmpty()) {
                // outputs go to the watch's output sub-folder ("same folder" with the item folder pointing there)
                val r = recipe.copy(output = recipe.output.copy(sameFolder = true, folderUri = "", folderName = ""))
                BatchJobs.enqueueItems(ctx, id, SafFolders.name(ctx, tree), items, r, false, File(ctx.filesDir, "t5_batch_out"))
                queued += items.size
            }
            seenF.appendText(fresh.joinToString("") { it.second + "\n" })
            update(ctx, w.copy(lastScan = System.currentTimeMillis(), handled = w.handled + fresh.size))
        }
        return queued
    }

    /** The `SmartDocs Output` sub-folder of [tree] (created when missing), as a document URI. */
    private fun outputFolder(ctx: Context, tree: Uri): Uri {
        val root = SafFolders.root(tree)
        SafFolders.childDir(ctx, root, OUTPUT_DIR)?.let { return it }
        return DocumentsContract.createDocument(ctx.contentResolver, root, DocumentsContract.Document.MIME_TYPE_DIR, OUTPUT_DIR) ?: error("cannot create $OUTPUT_DIR")
    }
}

/** Runs one [WatchedFolders.scan]. */
class WatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { WatchedFolders.scan(applicationContext) }
        return Result.success()
    }
}
