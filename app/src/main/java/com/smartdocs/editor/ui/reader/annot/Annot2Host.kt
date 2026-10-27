package com.smartdocs.editor.ui.reader.annot

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.smartdocs.editor.core.annot.Annot2Writer
import com.smartdocs.editor.core.annot.CommentItem
import com.smartdocs.editor.core.annot.CommentStore
import com.smartdocs.editor.core.annot.MediaAnnots
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.ui.reader.AnnotTarget
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.reader.TextSelectionLogic
import com.smartdocs.editor.ui.reader.annotBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Text to type for a caret / replace created on the page (or from a text selection). */
data class Prompt2(val kind: AnnotKind, val page: Int, val rects: List<RectF>, val original: String = "")

/** A tap (or, for a video, a dragged box [rect] in PDF space) that waits for a picker / recorder to produce its file. */
data class Tap2(val tool: ReaderTool, val page: Int, val x: Float, val y: Float, val rect: RectF? = null)

/** Result of the snapshot tool: a PNG of the region, already on the clipboard. */
data class Snapshot2(val file: File, val bitmap: Bitmap)

/** Ink to turn into text: pending ink ids and stored ink annotations of one page. */
data class Handwriting2(val page: Int, val pendingIds: List<Long>, val stored: List<AnnotationWriter.Existing>)

private enum class DragMode { LASSO, SNAPSHOT, REPLACE, MOVE, VIDEO_BOX }

/**
 * Everything MODULE_annot2 adds to the reader: polygon / polyline / cloud by taps, caret & replace text,
 * attachments, sound, video, snapshot, multi-select, handwriting recognition and the comment exchange
 * actions. Owned by [ReaderState] (`state.annot2`); the page canvas forwards taps, drags and drawing here.
 */
class Annot2Host(val s: ReaderState) {
    // ---- property bar extras ----
    var fillColor by mutableStateOf<Int?>(null)
    var cloudIntensity by mutableIntStateOf(1)
    // ---- section 7 creation extras (remembered per tool, ToolMemory) ----
    var dash by mutableIntStateOf(0)
    var noBorder by mutableStateOf(false)
    var lineStart by mutableStateOf("None")
    var lineEnd by mutableStateOf("")
    var rectCloud by mutableIntStateOf(0)
    var bold by mutableStateOf(false)
    var italic by mutableStateOf(false)
    var underline by mutableStateOf(false)
    var noteIcon by mutableStateOf("")
    /** Text box formatting for new FreeText (font family, strike, alignment, spacing, scale, bullets). */
    var textFmt by mutableStateOf(TextFmt())
    /** New lines / arrows show their note text on the line (/Cap). */
    var caption by mutableStateOf(false)
    /** Ink strokes that look like a line / rectangle / ellipse become that shape (OLD_PLAN D2). */
    var inkToShapes by mutableStateOf(false)
    /** Rectangle / oval drawn as square / circle (Shift constraint). */
    var constrain by mutableStateOf(false)
    /** Comment features of section 7 (clipboard, align, lock, flatten, pop-ups, tickmarks...). */
    val cx = CommentExtras(this)

    // ---- polygon / polyline / cloud in progress ----
    var polyPage by mutableIntStateOf(-1); private set
    val vertices = mutableStateListOf<Pair<Float, Float>>()

    // ---- drag gesture (lasso / snapshot / replace / move) in unrotated screen space ----
    var dragPage by mutableIntStateOf(-1); private set
    val dragPts = mutableStateListOf<Offset>()
    private var dragMode: DragMode? = null

    val multi = MultiSelection(this)

    // ---- dialogs (rendered by Annot2Dialogs) ----
    var prompt by mutableStateOf<Prompt2?>(null)
    var pendingTap by mutableStateOf<Tap2?>(null)
    var recordTap by mutableStateOf<Tap2?>(null)
    var snapshot by mutableStateOf<Snapshot2?>(null)
    var handwriting by mutableStateOf<Handwriting2?>(null)
    var playing by mutableStateOf<File?>(null)
    var summaryFile by mutableStateOf<File?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    /** Stored comments with threads / status / groups, refreshed whenever the stored list changes. */
    var comments by mutableStateOf<List<CommentItem>>(emptyList()); private set

