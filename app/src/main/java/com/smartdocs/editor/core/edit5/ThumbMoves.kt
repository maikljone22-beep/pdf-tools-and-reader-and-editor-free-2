package com.smartdocs.editor.core.edit5

/**
 * fix/thumbs-panel: page order arithmetic of the reader's thumbnails panel (drag to reorder, several pages at once).
 * The resulting order goes to [com.smartdocs.editor.core.pdf.PdfOps.reorder], which re-arranges the page tree in place,
 * so bookmarks, links and fields keep pointing at the same pages (as in Organize).
 */
object ThumbMoves {
    /**
     * New order of [n] pages after dragging the pages [moving] onto the page at index [target]. [forward] = the drag
     * went towards the end (the block lands after the target, as in Organize's single-page drag), else before it.
     * Null when nothing would change (the target is part of the block, or bad input).
     */
    fun moveOrder(n: Int, moving: Collection<Int>, target: Int, forward: Boolean): List<Int>? {
        val block = moving.filter { it in 0 until n }.distinct().sorted()
        if (block.isEmpty() || target !in 0 until n || target in block) return null
        val rest = (0 until n).filter { it !in block }
        val at = rest.indexOf(target) + if (forward) 1 else 0
        val order = rest.toMutableList().apply { addAll(at, block) }
        return order.takeIf { it != (0 until n).toList() }
    }

    /** Where the pages [moved] are in [order] (to keep them selected after the move). */
    fun indexesAfter(order: List<Int>, moved: Collection<Int>): Set<Int> = moved.mapNotNull { p -> order.indexOf(p).takeIf { it >= 0 } }.toSet()

    /** Thumbnail width steps of the panel (dp) and the default. */
    val SIZES = listOf(64, 80, 96, 120, 150, 190, 240)
    const val DEFAULT_SIZE = 96

    /** The next size step up (+1) or down (-1) from [current]; stays at the ends. */
    fun step(current: Int, dir: Int): Int = SIZES[(SIZES.indexOf(normalize(current)) + dir).coerceIn(0, SIZES.lastIndex)]

    /** A stored size that is not a step (older value, bad input) snaps to the nearest step. */
    fun normalize(v: Int): Int = SIZES.minByOrNull { kotlin.math.abs(it - v) } ?: DEFAULT_SIZE
}
