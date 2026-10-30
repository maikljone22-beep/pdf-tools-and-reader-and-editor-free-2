package com.smartdocs.editor.core.ocr

/**
 * FINAL_CHECK_2 Low: table rules / ruled lines of a scanned page found as thin, long, dark runs, so the editable-text
 * OCR can erase them from the background picture and draw them as sharp vector lines. Pure over ARGB pixels (tested).
 */
object TableRules {

    /** One ruled line in pixels: horizontal ([horizontal]) from (x0, y0) to (x1, y1) inclusive, [thick] pixels thick. */
    data class Rule(val horizontal: Boolean, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val color: Int) {
        val thick: Int get() = if (horizontal) y1 - y0 + 1 else x1 - x0 + 1
    }

    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /**
     * Rules at least [minFrac] of the page side long (and 40 px), at most [maxThick] px thick, with light paper on both
     * sides (so photo edges and solid boxes are left alone).
     */
    fun detect(px: IntArray, w: Int, h: Int, minFrac: Float = 0.12f, maxThick: Int = 5, dark: Int = 110): List<Rule> =
        scan(w, h, true, minFrac, maxThick) { a, b -> lum(px[b * w + a]) < dark } .map { it.copy(color = avg(px, w, it)) } +
        scan(h, w, false, minFrac, maxThick) { a, b -> lum(px[a * w + b]) < dark }.map { it.copy(color = avg(px, w, it)) }

    /** [isDark](along, across): along = position on the line, across = the row (horizontal) / column (vertical). */
    private fun scan(len: Int, lines: Int, horizontal: Boolean, minFrac: Float, maxThick: Int, isDark: (Int, Int) -> Boolean): List<Rule> {
        val minLen = maxOf(40, (len * minFrac).toInt())
        // runs per line: (start, end)
        val runs = Array(lines) { ArrayList<IntArray>() }
        for (l in 0 until lines) {
            var a = 0
            while (a < len) {
                if (!isDark(a, l)) { a++; continue }
                // a run may have gaps of up to 2 px (scan noise)
                var b = a; var g = 0; var p = a + 1
                while (p < len) { if (isDark(p, l)) { b = p; g = 0 } else if (++g > 2) break; p++ }
                if (b - a + 1 >= minLen) runs[l] += intArrayOf(a, b)
                a = b + 1
            }
        }
        // group runs on consecutive lines with about the same extent
        val used = Array(lines) { BooleanArray(runs[it].size) }
        val out = ArrayList<Rule>()
        for (l in 0 until lines) for ((k, r) in runs[l].withIndex()) {
            if (used[l][k]) continue
            used[l][k] = true
            var s = r[0]; var e = r[1]; var last = l
            while (last + 1 < lines) {
                val j = runs[last + 1].indices.firstOrNull { !used[last + 1][it] && kotlin.math.abs(runs[last + 1][it][0] - s) <= 6 && kotlin.math.abs(runs[last + 1][it][1] - e) <= 6 } ?: break
                used[last + 1][j] = true; s = minOf(s, runs[last + 1][j][0]); e = maxOf(e, runs[last + 1][j][1]); last++
            }
            if (last - l + 1 > maxThick) continue
            // light paper just outside the line on both sides, over most of its length
            fun lightRow(row: Int): Boolean { if (row !in 0 until lines) return true; var n = 0; var t = 0; var p = s; while (p <= e) { t++; if (!isDark(p, row)) n++; p += 3 }; return n * 10 >= t * 8 }
            if (!lightRow(l - 1) || !lightRow(last + 1)) continue
            out += if (horizontal) Rule(true, s, l, e, last, 0) else Rule(false, l, s, last, e, 0)
        }
        return out
    }

    private fun avg(px: IntArray, w: Int, r: Rule): Int {
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0L
        val step = maxOf(1, ((r.x1 - r.x0 + 1) * (r.y1 - r.y0 + 1)) / 400)
        var i = 0
        for (y in r.y0..r.y1) for (x in r.x0..r.x1) { if (i++ % step != 0) continue; val c = px[y * w + x]; rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF; n++ }
        if (n == 0L) return 0
        return ((rs / n).toInt() shl 16) or ((gs / n).toInt() shl 8) or (bs / n).toInt()
    }
}
