package com.smartdocs.editor.core.cloud

import android.content.Context
import com.smartdocs.editor.core.collab.CollabStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Remembers which cloud item a local working copy came from, so a save in the reader can be uploaded back
 * over the same cloud file. (`app.documents.setOrigin` covers content:// sources; cloud items are tracked here.)
 */
object CloudLinks {
    /**
     * [dirty] = saved locally, upload not confirmed yet; [uploadedHash] = SHA-256 of the bytes last downloaded or
     * uploaded; [gen] counts saves, so an upload that raced with a newer save does not clear [dirty] (DI-23 / DI-24).
     */
    data class Link(val provider: ProviderId, val item: CloudItem, val dirty: Boolean = false, val uploadedHash: String? = null, val gen: Long = 0L)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("cloud_links", Context.MODE_PRIVATE)

    /** Records (or re-points) the link of [local]; its dirty state is kept. */
    fun set(context: Context, local: File, provider: ProviderId, item: CloudItem) = synchronized(this) {
        val old = get(context, local)
        put(context, local, Link(provider, item, old?.dirty ?: false, old?.uploadedHash, old?.gen ?: 0L))
    }

    private fun put(context: Context, local: File, link: Link) {
        val item = link.item
        val o = JSONObject().put("p", link.provider.key).put("id", item.id).put("name", item.name).put("parent", item.parentId ?: "")
            .put("path", item.path ?: "").put("mime", item.mime ?: "").put("mod", item.modified).put("size", item.size)
            .put("dirty", link.dirty).put("uh", link.uploadedHash ?: "").put("gen", link.gen)
        // commit: the dirty flag must survive the process being killed right after a save
        prefs(context).edit().putString(local.absolutePath, o.toString()).commit()
    }

    fun get(context: Context, local: File): Link? {
        val s = prefs(context).getString(local.absolutePath, null) ?: return null
        return parse(s)
    }

    private fun parse(s: String): Link? = runCatching {
        val o = JSONObject(s)
        Link(ProviderId.of(o.getString("p"))!!, CloudItem(
            id = o.getString("id"), name = o.optString("name"), isFolder = false, size = o.optLong("size"), modified = o.optLong("mod"),
            mime = o.optString("mime").ifEmpty { null }, parentId = o.optString("parent").ifEmpty { null }, path = o.optString("path").ifEmpty { null },
        ), o.optBoolean("dirty", false), o.optString("uh").ifEmpty { null }, o.optLong("gen"))
    }.getOrNull()

    fun remove(context: Context, local: File) = prefs(context).edit().remove(local.absolutePath).apply()

    /** A save of [local]: marks its link dirty (new generation). Returns false when the file has no cloud link. */
    fun markDirty(context: Context, local: File): Boolean = synchronized(this) {
        val l = get(context, local) ?: return false
        put(context, local, l.copy(dirty = true, gen = l.gen + 1))
        true
    }

    /** [hash] is in the cloud as [item]; clears [Link.dirty] unless another save came after generation [gen]. */
    fun markUploaded(context: Context, local: File, item: CloudItem, hash: String, gen: Long) = synchronized(this) {
        val l = get(context, local) ?: return
        put(context, local, l.copy(item = item, uploadedHash = hash, dirty = l.gen != gen))
    }

    /** Working copies with edits not in the cloud yet (flag set, or content differs from the last upload / download). */
    fun unsynced(context: Context, provider: ProviderId? = null): List<File> = prefs(context).all.mapNotNull { (k, v) ->
        val l = (v as? String)?.let(::parse) ?: return@mapNotNull null
        val f = File(k)
        if ((provider != null && l.provider != provider) || !f.isFile) return@mapNotNull null
        val hash = if (!l.dirty && l.uploadedHash != null) runCatching { CloudCopy.sha256(f) }.getOrNull() else null
        f.takeIf { CloudCopy.isDirty(l.dirty, l.uploadedHash, hash) }
    }

    /** Re-queues the upload of every dirty working copy (the cloud screens call it when they open). */
    fun resumePending(context: Context) {
        runCatching { unsynced(context).forEach { CloudUploadWorker.enqueue(context, it, replace = false) } }
    }

    /**
     * Opens [item]: downloads it into the provider's cache folder and records the link. A working copy with edits that
     * are not uploaded yet is kept and opened instead, and its upload is queued (DI-23, see [CloudCopy.openInto]).
     */
    suspend fun open(context: Context, provider: CloudProvider, item: CloudItem, onProgress: ((Long, Long) -> Unit)? = null): CloudCopy.Opened {
        val dir = Clouds.cacheDir(context, provider.id)
        val name = if (item.mime?.startsWith("application/vnd.google-apps.") == true) "${item.name}.pdf" else item.name
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        // one working copy per cloud item: reuse the same local name for the same id
        val existing = prefs(context).all.entries.firstOrNull { (_, v) -> (v as? String)?.let { runCatching { JSONObject(it).getString("id") == item.id }.getOrDefault(false) } == true }
            ?.key?.let(::File)
        val dest = existing?.takeIf { it.parentFile == dir } ?: uniqueIn(dir, safe)
        val opened = CloudCopy.openInto(provider, item, dest, if (dest == existing) get(context, dest) else null, onProgress)
        if (opened.action == CloudCopy.OpenAction.DOWNLOAD) synchronized(this) {
            val old = get(context, dest)
            put(context, dest, Link(provider.id, item, dirty = false, uploadedHash = opened.hash, gen = old?.gen ?: 0L))
        } else {
            markDirty(context, dest) // the link keeps the item we last synced: the upload compares against it
            runCatching { CloudUploadWorker.enqueue(context, dest, replace = false) }
        }
        return opened
    }

    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name); var n = 2
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        while (f.exists()) { f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"); n++ }
        return f
    }

    /**
     * Uploads the saved local copy over its cloud item. Returns null when the file has no cloud link.
     * The link is not changed here: the caller records the result with [markUploaded].
     */
    suspend fun writeBack(context: Context, local: File): Result<CloudItem>? {
        val link = get(context, local) ?: return null
        val provider = Clouds.provider(context, link.provider)
        return runCatching {
            // share6: someone changed the cloud file while it was open here → keep theirs, upload ours as a conflict copy
            val current = runCatching { provider.metadataOf(link.item) }.getOrNull()
            if (CloudCopy.remoteChanged(link.item, current)) {
                val name = com.smartdocs.editor.core.sync.SyncRules.conflictName(link.item.name) { false }
                provider.upload(local, link.item.parentId, name).also { lastConflict = it }
            } else provider.update(link.item, local)
        }.map { it.copy(parentId = it.parentId ?: link.item.parentId) } // later saves go to our copy
    }

    /** Last conflict copy created by [writeBack] (the UI may mention it). */
    @Volatile var lastConflict: CloudItem? = null
}

