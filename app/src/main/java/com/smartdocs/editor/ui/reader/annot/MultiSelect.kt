package com.smartdocs.editor.ui.reader.annot

import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.core.annot.CommentStore
import com.smartdocs.editor.core.annot.StoredOps
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.annotAt
import com.smartdocs.editor.ui.reader.annotBounds
import com.smartdocs.editor.ui.reader.mapAnnot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Several annotations selected together on one page (lasso / rectangle with the Select tool, a tap on a
 * grouped annotation, or tap-to-add). Pending annotations are changed in memory as one undo step; stored
 * ones are moved in place in the file (appearance, replies and review states are kept), deleted, grouped or
 * converted to pending for colour / opacity changes.
 */
class MultiSelection(private val h: Annot2Host) {
    private val s get() = h.s
    var page by mutableIntStateOf(-1); private set
    val pending = mutableStateListOf<Long>()
    val stored = mutableStateListOf<AnnotationWriter.Existing>()
    var busy by mutableStateOf(false); private set

    val active: Boolean get() = pending.isNotEmpty() || stored.isNotEmpty()
    val count: Int get() = pending.size + stored.size

    fun clear() { pending.clear(); stored.clear(); page = -1 }

    fun pendingAnnots(): List<Annot> = s.annots.filter { it.id in pending }

    /** Union frame (PDF space, top = larger y) of everything selected. */
    fun bounds(): RectF? {
        val rs = pendingAnnots().map { annotBounds(it) } + stored.map { PageGeometry.normPdf(it.rect) }
        if (rs.isEmpty()) return null
        return RectF(rs.minOf { it.left }, rs.maxOf { it.top }, rs.maxOf { it.right }, rs.minOf { it.bottom })
    }

