package com.smartdocs.editor.core.convert

import kotlin.math.abs

/**
 * Table detection on one page.
 *  - [ruled]: grids built from painted horizontal + vertical rules (with merged cells).
 *  - [stream]: borderless tables found from column alignment of consecutive multi-chunk lines.
 */
object TableDetector {

    class RuledResult(val tables: List<TableBlock>, val usedWords: Set<Word>)
    class StreamResult(val tables: List<TableBlock>, val usedLines: Set<Line>)

    private const val TOL = 2.0f

    private class H(val y: Float, var x1: Float, var x2: Float)
    private class V(val x: Float, var y1: Float, var y2: Float)

    // ---------------------------------------------------------------- ruled

    fun ruled(segments: List<Segment>, lines: List<Line>): RuledResult {
        val hs = mergeH(segments.filter { it.horizontal }.map { H((it.y1 + it.y2) / 2f, minOf(it.x1, it.x2), maxOf(it.x1, it.x2)) })
        val vs = mergeV(segments.filter { it.vertical }.map { V((it.x1 + it.x2) / 2f, minOf(it.y1, it.y2), maxOf(it.y1, it.y2)) })
        if (hs.size < 2 || vs.size < 2 || hs.size + vs.size > 4000) return RuledResult(emptyList(), emptySet())

        // union-find over intersecting rules
        val n = hs.size + vs.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for ((i, h) in hs.withIndex()) for ((j, v) in vs.withIndex()) {
            if (v.x >= h.x1 - TOL && v.x <= h.x2 + TOL && h.y >= v.y1 - TOL && h.y <= v.y2 + TOL) {
                val a = find(i); val b = find(hs.size + j); if (a != b) parent[a] = b
            }
        }
        val groups = HashMap<Int, Pair<MutableList<H>, MutableList<V>>>()
        hs.forEachIndexed { i, h -> groups.getOrPut(find(i)) { mutableListOf<H>() to mutableListOf() }.first += h }
        vs.forEachIndexed { j, v -> groups.getOrPut(find(hs.size + j)) { mutableListOf<H>() to mutableListOf() }.second += v }

        val allWords = lines.flatMap { it.visual }
        val used = HashSet<Word>()
        val tables = ArrayList<TableBlock>()
        for ((gh, gv) in groups.values) {
            if (gh.size < 2 || gv.size < 2) continue
            val ys = distinct(gh.map { it.y })
            val xs = distinct(gv.map { it.x })
            if (ys.size < 2 || xs.size < 2) continue
            val rows = ys.size - 1; val cols = xs.size - 1
            if (rows * cols < 2 || rows > 300 || cols > 60) continue
            fun hasV(k: Int, ya: Float, yb: Float): Boolean { val m = (ya + yb) / 2f; return gv.any { abs(it.x - xs[k]) <= TOL && it.y1 <= m && it.y2 >= m } }
            fun hasH(k: Int, xa: Float, xb: Float): Boolean { val m = (xa + xb) / 2f; return gh.any { abs(it.y - ys[k]) <= TOL && it.x1 <= m && it.x2 >= m } }
            val occupied = Array(rows) { BooleanArray(cols) }
            data class Raw(val r: Int, val c: Int, val rs: Int, val cs: Int)
            val raws = ArrayList<Raw>()
            for (r in 0 until rows) for (c in 0 until cols) {
                if (occupied[r][c]) continue
                var cs = 1
                while (c + cs < cols && !occupied[r][c + cs] && !hasV(c + cs, ys[r], ys[r + 1])) cs++
                var rs = 1
                while (r + rs < rows && (c until c + cs).all { cc -> !occupied[r + rs][cc] && !hasH(r + rs, xs[cc], xs[cc + 1]) }) rs++
                for (rr in r until r + rs) for (cc in c until c + cs) occupied[rr][cc] = true
                raws += Raw(r, c, rs, cs)
            }
            if (raws.size < 2) continue
            val box = Rect(xs.first(), ys.first(), xs.last(), ys.last())
            val inside = allWords.filter { it !in used && box.contains((it.x0 + it.x1) / 2f, (it.top + it.bottom) / 2f, 1f) }
            if (inside.isEmpty()) continue // grid of a chart / drawing, not a table
            val cells = raws.map { rw ->
                val rect = Rect(xs[rw.c], ys[rw.r], xs[rw.c + rw.cs], ys[rw.r + rw.rs])
                val ws = inside.filter { rect.contains((it.x0 + it.x1) / 2f, (it.top + it.bottom) / 2f, 0.5f) }
                TableCell(rw.r, rw.c, rw.rs, rw.cs, rect, LayoutAnalyzer.linesOfWords(ws))
            }
            used += inside
            tables += TableBlock(xs, ys, cells, ruled = true)
        }
        return RuledResult(tables, used)
    }

    private fun mergeH(src: List<H>): List<H> {
        val out = ArrayList<H>()
        for (h in src.sortedWith(compareBy<H>({ it.y }, { it.x1 }))) {
            val m = out.lastOrNull { abs(it.y - h.y) <= 1.5f && h.x1 <= it.x2 + TOL }
            if (m != null && abs(m.y - h.y) <= 1.5f) m.x2 = maxOf(m.x2, h.x2) else out += H(h.y, h.x1, h.x2)
        }
        return out.filter { it.x2 - it.x1 >= 6f }
    }

