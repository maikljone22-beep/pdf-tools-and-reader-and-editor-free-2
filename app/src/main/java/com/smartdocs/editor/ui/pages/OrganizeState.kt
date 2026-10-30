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
import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.core.security.SecurityInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Filter chips of the organizer. */
enum class PageFilter { ALL, ANNOTATED, BOOKMARKED }

/** audit 5 G-13: outcome of an awaited organizer operation. */
enum class OpResult { DONE, BUSY, FAILED }

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
    /** audit 5 G-03 / G-02: the document's security as opened (null = not encrypted / unknown). */
    var security by mutableStateOf<SecurityInfo?>(null); private set
    /** audit 5 G-06: progress text of a long operation (OCR), shown instead of the generic "applying". */
    var progress by mutableStateOf<String?>(null); private set

    var selected by mutableStateOf(setOf<Int>())
    var selectMode by mutableStateOf(false)
    var filter by mutableStateOf(PageFilter.ALL)
    var annotatedPages by mutableStateOf(setOf<Int>()); private set
    var bookmarkedPages by mutableStateOf(setOf<Int>()); private set
    /** audit 5 G-11: pages copied with "copy", as page identities (see [OrganizeLogic]); pages deleted since are dropped. */
    var clipboard by mutableStateOf(listOf<Long>()); private set

    /** Identity of every page of the current version (audit 5 G-11). Main thread only. */
    private var ids: List<Long> = emptyList()
    private var nextId = 1L
    private fun freshId(): Long = nextId++
    private fun freshIds(n: Int): List<Long> = List(n) { freshId() }

    /** Thumbnails keyed by "version:index" (what the grid shows; bounded by [thumbCache]). */
    val thumbs = mutableStateMapOf<String, Bitmap>()
    /**
     * audit 5 G-07: LRU bound of [thumbs] in bytes (1/8 of the heap, 16..64 MB). Touched on every request, so the
     * pages on screen stay; an evicted thumbnail leaves [thumbs] and is rendered again when it comes back into view.
     * Used on the main thread only.
     */
    private val thumbCache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(16L shl 20, 64L shl 20).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) { if (newValue == null) thumbs.remove(key) }
    }
    private val sizes = java.util.concurrent.ConcurrentHashMap<Int, Pair<Float, Float>>()
    private val history = ArrayList<File>()
    private val idHistory = ArrayList<List<Long>>()
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
                // audit 5 G-02: the password the reader opened this file with (no second prompt)
                if (password == null) password = com.smartdocs.editor.core.pdf.SessionPasswords.get(file)
                original = file; work = file
                history.clear(); idHistory.clear(); clipboard = emptyList()
                selected = emptySet(); selectMode = false; thumbCache.evictAll(); thumbs.clear(); sizes.clear()
                reopen()
                ids = freshIds(pageCount)
                // audit 5 G-03: the author's restrictions, read by the organizer itself
                val pw = password
                security = withContext(Dispatchers.IO) {
                    if (!com.smartdocs.editor.core.security.SecurityInfoReader.mentionsEncrypt(file)) null
                    else runCatching { PdfOps.loadRaw(file, pw).use { com.smartdocs.editor.core.security.SecurityInfoReader.of(it) } }.getOrNull()
                }
                // audit 5 G-16: the PdfBox parse runs off the main thread
                annotatedPages = withContext(Dispatchers.IO) {
                    runCatching { com.smartdocs.editor.core.pdf.AnnotationWriter.list(file, pw).map { it.page }.toSet() }.getOrDefault(emptySet())
                }
                bookmarkedPages = runCatching { bookmarkedFlow() }.getOrDefault(emptySet())
            } catch (e: PdfSession.Companion.PasswordRequiredException) {
                original = null; work = null; error = "password"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { original = null; work = null; error = message(e) } finally { busy = false }
        }
    }

    /** audit 5 G-03: the author's permissions allow [action] on this document. */
    fun allows(action: DocAction): Boolean = OrganizeLogic.allows(security, action)

    /** audit 5 G-02: Overwrite will replace the author's owner password (the file was opened without owner access). */
    val ownerPasswordWillChange: Boolean get() = OrganizeLogic.ownerPasswordWillChange(security)

    /** A user-facing message for a failed operation (never raw English exception text). */
    fun message(e: Throwable): String = when (e) {
        is com.smartdocs.editor.ui.quick.QuickInfo -> e.message ?: context.getString(com.smartdocs.editor.R.string.ttpg_op_failed)
        is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException, is PdfSession.Companion.PasswordRequiredException ->
            context.getString(com.smartdocs.editor.R.string.password_wrong)
        is com.smartdocs.editor.core.util.PdfPrint.PrintRestricted -> context.getString(com.smartdocs.editor.R.string.np2_restricted_author)
        // the central signed gate (SignedGuard.checkWrite): signed / certified originals are saved as a new file
        is com.smartdocs.editor.core.pdf.SignedDocumentException -> com.smartdocs.editor.ui.common.signedOrMessage(context, e)
        else -> context.getString(com.smartdocs.editor.R.string.ttpg_op_failed)
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
        thumbCache.snapshot().keys.filter { !it.startsWith(prefix) }.forEach { thumbCache.remove(it) }
        thumbs.keys.filter { !it.startsWith(prefix) }.forEach { thumbs.remove(it) }
    }

    /** Page size in points (cached). */
    suspend fun pageSize(index: Int): Pair<Float, Float> {
        sizes[index]?.let { return it }
        val s = session ?: return 595f to 842f
        return runCatching { s.pageSize(index) }.getOrDefault(595f to 842f).also { sizes[index] = it }
    }

    fun thumbKey(index: Int) = "$version:$index"

    /** Render a ~[widthPx] wide thumbnail once (audit 5 G-07: sized to the cell); the result lands in [thumbs]. */
    fun requestThumb(index: Int, widthPx: Int = 300) {
        val key = thumbKey(index)
        // touches the LRU entry; a thumbnail much smaller than the cell (after zooming in) is rendered again
        thumbCache.get(key)?.let { if (it.width >= widthPx * 0.8f) return }
        if (thumbJobs.containsKey(key)) return
        val v = version
        thumbJobs[key] = scope.launch(Dispatchers.IO) {
            try {
                val s = session ?: return@launch
                if (v != version) return@launch
                val (w, h) = pageSize(index)
                val hPx = (widthPx * h / w).toInt().coerceAtLeast(1)
                val bmp = s.render(index, widthPx, hPx)
                if (v == version) withContext(Dispatchers.Main) { if (v == version) { thumbs[key] = bmp; thumbCache.put(key, bmp) } }
            } catch (_: Exception) { } finally { thumbJobs.remove(key) }
        }
    }

    /**
     * Run [op] producing a new version. The previous version is pushed to the undo stack.
     * [remap] gives the page identities of the new version from the old ones and the new page count (audit 5 G-11);
     * null keeps them when the page count is unchanged (rotate, OCR) and starts fresh ones otherwise.
     * [keepSelection] keeps the user's selected pages (by identity) instead of [selectAfter] (audit 5 G-08).
     */
    fun apply(selectAfter: Set<Int> = emptySet(), remap: ((List<Long>, Int) -> List<Long>)? = null, keepSelection: Boolean = false,
              op: suspend (src: File, out: File) -> Unit) {
        if (work == null || busy) return
        busy = true
        scope.launch { runOp(selectAfter, remap, keepSelection, op) }
    }

    /** The body of [apply]; true when the new version is in place. Errors land in [error]. */
    private suspend fun runOp(selectAfter: Set<Int>, remap: ((List<Long>, Int) -> List<Long>)?, keepSelection: Boolean,
                              op: suspend (src: File, out: File) -> Unit): Boolean {
        val src = work ?: run { busy = false; progress = null; return false }
        busy = true; error = null
        try {
            val countBefore = pageCount
            val idsBefore = ids
            val keptSel = if (keepSelection) OrganizeLogic.idsOf(selected, idsBefore) else null
            val out = documents.newTemp("org")
            withContext(Dispatchers.IO) { op(src, out) }
            history += src
            idHistory += idsBefore
            work = out
            reopen()
            ids = remap?.invoke(idsBefore, pageCount)?.takeIf { it.size == pageCount }
                ?: if (pageCount == idsBefore.size) idsBefore else freshIds(pageCount)
            clipboard = clipboard.filter { it in ids }
            // Annotated/bookmarked sets refer to the original numbering; drop them once the structure changed.
            if (pageCount != countBefore) { annotatedPages = emptySet(); bookmarkedPages = emptySet(); if (filter != PageFilter.ALL) filter = PageFilter.ALL }
            selected = if (keptSel != null) OrganizeLogic.indexesOf(keptSel, ids).toSet() else selectAfter.filter { it in 0 until pageCount }.toSet()
            selectMode = selected.isNotEmpty()
            return true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = message(e); return false
        } finally { busy = false; progress = null }
    }

    /** Thumbnail menu: redact the whole page (audit FINAL_CHECK: acts on the tapped page). */
    fun redactPage(p: Int) = apply(keepSelection = true) { src, out -> com.smartdocs.editor.core.edit5.PageOrganize.redactPages(src, out, password, listOf(p)) }

    fun undo() {
        if (history.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                val prev = history.removeAt(history.size - 1)
                val prevIds = idHistory.removeAt(idHistory.size - 1)
                work?.let { if (it != original) it.delete() }
                work = prev
                selected = emptySet()
                reopen()
                ids = prevIds.takeIf { it.size == pageCount } ?: freshIds(pageCount)
                clipboard = clipboard.filter { it in ids }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = message(e) } finally { busy = false }
        }
    }

    // ---- operations -------------------------------------------------------------------------

    fun deleteSelected() = deletePages(selected)

    /** Delete [pages] (audit 5 G-08: the per-page menu deletes its own page and keeps the user's selection). */
    fun deletePages(pages: Set<Int>, keepSelection: Boolean = false) {
        val sel = pages.filter { it in 0 until pageCount }.toSet()
        if (sel.isEmpty() || sel.size >= pageCount) return
        apply(remap = { old, _ -> OrganizeLogic.idsAfterDelete(old, sel) }, keepSelection = keepSelection) { src, out -> PdfOps.deletePages(src, sel, out, password) }
    }

    fun rotateSelected(degrees: Int) = rotatePages(selected, degrees)

    /** Rotate [pages] (audit 5 G-08: the per-page menu rotates its own page and keeps the user's selection). */
    fun rotatePages(pages: Set<Int>, degrees: Int, keepSelection: Boolean = false) {
        val sel = pages.filter { it in 0 until pageCount }.sorted()
        if (sel.isEmpty()) return
        apply(keepSelection = keepSelection) { src, out -> PdfOps.rotatePages(src, sel, degrees, out, password) }
    }

    private fun insertRemap(at: Int): (List<Long>, Int) -> List<Long> = { old, n -> OrganizeLogic.idsAfterInsert(old, at, n - old.size, ::freshId) }

    /** fix/thumbs-panel: one copy of each of [pages] right after it (thumbnail menu; PageOps5 copies inside the document). */
    fun duplicatePages(pages: Collection<Int>) {
        val sel = pages.filter { it in 0 until pageCount }.distinct().sorted()
        if (sel.isEmpty()) return
        apply { src, out -> com.smartdocs.editor.core.edit5.PageOps5.duplicate(src, out, password, sel, 1, false) }
    }

    /**
     * fix/thumbs-panel: replace [pages] pairwise by the first pages of [other] (thumbnail menu). PageOps5.replace
     * re-points bookmarks and links at the new pages; [otherPassword] opens a protected [other].
     */
    fun replacePages(pages: List<Int>, other: File, otherPassword: String?) {
        val sel = pages.filter { it in 0 until pageCount }.distinct().sorted()
        if (sel.isEmpty()) return
        apply(keepSelection = true) { src, out -> com.smartdocs.editor.core.edit5.PageOps5.replace(src, out, password, sel, other, otherPassword, sel.indices.toList()) }
    }

    fun insertBlank(at: Int, style: PdfOps.PageStyle, size: com.tom_roush.pdfbox.pdmodel.common.PDRectangle? = null) {
        apply(remap = insertRemap(at)) { src, out -> PdfOps.insertBlankPage(src, at, out, style, password, size) }
    }

    fun insertFrom(other: File, at: Int) = insertFrom(other, at, null)

    /** share6 (fid): [otherPassword] unlocks a password-protected [other] (a decrypted temp copy is inserted, then deleted). */
    fun insertFrom(other: File, at: Int, otherPassword: String?) {
        apply(remap = insertRemap(at)) { src, out -> insertOp(src, out, other, at, otherPassword) }
    }

    /**
     * audit 5 G-13: insert and wait for the result. [OpResult.BUSY] when another operation is running (nothing is
     * inserted; the caller tells the user), [OpResult.FAILED] when it failed (the message is in [error]).
     */
    suspend fun insertFromAwait(other: File, at: Int, otherPassword: String? = null): OpResult {
        if (work == null) return OpResult.FAILED
        if (busy) return OpResult.BUSY
        val ok = runOp(emptySet(), insertRemap(at), false) { src, out -> insertOp(src, out, other, at, otherPassword) }
        return if (ok) OpResult.DONE else OpResult.FAILED
    }

    private suspend fun insertOp(src: File, out: File, other: File, at: Int, otherPassword: String?) {
        val u = withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.LockedPdf.unlockedForInsert(other, otherPassword, documents.tempDir) }
        try { PdfOps.insertPagesFrom(src, u ?: other, at, out, password) } finally { u?.delete() }
    }

    fun insertImages(images: List<File>, at: Int) {
        if (images.isEmpty()) return
        apply(remap = insertRemap(at)) { src, out ->
            val tmp = documents.newTemp("imgpages")
            PdfOps.imagesToPdf(images, tmp, fitA4 = true)
            PdfOps.insertPagesFrom(src, tmp, at, out, password)
            tmp.delete()
        }
    }

    fun reorder(order: List<Int>) {
        if (order.size != pageCount || order == (0 until pageCount).toList()) return
        apply(remap = { old, _ -> OrganizeLogic.idsAfterReorder(old, order, ::freshId) }) { src, out -> PdfOps.reorder(src, order, out, password) }
    }

    /** Move the single selected page one slot towards the start (-1) or the end (+1). */
    fun moveSelected(delta: Int) {
        val idx = selected.singleOrNull() ?: return
        val target = idx + delta
        if (target !in 0 until pageCount) return
        val order = (0 until pageCount).toMutableList()
        order.removeAt(idx); order.add(target, idx)
        apply(selectAfter = setOf(target), remap = { old, _ -> OrganizeLogic.idsAfterReorder(old, order, ::freshId) }) { src, out -> PdfOps.reorder(src, order, out, password) }
    }

    /** audit 5 G-11: the clipboard keeps the identities of the copied pages, not their indexes. */
    fun copySelected() { clipboard = OrganizeLogic.idsOf(selected, ids) }

    /** Paste clipboard pages after page [after] (or at the end when -1). */
    fun paste(after: Int) {
        val clip = OrganizeLogic.indexesOf(clipboard, ids).filter { it in 0 until pageCount }
        if (clip.isEmpty()) return
        val order = (0 until pageCount).toMutableList()
        val pos = if (after < 0) order.size else (after + 1).coerceIn(0, order.size)
        order.addAll(pos, clip)
        // audit AN-22: in place on the page tree (keeps outline, forms, labels, encryption); pasted pages are copies
        apply(remap = { old, _ -> OrganizeLogic.idsAfterPaste(old, pos, clip.size, ::freshId) }) { src, out -> PdfOps.reorder(src, order, out, password) }
    }

    /**
     * audit 5 G-06: OCR page [page] of the current version in place, as one organizer step (thumbnails and undo stay
     * in sync; no second reader). Uses the OCR sheet's last languages and mode.
     */
    fun ocrPage(page: Int) {
        if (page !in 0 until pageCount || work == null || busy) return
        val langs = com.smartdocs.editor.ui.ocr.OcrPrefs.languages(context)
        val editable = com.smartdocs.editor.ui.ocr.OcrPrefs.editable(context)
        progress = context.getString(com.smartdocs.editor.R.string.ttpg_ocr_running, page + 1)
        apply(keepSelection = true) { src, out ->
            val engine = com.smartdocs.editor.core.ocr.OcrEngine(context.applicationContext)
            try {
                com.smartdocs.editor.ui.ocr.runOcrToFile(context, engine, src, out, password,
                    com.smartdocs.editor.ui.ocr.OcrOptions(listOf(page), langs, editable)) { msg, _ -> progress = msg }
            } finally { withContext(NonCancellable + Dispatchers.IO) { runCatching { engine.close() } } }
        }
    }

    /** audit 5 G-06: the copy of the current version handed to another tool screen, with its stamp at hand-off. */
    private var handed: Triple<File, Long, Long>? = null

    /**
     * audit 5 G-06: a copy of the current version (same file name, own temp folder, password remembered) for a tool
     * screen such as Redact. When that tool replaces the copy ("Save in original"), [takeBack] adopts it as a new
     * organizer version on return, so the grid never shows stale pages; the organizer's own work file is never
     * edited by another screen.
     */
    suspend fun handOff(): File? {
        val w = work ?: return null; val o = original ?: return null
        if (busy) return null
        val f = withContext(Dispatchers.IO) {
            val dir = File(documents.tempDir, "orghand_${System.nanoTime()}").apply { mkdirs() }
            File(dir, o.name).also { w.copyTo(it, overwrite = true) }
        }
        com.smartdocs.editor.core.pdf.SessionPasswords.put(f, password)
        handed = Triple(f, f.lastModified(), f.length())
        return f
    }

    /** audit 5 G-06: on resume. Adopts the handed copy as a new version when the tool changed it, otherwise drops it. */
    fun takeBack() {
        val h = handed ?: return
        if (busy) return                       // tried again on the next resume
        handed = null
        val (f, mod, len) = h
        com.smartdocs.editor.core.pdf.SessionPasswords.forget(f)
        val changed = f.exists() && (f.lastModified() != mod || f.length() != len)
        if (!changed) { scope.launch(Dispatchers.IO) { runCatching { f.parentFile?.deleteRecursively() } }; return }
        apply(keepSelection = true) { _, out ->
            try { f.copyTo(out, overwrite = true) } finally { runCatching { f.parentFile?.deleteRecursively() } }
        }
    }

    /** Extract selected pages into a new file in docsDir (the screen checks COPY first, audit 5 G-03). */
    suspend fun extractSelected(): File? = extractPages(selected.sorted())

    /** fix/thumbs-panel: [pages] into a new file in docsDir (thumbnail menu; the screen checks COPY first). */
    suspend fun extractPages(pages: List<Int>): File? {
        val src = work ?: return null
        val sel = pages.filter { it in 0 until pageCount }.distinct().sorted()
        if (sel.isEmpty()) return null
        val base = (original?.nameWithoutExtension ?: "pages") + "_pages"
        val out = documents.uniqueFile(documents.docsDir, base)
        PdfOps.keepPages(src, sel, out, password)
        return out
    }

    /** Selected pages into a temp file for sharing (the caller checks COPY first, audit 5 G-03). */
    suspend fun selectedToTemp(): File? {
        val src = work ?: return null
        val sel = selected.sorted()
        if (sel.isEmpty()) return null
        val out = documents.newTemp((original?.nameWithoutExtension ?: "pages") + "_pages")
        PdfOps.keepPages(src, sel, out, password)
        return out
    }

    /**
     * audit 5 G-09 / G-03: print [pages] of the current version through the one shared print path
     * (PdfPrint.printDocument: V5 pipeline, PRINT permission checked). No unprotected temp copy of the pages is made.
     */
    suspend fun printPages(pages: Collection<Int>) {
        val src = work ?: return
        val sel = pages.filter { it in 0 until pageCount }
        if (sel.isEmpty()) return
        com.smartdocs.editor.core.util.PdfPrint.printDocument(context, src, password, OrganizeLogic.rangeSpec(sel),
            original?.nameWithoutExtension ?: "pages", documents.tempDir)
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
            // audit 5 E-16: like every other "save in original", a copy of the old original goes to the trash first
            try { documents.replaceOriginal(o, w, password) } catch (e: Exception) { reopen(); throw e }
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
        history.clear(); idHistory.clear()
    }

    /** audit 5 G-12: "Select all" toggles on the visible (filtered) pages. */
    fun toggleSelectAll() {
        selected = OrganizeLogic.selectAllToggle(selected, visiblePages())
        selectMode = selected.isNotEmpty()
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
        // audit 5 G-16: never block the main thread on a render that holds the session lock
        val s = session; session = null
        val old = history.filter { it != original }
        val h = handed?.first; handed = null
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            runCatching { s?.closeAsync() }
            // temp versions are in cacheDir; leave the last work file in case a ResultSheet still points at it
            old.forEach { runCatching { it.delete() } }
            h?.let { com.smartdocs.editor.core.pdf.SessionPasswords.forget(it); runCatching { it.parentFile?.deleteRecursively() } }
        }
    }

    companion object {
        /** Helper to collect the bookmark pages for a document uri once. */
        suspend fun bookmarks(dao: com.smartdocs.editor.core.data.BookmarkDao, uri: String): Set<Int> =
            dao.forDoc(uri).first().map { it.page }.toSet()
    }
}
