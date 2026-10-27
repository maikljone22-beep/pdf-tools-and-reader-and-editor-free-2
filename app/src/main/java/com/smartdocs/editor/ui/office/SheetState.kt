package com.smartdocs.editor.ui.office

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.smartdocs.editor.core.office5.*
import java.io.File

/** UI state of the spreadsheet editor: active sheet, selection, formula bar, viewport and clipboard. */
class SheetState(val wb: Workbook5, override val file: File) : OfficeHost {
    override val ext: String = if (wb.kind == Workbook5.Kind.CSV) file.extension.lowercase().ifEmpty { "csv" } else "xlsx"
    override var tick by mutableIntStateOf(0); private set
    override val modified: Boolean get() = wb.modified
    override val canUndo: Boolean get() = wb.canUndo
    override val canRedo: Boolean get() = wb.canRedo

    var active by mutableIntStateOf(0)
    val sheet: Sheet5 get() = wb.book.sheets[active.coerceIn(0, wb.book.sheets.size - 1)]

    /** Active cell and selected range. */
    var row by mutableIntStateOf(0)
    var col by mutableIntStateOf(0)
    var sel by mutableStateOf(CRange(0, 0, 0, 0))

    var editing by mutableStateOf(false)
    var input by mutableStateOf(TextFieldValue(""))
    var error by mutableStateOf<String?>(null)

    var zoom by mutableFloatStateOf(1f)
    var scrollX by mutableFloatStateOf(0f)
    var scrollY by mutableFloatStateOf(0f)
    var showFormulas by mutableStateOf(false)
    var gridlines by mutableStateOf(true)
    /** Drag-to-select after a long press. */
    var dragSelect = false
    /** Target range while dragging the fill handle. */
    var fillTarget by mutableStateOf<CRange?>(null)
    var menuOpen by mutableStateOf(false)

    var clip: Workbook5.Clip? = null
    private var clipSheet: Sheet5? = null
    private var clipCut = false

    fun bump() { tick++ }

    // ---------------- office6: find & replace ----------------
    var findOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchIndex by mutableIntStateOf(0)
    var findOpts by mutableStateOf(FindOptions())
    /** Cell the grid should scroll to (consumed by SheetGrid). */
    var reveal by mutableStateOf<Pair<Int, Int>?>(null)
    val matches: List<SheetHit> get() { tick; return SheetFind.find(wb.book, sheet, query, findOpts) }

    fun gotoMatch(i: Int) {
        val ms = matches; if (ms.isEmpty()) return
        matchIndex = i.mod(ms.size)
        val h = ms[matchIndex]
        if (editing) commit(false)
        select(h.row, h.col); reveal = h.row to h.col
    }

    fun replaceCurrent() {
        val ms = matches; if (ms.isEmpty()) return
        val h = ms[matchIndex.coerceIn(0, ms.size - 1)]
        change { SheetFind.replaceCell(wb, sheet, h.row, h.col, query, replacement, findOpts) }
        if (matches.isNotEmpty()) gotoMatch(matchIndex)
    }

    fun replaceAll(): Int { if (editing) commit(false); val n = SheetFind.replaceAll(wb, active, sheet, query, replacement, findOpts); bump(); return n }

    // ---------------- selection ----------------

    fun select(r: Int, c: Int, extend: Boolean = false) {
        val rr = r.coerceIn(0, MAX_ROWS - 1); val cc = c.coerceIn(0, MAX_COLS - 1)
        if (extend) sel = CRange.of(row, col, rr, cc)
        else {
            row = rr; col = cc
            sel = sheet.mergeAt(rr, cc) ?: CRange.cell(rr, cc)
            input = TextFieldValue(sheet.cell(rr, cc)?.editText() ?: "")
        }
        bump()
    }

    fun selectColumn(c: Int, extend: Boolean = false) { if (!extend) { col = c; row = 0 }; sel = CRange.of(0, if (extend) col else c, MAX_ROWS - 1, c); input = TextFieldValue(sheet.cell(row, col)?.editText() ?: ""); bump() }
    fun selectRow(r: Int, extend: Boolean = false) { if (!extend) { row = r; col = 0 }; sel = CRange.of(if (extend) row else r, 0, r, MAX_COLS - 1); input = TextFieldValue(sheet.cell(row, col)?.editText() ?: ""); bump() }

    val wholeColumns: Boolean get() = sel.r1 == 0 && sel.r2 == MAX_ROWS - 1
    val wholeRows: Boolean get() = sel.c1 == 0 && sel.c2 == MAX_COLS - 1

