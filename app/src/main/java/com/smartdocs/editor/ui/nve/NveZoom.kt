package com.smartdocs.editor.ui.nve

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.smartdocs.editor.ui.reader.MAX_ZOOM
import com.smartdocs.editor.ui.reader.MIN_ZOOM
import com.smartdocs.editor.ui.reader.ReaderState
import kotlin.math.max
import kotlin.math.min

/** Zoom presets the reader offers (Nitro / Foxit "Zoom" menu). */
enum class ZoomPreset { FIT_WIDTH, FIT_PAGE, FIT_HEIGHT, ACTUAL, AUTO }

/**
 * Zoom presets, numeric zoom (percent of actual size), marquee zoom and zoom-to-rectangle, on top of PageView's own
 * geometry (published every composition by the PageView hook [bind]). 100 % = the page at its real printed size
 * (1 pt = 1/72 inch on the screen).
 */
object NveZoom {
    /** PageView hook: plain assignments, cheap enough to run on every composition. */
    fun bind(n: NveState, viewport: Size, baseWidth: (Int) -> Float, applyZoom: (Float, Offset, Offset) -> Unit, visible: () -> List<VisiblePage>) {
        if (n.viewport != viewport) n.viewport = viewport
        n.baseWidth = baseWidth; n.applyZoom = applyZoom; n.visiblePages = visible
    }

    private fun pageWidthPt(s: ReaderState, page: Int): Float {
        val (w, h) = s.pageSizes[page] ?: (595f to 842f)
        return if (s.rotation % 180 == 0) w else h
    }
    private fun pageHeightPt(s: ReaderState, page: Int): Float {
        val (w, h) = s.pageSizes[page] ?: (595f to 842f)
        return if (s.rotation % 180 == 0) h else w
    }

    /** Pixels of one point at 100 %. */
    fun pxPerPt(s: ReaderState): Float = s.context.resources.displayMetrics.densityDpi / 72f

    /** Current zoom as a percentage of the actual size. */
    fun percent(s: ReaderState): Float {
        val n = NveState.of(s)
        val w = n.baseWidth(s.currentPage) * s.zoom
        return w / (pageWidthPt(s, s.currentPage) * pxPerPt(s)) * 100f
    }

    /** Range of percentages the reader can show for the current page. */
    fun percentRange(s: ReaderState): ClosedFloatingPointRange<Float> {
        val n = NveState.of(s)
        val unit = n.baseWidth(s.currentPage) / (pageWidthPt(s, s.currentPage) * pxPerPt(s)) * 100f
        return (unit * MIN_ZOOM)..(unit * MAX_ZOOM)
    }

    private fun setZoom(s: ReaderState, z: Float) {
        val n = NveState.of(s)
        val target = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val apply = n.applyZoom
        if (apply == null || s.zoom <= 0f) { s.zoom = target; return }
        apply(target / s.zoom, Offset(n.viewport.width / 2f, n.viewport.height / 2f), Offset.Zero)
    }

    fun setPercent(s: ReaderState, pct: Float) {
        val n = NveState.of(s)
        val base = n.baseWidth(s.currentPage).coerceAtLeast(1f)
        setZoom(s, pct / 100f * pageWidthPt(s, s.currentPage) * pxPerPt(s) / base)
    }

    fun preset(s: ReaderState, p: ZoomPreset) {
        val n = NveState.of(s)
        when (p) {
            ZoomPreset.FIT_WIDTH -> { s.fitPage = false; s.zoom = 1f }
            ZoomPreset.FIT_PAGE -> { s.fitPage = true; s.zoom = 1f }
            ZoomPreset.FIT_HEIGHT -> {
                s.fitPage = false
                val gap = 8f * s.context.resources.displayMetrics.density
                val aspect = pageWidthPt(s, s.currentPage) / pageHeightPt(s, s.currentPage).coerceAtLeast(1f)
                val want = (n.viewport.height - gap * 2) * aspect
                val base = n.baseWidth(s.currentPage).coerceAtLeast(1f)
                s.zoom = (want / base).coerceIn(MIN_ZOOM, MAX_ZOOM)
            }
            ZoomPreset.ACTUAL -> setPercent(s, 100f)
            ZoomPreset.AUTO -> {
                // portrait page in a landscape window: whole page; otherwise fit width (Foxit "automatic")
                val pagePortrait = pageHeightPt(s, s.currentPage) >= pageWidthPt(s, s.currentPage)
                val wide = n.viewport.width > n.viewport.height
                preset(s, if (pagePortrait && wide) ZoomPreset.FIT_PAGE else ZoomPreset.FIT_WIDTH)
            }
        }
    }

    /** Zoom so [r] (viewport px) fills the window ([out]: zoom out by the same ratio instead). */
    fun zoomToRect(s: ReaderState, r: Rect, out: Boolean = false) {
        val n = NveState.of(s)
        val apply = n.applyZoom ?: return
        if (r.width < 8f || r.height < 8f) {
            // a tap: step zoom around that point
            apply(if (out) 0.5f else 2f, r.center, Offset.Zero); return
        }
        val vp = n.viewport
        var zc = min(vp.width / r.width, vp.height / r.height)
        if (out) zc = 1f / zc.coerceAtLeast(1f)
        zc = zc.coerceIn(MIN_ZOOM / s.zoom, MAX_ZOOM / s.zoom)
        val pan = Offset(vp.width / 2f - r.center.x, vp.height / 2f - r.center.y)
        apply(zc, r.center, if (out) Offset.Zero else pan)
    }

    /** Zoom to a rectangle of [page] given in PDF user space (the reader's PDF coordinates, e.g. article beads). */
    fun zoomToPageRect(s: ReaderState, page: Int, left: Float, bottom: Float, right: Float, top: Float) {
        val n = NveState.of(s)
        val vp = n.visiblePages().firstOrNull { it.page == page }
        if (vp == null) { s.jumpTo(page); return }
        val g = geometryOf(s, vp)
        val a = g.pdfToLayout(left, top); val b = g.pdfToLayout(right, bottom)
        zoomToRect(s, Rect(vp.rect.left + min(a.x, b.x), vp.rect.top + min(a.y, b.y), vp.rect.left + max(a.x, b.x), vp.rect.top + max(a.y, b.y)))
    }

    /** Mouse wheel with Ctrl: zoom around the pointer. */
    fun wheel(s: ReaderState, scrollY: Float, at: Offset) {
        val apply = NveState.of(s).applyZoom ?: return
        val f = if (scrollY < 0) 1.15f else 1f / 1.15f
        apply(f, at, Offset.Zero)
    }

    val PERCENTS = listOf(25, 50, 75, 100, 125, 150, 200, 300, 400, 800, 1600)
}
