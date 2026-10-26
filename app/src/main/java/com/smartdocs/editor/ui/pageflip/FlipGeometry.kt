package com.smartdocs.editor.ui.pageflip

import android.graphics.RectF
import com.smartdocs.editor.core.pageflip.PageSide
import com.smartdocs.editor.core.pageflip.SpreadMap
import kotlin.math.max
import kotlin.math.min

/**
 * Page rectangles of a screen in the page-turn host (viewport px, zoom 1, before pan): the page or spread is fitted
 * into the viewport minus [margin]; a spread's pages touch at the spine (book) or keep [gap] between them. A lone
 * page of a spread layout (cover / last page) keeps its side, as in a real book.
 */
class FlipGeometry(
    val map: SpreadMap,
    val pageCount: Int,
    val rtl: Boolean,
    val vw: Float,
    val vh: Float,
    val margin: Float,
    val gap: Float,
    private val size: (Int) -> Pair<Float, Float>,
) {
    fun rects(item: Int): List<Triple<Int, RectF, PageSide>> {
        val sides = map.sides(item, pageCount, rtl)
        if (sides.isEmpty() || vw <= 0f || vh <= 0f) return emptyList()
        val aw = (vw - 2 * margin).coerceAtLeast(1f); val ah = (vh - 2 * margin).coerceAtLeast(1f)
        fun aspect(p: Int): Float = size(p).let { (w, h) -> (w / h.coerceAtLeast(1f)).coerceIn(0.1f, 10f) }
        if (!map.paired) {
            val (p, _) = sides[0]
            val a = aspect(p)
            val w = min(aw, ah * a); val h = w / a
            val l = (vw - w) / 2f; val t = (vh - h) / 2f
            return listOf(Triple(p, RectF(l, t, l + w, t + h), PageSide.CENTER))
        }
        val left = sides.firstOrNull { it.second == PageSide.LEFT }?.first
        val right = sides.firstOrNull { it.second == PageSide.RIGHT }?.first
        val al = aspect(left ?: right!!); val ar = aspect(right ?: left!!)
        var h = ah
        val total = h * (al + ar) + gap
        if (total > aw) h *= (aw - gap) / (total - gap)
        val wl = h * al; val wr = h * ar
        val l0 = (vw - (wl + wr + gap)) / 2f
        val spine = l0 + wl + gap / 2f
        val top = (vh - h) / 2f
        val out = ArrayList<Triple<Int, RectF, PageSide>>(2)
        left?.let { out += Triple(it, RectF(spine - gap / 2f - wl, top, spine - gap / 2f, top + h), PageSide.LEFT) }
        right?.let { out += Triple(it, RectF(spine + gap / 2f, top, spine + gap / 2f + wr, top + h), PageSide.RIGHT) }
        // keep reading order (earlier page first) for callers that iterate pages
        return out.sortedBy { it.first }
    }

    /** Bounds of a screen at zoom 1. */
    fun bounds(item: Int): RectF = RectF().also { r -> rects(item).forEachIndexed { i, (_, rc, _) -> if (i == 0) r.set(rc) else r.union(rc) } }

    companion object {
        /** Apply zoom (about the viewport centre) and pan to a rect. */
        fun zoomed(r: RectF, zoom: Float, panX: Float, panY: Float, vw: Float, vh: Float): RectF {
            val cx = vw / 2f; val cy = vh / 2f
            return RectF(cx + (r.left - cx) * zoom + panX, cy + (r.top - cy) * zoom + panY, cx + (r.right - cx) * zoom + panX, cy + (r.bottom - cy) * zoom + panY)
        }

        /** Largest pan keeping zoomed [bounds] covering the viewport where it can. */
        fun maxPan(bounds: RectF, zoom: Float, vw: Float, vh: Float): Pair<Float, Float> =
            max(0f, (bounds.width() * zoom - vw) / 2f) to max(0f, (bounds.height() * zoom - vh) / 2f)
    }
}
