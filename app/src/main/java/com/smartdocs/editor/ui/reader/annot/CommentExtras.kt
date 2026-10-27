package com.smartdocs.editor.ui.reader.annot

import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.AnnotStyle
import com.smartdocs.editor.core.annot.CommentOps7
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.ui.reader.AnnotTarget
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.annotBounds
import com.smartdocs.editor.ui.reader.mapAnnot
import com.smartdocs.editor.ui.reader.resizable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** ZapfDingbats tickmark palette: (font code, screen glyph). */
object TickSymbols {
    val all = listOf("3" to "✓", "4" to "✔", "7" to "✗", "8" to "✘", "H" to "★",
        "l" to "●", "n" to "■", "u" to "◆", "s" to "▲", "t" to "▼")
}

/** In-app comment clipboard (survives switching documents in this process): pending copies and/or stored refs. */
object CommentClipboard {
    data class Stored(val file: File, val password: String?, val page: Int, val indices: List<Int>)
    var pending: List<Annot> = emptyList(); private set
    var stored: Stored? = null; private set
    var srcPage: Int = 0; private set
    var srcDoc: String? = null; private set
    val isEmpty: Boolean get() = pending.isEmpty() && stored == null

    fun set(page: Int, doc: String?, p: List<Annot>, s: Stored?) {
        stored?.file?.delete()
        pending = p; stored = s; srcPage = page; srcDoc = doc
    }
}

enum class AlignMode { LEFT, CENTER_H, RIGHT, TOP, CENTER_V, BOTTOM, DIST_H, DIST_V, SAME_W, SAME_H, SAME_BOTH }
enum class TickMode { NUMBER, LETTER, SYMBOL }

/** What a section-7 action works on: pending annotation ids and stored annotations of one page. */
data class CTarget(val page: Int, val pending: List<Long>, val stored: List<AnnotationWriter.Existing>)

/** Values of the properties dialog (null = several values / unchanged). */
data class PropsReq(val target: CTarget, val author: String, val subject: String, val name: String, val contents: String,
                    val locked: Boolean, val icon: String, val kind: String, val single: Boolean)

/** "Place on pages" request (stamp / selection copied to a page range). */
data class PagesReq(val target: CTarget)

/**
 * Section 7 comment features on top of [Annot2Host]: hide comments, clipboard (cut / copy / paste / paste in
 * place, other pages and documents), align / distribute / same size, properties (author, subject, name, lock,
 * note icon), flatten selected, rotate, pop-ups, numbered tickmarks, previous / next comment, export selected.
 * Pending annotations change in memory (one undo step each); stored ones through [Annot2Host.fileOp] (one file
 * step of the unified history each; signed files are appended incrementally by [CommentOps7]).
 */
class CommentExtras(private val h: Annot2Host) {
    private val s get() = h.s

    /** Pages are rendered without annotations and pending ones are not drawn (7.65). */
    var hidden by mutableStateOf(false)
    /** Show a sticky-note glyph on markups that carry a note (Foxit 24.13); remembered in prefs. */
    var noteIndicators by mutableStateOf(prefs().getBoolean("note_indicators", false))
        private set

    private fun prefs() = h.s.context.getSharedPreferences("comment_prefs", android.content.Context.MODE_PRIVATE)

    /** Pop-up note text size and opacity (7.74 / 24.12), used by the note text dialogs. */
    var noteFontSize by mutableFloatStateOf(prefs().getFloat("note_font", 16f))
    var noteOpacity by mutableFloatStateOf(prefs().getFloat("note_opacity", 1f))
    var notePrefsOpen by mutableStateOf(false)
    fun saveNotePrefs() { prefs().edit().putFloat("note_font", noteFontSize).putFloat("note_opacity", noteOpacity).apply() }

    /** Copy the text inside a new rectangle / oval / polygon into its note (Foxit 24.41), remembered in prefs. */
    var copyEncircled by mutableStateOf(prefs().getBoolean("copy_encircled", false))
        private set
    fun toggleCopyEncircled() { copyEncircled = !copyEncircled; prefs().edit().putBoolean("copy_encircled", copyEncircled).apply() }

