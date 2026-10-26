package com.smartdocs.editor.ui.office

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.smartdocs.editor.core.office5.*
import java.io.File

/** A table cell being edited: body block index, row and cell index. */
data class CellPos(val block: Int, val row: Int, val cell: Int)

/**
 * UI state of the Word editor. Typing goes straight into the paragraph DOM (diff-based, so the formatting
 * of untouched characters is kept); one typing session in one paragraph is one undo step. Enter splits the
 * paragraph, Backspace at its start joins it with the previous one.
 */
class WordState(val doc: WordDoc, override val file: File) : OfficeHost {
    override val ext = "docx"
    override var tick by mutableIntStateOf(0); private set
    override val modified: Boolean get() = doc.modified
    override val canUndo: Boolean get() = doc.canUndo
    override val canRedo: Boolean get() = doc.canRedo

    private var cacheVersion = -1
    private var cache: List<WBlock> = emptyList()
    val blocks: List<WBlock> get() { if (cacheVersion != doc.version) { cache = WordView.build(doc); cacheVersion = doc.version }; return cache }

    /** After typing inside block [i] only: refresh that block's view instead of rebuilding the document. */
    private fun patch(i: Int, before: Int) {
        if (cacheVersion != before || i !in cache.indices) return
        val old = cache[i]
        val nb: WBlock = when (old) {
            is WBlock.Para -> WBlock.Para(WordView.para(doc, old.elem, old.p.marker, reuseMarker = true))
            is WBlock.Table -> WordView.table(doc, old.elem)
            else -> return
        }
        cache = cache.toMutableList().also { it[i] = nb }
        cacheVersion = doc.version
    }

    var focus by mutableIntStateOf(-1)
    var cell by mutableStateOf<CellPos?>(null)
    var field by mutableStateOf(TextFieldValue())
    /** Block index of a selected picture paragraph. */
    var image by mutableStateOf<Int?>(null)
    var reading by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
    var outline by mutableStateOf(true)
    val list = LazyListState()
    private var typing = false

    // find & replace
    var findOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchIndex by mutableIntStateOf(0)
    val matches: List<WordDoc.Match> get() { tick; return if (query.isEmpty()) emptyList() else doc.find(query) }

    fun bump() { tick++ }

    // ---------------- focus ----------------

    fun focusParagraph(i: Int, at: Int? = null) {
        val b = blocks.getOrNull(i) as? WBlock.Para ?: return
        focus = i; cell = null; image = null; typing = false
        val t = b.p.text
        field = TextFieldValue(t, TextRange((at ?: t.length).coerceIn(0, t.length)))
        bump()
    }

    fun focusCell(pos: CellPos) {
        val tc = cellElem(pos) ?: return
        focus = -1; image = null; cell = pos; typing = false
        val t = WordTables.cellText(tc)
        field = TextFieldValue(t, TextRange(t.length))
        bump()
    }

    fun selectImage(i: Int) { focus = -1; cell = null; image = i; typing = false; bump() }

    fun clearFocus() { focus = -1; cell = null; image = null; typing = false; bump() }

    fun cellElem(p: CellPos): XElem? {
        val t = (blocks.getOrNull(p.block) as? WBlock.Table)?.elem ?: return null
        return WordTables.rows(t).getOrNull(p.row)?.let { WordTables.cells(it).getOrNull(p.cell) }
    }

    val focusedPara: XElem? get() = (blocks.getOrNull(focus) as? WBlock.Para)?.elem

    /** The block a new element goes after: focused paragraph / table / picture, else the end. */
    val anchor: XElem? get() = blocks.getOrNull(focus)?.elem ?: cell?.let { blocks.getOrNull(it.block)?.elem } ?: image?.let { blocks.getOrNull(it)?.elem }