    // ---- launchers installed by Annot2Dialogs (they need the composition) ----
    var launchAttach: () -> Unit = {}
    var launchVideo: () -> Unit = {}
    /** Pick an audio file (.mp3 / .wav / .m4a ...) for the Sound tool instead of recording (Foxit 19.x). */
    var launchAudio: () -> Unit = {}
    var launchImport: () -> Unit = {}
    var launchMerge: () -> Unit = {}
    var launchExport: (xfdf: Boolean) -> Unit = {}
    var launchSummary: () -> Unit = {}
    /** Export the selection (or all when [all]) as XFDF / FDF; [share] sends it (e-mail) instead of saving. */
    var launchExportSelected: (xfdf: Boolean, share: Boolean, all: Boolean) -> Unit = { _, _, _ -> }

    val polyTools = setOf(ReaderTool.POLYGON, ReaderTool.POLYLINE, ReaderTool.CLOUD)

    // =============== comments cache ===============

    suspend fun reloadComments() {
        val f = s.file ?: return
        comments = runCatching { CommentStore.load(f, s.password) }.getOrDefault(emptyList())
    }

    // =============== tool selection ===============

    /** Toolbar click on one of our tools (or any tool from the custom toolset). */
    fun pick(t: ReaderTool) {
        // caret / replace on an existing text selection act on it right away
        val sel = s.selection
        if (sel != null && (t == ReaderTool.REPLACE || t == ReaderTool.CARET)) {
            if (t == ReaderTool.REPLACE) prompt = Prompt2(AnnotKind.REPLACE, sel.page, sel.rects.map { PageGeometry.normPdf(it) }, sel.text)
            else {
                val last = sel.rects.lastOrNull()?.let { PageGeometry.normPdf(it) } ?: return
                val rtl = sel.text.any { it in '؀'..'ۿ' }
                prompt = Prompt2(AnnotKind.CARET, sel.page, listOf(Annot2Writer.caretBox(if (rtl) last.left else last.right, last.bottom, last.top - last.bottom)))
            }
            s.selection = null
            return
        }
        s.selectTool(t)
        vertices.clear(); polyPage = -1
        if (s.tool != ReaderTool.SELECT) multi.clear()
        when (s.tool) {
            ReaderTool.CARET -> s.color = 0x993399
            ReaderTool.REPLACE -> s.color = 0x0000FF
            ReaderTool.POLYGON, ReaderTool.POLYLINE, ReaderTool.CLOUD, ReaderTool.ATTACH, ReaderTool.SOUND, ReaderTool.VIDEO ->
                if (s.color == 0xFFEB3B || s.color == 0x212121) s.color = 0xF44336
            else -> {}
        }
        // Video tool: pick the video first, then drag its box on the page (or tap for a default box)
        if (s.tool == ReaderTool.VIDEO && s.video.picked == null) { pendingTap = null; launchVideo() }
        else if (s.tool == ReaderTool.VIDEO) message = s.context.getString(com.smartdocs.editor.R.string.vid_tool_hint)
    }

    /** The active tool changed by any path: drop transient state that belonged to the previous one. */
    fun onToolChanged(t: ReaderTool?) {
        if (t !in polyTools) { vertices.clear(); polyPage = -1 }
        if (t != null && t != ReaderTool.SELECT) multi.clear()
    }

    // =============== page hooks ===============

