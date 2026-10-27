package com.smartdocs.editor.ui.reader

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai6.QuoteLocator
import com.smartdocs.editor.core.ai6.ReadingOrder
import com.smartdocs.editor.core.forms5.WKind
import com.smartdocs.editor.core.pdf.SearchHit
import com.smartdocs.editor.core.search.FindReplace
import com.smartdocs.editor.core.search.ReplaceOutcome
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.tts.ReadAloudController
import com.smartdocs.editor.ui.quick.QuickController
import com.smartdocs.editor.ui.quick.QuickInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * ai6 reader AI hooks (small calls from ReaderScreen / PageCanvas):
 * - B11 [replaceSelection]: an AI rewrite of the selection replaces the text in the page content (content-edit engine,
 *   one undo step through [QuickController.apply] → [ReaderState.applyInPlace]).
 * - B1 [highlightQuote]: a tapped citation jumps to its page and highlights the quoted passage for a few seconds.
 * - C1 [readingText] / [ReadAloudHighlight]: read aloud in column-aware reading order with form field values, and the
 *   sentence being spoken highlighted on the page.
 */
object ReaderAi6 {

    // ---------------- B11 ----------------

    fun replaceSelection(ctx: Context, state: ReaderState, quick: QuickController, sel: TextSelection, replacement: String) {
        val query = sel.text.trim()
        if (query.isEmpty() || sel.rects.isEmpty() || replacement.isBlank()) return
        var outcome: ReplaceOutcome? = null
        quick.apply(ctx.getString(R.string.ai6r_replacing), { ctx.getString(R.string.ai6r_replaced) }, "rewritten",
            after = {
                val o = outcome
                quick.message = ctx.getString(R.string.ai6r_replaced) +
                    (if (o?.fontWarning == true) "\n" + ctx.getString(R.string.s5_rr_font_warn) else "") +
                    (if (o?.lossy == true) "\n" + ctx.getString(R.string.s5_rr_lossy) else "")
            }) { src, out, pw ->
            // the selection rect points at the block; a selection spanning several blocks cannot be rewritten in place
            val o = FindReplace.replaceHit(ctx, src, out, pw, sel.page, RectF(sel.rects.first()), emptyList(), query, SearchOptions(), replacement)
            if (o.count == 0) { out.delete(); throw QuickInfo(ctx.getString(R.string.ai6r_replace_failed)) }
            outcome = o
        }
    }

    // ---------------- B1 ----------------

    fun highlightQuote(scope: CoroutineScope, state: ReaderState, page: Int, quote: String) {
        state.jumpTo(page)
        if (quote.isBlank()) return
        scope.launch {
            val text = state.pageText(page)
            val boxes = state.charBoxes(page)
            val r = QuoteLocator.locate(text, quote) ?: return@launch
            if (boxes.isEmpty()) return@launch
            val rects = TextSelectionLogic.rects(boxes, r.first, r.last)
            if (rects.isEmpty()) return@launch
            val hit = SearchHit(page, r.first, r.last - r.first + 1, rects, text.substring(r.first, (r.last + 1).coerceAtMost(text.length)).take(80))
            if (state.searchOpen) return@launch          // never replace the user's own search results
            state.searchHits = listOf(hit); state.searchIndex = 0
            delay(8000)
            if (!state.searchOpen && state.searchHits.singleOrNull() === hit) { state.searchHits = emptyList(); state.searchIndex = -1 }
        }
    }

    // ---------------- C1 ----------------

    /** Per reader: page → (source text, reading order). The source text detects a page changed by an edit. */
    private val ordered = WeakHashMap<ReaderState, HashMap<Int, Pair<String, ReadingOrder.Ordered>>>()

    private fun cache(state: ReaderState): HashMap<Int, Pair<String, ReadingOrder.Ordered>> = synchronized(ordered) { ordered.getOrPut(state) { HashMap() } }

    private suspend fun orderedPage(state: ReaderState, page: Int): ReadingOrder.Ordered {
        val text = state.pageText(page)
        cache(state).let { c -> synchronized(c) { c[page] }?.let { (src, o) -> if (src == text) return o } }
        val boxes = runCatching { state.charBoxes(page) }.getOrDefault(emptyList())
        val o = runCatching {
            ReadingOrder.order(text, boxes.map { b -> ReadingOrder.Box(b.left, b.top, b.right, b.bottom) })
        }.getOrElse { ReadingOrder.identity(text) }
        val c = cache(state); synchronized(c) { c[page] = text to o }
        return o
    }

    /** Text read aloud for [page]: reading order, then the page's form fields ("label: value"). */
    suspend fun readingText(state: ReaderState, page: Int): String {
        val o = orderedPage(state, page)
        val fields = runCatching { fieldLines(state, page) }.getOrDefault("")
        return if (fields.isBlank()) o.text else o.text + "\n\n" + fields
    }

    private fun fieldLines(state: ReaderState, page: Int): String {
        val fs = state.forms5
        val seen = HashSet<String>()
        return fs.model.widgets.filter { it.page == page && !fs.isHidden(it) && it.kind != WKind.BUTTON && it.kind != WKind.SIGNATURE && it.kind != WKind.IMAGE }
            .mapNotNull { w ->
                if (!seen.add(w.name)) return@mapNotNull null
                val label = w.label.ifBlank { w.name.substringAfterLast('.') }
                when (w.kind) {
                    WKind.CHECK -> label + ": " + state.context.getString(if (fs.isOn(w)) R.string.ai6r_field_checked else R.string.ai6r_field_unchecked)
                    WKind.RADIO -> fs.value(w.name).takeIf { it.isNotBlank() && it != "Off" }?.let { "$label: $it" }
                    else -> fs.value(w.name).takeIf { it.isNotBlank() }?.let { "$label: ${it.replace('\n', '،')}" }
                }
            }.joinToString("\n")
    }

    /** Raw page-text index (e.g. "read from here" on a selection) → offset in [readingText]. */
    suspend fun toReadingOffset(state: ReaderState, page: Int, raw: Int): Int {
        if (raw <= 0) return 0
        val o = orderedPage(state, page)
        val i = o.map.indexOfFirst { it == raw }
        return if (i >= 0) i else raw.coerceAtMost(o.text.length)
    }

    /** Page and rects (PDF space) of the sentence being read aloud; drawn by PageCanvas. */
    val ttsHighlight = mutableStateOf<Pair<Int, List<RectF>>?>(null)

    /** Keeps [ttsHighlight] in sync with the read-aloud progress (placed once in ReaderScreen). */
    @Composable
    fun ReadAloudHighlight(state: ReaderState) {
        val spoken by ReadAloudController.spoken.collectAsState()
        val status by ReadAloudController.status.collectAsState()
        LaunchedEffect(spoken, status.state) {
            val sp = spoken
            if (sp == null || status.state == ReadAloudController.State.IDLE) { ttsHighlight.value = null; return@LaunchedEffect }
            val o = cache(state).let { c -> synchronized(c) { c[sp.page]?.second } } ?: run { ttsHighlight.value = null; return@LaunchedEffect }
            val boxes = runCatching { state.charBoxes(sp.page) }.getOrDefault(emptyList())
            if (boxes.isEmpty()) { ttsHighlight.value = null; return@LaunchedEffect }
            val rects = o.sourceRuns(sp.start, sp.end).flatMap { r -> TextSelectionLogic.rects(boxes, r.first, r.last) }
            ttsHighlight.value = if (rects.isEmpty()) null else sp.page to rects
        }
    }
}