    /** Add a freshly drawn annotation; drawing shapes first get the encircled page text when [copyEncircled]. */
    fun addNew(a: Annot) {
        if (!copyEncircled || a.text.isNotBlank() || a.kind !in setOf(AnnotKind.RECT, AnnotKind.OVAL, AnnotKind.POLYGON, AnnotKind.CLOUD)) { s.addAnnot(a); return }
        s.scope.launch {
            val text = runCatching {
                val boxes = s.charBoxes(a.page); val pt = s.pageText(a.page)
                val b = annotBounds(a)
                val inside = boxes.indices.filter { i ->
                    val r = PageGeometry.normPdf(boxes[i]); val cx = (r.left + r.right) / 2; val cy = (r.top + r.bottom) / 2
                    cx >= b.left && cx <= b.right && cy <= b.top && cy >= b.bottom
                }
                if (inside.isEmpty()) "" else com.smartdocs.editor.ui.reader.TextSelectionLogic.text(pt, inside.first(), inside.last(), boxes).trim()
            }.getOrDefault("")
            s.addAnnot(if (text.isBlank()) a else a.copy(text = text.take(2000)))
        }
    }

    /**
     * Move the start or the end of the selected text markup by one word (Foxit 24.9 "adjust markup range"):
     * [delta] -1 = one word earlier, +1 = one word later. Stored markups become pending first (normal edit path).
     */
    fun adjustMarkup(start: Boolean, delta: Int) {
        val a = s.edit.current() ?: return
        if (a.kind !in setOf(AnnotKind.HIGHLIGHT, AnnotKind.UNDERLINE, AnnotKind.STRIKEOUT, AnnotKind.SQUIGGLY)) return
        s.scope.launch {
            val boxes = s.charBoxes(a.page); val pt = s.pageText(a.page)
            val inside = boxes.indices.filter { i ->
                val r = PageGeometry.normPdf(boxes[i]); val cx = (r.left + r.right) / 2; val cy = (r.top + r.bottom) / 2
                a.rects.any { q -> val n = PageGeometry.normPdf(q); cx >= n.left && cx <= n.right && cy <= n.top && cy >= n.bottom }
            }
            if (inside.isEmpty() || pt.isEmpty()) return@launch
            var st = inside.first(); var en = inside.last()
            val sel = com.smartdocs.editor.ui.reader.TextSelectionLogic
            fun prevWordStart(i: Int): Int { var k = (i - 1).coerceAtLeast(0); while (k > 0 && pt[k].isWhitespace()) k--; return sel.wordAt(pt, k).first }
            fun nextWordEnd(i: Int): Int { var k = (i + 1).coerceAtMost(pt.length - 1); while (k < pt.length - 1 && pt[k].isWhitespace()) k++; return sel.wordAt(pt, k).last }
            if (start) st = if (delta < 0) prevWordStart(st) else minOf(nextWordEnd(st) + 1, en).let { k -> var x = k; while (x < en && pt[x].isWhitespace()) x++; x }
            else en = if (delta > 0) nextWordEnd(en) else maxOf(prevWordStart(en) - 1, st).let { k -> var x = k; while (x > st && pt[x].isWhitespace()) x--; x }
            if (en < st) return@launch
            val rects = sel.rects(boxes, st, en)
            if (rects.isEmpty()) return@launch
            s.edit.edit { it.copy(rects = rects, text = if (it.text.isBlank()) it.text else sel.text(pt, st, en, boxes)) }
        }
    }

    fun toggleNoteIndicators() { noteIndicators = !noteIndicators; prefs().edit().putBoolean("note_indicators", noteIndicators).apply() }
    var props by mutableStateOf<PropsReq?>(null)
    var pagesReq by mutableStateOf<PagesReq?>(null)
    var rotateReq by mutableStateOf<CTarget?>(null)
    var calculator by mutableStateOf(false)
    /** The summary options dialog is open (7.70); [runSummary] builds it (installed by Annot2Dialogs). */
    var summaryAsk by mutableStateOf(false)
    var runSummary: (com.smartdocs.editor.core.annot.SummaryOptions) -> Unit = {}
    var tickMode by mutableStateOf(TickMode.NUMBER)
    var tickSymbol by mutableStateOf("4")
    var tickSize by mutableFloatStateOf(16f)
    /** Index in the sorted comment list of the last previous / next step (24.74). */
    private var navIndex = -1

    private fun str(id: Int) = s.context.getString(id)

    // ================= target =================

