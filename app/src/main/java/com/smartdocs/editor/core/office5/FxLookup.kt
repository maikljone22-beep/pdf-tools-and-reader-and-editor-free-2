package com.smartdocs.editor.core.office5

/** Lookup and reference functions beyond VLOOKUP / HLOOKUP / CHOOSE in [FxFunctions]. */
internal fun FxFunctions.registerLookup() {
    reg("ROW", "lookup", 0, 1) { a -> FV.Num(refRange(a).r1 + 1.0) }
    reg("COLUMN", "lookup", 0, 1) { a -> FV.Num(refRange(a).c1 + 1.0) }
    reg("ROWS", "lookup", 1) { a -> FV.Num(shape(a).first.toDouble()) }
    reg("COLUMNS", "lookup", 1) { a -> FV.Num(shape(a).second.toDouble()) }
    reg("INDEX", "lookup", 2, 3) { a ->
        val t = a.arr(0); var r = a.int(1, 0); var c = a.int(2, 0)
        if (a.size == 2 && t.rows == 1) { c = r; r = 1 }                        // one number on a single row counts along it
        if (r < 0 || c < 0) FV.VALUE else if (r > t.rows || c > t.cols) FV.REF
        else if (r > 0 && c > 0) t[r - 1, c - 1]
        else if (r > 0) (if (t.cols == 1) t[r - 1, 0] else FV.Arr(1, t.cols, Array(t.cols) { t[r - 1, it] }))          // 0 = the whole row
        else if (c > 0) (if (t.rows == 1) t[0, c - 1] else FV.Arr(t.rows, 1, Array(t.rows) { t[it, c - 1] }))          // 0 = the whole column
        else if (t.data.size == 1) t.data[0] else t
    }
    reg("MATCH", "lookup", 2, 3) { a ->
        val key = lookupKey(a); val t = a.arr(1); val type = if (a.size < 3) 1.0 else a.num(2)        // MATCH(x, r,) is an exact match
        if (key is FV.Err) key else if (t.rows != 1 && t.cols != 1) FV.NA else {
            val i = if (type == 0.0) findExact(t.data, key, wildcards = true, reverse = false) else findSorted(t.data, key, ascending = type > 0)
            if (i < 0) FV.NA else FV.Num(i + 1.0)
        }
    }
    reg("XMATCH", "lookup", 2, 4) { a ->
        val key = lookupKey(a); val t = a.arr(1); val mode = a.int(2, 0); val search = a.int(3, 1)
        if (key is FV.Err) key else if ((t.rows != 1 && t.cols != 1) || mode !in -1..2 || search !in SEARCH_MODES) FV.VALUE else {
            val i = xFind(t.data, key, mode, search)
            if (i < 0) FV.NA else FV.Num(i + 1.0)
        }
    }
    reg("XLOOKUP", "lookup", 3, 6) { a ->
        val key = lookupKey(a); val t = a.arr(1); val r = a.arr(2); val mode = a.int(4, 0); val search = a.int(5, 1)
        val down = t.cols == 1
        if (key is FV.Err) key
        else if ((t.rows != 1 && t.cols != 1) || mode !in -1..2 || search !in SEARCH_MODES) FV.VALUE
        else if (if (down) r.rows != t.rows else r.cols != t.cols) FV.VALUE
        else {
            val i = xFind(t.data, key, mode, search)
            if (i < 0) (if (a.missing(3)) FV.NA else a.value(3))
            else if (down) (if (r.cols == 1) r[i, 0] else FV.Arr(1, r.cols, Array(r.cols) { r[i, it] }))
            else (if (r.rows == 1) r[0, i] else FV.Arr(r.rows, 1, Array(r.rows) { r[it, i] }))
        }
    }
    reg("LOOKUP", "lookup", 2, 3) { a ->
        val key = lookupKey(a); val t = a.arr(1)
        if (key is FV.Err) key
        else if (!a.missing(2)) {
            // vector form: a one-row or one-column vector to search and one of the same length to answer from
            val r = a.arr(2)
            if ((t.rows != 1 && t.cols != 1) || (r.rows != 1 && r.cols != 1)) FV.NA
            else findSorted(t.data, key, ascending = true).let { i -> if (i < 0) FV.NA else r.data.getOrNull(i) ?: FV.NA }
        } else if (t.cols > t.rows) {
            // array form: searches the first row of a wide array (the first column otherwise) and answers from the last one
            findSorted(Array(t.cols) { t[0, it] }, key, ascending = true).let { i -> if (i < 0) FV.NA else t[t.rows - 1, i] }
        } else if (t.data.isEmpty()) FV.NA
        else findSorted(Array(t.rows) { t[it, 0] }, key, ascending = true).let { i -> if (i < 0) FV.NA else t[i, t.cols - 1] }
    }
}

