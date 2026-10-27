package com.smartdocs.editor.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.data.AppSettings
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.SearchHit
import com.smartdocs.editor.core.history.DocHistory
import com.smartdocs.editor.core.history.HistoryNames
import com.smartdocs.editor.core.history.HistoryStep
import com.smartdocs.editor.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class ReaderTab { VIEW, COMMENT, EDIT, FILL_SIGN, SIGN, TOOLS, PAGES, CUSTOM }

/** COVER = "cover facing": page 1 alone, then pairs (2-3, 4-5…). Pager mapping: ui/reader/view/PageSpread. */
enum class ViewMode { SINGLE, CONTINUOUS, FACING, COVER }

/** Every interactive tool the page canvas understands. */
enum class ReaderTool {
    HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY, NOTE, FREETEXT, INK, ERASER, LINE, ARROW, RECT, OVAL, STAMP, MEASURE,
    TEXTBOX, IMAGE, LINK,
    FS_TEXT, FS_CHECK, FS_CROSS, FS_DOT, FS_LINE, FS_DATE, FS_PROFILE,
    SIGNATURE,
    // MODULE_annot2 (ui/reader/annot)
    CARET, REPLACE, POLYGON, POLYLINE, CLOUD, ATTACH, SOUND, VIDEO, SNAPSHOT, SELECT,
}

/** A text selection on one page. Rects are in PDF space. */
data class TextSelection(val page: Int, val start: Int, val end: Int, val rects: List<RectF>, val text: String)

/** An image being placed on the page with drag / resize / rotate handles (signature, stamp, picture). */
data class Placement(val page: Int, val rect: RectF, val image: Bitmap, val rotation: Float = 0f)

/** A measurement in progress or finished: both points in PDF space of one page. */
data class Measure(val page: Int, val a: Pair<Float, Float>, val b: Pair<Float, Float>?)

/** Last unified undo / redo, shown by the screen as a snackbar (undone: label, with redo). */
data class HistoryEvent(val undo: Boolean, val label: String, val error: String? = null, val nonce: Long = System.nanoTime())

val PALETTE = listOf(
    0xFFEB3B, 0xFF9800, 0xF44336, 0xE91E63, 0x9C27B0, 0x3F51B5,
    0x2196F3, 0x00BCD4, 0x4CAF50, 0x8BC34A, 0x795548, 0x212121,
)

/**
 * State holder of the reader screen: the PDF session, pending annotations (undo/redo), tools and view options.
 * Created with `remember` and bound to the composable's coroutine scope.
 */
