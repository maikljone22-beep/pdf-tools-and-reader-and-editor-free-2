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
import com.smartdocs.editor.ui.reader.annot.styleNew

enum class ReaderTab { VIEW, COMMENT, EDIT, FILL_SIGN, SIGN, TOOLS, PAGES, CUSTOM }

/** COVER = "cover facing": page 1 alone, then pairs (2-3, 4-5…). Pager mapping: ui/reader/view/PageSpread. */
enum class ViewMode { SINGLE, CONTINUOUS, FACING, COVER }

/** Every interactive tool the page canvas understands. */
enum class ReaderTool {
    HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY, NOTE, FREETEXT, INK, ERASER, LINE, ARROW, RECT, OVAL, STAMP, MEASURE,
    TEXTBOX, IMAGE, LINK,
    FS_TEXT, FS_CHECK, FS_CROSS, FS_DOT, FS_LINE, FS_DATE, FS_PROFILE,
    SIGNATURE,
    // nitro-fillsign2: Fill & Sign rounded rectangle and comb text
    FS_RRECT, FS_COMB,
    // MODULE_annot2 (ui/reader/annot)
    CARET, REPLACE, POLYGON, POLYLINE, CLOUD, ATTACH, SOUND, VIDEO, SNAPSHOT, SELECT,
    // section 7: numbered / lettered / symbol tickmarks
    TICKMARK,
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
    var password: String? = null
        internal set(value) { field = value; file?.let { com.smartdocs.editor.core.pdf.OpenPasswords.put(it, value) } }   // audit D4
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
    // nav-prefs (PREF-6): default page layout and zoom from Settings → more preferences
    var viewMode by mutableStateOf(NpfReader.initialMode(initial.continuousScroll))
    var fitPage by mutableStateOf(NpfReader.initialFitPage())
    var zoom by mutableFloatStateOf(NpfReader.initialZoom())

    /**
     * Old plan A6 / FILE-2 / FILE-3: the document's own reading direction (/ViewerPreferences /Direction): true = right
     * to left, false = left to right, null = follow the app language.
     */
    var docRtl by mutableStateOf<Boolean?>(null)
    /** Panel or full screen the document asks for on its first open (/PageMode); consumed by the screen. */
    var initialPageMode by mutableStateOf<String?>(null)
    var nightMode by mutableStateOf(initial.nightMode)
    var pageColor by mutableLongStateOf(initial.pageColor)
    var rotation by mutableIntStateOf(0)
    var reflow by mutableStateOf(false)
    var reflowFontSize by mutableFloatStateOf(18f)
    var autoFlipSeconds by mutableIntStateOf(0)
    var pendingJump by mutableStateOf<Int?>(null)
    /** With [pendingJump]: how far down the page to scroll (0 = top, 1 = bottom; continuous view), e.g. JS doc.scroll. */
    var pendingJumpTop by mutableStateOf<Float?>(null)
    /** Crop, magnifier, auto-flip interval, orientation lock, screen timeout, reflow scale (ui/reader/view). */
    val vx = com.smartdocs.editor.ui.reader.view.ViewExtras(this)

    // ---- tabs / tools ----
    var tab by mutableStateOf(ReaderTab.VIEW)
    private var toolState by mutableStateOf<ReaderTool?>(null)
    /** The active tool. nitro-protect2: a tool the document's restrictions or Protected View forbid is refused here. */
    var tool: ReaderTool?
        get() = toolState
        set(v) {
            if (v != null && v != toolState && ReaderGuard.actionFor(v)?.let { guard.check(it) } == false) return
            // tabs audit CM-06: the settings of the tool being left are kept on every change, also when a tool clears itself
            val old = toolState
            if (old != null && old != v) {
                val mem = com.smartdocs.editor.ui.reader.annot.ToolMemory
                if (old in mem.REMEMBERED && mem.enabled(context)) runCatching { mem.save(context, old, mem.capture(this)) }
            }
            toolState = v
        }
    var color by mutableIntStateOf(0xFFEB3B)
    var opacity by mutableFloatStateOf(1f)
    var thickness by mutableFloatStateOf(3f)
    var fontSize by mutableFloatStateOf(14f)
    var continueCreating by mutableStateOf(com.smartdocs.editor.core.data.NpfPrefs.retainTools)   // nav-prefs: persisted
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
    var saving by mutableStateOf(false); internal set   // internal: Annot2Host.fileOp (audit CM-15)
    /** In-page form filling (MODULE_forms5): pending field values, live calculations, field bar. */
    val forms5 = com.smartdocs.editor.ui.forms5.FormFill.of(this).also { it.markDirty = { dirty = true } }
    /** nitro-protect2: restrictions, Protected View, Safe Reading Mode, action inspector. */
    val guard = ReaderGuard(this)
    /** Result of copying the saved file back into the document it was opened from (watched by the screen). */
    var writeBackResult by mutableStateOf<com.smartdocs.editor.core.files.DocumentRepository.WriteBack?>(null)
    /** lastModified/length of the file when the current session was opened; a tool replacing the file changes it. */
    private var openedStamp: Pair<Long, Long> = 0L to 0L