    private fun mergeV(src: List<V>): List<V> {
        val out = ArrayList<V>()
        for (v in src.sortedWith(compareBy<V>({ it.x }, { it.y1 }))) {
            val m = out.lastOrNull { abs(it.x - v.x) <= 1.5f && v.y1 <= it.y2 + TOL }
            if (m != null) m.y2 = maxOf(m.y2, v.y2) else out += V(v.x, v.y1, v.y2)
        }
        return out.filter { it.y2 - it.y1 >= 6f }
    }

    /** Sorted distinct coordinates; values closer than 3pt (double rules) collapse to their mean. */
    private fun distinct(values: List<Float>): List<Float> {
        val s = values.sorted()
        val out = ArrayList<Float>()
        var acc = 0f; var cnt = 0; var last = Float.NaN
        for (v in s) {
            if (cnt > 0 && v - last > 3f) { out += acc / cnt; acc = 0f; cnt = 0 }
            acc += v; cnt++; last = v
        }
        if (cnt > 0) out += acc / cnt
        return out
    }

    // ---------------------------------------------------------------- stream (borderless)

    fun stream(lines: List<Line>): StreamResult {
        val sorted = lines.sortedBy { it.top }
        val chunked = sorted.map { it to LayoutAnalyzer.chunks(it) }
        val tables = ArrayList<TableBlock>()
        val used = HashSet<Line>()
        var i = 0
        while (i < chunked.size) {
            if (chunked[i].second.size < 2) { i++; continue }
            var j = i + 1
            while (j < chunked.size && chunked[j].second.size >= 2) {
                val prev = chunked[j - 1].first; val next = chunked[j].first
                if (next.top - prev.bottom > 1.8f * prev.fontSize) break
                j++
            }
            val block = chunked.subList(i, j)
            val t = buildStream(block)
            if (t != null) { tables += t; block.forEach { used += it.first } }
            i = j
        }
        return StreamResult(tables, used)
    }

    private fun buildStream(block: List<Pair<Line, List<List<Word>>>>): TableBlock? {
        val rows = block.size
        // column intervals = union of chunk extents
        val iv = block.flatMap { (_, cs) -> cs.map { c -> floatArrayOf(c.first().x0, c.last().x1) } }.sortedBy { it[0] }
        val cols = ArrayList<FloatArray>()
        for (a in iv) {
            val last = cols.lastOrNull()
            if (last != null && a[0] <= last[1] + 0.5f) last[1] = maxOf(last[1], a[1]) else cols += floatArrayOf(a[0], a[1])
        }
        val nc = cols.size
        if (nc < 2 || nc > 40) return null
        if (rows < 2 || (rows < 3 && nc < 3)) return null
        // each column must be used by at least two rows
        val hits = IntArray(nc)
        block.forEach { (_, cs) -> cs.forEach { c -> val cx = (c.first().x0 + c.last().x1) / 2f; val k = cols.indexOfFirst { cx >= it[0] && cx <= it[1] }; if (k >= 0) hits[k]++ } }
        if (hits.any { it < 2 }) return null
        // a column of stray marks («(», «:», «١») beside running text is decoration or OCR noise, not a table column
        val colChars = IntArray(nc); val colCells = IntArray(nc)
        block.forEach { (_, cs) -> cs.forEach { c -> val cx = (c.first().x0 + c.last().x1) / 2f; val k = cols.indexOfFirst { cx >= it[0] && cx <= it[1] }; if (k >= 0) { colChars[k] += c.sumOf { it.text.length }; colCells[k]++ } } }
        if (nc == 2 && (0 until nc).any { colChars[it] <= 2 * colCells[it] }) return null
        // two columns of running text are a page layout, not a table
        if (nc == 2) {
            val avg = block.flatMap { it.second }.map { c -> c.sumOf { it.text.length } + c.size - 1 }.average()
            if (avg > 30) return null
        }
        // text columns: every column holds about four or more words per row → running text, not a table
        val wordsPerCol = DoubleArray(nc)
        block.forEach { (_, cs) -> cs.forEach { c -> val cx = (c.first().x0 + c.last().x1) / 2f; val k = cols.indexOfFirst { cx >= it[0] && cx <= it[1] }; if (k >= 0) wordsPerCol[k] += c.size.toDouble() } }
        if (wordsPerCol.all { it / rows >= 3.5 }) return null
        val colX = ArrayList<Float>()
        colX += cols.first()[0] - 2f
        for (k in 1 until nc) colX += (cols[k - 1][1] + cols[k][0]) / 2f
        colX += cols.last()[1] + 2f
        val rowY = ArrayList<Float>()
        rowY += block.first().first.top - 2f
        for (k in 1 until rows) rowY += (block[k - 1].first.bottom + block[k].first.top) / 2f
        rowY += block.last().first.bottom + 2f
        val cells = ArrayList<TableCell>()
        block.forEachIndexed { r, (line, _) ->
            for (c in 0 until nc) {
                val ws = line.visual.filter { val cx = (it.x0 + it.x1) / 2f; cx >= colX[c] && cx < colX[c + 1] }
                val rect = Rect(colX[c], rowY[r], colX[c + 1], rowY[r + 1])
                cells += TableCell(r, c, 1, 1, rect, if (ws.isEmpty()) emptyList() else listOf(LayoutAnalyzer.lineOf(ws)))
            }
        }
        return TableBlock(colX, rowY, cells, ruled = false)
    }
}