    private fun syncField() {
        val c = cell
        if (c != null) { val tc = cellElem(c); if (tc == null) { cell = null } else { val t = WordTables.cellText(tc); field = TextFieldValue(t, TextRange(field.selection.start.coerceIn(0, t.length), field.selection.end.coerceIn(0, t.length))) } }
        val p = blocks.getOrNull(focus) as? WBlock.Para
        if (focus >= 0) { if (p == null) focus = -1 else { val t = p.p.text; field = TextFieldValue(t, TextRange(field.selection.start.coerceIn(0, t.length), field.selection.end.coerceIn(0, t.length))) } }
        if (image != null && blocks.getOrNull(image!!) == null) image = null
    }

    // ---------------- typing ----------------

    private fun diff(a: String, b: String): Pair<Int, Int> {
        var x = 0
        while (x < a.length && x < b.length && a[x] == b[x]) x++
        var y = 0
        while (y < a.length - x && y < b.length - x && a[a.length - 1 - y] == b[b.length - 1 - y]) y++
        return x to y
    }

    fun onField(v: TextFieldValue) {
        val old = field.text
        if (v.text == old) { field = v; return }
        if (!typing) { doc.checkpoint(); typing = true }
        val c = cell
        if (c != null) {
            val tc = cellElem(c) ?: return
            val before = doc.version
            doc.continueChange { WordTables.setCellText(tc, v.text) }
            patch(c.block, before)
            field = v; bump(); return
        }
        val p = focusedPara ?: return
        val (a, b) = diff(old, v.text)
        val inserted = v.text.substring(a, v.text.length - b)
        val breaks = inserted.indices.filter { inserted[it] == '\n' }.map { a + it }
        if (breaks.isEmpty()) {
            val before = doc.version
            doc.continueChange { WordRuns.setText(p, v.text) }
            patch(focus, before)
            field = v; bump(); return
        }
        // Enter (or pasted lines): split the paragraph at every new line break
        doc.continueChange {
            WordRuns.setText(p, v.text)
            for (pos in breaks.asReversed()) {
                WordRuns.splitParagraph(doc.body, p, pos + 1)
                WordRuns.deleteRange(p, pos, pos + 1)
            }
        }
        typing = false
        val last = breaks.last()
        focus += breaks.size
        val t = (blocks.getOrNull(focus) as? WBlock.Para)?.p?.text ?: ""
        field = TextFieldValue(t, TextRange((v.selection.start - last - 1).coerceIn(0, t.length)))
        bump()
    }

    /** Backspace at the start of a paragraph: join it with the previous paragraph. */
    fun joinWithPrevious(): Boolean {
        val p = focusedPara ?: return false
        val prev = blocks.getOrNull(focus - 1) as? WBlock.Para ?: return false
        val at = prev.p.text.length
        doc.change { WordRuns.joinParagraphs(doc.body, prev.elem, p) }
        typing = false
        focus -= 1
        val t = (blocks.getOrNull(focus) as? WBlock.Para)?.p?.text ?: ""
        field = TextFieldValue(t, TextRange(at.coerceAtMost(t.length)))
        bump(); return true
    }

    // ---------------- formatting ----------------

    /** Paragraph ranges the current selection covers (a collapsed cursor means the word under it, else the paragraph). */
    private fun selection(): List<Triple<XElem, Int, Int>> {
        val sel = field.selection
        val s0 = minOf(sel.start, sel.end); val e0 = maxOf(sel.start, sel.end)
        val c = cell
        if (c != null) {
            val tc = cellElem(c) ?: return emptyList()
            val out = ArrayList<Triple<XElem, Int, Int>>(); var off = 0
            for (p in WordTables.cellParagraphs(tc)) {
                val len = WordRuns.text(p).length
                if (s0 == e0) out.add(Triple(p, 0, len))
                else { val a = maxOf(s0, off) - off; val b = minOf(e0, off + len) - off; if (b > a) out.add(Triple(p, a, b)) }
                off += len + 1
            }
            return out
        }
        val p = focusedPara ?: return emptyList()
        val t = field.text
        if (s0 != e0) return listOf(Triple(p, s0, e0))
        var a = s0; var b = s0
        while (a > 0 && t[a - 1].isLetterOrDigit()) a--
        while (b < t.length && t[b].isLetterOrDigit()) b++
        return listOf(if (b > a) Triple(p, a, b) else Triple(p, 0, t.length))
    }