    /** A tap on [page] at PDF (x, y). Returns true when it was handled here. */
    fun onTap(page: Int, g: PageGeometry, x: Float, y: Float): Boolean {
        val tol = 8f / g.scale.coerceAtLeast(0.1f)
        val t = s.tool
        if (t == null && multi.active) {
            val b = multi.bounds()
            if (multi.page == page && b != null && x >= b.left - tol && x <= b.right + tol && y <= b.top + tol && y >= b.bottom - tol) return true
            multi.clear(); return true
        }
        when (t) {
            ReaderTool.POLYGON, ReaderTool.POLYLINE, ReaderTool.CLOUD -> {
                if (polyPage != page) { vertices.clear(); polyPage = page }
                vertices += x to y
            }
            ReaderTool.CARET -> s.scope.launch {
                val boxes = s.charBoxes(page); val text = s.pageText(page)
                val i = TextSelectionLogic.charAt(boxes, x, y, 14f)
                val box = if (i != null) {
                    val r = PageGeometry.normPdf(boxes[i])
                    val rtl = text.getOrNull(i)?.let { it in '؀'..'ۿ' } ?: false
                    val after = if (rtl) x < (r.left + r.right) / 2 else x > (r.left + r.right) / 2
                    val cx = if (after != rtl) r.right else r.left
                    Annot2Writer.caretBox(cx, r.bottom, r.top - r.bottom)
                } else Annot2Writer.caretBox(x, y - 6f, 12f)
                prompt = Prompt2(AnnotKind.CARET, page, listOf(box))
            }
            ReaderTool.REPLACE -> s.scope.launch {
                val boxes = s.charBoxes(page); val text = s.pageText(page)
                val i = TextSelectionLogic.charAt(boxes, x, y) ?: return@launch
                val r = TextSelectionLogic.wordAt(text, i)
                prompt = Prompt2(AnnotKind.REPLACE, page, TextSelectionLogic.rects(boxes, r.first, r.last), TextSelectionLogic.text(text, r.first, r.last, boxes))
            }
            ReaderTool.ATTACH -> { pendingTap = Tap2(ReaderTool.ATTACH, page, x, y); launchAttach() }
            ReaderTool.VIDEO -> placeVideoOrPick(Tap2(ReaderTool.VIDEO, page, x, y))
            ReaderTool.SOUND -> recordTap = Tap2(ReaderTool.SOUND, page, x, y)
            ReaderTool.SNAPSHOT -> {}
            ReaderTool.SELECT -> { if (!multi.toggleAt(page, x, y, tol)) multi.clear() }
            ReaderTool.TICKMARK -> cx.placeTick(page, x, y)
            else -> return false
        }
        return true
    }

    /** Double tap: finishes a polygon (adding the tapped vertex). */
    fun onDoubleTap(page: Int, x: Float, y: Float): Boolean {
        if (s.tool !in polyTools) return false
        if (polyPage != page) { vertices.clear(); polyPage = page }
        vertices += x to y
        finishPoly()
        return true
    }

    fun finishPoly() {
        val t = s.tool ?: return
        val kind = when (t) { ReaderTool.POLYGON -> AnnotKind.POLYGON; ReaderTool.POLYLINE -> AnnotKind.POLYLINE; ReaderTool.CLOUD -> AnnotKind.CLOUD; else -> return }
        // a double tap often repeats the last vertex: drop near-duplicates
        val pts = ArrayList<Pair<Float, Float>>()
        vertices.forEach { p -> if (pts.isEmpty() || abs(pts.last().first - p.first) + abs(pts.last().second - p.second) > 1.5f) pts += p }
        val need = if (kind == AnnotKind.POLYLINE) 2 else 3
        val page = polyPage
        vertices.clear(); polyPage = -1
        if (pts.size < need || page < 0) return
        val a = s.newAnnot(kind, page, points = pts).copy(
            fillColor = if (kind == AnnotKind.POLYLINE) null else fillColor,
            cloud = if (kind == AnnotKind.CLOUD) cloudIntensity else 0,
        )
        cx.addNew(a)
    }

    fun undoVertex() { if (vertices.isNotEmpty()) vertices.removeAt(vertices.lastIndex); if (vertices.isEmpty()) polyPage = -1 }
    fun cancelPoly() { vertices.clear(); polyPage = -1 }

    /** Whether a drag starting at [p] (unrotated screen) on [page] belongs to us. */
    fun wantsDrag(page: Int, g: PageGeometry, p: Offset): Boolean {
        val t = s.tool
        if ((t == null || t == ReaderTool.SELECT) && multi.active && multi.page == page && !multi.busy) {
            val b = multi.bounds()
            if (b != null && g.pdfRectToScreen(b).inflate(10f).contains(p)) return true
        }
        return t == ReaderTool.SELECT || t == ReaderTool.SNAPSHOT || t == ReaderTool.REPLACE || t == ReaderTool.VIDEO
    }

    fun dragStart(page: Int, g: PageGeometry, p: Offset) {
        val t = s.tool
        val b = multi.bounds()
        dragMode = when {
            (t == null || t == ReaderTool.SELECT) && multi.active && multi.page == page && b != null && g.pdfRectToScreen(b).inflate(10f).contains(p) -> DragMode.MOVE
            t == ReaderTool.SELECT -> DragMode.LASSO
            t == ReaderTool.SNAPSHOT -> DragMode.SNAPSHOT
            t == ReaderTool.REPLACE -> DragMode.REPLACE
            t == ReaderTool.VIDEO -> DragMode.VIDEO_BOX
            else -> null
        }
        dragPts.clear(); dragPts += p; dragPage = page
    }

