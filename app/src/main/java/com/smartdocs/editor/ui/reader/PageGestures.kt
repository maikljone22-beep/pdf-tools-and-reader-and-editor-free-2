package com.smartdocs.editor.ui.reader

import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** In-progress drag on a page. Points are in unrotated screen space of that page. */
class GestureDraft {
    var active by mutableStateOf(false)
    var tool: ReaderTool? = null
    var start = Offset.Zero
    var current by mutableStateOf(Offset.Zero)
    val points = ArrayList<Offset>()
    val pressures = ArrayList<Float>()
    var version by mutableStateOf(0)       // bumped on each ink point so the canvas redraws
    var handle: PlacementHandle = PlacementHandle.NONE
    var placementStart: Placement? = null
    /** Frame (unrotated screen space) of the annotation being moved / resized, null otherwise. */
    var annotFrame: Rect? = null

    fun begin(t: ReaderTool?, at: Offset) { tool = t; start = at; current = at; points.clear(); pressures.clear(); points += at; active = true; version++ }
    fun move(to: Offset, pressure: Float = 1f) { current = to; points += to; pressures += pressure; version++ }
    fun end() { active = false; tool = null; handle = PlacementHandle.NONE; placementStart = null; annotFrame = null; version++ }
    val rect: Rect get() = Rect(min(start.x, current.x), min(start.y, current.y), max(start.x, current.x), max(start.y, current.y))
}

enum class PlacementHandle { NONE, MOVE, TL, TR, BL, BR, ROTATE }

/** Which part of a placement frame is under [p] (unrotated screen space). */
fun hitPlacement(p: Placement, g: PageGeometry, at: Offset): PlacementHandle {
    val s = g.pdfRectToScreen(p.rect)
    val q = unrotatePoint(at, s.center, p.rotation)
    val r = HANDLE_R * 1.8f
    fun near(o: Offset) = hypot(q.x - o.x, q.y - o.y) <= r
    if (near(rotateHandle(s))) return PlacementHandle.ROTATE
    val h = placementHandles(s)
    if (near(h[0])) return PlacementHandle.TL
    if (near(h[1])) return PlacementHandle.TR
    if (near(h[2])) return PlacementHandle.BL
    if (near(h[3])) return PlacementHandle.BR
    if (s.inflate(8f).contains(q)) return PlacementHandle.MOVE
    return PlacementHandle.NONE
}

/** Apply a drag to a placement frame and return the updated one. */
fun dragPlacement(orig: Placement, handle: PlacementHandle, g: PageGeometry, from: Offset, to: Offset): Placement {
    val s = g.pdfRectToScreen(orig.rect)
    val d = Offset(to.x - from.x, to.y - from.y)
    return when (handle) {
        PlacementHandle.MOVE -> orig.copy(rect = g.screenRectToPdf(s.translate(d)))
        PlacementHandle.ROTATE -> {
            val c = s.center
            val a0 = atan2((from.y - c.y).toDouble(), (from.x - c.x).toDouble())
            val a1 = atan2((to.y - c.y).toDouble(), (to.x - c.x).toDouble())
            orig.copy(rotation = (orig.rotation + Math.toDegrees(a1 - a0).toFloat()) % 360f)
        }
        else -> {
            val aspect = s.width / s.height.coerceAtLeast(1f)
            val minW = 24f
            var l = s.left; var t = s.top; var r = s.right; var b = s.bottom
            val q = unrotatePoint(to, s.center, orig.rotation)
            when (handle) {
                PlacementHandle.BR -> { r = max(l + minW, q.x); b = t + (r - l) / aspect }
                PlacementHandle.BL -> { l = min(r - minW, q.x); b = t + (r - l) / aspect }
                PlacementHandle.TR -> { r = max(l + minW, q.x); t = b - (r - l) / aspect }
                PlacementHandle.TL -> { l = min(r - minW, q.x); t = b - (r - l) / aspect }
                else -> {}
            }
            orig.copy(rect = g.screenRectToPdf(Rect(l, t, r, b)))
        }
    }
}

/** Convert a finished draft into an annotation (or null when nothing should be created). */
fun draftToAnnot(state: ReaderState, draft: GestureDraft, page: Int, g: PageGeometry, pressure: Boolean): Annot? {
    val tool = draft.tool ?: return null
    val rect = draft.rect
    val tooSmall = rect.width < 3f && rect.height < 3f
    return when (tool) {
        ReaderTool.INK -> {
            val pts = simplify(draft.points).map { g.screenToPdf(it.x, it.y) }
            if (pts.isEmpty()) return null
            val avgP = if (pressure && draft.pressures.isNotEmpty()) draft.pressures.average().toFloat().coerceIn(0.2f, 1.5f) else 1f
            state.newAnnot(AnnotKind.INK, page, paths = listOf(pts)).copy(strokeWidth = (state.thickness * (if (pressure) 0.6f + avgP * 0.8f else 1f)))
        }
        ReaderTool.LINE, ReaderTool.ARROW -> {
            if (tooSmall) return null
            val a = g.screenToPdf(draft.start.x, draft.start.y); val b = g.screenToPdf(draft.current.x, draft.current.y)
            state.newAnnot(if (tool == ReaderTool.LINE) AnnotKind.LINE else AnnotKind.ARROW, page, points = listOf(a, b))
        }
        ReaderTool.RECT, ReaderTool.OVAL -> {
            if (tooSmall) return null
            state.newAnnot(if (tool == ReaderTool.RECT) AnnotKind.RECT else AnnotKind.OVAL, page, rects = listOf(g.screenRectToPdf(rect)))
        }
        else -> null
    }
}

