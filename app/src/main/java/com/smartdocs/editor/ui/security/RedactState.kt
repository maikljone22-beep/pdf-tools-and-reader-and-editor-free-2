package com.smartdocs.editor.ui.security

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.PageFrame
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.redact.Box
import com.smartdocs.editor.core.redact.RedactMark
import com.smartdocs.editor.core.redact.RedactMarks
import com.smartdocs.editor.core.redact.RedactMath
import com.smartdocs.editor.core.redact.RedactPrefs
import com.smartdocs.editor.core.redact.RedactProfile
import com.smartdocs.editor.core.redact.RedactStyle
import com.smartdocs.editor.core.search.Found
import com.smartdocs.editor.core.search.PatternKind
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.SearchPrefs
import com.smartdocs.editor.core.search.SearchRequest
import com.smartdocs.editor.core.search.TextSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Display ⇄ user space of one page (see [PageFrame]); display space is what the page view shows. */
class PageGeo(val width: Float, val height: Float, val toDisplay: FloatArray, val toUser: FloatArray)

fun foundKey(f: Found) = "${f.hit.page}:${f.hit.charIndex}:${f.hit.length}"

/**
 * State of the redaction tool: marks (PDF user space), their default style, the search inputs (query, word list,
 * patterns, custom regex, names list, keep-last-N, page range) and the checkable hit list.
 */
class RedactState(private val context: Context) {
    val marks = mutableStateListOf<RedactMark>()
    /** Marks unticked in the review list: kept (and shown) but not applied or saved. */
    var excluded by mutableStateOf<Set<String>>(emptySet())
    var style by mutableStateOf(RedactPrefs.lastStyle(context))
    var geo by mutableStateOf<Map<Int, PageGeo>>(emptyMap())

    var query by mutableStateOf("")
    var options by mutableStateOf(SearchOptions(norm = SearchPrefs.load(context)))
    var wordsText by mutableStateOf("")
    var patterns by mutableStateOf<Set<PatternKind>>(emptySet())
    var regexText by mutableStateOf("")
    var names by mutableStateOf(RedactPrefs.names(context))
    var useNames by mutableStateOf(false)
    var keepLast by mutableIntStateOf(0)
    var pageRange by mutableStateOf("")
    var found by mutableStateOf<List<Found>>(emptyList())
    var checked by mutableStateOf<Set<String>>(emptySet())
    var searching by mutableStateOf(false)
    var searched by mutableStateOf(false)

    private var seq = 0
    private fun newId() = "sd-${System.currentTimeMillis()}-${seq++}"

    /** Page geometry and the /Redact marks already in the file. */
    suspend fun load(file: File, password: String?) = withContext(Dispatchers.IO) {
        val g = HashMap<Int, PageGeo>()
        val existing = runCatching {
            PdfOps.load(file, password).use { doc ->
                doc.pages.forEachIndexed { i, p ->
                    val f = PageFrame.of(p)
                    g[i] = PageGeo(f.width, f.height, Affine.values(f.toDisplay), Affine.values(f.toUser))
                }
                RedactMarks.read(doc)
            }
        }.getOrDefault(emptyList())
        withContext(Dispatchers.Main) {
            geo = g
            marks.clear(); marks.addAll(existing); excluded = emptySet()
            found = emptyList(); checked = emptySet(); searched = false
        }
    }

    /** Phrases of the word list and their own overlay texts ("phrase | overlay"). */
    fun phrases() = com.smartdocs.editor.core.redact.PhraseList.parse(wordsText)

