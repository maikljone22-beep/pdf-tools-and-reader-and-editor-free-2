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
 *
 * Deletions (DI-25) are collected during the pass and run at its end, unless [DeleteGuard.tooMany] says the set is
 * suspicious: then none runs and [withheldDeletes] tells the caller to ask the user ([deletes] = what the user chose).
 * Local deletions go to the app trash first.
 */
class SyncEngine(
    private val context: Context,
    private val pair: SyncPair,
    private val force: Boolean = false,
    private val onProgress: (String?, Int) -> Unit = { _, _ -> },
    private val deletes: DeleteGuard.Policy = DeleteGuard.Policy.GUARD,
) {
    private val provider: CloudProvider = Clouds.provider(context, pair.provider)
    private val cr = context.contentResolver
    private val snapshot = SyncStore.snapshot(context, pair.id)
    private val queue = SyncStore.queue(context, pair.id)
    private val seen = HashSet<String>()
    private val snapshotSize = snapshot.size
    private class PendingDelete(val localDir: DocumentFile, val remoteId: String, val key: String, val name: String, val l: DocumentFile?, val r: CloudItem?, val action: Action)
    private val pendingDeletes = ArrayList<PendingDelete>()
    /** Deletions this pass did not run because they need the user's confirmation (0 = none). */
    var withheldDeletes = 0; private set
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
            val run = DeleteGuard.toRun(pendingDeletes, snapshotSize, deletes)
            withheldDeletes = pendingDeletes.size - run.size // withheld entries keep their snapshot (they are in [seen]) until the user decides
            for (d in run) execute(d.localDir, d.remoteId, d.key, d.name, d.l, d.r, d.action)
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
        if (DeleteGuard.isDelete(action)) {
            if (deletes == DeleteGuard.Policy.RESTORE) return execute(localDir, remoteId, key, name, l, r, DeleteGuard.restored(action))
            pendingDeletes += PendingDelete(localDir, remoteId, key, name, l, r, action); return
        }
        execute(localDir, remoteId, key, name, l, r, action)
    }

    private suspend fun execute(localDir: DocumentFile, remoteId: String, key: String, name: String, l: DocumentFile?, r: CloudItem?, action: Action) {
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
                Action.DOWNLOAD -> { val got = download(r!!, localDir, name, l!!); downloaded++; record(key, got, r) }
                Action.CONFLICT -> conflict(localDir, key, name, l!!, r!!)
                Action.UPLOAD_NEW -> { val up = provider.upload(toTemp(l!!), remoteId, name); uploaded++; record(key, l, up) }
                Action.DOWNLOAD_NEW -> {
                    val created = download(r!!, localDir, name, null); downloaded++
                    record(key, localDir.findFile(created.name ?: name) ?: created, r)
                }
                Action.DELETE_LOCAL -> { deleteLocal(l!!, name); snapshot.remove(key); seen -= key }
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

    /** DI-25: a local deletion first copies the document into the app trash (30 days), then deletes it. */
    private fun deleteLocal(l: DocumentFile, name: String) {
        val entry = SyncTrash.add(File(context.applicationContext.filesDir, "trash"), name, l.uri.toString(), l.length()) { f ->
            (cr.openInputStream(l.uri) ?: throw IllegalStateException("cannot read $name")).use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        if (!l.delete()) { SyncTrash.remove(entry); throw IllegalStateException("cannot delete $name") }
    }

    /** Keeps the cloud version as a conflict copy beside the local file, then uploads the local version. */
    private suspend fun conflict(localDir: DocumentFile, key: String, name: String, l: DocumentFile, r: CloudItem) {
        val copyName = SyncRules.conflictName(name) { localDir.findFile(it) != null }
        download(r, localDir, copyName, null); downloaded++
        val updated = provider.update(r, toTemp(l)); uploaded++
        conflicts++
        record(key, l, updated)
        // the conflict copy is uploaded as a new file on the next pass (it has no snapshot entry)
    }

    /**
     * Downloads [r] into [dir] as [name], replacing [target] when given (DI-26: never in place; see [SafeReplace]).
     * Returns the document that now holds the cloud content.
     */
    private suspend fun download(r: CloudItem, dir: DocumentFile, name: String, target: DocumentFile?): DocumentFile {
        val tmp = File(context.cacheDir, "sync_dl_${System.nanoTime()}")
        try {
            provider.download(r, tmp)
            return SafeReplace.replace(SafOps(dir, name), target, name, tmp)
        } finally { tmp.delete() }
    }

    /** SAF side of [SafeReplace]: documents of one tree folder. */
    private inner class SafOps(private val dir: DocumentFile, private val name: String) : SafeReplace.Ops<DocumentFile> {
        override fun create(name: String): DocumentFile? {
            if (name == SafeReplace.partName(this.name)) dir.findFile(name)?.delete() // stale part of an interrupted run
            // octet-stream: the provider must not append an extension to the hidden temp names
            val mime = if (name == this.name) mimeOf(name) else "application/octet-stream"
            return dir.createFile(mime, name)
        }
        private fun out(d: DocumentFile) = runCatching { cr.openOutputStream(d.uri, "wt") }.getOrNull() ?: cr.openOutputStream(d.uri, "w")
        override fun write(doc: DocumentFile, src: File) {
            out(doc)?.use { o -> src.inputStream().use { it.copyTo(o) } } ?: throw IllegalStateException("cannot write ${doc.name}")
        }
        override fun copy(from: DocumentFile, to: DocumentFile) {
            (cr.openInputStream(from.uri) ?: throw IllegalStateException("cannot read ${from.name}")).use { i ->
                out(to)?.use { o -> i.copyTo(o) } ?: throw IllegalStateException("cannot write ${to.name}")
            }
        }
        override fun length(doc: DocumentFile): Long = (DocumentFile.fromSingleUri(context, doc.uri) ?: doc).length()
        override fun rename(doc: DocumentFile, name: String): DocumentFile? =
            doc.takeIf { runCatching { it.renameTo(name) }.getOrDefault(false) } // TreeDocumentFile updates its uri
        override fun delete(doc: DocumentFile): Boolean = runCatching { doc.delete() }.getOrDefault(false)
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
