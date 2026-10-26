package com.smartdocs.editor.core.office5

/** Excel limits. */
const val MAX_ROWS = 1_048_576
const val MAX_COLS = 16_384

/** One cell reference, 0-based, with the `$` flags and an optional sheet name. */
data class CellRef(val row: Int, val col: Int, val absRow: Boolean = false, val absCol: Boolean = false, val sheet: String? = null) {
    fun a1(): String = (if (absCol) "$" else "") + A1.colName(col) + (if (absRow) "$" else "") + (row + 1)
    override fun toString(): String = (sheet?.let { A1.quoteSheet(it) + "!" } ?: "") + a1()
}

/** A rectangular range, 0-based inclusive, normalised so r1<=r2 and c1<=c2. */
data class CRange(val r1: Int, val c1: Int, val r2: Int, val c2: Int) {
    val rows: Int get() = r2 - r1 + 1
    val cols: Int get() = c2 - c1 + 1
    val size: Long get() = rows.toLong() * cols
    fun contains(r: Int, c: Int) = r in r1..r2 && c in c1..c2
    fun intersects(o: CRange) = r1 <= o.r2 && o.r1 <= r2 && c1 <= o.c2 && o.c1 <= c2
    fun a1(): String = if (r1 == r2 && c1 == c2) A1.name(r1, c1) else A1.name(r1, c1) + ":" + A1.name(r2, c2)
    override fun toString() = a1()

    companion object {
        fun of(r1: Int, c1: Int, r2: Int, c2: Int) = CRange(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
        fun cell(r: Int, c: Int) = CRange(r, c, r, c)
    }
}

object A1 {
    fun colName(c: Int): String {
        var n = c + 1; val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, 'A' + m); n = (n - 1) / 26 }
        return sb.toString()
    }

    /** "A" -> 0, "AA" -> 26; -1 when not letters. */
    fun colIndex(letters: String): Int {
        if (letters.isEmpty() || letters.length > 3) return -1
        var c = 0
        for (ch in letters) { val u = ch.uppercaseChar(); if (u !in 'A'..'Z') return -1; c = c * 26 + (u - 'A' + 1) }
        return (c - 1).takeIf { it < MAX_COLS } ?: -1
    }

    fun name(row: Int, col: Int) = colName(col) + (row + 1)

    private val cellRx = Regex("""^(\$?)([A-Za-z]{1,3})(\$?)(\d{1,7})$""")

    /** "B3", "$B$3" (no sheet). */
    fun parseCell(s: String): CellRef? {
        val m = cellRx.matchEntire(s.trim()) ?: return null
        val c = colIndex(m.groupValues[2]); val r = m.groupValues[4].toIntOrNull()?.minus(1) ?: return null
        if (c < 0 || r < 0 || r >= MAX_ROWS) return null
        return CellRef(r, c, m.groupValues[3] == "$", m.groupValues[1] == "$")
    }

    /** "A1:C4" or "B2" (no sheet). */
    fun parseRange(s: String): CRange? {
        val parts = s.trim().split(':')
        val a = parseCell(parts[0]) ?: return null
        val b = if (parts.size > 1) parseCell(parts[1]) ?: return null else a
        return CRange.of(a.row, a.col, b.row, b.col)
    }

    fun quoteSheet(name: String): String =
        if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == '.' } && !name[0].isDigit() && parseCell(name) == null) name
        else "'" + name.replace("'", "''") + "'"

    // ---------------- structural shifts ----------------

    /**
     * New index of [i] after inserting [count] lines at [at] (count > 0) or deleting [-count] lines starting
     * at [at] (count < 0); null when the line itself is deleted.
     */
    fun shiftIndex(i: Int, at: Int, count: Int): Int? = when {
        count >= 0 -> if (i >= at) i + count else i
        i < at -> i
        i < at - count -> null
        else -> i + count
    }

    /** Shifts a span [a, b] (inclusive) on one axis; null when everything is deleted. */
    fun shiftSpan(a: Int, b: Int, at: Int, count: Int): Pair<Int, Int>? {
        if (count >= 0) return (if (a >= at) a + count else a) to (if (b >= at) b + count else b)
        val delEnd = at - count - 1
        if (a >= at && b <= delEnd) return null
        val na = if (a < at) a else if (a <= delEnd) at else a + count
        val nb = if (b < at) b else if (b <= delEnd) at - 1 else b + count
        return if (nb < na) null else na to nb
    }

    fun shiftRange(r: CRange, rows: Boolean, at: Int, count: Int): CRange? =
        if (rows) shiftSpan(r.r1, r.r2, at, count)?.let { CRange(it.first, r.c1, it.second, r.c2) }
        else shiftSpan(r.c1, r.c2, at, count)?.let { CRange(r.r1, it.first, r.r2, it.second) }
}
