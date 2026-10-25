package com.smartdocs.editor.core.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class SyncStatus { IDLE, PENDING, RUNNING, SYNCED, FAILED }

/** A local SAF tree folder kept in two-way sync with a Google Drive folder. */
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
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("tree", treeUri).put("ln", localName).put("rid", remoteFolderId).put("rn", remoteName)
        .put("wifi", wifiOnly).put("auto", auto).put("st", status.name).put("last", lastSync).put("msg", message ?: "")
        .put("up", uploaded).put("down", downloaded).put("conf", conflicts)

    companion object {
        fun from(o: JSONObject) = SyncPair(
            id = o.getString("id"), treeUri = o.getString("tree"), localName = o.optString("ln"), remoteFolderId = o.getString("rid"),
            remoteName = o.optString("rn"), wifiOnly = o.optBoolean("wifi", true), auto = o.optBoolean("auto", true),
            status = runCatching { SyncStatus.valueOf(o.optString("st")) }.getOrDefault(SyncStatus.IDLE), lastSync = o.optLong("last"),
            message = o.optString("msg").ifEmpty { null }, uploaded = o.optInt("up"), downloaded = o.optInt("down"), conflicts = o.optInt("conf"),
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

    fun saveSnapshot(context: Context, id: String, map: Map<String, Entry>) {
        val o = JSONObject()
        map.forEach { (k, e) -> o.put(k, JSONObject().put("lm", e.localMod).put("ls", e.localSize).put("rid", e.remoteId).put("rm", e.remoteMod).put("rs", e.remoteSize)) }
        val f = snapshotFile(context, id)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }
}