    /** The multi-selection, else the single selected annotation (pending or stored). */
    fun target(): CTarget? {
        val m = h.multi
        if (m.active) return CTarget(m.page, m.pending.toList(), m.stored.toList())
        return when (val t = s.edit.target) {
            is AnnotTarget.Pending -> CTarget(t.page, listOf(t.id), emptyList())
            is AnnotTarget.Stored -> CTarget(t.page, emptyList(), listOf(t.e))
            null -> null
        }
    }

    private fun lockedIn(t: CTarget): Boolean =
        s.annots.any { it.id in t.pending && it.locked } || t.stored.any { it.locked }

    /** Refuse a change of locked annotations (7.58): true when the action must stop. */
    fun refuseLocked(t: CTarget): Boolean {
        if (!lockedIn(t)) return false
        h.message = str(R.string.cx_locked_msg); return true
    }

    private fun boundsOf(t: CTarget): List<Pair<Any, RectF>> =
        s.annots.filter { it.id in t.pending }.map { it.id as Any to annotBounds(it) } + t.stored.map { it as Any to PageGeometry.normPdf(it.rect) }

    // ================= hide / show =================

    fun toggleHidden() { hidden = !hidden; s.edit.clear(); h.multi.clear() }

    // ================= clipboard =================

    fun copy(cut: Boolean = false) {
        val t = target() ?: return
        if (cut && refuseLocked(t)) return
        h.launchBusy {
            val pend = s.annots.filter { it.id in t.pending }
            val stored = if (t.stored.isEmpty()) null else s.file?.let { f ->
                val snap = withContext(Dispatchers.IO) { s.app.documents.newTemp("clip", "pdf").also { f.copyTo(it, overwrite = true) } }
                CommentClipboard.Stored(snap, s.password, t.page, t.stored.map { it.index })
            }
            CommentClipboard.set(t.page, s.file?.absolutePath, pend, stored)
            if (cut) {
                if (pend.isNotEmpty()) { val ids = pend.map { it.id }.toSet(); s.batchAnnots { l -> l.removeAll { it.id in ids } } }
                if (t.stored.isNotEmpty()) h.fileOp { src, out, pw -> com.smartdocs.editor.core.annot.CommentStore.deleteMany(src, out, pw, mapOf(t.page to t.stored.map { it.index })) }
                h.multi.clear(); s.edit.clear()
            }
            h.message = str(if (cut) R.string.cx_cut_done else R.string.cx_copied)
        }
    }

    val canPaste: Boolean get() = !CommentClipboard.isEmpty

    /** Paste onto [pages] (default: the current page). [inPlace] keeps the coordinates even on the source page. */
    fun paste(inPlace: Boolean, pages: List<Int> = listOf(s.currentPage)) {
        if (CommentClipboard.isEmpty) return
        val sameDoc = CommentClipboard.srcDoc == s.file?.absolutePath
        h.launchBusy {
            val valid = pages.filter { it in 0 until s.pageCount }
            val offset = !inPlace && sameDoc && valid == listOf(CommentClipboard.srcPage)
            val d = if (offset) 12f else 0f
            if (CommentClipboard.pending.isNotEmpty()) {
                val copies = valid.flatMap { pg ->
                    CommentClipboard.pending.map { a ->
                        val b = annotBounds(a)
                        mapAnnot(a, b, RectF(b.left + d, b.top - d, b.right + d, b.bottom - d))
                            .copy(id = System.nanoTime() + pg * 7919L + a.id % 997, page = pg, nm = null, created = System.currentTimeMillis(), locked = false)
                    }
                }
                s.batchAnnots { it.addAll(copies) }
            }
            CommentClipboard.stored?.let { c ->
                h.fileOp { src, out, pw -> CommentOps7.copyStored(c.file, c.password, c.page, c.indices, src, out, pw, valid, d, -d) }
            }
            h.message = str(R.string.cx_pasted)
        }
    }

    // ================= place on pages (stamp on a page range) =================

