package com.smartdocs.editor.core.collab

import android.content.Context
import com.smartdocs.editor.core.pdf.SignedSave
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

    fun upsert(c: Context, r: ReviewRef) {
        synchronized(this) { save(c, load(c).filterNot { it.id == r.id } + r) }
        runCatching { ReviewReminder.schedule(c, r) }   // fc-sync: local reminder before the deadline
    }
    fun remove(c: Context, id: String) = synchronized(this) {
        runCatching { ReviewReminder.cancel(c, id) }
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
        if (com.smartdocs.editor.core.share5.ReviewLocal.offline(c)) return   // nav-attach: "work offline" keeps comments pending
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
                val consent = com.smartdocs.editor.core.share5.ReviewConsent   // share6: anonymous reviewers publish under a pseudonym
                CollabClient.publish(ref.id, ref.code, participantId(c), consent.author(c, ref.id, authorName(c)), changed.map { consent.outgoing(c, ref.id, it) }, deleted)
                putMap(c, "pub_${ref.id}", mine.associate { it.nm to it.hash })
                upsert(c, ref.copy(lastSync = System.currentTimeMillis()))
                changed.size + deleted.size
            }
        }
    }

    /** Merges other participants' comments into the local file. Returns the number of changes applied.
     *  While the file is open in the reader (attached to [CollabLive]) nothing is written: the comments are handed to
     *  CollabLive, which shows them and merges them into the reader's next save. */
    suspend fun fetch(c: Context, ref: ReviewRef): Int = withContext(Dispatchers.IO) {
        val remote = CollabClient.comments(ref.id, ref.code)
        if (CollabLive.isAttached(File(ref.localPath))) { CollabLive.offer(c, ref, remote); upsert(c, (get(c, ref.id) ?: ref).copy(lastSync = System.currentTimeMillis())); return@withContext 0 }
        ioLock.withLock {
            val file = File(ref.localPath)
            val n = PDDocument.load(file).use { doc ->
                val plan = planFor(c, ref, remote, presentNames(doc))
                if (plan.isEmpty) { markApplied(c, ref, remote, plan); return@use 0 }
                val count = applyPlan(doc, remote, plan)
                if (count > 0) saveAtomically(doc, file)
                markApplied(c, ref, remote, plan)
                count
            }
            upsert(c, (get(c, ref.id) ?: ref).copy(lastSync = System.currentTimeMillis()))
            n
        }
    }

    /** Thrown by [takeOver] while the document is open in the reader (its save would overwrite the change). */
    class DocumentOpen : IllegalStateException("document open")

    /**
     * fc-sync "take ownership": the comments of [author] (null = all other reviewers) become mine: their /T is set to
     * my name, they leave the "from others" set and are published as mine. Returns how many were taken.
     */
    suspend fun takeOver(c: Context, ref: ReviewRef, author: String?): Int = withContext(Dispatchers.IO) {
        val file = File(ref.localPath)
        if (CollabLive.isAttached(file)) throw DocumentOpen()
        val n = ioLock.withLock {
            PDDocument.load(file).use { doc ->
                val foreign = map(c, "foreign_${ref.id}")
                val byName = HashMap<String, com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>()
                doc.pages.forEach { p -> runCatching { p.annotations }.getOrDefault(emptyList()).forEach { a -> a.annotationName?.let { byName[it] = a } } }
                val authors = byName.mapValues { (_, a) -> (a as? com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup)?.titlePopup.orEmpty() }
                val picked = CommentMerge.takeOver(authors, foreign.keys, author)
                if (picked.isEmpty()) return@use 0
                val me = com.smartdocs.editor.core.share5.ReviewConsent.author(c, ref.id, authorName(c))
                picked.forEach { nm -> (byName[nm] as? com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup)?.titlePopup = me }
                saveAtomically(doc, file)
                picked.forEach { foreign.remove(it) }
                putMap(c, "foreign_${ref.id}", foreign)
                picked.size
            }
        }
        if (n > 0 && CollabConfig.enabled && !ref.closed) runCatching { publish(c, ref) }
        n
    }

    /** /NM of every annotation in [doc]. */
    fun presentNames(doc: PDDocument): Set<String> = HashSet<String>().apply { doc.pages.forEach { p -> p.annotations.forEach { a -> a.annotationName?.let(::add) } } }

    /** What merging [remote] would change (see [CommentMerge]). */
    fun planFor(c: Context, ref: ReviewRef, remote: List<RemoteComment>, present: Set<String>): CommentMerge.Plan {
        val applied = map(c, "applied_${ref.id}").mapValues { it.value.toLongOrNull() ?: -1L }
        val mine = map(c, "pub_${ref.id}").keys
        return CommentMerge.plan(remote.map { CommentMerge.Incoming(it.nm, it.participant, it.modified, it.deleted) }, participantId(c), applied, mine, present)
    }

    /** Writes the plan into [doc] (not saved). Returns the number of annotation changes. */
    fun applyPlan(doc: PDDocument, remote: List<RemoteComment>, plan: CommentMerge.Plan): Int {
        val wanted = plan.upserts.toSet()
        val newest = remote.filter { it.nm in wanted && !it.deleted && it.data != null }.groupBy { it.nm }.mapValues { (_, v) -> v.maxByOrNull { it.modified }!! }
        return CollabAnnots.apply(doc, newest.values.map { it.page to it.data!! }, plan.deletes)
    }

    /** Records the plan as applied: those comments are others' (never republished as mine) and are not merged again. */
    fun markApplied(c: Context, ref: ReviewRef, remote: List<RemoteComment>, plan: CommentMerge.Plan) {
        val applied = map(c, "applied_${ref.id}")
        val foreign = map(c, "foreign_${ref.id}")
        plan.applied.forEach { (nm, mod) -> applied[nm] = mod.toString() }
        plan.upserts.forEach { nm -> foreign[nm] = (plan.applied[nm] ?: 0L).toString() }
        plan.deletes.forEach { foreign.remove(it) }
        putMap(c, "applied_${ref.id}", applied); putMap(c, "foreign_${ref.id}", foreign)
    }

    /** Merges [remote] into the PDF [src] and writes the result to [dest] ([src] may equal [dest]). Returns the plan used. */
    suspend fun mergeInto(c: Context, ref: ReviewRef, src: File, dest: File, password: String?, remote: List<RemoteComment>): CommentMerge.Plan =
        withContext(Dispatchers.IO) {
            ioLock.withLock {
                PDDocument.load(src, password ?: "").use { doc ->
                    val plan = planFor(c, ref, remote, presentNames(doc))
                    if (!plan.isEmpty) { applyPlan(doc, remote, plan); saveAtomically(doc, dest, password) }
                    else if (src.absolutePath != dest.absolutePath) src.copyTo(dest, overwrite = true)
                    plan
                }
            }
        }

    fun authorName(c: Context): String = prefs(c).getString("author", null).orEmpty()
    fun setAuthorName(c: Context, name: String) = prefs(c).edit().putString("author", name).apply()

    private fun saveAtomically(doc: PDDocument, target: File, password: String? = null) {
        val tmp = File(target.parentFile, ".${target.name}.collab")
        // a signed file gets the merged comments as an incremental update, so its signatures stay valid (audit AN-28)
        if (SignedSave.isSigned(doc)) SignedSave.saveEdited(doc, tmp, password, 0 until doc.numberOfPages)
        else doc.save(tmp)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
    }
}
