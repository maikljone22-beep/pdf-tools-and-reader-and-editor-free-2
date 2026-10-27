package com.smartdocs.editor.ui.reader

import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.AnnotationWriter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What is selected on the page for editing: an annotation stored in the file, or a pending (unsaved) one. */
sealed interface AnnotTarget {
    val page: Int
    data class Stored(val e: AnnotationWriter.Existing) : AnnotTarget { override val page: Int get() = e.page }
    data class Pending(val id: Long, override val page: Int) : AnnotTarget
}

/**
 * Selection and editing of annotations already on the page.
 *
 * A stored annotation is only read until the user changes it. On the first change it is converted: read back
 * with [AnnotationWriter.extract], removed from the working file (delete → swap, like deleteExisting) and added
 * as a pending [Annot]; from then on every edit only touches that pending annotation and the normal save
 * commits it. A backup of the file taken before the first conversion is restored when the user discards.
 */
class AnnotEditor(private val s: ReaderState) {
    var target by mutableStateOf<AnnotTarget?>(null); private set
    /** Read-only copy of the selected stored annotation (null while loading or when its kind cannot be edited). */
    var storedAnnot by mutableStateOf<Annot?>(null); private set
    var storedLoading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var editingText by mutableStateOf(false)
    /** (page, index) of stored annotations taken out of the file for editing since the last save. */
    val replaced = mutableSetOf<Pair<Int, Int>>()
    private var backup: File? = null
    private var discard = false

    val hasBackup: Boolean get() = backup != null
    /** The file as it was before the first conversion (the unified history keeps it as the version before the save). */
    internal val backupFile: File? get() = backup

    fun current(): Annot? = when (val t = target) {
        is AnnotTarget.Pending -> s.annots.firstOrNull { it.id == t.id }
        is AnnotTarget.Stored -> storedAnnot
        null -> null
    }

    /** PDF-space frame of the selection (top = larger y). */
    fun bounds(): RectF? = current()?.let(::annotBounds) ?: (target as? AnnotTarget.Stored)?.e?.rect?.let { PageGeometry.normPdf(it) }

    /** Whether the selection can be dragged / resized right now. */
    val movable: Boolean get() = !busy && current() != null

    fun clear() { target = null; storedAnnot = null; storedLoading = false; editingText = false }

    /** Drop a pending selection whose annotation disappeared (undo / redo / removal). */
    fun validate() { val t = target; if (t is AnnotTarget.Pending && s.annots.none { it.id == t.id }) clear() }

    fun selectPending(a: Annot) { target = AnnotTarget.Pending(a.id, a.page); storedAnnot = null; storedLoading = false; s.selection = null }

    fun selectStored(e: AnnotationWriter.Existing) {
        val t = AnnotTarget.Stored(e)
        target = t; storedAnnot = null; storedLoading = true; s.selection = null
        val f = s.file ?: return
        s.scope.launch {
            // media = false: a selected video is not copied out of the PDF just to show the edit bar
            val a = runCatching { AnnotationWriter.extract(f, e.page, e.index, s.password, media = false) }.getOrNull()
            if (target == t) { storedAnnot = a; storedLoading = false }
        }
    }

    /** Select the annotation under a PDF point of [page]; pending ones win since they are drawn on top. */
    fun selectAt(page: Int, x: Float, y: Float, tolerancePt: Float): Boolean {
        annotAt(s.annots, page, x, y, tolerancePt)?.let {
            // grouped annotations are selected (and moved) together through the multi-selection
            if (s.annot2.multi.trySelectGroup(it, null)) { clear(); return true }
            selectPending(it); return true
        }
        storedAt(page, x, y, tolerancePt)?.let {
            if (s.annot2.multi.trySelectGroup(null, it)) { clear(); return true }
            selectStored(it); return true
        }
        return false
    }

    /** Stored annotation under a point: the smallest rect that contains it (nested markups pick the inner one). */
    fun storedAt(page: Int, x: Float, y: Float, tol: Float): AnnotationWriter.Existing? =
        s.existing.filter { it.page == page }.filter { e ->
            val r = PageGeometry.normPdf(e.rect)
            x >= r.left - tol && x <= r.right + tol && y <= r.top + tol && y >= r.bottom - tol
        }.minByOrNull { e -> abs((e.rect.right - e.rect.left) * (e.rect.top - e.rect.bottom)) }

