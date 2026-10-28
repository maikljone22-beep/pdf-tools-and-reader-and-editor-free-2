package com.smartdocs.editor.core.view5

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A run of a text line separated from its neighbours by a wide gap (a table cell candidate), PDF space. */
data class RSeg(val text: String, val left: Float, val right: Float)

/** A straight ruling line of the page (PDF space, y up): horizontal when [y1] == [y2], vertical when [x1] == [x2]. */
data class RRule(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val horizontal: Boolean get() = abs(y1 - y2) < 0.75f && abs(x2 - x1) > 0.75f
    val vertical: Boolean get() = abs(x1 - x2) < 0.75f && abs(y2 - y1) > 0.75f
}

/** A table found on a page: [rows] of cell texts in page order (left to right), [lines] the indexes of the lines it used. */
data class RTable(val top: Float, val bottom: Float, val left: Float, val right: Float, val rows: List<List<String>>, val header: Boolean, val lines: Set<Int>)

/**
 * Table rebuilding for the reflow reader (OLD A1): lines are cut into segments at wide gaps; consecutive lines
 * with two or more segments that line up form a table; columns come from the overlap of the segments (or
 * from vertical ruling lines when the page draws them), rows from the lines (or from horizontal rules, so a
 * cell may hold several lines). Two-column prose is not a table. Pure, unit tested with synthetic boxes.
 */
object ReflowTables {

