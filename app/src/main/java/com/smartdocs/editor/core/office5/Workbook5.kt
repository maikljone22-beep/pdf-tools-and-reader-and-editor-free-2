package com.smartdocs.editor.core.office5

/**
 * An editable workbook: the [SheetBook] model, its styles, and the package it came from (null for CSV / new).
 * Every edit goes through [change] (one undo step). Structural edits rewrite formulas in every sheet.
 */
class Workbook5(val book: SheetBook, val styles: XStyles, val pkg: OPackage?, val kind: Kind) {
    enum class Kind { XLSX, CSV, NEW }

    private val undoStack = BookUndo(book)
    var version = 0; private set
    var modified = false
    val canUndo get() = undoStack.canUndo
    val canRedo get() = undoStack.canRedo

    /** IO bookkeeping of the source file (set by [XlsxIO5] / [CsvIO]). */
    var meta: XlsxIO5.Meta? = null

    init { book.numFmtOf = { styles.numFmtOfStyle(it) } }

    fun <T> change(active: Int, block: () -> T): T {
        undoStack.checkpoint(active)
        val r = block(); modified = true; version++; return r
    }

    /** Undo; returns the sheet index to show, or -1. */
    fun undo(active: Int): Int = undoStack.undo(active).also { if (it >= 0) { modified = true; version++ } }
    fun redo(active: Int): Int = undoStack.redo(active).also { if (it >= 0) { modified = true; version++ } }

    fun format(s: Sheet5, r: Int, c: Int): CellFormat = styles.format(s.cell(r, c)?.style ?: 0)

    // ---------------- cell input ----------------

    /** Sets typed [text] in a cell; returns an error position message when the formula does not parse. */
    fun setInput(s: Sheet5, r: Int, c: Int, text: String): String? {
        val p = book.parseInput(text)
        if (p.formula != null) {
            try { FxParser.parse(p.formula) } catch (e: FxParseException) { return e.message ?: "syntax" }
        }
        val old = s.cell(r, c)
        var style = old?.style ?: 0
        if (p.impliedFormat != null && styles.format(style).numFmt == "General") style = styles.styleFor(styles.format(style).copy(numFmt = p.impliedFormat), style)
        val keepSst = old?.takeIf { it.literal == p.literal && p.formula == null }?.sst ?: -1
        book.put(s, r, c, Cell5(p.formula, p.literal, style, keepSst))
        book.recalc(listOf(Triple(s, r, c)))
        return null
    }

    fun clear(s: Sheet5, range: CRange, contents: Boolean = true, formats: Boolean = false) {
        val changed = ArrayList<Triple<Sheet5, Int, Int>>()
        for (k in cellsIn(s, range)) {
            val cell = s.cells[k] ?: continue
            val r = Sheet5.row(k); val c = Sheet5.col(k)
            val n = when {
                contents && formats -> null
                contents -> Cell5(null, FV.Blank, cell.style)
                else -> cell.withStyle(0)
            }
            book.put(s, r, c, n); changed.add(Triple(s, r, c))
        }
        book.recalc(changed)
    }

    /** Keys of existing cells inside [range]. */
    fun cellsIn(s: Sheet5, range: CRange): List<Long> =
        if (range.size <= s.cells.size) (range.r1..range.r2).flatMap { r -> (range.c1..range.c2).map { c -> Sheet5.key(r, c) } }.filter { s.cells.containsKey(it) }
        else s.cells.keys.filter { range.contains(Sheet5.row(it), Sheet5.col(it)) }

    /** Applies a format change to every cell of [range] (empty cells get a style too, up to 200k cells). */
    fun applyFormat(s: Sheet5, range: CRange, f: (CellFormat) -> CellFormat) {
        val rect = clip(s, range)
        val memo = HashMap<Int, Int>()
        for (r in rect.r1..rect.r2) for (c in rect.c1..rect.c2) {
            val cell = s.cell(r, c)
            val st = cell?.style ?: 0
            val ns = memo.getOrPut(st) { styles.styleFor(f(styles.format(st)), st) }
            if (ns == st) continue
            book.put(s, r, c, (cell ?: Cell5(null, FV.Blank)).withStyle(ns))
        }
        book.recalcAll()
    }

    /** Like [applyFormat] but the change may depend on the cell position (borders: outside edges, etc.). */
    fun applyFormatAt(s: Sheet5, range: CRange, f: (Int, Int, CellFormat) -> CellFormat) {
        val rect = clip(s, range)
        for (r in rect.r1..rect.r2) for (c in rect.c1..rect.c2) {
            val cell = s.cell(r, c)
            val st = cell?.style ?: 0
            val ns = styles.styleFor(f(r, c, styles.format(st)), st)
            if (ns != st) book.put(s, r, c, (cell ?: Cell5(null, FV.Blank)).withStyle(ns))
        }
        book.recalcAll()
    }