    fun placeOnPages(spec: String) {
        val t = pagesReq?.target ?: return
        pagesReq = null
        val pages = parsePages(spec, s.pageCount).filter { it != t.page }
        if (pages.isEmpty()) return
        h.launchBusy {
            val pend = s.annots.filter { it.id in t.pending }
            if (pend.isNotEmpty()) {
                val copies = pages.flatMap { pg -> pend.map { it.copy(id = System.nanoTime() + pg * 7919L + it.id % 997, page = pg, nm = null, groupId = null) } }
                s.batchAnnots { it.addAll(copies) }
            }
            if (t.stored.isNotEmpty()) {
                val f = s.file ?: return@launchBusy
                h.fileOp { src, out, pw -> CommentOps7.copyStored(f, s.password, t.page, t.stored.map { it.index }, src, out, pw, pages, 0f, 0f) }
            }
            h.message = s.context.getString(R.string.cx_placed_pages, pages.size)
        }
    }

    // ================= align / distribute / size =================

    fun align(mode: AlignMode) {
        val t = target() ?: return
        if (refuseLocked(t)) return
        val items = boundsOf(t)
        if (items.size < 2) { h.message = str(R.string.cx_need_two); return }
        val l = items.minOf { it.second.left }; val r = items.maxOf { it.second.right }
        val top = items.maxOf { it.second.top }; val bot = items.minOf { it.second.bottom }
        val moves = HashMap<Any, FloatArray>()
        val sizes = HashMap<Any, RectF>()
        when (mode) {
            AlignMode.LEFT -> items.forEach { (k, b) -> moves[k] = floatArrayOf(l - b.left, 0f) }
            AlignMode.RIGHT -> items.forEach { (k, b) -> moves[k] = floatArrayOf(r - b.right, 0f) }
            AlignMode.CENTER_H -> items.forEach { (k, b) -> moves[k] = floatArrayOf((l + r) / 2 - (b.left + b.right) / 2, 0f) }
            AlignMode.TOP -> items.forEach { (k, b) -> moves[k] = floatArrayOf(0f, top - b.top) }
            AlignMode.BOTTOM -> items.forEach { (k, b) -> moves[k] = floatArrayOf(0f, bot - b.bottom) }
            AlignMode.CENTER_V -> items.forEach { (k, b) -> moves[k] = floatArrayOf(0f, (top + bot) / 2 - (b.top + b.bottom) / 2) }
            AlignMode.DIST_H, AlignMode.DIST_V -> {
                if (items.size < 3) { h.message = str(R.string.cx_need_three); return }
                val hor = mode == AlignMode.DIST_H
                val sorted = items.sortedBy { (_, b) -> if (hor) (b.left + b.right) / 2 else (b.top + b.bottom) / 2 }
                fun c(b: RectF) = if (hor) (b.left + b.right) / 2 else (b.top + b.bottom) / 2
                val first = c(sorted.first().second); val last = c(sorted.last().second)
                val step = (last - first) / (sorted.size - 1)
                sorted.forEachIndexed { i, (k, b) -> val d = first + step * i - c(b); moves[k] = if (hor) floatArrayOf(d, 0f) else floatArrayOf(0f, d) }
            }
            AlignMode.SAME_W, AlignMode.SAME_H, AlignMode.SAME_BOTH -> {
                // the anchor is the first selected annotation (Foxit "resize to anchor")
                val anchor = items.first().second
                val aw = anchor.right - anchor.left; val ah = anchor.top - anchor.bottom
                items.drop(1).forEach { (k, b) ->
                    val w = if (mode == AlignMode.SAME_H) b.right - b.left else aw
                    val hh = if (mode == AlignMode.SAME_W) b.top - b.bottom else ah
                    sizes[k] = RectF(b.left, b.top, b.left + w, b.top - hh)
                }
            }
        }
        val pend = moves.filterKeys { it is Long }.mapKeys { it.key as Long }
        val pendSizes = sizes.filterKeys { it is Long }.mapKeys { it.key as Long }
        if (pend.isNotEmpty() || pendSizes.isNotEmpty()) s.batchAnnots { list ->
            for (i in list.indices) {
                val a = list[i]
                pend[a.id]?.let { d -> val b = annotBounds(a); list[i] = mapAnnot(a, b, RectF(b.left + d[0], b.top + d[1], b.right + d[0], b.bottom + d[1])) }
                pendSizes[a.id]?.let { nr -> if (resizable(a)) list[i] = mapAnnot(a, annotBounds(a), nr) }
            }
        }
        val st = moves.filterKeys { it is AnnotationWriter.Existing }.mapKeys { (it.key as AnnotationWriter.Existing).index }
        val stSizes = sizes.filterKeys { it is AnnotationWriter.Existing }.map { (k, r) -> (k as AnnotationWriter.Existing).index to floatArrayOf(r.left, r.bottom, r.right, r.top) }.toMap()
        if (st.isEmpty() && stSizes.isEmpty()) return
        val pg = t.page
        h.launchBusy {
            h.fileOp { src, out, pw -> if (st.isNotEmpty()) CommentOps7.moveEach(src, out, pw, pg, st) else CommentOps7.setRects(src, out, pw, pg, stSizes) }
            if (st.isNotEmpty() && stSizes.isNotEmpty()) h.fileOp { src, out, pw -> CommentOps7.setRects(src, out, pw, pg, stSizes) }
            h.multi.refreshStored(pg, t.stored.map { it.index })
        }
    }