/** XLOOKUP / XMATCH search modes: 1 first to last, -1 last to first, 2 and -2 binary searches of sorted data. */
private val SEARCH_MODES = setOf(1, -1, 2, -2)

/** The value looked up; an empty cell looks up 0, like Excel. */
private fun lookupKey(a: FxArgs): FV = a.value(0).let { if (it is FV.Blank) FV.Num(0.0) else it }

/** Range of the reference argument of ROW / COLUMN (the formula's own cell when omitted); a non-reference is #VALUE!. */
private fun refRange(a: FxArgs): CRange {
    if (a.missing(0)) return CRange.cell(a.ev.ctx.row, a.ev.ctx.col)
    return refOf(a) ?: throw FxError(FV.VALUE)
}

/** The range argument 0 names when it is a reference (a missing sheet is #REF!), or null. */
private fun refOf(a: FxArgs): CRange? {
    var n = a.nodes[0]
    while (n is Fx.Paren) n = n.e
    val sheet = when (n) { is Fx.Ref -> n.sheet; is Fx.Area -> n.sheet; else -> return null }
    if (sheet != null && !a.ev.ctx.sheetExists(sheet)) throw FxError(FV.REF)
    return if (n is Fx.Ref) CRange.cell(n.ref.row, n.ref.col) else (n as Fx.Area).range
}

/** Rows and columns of argument 0: a reference counts as written (A:A has every row), anything else by its value. */
private fun shape(a: FxArgs): Pair<Int, Int> {
    refOf(a)?.let { return it.rows to it.cols }
    return when (val v = a.raw(0)) { is FV.Err -> throw FxError(v); is FV.Arr -> v.rows to v.cols; else -> 1 to 1 }
}

/** First (or last) cell equal to [key]; text ignores case and, with [wildcards], a key with ? or * is a pattern. */
private fun findExact(d: Array<FV>, key: FV, wildcards: Boolean, reverse: Boolean): Int {
    val rx = if (wildcards && key is FV.Str && (key.v.contains('*') || key.v.contains('?'))) FxCriteria.wildcard(key.v) else null
    for (i in if (reverse) d.indices.reversed() else d.indices) {
        val k = d[i]
        if (if (rx != null) k is FV.Str && rx.matches(k.v) else FxFunctions.sameKind(k, key) && FxCoerce.compare(k, key) == 0) return i
    }
    return -1
}

/**
 * Approximate match in sorted data: the last cell <= [key] when [ascending], the last cell >= [key] in descending
 * data. Cells of another kind than the key (and errors) are skipped, so a key above everything finds the last one.
 */
private fun findSorted(d: Array<FV>, key: FV, ascending: Boolean): Int {
    var found = -1
    for (i in d.indices) {
        val k = d[i]
        if (!FxFunctions.sameKind(k, key)) continue
        val c = FxCoerce.compare(k, key)
        if (if (ascending) c <= 0 else c >= 0) found = i else break
    }
    return found
}

/** XLOOKUP / XMATCH: [mode] 0 exact, -1 exact or next smaller, 1 exact or next larger, 2 wildcards; [search] -1 runs last to first. */
private fun xFind(d: Array<FV>, key: FV, mode: Int, search: Int): Int {
    val reverse = search == -1
    val exact = findExact(d, key, wildcards = mode == 2, reverse = reverse)
    if (exact >= 0 || mode == 0 || mode == 2) return exact
    var best = -1
    for (i in if (reverse) d.indices.reversed() else d.indices) {
        val k = d[i]
        if (!FxFunctions.sameKind(k, key)) continue
        val c = FxCoerce.compare(k, key)
        if (if (mode < 0) c < 0 else c > 0) {
            // the closest one on that side; the first met wins among equals
            if (best < 0 || (if (mode < 0) FxCoerce.compare(k, d[best]) > 0 else FxCoerce.compare(k, d[best]) < 0)) best = i
        }
    }
    return best
}