    /** Apply [change] to the selection, converting a stored annotation into a pending one first. */
    fun edit(change: (Annot) -> Annot) {
        when (val t = target) {
            is AnnotTarget.Pending -> s.annots.firstOrNull { it.id == t.id }?.let { s.replaceAnnot(change(it)) }
            is AnnotTarget.Stored -> {
                if (busy || storedAnnot == null) return
                s.scope.launch {
                    val a = convert(t.e) ?: return@launch
                    target = AnnotTarget.Pending(a.id, a.page); storedAnnot = null
                    s.replaceAnnot(change(a))
                }
            }
            null -> {}
        }
    }

    private suspend fun convert(e: AnnotationWriter.Existing): Annot? {
        busy = true
        try {
            return s.saveMutex.withLock {
                withContext(NonCancellable) {
                    val f = s.file ?: return@withContext null
                    // indices shift after every swap: find the annotation again in a fresh listing
                    val now = AnnotationWriter.list(f, s.password)
                    val match = now.firstOrNull { it.page == e.page && it.subtype == e.subtype && close(it.rect, e.rect) && it.index == e.index }
                        ?: now.firstOrNull { it.page == e.page && it.subtype == e.subtype && close(it.rect, e.rect) }
                        ?: return@withContext null
                    val a = AnnotationWriter.extract(f, match.page, match.index, s.password) ?: return@withContext null
                    if (backup == null) {
                        val b = File(f.parentFile, ".${f.nameWithoutExtension}.annotedit.pdf")
                        f.copyTo(b, overwrite = true); backup = b
                    }
                    val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.saving.pdf")
                    AnnotationWriter.delete(f, match.page, match.index, tmp, s.password)
                    s.swapFile(tmp, record = false)      // not an undo step: the annotation lives on as a pending one
                    replaced += match.page to match.index
                    s.adoptConverted(a)
                    a
                }
            }
        } catch (ex: Exception) {
            s.error = ex.message ?: ex.toString(); return null
        } finally { busy = false }
    }

    /** Back up the working file before stored annotations are taken out of it (restored on "discard"). */
    internal fun ensureBackup() {
        if (backup != null) return
        val f = s.file ?: return
        val b = File(f.parentFile, ".${f.nameWithoutExtension}.annotedit.pdf")
        f.copyTo(b, overwrite = true); backup = b
    }

    private fun close(a: RectF, b: RectF) = abs(a.left - b.left) < 0.5f && abs(a.right - b.right) < 0.5f && abs(a.top - b.top) < 0.5f && abs(a.bottom - b.bottom) < 0.5f

    fun transform(from: RectF, to: RectF) = edit { mapAnnot(it, from, to) }

    fun setColor(c: Int) = edit { it.copy(color = c) }
    fun setOpacity(o: Float) = edit { it.copy(opacity = o.coerceIn(0.1f, 1f)) }
    fun setThickness(w: Float) = edit { it.copy(strokeWidth = w.coerceIn(0.5f, 40f)) }
    fun setText(text: String) = edit { a ->
        if (a.kind != AnnotKind.FREETEXT && a.kind != AnnotKind.STAMP_TEXT) return@edit a.copy(text = text)
        // free text: grow the box so every line stays visible
        val r = PageGeometry.normPdf(a.rects.firstOrNull() ?: return@edit a.copy(text = text))
        val lines = text.split('\n')
        val needH = lines.size * a.fontSize * 1.3f + 8f
        val needW = (lines.maxOfOrNull { it.length } ?: 1) * a.fontSize * 0.58f + 12f
        val rtl = text.any { it in '؀'..'ۿ' }
        val w = max(r.right - r.left, needW); val h = max(r.top - r.bottom, needH)
        val nr = if (rtl) RectF(r.right - w, r.top, r.right, r.top - h) else RectF(r.left, r.top, r.left + w, r.top - h)
        a.copy(text = text, rects = listOf(nr))
    }

