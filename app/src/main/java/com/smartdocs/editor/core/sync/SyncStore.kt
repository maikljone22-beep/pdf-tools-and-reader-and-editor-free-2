package com.smartdocs.editor.core.sync

import android.content.Context
import com.smartdocs.editor.core.cloud.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** [NEEDS_CONFIRMATION]: the last pass withheld [SyncPair.pendingDeletes] deletions until the user decides (DI-25). */
enum class SyncStatus { IDLE, PENDING, RUNNING, SYNCED, FAILED, NEEDS_CONFIRMATION }

/** A local SAF tree folder kept in two-way sync with a folder of one cloud provider (Drive, OneDrive, Dropbox or Box). */
data class SyncPair(
    val id: String = UUID.randomUUID().toString(),
    val treeUri: String,
    val localName: String,
    val remoteFolderId: String,
    val remoteName: String,
    val wifiOnly: Boolean = true,
    val auto: Boolean = true,
    val status: SyncStatus = SyncStatus.IDLE,
    val lastSync: Long = 0L,
    val message: String? = null,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val conflicts: Int = 0,
    /** Cloud of the remote folder (pairs saved before share6 have no key and are Google Drive). */
    val provider: ProviderId = ProviderId.DRIVE,
    /** Files left failed after the last run (they are retried with back-off). */
    val failed: Int = 0,
    /** Deletions withheld by the mass-deletion guard in the last pass. */
    val pendingDeletes: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("tree", treeUri).put("ln", localName).put("rid", remoteFolderId).put("rn", remoteName)
        .put("wifi", wifiOnly).put("auto", auto).put("st", status.name).put("last", lastSync).put("msg", message ?: "")
        .put("up", uploaded).put("down", downloaded).put("conf", conflicts).put("pv", provider.key).put("fail", failed).put("pdel", pendingDeletes)

    companion object {
        fun from(o: JSONObject) = SyncPair(
            id = o.getString("id"), treeUri = o.getString("tree"), localName = o.optString("ln"), remoteFolderId = o.getString("rid"),
            remoteName = o.optString("rn"), wifiOnly = o.optBoolean("wifi", true), auto = o.optBoolean("auto", true),
            status = runCatching { SyncStatus.valueOf(o.optString("st")) }.getOrDefault(SyncStatus.IDLE), lastSync = o.optLong("last"),
            message = o.optString("msg").ifEmpty { null }, uploaded = o.optInt("up"), downloaded = o.optInt("down"), conflicts = o.optInt("conf"),
            provider = ProviderId.of(o.optString("pv").ifEmpty { null }) ?: ProviderId.DRIVE, failed = o.optInt("fail"),
            pendingDeletes = o.optInt("pdel"),
        )
    }
}

/** Persists sync pairs (SharedPreferences JSON) and the per-pair snapshot used to detect which side changed. */
object SyncStore {
    private val _pairs = MutableStateFlow<List<SyncPair>>(emptyList())
    val pairs: StateFlow<List<SyncPair>> = _pairs
    @Volatile private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("sync_pairs", Context.MODE_PRIVATE)

    fun load(context: Context): List<SyncPair> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val arr = runCatching { JSONArray(prefs(context).getString("pairs", "[]")) }.getOrDefault(JSONArray())
                _pairs.value = (0 until arr.length()).mapNotNull { runCatching { SyncPair.from(arr.getJSONObject(it)) }.getOrNull() }
                loaded = true
            }
        }
        return _pairs.value
    }

    private fun save(context: Context, list: List<SyncPair>) {
        _pairs.value = list
        prefs(context).edit().putString("pairs", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun add(context: Context, pair: SyncPair) = synchronized(this) { save(context, load(context) + pair) }
    fun remove(context: Context, id: String) = synchronized(this) {
        save(context, load(context).filterNot { it.id == id })
        snapshotFile(context, id).delete()
        queueFile(context, id).delete()
        _queues.value = _queues.value - id
    }
    fun update(context: Context, id: String, block: (SyncPair) -> SyncPair) = synchronized(this) {
        save(context, load(context).map { if (it.id == id) block(it) else it })
    }

    // ---- snapshot: relative path → state at the last successful sync ----
    data class Entry(val localMod: Long, val localSize: Long, val remoteId: String, val remoteMod: Long, val remoteSize: Long)

    private fun snapshotFile(context: Context, id: String) = File(context.filesDir, "sync/$id.json").apply { parentFile?.mkdirs() }

    fun snapshot(context: Context, id: String): MutableMap<String, Entry> {
        val f = snapshotFile(context, id)
        val map = HashMap<String, Entry>()
        if (!f.exists()) return map
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                map[k] = Entry(e.getLong("lm"), e.getLong("ls"), e.getString("rid"), e.getLong("rm"), e.getLong("rs"))
            }
        }
        return map
    }

    // ---- per-file queue (share6) ----
    private val _queues = MutableStateFlow<Map<String, List<SyncQueue.QItem>>>(emptyMap())
    /** Live per-pair file queues for the UI. */
    val queues: StateFlow<Map<String, List<SyncQueue.QItem>>> = _queues

    private fun queueFile(context: Context, id: String) = File(context.filesDir, "sync/$id.queue.json").apply { parentFile?.mkdirs() }

    fun queue(context: Context, id: String): SyncQueue = synchronized(this) {
        val f = queueFile(context, id)
        val q = if (f.exists()) runCatching { SyncQueue.fromJson(JSONArray(f.readText())) }.getOrDefault(SyncQueue()) else SyncQueue()
        _queues.value = _queues.value + (id to q.all)
        q
    }

    fun saveQueue(context: Context, id: String, q: SyncQueue) = synchronized(this) {
        writeAtomically(queueFile(context, id), q.toJson().toString())
        _queues.value = _queues.value + (id to q.all)
    }

    /** Publishes an in-progress queue to the UI without writing it (called often during a run). */
    fun publishQueue(id: String, q: SyncQueue) { _queues.value = _queues.value + (id to q.all) }

    /** Manual "retry" of one failed file (or all when [path] is null); the caller then requests a run. */
    fun retry(context: Context, id: String, path: String?) {
        val q = queue(context, id)
        val now = System.currentTimeMillis()
        if (path == null) q.retryAll(now) else q.retry(path, now)
        saveQueue(context, id, q)
    }

    // ---- change-feed cursor per provider (remote watcher) ----
    fun cursor(context: Context, provider: ProviderId): String? = prefs(context).getString("cursor_${provider.key}", null)
    fun setCursor(context: Context, provider: ProviderId, cursor: String?) = prefs(context).edit().putString("cursor_${provider.key}", cursor).apply()

    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    fun saveSnapshot(context: Context, id: String, map: Map<String, Entry>) {
        val o = JSONObject()
        map.forEach { (k, e) -> o.put(k, JSONObject().put("lm", e.localMod).put("ls", e.localSize).put("rid", e.remoteId).put("rm", e.remoteMod).put("rs", e.remoteSize)) }
        val f = snapshotFile(context, id)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }
}
