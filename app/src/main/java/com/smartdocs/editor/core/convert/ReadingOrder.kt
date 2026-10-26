package com.smartdocs.editor.core.convert

import kotlin.math.abs

/**
 * Reading order of the blocks of a page by recursive XY-cut (pure Kotlin):
 *  - a region is cut into columns at vertical whitespace gutters that run through the whole region; columns are read
 *    left → right, or right → left on Arabic pages; a narrow side column beside a wide main column is read after it;
 *  - otherwise the region is cut into horizontal bands, and neighbouring bands that share a vertical gutter are joined
 *    again (so a two-column body under a full-width title is still read column by column);
 *  - pictures lying under text (backgrounds, watermarks) are taken out of the cut and come first.
 * Also finds narrow column gutters ("rivers") for line splitting.
 */
object ReadingOrder {

    private const val MIN_GUTTER = 6f

    fun order(blocks: List<Block>, rtl: Boolean): List<Block> {
        if (blocks.size <= 1) return blocks
        val text = blocks.filter { it !is ImageBlock }
        val under = blocks.filter { b -> b is ImageBlock && underText(b, text) }
        val rest = blocks.filter { it !in under }
        val out = ArrayList<Block>(blocks.size)
        out += under.sortedBy { it.rect.y0 }
        cut(rest, rtl, out, 0)
        return out
    }

    private fun area(r: Rect) = (r.w * r.h).coerceAtLeast(0f)

    private fun interArea(a: Rect, b: Rect): Float {
        val w = minOf(a.x1, b.x1) - maxOf(a.x0, b.x0); val h = minOf(a.y1, b.y1) - maxOf(a.y0, b.y0)
        return if (w > 0 && h > 0) w * h else 0f
    }

    /** An image counts as "under text" when text blocks cover at least a quarter of it. */
    private fun underText(img: Block, text: List<Block>): Boolean {
        val a = area(img.rect).takeIf { it > 0f } ?: return false
        return text.sumOf { interArea(img.rect, it.rect).toDouble() } >= 0.25 * a
    }

    /** Vertical gutters (x intervals free of blocks) inside [bs]'s horizontal extent. */
    fun gutters(bs: List<Block>, minWidth: Float = MIN_GUTTER): List<Pair<Float, Float>> {
        if (bs.size < 2) return emptyList()
        val iv = bs.map { it.rect.x0 to it.rect.x1 }.sortedBy { it.first }
        val out = ArrayList<Pair<Float, Float>>()
        var end = iv[0].second
        for (k in 1 until iv.size) {
            val (a, b) = iv[k]
            if (a - end >= minWidth) out += end to a
            end = maxOf(end, b)
        }
        return out
    }

    private fun cut(bs: List<Block>, rtl: Boolean, out: MutableList<Block>, depth: Int) {
        if (bs.size <= 1 || depth > 24) { flat(bs, rtl, out); return }
        val g = gutters(bs)
        if (g.isNotEmpty()) {
            // columns between the gutters
            val bounds = ArrayList<Float>(); bounds += Float.NEGATIVE_INFINITY; g.forEach { bounds += (it.first + it.second) / 2f }; bounds += Float.POSITIVE_INFINITY
            var cols = (0 until bounds.size - 1).map { k -> bs.filter { val cx = (it.rect.x0 + it.rect.x1) / 2f; cx >= bounds[k] && cx < bounds[k + 1] } }.filter { it.isNotEmpty() }
            if (rtl) cols = cols.reversed()
            cols = sideColumnLast(cols)
            if (cols.size > 1) { cols.forEach { cut(it, rtl, out, depth + 1) }; return }
        }
        // horizontal bands
        val sorted = bs.sortedBy { it.rect.y0 }
        val bands = ArrayList<MutableList<Block>>()
        var bottom = Float.NEGATIVE_INFINITY
        for (b in sorted) {
            if (bands.isEmpty() || b.rect.y0 > bottom - 0.5f) { bands += mutableListOf(b); bottom = b.rect.y1 }
            else { bands.last() += b; bottom = maxOf(bottom, b.rect.y1) }
        }
        if (bands.size == 1) { flat(sorted, rtl, out); return }
        // join neighbouring bands that share a gutter (one multi-column flow cut by staggered paragraphs)
        val joined = ArrayList<MutableList<Block>>()
        for (band in bands) {
            val last = joined.lastOrNull()
            if (last != null && sharesGutter(last, band)) last += band else joined += band.toMutableList()
        }
        for (grp in joined) if (grp.size == bs.size) flat(grp, rtl, out) else cut(grp, rtl, out, depth + 1)
    }

    private fun flat(bs: List<Block>, rtl: Boolean, out: MutableList<Block>) {
        out += bs.sortedWith(compareBy({ Math.round(it.rect.y0 / 4f) }, { if (rtl) -it.rect.x1 else it.rect.x0 }))
    }

    private fun sharesGutter(a: List<Block>, b: List<Block>): Boolean {
        if (a.size + b.size < 3) return false
        val ga = gutters(a); val gb = gutters(b)
        if (ga.isEmpty() || gb.isEmpty()) return false
        val all = gutters(a + b)
        // the union keeps a gutter that lies inside one gutter of each part
        return all.any { u -> ga.any { overlaps(it, u) } && gb.any { overlaps(it, u) } }
    }

    private fun overlaps(a: Pair<Float, Float>, b: Pair<Float, Float>) = minOf(a.second, b.second) - maxOf(a.first, b.first) > 0f

    /** Two columns where the first (in reading order) is less than 40 % as wide as the other: read it last. */
    private fun sideColumnLast(cols: List<List<Block>>): List<List<Block>> {
        if (cols.size != 2) return cols
        fun width(c: List<Block>) = c.maxOf { it.rect.x1 } - c.minOf { it.rect.x0 }
        val a = width(cols[0]); val b = width(cols[1])
        return if (a < 0.4f * b) listOf(cols[1], cols[0]) else cols
    }

    // ---------------------------------------------------------------- rivers

    /**
     * Narrow column gutters: whitespace between words, at least [minEm] (but less than the normal gutter) wide, that
     * lines up on at least [minLines] consecutive lines. Returns x intervals (page coordinates).
     */
    fun rivers(lines: List<Line>, minEm: Float = 0.9f, minLines: Int = 3): List<Pair<Float, Float>> {
        val sorted = lines.sortedBy { it.top }
        val gaps = sorted.map { l ->
            val out = ArrayList<Pair<Float, Float>>()
            for (k in 1 until l.visual.size) {
                val a = l.visual[k - 1]; val b = l.visual[k]
                if (b.x0 - a.x1 >= minEm * minOf(a.fontSize, b.fontSize)) out += a.x1 to b.x0
            }
            out
        }
        val found = ArrayList<Pair<Float, Float>>()
        for (i in sorted.indices) for (g in gaps[i]) {
            var cur = g; var n = 1; var j = i + 1
            while (j < sorted.size) {
                val prev = sorted[j - 1]; val l = sorted[j]
                if (l.top - prev.bottom > 1.5f * prev.fontSize) break
                // a line that does not reach across the gutter does not break the river
                val spans = l.x0 < cur.first && l.x1 > cur.second
                if (!spans) { j++; continue }
                val m = gaps[j].firstOrNull { minOf(it.second, cur.second) - maxOf(it.first, cur.first) >= 0.3f * l.fontSize } ?: break
                cur = maxOf(cur.first, m.first) to minOf(cur.second, m.second); n++; j++
            }
            if (n >= minLines && found.none { abs((it.first + it.second) - (cur.first + cur.second)) < 4f }) found += cur
        }
        return found
    }
}
