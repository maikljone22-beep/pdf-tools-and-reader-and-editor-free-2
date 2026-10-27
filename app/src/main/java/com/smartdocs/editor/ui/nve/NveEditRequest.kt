package com.smartdocs.editor.ui.nve

import android.graphics.RectF
import com.smartdocs.editor.ui.reader.ReaderState

/**
 * "Edit text" from the reader's selection: the page and the selection's first rect (PDF space), kept until the
 * content editor opens and selects the text block there ([take] is called by the editor).
 */
object NveEditRequest {
    data class Request(val page: Int, val rect: RectF?)
    @Volatile private var pending: Pair<ReaderState, Request>? = null

    fun forward(s: ReaderState, r: Pair<Int, RectF?>) { pending = s to Request(r.first, r.second); s.currentPage = r.first }

    /** The pending request of [s], cleared once read. */
    fun take(s: ReaderState): Request? { val p = pending; if (p == null || p.first !== s) return null; pending = null; return p.second }
}
