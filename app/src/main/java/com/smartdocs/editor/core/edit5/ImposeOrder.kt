package com.smartdocs.editor.core.edit5

import kotlin.math.ceil
import kotlin.math.max

/** One side of a printed sheet with two page slots. -1 means a blank slot. */
data class SheetSide(val left: Int, val right: Int)

/** A page placed in an N-up grid: [col] 0 = leftmost, [row] 0 = top. */
data class Cell(val page: Int, val col: Int, val row: Int)

/** A poster tile: the part of the (scaled) source page it shows, in source points, origin bottom-left. */
data class Tile(val col: Int, val row: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/** Page orders for imposition (pure, unit-tested). */
object ImposeOrder {
    /** Pages padded to a multiple of [m] (slots past [count] are blanks). */
    fun padded(count: Int, m: Int): Int = if (count <= 0) 0 else ((count + m - 1) / m) * m

    /**
     * Saddle-stitch booklet: every sheet has a front and a back side with 2 pages each, folded in the middle.
     * Left-binding (LTR) sheet s: front = (N-1-2s, 2s), back = (2s+1, N-2-2s).
     * Right-binding (RTL, Arabic books): the two slots of every side are swapped.
     * Returns the sides in print order (front of sheet 1, back of sheet 1, front of sheet 2 …).
     */
    fun booklet(count: Int, rtl: Boolean = true): List<SheetSide> {
        val n = padded(count, 4)
        fun p(i: Int) = if (i < count) i else -1
        val out = ArrayList<SheetSide>()
        for (s in 0 until n / 4) {
            val front = SheetSide(p(n - 1 - 2 * s), p(2 * s))
            val back = SheetSide(p(2 * s + 1), p(n - 2 - 2 * s))
            out += if (rtl) SheetSide(front.right, front.left) else front
            out += if (rtl) SheetSide(back.right, back.left) else back
        }
        return out
    }

    /**
     * N-up: [cols]×[rows] pages per sheet. Reading order is row by row (or column by column when [columnFirst]);
     * [rtl] fills from the right. Returns one list of cells per output sheet.
     */
    fun nUp(count: Int, cols: Int, rows: Int, rtl: Boolean = false, columnFirst: Boolean = false): List<List<Cell>> {
        val per = (cols * rows).coerceAtLeast(1)
        val sheets = ArrayList<List<Cell>>()
        var p = 0
        while (p < count) {
            val cells = ArrayList<Cell>()
            for (k in 0 until per) {
                if (p + k >= count) break
                var col: Int; val row: Int
                if (columnFirst) { col = k / rows; row = k % rows } else { row = k / cols; col = k % cols }
                if (rtl) col = cols - 1 - col
                cells += Cell(p + k, col, row)
            }
            sheets += cells
            p += per
        }
        return sheets
    }

    /** Grid (cols, rows) of a standard N-up count: 2 → 2×1, 4 → 2×2, 6 → 3×2, 8 → 4×2, 9 → 3×3, 16 → 4×4. */
    fun gridFor(n: Int, landscapeSheet: Boolean = true): Pair<Int, Int> {
        val g = when (n) { 1 -> 1 to 1; 2 -> 2 to 1; 3 -> 3 to 1; 4 -> 2 to 2; 6 -> 3 to 2; 8 -> 4 to 2; 9 -> 3 to 3; 12 -> 4 to 3; 16 -> 4 to 4; else -> {
            val c = ceil(Math.sqrt(n.toDouble())).toInt(); c to ceil(n / c.toDouble()).toInt() } }
        return if (landscapeSheet) g else g.second to g.first
    }

    /**
     * Poster: the source page ([w]×[h] points) enlarged by [scale] and cut into tiles of [tileW]×[tileH] points
     * (printable area) with [overlap] points shared between neighbours. Tiles are listed top row first, left to right.
     */
    fun posterTiles(w: Float, h: Float, scale: Float, tileW: Float, tileH: Float, overlap: Float = 0f): List<Tile> {
        val bigW = w * scale; val bigH = h * scale
        val stepW = (tileW - overlap).coerceAtLeast(1f); val stepH = (tileH - overlap).coerceAtLeast(1f)
        val cols = max(1, ceil(((bigW - overlap) / stepW).toDouble()).toInt())
        val rows = max(1, ceil(((bigH - overlap) / stepH).toDouble()).toInt())
        val out = ArrayList<Tile>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = c * stepW
            val top = bigH - r * stepH
            val y = top - tileH
            out += Tile(c, r, x / scale, y / scale, tileW / scale, tileH / scale)
        }
        return out
    }

    /**
     * Split one page into parts (cut lines as fractions). Returns rects in fractions of the page (x, y, w, h,
     * origin bottom-left) in reading order: top row first; [rtl] reads right to left.
     */
    fun splitParts(cols: Int, rows: Int, rtl: Boolean, xCuts: List<Float>? = null, yCuts: List<Float>? = null): List<FloatArray> {
        val xs = (listOf(0f) + (xCuts ?: (1 until cols).map { it / cols.toFloat() }) + listOf(1f)).sorted()
        val ys = (listOf(0f) + (yCuts ?: (1 until rows).map { it / rows.toFloat() }) + listOf(1f)).sorted()
        val out = ArrayList<FloatArray>()
        for (r in ys.size - 2 downTo 0) {           // top row first
            val colIdx = (0 until xs.size - 1).let { if (rtl) it.reversed() else it }
            for (c in colIdx) out += floatArrayOf(xs[c], ys[r], xs[c + 1] - xs[c], ys[r + 1] - ys[r])
        }
        return out
    }

    /** Interleave two page lists (A1 B1 A2 B2 …); [reverseB] takes B from its end (scanned back sides). */
    fun interleave(a: Int, b: Int, reverseB: Boolean = false): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until max(a, b)) {
            if (i < a) out += 0 to i
            if (i < b) out += 1 to (if (reverseB) b - 1 - i else i)
        }
        return out
    }
}

/** White-margin detection on rendered pixels (ARGB ints). Pure. */
object MarginDetect {
    /**
     * Bounding box (left, top, right, bottom; pixels, inclusive) of the non-background pixels, or null for a blank page.
     * A pixel counts as content when any channel is darker than [threshold] (0..255). [ignore] rows/cols of noise.
     */
    fun contentBounds(px: IntArray, w: Int, h: Int, threshold: Int = 245, ignore: Int = 0): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                val a = (c ushr 24) and 0xFF
                if (a < 16) continue
                if (((c shr 16) and 0xFF) < threshold || ((c shr 8) and 0xFF) < threshold || (c and 0xFF) < threshold) {
                    if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                }
            }
        }
        if (r < 0) return null
        if (ignore > 0 && (r - l < ignore || b - t < ignore)) return null
        return intArrayOf(l, t, r, b)
    }

    /** Fraction of content pixels (for blank-page detection). */
    fun inkRatio(px: IntArray, threshold: Int = 235): Float {
        if (px.isEmpty()) return 0f
        var n = 0
        for (c in px) if (((c shr 16) and 0xFF) < threshold || ((c shr 8) and 0xFF) < threshold || (c and 0xFF) < threshold) n++
        return n / px.size.toFloat()
    }
}
