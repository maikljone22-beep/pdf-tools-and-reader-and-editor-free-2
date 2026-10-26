package com.smartdocs.editor.core.office5

import java.util.TreeMap

/** Stored content of one cell (immutable): a formula (text without '=') or a literal, a style index, and IO bookkeeping. */
class Cell5(
    val formula: String?,
    val literal: FV,
    val style: Int = 0,
    /** Shared-string index this text came from (kept while the text is unchanged, so rich text survives). */
    val sst: Int = -1,
    /** Value cached in the file (used for functions the engine does not implement). */
    val cached: FV? = null,
) {
    val isFormula: Boolean get() = formula != null
    fun withStyle(s: Int) = Cell5(formula, literal, s, sst, cached)
    val isEmpty: Boolean get() = formula == null && literal is FV.Blank

    /** Text for the formula bar. */
    fun editText(): String = when {
        formula != null -> "=$formula"
        literal is FV.Num -> FxCoerce.general(literal.v)
        literal is FV.Str -> literal.v.let { if (FxCoerce.parseNumber(it) != null || it.startsWith("=") || it.startsWith("'")) "'$it" else it }
        literal is FV.Bool -> if (literal.v) "TRUE" else "FALSE"
        literal is FV.Err -> literal.code
        else -> ""
    }
}

class Sheet5(var name: String) {
    val cells = HashMap<Long, Cell5>()
    /** Column widths in Excel character units. */
    val colWidths = TreeMap<Int, Float>()
    /** Row heights in points. */
    val rowHeights = HashMap<Int, Float>()
    val merges = ArrayList<CRange>()
    var freezeRows = 0
    var freezeCols = 0
    var rtl = false
    var defaultColWidth = 8.43f
    var defaultRowHeight = 15f
    /** Worksheet part in the package (null for a new sheet / CSV). */
    var partPath: String? = null
    /** Changed since load: its worksheet part must be regenerated. */
    var dirty = false
    /** Relationship id of the sheet in workbook.xml (IO bookkeeping). */
    var relId: String? = null
    /** Column style index / hidden flags read from `<cols>` (kept when the columns are rewritten). */
    val colStyles = HashMap<Int, String>()
    val colHidden = HashSet<Int>()
    /** Extra attributes of the original `<row>` elements (hidden, s, customFormat, outlineLevel…). */
    val rowAttrs = HashMap<Int, List<XAttr>>()

    // computed state (owned by SheetBook)
    internal val values = HashMap<Long, FV>()
    internal val asts = HashMap<Long, Fx?>()

    fun cell(r: Int, c: Int): Cell5? = cells[key(r, c)]

    fun usedRange(): CRange? {
        if (cells.isEmpty()) return null
        var r2 = 0; var c2 = 0
        for (k in cells.keys) { val r = row(k); val c = col(k); if (r > r2) r2 = r; if (c > c2) c2 = c }
        return CRange(0, 0, r2, c2)
    }

    fun colWidth(c: Int): Float = colWidths[c] ?: defaultColWidth
    fun rowHeight(r: Int): Float = rowHeights[r] ?: defaultRowHeight
    fun mergeAt(r: Int, c: Int): CRange? = merges.firstOrNull { it.contains(r, c) }

    companion object {
        fun key(r: Int, c: Int): Long = (r.toLong() shl 14) or c.toLong()
        fun row(k: Long): Int = (k shr 14).toInt()
        fun col(k: Long): Int = (k and 0x3FFF).toInt()
    }
}

/**
 * A workbook in memory with a formula dependency graph. Editing a cell recalculates only the cells that
 * depend on it (transitively), in topological order; cells in a cycle get [FV.CIRC]. Volatile functions
 * (TODAY, NOW) recalculate on every change. Functions the engine lacks keep the value cached in the file.
 */
class SheetBook {
    val sheets = ArrayList<Sheet5>()
    var date1904 = false
    /** Number format code of a style index (set by the IO layer). */
    var numFmtOf: (Int) -> String? = { null }

