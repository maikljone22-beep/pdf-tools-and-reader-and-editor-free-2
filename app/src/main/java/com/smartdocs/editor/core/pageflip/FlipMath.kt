package com.smartdocs.editor.core.pageflip

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a page of a screen sits. CENTER = single-page layout. */
enum class PageSide { LEFT, RIGHT, CENTER }

/**
 * Screen ("item") <-> page mapping of the page-turn host. Identical to the reader's PageSpread for SINGLE / FACING /
 * COVER (so `vx.flip`, auto flip and keys agree with it), plus the side of every page in a book layout.
 */
class SpreadMap(val spread: PfSpread) {
    val paired: Boolean get() = spread != PfSpread.SINGLE

    fun itemCount(pageCount: Int): Int = when (spread) {
        PfSpread.TWO -> (pageCount + 1) / 2
        PfSpread.TWO_COVER -> if (pageCount <= 1) pageCount else 1 + pageCount / 2
        PfSpread.SINGLE -> pageCount
    }

    fun pageToItem(page: Int): Int = when (spread) {
        PfSpread.TWO -> (page / 2).coerceAtLeast(0)
        PfSpread.TWO_COVER -> if (page <= 0) 0 else (page + 1) / 2
        PfSpread.SINGLE -> page.coerceAtLeast(0)
    }

    fun firstPage(item: Int): Int = when (spread) {
        PfSpread.TWO -> item * 2
        PfSpread.TWO_COVER -> if (item <= 0) 0 else item * 2 - 1
        PfSpread.SINGLE -> item
    }

    fun pages(item: Int, pageCount: Int): List<Int> {
        val f = firstPage(item)
        val list = when {
            !paired -> listOf(f)
            spread == PfSpread.TWO_COVER && item == 0 -> listOf(0)
            else -> listOf(f, f + 1)
        }
        return list.filter { it in 0 until pageCount }
    }

    /**
     * Side of each page of [item] in reading direction: in LTR the earlier page is on the left; the cover (and a
     * lone first page of a cover layout) is a right-hand page, a lone last page a left-hand one. RTL mirrors.
     */
    fun sides(item: Int, pageCount: Int, rtl: Boolean): List<Pair<Int, PageSide>> {
        val ps = pages(item, pageCount)
        if (!paired) return ps.map { it to PageSide.CENTER }
        fun m(s: PageSide) = if (!rtl) s else if (s == PageSide.LEFT) PageSide.RIGHT else PageSide.LEFT
        return when {
            ps.size == 2 -> listOf(ps[0] to m(PageSide.LEFT), ps[1] to m(PageSide.RIGHT))
            ps.size == 1 && spread == PfSpread.TWO_COVER && item == 0 -> listOf(ps[0] to m(PageSide.RIGHT))
            ps.size == 1 -> listOf(ps[0] to m(PageSide.LEFT))
            else -> emptyList()
        }
    }

    /** True when turning from [item] forward opens the cover / closes the back cover (rigid hard-cover turn). */
    fun isCoverTurn(item: Int, pageCount: Int): Boolean {
        if (spread != PfSpread.TWO_COVER) return false
        val last = itemCount(pageCount) - 1
        return item == 0 || (item + 1 == last && pages(last, pageCount).size == 1)
    }
}

/** Gesture / motion decisions of the page-turn host (pure). */
object FlipMath {
    /** Screen delta (+1 next, −1 previous, 0 none) for a drag of (dx, dy). */
    fun dragDelta(dx: Float, dy: Float, axis: PfAxis, rtl: Boolean): Int = when (axis) {
        PfAxis.HORIZONTAL -> when {
            abs(dx) < 1f -> 0
            (dx < 0) != rtl -> 1
            else -> -1
        }
        PfAxis.VERTICAL -> when {
            abs(dy) < 1f -> 0
            dy < 0 -> 1
            else -> -1
        }
    }

    /** True when a drag of (dx, dy) runs along the paging axis (decided once, at touch slop). */
    fun alongAxis(dx: Float, dy: Float, axis: PfAxis): Boolean =
        if (axis == PfAxis.HORIZONTAL) abs(dx) > abs(dy) * 1.1f else abs(dy) > abs(dx) * 1.1f