/** Drop points closer than 1.5px to the previous kept one. */
fun simplify(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val out = ArrayList<Offset>(); out += points.first()
    for (p in points) { val l = out.last(); if (hypot(p.x - l.x, p.y - l.y) >= 1.5f) out += p }
    if (out.last() != points.last()) out += points.last()
    return out
}

/** Ids of pending INK annotations on [page] that pass within [radiusPt] points of PDF point (x, y). */
fun inkHits(annots: List<Annot>, page: Int, x: Float, y: Float, radiusPt: Float): List<Long> =
    annots.filter { it.page == page && it.kind == AnnotKind.INK }.filter { a ->
        a.paths.any { stroke -> stroke.any { (px, py) -> hypot(px - x, py - y) <= radiusPt + a.strokeWidth } }
    }.map { it.id }

/** Pending (not yet saved) annotation under a PDF point, for tap-to-delete / tap-to-edit. */
fun annotAt(annots: List<Annot>, page: Int, x: Float, y: Float, tolerance: Float = 6f): Annot? =
    annots.lastOrNull { a ->
        a.page == page && when (a.kind) {
            AnnotKind.INK -> a.paths.any { s -> s.any { (px, py) -> hypot(px - x, py - y) <= tolerance + a.strokeWidth } }
            AnnotKind.LINE, AnnotKind.ARROW -> a.points.size >= 2 && distToSegment(x, y, a.points[0], a.points[1]) <= tolerance + a.strokeWidth
            AnnotKind.POLYGON, AnnotKind.CLOUD, AnnotKind.POLYLINE -> polyHit(a, x, y, tolerance)
            AnnotKind.NOTE -> a.rects.firstOrNull()?.let { r -> x >= r.left && x <= r.left + 20f && y <= r.top && y >= r.top - 20f } ?: false
            else -> a.rects.any { raw -> val r = PageGeometry.normPdf(raw); x >= r.left - tolerance && x <= r.right + tolerance && y <= r.top + tolerance && y >= r.bottom - tolerance }
        }
    }

/** Near an edge of a polygon / polyline, or inside a closed one. */
private fun polyHit(a: Annot, x: Float, y: Float, tol: Float): Boolean {
    val p = a.points; if (p.size < 2) return false
    val closed = a.kind != AnnotKind.POLYLINE
    val edges = if (closed) p.indices.map { p[it] to p[(it + 1) % p.size] } else (0 until p.size - 1).map { p[it] to p[it + 1] }
    val reach = tol + a.strokeWidth + if (a.kind == AnnotKind.CLOUD) 6f else 0f
    if (edges.any { (s, e) -> distToSegment(x, y, s, e) <= reach }) return true
    if (!closed) return false
    var inside = false; var j = p.size - 1
    for (i in p.indices) {
        val (xi, yi) = p[i]; val (xj, yj) = p[j]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / ((yj - yi).takeIf { it != 0f } ?: 1e-6f) + xi) inside = !inside
        j = i
    }
    return inside
}

private fun distToSegment(x: Float, y: Float, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
    val dx = b.first - a.first; val dy = b.second - a.second
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((x - a.first) * dx + (y - a.second) * dy) / len2).coerceIn(0f, 1f)
    return hypot(x - (a.first + t * dx), y - (a.second + t * dy))
}

/** Default rect for a tapped text-like annotation: anchored at the tap, sized for the text. */
fun textRectAt(x: Float, y: Float, text: String, fontSize: Float, pageW: Float, rtl: Boolean): RectF {
    val lines = text.split('\n')
    val w = ((lines.maxOfOrNull { it.length } ?: 1) * fontSize * 0.58f + 12f).coerceIn(40f, pageW * 0.9f)
    val h = lines.size * fontSize * 1.3f + 8f
    val left = if (rtl) (x - w).coerceAtLeast(2f) else x.coerceAtMost(pageW - w - 2f).coerceAtLeast(2f)
    return RectF(left, y, left + w, y - h)
}

fun formatDistance(pt: Float): String {
    val cm = PageGeometry.ptToCm(pt); val mm = PageGeometry.ptToMm(pt); val inch = PageGeometry.ptToInch(pt)
    return String.format(java.util.Locale.US, "%.2f cm · %.0f mm · %.2f in", cm, mm, inch)
}

fun approxEq(a: Float, b: Float, eps: Float = 0.5f) = abs(a - b) < eps