    fun switchSheet(i: Int) {
        if (editing) commit(false)
        active = i.coerceIn(0, wb.book.sheets.size - 1); scrollX = 0f; scrollY = 0f
        select(0, 0)
    }

    // ---------------- editing ----------------

    fun startEdit(initial: String? = null) {
        val t = initial ?: sheet.cell(row, col)?.editText() ?: ""
        input = TextFieldValue(t, TextRange(t.length)); editing = true; error = null; bump()
    }

    fun onInput(v: TextFieldValue) { if (!editing) editing = true; input = v; error = null; bump() }

    /** Inserts a reference at the cursor while typing a formula; returns false when not in formula mode. */
    fun insertRef(r: Int, c: Int, extend: Boolean = false): Boolean {
        if (!editing || !input.text.startsWith("=")) return false
        val t = input.text; val at = input.selection.start
        // replace a reference right before the cursor (re-tapping moves it)
        val before = t.substring(0, at)
        val m = Regex("""(\$?[A-Z]{1,3}\$?\d+(:\$?[A-Z]{1,3}\$?\d+)?)$""").find(before)
        val ref = if (extend && m != null) m.value.substringBefore(':') + ":" + A1.name(r, c) else A1.name(r, c)
        val canInsert = m != null || before.isEmpty() || before.last() in "=(+-*/^&,;:<> "
        if (!canInsert) return false
        val start = m?.range?.first ?: at
        input = TextFieldValue(t.substring(0, start) + ref + t.substring(at), TextRange(start + ref.length)); bump()
        return true
    }

    /** Writes the formula bar into the active cell; [move] goes to the next row. */
    fun commit(move: Boolean = true): Boolean {
        if (!editing) return true
        val text = input.text
        val old = sheet.cell(row, col)?.editText() ?: ""
        if (text != old) {
            val err = wb.change(active) { wb.setInput(sheet, row, col, autoClose(text)) }
            if (err != null) { error = err; bump(); return false }
        }
        editing = false; error = null
        if (move) select(row + 1, col) else bump()
        return true
    }

    /** Adds missing closing brackets and quotes to a formula. */
    private fun autoClose(t: String): String {
        if (!t.startsWith("=")) return t
        var s = t
        if (s.count { it == '"' } % 2 == 1) s += "\""
        var depth = 0; var q = false
        for (ch in s) { if (ch == '"') q = !q else if (!q) { if (ch == '(') depth++ else if (ch == ')') depth-- } }
        if (depth > 0) s += ")".repeat(depth)
        return s
    }

    fun cancelEdit() { editing = false; error = null; input = TextFieldValue(sheet.cell(row, col)?.editText() ?: ""); bump() }

    /** Function-name suggestions for the identifier being typed in a formula. */
    fun suggestions(): List<String> {
        val t = input.text; if (!editing || !t.startsWith("=")) return emptyList()
        val before = t.substring(0, input.selection.start.coerceIn(0, t.length))
        val m = Regex("""(?:^=|[=(+\-*/^&,;<> ])([A-Za-z][A-Za-z0-9.]*)$""").find(before) ?: return emptyList()
        val p = m.groupValues[1].uppercase()
        return FxFunctions.all.map { it.name }.filter { it.startsWith(p) && it != p }.sorted().take(8)
    }

    fun applySuggestion(name: String) {
        val t = input.text; val at = input.selection.start
        val before = t.substring(0, at)
        val m = Regex("""[A-Za-z][A-Za-z0-9.]*$""").find(before) ?: return
        val nt = before.substring(0, m.range.first) + name + "(" + t.substring(at)
        input = TextFieldValue(nt, TextRange(m.range.first + name.length + 1)); bump()
    }

    // ---------------- operations ----------------

    fun <T> change(block: () -> T): T { if (editing) commit(false); val r = wb.change(active, block); bump(); return r }

    fun format(f: (CellFormat) -> CellFormat) = change { wb.applyFormat(sheet, sel, f) }

    fun formatAt(f: (Int, Int, CellFormat) -> CellFormat) = change { wb.applyFormatAt(sheet, sel, f) }

    val fmt: CellFormat get() { tick; return wb.format(sheet, row, col) }

    fun copy(cut: Boolean): String {
        val c = wb.copy(sheet, sel); clip = c; clipSheet = sheet; clipCut = cut; return c.text
    }