/**
 * Hook the reader calls after every successful save (right after `app.documents.writeBack(f)` in ReaderState):
 *
 *     com.smartdocs.editor.core.cloud.SaveHooks.onSaved(context, f)
 *
 * It uploads cloud working copies back to their cloud item and publishes comments of shared-review documents.
 * Runs in the background; upload results (from [CloudUploadWorker]) are emitted on [events] (the Cloud screen shows them).
 */
object SaveHooks {
    sealed interface Event {
        data class Uploaded(val file: File, val provider: ProviderId) : Event
        data class UploadFailed(val file: File, val provider: ProviderId, val error: Throwable) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events
    internal fun emit(e: Event) { _events.tryEmit(e) }

    /** Extra listeners other modules may register (called on a background thread). */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<(File) -> Unit>()

    fun onSaved(context: Context, file: File) {
        val app = context.applicationContext
        com.smartdocs.editor.core.review.ReviewPrompt.recordSuccess(app) // a save counts as a successful action
        scope.launch {
            // DI-24: persist "not uploaded yet", then upload through WorkManager (network constraint, retry with back-off)
            if (CloudLinks.markDirty(app, file)) runCatching { CloudUploadWorker.enqueue(app, file, replace = true) }
            // share6: comments merged into this save are now really in the file → mark them applied before publishing mine
            runCatching { com.smartdocs.editor.core.collab.CollabLive.confirm(app, file) }
            runCatching { CollabStore.onDocumentSaved(app, file) }
            runCatching { com.smartdocs.editor.core.sync.SyncLive.onFileSaved(app, file) }   // sync-after-save
            listeners.forEach { l -> runCatching { l(file) } }
        }
    }
}
