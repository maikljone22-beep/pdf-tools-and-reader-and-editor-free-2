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
 * fix/files-index: works on one named index ([FidIndexes]); its include folders (SAF trees) are read too.
 */
class Indexer(private val context: Context, private val def: IndexDef = IndexDef(FidIndexes.DEFAULT)) {

    /** A file to index: [key] is its path, or its content uri when it is only reachable through SAF. */
    class Source(val key: String, val name: String, val size: Long, val mtime: Long, val file: File?, val uri: Uri?)

    private fun indexable(name: String, size: Long) = size > 0 && (name.endsWith(".pdf", true) || com.smartdocs.editor.core.openall.OpenAllText.isIndexableName(name, size))

    suspend fun sources(): List<File> = collect().mapNotNull { it.file }

    /** Every source of [def]: the app sources (when on) and the include folders. */
    suspend fun collect(): List<Source> {
        val app = context.applicationContext as? SmartDocsApp
        val out = LinkedHashMap<String, Source>()
        val nveOpts = com.smartdocs.editor.ui.nve.search.NveIndexPrefs.load(context)   // NVE-search: excluded folders
        // open-all: text of Word / e-book / e-mail / text / sheet files is searchable too
        fun add(f: File) {
            if (f.isFile && f.canRead() && indexable(f.name, f.length()) &&
                !com.smartdocs.editor.core.nve.NveSearchCore.excluded(f.absolutePath, nveOpts)) out.putIfAbsent(f.absolutePath, Source(f.absolutePath, f.name, f.length(), f.lastModified(), f, null))
        }
        if (def.appSources) {
            app?.documents?.docsDir?.walkTopDown()?.maxDepth(6)?.forEach { if (it.isFile) add(it) }
            runCatching {
                withTimeoutOrNull(5_000) { app?.db?.docs()?.recents()?.first() }?.forEach { r ->
                    val u = Uri.parse(r.uri)
                    if (u.scheme == "file") u.path?.let { add(File(it)) }
                }
            }
            runCatching { app?.documents?.scanDevicePdfs()?.forEach { d -> if (d.path.isNotBlank()) add(File(d.path)) } }
        }
        // fix/files-index: include folders — read directly when the path is readable, else through the SAF tree
        for (folder in def.folders) runCatching {
            val uri = if (folder.startsWith("content:")) Uri.parse(folder) else null
            val dir = (uri?.let { com.smartdocs.editor.ui.nve.search.NveIndexPrefs.treePath(it) } ?: folder.takeIf { uri == null })?.let { File(it) }
            if (dir != null && dir.isDirectory && dir.listFiles() != null) dir.walkTopDown().maxDepth(8).take(20_000).forEach { if (it.isFile) add(it) }
            else if (uri != null) {
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri) ?: return@runCatching
                var count = 0
                fun walk(d: androidx.documentfile.provider.DocumentFile, depth: Int) {
                    if (depth > 8 || count > 20_000) return
                    for (c in d.listFiles()) {
                        if (c.isDirectory) walk(c, depth + 1)
                        else {
                            val n = c.name ?: continue
                            count++
                            if (indexable(n, c.length())) out.putIfAbsent(c.uri.toString(), Source(c.uri.toString(), n, c.length(), c.lastModified(), null, c.uri))
                        }
                    }
                }
                walk(root, 0)
            }
        }
        return out.values.toList()
    }

    /** Indexes [only] (one file) or every source. Returns the number of files (re)indexed. */
    suspend fun run(only: File? = null, force: Boolean = false): Int {
        val index = FullTextIndex.get(context, def.id)
        val files = if (only != null) listOf(Source(only.absolutePath, only.name, only.length(), only.lastModified(), only, null)) else collect()
        val todo = files.filter { force || runCatching { index.needsIndex(it.key, it.size, it.mtime) }.getOrDefault(true) }
        var done = 0
        FullTextIndex.progressState.value = IndexProgress(true, 0, todo.size, null)
        try {
            for (s in todo) {
                FullTextIndex.progressState.value = IndexProgress(true, done, todo.size, s.name)
                val t0 = System.currentTimeMillis()
                runCatching { indexSource(index, s) }.onFailure { FcsIndexPrefs.log(context, "FAILED ${s.key}: ${it.message}") }
                    .onSuccess { FcsIndexPrefs.log(context, "indexed ${s.key} (${System.currentTimeMillis() - t0} ms)") }   // fc-settings: index log
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

    /** A SAF source is copied to the cache first (PDFium and the text readers need a file). */
    private suspend fun indexSource(index: FullTextIndex, s: Source) {
        if (s.file != null) { indexFile(index, s.file, s); return }
        val uri = s.uri ?: return
        val dir = File(context.cacheDir, "fid_index_tmp").apply { mkdirs() }
        val tmp = File(dir, "src." + s.name.substringAfterLast('.', "bin").take(8))
        try {
            context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return
            indexFile(index, tmp, s)
        } finally { tmp.delete() }
    }

    private suspend fun indexFile(index: FullTextIndex, f: File, s: Source) {
        if (!f.extension.equals("pdf", true)) {
            val text = com.smartdocs.editor.core.openall.OpenAllText.extract(f)
            index.put(s.key, s.name, s.size, s.mtime, 1, if (text != null) listOf(DocPart(PartKind.TEXT, 0, text)) else emptyList(), locked = false)
            return
        }
        val session = try { PdfSession.open(context, f, null) }
        catch (e: PdfSession.Companion.PasswordRequiredException) { index.put(s.key, s.name, s.size, s.mtime, 0, emptyList(), locked = true); FcsIndexPrefs.log(context, "locked ${s.key}"); return }
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
        index.put(s.key, s.name, s.size, s.mtime, pages, parts, locked = false, title = title)
    }
}

/** WorkManager job around [Indexer]: periodic (every 12 h, battery and storage not low) or one-off. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_FILE)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val only = inputData.getString(KEY_INDEX)
        // fix/files-index: every index (or the one asked for); a single file goes to the indexes that take app files
        val defs = FidIndexes.list(applicationContext).filter { d -> (only == null || d.id == only) && (path == null || d.appSources) }
        return runCatching { defs.forEach { d -> Indexer(applicationContext, d).run(path?.let { File(it) }, force) }; Result.success() }.getOrElse { Result.retry() }
    }

    companion object {
        const val KEY_FILE = "file"
        const val KEY_FORCE = "force"
        const val KEY_INDEX = "index"
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

        /** Index everything now (re-index changed files; [force] re-indexes all). [index]: only that index (fix/files-index). */
        fun runNow(context: Context, force: Boolean = false, index: String? = null) {
            runCatching {
                val req = OneTimeWorkRequestBuilder<IndexWorker>().setInputData(workDataOf(KEY_FORCE to force, KEY_INDEX to index)).build()
                WorkManager.getInstance(context).enqueueUniqueWork(if (index == null) NOW else NOW + "_" + index, ExistingWorkPolicy.KEEP, req)
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