    /** Column width that fits the longest displayed text of column [c] (character units). */
    fun fitWidth(s: Sheet5, c: Int): Float {
        var best = 0
        for (k in s.cells.keys) if (Sheet5.col(k) == c) best = maxOf(best, book.display(s, Sheet5.row(k), c).text.length)
        return (best * 1.15f + 1.5f).coerceIn(4f, 80f)
    }

    /** Whole-column/row selections are clipped to the used area (plus the selection start). */
    private fun clip(s: Sheet5, range: CRange): CRange {
        if (range.size <= 200_000) return range
        val u = s.usedRange() ?: return CRange.cell(range.r1, range.c1)
        return CRange(range.r1, range.c1, minOf(range.r2, maxOf(u.r2, range.r1)), minOf(range.c2, maxOf(u.c2, range.c1)))
    }

    // ---------------- rows / columns ----------------

    private fun rewriteFormulas(target: Sheet5, rows: Boolean, at: Int, count: Int) {
        for (sh in book.sheets) for ((k, cell) in sh.cells.entries.toList()) {
            val f = cell.formula ?: continue
            val fx = FxParser.tryParse(f) ?: continue
            val out = FxPrinter.shift(fx, { name -> if (name == null) sh === target else name.equals(target.name, true) }, rows, at, count) ?: continue
            sh.cells[k] = Cell5(FxPrinter.print(out), cell.literal, cell.style, cell.sst, cell.cached); sh.dirty = true
        }
    }

    /** Inserts (count > 0) or deletes (count < 0) rows at [at]. */
    fun shiftRows(s: Sheet5, at: Int, count: Int) = shift(s, true, at, count)
    fun shiftCols(s: Sheet5, at: Int, count: Int) = shift(s, false, at, count)

    private fun shift(s: Sheet5, rows: Boolean, at: Int, count: Int) {
        if (count == 0) return
        rewriteFormulas(s, rows, at, count)
        val moved = HashMap<Long, Cell5>()
        for ((k, cell) in s.cells) {
            val r = Sheet5.row(k); val c = Sheet5.col(k)
            val n = A1.shiftIndex(if (rows) r else c, at, count) ?: continue
            if (n >= (if (rows) MAX_ROWS else MAX_COLS)) continue
            moved[if (rows) Sheet5.key(n, c) else Sheet5.key(r, n)] = cell
        }
        s.cells.clear(); s.cells.putAll(moved)
        if (rows) s.rowAttrs.clear() else { s.colStyles.clear(); s.colHidden.clear() }
        if (rows) {
            val h = HashMap<Int, Float>(); s.rowHeights.forEach { (r, v) -> A1.shiftIndex(r, at, count)?.let { h[it] = v } }
            s.rowHeights.clear(); s.rowHeights.putAll(h)
        } else {
            val w = java.util.TreeMap<Int, Float>(); s.colWidths.forEach { (c, v) -> A1.shiftIndex(c, at, count)?.let { w[it] = v } }
            s.colWidths.clear(); s.colWidths.putAll(w)
        }
        val ms = s.merges.mapNotNull { A1.shiftRange(it, rows, at, count) }.filter { it.size > 1 }
        s.merges.clear(); s.merges.addAll(ms)
        s.dirty = true
        book.recalcAll()
    }

    // ---------------- merge / sizes / freeze ----------------

    fun merge(s: Sheet5, range: CRange) {
        if (range.size <= 1) return
        s.merges.removeAll { it.intersects(range) }
        s.merges.add(range)
        val changed = ArrayList<Triple<Sheet5, Int, Int>>()
        for (k in cellsIn(s, range)) {
            val r = Sheet5.row(k); val c = Sheet5.col(k)
            if (r == range.r1 && c == range.c1) continue
            val cell = s.cells[k]!!
            book.put(s, r, c, Cell5(null, FV.Blank, cell.style)); changed.add(Triple(s, r, c))
        }
        s.dirty = true
        book.recalc(changed)
    }

    fun unmerge(s: Sheet5, r: Int, c: Int): Boolean { val m = s.mergeAt(r, c) ?: return false; s.merges.remove(m); s.dirty = true; return true }