    val hasTarget: Boolean get() { tick; return focus >= 0 || cell != null }

    /** Effective formatting at the selection (ribbon toggle states). */
    fun props(): RunProps {
        tick
        val sel = selection().firstOrNull() ?: return RunProps()
        val v = WordView.para(doc, sel.first)
        return WordView.propsAt(v, sel.second, sel.third)
    }

    fun format(mutate: (XElem) -> Unit) {
        val sel = selection(); if (sel.isEmpty()) return
        doc.change {
            sel.forEach { (p, a, b) ->
                if (b > a) WordRuns.formatRange(p, a, b, mutate)
                else if (WordRuns.text(p).isEmpty()) {
                    // empty paragraph: the paragraph mark carries the formatting of what will be typed
                    val pp = WordRuns.pPr(p)
                    val rp = pp.child("rPr") ?: XElem(pp.prefix.let { if (it.isEmpty()) "rPr" else "$it:rPr" }).also { pp.setOrdered(it, WordRuns.PPR_ORDER) }
                    mutate(rp)
                }
            }
        }
        typing = false; syncField(); bump()
    }

    fun toggle(get: (RunProps) -> Boolean?, set: (XElem, Boolean) -> Unit) { val on = get(props()) != true; format { set(it, on) } }

    fun clearFormatting() = format { rp -> rp.kids.removeAll { it is XElem && it.local != "rStyle" && it.local != "rtl" && it.local != "lang" && it.local != "rFonts" } }

    /** Paragraphs of the focus (one body paragraph, or all paragraphs of the edited cell). */
    fun focusParagraphs(): List<XElem> = cell?.let { c -> cellElem(c)?.let { WordTables.cellParagraphs(it) } } ?: listOfNotNull(focusedPara ?: image?.let { blocks.getOrNull(it)?.elem })

    fun paragraphs(op: (XElem) -> Unit) {
        val ps = focusParagraphs(); if (ps.isEmpty()) return
        doc.change { ps.forEach(op) }
        typing = false; syncField(); bump()
    }

    fun styleKind(): WordParts.StyleKind? { tick; return focusParagraphs().firstOrNull()?.let { doc.styleKindOf(it) } }

    fun align(): PAlign? { tick; return focusParagraphs().firstOrNull()?.let { WordFormat.align(it) } }
    fun rtl(): Boolean { tick; return focusParagraphs().firstOrNull()?.let { p -> (blocks.getOrNull(focus) as? WBlock.Para)?.p?.rtl ?: WordFormat.bidi(p) } ?: false }

    fun toggleList(bullet: Boolean) {
        val ps = focusParagraphs(); if (ps.isEmpty()) return
        doc.change { doc.toggleList(ps, bullet) }
        typing = false; syncField(); bump()
    }

    fun applyStyle(k: WordParts.StyleKind) = paragraphs { doc.applyStyle(it, k) }

    // ---------------- insert ----------------

    fun insertParagraph() {
        val i = doc.change { doc.insertParagraphAfter(anchor) }
        val idx = blocks.indexOfFirst { it.elem === i }
        if (idx >= 0) focusParagraph(idx, 0) else bump()
    }

    fun insertTable(rows: Int, cols: Int) {
        val after = anchor
        val rtl = rtl() || (after == null && blocks.any { it is WBlock.Para && it.p.rtl })
        val t = doc.change {
            val e = XDom.elem(WordParts.tableXml(doc.w, rows, cols, (doc.contentWidthEmu() / 635).toInt(), rtl))
            val b = doc.body
            val at = after?.let { b.indexOf(it) + 1 } ?: b.kids.indexOfLast { it is XElem && it.local == "sectPr" }.let { if (it < 0) b.kids.size else it }
            b.add(at, e)
            // Word needs a paragraph after a table at the end of the body
            val next = b.kids.drop(at + 1).firstOrNull { it is XElem }
            if (next == null || (next as XElem).local == "sectPr") b.add(at + 1, doc.newParagraph(after?.takeIf { it.local == "p" }))
            e
        }
        val idx = blocks.indexOfFirst { it.elem === t }
        if (idx >= 0) focusCell(CellPos(idx, 0, 0)) else bump()
    }

