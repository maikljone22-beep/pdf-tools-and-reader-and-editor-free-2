package com.smartdocs.editor.core.view5

/** Pure page-order and placement logic of the print options (range, booklet, pages per sheet). */
object PrintLayout {

    enum class Subset { ALL, ODD, EVEN }

    /**
     * "1-3, 5, 8-" → zero-based indexes within [count] (an open end means "to the last page", "-4" from the first).
     * Blank means all pages. Invalid parts are ignored; order is kept, duplicates are dropped.
     */
    fun parseRange(spec: String, count: Int): List<Int> {
        val s = Digits.toLatin(spec).replace('،', ',').replace('–', '-').trim()
        if (s.isEmpty()) return (0 until count).toList()
        val out = LinkedHashSet<Int>()
        s.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val dash = part.indexOf('-')
            if (dash < 0) { part.toIntOrNull()?.let { if (it in 1..count) out += it - 1 }; return@forEach }
            val a = part.substring(0, dash).trim().ifEmpty { "1" }.toIntOrNull() ?: return@forEach
            val b = part.substring(dash + 1).trim().ifEmpty { count.toString() }.toIntOrNull() ?: return@forEach
            val lo = a.coerceIn(1, count.coerceAtLeast(1)); val hi = b.coerceIn(1, count.coerceAtLeast(1))
            if (lo <= hi) for (i in lo..hi) out += i - 1 else for (i in lo downTo hi) out += i - 1
        }
        return out.toList()
    }

    fun subset(pages: List<Int>, which: Subset): List<Int> = when (which) {
        Subset.ALL -> pages
        Subset.ODD -> pages.filter { it % 2 == 0 }     // page numbers 1, 3, 5…
        Subset.EVEN -> pages.filter { it % 2 == 1 }
    }

    /**
     * Booklet imposition: sheets printed on both sides, folded in the middle. Returns, for every side of
     * every sheet, the (left, right) pages; -1 is a blank. [rightBinding] (Arabic books) swaps the halves.
     */
    fun booklet(pages: List<Int>, rightBinding: Boolean = false): List<Pair<Int, Int>> {
        if (pages.isEmpty()) return emptyList()
        val n = ((pages.size + 3) / 4) * 4
        val padded = pages + List(n - pages.size) { -1 }
        val out = ArrayList<Pair<Int, Int>>()
        var lo = 0; var hi = n - 1
        while (lo < hi) {
            // front: last | first ; back: second | second-last
            val front = padded[hi] to padded[lo]
            val back = padded[lo + 1] to padded[hi - 1]
            out += if (rightBinding) (front.second to front.first) else front
            out += if (rightBinding) (back.second to back.first) else back
            lo += 2; hi -= 2
        }
        return out
    }

    /** Grid (columns, rows) for N pages per sheet; landscape sheets get the wider grid. */
    fun grid(perSheet: Int, landscape: Boolean): Pair<Int, Int> = when (perSheet) {
        1 -> 1 to 1
        2 -> if (landscape) 2 to 1 else 1 to 2
        4 -> 2 to 2
        6 -> if (landscape) 3 to 2 else 2 to 3
        9 -> 3 to 3
        16 -> 4 to 4
        else -> 1 to 1
    }

    /** One placed page: cell origin and size in sheet points (origin bottom-left) and the fitted scale. */
    data class Cell(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float, val scale: Float)

    /**
     * Pages of [chunk] placed on a sheet [sheetW]×[sheetH]: row by row from the top, right-to-left when [rtl].
     * [sizes] gives each page's (w, h) in points; pages keep their aspect ratio, centred in the cell.
     */
    fun place(chunk: List<Int>, sizes: (Int) -> Pair<Float, Float>, perSheet: Int, sheetW: Float, sheetH: Float, margin: Float = 18f, gap: Float = 8f, rtl: Boolean = false): List<Cell> {
        val (cols, rows) = grid(perSheet, sheetW > sheetH)
        val cw = (sheetW - margin * 2 - gap * (cols - 1)) / cols
        val ch = (sheetH - margin * 2 - gap * (rows - 1)) / rows
        return chunk.mapIndexedNotNull { i, p ->
            if (p < 0 || i >= cols * rows) return@mapIndexedNotNull null
            val r = i / cols; var c = i % cols
            if (rtl) c = cols - 1 - c
            val (pw, ph) = sizes(p)
            val s = minOf(cw / pw.coerceAtLeast(1f), ch / ph.coerceAtLeast(1f))
            val w = pw * s; val h = ph * s
            val cellX = margin + c * (cw + gap); val cellTop = sheetH - margin - r * (ch + gap)
            Cell(p, cellX + (cw - w) / 2, cellTop - ch + (ch - h) / 2, w, h, s)
        }
    }
}
