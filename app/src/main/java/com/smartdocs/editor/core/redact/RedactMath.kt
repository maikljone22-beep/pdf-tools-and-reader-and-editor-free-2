package com.smartdocs.editor.core.redact

/**
 * A rectangle in PDF space (y up): [l] < [r], [b] < [t]. Plain floats so the geometry is unit-testable
 * without Android's RectF stubs.
 */
data class Box(val l: Float, val b: Float, val r: Float, val t: Float) {
    val w get() = r - l
    val h get() = t - b
    val area get() = (w.coerceAtLeast(0f)) * (h.coerceAtLeast(0f))
    val cx get() = (l + r) / 2f
    val cy get() = (b + t) / 2f
    fun contains(x: Float, y: Float) = x >= l && x <= r && y >= b && y <= t
    fun intersects(o: Box) = l < o.r && o.l < r && b < o.t && o.b < t
    fun inter(o: Box): Box? = if (!intersects(o)) null else Box(maxOf(l, o.l), maxOf(b, o.b), minOf(r, o.r), minOf(t, o.t))
    /** True when [o] lies inside this box, allowing [tol] points of slack. */
    fun encloses(o: Box, tol: Float = 0.5f) = o.l >= l - tol && o.r <= r + tol && o.b >= b - tol && o.t <= t + tol
    fun union(o: Box) = Box(minOf(l, o.l), minOf(b, o.b), maxOf(r, o.r), maxOf(t, o.t))
    fun inflate(d: Float) = Box(l - d, b - d, r + d, t + d)

    companion object {
        /** Normalises any corner order. */
        fun of(x0: Float, y0: Float, x1: Float, y1: Float) = Box(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    }
}

/** Pure helpers of the redaction tool (unit-tested). */
object RedactMath {

    /** A glyph is redacted when its centre lies in a mark, or at least half of its box is covered. */
    fun glyphHit(glyph: Box, marks: List<Box>): Boolean =
        marks.any { m -> m.contains(glyph.cx, glyph.cy) || (glyph.area > 0f && (m.inter(glyph)?.area ?: 0f) >= 0.5f * glyph.area) }

    /** An object (path, image, form) is removed when one mark encloses it entirely. */
    fun enclosed(obj: Box, marks: List<Box>, tol: Float = 0.5f): Boolean = marks.any { it.encloses(obj, tol) }

    fun touches(obj: Box, marks: List<Box>): Boolean = marks.any { it.intersects(obj) }

    /**
     * Partial masking ("keep the last N characters"): the part of [range] in [text] to redact so that the last
     * [keep] letters/digits stay visible. Separators (spaces, dashes) between them count as kept. Null when
     * nothing would be masked.
     */
    fun keepLast(text: String, range: IntRange, keep: Int): IntRange? {
        if (keep <= 0) return range
        var seen = 0
        var i = range.last
        while (i >= range.first) {
            if (text[i].isLetterOrDigit()) { seen++; if (seen == keep) break }
            i--
        }
        if (i <= range.first) return null
        var end = i - 1
        while (end >= range.first && !text[end].isLetterOrDigit()) end--
        return if (end < range.first) null else range.first..end
    }

    enum class PageParity { ALL, ODD, EVEN }

    /** Zero-based pages of a "1-3, 5, 9-" spec (blank = every page), filtered by [parity] (1-based odd/even). */
    fun pages(spec: String, count: Int, parity: PageParity = PageParity.ALL): List<Int> {
        val all = if (spec.isBlank()) (0 until count).toList() else spec.split(',', '،', ';').flatMap { part ->
            val p = part.trim()
            if (p.isEmpty()) return@flatMap emptyList<Int>()
            val dash = p.indexOf('-')
            if (dash < 0) listOfNotNull(p.toIntOrNull()?.minus(1))
            else {
                val a = p.substring(0, dash).trim().toIntOrNull() ?: 1
                val b = p.substring(dash + 1).trim().toIntOrNull() ?: count
                (minOf(a, b) - 1..maxOf(a, b) - 1).toList()
            }
        }.filter { it in 0 until count }.distinct().sorted()
        return all.filter { when (parity) { PageParity.ALL -> true; PageParity.ODD -> it % 2 == 0; PageParity.EVEN -> it % 2 == 1 } }
    }

    /** Applies an affine [m] (a b c d e f, PDF convention x' = a x + c y + e) to a box: the bounding box of its corners. */
    fun map(m: FloatArray, bx: Box): Box {
        fun x(px: Float, py: Float) = m[0] * px + m[2] * py + m[4]
        fun y(px: Float, py: Float) = m[1] * px + m[3] * py + m[5]
        val xs = floatArrayOf(x(bx.l, bx.b), x(bx.r, bx.b), x(bx.l, bx.t), x(bx.r, bx.t))
        val ys = floatArrayOf(y(bx.l, bx.b), y(bx.r, bx.b), y(bx.l, bx.t), y(bx.r, bx.t))
        return Box(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** Auto font size for overlay text: fits the box height and, for one line, the width. */
    fun autoFontSize(textWidthAt1pt: Float, boxW: Float, boxH: Float): Float {
        val byH = boxH * 0.72f
        val byW = if (textWidthAt1pt > 0f) (boxW * 0.92f) / textWidthAt1pt else byH
        return minOf(byH, byW).coerceIn(4f, 72f)
    }

    /** How many copies of a text of [unitW] (plus a gap) fill [boxW] when "repeat" is on. */
    fun repeatCount(unitW: Float, boxW: Float, gap: Float): Int = if (unitW <= 0f) 1 else (((boxW + gap) / (unitW + gap)).toInt()).coerceIn(1, 200)
}
