package com.smartdocs.editor.core.webdav

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.smartdocs.editor.core.cloud.AuthRequiredException
import com.smartdocs.editor.core.cloud.CloudCopy
import com.smartdocs.editor.core.cloud.SaveHooks
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Which WebDAV file a local working copy came from, so a save in the reader is uploaded back (with LOCK / UNLOCK
 * when the server supports locks). Kept apart from the cloud sync engine: it only listens to [SaveHooks] (one
 * listener installed at start-up) and uses its own WorkManager job. [dirty] = saved here, not uploaded yet;
 * [etag] = server version last downloaded / uploaded (If-Match refuses to overwrite a newer one); [lock] = token of
 * a lock held while the file is checked out; [canLock] = the server advertised class 2.
 */
data class DavLink(
    val placeId: String, val url: String, val etag: String? = null, val dirty: Boolean = false, val gen: Long = 0L,
    val lock: String? = null, val canLock: Boolean = false, val error: String? = null,
)

object DavLinks {
    /** Upload results for the browser screen: (local file, null = uploaded / error message). */
    data class Event(val file: File, val error: String?)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events

    @Volatile private var installed = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("webdav_links", Context.MODE_PRIVATE)

    /** Registers the save listener (SmartDocsApp.onCreate). */
    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        SaveHooks.listeners += { f -> if (markDirty(app, f)) DavUploadWorker.enqueue(app, f) }
    }

    private fun toJson(l: DavLink) = JSONObject().put("p", l.placeId).put("u", l.url).put("e", l.etag ?: "").put("d", l.dirty).put("g", l.gen)
        .put("l", l.lock ?: "").put("cl", l.canLock).put("err", l.error ?: "")

    private fun parse(s: String): DavLink? = runCatching {
        val o = JSONObject(s)
        DavLink(o.getString("p"), o.getString("u"), o.optString("e").ifEmpty { null }, o.optBoolean("d"), o.optLong("g"),
            o.optString("l").ifEmpty { null }, o.optBoolean("cl"), o.optString("err").ifEmpty { null })
    }.getOrNull()

    fun get(c: Context, local: File): DavLink? = prefs(c).getString(local.absolutePath, null)?.let(::parse)

    private fun put(c: Context, local: File, l: DavLink) { prefs(c).edit().putString(local.absolutePath, toJson(l).toString()).commit() }

    /** Every linked working copy (local path → link). */
    fun all(c: Context): Map<String, DavLink> = prefs(c).all.mapNotNull { (k, v) -> (v as? String)?.let(::parse)?.let { k to it } }.toMap()

    fun forUrl(c: Context, url: String): Pair<File, DavLink>? =
        all(c).entries.firstOrNull { DavXml.normalize(it.value.url) == DavXml.normalize(url) }?.let { File(it.key) to it.value }

    fun set(c: Context, local: File, link: DavLink) {
        val isNew = get(c, local) == null
        synchronized(this) { put(c, local, link) }
        if (isNew) runCatching { com.smartdocs.editor.core.sync.SyncLive.refresh(c) }   // fc-sync: folder + server watchers
        DavWatch.clear(c, link.url)   // fc-sync: a fresh download is the server version
    }

    fun update(c: Context, local: File, change: (DavLink) -> DavLink) = synchronized(this) { get(c, local)?.let { put(c, local, change(it)) } }

    fun remove(c: Context, local: File) = prefs(c).edit().remove(local.absolutePath).apply()

    fun forgetPlace(c: Context, placeId: String) {
        val e = prefs(c).edit()
        all(c).filterValues { it.placeId == placeId }.keys.forEach { e.remove(it) }
        e.apply()
    }

    /** A save of [local]: dirty with a new generation. False when the file is not a WebDAV copy. */
    fun markDirty(c: Context, local: File): Boolean = synchronized(this) {
        val l = get(c, local) ?: return false
        put(c, local, l.copy(dirty = true, gen = l.gen + 1, error = null)); true
    }

    /** What opening a server file does with an existing working copy (pure). */
    enum class OpenAction { DOWNLOAD, REUSE, KEEP_LOCAL }

    fun openAction(localExists: Boolean, link: DavLink?, remoteEtag: String?): OpenAction = when {
        !localExists || link == null -> OpenAction.DOWNLOAD
        link.dirty -> OpenAction.KEEP_LOCAL                     // edits not uploaded yet: never overwrite them
        remoteEtag != null && remoteEtag == link.etag -> OpenAction.REUSE
        else -> OpenAction.DOWNLOAD
    }

    /** Name for a copy uploaded next to a file that changed on the server meanwhile. */
    fun conflictName(name: String, now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(now))
        val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        return "$stem (SmartDocs $stamp)" + if (ext.isNotEmpty() && name.contains('.')) ".$ext" else ""
    }

    /** Owner text of our locks. */
    fun lockOwner(c: Context): String = "SmartDocs (" + android.os.Build.MODEL + ")"

    internal fun emit(e: Event) { _events.tryEmit(e) }

    /** Uploads [local] now (worker body; also "retry" in the browser). Throws on failure; returns the new link. */
    fun upload(c: Context, local: File): DavLink? {
        val link = get(c, local) ?: return null
        val place = DavPlaces.get(c, link.placeId) ?: return null
        val client = DavClient(place)
        val gen = link.gen
        val mime = if (local.extension.equals("pdf", true)) "application/pdf" else "application/octet-stream"
        val held = link.lock
        var token: String? = held?.takeIf { t -> runCatching { client.refreshLock(link.url, t, LOCK_SECONDS) }.isSuccess }
        val keepHeld = held != null && token != null      // a check-out lock that expired is dropped
        var ownLock = false
        if (token == null && link.canLock) {
            // short lock around the write, so no other client writes at the same time
            token = runCatching { client.lock(link.url, lockOwner(c), 120) }.getOrNull()
            ownLock = token != null
        }
        try {
            val etag = try {
                client.put(link.url, local, mime, token, ifMatch = link.etag)
            } catch (e: DavConflictException) {
                // someone changed the file on the server: keep both, ours goes next to it
                val other = DavXml.childUrl(DavXml.parentOf(link.url), conflictName(DavXml.nameOf(link.url)))
                client.put(other, local, mime, mustNotExist = true)
                set(c, local, link.copy(url = other, etag = null))
                null
            }
            val fresh = etag ?: runCatching { client.stat(get(c, local)!!.url)?.etag }.getOrNull()
            update(c, local) { cur -> cur.copy(etag = fresh, dirty = cur.gen != gen, error = null, lock = if (keepHeld) held else null) }
            DavWatch.clear(c, link.url)
            return get(c, local)
        } finally {
            if (ownLock) token?.let { t -> runCatching { client.unlock(link.url, t) } }
        }
    }

    /** Lock held while a file is checked out (refreshed on every upload). */
    const val LOCK_SECONDS = 3600

    /** Check out: takes a lock on the server and remembers it on the link. */
    fun checkOut(c: Context, local: File): DavLink? {
        val link = get(c, local) ?: return null
        val place = DavPlaces.get(c, link.placeId) ?: return null
        val token = DavClient(place).lock(link.url, lockOwner(c), LOCK_SECONDS)
        update(c, local) { it.copy(lock = token, canLock = true) }
        return get(c, local)
    }

    /** Check in: uploads pending edits, then releases the lock. */
    fun checkIn(c: Context, local: File) {
        val link = get(c, local) ?: return
        val place = DavPlaces.get(c, link.placeId) ?: return
        if (link.dirty) upload(c, local)
        val l = get(c, local) ?: return
        l.lock?.let { t -> runCatching { DavClient(place).unlock(l.url, t) } }
        update(c, local) { it.copy(lock = null) }
    }

    /** Re-queues every dirty copy (e.g. after the network came back or the app restarted). */
    fun resumePending(c: Context) {
        all(c).filterValues { it.dirty }.keys.map(::File).filter { it.isFile }.forEach { DavUploadWorker.enqueue(c, it) }
    }

    fun sha(f: File) = CloudCopy.sha256Of(f.absolutePath).take(24)
}

/** Uploads one dirty WebDAV working copy back (network required, exponential back-off). */
class DavUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val file = File(inputData.getString(KEY_PATH) ?: return Result.success())
        val link = DavLinks.get(ctx, file) ?: return Result.success()
        if (!link.dirty || !file.isFile) return Result.success()
        return try {
            DavLinks.upload(ctx, file)
            DavLinks.emit(DavLinks.Event(file, null))
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val msg = e.message ?: e.toString()
            DavLinks.update(ctx, file) { it.copy(error = msg) }
            if (runAttemptCount == 0) DavLinks.emit(DavLinks.Event(file, msg))
            if (e is AuthRequiredException || runAttemptCount >= 8) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_PATH = "path"

        fun enqueue(c: Context, file: File) {
            val req = OneTimeWorkRequestBuilder<DavUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_PATH to file.absolutePath))
                .build()
            WorkManager.getInstance(c.applicationContext).enqueueUniqueWork("webdav_up_" + DavLinks.sha(file), ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
