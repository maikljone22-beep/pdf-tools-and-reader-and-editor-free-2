package com.smartdocs.editor.ui.pages

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Filter chips of the organizer. */
enum class PageFilter { ALL, ANNOTATED, BOOKMARKED }

/**
 * Editing state of the page organizer. Every operation writes a new temp file (never the original);
 * previous versions are kept on a stack so undo is a simple pop.
 */
class OrganizeState(
    private val context: Context,
    private val documents: DocumentRepository,
    private val scope: CoroutineScope,
) {
    /** The file the user opened (overwritten only by "save"). */
    var original by mutableStateOf<File?>(null); private set
    /** Current working version (temp file or the original when untouched). */
    var work by mutableStateOf<File?>(null); private set
    /** Increments on every version change so thumbnails are re-rendered. */
    var version by mutableIntStateOf(0); private set
    var pageCount by mutableIntStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var password by mutableStateOf<String?>(null)

    var selected by mutableStateOf(setOf<Int>())
    var selectMode by mutableStateOf(false)
    var filter by mutableStateOf(PageFilter.ALL)
    var annotatedPages by mutableStateOf(setOf<Int>()); private set
    var bookmarkedPages by mutableStateOf(setOf<Int>()); private set
    /** Pages copied with "copy" (indexes into the version they were copied from; we keep it simple and re-validate on paste). */
    var clipboard by mutableStateOf(listOf<Int>())

    /** Thumbnails keyed by "version:index". */
    val thumbs = mutableStateMapOf<String, Bitmap>()
    private val sizes = java.util.concurrent.ConcurrentHashMap<Int, Pair<Float, Float>>()
    private val history = ArrayList<File>()
    val canUndo: Boolean get() = history.isNotEmpty()
    val dirty: Boolean get() = history.isNotEmpty()
    val changes: Int get() = history.size

    private var session: PdfSession? = null
    private val sessionLock = Mutex()
    private val thumbJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun open(file: File, bookmarkedFlow: suspend () -> Set<Int>) {
        scope.launch {
            busy = true; error = null
            try {
                original = file; work = file
                history.clear(); selected = emptySet(); selectMode = false; thumbs.clear(); sizes.clear()
                reopen()
                annotatedPages = runCatching { com.smartdocs.editor.core.pdf.AnnotationWriter.list(file, password).map { it.page }.toSet() }.getOrDefault(emptySet())
                bookmarkedPages = runCatching { bookmarkedFlow() }.getOrDefault(emptySet())
            } catch (e: PdfSession.Companion.PasswordRequiredException) {
                original = null; work = null; error = "password"
            } catch (e: Exception) { original = null; work = null; error = e.message ?: e.toString() } finally { busy = false }
        }
    }

    private suspend fun reopen() {
        sessionLock.withLock {
            session?.closeAsync(); session = null
            val f = work ?: return
            val s = PdfSession.open(context, f, password)
            session = s
            pageCount = s.pageCount
            sizes.clear()
        }
        version++
        val prefix = "$version:"
        thumbs.keys.filter { !it.startsWith(prefix) }.forEach { thumbs.remove(it) }
    }

    /** Page size in points (cached). */
    suspend fun pageSize(index: Int): Pair<Float, Float> {
        sizes[index]?.let { return it }
        val s = session ?: return 595f to 842f
        return runCatching { s.pageSize(index) }.getOrDefault(595f to 842f).also { sizes[index] = it }
    }

    fun thumbKey(index: Int) = "$version:$index"

    /** Render a ~[widthPx] wide thumbnail once; result lands in [thumbs]. */
    fun requestThumb(index: Int, widthPx: Int = 300) {
        val key = thumbKey(index)
        if (thumbs.containsKey(key) || thumbJobs.containsKey(key)) return
        val v = version
        thumbJobs[key] = scope.launch(Dispatchers.IO) {
            try {
                val s = session ?: return@launch
                if (v != version) return@launch
                val (w, h) = pageSize(index)
                val hPx = (widthPx * h / w).toInt().coerceAtLeast(1)
                val bmp = s.render(index, widthPx, hPx)
                if (v == version) withContext(Dispatchers.Main) { thumbs[key] = bmp }
            } catch (_: Exception) { } finally { thumbJobs.remove(key) }
        }
    }

    /** Run [op] producing a new version. The previous version is pushed to the undo stack. */
    fun apply(selectAfter: Set<Int> = emptySet(), op: suspend (src: File, out: File) -> Unit) {
        val src = work ?: return
        if (busy) return
        scope.launch {
            busy = true; error = null
            try {
                val countBefore = pageCount
                val out = documents.newTemp("org")
                withContext(Dispatchers.IO) { op(src, out) }
                history += src
                work = out
                reopen()
                // Annotated/bookmarked sets refer to the original numbering; drop them once the structure changed.
                if (pageCount != countBefore) { annotatedPages = emptySet(); bookmarkedPages = emptySet(); if (filter != PageFilter.ALL) filter = PageFilter.ALL }
                selected = selectAfter.filter { it in 0 until pageCount }.toSet()
                selectMode = selected.isNotEmpty()
            } catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
        }
    }

    fun undo() {
        if (history.isEmpty() || busy) return
        scope.launch {
            busy = true
            try {
                val prev = history.removeAt(history.size - 1)
                work?.let { if (it != original) it.delete() }
                work = prev
                selected = emptySet()
                reopen()
            } catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
        }
    }

    // ---- operations -------------------------------------------------------------------------

    fun deleteSelected() {
        val sel = selected.filter { it in 0 until pageCount }.toSet()
        if (sel.isEmpty() || sel.size >= pageCount) return
        apply { src, out -> PdfOps.deletePages(src, sel, out, password) }
    }

    fun rotateSelected(degrees: Int) {
        val sel = selected.sorted()
        if (sel.isEmpty()) return
        apply { src, out -> PdfOps.rotatePages(src, sel, degrees, out, password) }
    }

    fun insertBlank(at: Int, style: PdfOps.PageStyle) {
        apply { src, out -> PdfOps.insertBlankPage(src, at, out, style, password) }
    }

    fun insertFrom(other: File, at: Int) = insertFrom(other, at, null)

    /** share6 (fid): [otherPassword] unlocks a password-protected [other] (a decrypted temp copy is inserted, then deleted). */
    fun insertFrom(other: File, at: Int, otherPassword: String?) {
        apply { src, out ->
            val u = withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.LockedPdf.unlockedForInsert(other, otherPassword, documents.tempDir) }
            try { PdfOps.insertPagesFrom(src, u ?: other, at, out, password) } finally { u?.delete() }
        }
    }

    fun insertImages(images: List<File>, at: Int) {
        if (images.isEmpty()) return
        apply { src, out ->
            val tmp = documents.newTemp("imgpages")
            PdfOps.imagesToPdf(images, tmp, fitA4 = true)
            PdfOps.insertPagesFrom(src, tmp, at, out, password)
            tmp.delete()
        }
    }

    fun reorder(order: List<Int>) {
        if (order.size != pageCount || order == (0 until pageCount).toList()) return
        apply { src, out -> PdfOps.reorder(src, order, out, password) }
    }

    /** Move the single selected page one slot towards the start (-1) or the end (+1). */
    fun moveSelected(delta: Int) {
        val idx = selected.singleOrNull() ?: return
        val target = idx + delta
        if (target !in 0 until pageCount) return
        val order = (0 until pageCount).toMutableList()
        order.removeAt(idx); order.add(target, idx)
        apply(selectAfter = setOf(target)) { src, out -> PdfOps.reorder(src, order, out, password) }
    }

    fun copySelected() { clipboard = selected.sorted() }

    /** Paste clipboard pages after page [after] (or at the end when -1). */
    fun paste(after: Int) {
        val clip = clipboard.filter { it in 0 until pageCount }
        if (clip.isEmpty()) return
        val order = (0 until pageCount).toMutableList()
        val pos = if (after < 0) order.size else (after + 1).coerceIn(0, order.size)
        order.addAll(pos, clip)
        // audit AN-22: in place on the page tree (keeps outline, forms, labels, encryption); pasted pages are copies
        apply { src, out -> PdfOps.reorder(src, order, out, password) }
    }

    /** Extract selected pages into a new file in docsDir. */
    suspend fun extractSelected(): File? {
        val src = work ?: return null
        val sel = selected.sorted()
        if (sel.isEmpty()) return null
        val base = (original?.nameWithoutExtension ?: "pages") + "_pages"
        val out = documents.uniqueFile(documents.docsDir, base)
        PdfOps.keepPages(src, sel, out, password)
        return out
    }

    /** Selected pages into a temp file for sharing. */
    suspend fun selectedToTemp(): File? {
        val src = work ?: return null
        val sel = selected.sorted()
        if (sel.isEmpty()) return null
        val out = documents.newTemp((original?.nameWithoutExtension ?: "pages") + "_pages")
        PdfOps.keepPages(src, sel, out, password)
        return out
    }

    /** Overwrite the original file with the working version. */
    suspend fun saveOverwrite(): File? {
        val o = original ?: return null; val w = work ?: return null
        if (w != o) {
            // tab-view-core G-01: page changes are never written over a signed / certified original (save as new instead)
            withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.SignedGuard.checkWrite(o, w, password) }
            // close our session first, then replace atomically (sibling temp + rename) so a crash mid-copy
            // never leaves a truncated original behind
            sessionLock.withLock { session?.closeAsync(); session = null }
            withContext(Dispatchers.IO) {
                val tmp = File(o.parentFile, ".${o.nameWithoutExtension}.saving.pdf")
                w.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(o)) { tmp.copyTo(o, overwrite = true); tmp.delete() }
            }
        }
        runCatching { documents.touchRecent(o, pageCount) }
        clearHistory()
        work = o
        reopen()
        return o
    }

    /** Save the working version as a new file next to the original in docsDir. */
    suspend fun saveAsNew(): File? {
        val o = original ?: return null; val w = work ?: return null
        val out = documents.uniqueFile(documents.docsDir, o.nameWithoutExtension + "_organized")
        withContext(Dispatchers.IO) { w.copyTo(out, overwrite = true) }
        runCatching { documents.touchRecent(out, pageCount) }
        return out
    }

    private fun clearHistory() {
        history.forEach { if (it != original) it.delete() }
        history.clear()
    }

    fun visiblePages(): List<Int> = when (filter) {
        PageFilter.ALL -> (0 until pageCount).toList()
        PageFilter.ANNOTATED -> (0 until pageCount).filter { it in annotatedPages }
        PageFilter.BOOKMARKED -> (0 until pageCount).filter { it in bookmarkedPages }
    }

    fun toggle(index: Int) {
        selected = if (index in selected) selected - index else selected + index
        if (selected.isEmpty()) selectMode = false
    }

    fun dispose() {
        thumbJobs.values.forEach { it.cancel() }
        runCatching { session?.close() }; session = null
        // temp versions are in cacheDir; leave the last work file in case a ResultSheet still points at it
        history.forEach { if (it != original) runCatching { it.delete() } }
    }

    companion object {
        /** Helper to collect the bookmark pages for a document uri once. */
        suspend fun bookmarks(dao: com.smartdocs.editor.core.data.BookmarkDao, uri: String): Set<Int> =
            dao.forDoc(uri).first().map { it.page }.toSet()
    }
}
