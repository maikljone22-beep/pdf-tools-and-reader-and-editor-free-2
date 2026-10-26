package com.smartdocs.editor.core.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.smartdocs.editor.core.cloud.AuthRequiredException
import com.smartdocs.editor.core.cloud.CloudItem
import com.smartdocs.editor.core.cloud.CloudProvider
import com.smartdocs.editor.core.cloud.Clouds
import com.smartdocs.editor.core.cloud.mimeOf
import com.smartdocs.editor.core.sync.SyncRules.Action
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Two-way sync between a SAF tree folder and a folder of any [CloudProvider] (Drive, OneDrive, Dropbox, Box).
 * The per-file decision is [SyncRules.decide] against the snapshot of the last successful sync; every transfer goes
 * through the pair's [SyncQueue] (pending → running → synced / failed with back-off). Subfolders are synced to depth 6.
 * Provider-native documents (Google Docs/Sheets/Slides) are skipped.
 *
 * [force] = a manual "sync now": failed files are retried even while their back-off is running.
 */
class SyncEngine(
    private val context: Context,
    private val pair: SyncPair,
    private val force: Boolean = false,
    private val onProgress: (String?, Int) -> Unit = { _, _ -> },
) {
    private val provider: CloudProvider = Clouds.provider(context, pair.provider)
    private val cr = context.contentResolver
    private val snapshot = SyncStore.snapshot(context, pair.id)
    private val queue = SyncStore.queue(context, pair.id)
    private val seen = HashSet<String>()
    var uploaded = 0; private set
    var downloaded = 0; private set
    var conflicts = 0; private set
    var failed = 0; private set
    private var transfers = 0

    companion object {
        const val CONFLICT_SUFFIX = SyncRules.CONFLICT_SUFFIX
        private const val MAX_DEPTH = 6
    }

    suspend fun run() = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(pair.treeUri)) ?: throw IllegalStateException("folder not accessible")
        if (!root.canRead()) throw SecurityException("folder permission lost")
        queue.recoverInterrupted(System.currentTimeMillis())
        try {
            syncFolder(root, pair.remoteFolderId, "", 0)
        } finally {
            File(context.cacheDir, "sync_up").deleteRecursively()
            queue.prune(System.currentTimeMillis(), exists = { it in seen })
            SyncStore.saveQueue(context, pair.id, queue)
            onProgress(null, transfers)
        }
        // drop snapshot entries of files that no longer exist anywhere
        snapshot.keys.retainAll(seen)
        SyncStore.saveSnapshot(context, pair.id, snapshot)
    }

    private fun remoteSkipped(r: CloudItem) = (!r.isFolder && r.mime?.startsWith("application/vnd.google-apps.") == true) || SyncRules.ignored(r.name)

    private suspend fun syncFolder(local: DocumentFile, remoteId: String, prefix: String, depth: Int) {
        coroutineContext.ensureActive()
        val localKids = local.listFiles().filter { it.name != null && !SyncRules.ignored(it.name!!) }.associateBy { it.name!! }
        val remoteKids = provider.list(remoteId).filterNot(::remoteSkipped)
            .groupBy { it.name }.mapValues { (_, v) -> v.maxByOrNull { it.modified }!! }
        for (name in (localKids.keys + remoteKids.keys).toSortedSet()) {
            val l = localKids[name]; val r = remoteKids[name]
            val key = prefix + name
            val isDir = l?.isDirectory == true || r?.isFolder == true
            if (isDir) {
                if (depth >= MAX_DEPTH) continue
                val ld = l?.takeIf { it.isDirectory } ?: local.createDirectory(name) ?: continue
                val rd = r?.takeIf { it.isFolder } ?: provider.createFolder(remoteId, name)
                syncFolder(ld, rd.id, "$key/", depth + 1)
                continue
            }
            syncFile(local, remoteId, key, name, l, r)
        }
    }

    private suspend fun syncFile(localDir: DocumentFile, remoteId: String, key: String, name: String, l: DocumentFile?, r: CloudItem?) {
        seen += key
        val s = snapshot[key]
        val action = SyncRules.decide(
            l?.let { SyncRules.Side(it.lastModified(), it.length()) },
            r?.let { SyncRules.Side(it.modified, it.size) },
            s?.let { SyncRules.Snap(it.localMod, it.localSize, it.remoteMod, it.remoteSize) },
        )
        if (action == Action.NONE) return
        if (action == Action.RECORD) { record(key, l!!, r!!); return }
        val op = SyncQueue.opFor(action) ?: return
        val now = System.currentTimeMillis()
        if (!queue.mayRun(key, now, force)) { failed++; return } // still backing off: keep the old snapshot entry
        queue.enqueue(key, op, now, l?.length() ?: r?.size ?: 0L)
        queue.start(key, now)
        SyncStore.publishQueue(pair.id, queue)
        onProgress(name, transfers)
        try {
            coroutineContext.ensureActive()
            when (action) {
                Action.UPLOAD -> record(key, l!!, provider.update(r!!, toTemp(l)).also { uploaded++ })
                Action.DOWNLOAD -> { download(r!!, l!!); downloaded++; record(key, l, r) }
                Action.CONFLICT -> conflict(localDir, key, name, l!!, r!!)
                Action.UPLOAD_NEW -> { val up = provider.upload(toTemp(l!!), remoteId, name); uploaded++; record(key, l, up) }
                Action.DOWNLOAD_NEW -> {
                    val created = localDir.createFile(mimeOf(name), name) ?: throw IllegalStateException("cannot create $name")
                    download(r!!, created); downloaded++
                    record(key, localDir.findFile(created.name ?: name) ?: created, r)
                }
                Action.DELETE_LOCAL -> { if (!l!!.delete()) throw IllegalStateException("cannot delete $name"); snapshot.remove(key); seen -= key }
                Action.DELETE_REMOTE -> { provider.delete(r!!); snapshot.remove(key); seen -= key }
                else -> Unit
            }
            transfers++
            queue.succeed(key, System.currentTimeMillis())
        } catch (e: Exception) {
            if (e is SecurityException || e is AuthRequiredException || e is kotlinx.coroutines.CancellationException) {
                queue.fail(key, e.message ?: e.javaClass.simpleName, System.currentTimeMillis()); throw e
            }
            failed++
            queue.fail(key, e.message ?: e.javaClass.simpleName, System.currentTimeMillis())
        } finally {
            SyncStore.publishQueue(pair.id, queue)
        }
    }

    /** Keeps the cloud version as a conflict copy beside the local file, then uploads the local version. */
    private suspend fun conflict(localDir: DocumentFile, key: String, name: String, l: DocumentFile, r: CloudItem) {
        val copyName = SyncRules.conflictName(name) { localDir.findFile(it) != null }
        val copy = localDir.createFile(mimeOf(name), copyName) ?: throw IllegalStateException("cannot create conflict copy")
        download(r, copy); downloaded++
        val updated = provider.update(r, toTemp(l)); uploaded++
        conflicts++
        record(key, l, updated)
        // the conflict copy is uploaded as a new file on the next pass (it has no snapshot entry)
    }

    private suspend fun download(r: CloudItem, target: DocumentFile) {
        val tmp = File(context.cacheDir, "sync_dl_${System.nanoTime()}")
        try {
            provider.download(r, tmp)
            (runCatching { cr.openOutputStream(target.uri, "wt") }.getOrNull() ?: cr.openOutputStream(target.uri, "w"))?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                ?: throw IllegalStateException("cannot write ${target.name}")
        } finally { tmp.delete() }
    }

    /** SAF documents have no File path: copy to a temp file (named like the original, for the MIME type) before uploading. */
    private fun toTemp(l: DocumentFile): File {
        val dir = File(context.cacheDir, "sync_up/${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, l.name ?: "file")
        cr.openInputStream(l.uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: throw IllegalStateException("cannot read ${l.name}")
        return f
    }

    private fun record(key: String, l: DocumentFile, r: CloudItem) {
        seen += key
        // re-query: lastModified/length change after we write the document
        val fresh = DocumentFile.fromSingleUri(context, l.uri) ?: l
        snapshot[key] = SyncStore.Entry(fresh.lastModified(), fresh.length(), r.id, r.modified, r.size)
    }
}
