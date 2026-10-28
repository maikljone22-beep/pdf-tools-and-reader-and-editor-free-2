package com.smartdocs.editor.core.sync

import android.content.Context
import android.os.FileObserver
import com.smartdocs.editor.core.cloud.CloudCopy
import com.smartdocs.editor.core.cloud.CloudLinks
import com.smartdocs.editor.core.cloud.CloudUploadWorker
import com.smartdocs.editor.core.cloud.SaveHooks
import com.smartdocs.editor.core.webdav.DavLinks
import com.smartdocs.editor.core.webdav.DavUploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * fc-sync (local files): a [FileObserver] on each app folder that holds cloud or WebDAV working copies
 * (`Documents/Cloud/<provider>/`, `Documents/Cloud/webdav/<place>/`). Another app that writes a working copy (opened
 * with write access through the app's file provider) is noticed at once instead of on the next open of a cloud screen.
 *
 * inotify costs nothing while idle (no wake-ups); folders without links are not watched. A finished write is handled
 * after [QUIET_MS] of quiet:
 *  - files the reader saved itself are skipped (SaveHooks already queued them);
 *  - a cloud copy is queued only when its bytes differ from the last upload / download ([CloudCopy.isDirty]), so our own
 *    downloads never look like edits; the upload job itself is the usual idempotent [CloudUploadWorker];
 *  - a WebDAV copy is marked dirty and queued ([DavUploadWorker], If-Match keeps a newer server version safe).
 */
object AppFolderWatch {
    private const val QUIET_MS = 10_000L
    private const val OWN_SAVE_MS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val pending = ConcurrentHashMap<String, Job>()
    private val ownSaves = ConcurrentHashMap<String, Long>()
    @Volatile private var hooked = false

    /** Pure: folders to watch = the parent folders of every linked working copy. */
    fun folders(linkedPaths: Collection<String>): Set<String> = linkedPaths.mapNotNull { File(it).parent }.toSet()

    /** Pure: is a write event at [now] one of our own reader saves ([savedAt] = when SaveHooks saw it)? */
    fun isOwnSave(savedAt: Long?, now: Long): Boolean = savedAt != null && now - savedAt in 0..OWN_SAVE_MS

    /** Re-reads the links and (un)registers observers. Cheap; called from [SyncLive.refresh]. */
    fun refresh(context: Context) {
        val a = context.applicationContext
        if (!hooked) synchronized(this) {
            if (!hooked) { hooked = true; SaveHooks.listeners += { f -> ownSaves[f.absolutePath] = System.currentTimeMillis() } }
        }
        scope.launch {
            val linked = runCatching { CloudLinks.paths(a) + DavLinks.all(a).keys }.getOrDefault(emptyList())
            val wanted = folders(linked)
            observers.keys.filter { it !in wanted }.forEach { observers.remove(it)?.stopWatching() }
            for (dir in wanted) if (!observers.containsKey(dir) && File(dir).isDirectory) watch(a, dir)
        }
    }

    private fun watch(a: Context, dir: String) {
        @Suppress("DEPRECATION")
        val o = object : FileObserver(dir, FileObserver.CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                val name = path ?: return
                if (name.startsWith(".")) return   // temp / part files
                onWrite(a, File(dir, name))
            }
        }
        runCatching { o.startWatching(); observers[dir] = o }
    }

    private fun onWrite(a: Context, f: File) {
        val key = f.absolutePath
        pending.remove(key)?.cancel()
        pending[key] = scope.launch {
            delay(QUIET_MS)
            pending.remove(key)
            if (!f.isFile || isOwnSave(ownSaves[key], System.currentTimeMillis())) return@launch
            CloudLinks.get(a, f)?.let { l ->
                if (l.dirty) return@launch   // already queued by a save
                val hash = runCatching { CloudCopy.sha256(f) }.getOrNull() ?: return@launch
                if (CloudCopy.isDirty(false, l.uploadedHash, hash) && CloudLinks.markDirty(a, f)) runCatching { CloudUploadWorker.enqueue(a, f, replace = true) }
                return@launch
            }
            DavLinks.get(a, f)?.let { l ->
                if (l.dirty) return@launch
                if (DavLinks.markDirty(a, f)) runCatching { DavUploadWorker.enqueue(a, f) }
            }
        }
    }
}