    // ================= properties =================

    fun openProps() {
        val t = target() ?: return
        val pend = s.annots.filter { it.id in t.pending }
        val single = pend.size + t.stored.size == 1
        h.launchBusy {
            var author = ""; var subject = ""; var name = ""; var contents = ""; var locked = false; var icon = ""; var kind = ""
            val first = pend.firstOrNull()
            if (first != null) {
                author = first.author; subject = first.subject; name = first.nm ?: ""; contents = first.text; locked = first.locked; icon = first.icon
                kind = when (first.kind) { AnnotKind.NOTE -> "Text"; AnnotKind.FILE_ATTACH -> "FileAttachment"; else -> first.kind.name }
            } else t.stored.firstOrNull()?.let { e ->
                author = e.author; contents = e.contents; locked = e.locked; subject = e.subject; kind = e.subtype
                s.file?.let { f -> withContext(Dispatchers.IO) { readMeta(f, s.password, e.page, e.index) } }?.let { (n, ic) -> name = n; icon = ic }
            }
            props = PropsReq(t, author, subject, name, contents, locked, icon, kind, single)
        }
    }

    private fun readMeta(f: File, pw: String?, page: Int, index: Int): Pair<String, String>? = runCatching {
        val d = if (pw.isNullOrEmpty()) com.tom_roush.pdfbox.pdmodel.PDDocument.load(f) else com.tom_roush.pdfbox.pdmodel.PDDocument.load(f, pw)
        d.use { doc ->
            val cos = doc.getPage(page).annotations[index].cosObject
            AnnotStyle.nameOf(cos) to (cos.getNameAsString(com.tom_roush.pdfbox.cos.COSName.getPDFName("Name")) ?: "")
        }
    }.getOrNull()

    /** Apply the dialog values: blank author / subject on a multi-selection leave them unchanged. */
    fun applyProps(req: PropsReq, author: String, subject: String, name: String, contents: String, locked: Boolean, icon: String) {
        props = null
        val t = req.target
        val setAuthor = author.takeIf { req.single || it.isNotBlank() }
        val setSubject = subject.takeIf { req.single || it.isNotBlank() }
        val setName = name.takeIf { req.single }
        val setContents = contents.takeIf { req.single }
        val setIcon = icon.takeIf { it.isNotBlank() }
        val ids = t.pending.toSet()
        if (ids.isNotEmpty()) s.batchAnnots { list ->
            for (i in list.indices) if (list[i].id in ids) {
                var a = list[i]
                setAuthor?.let { a = a.copy(author = it) }; setSubject?.let { a = a.copy(subject = it) }
                setName?.let { a = a.copy(nm = it.ifBlank { null }) }; setContents?.let { a = a.copy(text = it) }
                setIcon?.let { if (a.kind == AnnotKind.NOTE || a.kind == AnnotKind.FILE_ATTACH) a = a.copy(icon = it) }
                list[i] = a.copy(locked = locked)
            }
        }
        if (t.stored.isNotEmpty()) h.launchBusy {
            val p = CommentOps7.Props(setAuthor, setSubject, setName, setContents, locked, setIcon)
            h.fileOp { src, out, pw -> CommentOps7.setProps(src, out, pw, t.page, t.stored.map { it.index }, p) }
            refresh(t)
        }
    }