    fun insertImage(bytes: ByteArray) {
        val p = doc.change { doc.insertImage(anchor, bytes) }
        val idx = blocks.indexOfFirst { it.elem === p }
        if (idx >= 0) selectImage(idx) else bump()
    }

    fun insertPageBreak() { doc.change { doc.insertPageBreak(anchor) }; typing = false; bump() }

    /** Inserts text at the cursor of the focused paragraph (date, symbols). */
    fun insertAtCursor(s: String) {
        val t = field.text; val a = minOf(field.selection.start, field.selection.end); val b = maxOf(field.selection.start, field.selection.end)
        onField(TextFieldValue(t.substring(0, a) + s + t.substring(b), TextRange(a + s.length)))
    }

    fun deleteBlock() {
        val b = anchor ?: return
        doc.change { doc.deleteBlock(b) }
        clearFocus()
    }

    // ---------------- tables / pictures ----------------

    fun table(op: (XElem, CellPos) -> Unit) {
        val c = cell ?: return
        val t = (blocks.getOrNull(c.block) as? WBlock.Table)?.elem ?: return
        doc.change { op(t, c) }
        typing = false
        if (cellElem(c) == null) cell = CellPos(c.block, c.row.coerceAtMost(WordTables.rows(t).size - 1).coerceAtLeast(0), 0)
        syncField(); bump()
    }

    fun resizeImage(factor: Float) {
        val i = image ?: return
        val p = blocks.getOrNull(i)?.elem ?: return
        doc.change {
            doc.drawings(p).forEach { d ->
                val (cx, cy) = doc.drawingSize(d)
                val max = doc.contentWidthEmu()
                var nx = (cx * factor).toLong().coerceAtLeast(91440); var ny = (cy * factor).toLong().coerceAtLeast(91440)
                if (nx > max) { ny = ny * max / nx; nx = max }
                doc.resizeDrawing(d, nx, ny)
            }
        }
        bump()
    }

    // ---------------- find ----------------

    fun gotoMatch(i: Int) {
        val ms = matches; if (ms.isEmpty()) return
        matchIndex = i.mod(ms.size)
        val m = ms[matchIndex]
        val top = blocks.indexOfFirst { it.elem === m.paragraph }
        if (top >= 0) { focusParagraph(top); field = field.copy(selection = TextRange(m.start, m.end)) }
        else blocks.indexOfFirst { it is WBlock.Table && WordRuns.parentOf(it.elem, m.paragraph) != null }.takeIf { it >= 0 }?.let { ti ->
            val t = blocks[ti].elem
            WordTables.rows(t).forEachIndexed { r, tr -> WordTables.cells(tr).forEachIndexed { c, tc -> if (WordRuns.parentOf(tc, m.paragraph) != null) focusCell(CellPos(ti, r, c)) } }
        }
        bump()
    }

    fun replaceCurrent() {
        val ms = matches; if (ms.isEmpty()) return
        val m = ms[matchIndex.coerceIn(0, ms.size - 1)]
        doc.change { doc.replace(m, replacement) }
        typing = false; syncField(); bump()
        gotoMatch(matchIndex)
    }

    fun replaceAll(): Int { val n = doc.replaceAll(query, replacement); typing = false; syncField(); bump(); return n }

    // ---------------- host ----------------

    override fun undo() { if (doc.undo()) { typing = false; syncField(); bump() } }
    override fun redo() { if (doc.redo()) { typing = false; syncField(); bump() } }

    override fun commitPending() { typing = false }

    override fun saveTo(target: File) {
        doc.pkg.saveTo(target)
        if (target.absolutePath == file.absolutePath) { doc.modified = false; tick++ }
    }
}