    fun isDragging(page: Int) = dragMode != null && dragPage == page

    fun dragMove(page: Int, p: Offset) {
        if (!isDragging(page)) return
        if (dragMode == DragMode.LASSO) { val l = dragPts.last(); if (abs(l.x - p.x) + abs(l.y - p.y) >= 3f) dragPts += p }
        else { if (dragPts.size > 1) dragPts[1] = p else dragPts += p }
    }

    /** Screen rect of a two-point drag. */
    val dragRect: Rect? get() = if (dragPts.size >= 2) Rect(minOf(dragPts[0].x, dragPts[1].x), minOf(dragPts[0].y, dragPts[1].y), maxOf(dragPts[0].x, dragPts[1].x), maxOf(dragPts[0].y, dragPts[1].y)) else null

    /** Live offset (screen px) of a multi-selection move. */
    val moveOffset: Offset get() = if (dragMode == DragMode.MOVE && dragPts.size >= 2) dragPts[1] - dragPts[0] else Offset.Zero

    fun dragEnd(page: Int, g: PageGeometry) {
        val mode = dragMode ?: return
        val pts = dragPts.toList(); val rect = dragRect
        dragMode = null; dragPts.clear(); dragPage = -1
        when (mode) {
            DragMode.LASSO -> {
                val poly = if (pts.size >= 3 && lassoArea(pts) > 400f) pts.map { g.screenToPdf(it.x, it.y) }
                else rect?.let { r -> listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft).map { g.screenToPdf(it.x, it.y) } } ?: return
                multi.selectIn(page, poly)
            }
            DragMode.MOVE -> {
                if (pts.size < 2) return
                val (x0, y0) = g.screenToPdf(pts[0].x, pts[0].y); val (x1, y1) = g.screenToPdf(pts[1].x, pts[1].y)
                multi.moveBy(x1 - x0, y1 - y0)
            }
            DragMode.SNAPSHOT -> rect?.takeIf { it.width > 8f && it.height > 8f }?.let { takeSnapshot(page, g.screenRectToPdf(it)) }
            DragMode.VIDEO_BOX -> rect?.let { r ->
                val (x, y) = g.screenToPdf(r.center.x, r.center.y)
                // a tiny drag is a tap: default box
                val box = if (r.width > 24f && r.height > 24f) PageGeometry.normPdf(g.screenRectToPdf(r)) else null
                placeVideoOrPick(Tap2(ReaderTool.VIDEO, page, x, y, box))
            }
            DragMode.REPLACE -> rect?.let { r ->
                val pr = g.screenRectToPdf(r)
                s.scope.launch {
                    val boxes = s.charBoxes(page); val text = s.pageText(page)
                    val range = TextSelectionLogic.charsInRect(boxes, pr) ?: return@launch
                    prompt = Prompt2(AnnotKind.REPLACE, page, TextSelectionLogic.rects(boxes, range.first, range.last), TextSelectionLogic.text(text, range.first, range.last, boxes))
                }
            }
        }
    }

    fun dragCancel() { dragMode = null; dragPts.clear(); dragPage = -1 }

    private fun lassoArea(p: List<Offset>): Float {
        var a = 0f
        for (i in p.indices) { val q = p[i]; val r = p[(i + 1) % p.size]; a += q.x * r.y - r.x * q.y }
        return abs(a / 2f)
    }

    // =============== creation from dialogs ===============

    fun confirmPrompt(p: Prompt2, text: String) {
        prompt = null
        if (p.rects.isEmpty()) return
        s.addAnnot(s.newAnnot(p.kind, p.page, rects = p.rects, text = text, color = s.color))
    }

    /** A picked / recorded file becomes a pending attachment, sound or video at the tap. */
    fun addMedia(tap: Tap2, file: File, displayName: String) {
        val kind = when (tap.tool) { ReaderTool.SOUND -> AnnotKind.SOUND; ReaderTool.VIDEO -> AnnotKind.VIDEO; else -> AnnotKind.FILE_ATTACH }
        val sz = 22f
        val r = RectF(tap.x - sz / 2, tap.y + sz / 2, tap.x + sz / 2, tap.y - sz / 2)
        s.addAnnot(s.newAnnot(kind, tap.page, rects = listOf(r), text = displayName).copy(filePath = file.absolutePath, mime = MediaAnnots.mimeOf(file.name)))
    }

    /** Video tool: place the already picked video, or open the picker and place it when it returns. */
    private fun placeVideoOrPick(tap: Tap2) {
        val pv = s.video.picked
        if (pv != null && pv.file.isFile) s.video.place(pv, tap.page, tap.rect, tap.x, tap.y)
        else { pendingTap = tap; launchVideo() }
    }

    /** Persistent folder for media picked or recorded before the document is saved. */
    fun mediaDir(): File = File(s.context.filesDir, "annot2_media").apply { mkdirs() }

    // =============== open / play ===============

    /** Open the selected attachment / sound / video annotation (pending: its file, stored: extracted). */
    fun openSelected() {
        val ed = s.edit
        // a native /Sound or a Screen audio clip of another PDF has no editable model: play it directly
        (ed.target as? AnnotTarget.Stored)?.e?.takeIf { it.subtype == "Sound" || (it.subtype == "Screen" && it.video == null) }?.let { e ->
            val f = s.file ?: return
            s.scope.launch {
                val r = runCatching { MediaAnnots.extractAt(f, s.password, e.page, e.index, File(s.context.cacheDir, "annot2_open")) }.getOrNull()
                if (r != null) playing = r.first else message = s.context.getString(com.smartdocs.editor.R.string.cx_no_audio)
            }
            return
        }
        val a = ed.current() ?: return
        // videos play inside the page (never in another app)
        if (a.kind == AnnotKind.VIDEO) { s.video.playSelected(); return }
        s.scope.launch {
            val pair: Pair<File, String>? = when (val t = ed.target) {
                is AnnotTarget.Pending -> a.filePath?.let(::File)?.takeIf { it.isFile }?.let { it to a.mime }
                is AnnotTarget.Stored -> s.file?.let { f -> runCatching { MediaAnnots.extractAt(f, s.password, t.e.page, t.e.index, File(s.context.cacheDir, "annot2_open")) }.getOrNull() }
                null -> null
            }
            val (file, mime) = pair ?: return@launch
            val m = mime.ifBlank { MediaAnnots.mimeOf(file.name) }
            if (a.kind == AnnotKind.SOUND || m.startsWith("audio/")) playing = file
            else MediaIntents.view(s.context, s.app, file, m)       // generic file attachments only
        }
    }

    // =============== snapshot ===============

    private fun takeSnapshot(page: Int, r: RectF) {
        val session = s.session ?: return
        s.scope.launch {
            busy = true
            try {
                val (pw, ph) = s.pageSize(page)
                val n = PageGeometry.normPdf(r)
                val rw = (n.right - n.left).coerceAtLeast(1f); val rh = (n.top - n.bottom).coerceAtLeast(1f)
                val k = minOf(4f, 4096f / maxOf(rw, rh))          // up to 4 px per point (~288 dpi)
                val clip = RectF(n.left / pw, (ph - n.top) / ph, n.right / pw, (ph - n.bottom) / ph)
                val bmp = session.renderRegion(page, (pw * k).toInt().coerceAtLeast(1), (ph * k).toInt().coerceAtLeast(1), clip, false)
                val file = withContext(Dispatchers.IO) {
                    s.app.documents.newTemp("snapshot", "png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
                MediaIntents.copyImage(s.context, s.app, file)
                snapshot = Snapshot2(file, bmp)
            } catch (e: Exception) { s.error = e.message ?: e.toString() } finally { busy = false }
        }
    }

    // =============== handwriting ===============

    /** Start handwriting recognition on the ink of the current selection, or else on the page's pending ink. */
    fun startHandwriting(page: Int = s.currentPage) {
        if (multi.active) {
            val ids = multi.pendingAnnots().filter { it.kind == AnnotKind.INK }.map { it.id }
            val st = multi.stored.filter { it.subtype == "Ink" }
            if (ids.isNotEmpty() || st.isNotEmpty()) { handwriting = Handwriting2(multi.page, ids, st); return }
        }
        val ed = s.edit
        when (val t = ed.target) {
            is AnnotTarget.Pending -> if (ed.current()?.kind == AnnotKind.INK) { handwriting = Handwriting2(t.page, listOf(t.id), emptyList()); return }
            is AnnotTarget.Stored -> if (t.e.subtype == "Ink") { handwriting = Handwriting2(t.page, emptyList(), listOf(t.e)); return }
            null -> {}
        }
        val inks = s.annots.filter { it.page == page && it.kind == AnnotKind.INK }.map { it.id }
        if (inks.isNotEmpty()) handwriting = Handwriting2(page, inks, emptyList()) else message = "no_ink"
    }

    /** Strokes (PDF space) of a handwriting request. */
    suspend fun strokesOf(req: Handwriting2): List<List<Pair<Float, Float>>> {
        val pend = s.annots.filter { it.id in req.pendingIds }.flatMap { it.paths }
        val f = s.file
        val stored = if (f == null) emptyList() else req.stored.flatMap { e -> runCatching { AnnotationWriter.extract(f, e.page, e.index, s.password) }.getOrNull()?.paths ?: emptyList() }
        return pend + stored
    }

    /** Replace the ink by a free-text annotation at its bounds. */
    fun applyHandwriting(req: Handwriting2, strokes: List<List<Pair<Float, Float>>>, text: String) {
        handwriting = null
        if (text.isBlank() || strokes.isEmpty()) return
        val pts = strokes.flatten()
        val l = pts.minOf { it.first }; val r = pts.maxOf { it.first }; val b = pts.minOf { it.second }; val t = pts.maxOf { it.second }
        val lines = text.split('\n')
        val fs = ((t - b) / lines.size * 0.75f).coerceIn(8f, 72f)
        val rtl = text.any { it in '؀'..'ۿ' }
        val needW = (lines.maxOf { it.length } * fs * 0.58f + 12f)
        val w = maxOf(r - l + 8f, needW)
        val h = maxOf(t - b + 8f, lines.size * fs * 1.3f + 8f)
        val rect = if (rtl) RectF(r + 4f - w, t + 4f, r + 4f, t + 4f - h) else RectF(l - 4f, t + 4f, l - 4f + w, t + 4f - h)
        val ids = req.pendingIds.toSet()
        val ft = s.newAnnot(AnnotKind.FREETEXT, req.page, rects = listOf(rect), text = text, fontSize = fs, color = s.annots.firstOrNull { it.id in ids }?.color ?: 0x212121)
        s.batchAnnots { list -> list.removeAll { it.id in ids }; list += ft }
        multi.clear(); s.edit.clear()
        if (req.stored.isNotEmpty()) s.scope.launch {
            runCatching { fileOp { src, out, pw -> CommentStore.deleteMany(src, out, pw, mapOf(req.page to req.stored.map { it.index })) } }
                .onFailure { s.error = it.message }
        }
    }

    // =============== file operations on the open document ===============

    /**
     * Change stored annotations: [op] writes src → out, then the session is swapped and the user's document
     * written back (same pattern as deleteExisting). Pending annotations stay in memory untouched.
     */
    suspend fun fileOp(op: suspend (src: File, out: File, pw: String?) -> Unit) {
        s.saveMutex.withLock {
            val f = s.file ?: return
            withContext(NonCancellable) {
                val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.annot2.pdf")
                tmp.delete()
                op(f, tmp, s.password)
                if (!tmp.exists() || tmp.length() == 0L) throw IllegalStateException("empty result")
                s.swapFile(tmp, s.context.getString(com.smartdocs.editor.R.string.hi_comments_file))
                s.writeBackResult = runCatching { s.app.documents.writeBack(f) }.getOrNull()
            }
        }
        reloadComments()
    }

    /** Run [block] showing the busy indicator; errors go to the reader's snackbar. */
    fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        s.scope.launch {
            busy = true
            try { block() } catch (e: Exception) { s.error = e.message ?: e.toString() } finally { busy = false }
        }
    }

    /** Bounds (PDF) of the pending annotations with [ids] (used for frames). */
    fun boundsOf(ids: Collection<Long>): RectF? {
        val rs = s.annots.filter { it.id in ids }.map { annotBounds(it) }
        if (rs.isEmpty()) return null
        return RectF(rs.minOf { it.left }, rs.maxOf { it.top }, rs.maxOf { it.right }, rs.minOf { it.bottom })
    }

    fun pendingOf(id: Long): Annot? = s.annots.firstOrNull { it.id == id }

    companion object {
        /** Tools whose toolbar click goes through [pick]. */
        val OWN_TOOLS = setOf(ReaderTool.POLYGON, ReaderTool.POLYLINE, ReaderTool.CLOUD, ReaderTool.CARET, ReaderTool.REPLACE,
            ReaderTool.ATTACH, ReaderTool.SOUND, ReaderTool.VIDEO, ReaderTool.SNAPSHOT, ReaderTool.SELECT, ReaderTool.TICKMARK)
    }
}