    // ---- search ----
    /** 8.84 JavaScript doc.saveAs / closeDoc: carried out by the reader screen (file name for "Save a copy"; leave). */
    var scriptSaveAs by mutableStateOf<String?>(null)
    var scriptClose by mutableStateOf(false)
    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList())
    var searchIndex by mutableIntStateOf(-1)
    var matchCase by mutableStateOf(com.smartdocs.editor.core.data.NpfPrefs.searchMatchCase)    // nav-prefs: search defaults
    var wholeWords by mutableStateOf(com.smartdocs.editor.core.data.NpfPrefs.searchWholeWords)
    var searching by mutableStateOf(false)
    private var searchJob: Job? = null

    // ---- caches ----
    private val textCache = HashMap<Int, String>()
    private val boxCache = HashMap<Int, List<RectF>>()
    private val cacheMutex = Mutex()

    // ================= document lifecycle =================

    /** A save into a signed / certified document was refused (audit AN-28 hook): the screen offers "Save a copy". */
    var signedBlock by mutableStateOf<com.smartdocs.editor.core.pdf.SignedDocumentException.Reason?>(null)

    /**
     * tab-view-core V-06 / E-01: the result of a change that [applyInPlace] refused to write into the signed /
     * certified original; the "signed" dialog's "Save a copy" exports it (the change is kept, the original is not
     * touched). Deleted by [dropSignedCopy].
     */
    var signedCopy by mutableStateOf<File?>(null); private set

    fun dropSignedCopy() { signedCopy?.delete(); signedCopy = null }

    private fun keepSignedCopy(out: File) {
        dropSignedCopy()
        val keep = File(app.documents.tempDir, "signed_copy_${System.nanoTime()}.pdf")
        runCatching { if (!out.renameTo(keep)) { out.copyTo(keep, overwrite = true); out.delete() } }.onSuccess { signedCopy = keep }.onFailure { out.delete() }
    }

    private var signedInfoCache: Pair<Pair<Long, Long>, com.smartdocs.editor.core.pdf.SignedGuard.Info>? = null

    /** tab-view-core V-06: signature / certification state of the open file (cached until the file changes). */
    suspend fun signedInfo(): com.smartdocs.editor.core.pdf.SignedGuard.Info {
        val f = file ?: return com.smartdocs.editor.core.pdf.SignedGuard.Info.NONE
        val stamp = f.lastModified() to f.length()
        signedInfoCache?.takeIf { it.first == stamp }?.let { return it.second }
        val pw = password
        val info = withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.SignedGuard.inspect(f, pw) }
        signedInfoCache = stamp to info
        return info
    }

    /**
     * tab-view-core V-06 / E-01 / D2: THE signed-file gate of the reader. What a [change] may do to the open file:
     * PROCEED, SAVE_INCREMENTAL (append with SignedSave.saveIncremental), OFFER_COPY or REFUSE (certified).
     * [incremental]: whether the writer can append (null = not known). [applyInPlace] applies it to every change.
     */
    suspend fun guardSigned(change: com.smartdocs.editor.core.pdf.SignedGuard.Change, incremental: Boolean? = null): com.smartdocs.editor.core.pdf.SignedGuard.Decision =
        com.smartdocs.editor.core.pdf.SignedGuard.decide(signedInfo(), change, incremental)

    /**
     * tab-view-core E-12: a protected file opened without its owner (permissions) password gets a new random owner
     * password on every full save (PdfOps.keepProtection), so the author's password would no longer lift its
     * restrictions. Before the first interactive write the screen asks once ([ownerPrompt]): the owner password (then
     * kept), continue anyway (""), or cancel (null).
     */
    var ownerPrompt by mutableStateOf<kotlinx.coroutines.CompletableDeferred<String?>?>(null); private set
    private var ownerAsked = false

    fun answerOwnerPrompt(answer: String?) { ownerPrompt?.complete(answer) }

    /** False when the user cancelled (or typed a wrong owner password): nothing is written. */
    suspend fun confirmOwnerPassword(): Boolean {
        val i = guard.info ?: return true
        if (!i.encrypted || i.ownerAccess || ownerAsked || disposed) return true
        val f = file ?: return true
        val d = kotlinx.coroutines.CompletableDeferred<String?>()
        ownerPrompt = d
        val ans = try { d.await() } finally { ownerPrompt = null }
        if (ans == null) return false
        if (ans.isEmpty()) { ownerAsked = true; return true }
        val owner = withContext(Dispatchers.IO) { runCatching { com.smartdocs.editor.core.pdf.PdfOps.loadRaw(f, ans).use { it.currentAccessPermission?.isOwnerPermission == true } }.getOrDefault(false) }
        if (!owner) { error = str(R.string.tvc_owner_wrong); return false }
        ownerAsked = true
        saveMutex.withLock { withContext(NonCancellable) {
            password = ans
            com.smartdocs.editor.core.pdf.OpenPasswords.put(f, ans)
            runCatching { guard.prepare(f, ans) }
            runCatching { reopen() }
        } }
        return true
    }

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
                    if (mark?.protected != true || com.smartdocs.editor.core.data.FcsPrefs.connectedOff) throw e   // fc-settings: ConnectedPDF off → the password prompt
                    val st = com.smartdocs.editor.core.connected.ConnectedDocs.checkOnOpen(context, f)
                    connected = st
                    if (st == null || st.locked) { connectedLocked = true; return@launch }
                    openPw = st.password
                    PdfSession.open(context, f, openPw)
                }
                if (disposed) { runCatching { s.closeAsync() }; return@launch }
                password = openPw
                com.smartdocs.editor.core.pdf.OpenPasswords.put(f, openPw)   // audit D4 / tab-view-core E-03 / E-20: tool screens open it too
                pageCount = s.pageCount
                val recent = app.db.docs().get(Uri.fromFile(f).toString())
                initialPage = (if (startPage >= 0) startPage else recent?.lastPage ?: 0).coerceIn(0, (s.pageCount - 1).coerceAtLeast(0))
                currentPage = initialPage
                // stable layout: sizes around the start page before the first frame, the rest in the background
                val near = (initialPage - 15).coerceAtLeast(0)..(initialPage + 15).coerceAtMost(s.pageCount - 1)
                runCatching { s.pageSizes(near) }.getOrNull()?.forEach { (i, sz) -> pageSizes[i] = sz }
                openedStamp = f.lastModified() to f.length()
                runCatching { guard.prepare(f, openPw) }    // nitro-protect2: before the first tap can edit / copy / print
                session = s
                scope.launch { runCatching { s.pageSizes() }.getOrNull()?.let { all -> androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { all.forEach { (i, sz) -> pageSizes[i] = sz } } } }
                needPassword = false; wrongPassword = false; error = null
                if (!markChecked && !com.smartdocs.editor.core.data.FcsPrefs.connectedOff) scope.launch {
                    // after the first render: connected status for the banner; a revoked protected copy still locks
                    val mark = withContext(Dispatchers.IO) { com.smartdocs.editor.core.connected.ConnectedDocs.markOf(f) } ?: return@launch
                    val st = com.smartdocs.editor.core.connected.ConnectedDocs.checkOnOpen(context, f)
                    connected = st
                    if (mark.protected && st?.locked == true) { runCatching { session?.closeAsync() }; session = null; connectedLocked = true }
                }
                val firstOpen = recent == null && startPage < 0
                scope.launch {
                    val v = withContext(Dispatchers.IO) { runCatching { com.smartdocs.editor.core.edit5.DocPropsOps.read(f, openPw).view }.getOrNull() } ?: return@launch
                    applyInitialView(v, firstOpen)
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

    // ---- OLD I1: Fill & Sign on flat forms — detected fields are tap targets ----
    /** Likely fields of pages without form fields (lines, boxes, label gaps), per page; filled when the page is shown. */
    val fsTargets = androidx.compose.runtime.mutableStateMapOf<Int, List<com.smartdocs.editor.core.formsx.FieldRecognition.Suggestion>>()
    private var fsTargetsKey: Any? = null

    fun loadFsTargets(page: Int) {
        val f = file ?: return
        val key = f.absolutePath to renderVersion
        if (fsTargetsKey != key) { fsTargets.clear(); fsTargetsKey = key }
        if (fsTargets.containsKey(page)) return
        fsTargets[page] = emptyList()
        val pw = password
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    com.smartdocs.editor.core.pdf.PdfOps.loadRaw(f, pw).use { d ->
                        // a real form is filled through its fields; only flat pages get detected targets
                        if (d.documentCatalog.acroForm?.fields?.isNotEmpty() == true || page !in 0 until d.numberOfPages) emptyList()
                        else com.smartdocs.editor.core.formsx.FieldRecognition.scanPage(d, page)
                    }
                }.getOrDefault(emptyList())
            }
            if (fsTargetsKey == key) fsTargets[page] = found
        }
    }

    /** The detected target under PDF point ([x], [y]) of [page], if any. */
    fun fsTargetAt(page: Int, x: Float, y: Float) = com.smartdocs.editor.core.formsx.FieldRecognition.at(fsTargets[page], x, y)

    fun jumpTo(page: Int) { pendingJump = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) }

    /** Jump to [page] and scroll [topFraction] of its height down (0..1). */
    fun jumpTo(page: Int, topFraction: Float) { pendingJumpTop = topFraction.coerceIn(0f, 1f); jumpTo(page) }

    /**
     * The document's initial view (written by Document properties or other producers): direction always; on the first
     * open also the open page and zoom (OpenAction), the page layout and the page mode, over the default layout / zoom /
     * panel of Settings → more preferences unless "use the document's initial view" is off. Later opens resume where
     * the reader left off.
     */
    fun applyInitialView(v: com.smartdocs.editor.core.edit5.InitialView, firstOpen: Boolean) {
        docRtl = InitialViewMap.rtl(v.direction)
        if (!firstOpen || !com.smartdocs.editor.core.data.NpfPrefs.docViewFirstOpen) return
        InitialViewMap.viewMode(v.pageLayout)?.let { viewMode = it }
        if (v.openPage in 1 until pageCount) jumpTo(v.openPage)
        if (v.openFit == com.smartdocs.editor.core.edit5.DestFit.XYZ && v.openZoom in 0.25f..8f) zoom = v.openZoom
        initialPageMode = v.pageMode.takeIf { it.isNotBlank() && it != "UseNone" }
    }

    /** Independent of the composable scope so closing still happens after the screen is disposed. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var disposed = false
    internal val saveMutex = Mutex()

    /** The user chose "discard" for the pending edits: [close] must not save them. */
    @Volatile var discarded = false
        private set

    fun discardChanges() { discarded = true; autosaveRefused = false; edit.discardOnClose(); forms5.discard() }

    /**
     * tab-view-core V-07: "Revert to saved" in place. The old way (close this reader, open a new one on the same file)
     * let the new session load the file before the closing reader had put the stored-annotation backup back. Now the
     * pending changes are dropped and the backup is restored here, under [saveMutex], then the session is reopened.
     */
    suspend fun revertToSaved() {
        saveMutex.withLock {
            withContext(NonCancellable) {
                val f = file ?: return@withContext
                edit.clear(); selection = null; placement = null; measure = null
                forms5.discard()
                annots.clear(); history.removeOwner(OWNER_ANNOTS)
                val b = edit.backupFile?.takeIf { it.exists() }
                if (b != null) {
                    runCatching { session?.closeAsync() }
                    withContext(Dispatchers.IO) { if (!b.renameTo(f)) { b.copyTo(f, overwrite = true); b.delete() } }
                }
                edit.committed()
                dirty = false
                if (b != null) reopen() else renderVersion++
            }
        }
    }

    /**
     * The document is closed for good (its back-stack entry was removed, see [ReaderHolder]). Pending edits the user
     * did not discard are saved first (audit AN-01 / DI-10): no way out of the reader drops them silently.
     */
    fun close() {
        disposed = true
        ReaderAi6.stopTtsOf(file?.absolutePath)     // tab-view-core V-08: read aloud never outlives its document
        com.smartdocs.editor.core.pdf.OpenPasswords.remove(file)
        guard.release()
        runCatching { video.release() }      // player + extracted video copies
        val saveFirst = dirty && !discarded
        val s = session
        if (!saveFirst) session = null
        val restore = edit.restoreOnClose()
        // side-car states keyed by this reader hold it strongly: drop them (audit ARCH-03)
        com.smartdocs.editor.ui.forms5.FormFill.forget(this)
        com.smartdocs.editor.ui.view5.V5State.forget(this)
        com.smartdocs.editor.ui.nve.NveState.forget(this)      // NVE
        com.smartdocs.editor.ui.nve.articles.NveArticlesState.forget(this)
        com.smartdocs.editor.ui.pageflip.PageFlipController.forget(this)
        StampClipboard.release(this)      // tab audit D31: the "Paste here" offer held this reader
        // PdfSession.closeAsync waits for in-flight renders, so no delay is needed
        ioScope.launch {
            if (saveFirst) withContext(NonCancellable) {
                // tab audit D7: a save the signed / certified file refuses must not lose the edits silently: they are
                // kept as the recovery copy the reader offers when this document is opened again
                val ok = runCatching { save(auto = true) }.getOrDefault(false)
                if (!ok && dirty) keepUnsavedOnClose()
            }
            runCatching { session?.closeAsync() }; session = null
            runCatching { s?.closeAsync() }
            // discarded edits: put back the file as it was before stored annotations were taken out for editing
            restore?.let { (backup, target) -> runCatching { if (!backup.renameTo(target)) { backup.copyTo(target, overwrite = true); backup.delete() } } }
            history.dispose()      // the document is closed for good: its history folder goes too
        }
    }

    /** Tab audit D7: the pending edits (file + annotations + form values) as the recovery copy of this document. */
    private suspend fun keepUnsavedOnClose() {
        val f = file ?: return
        val out = NpfReader.recoveryFile(this, f)
        val tmp = File(out.parentFile, out.name + ".part")
        val kept = runCatching { exportWithAnnots(tmp); if (tmp.length() > 0) { out.delete(); tmp.renameTo(out) } else false }.getOrDefault(false)
        tmp.delete()
        withContext(Dispatchers.Main) {
            runCatching { android.widget.Toast.makeText(context, str(if (kept) R.string.tfr_close_kept_copy else R.string.tfr_close_lost), android.widget.Toast.LENGTH_LONG).show() }
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
        if (!guard.check(com.smartdocs.editor.core.security.DocAction.ANNOTATE)) return
        val before = annots.toList()
        annots += a; dirty = true
        annotStep(R.string.hi_add_annot, a.page, before)
        if (!continueCreating && tool != ReaderTool.INK) tool = null
    }

    /** Several pending-annotation changes as one undo step (multi-select, handwriting to text). */
    fun batchAnnots(change: (MutableList<Annot>) -> Unit) {
        if (!guard.check(com.smartdocs.editor.core.security.DocAction.ANNOTATE)) return
        val before = annots.toList()
        val copy = before.toMutableList(); change(copy)
        if (copy == before) return
        annots.clear(); annots += copy; dirty = true
        annotStep(R.string.hi_batch_annot, copy.firstOrNull { it !in before }?.page ?: before.firstOrNull { it !in copy }?.page ?: currentPage, before)
        edit.validate()
    }

    /** Eraser stroke (tabs audit CM-08): pending ink removed while dragging, one undo step at the end ([endErase]); locked ink stays. */
    private var eraseBefore: List<Annot>? = null

    fun eraseHits(ids: List<Long>) {
        if (ids.isEmpty() || !guard.check(com.smartdocs.editor.core.security.DocAction.ANNOTATE)) return
        if (eraseBefore == null) eraseBefore = annots.toList()
        if (annots.removeAll { it.id in ids && !it.locked }) dirty = true
    }

    fun endErase(page: Int) {
        val b = eraseBefore ?: return
        eraseBefore = null
        if (annots.size != b.size) annotStep(R.string.hi_del_annot, page, b)
    }

    fun removeAnnot(id: Long) {
        val a = annots.firstOrNull { it.id == id } ?: return
        val before = annots.toList()
        annots.removeAll { it.id == id }; dirty = true
        annotStep(R.string.hi_del_annot, a.page, before)
    }

    fun replaceAnnot(a: Annot) {
        val i = annots.indexOfFirst { it.id == a.id }; if (i < 0) return
        if (!guard.check(com.smartdocs.editor.core.security.DocAction.ANNOTATE)) return
        val before = annots.toList()
        annots[i] = a; dirty = true
        annotStep(R.string.hi_edit_annot, a.page, before)
    }

    /** Unified undo of the newest step (annotation, form value or file change). */
    fun undo() { scope.launch { runHistory(true, listOfNotNull(history.peekUndo())) { history.undo() } } }

    /** Unified redo. */
    fun redo() { scope.launch { runHistory(false, listOfNotNull(history.peekRedo())) { history.redo() } } }

    /**
     * tab-view-core E-05: undo / redo that waits for the result and goes through the same guards as the reader's
     * buttons (stored annotations taken out, saving in progress); returns the event (label or error), consumed.
     */
    suspend fun historyStepAwait(undo: Boolean): HistoryEvent? {
        historyEvent = null
        runHistory(undo, listOfNotNull(if (undo) history.peekUndo() else history.peekRedo())) { if (undo) history.undo() else history.redo() }
        return historyEvent.also { historyEvent = null }
    }

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
            points = points, text = NpfReader.typed(kind, text), fontSize = fontSize, author = authorName, image = image)
            .let { if (tool != null) annot2.styleNew(it) else it }

    // ================= save =================

    /**
     * Write pending annotations into the file itself (write temp → close session → replace → reopen).
     * The critical section is non-cancellable so a save started from ON_PAUSE finishes even when the
     * screen is disposed meanwhile; concurrent calls (back button + autosave) are serialised.
     */
    suspend fun save(auto: Boolean = false): Boolean {
        if (!auto) autosaveRefused = false      // tab audit D7: the user asked: try (and explain) again
        val ok = saveLocked()
        // the dialog shows once; later autosaves stay quiet until the user saves / leaves (V-02 dialog) or discards
        if (!ok && signedRefusal) autosaveRefused = true
        return ok
    }

    /** Set by [saveLocked] when the signed / certified file refused the save (tab audit D7). */
    @Volatile private var signedRefusal = false

    private suspend fun saveLocked(): Boolean {
        signedRefusal = false
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
                    catch (e: com.smartdocs.editor.core.pdf.SignedDocumentException) { signedBlock = e.reason; signedRefusal = true; return false }   // audit V-01
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
                        flattenSignaturesOnSave(pending, tmp)
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
                    if (wrote) guard.afterSave(f)      // nitro-protect2: a trusted document stays trusted
                    return true
                } catch (e: com.smartdocs.editor.core.pdf.SignedDocumentException) {
                    // signed / certified file: the pending annotations stay pending; the screen offers "Save a copy"
                    signedBlock = e.reason; signedRefusal = true; return false
                } catch (e: Exception) { error = e.message ?: e.toString(); return false } finally { saving = false }
            } finally {
                if (wrote && before != null) recordSaved(before, prevPw) else before?.let { runCatching { it.delete() } }
                // audit AN-05: written without a snapshot (copy failed, e.g. storage full) -> the memory steps describe
                // annotations / values that are now in the file; replaying them would duplicate them
                if (wrote && before == null) { history.removeOwner(OWNER_ANNOTS); history.removeOwner(com.smartdocs.editor.ui.forms5.OWNER_FORMS) }
            }
        }
    }

    /**
     * nitro-fillsign2 preference "Flatten signatures when saving": the signature stamps just written into [tmp] (and
     * any placed before) become page content. Never on a digitally signed file: its signatures would break.
     */
    private suspend fun flattenSignaturesOnSave(pending: List<Annot>, tmp: File) {
        if (pending.none { it.isSignature } || !com.smartdocs.editor.core.fillsign2.FillSignPrefs.flattenOnSave(context)) return
        withContext(Dispatchers.IO) {
            if (!tmp.exists() || com.smartdocs.editor.core.pdf.SignedSave.isSigned(tmp, password)) return@withContext
            val flat = File(tmp.parentFile, tmp.nameWithoutExtension + ".sigflat.pdf")
            runCatching {
                if (com.smartdocs.editor.core.pdf.PdfOps.flattenSignatureStamps(tmp, flat, password) > 0 && !flat.renameTo(tmp)) { flat.copyTo(tmp, overwrite = true); flat.delete() }
            }.onFailure { flat.delete() }
        }
    }

    /** The pending steps just written into the file are replaced by one file step holding the version before. */
    private fun recordSaved(before: File, pw: String?) {
        val label = HistoryNames.groupLabel(history.memoryLabels(), str(R.string.hi_saved)) { l, n -> str(R.string.hi_saved_group, l, n) }
        history.replaceMemory(HistoryStep.FileSnapshot(label, before, pw, currentPage))
    }

    /**
     * Tab audit D7: a save into this signed / certified file was refused. Autosave (ON_PAUSE, the N-minute timer)
     * then stops retrying, so it does not raise the same "signed" dialog again and again; an explicit save, a
     * successful save or "discard" clear it.
     */
    @Volatile private var autosaveRefused = false

    /** Autosave from ON_PAUSE: runs in a scope that outlives the composable so leaving the screen cannot cut it short. */
    fun saveInBackground() { if (dirty && !saving && !autosaveRefused) ioScope.launch { runCatching { save(auto = true) } } }

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
        if (!guard.check(com.smartdocs.editor.core.security.DocAction.ANNOTATE)) return      // nitro-protect2
        if (pts.isEmpty() || saving) return
        val candidates = existing.filter { it.page == page && it.subtype == "Ink" && !it.locked && EraseLogic.nearRect(it.rect.left, it.rect.bottom, it.rect.right, it.rect.top, pts, radius + 4f) }
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
        if (!guard.check(com.smartdocs.editor.core.security.DocAction.ANNOTATE)) return      // nitro-protect2
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
        scope.launch { runCatching { guard.refreshSigned(f, password) } }      // tab audit D8: a signing tool may have certified it
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
    suspend fun applyInPlace(newPassword: String? = password, label: (() -> String)? = null,
                             action: com.smartdocs.editor.core.security.DocAction? = com.smartdocs.editor.core.security.DocAction.MODIFY,
                             change: com.smartdocs.editor.core.pdf.SignedGuard.Change? = null,
                             op: suspend (src: File, out: File, pw: String?) -> Unit): File? {
        // nitro-protect2: the author's restrictions / Protected View ([action] = what the change needs; null = none)
        action?.let { guard.require(it) }
        if (!confirmOwnerPassword()) return null      // tab-view-core E-12
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
                // tab-view-core V-06 / E-01 / D2: one signed-file gate for every in-place change. A result that is not
                // an incremental update of a signed original (or that a certification forbids) never replaces it:
                // it is kept as [signedCopy] and the screen offers "Save a copy".
                val info = signedInfo()
                if (info.protectedBySignature) {
                    val incr = withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.SignedGuard.isIncrementalOf(f, out) }
                    val d = com.smartdocs.editor.core.pdf.SignedGuard.decide(info, signedChangeOf(change, action, incr, newPassword != prevPw), incr)
                    com.smartdocs.editor.core.pdf.SignedGuard.reasonOf(d)?.let { r -> keepSignedCopy(out); signedBlock = r; return@withLock null }
                }
                withContext(NonCancellable) {
                    val lbl = runCatching { label?.invoke() }.getOrNull()?.takeIf { it.isNotBlank() } ?: str(R.string.hi_doc_change)
                    password = newPassword
                    val snap = try { swapFile(out, lbl, prevPw, requireSnapshot = true) } catch (e: Throwable) { password = prevPw; throw e }
                    writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }
                    snap
                }
            } catch (e: com.smartdocs.editor.core.pdf.SignedDocumentException) {
                // tabs audit CM-01 / CM-14: signed / certified file -> "save a copy" dialog, nothing written
                out.delete(); signedBlock = e.reason; null
            } catch (e: Throwable) {
                out.delete(); throw e
            }
        }
    }

    /**
     * The kind of change for the signed gate: explicit [change], else from the permission [action]. A writer that
     * appended an incremental update knows about signatures (NavIo, CommentOps, AttachmentOps): it is judged as an
     * annotation-level change. A password change always rewrites the file.
     */
    private fun signedChangeOf(change: com.smartdocs.editor.core.pdf.SignedGuard.Change?, action: com.smartdocs.editor.core.security.DocAction?,
                               incremental: Boolean, passwordChanged: Boolean): com.smartdocs.editor.core.pdf.SignedGuard.Change {
        val C = com.smartdocs.editor.core.pdf.SignedGuard.Change.MODIFY
        if (passwordChanged) return C
        val c = change ?: when (action) {
            com.smartdocs.editor.core.security.DocAction.ANNOTATE -> com.smartdocs.editor.core.pdf.SignedGuard.Change.ANNOTATE
            com.smartdocs.editor.core.security.DocAction.FILL_FORMS -> com.smartdocs.editor.core.pdf.SignedGuard.Change.FORM_FILL
            else -> C
        }
        return if (incremental && c == C) com.smartdocs.editor.core.pdf.SignedGuard.Change.ANNOTATE else c
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
        if (query.isBlank()) { searchHits = emptyList(); searchIndex = -1; searching = false; return }   // tab-view-core V-20
        val s = session ?: return
        searchJob = scope.launch {
            searching = true
            delay(250)
            val norm = com.smartdocs.editor.core.search.SearchPrefs.load(context, matchCase)
            val hits = runCatching {
                s.searchWith { com.smartdocs.editor.core.search.TextMatcher.find(it, query, norm, wholeWords) }
            }.getOrElse { runCatching { s.search(query, matchCase, wholeWords) }.getOrDefault(emptyList()) }
            val max = com.smartdocs.editor.core.data.NpfPrefs.searchMax   // nav-prefs: maximum results
            searchHits = if (max > 0) hits.take(max) else hits
            searchIndex = if (hits.isEmpty()) -1 else {
                val near = searchHits.indexOfFirst { it.page >= currentPage }.let { if (it < 0) 0 else it }
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

    fun closeSearch() { searchJob?.cancel(); searching = false; searchOpen = false; searchHits = emptyList(); searchIndex = -1; searchQuery = "" }   // tab-view-core V-20

    // ================= tools =================

    fun selectTool(t: ReaderTool?) {
        // remember the settings of the tool being left, restore those of the new one (OLD_PLAN: settings per tool)
        val mem = com.smartdocs.editor.ui.reader.annot.ToolMemory
        val prevTool = tool
        tool = if (tool == t) null else t                 // the setter saved the old tool's settings
        // tabs audit CM-27: the stamp picker starts empty (a picture of the image / signature tool is not a stamp)
        if (tool == ReaderTool.STAMP && prevTool != ReaderTool.STAMP) { pendingImage = null; placement = null }
        val restored = mem.enabled(context) && tool?.takeIf { it in mem.REMEMBERED }?.let { nt -> mem.load(context, nt) }?.also { mem.apply(this, it) } != null
        if (restored) { selection = null; measure = null; edit.clear(); placement = null; pendingImage = null; pendingText = ""; return }
        selection = null; measure = null; edit.clear()
        if (tool != ReaderTool.SIGNATURE && tool != ReaderTool.STAMP && tool != ReaderTool.IMAGE) { placement = null; pendingImage = null }
        if (tool != ReaderTool.FS_PROFILE && tool != ReaderTool.FS_DATE) pendingText = ""
        // sensible default colours per tool
        when (tool) {
            ReaderTool.HIGHLIGHT -> if (color == 0x212121) color = 0xFFEB3B
            ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY, ReaderTool.INK, ReaderTool.LINE, ReaderTool.ARROW,
            ReaderTool.RECT, ReaderTool.OVAL -> if (color == 0xFFEB3B) color = 0xF44336
            ReaderTool.FREETEXT, ReaderTool.TEXTBOX, ReaderTool.FS_TEXT, ReaderTool.FS_PROFILE, ReaderTool.FS_DATE,
            ReaderTool.FS_CHECK, ReaderTool.FS_CROSS, ReaderTool.FS_DOT, ReaderTool.FS_LINE, ReaderTool.FS_RRECT, ReaderTool.FS_COMB -> if (color == 0xFFEB3B) color = 0x212121
            else -> {}
        }
    }

    /** Commit an image placement (signature / stamp / picture) as a STAMP_IMAGE annotation. */
    fun confirmPlacement() {
        val p = placement ?: return
        // nitro-quicksign: Recently used (pendingText = the saved signature's path while the signature tool is active)
        if (tool == ReaderTool.SIGNATURE && pendingText.isNotBlank()) com.smartdocs.editor.core.data.SignatureMetaStore.markUsed(context, pendingText)
        val bmp = if (p.rotation != 0f) rotateBitmap(p.image, p.rotation) else p.image
        // nitro-fillsign2: a placed signature remembers its source (default size, Apply All Signatures)
        val sig = pendingText.takeIf { tool == ReaderTool.SIGNATURE && it.isNotBlank() }
        addAnnot(newAnnot(AnnotKind.STAMP_IMAGE, p.page, rects = listOf(PageGeometry.normPdf(p.rect)), image = bmp).copy(sigPath = sig))
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
        val copied = if (com.smartdocs.editor.core.data.NpfPrefs.copySelection) sel.text.take(200) else ""   // nav-prefs (PREF-4)
        addAnnot(newAnnot(kind, sel.page, rects = sel.rects.map { PageGeometry.normPdf(it) }, text = copied, color = c))
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

/** PDF initial-view names → reader settings (pure, unit-tested). */
object InitialViewMap {
    fun rtl(direction: String): Boolean? = when (direction) { "R2L" -> true; "L2R" -> false; else -> null }

    fun viewMode(pageLayout: String): ViewMode? = when (pageLayout) {
        "SinglePage" -> ViewMode.SINGLE
        "OneColumn" -> ViewMode.CONTINUOUS
        "TwoColumnLeft", "TwoPageLeft" -> ViewMode.FACING
        "TwoColumnRight", "TwoPageRight" -> ViewMode.COVER
        else -> null
    }

    /** /PageMode → the reader panel to open, "full" for FullScreen, null for none. */
    fun panelOf(pageMode: String?): String? = when (pageMode) {
        "UseOutlines" -> "OUTLINE"; "UseThumbs" -> "THUMBNAILS"; "UseAttachments" -> "INFO"; "FullScreen" -> "full"; else -> null
    }
}
