package com.smartdocs.editor.core.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.smartdocs.editor.core.cloud.Clouds

/**
 * fc-sync: the 15-minute periodic job (the fallback when the app is closed) no longer re-lists every cloud folder
 * when nothing changed. Before a pair runs, it asks:
 *  1. the provider's delta feed, from the cursor taken when the last clean run of this pair started
 *     ([SyncStore.pairCursor]): did anything change in the drive since then? (One request, not one per folder.)
 *  2. the local folder: does its listing still match the snapshot of that run exactly (same files, sizes and times)?
 *  3. is the pair's queue empty of pending / failed files, its status SYNCED, and its last full run younger than
 *     [FULL_EVERY_MS]?
 * Only when all say "no change" is the pair skipped. Any doubt (no cursor, an error, a reset feed) means a normal run,
 * so the engine's rules (DeleteGuard, SafeReplace, conflict copies) still decide everything that is transferred.
 * Expedited requests (a save, a local change, a cloud notification, the app opening) and "sync now" never skip.
 */
object SyncSkip {
    /** A full run happens at least this often even when the feeds say nothing changed. */
    const val FULL_EVERY_MS = 6 * 3600_000L
    private const val MAX_DEPTH = 6

    data class Side(val mod: Long, val size: Long)

    /** Pure: the local listing ([local], relative path → state) is exactly what the last run recorded. */
    fun localUnchanged(local: Map<String, Side>, snapshot: Map<String, SyncStore.Entry>): Boolean {
        if (local.size != snapshot.size) return false
        for ((k, s) in local) {
            val e = snapshot[k] ?: return false
            if (e.localMod != s.mod || e.localSize != s.size) return false
        }
        return true
    }

    /** Pure: the pair-level conditions (status, queue, age of the last full run). */
    fun pairQuiet(status: SyncStatus, lastSync: Long, queue: List<SyncQueue.QItem>, now: Long): Boolean =
        status == SyncStatus.SYNCED && lastSync > 0 && now - lastSync in 0 until FULL_EVERY_MS && queue.all { it.state == SyncQueue.State.SYNCED }

    /** Local files of the pair's tree, keyed like [SyncEngine] keys them (same ignore rules and depth limit). */
    fun listLocal(context: Context, pair: SyncPair): Map<String, Side>? {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(pair.treeUri)) ?: return null
        if (!root.canRead()) return null
        val out = HashMap<String, Side>()
        fun walk(dir: DocumentFile, prefix: String, depth: Int) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (SyncRules.ignored(name)) continue
                if (f.isDirectory) { if (depth < MAX_DEPTH) walk(f, "$prefix$name/", depth + 1); continue }
                out[prefix + name] = Side(f.lastModified(), f.length())
            }
        }
        walk(root, "", 0)
        return out
    }

    /**
     * True when the periodic run of [pair] can be skipped (see the class comment). Never throws: an error means
     * "run". Advances the pair cursor when the feed was read and showed nothing.
     */
    suspend fun canSkip(context: Context, pair: SyncPair): Boolean = runCatching {
        val now = System.currentTimeMillis()
        if (!pairQuiet(pair.status, pair.lastSync, SyncStore.queue(context, pair.id).all, now)) return false
        val cursor = SyncStore.pairCursor(context, pair.id) ?: return false
        val provider = Clouds.provider(context, pair.provider)
        if (!provider.isConnected() || !provider.feedCovers(pair.remoteFolderId)) return false
        val (changed, next) = provider.changesSince(cursor)
        if (changed) return false
        val local = listLocal(context, pair) ?: return false
        if (!localUnchanged(local, SyncStore.snapshot(context, pair.id))) return false
        SyncStore.setPairCursor(context, pair.id, next)
        true
    }.getOrDefault(false)

    /** Cursor to remember at the start of a run (null when the provider has no feed or it failed). */
    suspend fun cursorNow(context: Context, pair: SyncPair): String? =
        runCatching { Clouds.provider(context, pair.provider).changeCursor() }.getOrNull()
}
