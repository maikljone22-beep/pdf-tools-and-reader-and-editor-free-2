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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
    var pageCount by mutableIntStateOf(0); private set
    val pageSizes = mutableStateMapOf<Int, Pair<Float, Float>>()
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
    private val undoStack = ArrayDeque<List<Annot>>()
    private val redoStack = ArrayDeque<List<Annot>>()
    /** Selection / editing of existing annotations on the page (see AnnotEdit.kt). */
    val edit = AnnotEditor(this)
    /** Polygon/cloud/caret/media/snapshot tools, multi-select, comment threads & exchange (MODULE_annot2). */
    val annot2 = com.smartdocs.editor.ui.reader.annot.Annot2Host(this)
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var dirty by mutableStateOf(false); private set
    var existing by mutableStateOf<List<AnnotationWriter.Existing>>(emptyList()); private set
    var saving by mutableStateOf(false); private set
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

    fun open(pw: String? = null) {
        scope.launch {
            try {
                if (session != null) return@launch
                val f = file ?: app.documents.localFile(Uri.parse(uriString)).also { file = it }
                val s = PdfSession.open(context, f, pw)
                if (disposed) { runCatching { s.closeAsync() }; return@launch }
                password = pw
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
                app.documents.touchRecent(f, s.pageCount, initialPage)
                app.settings.update { it.copy(lastOpenedUri = Uri.fromFile(f).toString()) }
                loadExisting()
            } catch (e: PdfSession.Companion.PasswordRequiredException) {
                needPassword = true; wrongPassword = pw != null
            } catch (e: Exception) {
                error = e.message ?: e.toString()
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

    fun close() {
        disposed = true
        val s = session; session = null
        val restore = edit.restoreOnClose()
        // PdfSession.closeAsync waits for in-flight renders, so no delay is needed
        ioScope.launch {
            runCatching { s?.closeAsync() }
            // discarded edits: put back the file as it was before stored annotations were taken out for editing
            restore?.let { (backup, target) -> runCatching { if (!backup.renameTo(target)) { backup.copyTo(target, overwrite = true); backup.delete() } } }
        }
    }

    private fun loadExisting() {
        val f = file ?: return
        scope.launch { existing = runCatching { AnnotationWriter.list(f, password) }.getOrDefault(emptyList()) }
    }

    // ================= annotations / undo =================

    private fun snapshot() {
        undoStack.addLast(annots.toList()); if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear(); canUndo = true; canRedo = false
    }

    fun addAnnot(a: Annot) {
        snapshot(); annots += a; dirty = true
        if (!continueCreating && tool != ReaderTool.INK) tool = null
    }

    /** Several pending-annotation changes as one undo step (multi-select, handwriting → text). */
    fun batchAnnots(change: (MutableList<Annot>) -> Unit) {
        val copy = annots.toMutableList(); change(copy)
        if (copy == annots.toList()) return
        snapshot(); annots.clear(); annots += copy; dirty = true
        edit.validate()
    }

    fun removeAnnot(id: Long) {
        if (annots.none { it.id == id }) return
        snapshot(); annots.removeAll { it.id == id }; dirty = true
    }

    fun replaceAnnot(a: Annot) {
        val i = annots.indexOfFirst { it.id == a.id }; if (i < 0) return
        snapshot(); annots[i] = a; dirty = true
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(annots.toList())
        annots.clear(); annots += prev
        canUndo = undoStack.isNotEmpty(); canRedo = true; dirty = true
        edit.validate()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(annots.toList())
        annots.clear(); annots += next
        canUndo = true; canRedo = redoStack.isNotEmpty(); dirty = true
        edit.validate()
    }

    /**
     * A stored annotation was taken out of the file to be edited: it becomes pending. Every undo/redo state
     * gets it too, so undo can revert its edits but never makes it vanish (the file no longer holds it).
     */
    internal fun adoptConverted(a: Annot) {
        annots += a
        val u = undoStack.map { it + a }; undoStack.clear(); undoStack.addAll(u)
        val r = redoStack.map { it + a }; redoStack.clear(); redoStack.addAll(r)
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
            if (annots.isEmpty()) {
                // stored annotations may have been taken out for editing and then deleted: the file already reflects it
                if (edit.hasBackup) { writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }; edit.committed() }
                dirty = false; return true
            }
            saving = true
            try {
                val pending = annots.toList()
                withContext(NonCancellable) {
                    val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.saving.pdf")
                    AnnotationWriter.commit(context, f, pending, tmp, password)
                    swapFile(tmp)
                    // the edit belongs in the user's document, not only in our working copy
                    writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }
                }
                annots.clear(); undoStack.clear(); redoStack.clear(); canUndo = false; canRedo = false; dirty = false
                edit.committed()
                return true
            } catch (e: Exception) { error = e.message ?: e.toString(); return false } finally { saving = false }
        }
    }

    /** Autosave from ON_PAUSE: runs in a scope that outlives the composable so leaving the screen cannot cut it short. */
    fun saveInBackground() { if (dirty && !saving) ioScope.launch { runCatching { save() } } }

    /** Write pending annotations plus the current file into [out] (used by "save as"). */
    suspend fun exportWithAnnots(out: File) {
        val f = file ?: return
        if (annots.isEmpty()) withContext(Dispatchers.IO) { f.copyTo(out, overwrite = true) }
        else AnnotationWriter.commit(context, f, annots.toList(), out, password)
    }

    suspend fun deleteExisting(e: AnnotationWriter.Existing) {
        saveMutex.withLock {
            val f = file ?: return
            saving = true
            try {
                withContext(NonCancellable) {
                    val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.saving.pdf")
                    AnnotationWriter.delete(f, e.page, e.index, tmp, password)
                    swapFile(tmp)
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
     */
    internal suspend fun swapFile(tmp: File) {
        val f = file ?: return
        // The closed session stays referenced (not nulled) so the page view keeps its scroll position;
        // renders issued meanwhile fail fast (session.isClosed) and the canvas keeps its last bitmap.
        runCatching { session?.closeAsync() }    // waits for the native lock, never frees PDFium mid-render
        withContext(Dispatchers.IO) {
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
        reopen()
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
     * to its source. Returns a copy of the previous version (for undo) or null when nothing was done.
     * [newPassword] is the password the result opens with (protect / remove password change it).
     */
    suspend fun applyInPlace(newPassword: String? = password, op: suspend (src: File, out: File, pw: String?) -> Unit): File? {
        if (dirty && !save()) return null
        selection = null
        return saveMutex.withLock {
            val f = file ?: return@withLock null
            val out = File(f.parentFile, ".${f.nameWithoutExtension}.quick.pdf")
            val backup = app.documents.newTemp("undo")
            try {
                withContext(Dispatchers.IO) { f.copyTo(backup, overwrite = true); out.delete() }
                op(f, out, password)
                if (!out.exists() || out.length() == 0L) throw IllegalStateException("empty result")
                withContext(NonCancellable) {
                    password = newPassword
                    swapFile(out)
                    writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }
                }
                backup
            } catch (e: Throwable) {
                out.delete(); backup.delete(); throw e
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
                swapFile(tmp)
                writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }
            }
        }
    }

    /** Called when the screen resumes: a tool may have replaced the document ("save in the same file"). */
    fun reloadIfChanged() {
        val f = file ?: return
        if (session == null || saving || !f.exists()) return
        if ((f.lastModified() to f.length()) == openedStamp) return
        scope.launch { saveMutex.withLock { withContext(NonCancellable) { runCatching { reopen() }.onFailure { error = it.message } } } }
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

@Composable
fun rememberReaderState(app: SmartDocsApp, uri: String, startPage: Int, settings: AppSettings): ReaderState {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(uri) { ReaderState(app, ctx.applicationContext, scope, uri, startPage, settings) }
    LaunchedEffect(uri) { if (state.session == null && !state.needPassword) state.open(null) }
    DisposableEffect(uri) { onDispose { state.close() } }
    return state
}