    /** Edge zone of a tap at [x] of [width]: +1 next, −1 previous, 0 centre. In RTL the next zone is on the left. */
    fun zoneOf(x: Float, width: Float, zone: Float, rtl: Boolean): Int {
        if (width <= 0f) return 0
        val f = x / width
        return when {
            f < zone -> if (rtl) 1 else -1
            f > 1f - zone -> if (rtl) -1 else 1
            else -> 0
        }
    }

    /** Grabbed height (0..1) of the leaf: corners snap to 0 / 1 (diagonal curl), else the touch height. */
    fun grabY(yFrac: Float, corner: Float = 0.22f): Float = when {
        yFrac > 1f - corner -> 1f
        yFrac < corner -> 0f
        else -> yFrac.coerceIn(0f, 1f)
    }

    /**
     * Finish or cancel an interactive turn: [progress] 0..1 towards the target, [velocity] in progress units per
     * second towards the target (negative = back).
     */
    fun shouldCommit(progress: Float, velocity: Float): Boolean = when {
        velocity > 1.2f -> progress > 0.03f
        velocity < -1.2f -> false
        else -> progress > 0.5f
    }

    /**
     * Duration of the remaining motion: the effect's [baseMs] scaled by the remaining distance and [speed]; a fast
     * fling finishes at least as fast as the finger was moving (×1.4 for a natural ease-out).
     */
    fun durationMs(remaining: Float, velocity: Float, baseMs: Int, speed: Float, animatorScale: Float = 1f): Int {
        if (baseMs <= 0 || animatorScale <= 0f) return 0
        val rem = remaining.coerceIn(0f, 1f)
        var ms = baseMs / speed.coerceIn(PageFlipPrefs.SPEED_MIN, PageFlipPrefs.SPEED_MAX) * (0.35f + 0.65f * rem)
        val v = abs(velocity)
        if (v > 0.2f) ms = min(ms, rem / v * 1000f * 1.4f)
        return (ms * animatorScale).roundToInt().coerceIn(80, 1600)
    }

    /** Spring stiffness for a speed multiplier (Compose spring units). */
    fun stiffness(speed: Float): Float = 380f * speed.coerceIn(PageFlipPrefs.SPEED_MIN, PageFlipPrefs.SPEED_MAX).let { it * it }

    /**
     * Progress of leaf [i] of [n] in a stack turn at overall progress [t]: leaves start staggered and each turns
     * over the same span, the last one ends at t = 1.
     */
    fun stackLeaf(t: Float, i: Int, n: Int): Float {
        if (n <= 1) return t.coerceIn(0f, 1f)
        val gap = min(0.18f, 0.5f / (n - 1))
        val span = 1f - (n - 1) * gap
        return ((t - i * gap) / span).coerceIn(0f, 1f)
    }

    /** Number of leaves animated for a jump of [screens] screens (the rest is implied by the stack edge). */
    fun stackSize(screens: Int): Int = min(4, max(1, abs(screens)))

    /** Page shown by a scrubber at fraction [f] (0..1 of the track, left to right); RTL tracks run right to left. */
    fun pageAt(f: Float, pageCount: Int, rtl: Boolean): Int {
        if (pageCount <= 1) return 0
        val x = if (rtl) 1f - f.coerceIn(0f, 1f) else f.coerceIn(0f, 1f)
        return (x * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
    }

    fun fractionOf(page: Int, pageCount: Int, rtl: Boolean): Float {
        if (pageCount <= 1) return 0f
        val f = page.coerceIn(0, pageCount - 1) / (pageCount - 1f)
        return if (rtl) 1f - f else f
    }

    /** Riffle: pages moved by dragging [distance] px along the edge, one page per [stepPx]. */
    fun rifflePages(distance: Float, stepPx: Float): Int = if (stepPx <= 0f) 0 else (distance / stepPx).toInt()

    /** Paper-stack edge thickness in px for [pages] pages on one side (log-ish, capped). */
    fun stackEdgePx(pages: Int, maxPx: Float): Float = if (pages <= 0) 0f else min(maxPx, 1.5f + 1.2f * kotlin.math.ln(1f + pages))
}