    fun paste(valuesOnly: Boolean = false, externalText: String? = null) {
        val c = clip
        if (c != null && (externalText == null || externalText == c.text)) {
            change {
                if (clipCut) clipSheet?.let { src -> wb.clear(src, CRange(c.srcRow, c.srcCol, c.srcRow + c.rows - 1, c.srcCol + c.cols - 1), contents = true, formats = true) }
                wb.paste(c, sheet, sel.r1, sel.c1, valuesOnly && !clipCut, clipSheet)
            }
            if (clipCut) { clipCut = false; clip = null }
            sel = CRange(sel.r1, sel.c1, sel.r1 + c.rows - 1, sel.c1 + c.cols - 1); bump()
        } else if (externalText != null) change { wb.pasteText(externalText, sheet, sel.r1, sel.c1) }
    }

    fun clear(formats: Boolean = false) = change { wb.clear(sheet, sel, contents = true, formats = formats) }

    /** AutoSum-style function on the selection: below a column selection, or for the cell above a run of numbers. */
    fun autoFunction(name: String) {
        if (sel.rows > 1 || sel.cols > 1) {
            val target = if (sel.rows > 1) CellRef(sel.r2 + 1, sel.c1) else CellRef(sel.r1, sel.c2 + 1)
            val range = if (sel.rows > 1) CRange(sel.r1, sel.c1, sel.r2, sel.c1) else CRange(sel.r1, sel.c1, sel.r1, sel.c2)
            change { wb.setInput(sheet, target.row, target.col, "=$name(${range.a1()})") }
            select(target.row, target.col); return
        }
        // single cell: sum the numbers directly above (or to the left)
        var r = row - 1
        while (r >= 0 && wb.book.value(sheet, r, col) is FV.Num) r--
        val arg = if (r < row - 1) CRange(r + 1, col, row - 1, col).a1() else {
            var c = col - 1; while (c >= 0 && wb.book.value(sheet, row, c) is FV.Num) c--
            if (c < col - 1) CRange(row, c + 1, row, col - 1).a1() else ""
        }
        startEdit("=$name($arg)")
    }

    /** Fill: seeds are the leading filled rows (down) or columns (right) of the selection. */
    fun fillSelection(down: Boolean) {
        val s = sheet
        if (down) {
            var last = sel.r1
            while (last + 1 <= sel.r2 && (sel.c1..sel.c2).any { s.cell(last + 1, it)?.isEmpty == false }) last++
            if (last >= sel.r2) last = sel.r1
            change { wb.fill(s, CRange(sel.r1, sel.c1, last, sel.c2), sel) }
        } else {
            var last = sel.c1
            while (last + 1 <= sel.c2 && (sel.r1..sel.r2).any { s.cell(it, last + 1)?.isEmpty == false }) last++
            if (last >= sel.c2) last = sel.c1
            change { wb.fill(s, CRange(sel.r1, sel.c1, sel.r2, last), sel) }
        }
    }

    fun fillTo(target: CRange) {
        val src = sel
        val t = CRange.of(minOf(src.r1, target.r1), minOf(src.c1, target.c1), maxOf(src.r2, target.r2), maxOf(src.c2, target.c2))
        if (t == src) return
        // a fill extends in one direction only
        val down = t.r2 > src.r2 || t.r1 < src.r1
        val tt = if (down) CRange(t.r1, src.c1, t.r2, src.c2) else CRange(src.r1, t.c1, src.r2, t.c2)
        change { wb.fill(sheet, src, tt) }
        sel = tt; bump()
    }

    fun sort(ascending: Boolean) {
        val s = sheet
        val used = s.usedRange() ?: return
        val r = if (sel.size == 1L) CRange(0, 0, used.r2, used.c2) else CRange(sel.r1, sel.c1, minOf(sel.r2, used.r2), minOf(sel.c2, used.c2))
        val header = r.r1 == 0 && (r.c1..r.c2).all { c -> wb.book.value(s, 0, c).let { it is FV.Str || it is FV.Blank } } &&
            (r.c1..r.c2).any { c -> wb.book.value(s, 1, c) is FV.Num }
        change { wb.sort(s, r, col.coerceIn(r.c1, r.c2), ascending, header) }
    }

    // ---------------- host ----------------

    override fun undo() { if (editing) cancelEdit(); val a = wb.undo(active); if (a >= 0) { active = a.coerceIn(0, wb.book.sheets.size - 1); select(row, col) }; bump() }
    override fun redo() { if (editing) cancelEdit(); val a = wb.redo(active); if (a >= 0) { active = a.coerceIn(0, wb.book.sheets.size - 1); select(row, col) }; bump() }

    override fun commitPending() { if (editing) commit(false) }

    override fun saveTo(target: File) {
        val wasModified = wb.modified
        if (wb.kind == Workbook5.Kind.CSV) CsvIO.save(wb, target) else XlsxIO5.save(wb, target)
        wb.modified = if (target.absolutePath == file.absolutePath) false else wasModified
        tick++
    }
}