    fun delete() {
        when (val t = target) {
            is AnnotTarget.Pending -> { s.removeAnnot(t.id); clear() }
            is AnnotTarget.Stored -> s.scope.launch { s.deleteExisting(t.e); clear() }
            null -> {}
        }
    }

    /** Duplicate the selection as a new pending annotation slightly offset, and select the copy. */
    fun copy() {
        val a = current() ?: return
        val t = target
        if (a.kind == AnnotKind.VIDEO && a.filePath == null && t is AnnotTarget.Stored) {
            // the preview of a stored video has no file: read it fully before duplicating
            val f = s.file ?: return
            s.scope.launch {
                busy = true
                try { AnnotationWriter.extract(f, t.e.page, t.e.index, s.password)?.let { duplicate(it) } } finally { busy = false }
            }
            return
        }
        duplicate(a)
    }

    private fun duplicate(a: Annot) {
        val d = 12f
        val c = mapAnnot(a, annotBounds(a), annotBounds(a).let { RectF(it.left + d, it.top - d, it.right + d, it.bottom - d) })
            .copy(id = System.nanoTime(), created = System.currentTimeMillis())
        s.addAnnot(c); selectPending(c)
    }

    /** After a successful save: the file now holds everything, the backup is no longer needed. */
    fun committed() {
        backup?.let { runCatching { it.delete() } }; backup = null; replaced.clear()
        if (target is AnnotTarget.Pending) clear()
    }

    fun discardOnClose() { discard = true }

    /** (backup, file) to restore after the session closed, when the user discarded the edits. */
    fun restoreOnClose(): Pair<File, File>? {
        val b = backup ?: return null; val f = s.file ?: return null
        return if (discard) b to f else null
    }
}

// ================= geometry =================

/** Bounds of an annotation in PDF space (top = larger y), including stroke width. */
fun annotBounds(a: Annot): RectF {
    fun ofPoints(pts: List<Pair<Float, Float>>, pad: Float): RectF {
        if (pts.isEmpty()) return RectF()
        var l = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = Float.MAX_VALUE; var t = -Float.MAX_VALUE
        pts.forEach { (x, y) -> l = min(l, x); r = max(r, x); b = min(b, y); t = max(t, y) }
        return RectF(l - pad, t + pad, r + pad, b - pad)
    }
    return when (a.kind) {
        AnnotKind.INK -> ofPoints(a.paths.flatten(), a.strokeWidth / 2 + 2f)
        AnnotKind.LINE, AnnotKind.ARROW -> ofPoints(a.points, a.strokeWidth / 2 + 4f)
        AnnotKind.POLYGON, AnnotKind.POLYLINE -> ofPoints(a.points, a.strokeWidth / 2 + 2f)
        AnnotKind.CLOUD -> ofPoints(a.points, com.smartdocs.editor.core.annot.CloudPath.margin(a.cloud.coerceIn(1, 2), a.strokeWidth))
        AnnotKind.NOTE -> a.rects.firstOrNull()?.let { r -> val n = PageGeometry.normPdf(r); RectF(n.left, n.top, n.left + 20f, n.top - 20f) } ?: RectF()
        else -> {
            if (a.rects.isEmpty()) return RectF()
            val n = a.rects.map { PageGeometry.normPdf(it) }
            RectF(n.minOf { it.left }, n.maxOf { it.top }, n.maxOf { it.right }, n.minOf { it.bottom })
        }
    }
}

/** Map every coordinate of [a] from rect [from] to rect [to] (both PDF space): moves and scales. */
fun mapAnnot(a: Annot, from: RectF, to: RectF): Annot {
    val f = PageGeometry.normPdf(from); val t = PageGeometry.normPdf(to)
    val fw = f.right - f.left; val fh = f.top - f.bottom
    val sx = if (fw > 0.01f) (t.right - t.left) / fw else 1f
    val sy = if (fh > 0.01f) (t.top - t.bottom) / fh else 1f
    fun mx(x: Float) = t.left + (x - f.left) * sx
    fun my(y: Float) = t.bottom + (y - f.bottom) * sy
    fun mr(r: RectF) = PageGeometry.normPdf(RectF(mx(r.left), my(r.top), mx(r.right), my(r.bottom)))
    return a.copy(
        rects = a.rects.map(::mr),
        paths = a.paths.map { st -> st.map { (x, y) -> mx(x) to my(y) } },
        points = a.points.map { (x, y) -> mx(x) to my(y) },
    )
}