    private fun inside(poly: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var c = false; var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]; val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / ((yj - yi).takeIf { it != 0f } ?: 1e-6f) + xi) c = !c
            j = i
        }
        return c
    }

    /** Select everything on [pg] whose centre lies inside the lasso [poly] (PDF space). */
    fun selectIn(pg: Int, poly: List<Pair<Float, Float>>) {
        clear()
        if (poly.size < 3) return
        page = pg
        s.annots.filter { it.page == pg }.forEach { a ->
            val b = annotBounds(a); if (inside(poly, (b.left + b.right) / 2, (b.top + b.bottom) / 2)) pending += a.id
        }
        s.existing.filter { it.page == pg }.forEach { e ->
            val b = PageGeometry.normPdf(e.rect); if (inside(poly, (b.left + b.right) / 2, (b.top + b.bottom) / 2)) stored += e
        }
        expandGroups()
        if (!active) page = -1
    }

    /** Tap with the Select tool: add / remove the annotation under the finger. */
    fun toggleAt(pg: Int, x: Float, y: Float, tol: Float): Boolean {
        if (active && page != pg) clear()
        annotAt(s.annots, pg, x, y, tol)?.let { a ->
            page = pg; if (a.id in pending) pending.remove(a.id) else pending += a.id
            if (!active) page = -1
            return true
        }
        s.edit.storedAt(pg, x, y, tol)?.let { e ->
            page = pg
            val i = stored.indexOfFirst { it.page == e.page && it.index == e.index }
            if (i >= 0) stored.removeAt(i) else stored += e
            if (!active) page = -1
            return true
        }
        return false
    }

    /** Members of the same group join the selection (pending: same groupId; stored: /IRT /RT /Group). */
    private fun expandGroups() {
        val gids = pendingAnnots().mapNotNull { it.groupId }.toSet()
        if (gids.isNotEmpty()) s.annots.filter { it.page == page && it.groupId in gids && it.id !in pending }.forEach { pending += it.id }
        val items = h.comments
        val extra = stored.flatMap { e -> CommentStore.groupMembers(items, e.page, e.index) }.toSet()
        extra.forEach { idx -> if (stored.none { it.index == idx }) s.existing.firstOrNull { it.page == page && it.index == idx }?.let { stored += it } }
    }

    /** Called when a single annotation is tapped: grouped ones select their whole group here instead. */
    fun trySelectGroup(pendingHit: Annot?, storedHit: AnnotationWriter.Existing?): Boolean {
        if (pendingHit != null) {
            val gid = pendingHit.groupId ?: return false
            val members = s.annots.filter { it.groupId == gid && it.page == pendingHit.page }
            if (members.size < 2) return false
            clear(); page = pendingHit.page; members.forEach { pending += it.id }; return true
        }
        if (storedHit != null) {
            val members = CommentStore.groupMembers(h.comments, storedHit.page, storedHit.index)
            if (members.size < 2) return false
            clear(); page = storedHit.page
            members.forEach { idx -> s.existing.firstOrNull { it.page == storedHit.page && it.index == idx }?.let { stored += it } }
            return active
        }
        return false
    }

    // ---------------- operations ----------------

    /** Move everything by (dx, dy) PDF points. */
    fun moveBy(dx: Float, dy: Float) {
        if (abs(dx) < 0.01f && abs(dy) < 0.01f) return
        val ids = pending.toSet()
        if (ids.isNotEmpty()) s.batchAnnots { list ->
            for (i in list.indices) if (list[i].id in ids) {
                val b = annotBounds(list[i]); list[i] = mapAnnot(list[i], b, RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy))
            }
        }
        val refs = stored.map { it.index }
        if (refs.isEmpty()) return
        val pg = page
        run(true) {
            h.fileOp { src, out, pw -> StoredOps.translate(src, out, pw, pg, refs, dx, dy) }
            refreshStored(pg, refs)
        }
    }

    fun delete() {
        val ids = pending.toSet()
        if (ids.isNotEmpty()) s.batchAnnots { list -> list.removeAll { it.id in ids } }
        val refs = stored.map { it.index }; val pg = page
        clear()
        if (refs.isNotEmpty()) run(false) { h.fileOp { src, out, pw -> CommentStore.deleteMany(src, out, pw, mapOf(pg to refs)) } }
    }

    fun setColor(c: Int) = restyle { it.copy(color = c) }
    fun setOpacity(o: Float) = restyle { it.copy(opacity = o.coerceIn(0.1f, 1f)) }
    fun setThickness(w: Float) = restyle { if (it.kind in strokeKinds) it.copy(strokeWidth = w.coerceIn(0.5f, 40f)) else it }

    /** Style changes need fresh appearance streams: stored annotations the editor can rewrite become pending first. */
    private fun restyle(change: (Annot) -> Annot) {
        run(false) {
            if (stored.isNotEmpty()) convertStored()
            val ids = pending.toSet()
            s.batchAnnots { list -> for (i in list.indices) if (list[i].id in ids) list[i] = change(list[i]) }
        }
    }

    /** Group the selection: pending annotations are saved first so every member is a stored annotation. */
    fun group() = regroup(true)
    fun ungroup() = regroup(false)

    private fun regroup(join: Boolean) {
        val pg = page
        run(false) {
            val nms = pendingAnnots().map { it.nm ?: "sd-${it.id}" }.toSet()
            var refs = stored.map { it.index }
            if (nms.isNotEmpty()) {
                if (!s.save()) return@run
                val f = s.file ?: return@run
                val listed = AnnotationWriter.list(f, s.password)
                val names = withContext(kotlinx.coroutines.Dispatchers.IO) { StoredOps.namesOf(f, s.password, pg) }
                refs = refs + listed.filter { it.page == pg && names[it.index] in nms }.map { it.index }
            }
            refs = refs.distinct()
            if (join && refs.size < 2) return@run
            h.fileOp { src, out, pw -> if (join) CommentStore.group(src, out, pw, pg, refs) else CommentStore.ungroup(src, out, pw, pg, refs) }
            h.reloadComments()
            refreshStored(pg, refs)
            pending.clear()
        }
    }

    /** After a file change: point the stored selection at the re-listed annotations (same indices). */
    private suspend fun refreshStored(pg: Int, refs: List<Int>) {
        val f = s.file ?: return
        val now = runCatching { AnnotationWriter.list(f, s.password) }.getOrDefault(emptyList())
        stored.clear(); now.filter { it.page == pg && it.index in refs }.forEach { stored += it }
        if (active) page = pg
    }

    /** Convert extractable stored annotations into pending ones (one file pass, backup for "discard"). */
    private suspend fun convertStored() {
        if (!s.guard.check(com.smartdocs.editor.core.security.DocAction.ANNOTATE)) return      // nitro-protect2
        val f = s.file ?: return
        val pg = page
        val targets = stored.toList()
        val converted = ArrayList<Annot>()
        val removed = ArrayList<Int>()
        s.saveMutex.withLock {
            withContext(NonCancellable) {
                targets.forEach { e ->
                    AnnotationWriter.extract(f, e.page, e.index, s.password)?.let { converted += it; removed += e.index }
                }
                if (removed.isEmpty()) return@withContext
                s.edit.ensureBackup()
                val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.saving.pdf")
                StoredOps.removeOnly(f, tmp, s.password, pg, removed)
                s.swapFile(tmp, record = false)      // not an undo step: they live on as pending annotations
                converted.forEach { s.adoptConverted(it) }
            }
        }
        stored.removeAll { it.index in removed }
        // indices of stored annotations after removed ones shifted down
        val left = stored.map { e -> e.index - removed.count { it < e.index } }
        refreshStored(pg, left)
        converted.forEach { pending += it.id }
        if (active) page = pg
    }

    private fun run(keepSelection: Boolean, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        s.scope.launch {
            try { block() } catch (e: Exception) { s.error = e.message ?: e.toString() } finally { busy = false; if (!keepSelection && !active) page = -1 }
        }
    }

    companion object {
        val strokeKinds = setOf(AnnotKind.INK, AnnotKind.LINE, AnnotKind.ARROW, AnnotKind.RECT, AnnotKind.OVAL, AnnotKind.POLYGON, AnnotKind.POLYLINE, AnnotKind.CLOUD)
    }
}