    fun setColWidth(s: Sheet5, cols: IntRange, w: Float) { cols.forEach { s.colWidths[it] = w.coerceIn(0.5f, 255f) }; s.dirty = true }
    fun setRowHeight(s: Sheet5, rows: IntRange, h: Float) { rows.forEach { s.rowHeights[it] = h.coerceIn(2f, 409f) }; s.dirty = true }
    fun freeze(s: Sheet5, rows: Int, cols: Int) { s.freezeRows = rows.coerceAtLeast(0); s.freezeCols = cols.coerceAtLeast(0); s.dirty = true }

    // ---------------- sort ----------------

    /** Sorts the rows of [range] by column [col] (absolute index); [header] keeps the first row in place. */
    fun sort(s: Sheet5, range: CRange, col: Int, ascending: Boolean, header: Boolean) {
        val r0 = if (header) range.r1 + 1 else range.r1
        if (r0 >= range.r2) return
        val rows = (r0..range.r2).toList()
        val sorted = rows.sortedWith { a, b ->
            val va = book.value(s, a, col); val vb = book.value(s, b, col)
            when {
                va is FV.Blank && vb is FV.Blank -> 0
                va is FV.Blank -> 1
                vb is FV.Blank -> -1
                else -> FxCoerce.compare(va, vb).let { if (ascending) it else -it }
            }
        }
        val snapshot = rows.associateWith { r -> (range.c1..range.c2).map { c -> s.cell(r, c) } }
        for ((i, src) in sorted.withIndex()) {
            val dst = rows[i]
            for ((j, c) in (range.c1..range.c2).withIndex()) {
                val cell = snapshot[src]!![j]
                book.put(s, dst, c, cell?.let { translated(it, dst - src, 0) })
            }
        }
        book.recalcAll()
    }

    private fun translated(cell: Cell5, dr: Int, dc: Int): Cell5 {
        val f = cell.formula ?: return cell
        if (dr == 0 && dc == 0) return cell
        val fx = FxParser.tryParse(f) ?: return cell
        return Cell5(FxPrinter.print(FxPrinter.translate(fx, dr, dc)), cell.literal, cell.style, -1, null)
    }

    // ---------------- autofill ----------------

    /** Fills [target] (which extends [src] down, up, right or left) continuing series or copying. */
    fun fill(s: Sheet5, src: CRange, target: CRange) {
        val vertical = target.c1 == src.c1 && target.c2 == src.c2
        val forward = if (vertical) target.r2 > src.r2 else target.c2 > src.c2
        val lines = if (vertical) src.c1..src.c2 else src.r1..src.r2
        val changed = ArrayList<Triple<Sheet5, Int, Int>>()
        for (line in lines) {
            val seedPos = if (vertical) (src.r1..src.r2).toList() else (src.c1..src.c2).toList()
            val seeds = seedPos.map { p -> if (vertical) s.cell(p, line) else s.cell(line, p) }
            val targets = if (vertical) (if (forward) (src.r2 + 1)..target.r2 else (target.r1 until src.r1).reversed())
                else (if (forward) (src.c2 + 1)..target.c2 else (target.c1 until src.c1).reversed())
            val series = FillSeries.of(seeds, s.cell(if (vertical) src.r1 else line, if (vertical) line else src.c1)?.style?.let { styles.format(it).numFmt })
            for ((k, p) in targets.withIndex()) {
                val step = k + 1
                val seedIdx = if (forward) k % seeds.size else (seeds.size - 1 - k % seeds.size)
                val seed = seeds[seedIdx]
                val srcPos = seedPos[seedIdx]
                val r = if (vertical) p else line; val c = if (vertical) line else p
                val cell = when {
                    seed == null -> null
                    seed.formula != null -> translated(seed, if (vertical) r - srcPos else 0, if (vertical) 0 else c - srcPos)
                    else -> Cell5(null, series.value(if (forward) step else -step, seedIdx), seed.style)
                }
                book.put(s, r, c, cell); changed.add(Triple(s, r, c))
            }
        }
        book.recalc(changed)
    }

    // ---------------- clipboard ----------------

    class Clip(val rows: Int, val cols: Int, val cells: List<Cell5?>, val srcRow: Int, val srcCol: Int, val text: String)

    fun copy(s: Sheet5, range: CRange): Clip {
        val cells = ArrayList<Cell5?>()
        val tsv = StringBuilder()
        for (r in range.r1..range.r2) {
            for (c in range.c1..range.c2) { cells.add(s.cell(r, c)); if (c > range.c1) tsv.append('\t'); tsv.append(book.display(s, r, c).text) }
            if (r < range.r2) tsv.append('\n')
        }
        return Clip(range.rows, range.cols, cells, range.r1, range.c1, tsv.toString())
    }