/** Text markup and sticky notes follow text / an icon: they can be moved but not resized. */
fun resizable(a: Annot?): Boolean = a != null && a.kind !in setOf(AnnotKind.HIGHLIGHT, AnnotKind.UNDERLINE, AnnotKind.STRIKEOUT, AnnotKind.SQUIGGLY, AnnotKind.NOTE,
    AnnotKind.CARET, AnnotKind.REPLACE, AnnotKind.FILE_ATTACH, AnnotKind.SOUND)

fun hasStroke(a: Annot?): Boolean = a != null && a.kind in setOf(AnnotKind.INK, AnnotKind.LINE, AnnotKind.ARROW, AnnotKind.RECT, AnnotKind.OVAL,
    AnnotKind.POLYGON, AnnotKind.POLYLINE, AnnotKind.CLOUD)

fun hasEditableText(a: Annot?): Boolean = a != null && a.kind in setOf(AnnotKind.FREETEXT, AnnotKind.STAMP_TEXT, AnnotKind.NOTE,
    AnnotKind.HIGHLIGHT, AnnotKind.UNDERLINE, AnnotKind.STRIKEOUT, AnnotKind.SQUIGGLY,
    AnnotKind.CARET, AnnotKind.REPLACE, AnnotKind.FILE_ATTACH, AnnotKind.SOUND, AnnotKind.VIDEO)

/** Screen frame drawn around a selection: never smaller than a comfortable touch target. */
fun frameRect(bounds: RectF, g: PageGeometry): Rect {
    val r = g.pdfRectToScreen(bounds)
    val minS = 28f
    val dx = max(0f, (minS - r.width) / 2); val dy = max(0f, (minS - r.height) / 2)
    return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
}

/** Which part of a selection frame is under [at] (unrotated screen space). */
fun hitFrame(frame: Rect, at: Offset, handles: Boolean): PlacementHandle {
    if (handles) {
        val rad = HANDLE_R * 1.8f
        val h = placementHandles(frame)
        val order = listOf(PlacementHandle.TL, PlacementHandle.TR, PlacementHandle.BL, PlacementHandle.BR)
        h.forEachIndexed { i, o -> if (hypot(at.x - o.x, at.y - o.y) <= rad) return order[i] }
    }
    return if (frame.inflate(8f).contains(at)) PlacementHandle.MOVE else PlacementHandle.NONE
}

/** Frame after dragging [handle] from [from] to [to] (free aspect, minimum size). */
fun dragFrame(o: Rect, handle: PlacementHandle, from: Offset, to: Offset): Rect {
    val dx = to.x - from.x; val dy = to.y - from.y; val m = 16f
    return when (handle) {
        PlacementHandle.MOVE -> o.translate(dx, dy)
        PlacementHandle.TL -> Rect(min(o.left + dx, o.right - m), min(o.top + dy, o.bottom - m), o.right, o.bottom)
        PlacementHandle.TR -> Rect(o.left, min(o.top + dy, o.bottom - m), max(o.right + dx, o.left + m), o.bottom)
        PlacementHandle.BL -> Rect(min(o.left + dx, o.right - m), o.top, o.right, max(o.bottom + dy, o.top + m))
        PlacementHandle.BR -> Rect(o.left, o.top, max(o.right + dx, o.left + m), max(o.bottom + dy, o.top + m))
        else -> o
    }
}

/** Selection frame of an annotation: dashed border + corner handles (same visuals as image placement). */
fun DrawScope.drawAnnotFrame(s: Rect, accent: Color, handles: Boolean) {
    drawRect(accent, s.topLeft, s.size, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
    if (handles) placementHandles(s).forEach { h -> drawCircle(Color.White, HANDLE_R, h); drawCircle(accent, HANDLE_R, h, style = Stroke(2.5f)) }
}
