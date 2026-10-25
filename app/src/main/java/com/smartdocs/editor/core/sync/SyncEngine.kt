package com.smartdocs.editor.core.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.smartdocs.editor.core.cloud.CloudItem
import com.smartdocs.editor.core.cloud.GoogleDrive
import com.smartdocs.editor.core.cloud.mimeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Two-way sync between a SAF tree folder and a Google Drive folder.
 *
 * Decision per file, using the snapshot of the last successful sync:
 *  - changed only locally → upload; changed only in Drive → download;
 *  - changed on both sides (or first sync with different sizes) → the Drive version is kept as
 *    "<name> (نسخة متعارضة).<ext>" next to the local one, then the local version is uploaded;
 *  - deleted on one side and unchanged on the other → deleted there too (Drive deletions go to the trash).
 * Subfolders are synced recursively (depth ≤ 6). Google Docs native files are skipped (no binary content).
 */
class SyncEngine(private val context: Context, private val pair: SyncPair) {
    private val drive = GoogleDrive.get(context)
    private val cr = context.contentResolver
    private val snapshot = SyncStore.snapshot(context, pair.id)
    private val seen = HashSet<String>()
    var uploaded = 0; private set
    var downloaded = 0; private set
    var conflicts = 0; private set

    companion object {
        const val CONFLICT_SUFFIX = " (نسخة متعارضة)"
        private const val MAX_DEPTH = 6
    }

    suspend fun run() = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(pair.treeUri)) ?: throw IllegalStateException("folder not accessible")
        if (!root.canRead()) throw SecurityException("folder permission lost")
        try { syncFolder(root, pair.remoteFolderId, "", 0) } finally { File(context.cacheDir, "sync_up").deleteRecursively() }
        // drop snapshot entries of files that no longer exist anywhere
        snapshot.keys.retainAll(seen)
        SyncStore.saveSnapshot(context, pair.id, snapshot)
    }

    private suspend fun syncFolder(local: DocumentFile, remoteId: String, prefix: String, depth: Int) {
        val localKids = local.listFiles().filter { it.name != null && !it.name!!.startsWith(".") }.associateBy { it.name!! }
        val remoteKids = drive.list(remoteId).filterNot { it.mime?.startsWith("application/vnd.google-apps.") == true && !it.isFolder }
            .groupBy { it.name }.mapValues { (_, v) -> v.maxByOrNull { it.modified }!! }
        for (name in (localKids.keys + remoteKids.keys).toSortedSet()) {
            val l = localKids[name]; val r = remoteKids[name]
            val key = prefix + name
            val isDir = l?.isDirectory == true || r?.isFolder == true
            if (isDir) {
                if (depth >= MAX_DEPTH) continue
                val ld = l?.takeIf { it.isDirectory } ?: local.createDirectory(name) ?: continue
                val rd = r?.takeIf { it.isFolder } ?: drive.createFolder(remoteId, name)
                syncFolder(ld, rd.id, "$key/", depth + 1)
                continue
            }
            runCatching { syncFile(local, remoteId, key, name, l, r) }
                .onFailure { if (it is SecurityException || it is com.smartdocs.editor.core.cloud.AuthRequiredException) throw it }
        }
    }

    private suspend fun syncFile(localDir: DocumentFile, remoteId: String, key: String, name: String, l: DocumentFile?, r: CloudItem?) {
        val s = snapshot[key]
        when {
            l != null && r != null -> {
                val localChanged = s == null || l.lastModified() != s.localMod || l.length() != s.localSize
                val remoteChanged = s == null || r.modified != s.remoteMod || r.size != s.remoteSize
                when {
                    s == null && l.length() == r.size -> record(key, l, r) // first sync, already identical
                    localChanged && remoteChanged -> conflict(localDir, remoteId, key, name, l, r)
                    localChanged -> record(key, l, drive.update(r, toTemp(l)).also { uploaded++ })
                    remoteChanged -> { download(r, l); downloaded++; record(key, l, r) }
                    else -> seen += key
                }
            }
            l != null -> {
                if (s != null && l.lastModified() == s.localMod && l.length() == s.localSize) { l.delete() } // deleted in Drive
                else { val up = drive.upload(toTemp(l), remoteId, name); uploaded++; record(key, l, up) }
            }
            r != null -> {
                if (s != null && r.modified == s.remoteMod && r.size == s.remoteSize) { drive.trash(r.id) } // deleted locally
                else {
                    val created = localDir.createFile(mimeOf(name), name) ?: throw IllegalStateException("cannot create $name")
                    download(r, created); downloaded++
                    record(key, localDir.findFile(created.name ?: name) ?: created, r)
                }
            }
        }
    }

    /** Keeps the Drive version as a conflict copy beside the local file, then uploads the local version. */
    private suspend fun conflict(localDir: DocumentFile, remoteId: String, key: String, name: String, l: DocumentFile, r: CloudItem) {
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        var copyName = if (ext.isEmpty()) "$base$CONFLICT_SUFFIX" else "$base$CONFLICT_SUFFIX.$ext"
        var n = 2
        while (localDir.findFile(copyName) != null) { copyName = if (ext.isEmpty()) "$base$CONFLICT_SUFFIX $n" else "$base$CONFLICT_SUFFIX $n.$ext"; n++ }
        val copy = localDir.createFile(mimeOf(name), copyName) ?: throw IllegalStateException("cannot create conflict copy")
        download(r, copy); downloaded++
        val updated = drive.update(r, toTemp(l)); uploaded++
        conflicts++
        record(key, l, updated)
        // the conflict copy is uploaded as a new file on the next pass (it has no snapshot entry)
    }

    private suspend fun download(r: CloudItem, target: DocumentFile) {
        val tmp = File(context.cacheDir, "sync_dl_${System.nanoTime()}")
        try {
            drive.download(r, tmp)
            (cr.openOutputStream(target.uri, "wt") ?: cr.openOutputStream(target.uri, "w"))?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                ?: throw IllegalStateException("cannot write ${target.name}")
        } finally { tmp.delete() }
    }

    /** SAF documents have no File path: copy to a temp file (named like the original, for the MIME type) before uploading. */
    private fun toTemp(l: DocumentFile): File {
        val dir = File(context.cacheDir, "sync_up").apply { mkdirs() }
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