    fun setLocked(locked: Boolean) {
        val t = target() ?: return
        val ids = t.pending.toSet()
        if (ids.isNotEmpty()) s.batchAnnots { l -> for (i in l.indices) if (l[i].id in ids) l[i] = l[i].copy(locked = locked) }
        if (t.stored.isNotEmpty()) h.launchBusy {
            h.fileOp { src, out, pw -> CommentOps7.setProps(src, out, pw, t.page, t.stored.map { it.index }, CommentOps7.Props(locked = locked)) }
            refresh(t)
        }
        h.message = str(if (locked) R.string.cx_locked_done else R.string.cx_unlocked_done)
    }

    private suspend fun refresh(t: CTarget) {
        if (h.multi.active) h.multi.refreshStored(t.page, t.stored.map { it.index })
        else { s.edit.clear() }
    }

    // ================= flatten / rotate (stored; pending are saved first) =================

    /** Stored indices of [t]: pending members are saved first and found again by their /NM. */
    private suspend fun storedIndices(t: CTarget): List<Int>? {
        var refs = t.stored.map { it.index }
        val nms = s.annots.filter { it.id in t.pending }.map { it.nm ?: "sd-${it.id}" }.toSet()
        if (nms.isNotEmpty()) {
            if (!s.save()) return null
            val f = s.file ?: return null
            val names = withContext(Dispatchers.IO) { com.smartdocs.editor.core.annot.StoredOps.namesOf(f, s.password, t.page) }
            refs = refs + names.filterValues { it in nms }.keys
        }
        return refs.distinct()
    }

    fun flatten() {
        val t = target() ?: return
        if (refuseLocked(t)) return
        h.launchBusy {
            val refs = storedIndices(t) ?: return@launchBusy
            if (refs.isEmpty()) return@launchBusy
            h.fileOp { src, out, pw -> CommentOps7.flatten(src, out, pw, t.page, refs) }
            h.multi.clear(); s.edit.clear()
            h.message = s.context.getString(R.string.cx_flattened, refs.size)
        }
    }

    fun rotate(degrees: Float) {
        val t = rotateReq ?: target() ?: return
        rotateReq = null
        if (refuseLocked(t)) return
        h.launchBusy {
            val refs = storedIndices(t) ?: return@launchBusy
            if (refs.isEmpty()) return@launchBusy
            h.fileOp { src, out, pw -> CommentOps7.rotate(src, out, pw, t.page, refs, degrees) }
            h.multi.clear(); s.edit.clear()
        }
    }

    // ================= pop-ups =================

    fun resetPopups() = h.launchBusy { if (s.dirty && !s.save()) return@launchBusy; h.fileOp { src, out, pw -> CommentOps7.resetPopups(src, out, pw) }; h.message = str(R.string.cx_popups_reset) }
    fun popupsOpen(open: Boolean) = h.launchBusy { if (s.dirty && !s.save()) return@launchBusy; h.fileOp { src, out, pw -> CommentOps7.setPopupsOpen(src, out, pw, open) } }

    // ================= tickmarks =================

    private fun letters(n: Int): String { var k = n; val sb = StringBuilder(); while (k > 0) { val r = (k - 1) % 26; sb.insert(0, ('A' + r)); k = (k - 1) / 26 }; return sb.toString() }

    private fun lettersValue(s0: String): Int = s0.fold(0) { acc, c -> if (c in 'A'..'Z') acc * 26 + (c - 'A' + 1) else return 0 }