    fun request(): SearchRequest {
        val words = (phrases().first + if (useNames) names.lines() else emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
        return SearchRequest(query = query, options = options, words = words, patterns = patterns,
            regexes = regexText.lines().map { it.trim() }.filter { it.isNotEmpty() }, keepLast = keepLast)
    }

    suspend fun search(session: PdfSession) {
        searching = true
        try {
            val pages = PdfOps.parseRange(pageRange, session.pageCount)
            val req = request()
            val all = ArrayList<Found>()
            if (pages.size == session.pageCount) all += TextSearch.search(session, req)
            else pages.forEach { p -> all += TextSearch.search(session, req, p..p) }
            found = all
            checked = all.map(::foundKey).toSet()
            searched = true
        } finally { searching = false }
    }

    /** Turns the checked hits into marks (one mark per hit, one rect per line). */
    fun markChecked(): Int {
        val sel = found.filter { foundKey(it) in checked && it.hit.rects.isNotEmpty() }
        val existing = marks.map { "${it.page}:${it.label}:${it.rects.firstOrNull()?.left}" }.toHashSet()
        val overlays = phrases().second
        var n = 0
        for (f in sel) {
            val label = f.hit.snippet.trim()
            val key = "${f.hit.page}:$label:${f.hit.rects.first().left}"
            if (key in existing) continue
            // Foxit: a phrase group can carry its own overlay text
            val own = overlays.entries.firstOrNull { (w, _) -> label.contains(w, ignoreCase = true) || w.contains(label, ignoreCase = true) }?.value
            marks += RedactMark(newId(), f.hit.page, f.hit.rects.map { RectF(it) }, if (own != null) style.copy(overlay = own) else style, label)
            n++
        }
        checked = emptySet()
        return n
    }

    /** The marks that «تطبيق» / «حفظ العلامات» use (ticked in the review list). */
    fun activeMarks(): List<RedactMark> = marks.filter { it.id !in excluded }

    fun toggleIncluded(id: String) { excluded = if (id in excluded) excluded - id else excluded + id }

    /**
     * Hits of the smart PII scan ([com.smartdocs.editor.ui.ai5.PiiScanSheet]) become marks (ticked, so the review
     * list is where they are unticked or removed). Returns how many new marks were added.
     */
    fun addHits(hits: List<com.smartdocs.editor.core.pdf.SearchHit>, labelPrefix: String = ""): Int {
        val existing = marks.map { "${it.page}:${it.rects.firstOrNull()?.left}:${it.rects.firstOrNull()?.top}" }.toHashSet()
        var n = 0
        for (h in hits) {
            if (h.rects.isEmpty()) continue
            val key = "${h.page}:${h.rects.first().left}:${h.rects.first().top}"
            if (!existing.add(key)) continue
            marks += RedactMark(newId(), h.page, h.rects.map { RectF(it) }, style, labelPrefix + h.snippet.trim())
            n++
        }
        return n
    }

    fun displayBox(page: Int, user: RectF): Box? {
        val g = geo[page] ?: return null
        return RedactMath.map(g.toDisplay, Box.of(user.left, user.bottom, user.right, user.top))
    }

    /** A rectangle drawn on the page view (display space, top > bottom). */
    fun addManual(page: Int, display: RectF) {
        val g = geo[page]
        val u = if (g == null) Box.of(display.left, display.bottom, display.right, display.top)
                else RedactMath.map(g.toUser, Box.of(display.left, display.bottom, display.right, display.top))
        marks += RedactMark(newId(), page, listOf(RectF(u.l, u.t, u.r, u.b)), style, "")
    }

    fun addPageMarks(pages: List<Int>) {
        val have = marks.filter { it.wholePage }.map { it.page }.toSet()
        pages.filter { it !in have }.forEach { p -> marks += RedactMark(newId(), p, emptyList(), style, "", wholePage = true) }
    }

    /** Copies [m] (same position) to [pages]. */
    fun repeatOnPages(m: RedactMark, pages: List<Int>) {
        pages.filter { it != m.page }.forEach { p -> marks += m.copy(id = newId(), page = p) }
    }

    fun remove(id: String) { if (marks.any { it.id == id && it.locked }) return; marks.removeAll { it.id == id }; excluded = excluded - id }

    /** Moves an unlocked mark by ([dx], [dy]) display points. */
    fun move(id: String, page: Int, dx: Float, dy: Float) {
        val i = marks.indexOfFirst { it.id == id }; if (i < 0 || marks[i].locked || marks[i].wholePage) return
        val m = marks[i]
        val moved = m.rects.mapNotNull { r ->
            val d = displayBox(page, r) ?: return@mapNotNull null
            userRect(page, RectF(d.l + dx, d.t + dy, d.r + dx, d.b + dy))
        }
        if (moved.size == m.rects.size) marks[i] = m.copy(rects = moved)
    }

    /** Gives an unlocked one-rectangle mark the display rectangle [display] (top > bottom). */
    fun resize(id: String, page: Int, display: RectF) {
        val i = marks.indexOfFirst { it.id == id }; if (i < 0 || marks[i].locked || marks[i].rects.size != 1) return
        marks[i] = marks[i].copy(rects = listOf(userRect(page, display)))
    }

    private fun userRect(page: Int, display: RectF): RectF {
        val g = geo[page]
        val u = if (g == null) Box.of(display.left, display.bottom, display.right, display.top)
                else RedactMath.map(g.toUser, Box.of(display.left, display.bottom, display.right, display.top))
        return RectF(u.l, u.t, u.r, u.b)
    }

    /**
     * Foxit "double-click an image to mark the whole image": the image under display point ([x], [y]) on [page]
     * becomes a mark. Returns false when there is no image there.
     */
    suspend fun markImageAt(file: File, password: String?, page: Int, x: Float, y: Float): Boolean = withContext(Dispatchers.IO) {
        val g = geo[page]
        val pt = if (g == null) Box.of(x, y, x, y) else RedactMath.map(g.toUser, Box.of(x, y, x, y))
        val box = runCatching {
            PdfOps.load(file, password).use { doc ->
                val p = doc.getPage(page)
                com.smartdocs.editor.core.redact.PageInspector(p, doc.documentCatalog.ocProperties).run(p).paints
                    .filter { it.kind == com.smartdocs.editor.core.redact.PaintKind.IMAGE || it.kind == com.smartdocs.editor.core.redact.PaintKind.INLINE_IMAGE }
                    .filter { it.box.contains(pt.l, pt.b) }.minByOrNull { it.box.area }?.box
            }
        }.getOrNull() ?: return@withContext false
        withContext(Dispatchers.Main) { marks += RedactMark(newId(), page, listOf(RectF(box.l, box.t, box.r, box.b)), style, "") }
        true
    }

    fun setGeneral(id: String, author: String, subject: String, locked: Boolean) {
        val i = marks.indexOfFirst { it.id == id }; if (i >= 0) marks[i] = marks[i].copy(author = author, subject = subject, locked = locked)
    }

    /** Adds a review state (Accepted, Rejected, Cancelled, Completed, None) to the mark's history. */
    fun review(id: String, state: String, author: String) {
        val i = marks.indexOfFirst { it.id == id }
        if (i >= 0) marks[i] = marks[i].copy(reviews = marks[i].reviews + com.smartdocs.editor.core.redact.RedactReview(state, author, System.currentTimeMillis()))
    }

    fun update(m: RedactMark) { val i = marks.indexOfFirst { it.id == m.id }; if (i >= 0) marks[i] = m }

    fun applyStyleToAll(st: RedactStyle) {
        style = st
        RedactPrefs.saveStyle(context, st)
        for (i in marks.indices) marks[i] = marks[i].copy(style = st)
    }

    fun clear() { marks.removeAll { !it.locked }; excluded = excluded.filter { id -> marks.any { it.id == id } }.toSet() }

    fun saveNames() = RedactPrefs.saveNames(context, names)

    fun profile(name: String) = phrases().let { (words, overlays) ->
        RedactProfile(name, words, patterns, regexText.lines().filter { it.isNotBlank() }, style, options.wholeWord, keepLast, overlays)
    }

    fun loadProfile(p: RedactProfile) {
        wordsText = com.smartdocs.editor.core.redact.PhraseList.format(p.words, p.overlays); patterns = p.patterns; regexText = p.regexes.joinToString("\n")
        style = p.style; options = options.copy(wholeWord = p.wholeWord); keepLast = p.keepLast
    }
}
