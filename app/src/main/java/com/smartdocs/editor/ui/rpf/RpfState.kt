package com.smartdocs.editor.ui.rpf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.smartdocs.editor.core.rpf.Frac
import com.smartdocs.editor.core.rpf.ViewMath
import com.smartdocs.editor.ui.nve.NveState
import com.smartdocs.editor.ui.nve.NveZoom
import com.smartdocs.editor.ui.reader.MAX_ZOOM
import com.smartdocs.editor.ui.reader.ReaderState
import java.util.WeakHashMap

/**
 * Per-reader state of reader-prefs (kept outside ReaderState like V5State): the "for this document" sheet, the
 * full-screen request waiting for an answer, the PDF/A claim and the deep-zoom view.
 */
class RpfState private constructor(val s: ReaderState) {
    var docSheet by mutableStateOf(false)
    /** A document asked for full screen and the preference is "ask": run it when the user allows. */
    var fullScreenAsk by mutableStateOf<(() -> Unit)?>(null)
    /** "PDF/A-2b" when the open file claims PDF/A. */
    var pdfaClaim by mutableStateOf<String?>(null)
    var deep by mutableStateOf<Deep?>(null)

    /**
     * Deep zoom above the layout limit: [page] at [pct] % of its actual size, centred on ([cx], [cy]) (fractions of the
     * page as PDFium shows it, before the view rotation). Below [minPct] the normal page view takes over again.
     */
    data class Deep(val page: Int, val pct: Float, val cx: Float, val cy: Float, val minPct: Float)

    companion object {
        private val map = WeakHashMap<ReaderState, RpfState>()
        fun of(s: ReaderState): RpfState = synchronized(map) { map.getOrPut(s) { RpfState(s) } }
        fun forget(s: ReaderState) { synchronized(map) { map.remove(s) } }
    }
}

/** 6400 % zoom: the deep-zoom view renders only the visible region of the page (PDFium tile) at any scale. */
object RpfDeepZoom {
    /** Width of [page] in points as displayed (after the view rotation). */
    private fun shownW(s: ReaderState, page: Int): Float { val (w, h) = s.pageSizes[page] ?: (595f to 842f); return if (s.rotation % 180 == 0) w else h }

    /** The largest percentage the normal page view reaches for [page] (MAX_ZOOM × fit width). */
    fun layoutMaxPct(s: ReaderState, page: Int): Float =
        NveState.of(s).baseWidth(page) * MAX_ZOOM / (shownW(s, page) * NveZoom.pxPerPt(s)).coerceAtLeast(0.01f) * 100f

    /** Enter at the viewport point [at] (pinch centre); false when no page is under it. */
    fun enterAt(s: ReaderState, at: Offset, factor: Float = 1.25f): Boolean {
        if (RpfState.of(s).deep != null) return true        // the pinch that opened it is still going on
        val vp = NveState.of(s).visiblePages().let { l -> l.firstOrNull { it.rect.contains(at) } ?: l.firstOrNull() } ?: return false
        val r = vp.rect
        if (r.width <= 1f || r.height <= 1f) return false
        val shown = Frac((at.x - r.left) / r.width, (at.y - r.top) / r.height, (at.x - r.left) / r.width, (at.y - r.top) / r.height)
        val f = ViewMath.unrotate(shown, s.rotation)
        val max = layoutMaxPct(s, vp.page)
        if (max >= ViewMath.DEEP_MAX_PCT) return false          // the page view already reaches 6400 %
        val pct = (r.width / shownW(s, vp.page) / NveZoom.pxPerPt(s) * 100f * factor).coerceIn(max, ViewMath.DEEP_MAX_PCT)
        RpfState.of(s).deep = RpfState.Deep(vp.page, pct, f.left.coerceIn(0f, 1f), f.top.coerceIn(0f, 1f), max)
        return true
    }

    /** NveZoom.setPercent hook: a percentage above the layout limit opens the deep view at the centre of the window. */
    fun enterPercent(s: ReaderState, pct: Float): Boolean {
        val n = NveState.of(s)
        RpfState.of(s).deep?.let { d -> RpfState.of(s).deep = d.copy(pct = pct.coerceIn(d.minPct, ViewMath.DEEP_MAX_PCT)); return true }
        if (pct <= layoutMaxPct(s, s.currentPage) * 1.001f) return false
        val c = Offset(n.viewport.width / 2f, n.viewport.height / 2f)
        if (!enterAt(s, c)) return false
        RpfState.of(s).deep = RpfState.of(s).deep?.let { d -> d.copy(pct = pct.coerceIn(d.minPct, ViewMath.DEEP_MAX_PCT)) }
        return true
    }

    fun exit(s: ReaderState) {
        val d = RpfState.of(s).deep ?: return
        RpfState.of(s).deep = null
        s.zoom = MAX_ZOOM
        if (d.page != s.currentPage) s.jumpTo(d.page)
    }
}