    /**
     * Segments of a line: [text] with per-character boxes [l]..[r] (NaN for characters without a box, such as
     * generated spaces). A gap wider than [gap] (points) starts a new segment. Text keeps its logical order.
     */
    fun segment(text: String, l: FloatArray, r: FloatArray, gap: Float): List<RSeg> {
        val n = minOf(text.length, l.size, r.size)
        val ink = (0 until n).filter { !l[it].isNaN() && !r[it].isNaN() && !text[it].isWhitespace() }
        if (ink.isEmpty()) return emptyList()
        // x intervals of the segments, by sweeping the inked boxes left to right
        val byX = ink.sortedBy { l[it] }
        val spans = ArrayList<FloatArray>()
        var cur = floatArrayOf(l[byX[0]], r[byX[0]])
        for (k in 1 until byX.size) {
            val i = byX[k]
            if (l[i] - cur[1] > gap) { spans += cur; cur = floatArrayOf(l[i], r[i]) } else cur[1] = max(cur[1], r[i])
        }
        spans += cur
        if (spans.size == 1) return listOf(RSeg(text.substring(0, n).trim(), cur[0], cur[1]))
        fun spanOf(i: Int): Int { val c = (l[i] + r[i]) / 2f; return spans.indexOfFirst { c >= it[0] - 0.01f && c <= it[1] + 0.01f }.coerceAtLeast(0) }
        val parts = Array(spans.size) { StringBuilder() }
        var last = -1
        for (i in 0 until n) {
            val s = if (!l[i].isNaN() && !r[i].isNaN() && !text[i].isWhitespace()) spanOf(i) else last
            if (s < 0) continue
            parts[s].append(text[i]); last = s
        }
        return spans.indices.mapNotNull { k -> parts[k].toString().replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }?.let { RSeg(it, spans[k][0], spans[k][1]) } }
    }

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float) = min(a1, b1) - max(a0, b0)

    /** Column intervals from the segments of the table rows: overlapping segments merge into one column. */
    fun columns(rows: List<RLine>): List<FloatArray> {
        val iv = rows.flatMap { it.segs }.map { floatArrayOf(it.left, it.right) }.sortedBy { it[0] }
        val out = ArrayList<FloatArray>()
        for (s in iv) {
            val last = out.lastOrNull()
            if (last != null && s[0] <= last[1] + 1f) last[1] = max(last[1], s[1]) else out += floatArrayOf(s[0], s[1])
        }
        return out
    }

    /** Columns between vertical rules ([xs] sorted), kept when a segment falls in them. */
    private fun ruledColumns(xs: List<Float>, rows: List<RLine>): List<FloatArray> {
        val segs = rows.flatMap { it.segs }
        val lo = min(segs.minOf { it.left }, xs.first()) - 1f; val hi = max(segs.maxOf { it.right }, xs.last()) + 1f
        val cuts = (listOf(lo) + xs + listOf(hi)).distinct()
        return cuts.zipWithNext().map { (a, b) -> floatArrayOf(a, b) }.filter { c -> segs.any { s -> (s.left + s.right) / 2f in c[0]..c[1] } }
    }

    /** Index of the column [s] overlaps most. */
    private fun columnOf(cols: List<FloatArray>, s0: Float, s1: Float): Int {
        var best = 0; var bo = -Float.MAX_VALUE
        cols.forEachIndexed { k, c ->
            val o = overlap(c[0], c[1], s0, s1).let { if (it <= 0f) -abs((c[0] + c[1]) / 2f - (s0 + s1) / 2f) else it }
            if (o > bo) { bo = o; best = k }
        }
        return best
    }

    /** Merges unique values closer than [tol]. */
    private fun cluster(v: List<Float>, tol: Float): List<Float> {
        val out = ArrayList<Float>()
        for (x in v.sorted()) if (out.isEmpty() || x - out.last() > tol) out += x
        return out
    }

    /**
     * Tables among [lines] (top-down order, as built by [ReflowBlocks]); [rules] are the page's straight lines.
     * Every returned table lists the line indexes it consumed.
     */
    fun detect(lines: List<RLine>, rules: List<RRule> = emptyList()): List<RTable> {
        val out = ArrayList<RTable>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].segs.size < 2) { i++; continue }
            // a run of multi-segment lines; a single-segment line between two of them is a wrapped cell
            val run = arrayListOf(i)
            var j = i + 1
            while (j < lines.size) {
                val prev = lines[run.last()]; val l = lines[j]
                val gapOk = prev.bottom - l.top <= 2.5f * max(prev.size, l.size)
                if (!gapOk) break
                if (l.segs.size >= 2) { run += j; j++; continue }
                val next = lines.getOrNull(j + 1)
                if (next != null && next.segs.size >= 2 && l.bottom - next.top <= 2.5f * max(l.size, next.size)) { run += j; j++; continue }
                break
            }
            val t = tableOf(lines, run, rules)
            if (t != null) { out += t; i = run.last() + 1 } else i++
        }
        return out
    }

    private fun tableOf(lines: List<RLine>, run: List<Int>, rules: List<RRule>): RTable? {
        val multi = run.filter { lines[it].segs.size >= 2 }
        val all = run.map { lines[it] }
        val top = all.maxOf { it.top }; val bottom = all.minOf { it.bottom }
        val left = all.minOf { it.left }; val right = all.maxOf { it.right }
        val h = top - bottom
        // ruling lines inside the run's box
        val vRules = rules.filter { it.vertical && it.x1 in (left - 6f)..(right + 6f) && min(it.y1, it.y2) <= bottom + h * 0.2f && max(it.y1, it.y2) >= top - h * 0.2f }
        val hRules = rules.filter { it.horizontal && min(it.x1, it.x2) <= left + 6f && max(it.x1, it.x2) >= right - 6f && it.y1 in (bottom - 6f)..(top + 6f) }
        val ruled = vRules.isNotEmpty() || hRules.size >= 2
        if (multi.size < (if (ruled) 2 else 3)) return null
        val multiLines = multi.map { lines[it] }
        val innerX = cluster(vRules.map { it.x1 }.filter { it > left + 2f && it < right - 2f }, 3f)
        val cols = if (innerX.isNotEmpty()) ruledColumns(innerX, multiLines) else columns(multiLines)
        if (cols.size < 2) return null
        // two-column prose: two long columns without rules
        val segLen = multiLines.flatMap { it.segs }.map { it.text.length }.sorted()
        if (!ruled && cols.size == 2 && segLen[segLen.size / 2] > 25) return null
        // most rows must use at least two columns
        val used = multiLines.count { l -> l.segs.map { columnOf(cols, it.left, it.right) }.distinct().size >= 2 }
        if (used * 3 < multiLines.size * 2) return null
        // rows: by horizontal rules when there are bands, else one per multi-segment line (wrapped lines join the row above)
        val ys = cluster(hRules.map { it.y1 }, 2f).sortedDescending()
        val rowOf = HashMap<Int, Int>()
        if (ys.size >= 2) {
            run.forEach { k -> val c = (lines[k].top + lines[k].bottom) / 2f; rowOf[k] = ys.count { it > c } }
        } else {
            var r = -1
            run.forEach { k -> if (lines[k].segs.size >= 2 || r < 0) r++; rowOf[k] = r }
        }
        val rowIds = rowOf.values.distinct().sorted()
        val rows = rowIds.map { ri ->
            val cells = Array(cols.size) { StringBuilder() }
            run.filter { rowOf[it] == ri }.forEach { k ->
                val segs = lines[k].segs.ifEmpty { listOf(RSeg(lines[k].text, lines[k].left, lines[k].right)) }
                segs.sortedBy { it.left }.forEach { s ->
                    val c = cells[columnOf(cols, s.left, s.right)]
                    if (c.isNotEmpty()) c.append(' ')
                    c.append(s.text)
                }
            }
            cells.map { it.toString() }
        }.filter { r -> r.any { it.isNotBlank() } }
        if (rows.size < 2) return null
        val firstRowLines = run.filter { rowOf[it] == rowIds.first() }
        // a rule under the first row (between it and the second row) marks a header
        val firstBottom = firstRowLines.minOfOrNull { lines[it].bottom } ?: bottom
        val secondTop = run.firstOrNull { rowOf[it] != rowIds.first() }?.let { lines[it].top } ?: bottom
        val headerByRule = hRules.any { r -> r.y1 <= firstBottom + 1f && r.y1 >= secondTop - 1f }
        val bodySize = run.filter { it !in firstRowLines }.map { lines[it].size }.sorted().let { it.getOrNull(it.size / 2) ?: 0f }
        val headerBySize = firstRowLines.isNotEmpty() && bodySize > 0f && firstRowLines.all { lines[it].size >= bodySize * 1.1f }
        return RTable(top, bottom, left, right, rows, headerByRule || headerBySize, run.toSet())
    }
}
