package com.smartdocs.editor.core.search

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.pdf.PdfSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the full-text index: collects PDFs (the app's documents folder, recent files with file:// URIs and the
 * readable paths of [com.smartdocs.editor.core.files.DocumentRepository.scanDevicePdfs]), indexes the new or changed
 * ones (page text via PDFium, comments/bookmarks/fields/attachments via [DocParts]), records password-protected
 * files as locked, prunes deleted files and publishes progress through [FullTextIndex.progressFlow].
 */
class Indexer(private val context: Context) {

    suspend fun sources(): List<File> {
        val app = context.applicationContext as? SmartDocsApp
        val out = LinkedHashMap<String, File>()
        val nveOpts = com.smartdocs.editor.ui.nve.search.NveIndexPrefs.load(context)   // NVE-search: excluded folders
        // open-all: text of Word / e-book / e-mail / text / sheet files is searchable too
        fun add(f: File) {
            if (f.isFile && f.canRead() && f.length() > 0 && (f.extension.equals("pdf", true) || com.smartdocs.editor.core.openall.OpenAllText.isIndexable(f)) &&
                !com.smartdocs.editor.core.nve.NveSearchCore.excluded(f.absolutePath, nveOpts)) out.putIfAbsent(f.absolutePath, f)
        }
        app?.documents?.docsDir?.walkTopDown()?.maxDepth(6)?.forEach { if (it.isFile) add(it) }
        runCatching {
            withTimeoutOrNull(5_000) { app?.db?.docs()?.recents()?.first() }?.forEach { r ->
                val u = Uri.parse(r.uri)
                if (u.scheme == "file") u.path?.let { add(File(it)) }
            }
        }
        runCatching { app?.documents?.scanDevicePdfs()?.forEach { d -> if (d.path.isNotBlank()) add(File(d.path)) } }
        return out.values.toList()
    }

    /** Indexes [only] (one file) or every source. Returns the number of files (re)indexed. */
    suspend fun run(only: File? = null, force: Boolean = false): Int {
        val index = FullTextIndex.get(context)
        val files = if (only != null) listOf(only) else sources()
        val todo = files.filter { force || runCatching { index.needsIndex(it) }.getOrDefault(true) }
        var done = 0
        FullTextIndex.progressState.value = IndexProgress(true, 0, todo.size, null)
        try {
            for (f in todo) {
                FullTextIndex.progressState.value = IndexProgress(true, done, todo.size, f.name)
                val t0 = System.currentTimeMillis()
                runCatching { indexFile(index, f) }.onFailure { FcsIndexPrefs.log(context, "FAILED ${f.absolutePath}: ${it.message}") }
                    .onSuccess { FcsIndexPrefs.log(context, "indexed ${f.absolutePath} (${System.currentTimeMillis() - t0} ms)") }   // fc-settings: index log
                done++
            }
            if (only == null) runCatching { index.prune().let { if (it > 0) FcsIndexPrefs.log(context, "pruned $it missing file(s)") } }
            // fc-settings: index size cap
            runCatching { FcsIndexPrefs.cacheMb(context).takeIf { it > 0 }?.let { mb -> index.evictToLimit(mb * 1024L * 1024L).let { if (it > 0) FcsIndexPrefs.log(context, "size cap $mb MB: emptied $it document(s)") } } }
        } finally {
            FullTextIndex.progressState.value = IndexProgress(false, done, todo.size, null)
        }
        return done
    }

    private suspend fun indexFile(index: FullTextIndex, f: File) {
        if (!f.extension.equals("pdf", true)) {
            val text = com.smartdocs.editor.core.openall.OpenAllText.extract(f)
            index.put(f, 1, if (text != null) listOf(DocPart(PartKind.TEXT, 0, text)) else emptyList(), locked = false)
            return
        }
        val session = try { PdfSession.open(context, f, null) }
        catch (e: PdfSession.Companion.PasswordRequiredException) { index.put(f, 0, emptyList(), locked = true); FcsIndexPrefs.log(context, "locked ${f.absolutePath}"); return }
        val parts = ArrayList<DocPart>()
        val pages = session.pageCount
        try {
            for (i in 0 until pages) {
                val t = runCatching { session.text(i) }.getOrDefault("")
                if (t.isNotBlank()) parts += DocPart(PartKind.TEXT, i, t)
            }
        } finally { session.closeAsync() }
        val (extra, title) = DocParts.loadForIndex(f, null, FcsIndexPrefs.meta(context))   // fc-settings: XMP / custom properties / tags, title
        parts += extra
        index.put(f, pages, parts, locked = false, title = title)
    }
}

/** WorkManager job around [Indexer]: periodic (every 12 h, battery and storage not low) or one-off. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_FILE)
        val force = inputData.getBoolean(KEY_FORCE, false)
        return runCatching { Indexer(applicationContext).run(path?.let { File(it) }, force); Result.success() }.getOrElse { Result.retry() }
    }

    companion object {
        const val KEY_FILE = "file"
        const val KEY_FORCE = "force"
        private const val PERIODIC = "search_index_periodic"
        private const val NOW = "search_index_now"

        private fun constraints() = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()

        /** Call once from `SmartDocsApp.onCreate`. */
        fun schedule(context: Context) {
            runCatching {
                val req = PeriodicWorkRequestBuilder<IndexWorker>(12, TimeUnit.HOURS).setConstraints(constraints()).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
            }
        }

        /** Index everything now (re-index changed files; [force] re-indexes all). */
        fun runNow(context: Context, force: Boolean = false) {
            runCatching {
                val req = OneTimeWorkRequestBuilder<IndexWorker>().setInputData(workDataOf(KEY_FORCE to force)).build()
                WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
            }
        }

        /** Index one saved / produced file soon (e.g. from `DocumentRepository.touchRecent`). */
        fun indexSoon(context: Context, file: File) {
            if (!file.extension.equals("pdf", true)) return
            runCatching {
                val req = OneTimeWorkRequestBuilder<IndexWorker>().setInputData(workDataOf(KEY_FILE to file.absolutePath))
                    .setInitialDelay(5, TimeUnit.SECONDS).build()
                WorkManager.getInstance(context).enqueueUniqueWork("search_index_file_" + file.absolutePath.hashCode(), ExistingWorkPolicy.REPLACE, req)
            }
        }
    }
}
