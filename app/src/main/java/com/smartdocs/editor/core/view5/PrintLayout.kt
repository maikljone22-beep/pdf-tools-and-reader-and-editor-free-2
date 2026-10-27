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
    fun place(chunk: List<Int>, sizes: (Int) -> Pair<Float, Float>, perSheet: Int, sheetW: Float, sheetH: Float, margin: Float = 18f, gap: Float = 8f, rtl: Boolean = false,
              cols0: Int = 0, rows0: Int = 0, vertical: Boolean = false): List<Cell> {
        val (cols, rows) = if (cols0 > 0 && rows0 > 0) cols0 to rows0 else grid(perSheet, sheetW > sheetH)
        val cw = (sheetW - margin * 2 - gap * (cols - 1)) / cols
        val ch = (sheetH - margin * 2 - gap * (rows - 1)) / rows
        return chunk.mapIndexedNotNull { i, p ->
            if (p < 0 || i >= cols * rows) return@mapIndexedNotNull null
            // nav-batch: column-first order (Foxit "vertical") as well as row-first
            val r = if (vertical) i % rows else i / cols; var c = if (vertical) i / rows else i % cols
            if (rtl) c = cols - 1 - c
            val (pw, ph) = sizes(p)
            val s = minOf(cw / pw.coerceAtLeast(1f), ch / ph.coerceAtLeast(1f))
            val w = pw * s; val h = ph * s
            val cellX = margin + c * (cw + gap); val cellTop = sheetH - margin - r * (ch + gap)
            Cell(p, cellX + (cw - w) / 2, cellTop - ch + (ch - h) / 2, w, h, s)
        }
    }

    // ---------------- nav-batch: scale, poster, booklet sides, sections, page labels ----------------

    enum class Scale { AUTO, FIT, SHRINK, ACTUAL, CUSTOM }

    /**
     * Scale and rotation of a [pw]×[ph] page on a [sw]×[sh] sheet with [margin] on every side: FIT fills the
     * printable area, SHRINK only reduces pages that are too big, ACTUAL keeps 100 %, CUSTOM uses [pct] %.
     * With [autoRotate] a page is turned 90° when that matches the sheet better. Returns (scale, rotated).
     */
    fun fitScale(pw: Float, ph: Float, sw: Float, sh: Float, mode: Scale, pct: Int = 100, margin: Float = 0f, autoRotate: Boolean = true): Pair<Float, Boolean> {
        val aw = (sw - 2 * margin).coerceAtLeast(1f); val ah = (sh - 2 * margin).coerceAtLeast(1f)
        val rotate = autoRotate && (pw > ph) != (aw > ah) && pw != ph
        val w = if (rotate) ph else pw; val h = if (rotate) pw else ph
        val fit = minOf(aw / w.coerceAtLeast(1f), ah / h.coerceAtLeast(1f))
        val s = when (mode) {
            Scale.AUTO, Scale.FIT -> fit
            Scale.SHRINK -> minOf(1f, fit)
            Scale.ACTUAL -> 1f
            Scale.CUSTOM -> pct.coerceIn(5, 800) / 100f
        }
        return s to rotate
    }

    /** One poster tile: its column / row and the part of the page (page points, origin bottom-left) it shows. */
    data class Tile(val col: Int, val row: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * Tile Large Pages (poster): the [pw]×[ph] page enlarged by [zoomPct] % over sheets with a printable area of
     * [aw]×[ah] that overlap by [overlap] points (sheet units). Tiles run row by row from the top left.
     */
    fun tiles(pw: Float, ph: Float, aw: Float, ah: Float, zoomPct: Int, overlap: Float = 18f): List<Tile> {
        val z = zoomPct.coerceIn(10, 2000) / 100f
        val tw = aw / z; val th = ah / z                       // page area of one sheet
        val ov = (overlap / z).coerceIn(0f, minOf(tw, th) / 2f)
        fun count(len: Float, t: Float) = if (len <= t) 1 else 1 + kotlin.math.ceil(((len - t) / (t - ov)).toDouble()).toInt()
        val cols = count(pw, tw); val rows = count(ph, th)
        val out = ArrayList<Tile>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = c * (tw - ov); val top = ph - r * (th - ov)
            out += Tile(c, r, x, top - th, tw, th)
        }
        return out
    }

    enum class Sides { BOTH, FRONT, BACK }

    /**
     * Booklet sides to print: every sheet has a front (even index) and a back (odd index) side in [booklet] order.
     * [sides] keeps fronts or backs only (single-sided printers: print fronts, turn the stack, print backs) and
     * [sheets] ("1-3", blank = all) limits the sheets.
     */
    fun <T> bookletSides(all: List<T>, sides: Sides, sheets: String = ""): List<T> {
        val sheetCount = (all.size + 1) / 2
        val keep = parseRange(sheets, sheetCount).toSet()
        return all.filterIndexed { i, _ -> (i / 2) in keep && (sides == Sides.BOTH || (sides == Sides.FRONT) == (i % 2 == 0)) }
    }

    /**
     * Pages of the bookmark section at [index] of a flattened outline ([levels] / [pages] per item): from its page
     * to the page before the next item of the same or a higher level (or the last page).
     */
    fun section(levels: List<Int>, pages: List<Int>, index: Int, pageCount: Int): IntRange {
        val start = pages[index].coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        var end = pageCount - 1
        for (j in index + 1 until levels.size) if (levels[j] <= levels[index] && pages[j] >= 0) { end = maxOf(start, pages[j] - 1); break }
        return start..end
    }

    /**
     * Print range typed with page labels ("iii-v, A-2"): each label is replaced by its page number before
     * [parseRange]; parts that are not labels stay as typed.
     */
    fun labelRange(spec: String, labels: List<String>?): String {
        if (labels.isNullOrEmpty() || spec.isBlank()) return spec
        fun num(t: String): String { val k = t.trim(); val i = labels.indexOfFirst { it.equals(k, true) }; return if (i >= 0) (i + 1).toString() else k }
        return spec.split(',', '،').joinToString(",") { part ->
            val p = part.trim()
            val whole = labels.indexOfFirst { it.equals(p, true) }
            if (whole >= 0) return@joinToString (whole + 1).toString()
            // "a-b" with labels on either side (labels themselves may contain '-': try every split)
            val dashes = p.indices.filter { p[it] == '-' }
            val hit = dashes.firstOrNull { d -> val a = p.substring(0, d).trim(); val b = p.substring(d + 1).trim()
                (a.isEmpty() || labels.any { it.equals(a, true) } || a.toIntOrNull() != null) && (b.isEmpty() || labels.any { it.equals(b, true) } || b.toIntOrNull() != null) }
            if (hit != null) num(p.substring(0, hit)) + "-" + num(p.substring(hit + 1)) else p
        }
    }
}
