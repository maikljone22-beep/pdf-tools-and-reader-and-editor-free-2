package com.smartdocs.editor.core.collab

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A shared review this device created or joined. [localPath] is the working copy opened in the reader. */
data class ReviewRef(
    val id: String, val code: String, val title: String, val fileName: String, val localPath: String,
    val ownerKey: String? = null, val deadline: Long = 0L, val lastSync: Long = 0L, val closed: Boolean = false,
) {
    val isOwner get() = ownerKey != null
    fun toJson(): JSONObject = JSONObject().put("id", id).put("code", code).put("title", title).put("file", fileName).put("path", localPath)
        .put("owner", ownerKey ?: "").put("deadline", deadline).put("last", lastSync).put("closed", closed)
    companion object {
        fun from(o: JSONObject) = ReviewRef(o.getString("id"), o.getString("code"), o.optString("title"), o.optString("file"), o.getString("path"),
            o.optString("owner").ifEmpty { null }, o.optLong("deadline"), o.optLong("last"), o.optBoolean("closed"))
    }
}

/** Registry of reviews + the publish/fetch logic that keeps a local PDF and the review server in step. */
object CollabStore {
    private val _reviews = MutableStateFlow<List<ReviewRef>>(emptyList())
    val reviews: StateFlow<List<ReviewRef>> = _reviews
    @Volatile private var loaded = false
    private val ioLock = Mutex()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("collab", Context.MODE_PRIVATE)

    fun load(c: Context): List<ReviewRef> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val arr = runCatching { JSONArray(prefs(c).getString("reviews", "[]")) }.getOrDefault(JSONArray())
                _reviews.value = (0 until arr.length()).mapNotNull { runCatching { ReviewRef.from(arr.getJSONObject(it)) }.getOrNull() }
                loaded = true
            }
        }
        return _reviews.value
    }

    private fun save(c: Context, list: List<ReviewRef>) {
        _reviews.value = list
        prefs(c).edit().putString("reviews", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun upsert(c: Context, r: ReviewRef) = synchronized(this) { save(c, load(c).filterNot { it.id == r.id } + r) }
    fun remove(c: Context, id: String) = synchronized(this) {
        save(c, load(c).filterNot { it.id == id })
        prefs(c).edit().remove("pub_$id").remove("foreign_$id").remove("applied_$id").apply()
    }
    fun get(c: Context, id: String) = load(c).firstOrNull { it.id == id }
    fun forFile(c: Context, f: File) = load(c).firstOrNull { it.localPath == f.absolutePath }

    /** Random id of this installation inside reviews (comments are grouped per participant on the server). */
    fun participantId(c: Context): String = prefs(c).getString("participant", null) ?: UUID.randomUUID().toString().also { prefs(c).edit().putString("participant", it).apply() }

    fun reviewsDir(c: Context): File = File(File(c.getExternalFilesDir(null) ?: c.filesDir, "Documents"), "Reviews").apply { mkdirs() }

    // ---- per-review bookkeeping: what I published (nm → hash), what came from others (nm → modified) ----
    private fun map(c: Context, key: String): MutableMap<String, String> {
        val o = runCatching { JSONObject(prefs(c).getString(key, "{}")!!) }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }
    private fun putMap(c: Context, key: String, m: Map<String, String>) = prefs(c).edit().putString(key, JSONObject(m as Map<*, *>).toString()).apply()

    /** Called by SaveHooks after the reader saved a file: publishes when the file belongs to a review. */
    suspend fun onDocumentSaved(c: Context, f: File) {
        val ref = forFile(c, f) ?: return
        if (!CollabConfig.enabled || ref.closed) return
        runCatching { publish(c, ref) }
    }

    /** Marks every annotation currently in the file as coming from others (used right after joining). */
    suspend fun markAllForeign(c: Context, ref: ReviewRef) = withContext(Dispatchers.IO) {
        PDDocument.load(File(ref.localPath)).use { doc ->
            val changed = CollabAnnots.ensureNames(doc)
            val foreign = map(c, "foreign_${ref.id}")
            CollabAnnots.read(doc).forEach { foreign[it.nm] = "0" }
            putMap(c, "foreign_${ref.id}", foreign)
            if (changed) saveAtomically(doc, File(ref.localPath))
        }
    }

    /** Marks my current annotations as already published (the owner's initial comments travel inside the uploaded PDF). */
    suspend fun markAllPublished(c: Context, ref: ReviewRef) = withContext(Dispatchers.IO) {
        PDDocument.load(File(ref.localPath)).use { doc ->
            putMap(c, "pub_${ref.id}", CollabAnnots.read(doc).associate { it.nm to it.hash })
        }
    }

    /** Sends my new/changed comments and deletions. Returns how many items were sent. */
    suspend fun publish(c: Context, ref: ReviewRef): Int = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val file = File(ref.localPath)
            PDDocument.load(file).use { doc ->
                if (CollabAnnots.ensureNames(doc)) saveAtomically(doc, file)
                val foreign = map(c, "foreign_${ref.id}")
                val published = map(c, "pub_${ref.id}")
                val mine = CollabAnnots.read(doc).filter { it.nm !in foreign }
                val changed = mine.filter { published[it.nm] != it.hash }
                val deleted = published.keys - mine.map { it.nm }.toSet()
                if (changed.isEmpty() && deleted.isEmpty()) return@withLock 0
                CollabClient.publish(ref.id, ref.code, participantId(c), authorName(c), changed, deleted)
                putMap(c, "pub_${ref.id}", mine.associate { it.nm to it.hash })
                upsert(c, ref.copy(lastSync = System.currentTimeMillis()))
                changed.size + deleted.size
            }
        }
    }

    /** Merges other participants' comments into the local file. Returns the number of changes applied. */
    suspend fun fetch(c: Context, ref: ReviewRef): Int = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val me = participantId(c)
            val remote = CollabClient.comments(ref.id, ref.code).filter { it.participant != me }
            val applied = map(c, "applied_${ref.id}")
            val fresh = remote.filter { (applied[it.nm]?.toLongOrNull() ?: -1L) < it.modified }
            if (fresh.isEmpty()) { upsert(c, (get(c, ref.id) ?: ref).copy(lastSync = System.currentTimeMillis())); return@withLock 0 }
            val file = File(ref.localPath)
            val n = PDDocument.load(file).use { doc ->
                val upserts = fresh.filter { !it.deleted && it.data != null }.map { it.page to it.data!! }
                val deletes = fresh.filter { it.deleted }.map { it.nm }.toSet()
                val count = CollabAnnots.apply(doc, upserts, deletes)
                if (count > 0) saveAtomically(doc, file)
                count
            }
            val foreign = map(c, "foreign_${ref.id}")
            fresh.forEach { applied[it.nm] = it.modified.toString(); if (!it.deleted) foreign[it.nm] = it.modified.toString() else foreign.remove(it.nm) }
            putMap(c, "applied_${ref.id}", applied); putMap(c, "foreign_${ref.id}", foreign)
            upsert(c, (get(c, ref.id) ?: ref).copy(lastSync = System.currentTimeMillis()))
            n
        }
    }

    fun authorName(c: Context): String = prefs(c).getString("author", null).orEmpty()
    fun setAuthorName(c: Context, name: String) = prefs(c).edit().putString("author", name).apply()

    private fun saveAtomically(doc: PDDocument, target: File) {
        val tmp = File(target.parentFile, ".${target.name}.collab")
        doc.save(tmp)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
    }
}