    private data class G(val sheet: Sheet5, val key: Long)
    private class RangeDep(val range: CRange, val dep: G)

    private val precedents = HashMap<G, List<Pair<Sheet5?, CRange>>>()
    private val cellDeps = HashMap<G, HashSet<G>>()
    private val rangeDeps = HashMap<Sheet5, ArrayList<RangeDep>>()
    private val volatile = HashSet<G>()
    /** Cells found in a reference cycle by the last recalculation. */
    val circular = HashSet<Pair<String, Long>>()

    fun sheet(name: String?): Sheet5? = sheets.firstOrNull { it.name.equals(name, true) }
    fun indexOf(s: Sheet5) = sheets.indexOf(s)

    // ---------------- values ----------------

    fun value(s: Sheet5, r: Int, c: Int): FV {
        val k = Sheet5.key(r, c)
        val cell = s.cells[k] ?: return FV.Blank
        if (cell.formula == null) return cell.literal
        return s.values[k] ?: cell.cached ?: FV.Blank
    }

    fun display(s: Sheet5, r: Int, c: Int): Formatted {
        val cell = s.cells[Sheet5.key(r, c)] ?: return Formatted("")
        return NumFmt5.format(value(s, r, c), numFmtOf(cell.style), date1904)
    }

    private inner class Ctx(val home: Sheet5, override val row: Int, override val col: Int) : FxContext {
        override fun value(sheet: String?, row: Int, col: Int): FV {
            val s = if (sheet == null) home else sheet(sheet) ?: throw FxError(FV.REF)
            return this@SheetBook.value(s, row, col)
        }
        override fun sheetExists(sheet: String) = sheet(sheet) != null
        override fun usedRange(sheet: String?): CRange? = (if (sheet == null) home else sheet(sheet))?.usedRange()
    }

    /** Evaluates an arbitrary formula in the context of a cell (used for previews and tests). */
    fun evalFormula(s: Sheet5, r: Int, c: Int, formula: String): FV {
        val fx = FxParser.tryParse(formula) ?: return FV.NAME
        return try { FxEval(Ctx(s, r, c)).evaluate(fx) } catch (e: FxUnsupported) { FV.NAME }
    }

    // ---------------- input ----------------

    /** Result of parsing typed input: the cell content and a number format the input implies (12% → 0%). */
    class Parsed(val formula: String?, val literal: FV, val impliedFormat: String?)

    fun parseInput(text: String): Parsed {
        if (text.isEmpty()) return Parsed(null, FV.Blank, null)
        if (text.startsWith("=") && text.length > 1) return Parsed(text.substring(1).trim(), FV.Blank, null)
        if (text.startsWith("'")) return Parsed(null, FV.Str(text.substring(1)), null)
        val t = text.trim()
        when (t.uppercase()) { "TRUE" -> return Parsed(null, FV.TRUE, null); "FALSE" -> return Parsed(null, FV.FALSE, null) }
        if (t.uppercase() in FV.ERRORS) return Parsed(null, FV.Err(t.uppercase()), null)
        FxCoerce.parseNumber(t)?.let { n ->
            val fmt = if (t.endsWith("%")) (if (t.contains('.')) "0.00%" else "0%") else if (Regex("""\d,\d{3}""").containsMatchIn(FxCoerce.normalizeDigits(t))) "#,##0" else null
            return Parsed(null, FV.Num(n), fmt)
        }
        FxDates.parse(t)?.let { d -> return Parsed(null, FV.Num(d), if (FxDates.isTime(t)) "h:mm" else if (d % 1.0 != 0.0) "yyyy-mm-dd h:mm" else "yyyy-mm-dd") }
        return Parsed(null, FV.Str(text), null)
    }

    /** Stores [cell] (null clears content and style) without recalculating. */
    fun put(s: Sheet5, r: Int, c: Int, cell: Cell5?) {
        val k = Sheet5.key(r, c); val g = G(s, k)
        unlink(g)
        s.values.remove(k); s.asts.remove(k)
        if (cell == null || (cell.isEmpty && cell.style == 0)) s.cells.remove(k) else s.cells[k] = cell
        if (cell?.formula != null) link(s, k, cell)
        s.dirty = true
    }