    /** Label of the next tickmark on [page] in the current mode (auto-increment over pending and stored marks). */
    fun nextLabel(page: Int): String {
        val labels = s.annots.filter { it.page == page && it.subject == AnnotStyle.TICKMARK }.map { it.text } +
            s.existing.filter { it.page == page && it.subject == AnnotStyle.TICKMARK }.map { it.contents }
        return when (tickMode) {
            TickMode.NUMBER -> ((labels.mapNotNull { it.trim().toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
            TickMode.LETTER -> letters((labels.map { lettersValue(it.trim()) }.maxOrNull() ?: 0) + 1)
            TickMode.SYMBOL -> "zd:$tickSymbol"
        }
    }

    fun placeTick(page: Int, x: Float, y: Float) {
        val r = tickSize / 2
        val a = s.newAnnot(AnnotKind.OVAL, page, rects = listOf(RectF(x - r, y + r, x + r, y - r)), text = nextLabel(page))
            .copy(subject = AnnotStyle.TICKMARK, fillColor = s.color, noBorder = false, dash = 0, cloud = 0)
        s.addAnnot(a)
    }

    /** Select every tickmark of the current page (then recolour them together, 25.30). */
    fun selectTickmarks() {
        val pg = s.currentPage
        val ids = s.annots.filter { it.page == pg && it.subject == AnnotStyle.TICKMARK }.map { it.id }
        val st = s.existing.filter { it.page == pg && it.subject == AnnotStyle.TICKMARK }
        if (ids.isEmpty() && st.isEmpty()) { h.message = str(R.string.cx_no_tickmarks); return }
        s.selectTool(com.smartdocs.editor.ui.reader.ReaderTool.SELECT)
        h.multi.selectSet(pg, ids, st)
    }

    /** Jump to the previous / next page holding tickmarks. */
    fun stepTickmark(next: Boolean) {
        val pages = (s.annots.filter { it.subject == AnnotStyle.TICKMARK }.map { it.page } + s.existing.filter { it.subject == AnnotStyle.TICKMARK }.map { it.page }).distinct().sorted()
        val cur = s.currentPage
        val p = if (next) pages.firstOrNull { it > cur } ?: pages.firstOrNull() else pages.lastOrNull { it < cur } ?: pages.lastOrNull()
        if (p == null) { h.message = str(R.string.cx_no_tickmarks); return }
        s.jumpTo(p)
    }

    // ================= previous / next comment =================

    fun stepComment(next: Boolean) {
        val list = s.existing.sortedWith(compareBy({ it.page }, { -PageGeometry.normPdf(it.rect).top }, { it.rect.left }))
        if (list.isEmpty()) { h.message = str(R.string.an_no_comments); return }
        val cur = (s.edit.target as? AnnotTarget.Stored)?.e?.let { e -> list.indexOfFirst { it.page == e.page && it.index == e.index } } ?: navIndex
        navIndex = if (next) (cur + 1).mod(list.size) else (if (cur <= 0) list.size - 1 else cur - 1)
        val e = list[navIndex]
        h.multi.clear()
        s.jumpTo(e.page)
        s.edit.selectStored(e)
    }

    // ================= export selected =================

    /** FDF / XFDF of the selection only (24.92); [share] opens the share sheet (e-mail, 24.93). */
    fun exportSelected(xfdf: Boolean, share: Boolean, deliver: (File) -> Unit) {
        val t = target()
        h.launchBusy {
            if (s.dirty && !s.save()) return@launchBusy
            val f = s.file ?: return@launchBusy
            val refs: Map<Int, Set<Int>>? = t?.let { tt -> storedIndices(tt)?.let { mapOf(tt.page to it.toSet()) } }
            val dir = File(s.app.documents.tempDir, "cx_export_${System.nanoTime()}").apply { mkdirs() }
            val src = if (refs == null) f else File(dir, f.name).also { CommentOps7.keepOnly(f, s.password, it, refs) }
            val out = File(dir, f.nameWithoutExtension + if (xfdf) ".xfdf" else ".fdf")
            val pw = if (refs == null) s.password else null
            val n = if (xfdf) com.smartdocs.editor.core.annot.CommentExchange.exportXfdf(src, pw, out) else com.smartdocs.editor.core.annot.CommentExchange.exportFdf(src, pw, out)
            if (n == 0) { h.message = str(R.string.an_no_comments); return@launchBusy }
            if (share) MediaIntents.share(s.context, s.app, out, if (xfdf) "application/vnd.adobe.xfdf" else "application/vnd.fdf") else deliver(out)
        }
    }

    companion object {
        /** "1-3, 5, 8-" → zero-based pages (1-based input, open ranges to the end). */
        fun parsePages(spec: String, count: Int): List<Int> {
            if (spec.isBlank() || spec.trim().equals("all", true)) return (0 until count).toList()
            val out = LinkedHashSet<Int>()
            spec.split(',', ';', '،').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                val m = Regex("""^(\d*)\s*-\s*(\d*)$""").find(part)
                if (m != null) {
                    val a = m.groupValues[1].toIntOrNull() ?: 1; val b = m.groupValues[2].toIntOrNull() ?: count
                    for (p in minOf(a, b)..maxOf(a, b)) if (p in 1..count) out += p - 1
                } else part.toIntOrNull()?.let { if (it in 1..count) out += it - 1 }
            }
            return out.toList()
        }
    }
}