class ReaderState(
    val app: SmartDocsApp,
    val context: Context,
    val scope: CoroutineScope,
    val uriString: String,
    val startPage: Int,
    initial: AppSettings,
) {
    // ---- document ----
    var file by mutableStateOf<File?>(null); private set
    var session by mutableStateOf<PdfSession?>(null); private set
    var password: String? = null; internal set
    var needPassword by mutableStateOf(false)
    var wrongPassword by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    // share6: connected / enterprise-protected documents
    var connected by mutableStateOf<com.smartdocs.editor.core.connected.ConnectedStatus?>(null)
    var connectedLocked by mutableStateOf(false)
    var enterpriseInfo by mutableStateOf<com.smartdocs.editor.core.connected.EnterpriseProtection.Info?>(null)
    var pageCount by mutableIntStateOf(0); private set
    val pageSizes = mutableStateMapOf<Int, Pair<Float, Float>>()
    /** /Rotate + crop-box origin of the pages that have one (audit AN-06); absent = 0° at (0, 0). */
    val pageFrames = mutableStateMapOf<Int, PageUserFrame>()
    var initialPage by mutableIntStateOf(0); private set
    var currentPage by mutableIntStateOf(0)
    var renderVersion by mutableIntStateOf(0); private set
    val fileUri: String get() = file?.let { Uri.fromFile(it).toString() } ?: uriString

    // ---- view options ----
    var viewMode by mutableStateOf(if (initial.continuousScroll) ViewMode.CONTINUOUS else ViewMode.SINGLE)
    var fitPage by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
    var nightMode by mutableStateOf(initial.nightMode)
    var pageColor by mutableLongStateOf(initial.pageColor)
    var rotation by mutableIntStateOf(0)
    var reflow by mutableStateOf(false)
    var reflowFontSize by mutableFloatStateOf(18f)
    var autoFlipSeconds by mutableIntStateOf(0)
    var pendingJump by mutableStateOf<Int?>(null)
    /** Crop, magnifier, auto-flip interval, orientation lock, screen timeout, reflow scale (ui/reader/view). */
    val vx = com.smartdocs.editor.ui.reader.view.ViewExtras(this)

    // ---- tabs / tools ----
    var tab by mutableStateOf(ReaderTab.VIEW)
    var tool by mutableStateOf<ReaderTool?>(null)
    var color by mutableIntStateOf(0xFFEB3B)
    var opacity by mutableFloatStateOf(1f)
    var thickness by mutableFloatStateOf(3f)
    var fontSize by mutableFloatStateOf(14f)
    var continueCreating by mutableStateOf(true)
    var pendingText by mutableStateOf("")
    var pendingImage by mutableStateOf<Bitmap?>(null)
    var placement by mutableStateOf<Placement?>(null)
    var measure by mutableStateOf<Measure?>(null)
    var selection by mutableStateOf<TextSelection?>(null)
    var authorName = initial.authorName

    // ---- annotations ----
    val annots: SnapshotStateList<Annot> = mutableStateListOf()
    /**
     * Unified multi-level undo / redo of this document (core/history): file snapshots of every change written
     * into the file plus in-memory steps (pending annotations, form values), in the order they were made.
     */
    val history: DocHistory = DocHistory.open(app.documents.tempDir, uriString).also { h ->
        h.current = { file?.let { it to password } }
        h.restorer = { src, pw -> restoreFrom(src, pw) }
    }
    /** Last undo / redo (the screen shows it and clears it). */
    var historyEvent by mutableStateOf<HistoryEvent?>(null)
    /** Selection / editing of existing annotations on the page (see AnnotEdit.kt). */
    val edit = AnnotEditor(this)
    /** Polygon/cloud/caret/media/snapshot tools, multi-select, comment threads & exchange (MODULE_annot2). */
    val annot2 = com.smartdocs.editor.ui.reader.annot.Annot2Host(this)
    /** In-page video player (one video at a time; see ui/reader/annot/VideoHost). */
    val video = com.smartdocs.editor.ui.reader.annot.VideoHost(this)
    val canUndo: Boolean get() = history.canUndo && !saving
    val canRedo: Boolean get() = history.canRedo && !saving
    var dirty by mutableStateOf(false); private set
    var existing by mutableStateOf<List<AnnotationWriter.Existing>>(emptyList()); private set
    var saving by mutableStateOf(false); private set
    /** In-page form filling (MODULE_forms5): pending field values, live calculations, field bar. */
    val forms5 = com.smartdocs.editor.ui.forms5.FormFill.of(this).also { it.markDirty = { dirty = true } }
    /** Result of copying the saved file back into the document it was opened from (watched by the screen). */
    var writeBackResult by mutableStateOf<com.smartdocs.editor.core.files.DocumentRepository.WriteBack?>(null)
    /** lastModified/length of the file when the current session was opened; a tool replacing the file changes it. */
    private var openedStamp: Pair<Long, Long> = 0L to 0L

    // ---- search ----
    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList())
    var searchIndex by mutableIntStateOf(-1)
    var matchCase by mutableStateOf(false)
    var wholeWords by mutableStateOf(false)
    var searching by mutableStateOf(false)
    private var searchJob: Job? = null

    // ---- caches ----
    private val textCache = HashMap<Int, String>()
    private val boxCache = HashMap<Int, List<RectF>>()
    private val cacheMutex = Mutex()

    // ================= document lifecycle =================

    /** A save into a signed / certified document was refused (audit AN-28 hook): the screen offers "Save a copy". */
    var signedBlock by mutableStateOf<com.smartdocs.editor.core.pdf.SignedDocumentException.Reason?>(null)

    /** Why the document could not be opened; stays until [retryOpen] (the snackbar clears [error], not this). */
    var openError by mutableStateOf<OpenError?>(null)

    /** Error screen "Retry": import / open again from scratch. */
    fun retryOpen() {
        if (session != null) return
        openError = null; error = null
        if (file?.exists() == false) file = null
        open(null)
    }

    fun open(pw: String? = null) {
        scope.launch {
            try {
                if (session != null) return@launch
                val f = file ?: app.documents.localFile(Uri.parse(uriString)).also { file = it }
                // share6: rights-managed files -> explanation screen; connected documents -> status / protected key
                withContext(Dispatchers.IO) { com.smartdocs.editor.core.connected.EnterpriseProtection.detect(f) }?.takeIf { it.blocking }?.let { enterpriseInfo = it; return@launch }
                var openPw = pw
                // audit RD-03: the connected-document marker scan can read the whole file, so it no longer runs before
                // the first page. Only a password-protected file can need it up front (its key comes from the server).
                var markChecked = false
                val s = try { PdfSession.open(context, f, openPw) } catch (e: PdfSession.Companion.PasswordRequiredException) {
                    if (pw != null) throw e
                    markChecked = true
                    val mark = withContext(Dispatchers.IO) { com.smartdocs.editor.core.connected.ConnectedDocs.markOf(f) }
                    if (mark?.protected != true) throw e
                    val st = com.smartdocs.editor.core.connected.ConnectedDocs.checkOnOpen(context, f)
                    connected = st
                    if (st == null || st.locked) { connectedLocked = true; return@launch }
                    openPw = st.password
                    PdfSession.open(context, f, openPw)
                }
                if (disposed) { runCatching { s.closeAsync() }; return@launch }
                password = openPw
                pageCount = s.pageCount
                val recent = app.db.docs().get(Uri.fromFile(f).toString())
                initialPage = (if (startPage >= 0) startPage else recent?.lastPage ?: 0).coerceIn(0, (s.pageCount - 1).coerceAtLeast(0))
                currentPage = initialPage
                // stable layout: sizes around the start page before the first frame, the rest in the background
                val near = (initialPage - 15).coerceAtLeast(0)..(initialPage + 15).coerceAtMost(s.pageCount - 1)
                runCatching { s.pageSizes(near) }.getOrNull()?.forEach { (i, sz) -> pageSizes[i] = sz }
                openedStamp = f.lastModified() to f.length()
                session = s
                scope.launch { runCatching { s.pageSizes() }.getOrNull()?.let { all -> androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { all.forEach { (i, sz) -> pageSizes[i] = sz } } } }
                needPassword = false; wrongPassword = false; error = null
                if (!markChecked) scope.launch {
                    // after the first render: connected status for the banner; a revoked protected copy still locks
                    val mark = withContext(Dispatchers.IO) { com.smartdocs.editor.core.connected.ConnectedDocs.markOf(f) } ?: return@launch
                    val st = com.smartdocs.editor.core.connected.ConnectedDocs.checkOnOpen(context, f)
                    connected = st
                    if (mark.protected && st?.locked == true) { runCatching { session?.closeAsync() }; session = null; connectedLocked = true }
                }
                app.documents.touchRecent(f, s.pageCount, initialPage)
                app.settings.update { it.copy(lastOpenedUri = Uri.fromFile(f).toString()) }
                loadExisting()
            } catch (e: PdfSession.Companion.PasswordRequiredException) {
                needPassword = true; wrongPassword = pw != null
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                // audit RD-01: a lasting, localized error screen (Retry / Open with / Back), not a snackbar + spinner
                openError = OpenError.of(e, file?.exists())
                android.util.Log.w("ReaderState", "open failed", e)
            }
        }
    }

    suspend fun pageSize(i: Int): Pair<Float, Float> {
        pageSizes[i]?.let { return it }
        val s = session ?: return 595f to 842f
        if (s.isClosed || i !in 0 until s.pageCount) return 595f to 842f
        val sz = runCatching { s.pageSize(i) }.getOrNull()?.takeIf { it.first > 1f && it.second > 1f } ?: return 595f to 842f
        pageSizes[i] = sz
        return sz
    }

    suspend fun pageText(i: Int): String = cacheMutex.withLock { textCache[i] } ?: run {
        val t = runCatching { session?.text(i) ?: "" }.getOrDefault("")
        cacheMutex.withLock { textCache[i] = t }; t
    }

    suspend fun charBoxes(i: Int): List<RectF> = cacheMutex.withLock { boxCache[i] } ?: run {
        val b = runCatching { session?.charBoxes(i) ?: emptyList() }.getOrDefault(emptyList())
        cacheMutex.withLock { boxCache[i] = b }; b
    }

    fun rememberPage(page: Int) {
        currentPage = page
        val f = file ?: return
        scope.launch { runCatching { app.db.docs().updatePage(Uri.fromFile(f).toString(), page) } }
    }

    fun jumpTo(page: Int) { pendingJump = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) }

    /** Independent of the composable scope so closing still happens after the screen is disposed. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var disposed = false
    internal val saveMutex = Mutex()

    /** The user chose "discard" for the pending edits: [close] must not save them. */
    @Volatile var discarded = false
        private set

    fun discardChanges() { discarded = true; edit.discardOnClose(); forms5.discard() }

    /**
     * The document is closed for good (its back-stack entry was removed, see [ReaderHolder]). Pending edits the user
     * did not discard are saved first (audit AN-01 / DI-10): no way out of the reader drops them silently.
     */
    fun close() {
        disposed = true
        runCatching { video.release() }      // player + extracted video copies
        val saveFirst = dirty && !discarded
        val s = session
        if (!saveFirst) session = null
        val restore = edit.restoreOnClose()
        // side-car states keyed by this reader hold it strongly: drop them (audit ARCH-03)
        com.smartdocs.editor.ui.forms5.FormFill.forget(this)
        com.smartdocs.editor.ui.view5.V5State.forget(this)
        com.smartdocs.editor.ui.pageflip.PageFlipController.forget(this)
        // PdfSession.closeAsync waits for in-flight renders, so no delay is needed
        ioScope.launch {
            if (saveFirst) withContext(NonCancellable) { runCatching { save() } }
            runCatching { session?.closeAsync() }; session = null
            runCatching { s?.closeAsync() }
            // discarded edits: put back the file as it was before stored annotations were taken out for editing
            restore?.let { (backup, target) -> runCatching { if (!backup.renameTo(target)) { backup.copyTo(target, overwrite = true); backup.delete() } } }
            history.dispose()      // the document is closed for good: its history folder goes too
        }
    }

    private fun loadPageFrames() {
        val f = file ?: return; val pw = password
        scope.launch {
            val frames = withContext(Dispatchers.IO) { runCatching { PageUserFrame.read(f, pw) }.getOrNull() } ?: return@launch
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { pageFrames.clear(); pageFrames.putAll(frames) }
        }
    }

    private fun loadExisting() {
        loadPageFrames()
        val f = file ?: return
        scope.launch { existing = runCatching { AnnotationWriter.list(f, password) }.getOrDefault(emptyList()) }
    }

    // ================= annotations / undo =================

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** Before / after lists of one pending-annotation step (mutable: [adoptConverted] adds to every state). */
    private class AnnotSnap(var before: List<Annot>, var after: List<Annot>)

    /** Record a pending-annotation change (already applied to [annots]) as one unified undo step. */
    private fun annotStep(labelId: Int, page: Int, before: List<Annot>) {
        val snap = AnnotSnap(before, annots.toList())
        history.pushMemory(str(labelId), page, OWNER_ANNOTS, snap, undo = { setAnnots(snap.before) }, redo = { setAnnots(snap.after) })
    }

    private fun setAnnots(list: List<Annot>) {
        annots.clear(); annots += list; dirty = true
        edit.validate()
    }

    fun addAnnot(a: Annot) {
        val before = annots.toList()
        annots += a; dirty = true
        annotStep(R.string.hi_add_annot, a.page, before)
        if (!continueCreating && tool != ReaderTool.INK) tool = null
    }

    /** Several pending-annotation changes as one undo step (multi-select, handwriting to text). */
    fun batchAnnots(change: (MutableList<Annot>) -> Unit) {
        val before = annots.toList()
        val copy = before.toMutableList(); change(copy)
        if (copy == before) return
        annots.clear(); annots += copy; dirty = true
        annotStep(R.string.hi_batch_annot, copy.firstOrNull { it !in before }?.page ?: before.firstOrNull { it !in copy }?.page ?: currentPage, before)
        edit.validate()
    }

    fun removeAnnot(id: Long) {
        val a = annots.firstOrNull { it.id == id } ?: return
        val before = annots.toList()
        annots.removeAll { it.id == id }; dirty = true
        annotStep(R.string.hi_del_annot, a.page, before)
    }

    fun replaceAnnot(a: Annot) {
        val i = annots.indexOfFirst { it.id == a.id }; if (i < 0) return
        val before = annots.toList()
        annots[i] = a; dirty = true
        annotStep(R.string.hi_edit_annot, a.page, before)
    }

    /** Unified undo of the newest step (annotation, form value or file change). */
    fun undo() { scope.launch { runHistory(true, listOfNotNull(history.peekUndo())) { history.undo() } } }

    /** Unified redo. */
    fun redo() { scope.launch { runHistory(false, listOfNotNull(history.peekRedo())) { history.redo() } } }

    /** History sheet: undo back to before step [id]. */
    fun undoTo(id: Long) { scope.launch { runHistory(true, history.undoStepsTo(id)) { history.undoTo(id) } } }

    /** History sheet: redo up to step [id]. */
    fun redoTo(id: Long) { scope.launch { runHistory(false, history.redoStepsTo(id)) { history.redoTo(id) } } }

    /**
     * Audit AN-04: while stored annotations are taken out of the file for editing (not saved yet), restoring another
     * file version would bring the original annotation back next to its pending copy (a duplicate after save), and
     * the save's "before" snapshot would no longer match. File steps wait until the next save.
     */
    val fileUndoBlocked: Boolean get() = edit.hasBackup

    /**
     * Undo through the file step that saved [before] (done bars that kept the returned copy). False: no longer in the
     * history, or newer changes were made since (audit AN-08: they are not undone silently; see [DocHistory.newerStepsThan]).
     */
    fun undoThrough(before: File): Boolean {
        if (!history.canUndoThrough(before) || history.newerStepsThan(before) > 0 || fileUndoBlocked) return false
        scope.launch { runHistory(true, emptyList()) { history.undoThrough(before) } }
        return true
    }

    private suspend fun runHistory(undo: Boolean, steps: List<HistoryStep>, block: suspend () -> HistoryStep?) {
        if (saving) return
        if (com.smartdocs.editor.core.history.HistoryGuards.blocksFileSteps(steps, fileUndoBlocked)) {
            historyEvent = HistoryEvent(undo, "", str(R.string.fx_undo_save_first)); return
        }
        try {
            val st = block() ?: return
            historyEvent = HistoryEvent(undo, st.label)
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) {
            historyEvent = HistoryEvent(undo, "", e.message ?: e.toString())
        }
    }

    /**
     * A stored annotation was taken out of the file to be edited: it becomes pending. Every undo/redo state
     * gets it too, so undo can revert its edits but never makes it vanish (the file no longer holds it).
     */
    internal fun adoptConverted(a: Annot) {
        annots += a
        history.steps().forEach { st -> ((st as? HistoryStep.Memory)?.tag as? AnnotSnap)?.let { it.before = it.before + a; it.after = it.after + a } }
        dirty = true
    }

    /** Build a new annotation with the current property bar values. */
    fun newAnnot(kind: AnnotKind, page: Int, rects: List<RectF> = emptyList(), paths: List<List<Pair<Float, Float>>> = emptyList(),
                 points: List<Pair<Float, Float>> = emptyList(), text: String = "", image: Bitmap? = null, fontSize: Float = this.fontSize,
                 color: Int = this.color): Annot =
        Annot(kind = kind, page = page, color = color, opacity = opacity, strokeWidth = thickness, rects = rects, paths = paths,
            points = points, text = text, fontSize = fontSize, author = authorName, image = image)

    // ================= save =================

    /**
     * Write pending annotations into the file itself (write temp → close session → replace → reopen).
     * The critical section is non-cancellable so a save started from ON_PAUSE finishes even when the
     * screen is disposed meanwhile; concurrent calls (back button + autosave) are serialised.
     */
    suspend fun save(): Boolean {
        saveMutex.withLock {
            val f = file ?: return false
            // history: the version before this save (or before stored annotations were taken out for editing);
            // the pending steps written now become one file step (saving itself is not an undo step)
            val prevPw = password
            val willWrite = forms5.hasPending || annots.isNotEmpty() || edit.hasBackup
            val before = if (willWrite) runCatching { history.copyIn(edit.backupFile?.takeIf { it.exists() } ?: f) }.getOrNull() else null
            var wrote = false
            try {
                // MODULE_forms5: pending form values are written first (FormOps.fillFields -> swapFile)
                val formsSaved = try { withContext(NonCancellable) { forms5.flushOnSave(f, password) { tmp -> swapFile(tmp, record = false) } } }
                    catch (e: Exception) { error = e.message ?: e.toString(); return false }
                wrote = formsSaved
                if (annots.isEmpty()) {
                    // stored annotations may have been taken out for editing and then deleted: the file already reflects it
                    if (edit.hasBackup || formsSaved) { wrote = true; writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }; if (edit.hasBackup) edit.committed() }
                    dirty = false; return true
                }
                saving = true
                try {
                    val pending = annots.toList()
                    var failed: List<Annot> = emptyList()
                    withContext(NonCancellable) {
                        val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.saving.pdf")
                        failed = AnnotationWriter.commit(context, f, pending, tmp, password).failed
                        com.smartdocs.editor.core.collab.CollabLive.beforeSave(context, f, tmp, password)   // share6: merge other reviewers' comments
                        swapFile(tmp, record = false)
                        wrote = true
                        // the edit belongs in the user's document, not only in our working copy
                        writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }
                    }
                    // only what was written leaves the pending list: annotations that failed stay (audit DI-05) and
                    // ones added while the save ran are kept too (audit DI-36)
                    val keep = stillPendingAfterSave(annots.toList(), pending, failed)
                    annots.retainAll { a -> keep.any { it.id == a.id } }
                    dirty = annots.isNotEmpty() || forms5.hasPending
                    edit.committed()
                    if (failed.isNotEmpty()) { error = str(R.string.fx_annots_not_saved, failed.size); return false }
                    return true
                } catch (e: com.smartdocs.editor.core.pdf.SignedDocumentException) {
                    // signed / certified file: the pending annotations stay pending; the screen offers "Save a copy"
                    signedBlock = e.reason; return false
                } catch (e: Exception) { error = e.message ?: e.toString(); return false } finally { saving = false }
            } finally {
                if (wrote && before != null) recordSaved(before, prevPw) else before?.let { runCatching { it.delete() } }
                // audit AN-05: written without a snapshot (copy failed, e.g. storage full) -> the memory steps describe
                // annotations / values that are now in the file; replaying them would duplicate them
                if (wrote && before == null) { history.removeOwner(OWNER_ANNOTS); history.removeOwner(com.smartdocs.editor.ui.forms5.OWNER_FORMS) }
            }
        }
    }

    /** The pending steps just written into the file are replaced by one file step holding the version before. */
    private fun recordSaved(before: File, pw: String?) {
        val label = HistoryNames.groupLabel(history.memoryLabels(), str(R.string.hi_saved)) { l, n -> str(R.string.hi_saved_group, l, n) }
        history.replaceMemory(HistoryStep.FileSnapshot(label, before, pw, currentPage))
    }

    /** Autosave from ON_PAUSE: runs in a scope that outlives the composable so leaving the screen cannot cut it short. */
    fun saveInBackground() { if (dirty && !saving) ioScope.launch { runCatching { save() } } }

    /** Write pending annotations plus the current file into [out] (used by "save as"). */
    suspend fun exportWithAnnots(out: File) {
        val f0 = file ?: return
        val f = forms5.exportSource(f0, password)          // MODULE_forms5: temp copy with pending field values
        try {
            if (annots.isEmpty()) withContext(Dispatchers.IO) { f.copyTo(out, overwrite = true) }
            else AnnotationWriter.commit(context, f, annots.toList(), out, password, fullSave = true)   // a copy: never blocked by signatures
        } finally { if (f != f0) f.delete() }
    }

    /**
     * Eraser on saved ink (audit AN-13): every stored Ink annotation on [page] touched by the eraser path [pts]
     * (PDF space) is removed from the file in one write, which is one undo step.
     */
    fun eraseStoredInk(page: Int, pts: List<Pair<Float, Float>>, radius: Float) {
        if (pts.isEmpty() || saving) return
        val candidates = existing.filter { it.page == page && it.subtype == "Ink" && EraseLogic.nearRect(it.rect.left, it.rect.bottom, it.rect.right, it.rect.top, pts, radius + 4f) }
        if (candidates.isEmpty()) return
        scope.launch {
            saveMutex.withLock {
                val f = file ?: return@withLock
                saving = true
                try {
                    withContext(NonCancellable) {
                        val hit = withContext(Dispatchers.IO) {
                            candidates.filter { e -> AnnotationWriter.extract(f, e.page, e.index, password)?.let { a -> EraseLogic.touches(a.paths, a.strokeWidth, pts, radius) } == true }.map { it.index }
                        }
                        if (hit.isEmpty()) return@withContext
                        val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.saving.pdf")
                        com.smartdocs.editor.core.annot.StoredOps.removeOnly(f, tmp, password, page, hit)
                        swapFile(tmp, str(R.string.fx_erase_stored))
                        writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }
                    }
                } catch (ex: Exception) { error = ex.message ?: ex.toString() } finally { saving = false }
            }
        }
    }

    suspend fun deleteExisting(e: AnnotationWriter.Existing) {
        saveMutex.withLock {
            val f = file ?: return
            saving = true
            try {
                withContext(NonCancellable) {
                    val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.saving.pdf")
                    AnnotationWriter.delete(f, e.page, e.index, tmp, password)
                    swapFile(tmp, str(R.string.hi_del_stored))
                    writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }
                }
                if ((edit.target as? AnnotTarget.Stored)?.e == e) edit.clear()
            } catch (ex: Exception) { error = ex.message ?: ex.toString() } finally { saving = false }
        }
    }

    /**
     * Replace the document file by [tmp] and reopen. Order matters: the PDFium session on [file] is closed
     * first (waiting for in-flight renders), then the file is replaced, then a fresh session is opened.
     * Nothing is ever written into a file a session still holds.
     *
     * Unified history: unless [record] is false (save consolidation, undo / redo restores, taking stored
     * annotations out for editing), a byte copy of the previous version (opened with [prevPassword]) is kept as
     * one undo step labelled [label]. Returns that copy (owned by the history: never delete it) or null.
     * With [requireSnapshot] a failed copy aborts the change; otherwise the change goes on without an undo step.
     */
    internal suspend fun swapFile(tmp: File, label: String? = null, prevPassword: String? = password, record: Boolean = true, requireSnapshot: Boolean = false): File? {
        val f = file ?: return null
        val snap = if (!record) null else if (requireSnapshot) history.copyIn(f) else runCatching { history.copyIn(f) }.getOrNull()
        try {
            // The closed session stays referenced (not nulled) so the page view keeps its scroll position;
            // renders issued meanwhile fail fast (session.isClosed) and the canvas keeps its last bitmap.
            runCatching { session?.closeAsync() }    // waits for the native lock, never frees PDFium mid-render
            withContext(Dispatchers.IO) {
                if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            }
        } catch (e: Throwable) { snap?.delete(); throw e }
        snap?.let { history.push(HistoryStep.FileSnapshot(label ?: str(R.string.hi_doc_change), it, prevPassword, currentPage)) }
        reopen()
        return snap
    }

    /** Open a fresh session on [file] (after it was replaced) keeping scroll position and page sizes when possible. */
    private suspend fun reopen() {
        val f = file ?: return
        runCatching { session?.takeIf { !it.isClosed }?.closeAsync() }
        if (disposed) return                 // screen already left: do not leak a fresh native document
        val fresh = PdfSession.open(context, f, password)
        // keep the known sizes (an annotation save never changes them); re-measure when the page count changed
        // or a probe page changed size (rotate / page tools replacing the file)
        val probe = listOf(0, currentPage, currentPage + 1).filter { it in 0 until fresh.pageCount }.distinct()
        val resized = fresh.pageCount == pageCount && probe.any { i ->
            val old = pageSizes[i] ?: return@any false
            val now = runCatching { fresh.pageSize(i) }.getOrNull() ?: return@any false
            kotlin.math.abs(old.first - now.first) > 0.5f || kotlin.math.abs(old.second - now.second) > 0.5f
        }
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            if (fresh.pageCount != pageCount || resized) pageSizes.clear()
            pageCount = fresh.pageCount
            if (currentPage >= fresh.pageCount) currentPage = (fresh.pageCount - 1).coerceAtLeast(0)
        }
        if (pageSizes.isEmpty()) {
            val near = (currentPage - 15).coerceAtLeast(0)..(currentPage + 15).coerceAtMost(fresh.pageCount - 1)
            runCatching { fresh.pageSizes(near) }.getOrNull()?.forEach { (i, sz) -> pageSizes[i] = sz }
            scope.launch { runCatching { fresh.pageSizes() }.getOrNull()?.let { all -> androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { all.forEach { (i, sz) -> pageSizes[i] = sz } } } }
        }
        cacheMutex.withLock { textCache.clear(); boxCache.clear() }
        if (disposed) { runCatching { fresh.closeAsync() }; return }
        openedStamp = f.lastModified() to f.length()
        session = fresh
        renderVersion++
        loadExisting()
        runCatching { app.documents.touchRecent(f, fresh.pageCount, currentPage) }
    }

    /**
     * Run a document-level operation on the open document and replace it in place (one-step tools).
     * Pending annotations are saved first; [op] writes the result from `src` into `out` (never into `src`);
     * then the session is closed, the file atomically replaced, reopened at the same page and written back
     * to its source. The previous version becomes one step of the unified [history] labelled [label] (evaluated
     * once the operation succeeded). Returns that snapshot (owned by the history: do not delete it; undo it with
     * [undoThrough]) or null when nothing was done.
     * [newPassword] is the password the result opens with (protect / remove password change it).
     */
    suspend fun applyInPlace(newPassword: String? = password, label: (() -> String)? = null, op: suspend (src: File, out: File, pw: String?) -> Unit): File? {
        if (dirty && !save()) return null
        selection = null
        return saveMutex.withLock {
            val f = file ?: return@withLock null
            val out = File(f.parentFile, ".${f.nameWithoutExtension}.quick.pdf")
            val prevPw = password
            try {
                withContext(Dispatchers.IO) { out.delete() }
                op(f, out, password)
                if (!out.exists() || out.length() == 0L) throw IllegalStateException("empty result")
                withContext(NonCancellable) {
                    val lbl = runCatching { label?.invoke() }.getOrNull()?.takeIf { it.isNotBlank() } ?: str(R.string.hi_doc_change)
                    password = newPassword
                    val snap = try { swapFile(out, lbl, prevPw, requireSnapshot = true) } catch (e: Throwable) { password = prevPw; throw e }
                    writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }
                    snap
                }
            } catch (e: Throwable) {
                out.delete(); throw e
            }
        }
    }

    /** Put a previous version (from [applyInPlace]) back in place: used by "undo" and "save as copy". */
    suspend fun restoreFrom(backup: File, pw: String?) {
        saveMutex.withLock {
            withContext(NonCancellable) {
                val f = file ?: return@withContext
                val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.restore.pdf")
                withContext(Dispatchers.IO) { backup.copyTo(tmp, overwrite = true) }
                password = pw
                swapFile(tmp, record = false)        // undo / redo / restore: never a new history step
                writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }
            }
        }
    }

    /** Version kept before a full-screen tool opened on this document (label, snapshot, password). */
    private var externalBase: Triple<String, File, String?>? = null

    /**
     * A full-screen tool (organize, edit5 / tools screens) is about to open on this document: keep the current
     * version so a change it saves into the same file becomes one undo step when the reader resumes.
     */
    suspend fun beforeExternalTool(label: String) {
        val f = file ?: return
        externalBase?.second?.delete()
        externalBase = runCatching { Triple(label, history.copyIn(f), password) }.getOrNull()
    }

    /** Called when the screen resumes: a tool may have replaced the document ("save in the same file"). */
    fun reloadIfChanged() {
        val f = file ?: return
        if (session == null || saving || !f.exists()) return
        if ((f.lastModified() to f.length()) == openedStamp) { externalBase?.second?.delete(); externalBase = null; return }
        val base = externalBase; externalBase = null
        scope.launch {
            saveMutex.withLock { withContext(NonCancellable) { runCatching { reopen() }.onFailure { error = it.message } } }
            base?.let { (label, snap, pw) -> history.push(HistoryStep.FileSnapshot(label, snap, pw, currentPage)) }
        }
    }

    // ================= search =================

    fun runSearch(query: String) {
        searchQuery = query
        searchJob?.cancel()
        if (query.isBlank()) { searchHits = emptyList(); searchIndex = -1; return }
        val s = session ?: return
        searchJob = scope.launch {
            searching = true
            delay(250)
            val norm = com.smartdocs.editor.core.search.SearchPrefs.load(context, matchCase)
            val hits = runCatching {
                s.searchWith { com.smartdocs.editor.core.search.TextMatcher.find(it, query, norm, wholeWords) }
            }.getOrElse { runCatching { s.search(query, matchCase, wholeWords) }.getOrDefault(emptyList()) }
            searchHits = hits
            searchIndex = if (hits.isEmpty()) -1 else {
                val near = hits.indexOfFirst { it.page >= currentPage }.let { if (it < 0) 0 else it }
                near
            }
            searchHits.getOrNull(searchIndex)?.let { jumpTo(it.page) }
            searching = false
        }
    }

    fun searchStep(delta: Int) {
        if (searchHits.isEmpty()) return
        searchIndex = ((searchIndex + delta) % searchHits.size + searchHits.size) % searchHits.size
        jumpTo(searchHits[searchIndex].page)
    }

    fun closeSearch() { searchOpen = false; searchHits = emptyList(); searchIndex = -1; searchQuery = "" }

    // ================= tools =================

    fun selectTool(t: ReaderTool?) {
        tool = if (tool == t) null else t
        selection = null; measure = null; edit.clear()
        if (tool != ReaderTool.SIGNATURE && tool != ReaderTool.STAMP && tool != ReaderTool.IMAGE) { placement = null; pendingImage = null }
        if (tool != ReaderTool.FS_PROFILE && tool != ReaderTool.FS_DATE) pendingText = ""
        // sensible default colours per tool
        when (tool) {
            ReaderTool.HIGHLIGHT -> if (color == 0x212121) color = 0xFFEB3B
            ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY, ReaderTool.INK, ReaderTool.LINE, ReaderTool.ARROW,
            ReaderTool.RECT, ReaderTool.OVAL -> if (color == 0xFFEB3B) color = 0xF44336
            ReaderTool.FREETEXT, ReaderTool.TEXTBOX, ReaderTool.FS_TEXT, ReaderTool.FS_PROFILE, ReaderTool.FS_DATE,
            ReaderTool.FS_CHECK, ReaderTool.FS_CROSS, ReaderTool.FS_DOT, ReaderTool.FS_LINE -> if (color == 0xFFEB3B) color = 0x212121
            else -> {}
        }
    }

    /** Commit an image placement (signature / stamp / picture) as a STAMP_IMAGE annotation. */
    fun confirmPlacement() {
        val p = placement ?: return
        // nitro-quicksign: Recently used (pendingText = the saved signature's path while the signature tool is active)
        if (tool == ReaderTool.SIGNATURE && pendingText.isNotBlank()) com.smartdocs.editor.core.data.SignatureMetaStore.markUsed(context, pendingText)
        val bmp = if (p.rotation != 0f) rotateBitmap(p.image, p.rotation) else p.image
        addAnnot(newAnnot(AnnotKind.STAMP_IMAGE, p.page, rects = listOf(PageGeometry.normPdf(p.rect)), image = bmp))
        placement = null
        if (!continueCreating) { tool = null; pendingImage = null }
    }

    fun cancelPlacement() { placement = null }

    private fun rotateBitmap(b: Bitmap, deg: Float): Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** Pending annotation kinds that behave like text markup on a selection. */
    fun markupFromSelection(kind: AnnotKind) {
        val sel = selection ?: return
        if (sel.rects.isEmpty()) return
        val c = when (kind) { AnnotKind.HIGHLIGHT -> if (color == 0x212121) 0xFFEB3B else color; else -> if (color == 0xFFEB3B) 0xF44336 else color }
        addAnnot(newAnnot(kind, sel.page, rects = sel.rects.map { PageGeometry.normPdf(it) }, text = sel.text.take(200), color = c))
        selection = null
    }
}