    /** Recalculates everything that depends on the given cells (and the volatile cells). */
    fun recalc(changed: Collection<Triple<Sheet5, Int, Int>>) {
        val start = changed.map { (s, r, c) -> G(s, Sheet5.key(r, c)) }
        val dirty = LinkedHashSet<G>()
        val queue = ArrayDeque<G>()
        fun push(g: G) { if (dirty.add(g)) queue.add(g) }
        start.forEach { g -> if (g.sheet.cells[g.key]?.formula != null) dirty.add(g); queue.add(g) }
        volatile.forEach { push(it) }
        while (queue.isNotEmpty()) {
            val g = queue.removeFirst()
            cellDeps[g]?.forEach { push(it) }
            val r = Sheet5.row(g.key); val c = Sheet5.col(g.key)
            rangeDeps[g.sheet]?.forEach { if (it.range.contains(r, c)) push(it.dep) }
        }
        evaluate(dirty.filter { it.sheet.cells[it.key]?.formula != null })
    }

    /** Rebuilds the dependency graph and recalculates every formula (after load, structural edits, undo). */
    fun recalcAll() {
        precedents.clear(); cellDeps.clear(); rangeDeps.clear(); volatile.clear()
        val all = ArrayList<G>()
        for (s in sheets) {
            s.values.clear(); s.asts.clear()
            for ((k, cell) in s.cells) if (cell.formula != null) { link(s, k, cell); all.add(G(s, k)) }
        }
        evaluate(all)
    }

    private fun ast(s: Sheet5, k: Long, cell: Cell5): Fx? = s.asts.getOrPut(k) { FxParser.tryParse(cell.formula!!) }

    private fun link(s: Sheet5, k: Long, cell: Cell5) {
        val g = G(s, k)
        val fx = ast(s, k, cell) ?: return
        val refs = FxPrinter.refs(fx).map { (sh, r) -> (if (sh == null) s else sheet(sh)) to r }
        precedents[g] = refs
        for ((sh, r) in refs) {
            if (sh == null) continue
            if (r.r1 == r.r2 && r.c1 == r.c2) cellDeps.getOrPut(G(sh, Sheet5.key(r.r1, r.c1))) { HashSet() }.add(g)
            else rangeDeps.getOrPut(sh) { ArrayList() }.add(RangeDep(r, g))
        }
        if (FxPrinter.calls(fx).any { FxFunctions.isVolatile(it) }) volatile.add(g)
    }

    private fun unlink(g: G) {
        val refs = precedents.remove(g) ?: return
        volatile.remove(g)
        for ((sh, r) in refs) {
            if (sh == null) continue
            if (r.r1 == r.r2 && r.c1 == r.c2) cellDeps[G(sh, Sheet5.key(r.r1, r.c1))]?.remove(g)
            else rangeDeps[sh]?.removeAll { it.dep == g && it.range == r }
        }
    }

    /** Topological evaluation of [cells] (formula cells); members of cycles get #CIRC!. */
    private fun evaluate(cells: List<G>) {
        if (cells.isEmpty()) return
        val set = cells.toHashSet()
        val indeg = HashMap<G, Int>(cells.size * 2)
        val out = HashMap<G, ArrayList<G>>()
        for (g in cells) {
            var n = 0
            for ((sh, r) in precedents[g] ?: emptyList()) {
                if (sh == null) continue
                if (r.size <= set.size) {
                    for (rr in r.r1..r.r2) for (cc in r.c1..r.c2) {
                        val p = G(sh, Sheet5.key(rr, cc))
                        if (p in set) { n++; out.getOrPut(p) { ArrayList() }.add(g) }
                    }
                } else for (p in cells) if (p.sheet === sh && r.contains(Sheet5.row(p.key), Sheet5.col(p.key))) { n++; out.getOrPut(p) { ArrayList() }.add(g) }
            }
            indeg[g] = n
        }
        val ready = ArrayDeque<G>()
        indeg.forEach { (g, n) -> if (n == 0) ready.add(g) }
        var done = 0
        while (ready.isNotEmpty()) {
            val g = ready.removeFirst(); done++
            compute(g)
            out[g]?.forEach { d -> val n = indeg[d]!! - 1; indeg[d] = n; if (n == 0) ready.add(d) }
        }
        circular.removeAll { (name, k) -> set.any { it.sheet.name == name && it.key == k } }
        if (done < cells.size) for ((g, n) in indeg) if (n > 0) { g.sheet.values[g.key] = FV.CIRC; circular.add(g.sheet.name to g.key) }
    }

