package com.smartdocs.editor.core.view5

/** A laid-out box of a page (PDF space, y up): a text line, a text block, an image or a table. [chars]: text length (0 for pictures). */
data class CBox(val left: Float, val right: Float, val top: Float, val bottom: Float, val chars: Int = 0) {
    val cx: Float get() = (left + right) / 2f
}

/**
 * Column-aware reading order (reflow A1 / §14d): finds a vertical gutter that splits the boxes of a page into
 * columns; boxes that cross the gutter (a title over both columns, a wide table or picture) cut the page into
 * sections. Every section is read column by column (left to right, or right to left for Arabic pages), the
 * columns themselves split again for three-column pages. Pure, unit tested.
 */
object ReflowColumns {

    /**
     * The gutter x of [ids] (indexes into [boxes]), or null. Both sides need at least 3 boxes, 20 % of the text
     * and a reasonable width (a list's numbers are not a column); crossing boxes are at most 20 % of all.
     */
    fun gutter(boxes: List<CBox>, ids: List<Int>): Float? {
        if (ids.size < 6) return null
        val minX = ids.minOf { boxes[it].left }; val maxX = ids.maxOf { boxes[it].right }
        val w = maxX - minX
        if (w <= 0f) return null
        val total = ids.sumOf { boxes[it].chars }.coerceAtLeast(1)
        var best: Float? = null; var bestCross = Int.MAX_VALUE; var bestBalance = -1
        for (k in 25..75) {
            val x = minX + w * k / 100f
            val left = ids.filter { boxes[it].right <= x }
            val right = ids.filter { boxes[it].left >= x }
            val cross = ids.size - left.size - right.size
            if (left.size < 3 || right.size < 3 || cross > ids.size / 5) continue
            if (left.sumOf { boxes[it].chars } * 5 < total || right.sumOf { boxes[it].chars } * 5 < total) continue
            // the gap must be real white space between the two sides
            val gap = right.minOf { boxes[it].left } - left.maxOf { boxes[it].right }
            if (gap < 6f) continue
            fun medianWidth(s: List<Int>) = s.map { boxes[it].right - boxes[it].left }.sorted()[s.size / 2]
            if (medianWidth(left) < w * 0.15f || medianWidth(right) < w * 0.15f) continue
            val balance = minOf(left.size, right.size)
            if (cross < bestCross || (cross == bestCross && balance > bestBalance)) { best = x; bestCross = cross; bestBalance = balance }
        }
        return best
    }

    /**
     * Reading order of [boxes] as groups: every group is one column run of one section (or one crossing box), in
     * reading order; inside a group the boxes go top-down. One group for a single-column page.
     */
    fun groups(boxes: List<CBox>, rtl: Boolean): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        split(boxes, boxes.indices.toList(), rtl, 0, out)
        return out
    }

    /** Reading order of [boxes] (the groups of [groups] one after the other). */
    fun order(boxes: List<CBox>, rtl: Boolean): List<Int> = groups(boxes, rtl).flatten()

    private fun topDown(boxes: List<CBox>, ids: List<Int>) = ids.sortedWith(compareByDescending<Int> { boxes[it].top }.thenBy { boxes[it].left })

    private fun split(boxes: List<CBox>, ids: List<Int>, rtl: Boolean, depth: Int, out: MutableList<List<Int>>) {
        if (ids.isEmpty()) return
        val x = if (depth < 3) gutter(boxes, ids) else null
        if (x == null) { out += topDown(boxes, ids); return }
        val crossing = topDown(boxes, ids.filter { boxes[it].left < x && boxes[it].right > x })
        val rest = ids.filter { it !in crossing }
        // section of a box: the number of crossing boxes above it
        val sections = rest.groupBy { i -> crossing.count { c -> boxes[c].top > boxes[i].top } }
        for (s in 0..crossing.size) {
            val inS = sections[s].orEmpty()
            if (inS.isNotEmpty()) {
                val left = inS.filter { boxes[it].cx < x }; val right = inS.filter { boxes[it].cx >= x }
                val (first, second) = if (rtl) right to left else left to right
                split(boxes, first, rtl, depth + 1, out)
                split(boxes, second, rtl, depth + 1, out)
            }
            if (s < crossing.size) out += listOf(crossing[s])
        }
    }
}