/** Owner tag of pending-annotation steps in the unified history. */
const val OWNER_ANNOTS = "annots"

@Composable
fun rememberReaderState(app: SmartDocsApp, uri: String, startPage: Int, settings: AppSettings): ReaderState {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // audit AN-03 / DI-11 / RD-25 / AN-01: the reader lives as long as its back-stack entry, not its composition.
    // Opening Settings, AI, e-sign, a linked file or a tool and coming back keeps pending edits, the undo history
    // and the open document (same ReaderState instance per entry, so per-reader side-cars stay attached).
    val holder: ReaderHolder = androidx.lifecycle.viewmodel.compose.viewModel(key = "reader:$uri")
    val state = holder.state?.takeIf { it.uriString == uri }
        ?: ReaderState(app, ctx.applicationContext, holder.scope, uri, startPage, settings).also { holder.state = it }
    LaunchedEffect(uri) { if (state.session == null && !state.needPassword) state.open(null) }
    return state
}

/**
 * The pending annotations after a save that wrote [pending] (a snapshot of [current] taken when it started) and could
 * not write [failed]: the failed ones plus everything added meanwhile (audit DI-05 / DI-36).
 */
internal fun stillPendingAfterSave(current: List<Annot>, pending: List<Annot>, failed: List<Annot>): List<Annot> {
    val written = pending.map { it.id }.toHashSet().apply { removeAll(failed.map { it.id }.toSet()) }
    return current.filter { it.id !in written }
}

/** Owns the reader of one back-stack entry; [onCleared] (entry popped for good) closes it, saving undiscarded edits. */
class ReaderHolder : androidx.lifecycle.ViewModel() {
    /** Main-thread scope of the reader; cancelled only after the reader was closed. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var state: ReaderState? = null

    override fun onCleared() {
        state?.close(); state = null
        scope.cancel()
    }
}