    private fun compute(g: G) {
        val cell = g.sheet.cells[g.key] ?: return
        val fx = ast(g.sheet, g.key, cell)
        if (fx == null) { g.sheet.values[g.key] = cell.cached ?: FV.NAME; return }
        val v = try {
            FxEval(Ctx(g.sheet, Sheet5.row(g.key), Sheet5.col(g.key))).evaluate(fx)
        } catch (e: FxUnsupported) { cell.cached ?: FV.NAME } catch (e: StackOverflowError) { FV.NUM }
        g.sheet.values[g.key] = if (v is FV.Arr) v.first() else v
    }

    /** True when the formula of the cell uses a function the engine does not implement (its value is Excel's). */
    fun usesUnsupported(s: Sheet5, r: Int, c: Int): Boolean {
        val cell = s.cell(r, c) ?: return false
        val fx = cell.formula?.let { FxParser.tryParse(it) } ?: return cell.formula != null
        return FxPrinter.calls(fx).any { FxFunctions[it.removePrefix("_XLFN.")] == null }
    }

    // ---------------- undo snapshots ----------------

    class SheetSnap internal constructor(
        val sheet: Sheet5, val name: String, val cells: HashMap<Long, Cell5>, val widths: TreeMap<Int, Float>, val heights: HashMap<Int, Float>,
        val merges: List<CRange>, val freezeRows: Int, val freezeCols: Int, val rtl: Boolean, val partPath: String?,
    )

    class Snapshot internal constructor(val sheets: List<SheetSnap>, val active: Int)

    fun snapshot(active: Int = 0): Snapshot = Snapshot(sheets.map { s ->
        SheetSnap(s, s.name, HashMap(s.cells), TreeMap(s.colWidths), HashMap(s.rowHeights), ArrayList(s.merges), s.freezeRows, s.freezeCols, s.rtl, s.partPath)
    }, active)

    fun restore(snap: Snapshot) {
        sheets.clear()
        for (ss in snap.sheets) {
            val s = ss.sheet
            s.name = ss.name
            s.cells.clear(); s.cells.putAll(ss.cells)
            s.colWidths.clear(); s.colWidths.putAll(ss.widths)
            s.rowHeights.clear(); s.rowHeights.putAll(ss.heights)
            s.merges.clear(); s.merges.addAll(ss.merges)
            s.freezeRows = ss.freezeRows; s.freezeCols = ss.freezeCols; s.rtl = ss.rtl; s.partPath = ss.partPath
            s.dirty = true
            sheets.add(s)
        }
        recalcAll()
    }
}

/** Undo/redo stack of whole-book snapshots (cells are immutable, so a snapshot copies only the maps). */
class BookUndo(private val book: SheetBook, private val max: Int = 50) {
    private val undo = ArrayDeque<SheetBook.Snapshot>()
    private val redo = ArrayDeque<SheetBook.Snapshot>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun checkpoint(active: Int) { undo.addLast(book.snapshot(active)); redo.clear(); while (undo.size > max) undo.removeFirst() }

    /** Returns the active sheet index stored with the step, or -1. */
    fun undo(active: Int): Int { val s = undo.removeLastOrNull() ?: return -1; redo.addLast(book.snapshot(active)); book.restore(s); return s.active }
    fun redo(active: Int): Int { val s = redo.removeLastOrNull() ?: return -1; undo.addLast(book.snapshot(active)); book.restore(s); return s.active }
}