    fun paste(clip: Clip, s: Sheet5, r: Int, c: Int, valuesOnly: Boolean = false, srcSheet: Sheet5? = null) {
        val changed = ArrayList<Triple<Sheet5, Int, Int>>()
        for (i in 0 until clip.rows) for (j in 0 until clip.cols) {
            val cell = clip.cells[i * clip.cols + j]
            val rr = r + i; val cc = c + j
            if (rr >= MAX_ROWS || cc >= MAX_COLS) continue
            val n = when {
                cell == null -> null
                valuesOnly && cell.formula != null -> Cell5(null, srcSheet?.let { book.value(it, clip.srcRow + i, clip.srcCol + j) }?.takeIf { it !is FV.Arr } ?: cell.cached ?: FV.Blank, cell.style)
                else -> translated(cell, rr - (clip.srcRow + i), cc - (clip.srcCol + j))
            }
            book.put(s, rr, cc, n); changed.add(Triple(s, rr, cc))
        }
        book.recalc(changed)
    }

    /** Pastes tab-separated text (from another app) as typed input. */
    fun pasteText(text: String, s: Sheet5, r: Int, c: Int) {
        text.trimEnd('\n', '\r').split('\n').forEachIndexed { i, line ->
            line.trimEnd('\r').split('\t').forEachIndexed { j, v -> setInput(s, r + i, c + j, v) }
        }
    }

    // ---------------- sheets ----------------

    fun uniqueName(base: String): String {
        var n = book.sheets.size + 1
        while (book.sheet("$base$n") != null) n++
        return "$base$n"
    }

    fun addSheet(name: String, at: Int = book.sheets.size): Sheet5 {
        val s = Sheet5(name).also { it.dirty = true; it.rtl = book.sheets.firstOrNull()?.rtl ?: false }
        book.sheets.add(at.coerceIn(0, book.sheets.size), s)
        book.recalcAll()
        return s
    }

    /** Validates a sheet name like Excel; returns null when OK, else a reason key. */
    fun checkName(name: String, except: Sheet5? = null): String? = when {
        name.isBlank() -> "empty"
        name.length > 31 -> "long"
        name.any { it in "\\/?*[]:" } || name.startsWith("'") || name.endsWith("'") -> "chars"
        book.sheets.any { it !== except && it.name.equals(name, true) } -> "exists"
        else -> null
    }

    fun renameSheet(s: Sheet5, name: String) {
        val old = s.name
        for (sh in book.sheets) for ((k, cell) in sh.cells.entries.toList()) {
            val fx = cell.formula?.let { FxParser.tryParse(it) } ?: continue
            val out = FxPrinter.renameSheet(fx, old, name) ?: continue
            sh.cells[k] = Cell5(FxPrinter.print(out), cell.literal, cell.style, cell.sst, cell.cached); sh.dirty = true
        }
        s.name = name
        book.recalcAll()
    }

    fun deleteSheet(s: Sheet5): Boolean {
        if (book.sheets.size <= 1) return false
        book.sheets.remove(s)
        for (sh in book.sheets) for ((k, cell) in sh.cells.entries.toList()) {
            val fx = cell.formula?.let { FxParser.tryParse(it) } ?: continue
            val out = FxPrinter.dropSheet(fx, s.name) ?: continue
            sh.cells[k] = Cell5(FxPrinter.print(out), cell.literal, cell.style, cell.sst, cell.cached); sh.dirty = true
        }
        book.recalcAll()
        return true
    }

    fun moveSheet(from: Int, to: Int) {
        if (from !in book.sheets.indices || to !in book.sheets.indices || from == to) return
        val s = book.sheets.removeAt(from); book.sheets.add(to, s)
    }

    /** SUM/AVERAGE/COUNT/MIN/MAX of the numbers in a selection, for the status bar. */
    fun aggregates(s: Sheet5, range: CRange): DoubleArray? {
        var sum = 0.0; var n = 0; var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE; var nonBlank = 0
        for (k in cellsIn(s, range)) {
            val v = book.value(s, Sheet5.row(k), Sheet5.col(k))
            if (v !is FV.Blank) nonBlank++
            if (v is FV.Num) { sum += v.v; n++; mn = minOf(mn, v.v); mx = maxOf(mx, v.v) }
        }
        if (nonBlank == 0) return null
        return doubleArrayOf(sum, if (n > 0) sum / n else 0.0, n.toDouble(), if (n > 0) mn else 0.0, if (n > 0) mx else 0.0, nonBlank.toDouble())
    }
}
