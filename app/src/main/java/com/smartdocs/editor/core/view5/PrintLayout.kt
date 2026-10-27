package com.smartdocs.editor.core.view5

/** Pure page-order and placement logic of the print options (range, booklet, pages per sheet). */
object PrintLayout {

    enum class Subset { ALL, ODD, EVEN }

    // NVE-file: booklet sides, page scaling, custom N x M grid
    /** Which sides of a booklet are printed (manual duplex: fronts first, then the backs). */
    enum class BookletSides { BOTH, FRONT, BACK }

    /** Page scaling of one page per sheet: NONE = as-is (the system dialog decides), FIT, ACTUAL (100 %), SHRINK (only oversized), CUSTOM %. */
    enum class Scaling { NONE, FIT, ACTUAL, SHRINK, CUSTOM }

    /** [perSheet] value meaning "custom grid" (columns x rows given separately). */
    const val CUSTOM_GRID = -1

    /** Booklet sides filtered by [which] (sides alternate front, back, front, back...). */
    fun bookletSides(sides: List<Pair<Int, Int>>, which: BookletSides): List<Pair<Int, Int>> = when (which) {
        BookletSides.BOTH -> sides
        BookletSides.FRONT -> sides.filterIndexed { i, _ -> i % 2 == 0 }
        BookletSides.BACK -> sides.filterIndexed { i, _ -> i % 2 == 1 }
    }

    /** Pages per sheet of a grid. */
    fun cellsOf(perSheet: Int, cols: Int, rows: Int): Int = if (perSheet == CUSTOM_GRID) cols.coerceIn(1, 10) * rows.coerceIn(1, 10) else perSheet.coerceAtLeast(1)

    /** Grid (columns, rows): the fixed choices or the custom one. */
    fun gridOf(perSheet: Int, cols: Int, rows: Int, landscape: Boolean): Pair<Int, Int> =
        if (perSheet == CUSTOM_GRID) cols.coerceIn(1, 10) to rows.coerceIn(1, 10) else grid(perSheet, landscape)

    /** Scale of a [pw] x [ph] page on a [sw] x [sh] sheet (with [margin]). */
    fun scaleFor(pw: Float, ph: Float, sw: Float, sh: Float, scaling: Scaling, customPercent: Int, margin: Float = 0f): Float {
        val fit = minOf((sw - 2 * margin) / pw.coerceAtLeast(1f), (sh - 2 * margin) / ph.coerceAtLeast(1f))
        return when (scaling) {
            Scaling.NONE, Scaling.FIT -> fit
            Scaling.ACTUAL -> 1f
            Scaling.SHRINK -> minOf(1f, fit)
            Scaling.CUSTOM -> customPercent.coerceIn(5, 800) / 100f
        }
    }

    /** Sheet (w, h) for a page: portrait / landscape paper, turned to the page's orientation when [autoRotate]. */
    fun sheetFor(pw: Float, ph: Float, paperW: Float, paperH: Float, landscape: Boolean, autoRotate: Boolean): Pair<Float, Float> {
        val long = maxOf(paperW, paperH); val short = minOf(paperW, paperH)
        val land = if (autoRotate) pw > ph else landscape
        return if (land) long to short else short to long
    }

    /** One page placed on its own sheet: origin and scale; centred when [center], else top-left (top-right when [rtl]). */
    fun single(pw: Float, ph: Float, sw: Float, sh: Float, scaling: Scaling, customPercent: Int, center: Boolean, margin: Float = 0f, rtl: Boolean = false): Cell {
        val s = scaleFor(pw, ph, sw, sh, scaling, customPercent, margin)
        val w = pw * s; val h = ph * s
        val x = if (center) (sw - w) / 2f else if (rtl) sw - margin - w else margin
        val y = if (center) (sh - h) / 2f else sh - margin - h
        return Cell(-1, x, y, w, h, s)
    }

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
        return placeGrid(chunk, sizes, cols, rows, sheetW, sheetH, margin, gap, rtl)
    }

    /** [place] for any grid of [cols] x [rows] (NVE-file: custom N x M). */
    fun placeGrid(chunk: List<Int>, sizes: (Int) -> Pair<Float, Float>, cols: Int, rows: Int, sheetW: Float, sheetH: Float, margin: Float = 18f, gap: Float = 8f, rtl: Boolean = false): List<Cell> {
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
