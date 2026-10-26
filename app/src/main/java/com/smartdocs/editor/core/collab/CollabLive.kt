package com.smartdocs.editor.core.collab

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Live shared-review comments for the document open in the reader (share6).
 *
 * While a review file is attached nothing in the background rewrites it: other reviewers' comments are polled
 * every 30 s, kept in memory ([remote], [arrivals] for the "N new comments" message) and merged
 *  - into the reader's next save ([beforeSave], called between the annotation commit and the file swap), or
 *  - right away when the user taps "show" ([mergeOp] through `ReaderState.applyInPlace`).
 * They are marked applied only in [confirm], i.e. after the save really replaced the file (SaveHooks calls it before
 * publishing my own comments, so merged comments are never republished as mine). Comments still pending when the
 * reader closes are written into the file 1.5 s after [detach]; if that fails they are simply fetched again later.
 */
object CollabLive {
    /** A remote comment not merged yet, for drawing (PDF points, origin bottom-left). */
    data class RemoteAnnot(val nm: String, val page: Int, val rect: FloatArray?, val color: Int?, val author: String, val contents: String, val subtype: String)

    const val POLL_MS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _remote = MutableStateFlow<List<RemoteAnnot>>(emptyList())
    val remote: StateFlow<List<RemoteAnnot>> = _remote
    private val _arrivals = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** Emits the number of comments that just arrived (never seen on this device before). */
    val arrivals: SharedFlow<Int> = _arrivals
    private val _active = MutableStateFlow<String?>(null)
    /** Review id of the attached document, or null. */
    val active: StateFlow<String?> = _active

    @Volatile private var file: File? = null
    @Volatile private var ref: ReviewRef? = null
    @Volatile private var pending: List<RemoteComment> = emptyList()
    private val announced = HashSet<String>()
    /** Plan written into a save that has not been confirmed yet: (target path, remote list, plan). */
    @Volatile private var staged: Triple<String, List<RemoteComment>, CommentMerge.Plan>? = null
    private var poll: Job? = null

    fun isAttached(f: File) = file?.absolutePath == f.absolutePath

    /** Starts live comments for [f] when it is a shared-review working copy. Returns true when attached. */
    @Synchronized fun attach(c: Context, f: File): Boolean {
        if (!CollabConfig.enabled) return false
        val r = CollabStore.forFile(c, f) ?: return false
        if (isAttached(f)) return true
        file = f; ref = r; pending = emptyList(); _remote.value = emptyList(); _active.value = r.id
        synchronized(announced) { announced.clear() }
        poll?.cancel()
        val app = c.applicationContext
        poll = scope.launch {
            while (isActive) {
                runCatching { refresh(app) }
                delay(POLL_MS)
            }
        }
        return true
    }

    @Synchronized fun detach(c: Context, f: File? = null) {
        if (f != null && !isAttached(f)) return
        poll?.cancel(); poll = null
        val r = ref; val left = pending
        file = null; ref = null; pending = emptyList(); _remote.value = emptyList(); _active.value = null
        if (r != null && left.isNotEmpty()) {
            val app = c.applicationContext
            scope.launch { delay(1_500); runCatching { CollabStore.fetch(app, CollabStore.get(app, r.id) ?: r) } }
        }
    }

    /** Fetches now (e.g. a "refresh" button). */
    suspend fun refresh(c: Context) {
        val r = ref ?: return
        val list = CollabClient.comments(r.id, r.code)
        offer(c, r, list)
    }

    /** New server state for the attached review (also called by CollabStore.fetch instead of writing the file). */
    fun offer(c: Context, r: ReviewRef, list: List<RemoteComment>) {
        val f = file ?: return
        if (ref?.id != r.id) return
        val present = runCatching { com.tom_roush.pdfbox.pdmodel.PDDocument.load(f).use { CollabStore.presentNames(it) } }.getOrDefault(emptySet())
        val plan = CollabStore.planFor(c, r, list, present)
        val wanted = plan.upserts.toSet() + plan.deletes
        pending = list.filter { it.nm in wanted }
        _remote.value = pending.filter { !it.deleted && it.data != null }.map(::toAnnot)
        val fresh = synchronized(announced) {
            plan.upserts.filter { nm -> nm !in present && nm !in announced && pending.any { it.nm == nm && !it.deleted } }.also { announced.addAll(it) }
        }
        if (fresh.isNotEmpty()) _arrivals.tryEmit(fresh.size)
    }

    /**
     * Reader hook (ReaderState.save, between AnnotationWriter.commit and swapFile): merges pending remote comments
     * into [out], the file about to replace [src]. Never throws; waits at most 4 s for the network.
     */
    suspend fun beforeSave(c: Context, src: File, out: File, password: String?) {
        if (!isAttached(src)) return
        val r = ref ?: return
        runCatching {
            withTimeoutOrNull(4_000) { runCatching { refresh(c) } }
            val list = pending
            if (list.isEmpty()) return
            val plan = CollabStore.mergeInto(c, r, out, out, password, list)
            if (!plan.isEmpty) staged = Triple(src.absolutePath, list, plan)
        }
    }

    /** Operation for `ReaderState.applyInPlace` ("show" in the new-comments message): merges now and reopens the document. */
    val mergeOp: suspend (Context, File, File, String?) -> Unit = { c, src, out, pw ->
        val r = ref ?: CollabStore.forFile(c, src) ?: throw IllegalStateException("not a review")
        runCatching { refresh(c) }
        val list = pending
        val plan = CollabStore.mergeInto(c, r, src, out, pw, list)
        if (!plan.isEmpty) staged = Triple(src.absolutePath, list, plan)
        else src.copyTo(out, overwrite = true)
    }

    /** SaveHooks: the save of [f] replaced the file, so the staged comments are now really in it. */
    fun confirm(c: Context, f: File) {
        val s = staged ?: return
        if (s.first != f.absolutePath) return
        staged = null
        val r = CollabStore.forFile(c, f) ?: return
        CollabStore.markApplied(c, r, s.second, s.third)
        val done = s.third.upserts.toSet() + s.third.deletes
        pending = pending.filterNot { it.nm in done }
        _remote.value = _remote.value.filterNot { it.nm in done }
    }

    // ------------------------------------------------------------------ JSON dictionary → drawable summary

    private fun str(v: Any?): String = (v as? JSONObject)?.optString("s")?.takeIf { it.isNotEmpty() }?.let { b ->
        val bytes = Base64.decode(b, Base64.NO_WRAP)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else String(bytes, Charsets.ISO_8859_1)
    }.orEmpty()

    private fun nums(v: Any?): FloatArray? = (v as? JSONArray)?.let { a -> FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat() } }

    fun toAnnot(rc: RemoteComment): RemoteAnnot {
        val d = rc.data?.optJSONObject("d") ?: JSONObject()
        val rect = nums(d.opt("Rect"))?.takeIf { it.size == 4 }?.let { floatArrayOf(minOf(it[0], it[2]), minOf(it[1], it[3]), maxOf(it[0], it[2]), maxOf(it[1], it[3])) }
        val color = nums(d.opt("C"))?.takeIf { it.size == 3 }?.let { c ->
            (0xFF shl 24) or ((c[0] * 255).toInt().coerceIn(0, 255) shl 16) or ((c[1] * 255).toInt().coerceIn(0, 255) shl 8) or (c[2] * 255).toInt().coerceIn(0, 255)
        }
        return RemoteAnnot(rc.nm, rc.page, rect, color, str(d.opt("T")).ifEmpty { rc.author }, str(d.opt("Contents")), d.optJSONObject("Subtype")?.optString("n").orEmpty())
    }
}
