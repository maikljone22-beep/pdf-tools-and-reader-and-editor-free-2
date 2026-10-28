package com.smartdocs.editor.core.view5

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSNumber

/**
 * fc-settings (Foxit §36 / old G8): printer marks for normal printing — bleed marks and registration targets around
 * the placed page (crop marks are [PrintPrep]'s existing cut marks) — and the hairline fix that widens very thin
 * strokes. Geometry is pure so it can be unit tested; drawing happens in [PrintPrep].
 */
object PrinterMarks {
    data class Seg(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    /** A registration target: circle of radius [r] with a cross through its centre. */
    data class Reg(val cx: Float, val cy: Float, val r: Float)
    data class Layout(val bleed: List<Seg>, val reg: List<Reg>)

    const val GAP = 2f
    const val LEN = 12f
    const val REG_R = 5f

    /** Room (points) the marks need outside the trim box, so the page can be scaled to leave it free. */
    fun room(bleed: Float, reg: Boolean, bleedMarks: Boolean): Float =
        if (!reg && !bleedMarks) 0f else maxOf(bleed.coerceAtLeast(0f) + GAP + LEN, if (reg) 2 * REG_R + 2 * GAP + bleed.coerceAtLeast(0f) else 0f) + 2f

    /**
     * Marks for a trim box (x, y, w, h). Bleed marks are short lines at the bleed distance outside each corner
     * (parallel to the crop marks); registration targets sit centred on each side, outside the bleed.
     */
    fun layout(x: Float, y: Float, w: Float, h: Float, bleed: Float, reg: Boolean, bleedMarks: Boolean): Layout {
        val b = bleed.coerceAtLeast(0f)
        val segs = ArrayList<Seg>()
        if (bleedMarks && b > 0f) {
            // for each corner: a horizontal line at the bleed y and a vertical line at the bleed x, outside the bleed box
            for ((cx, cy, dx, dy) in listOf(listOf(x, y, -1f, -1f), listOf(x + w, y, 1f, -1f), listOf(x, y + h, -1f, 1f), listOf(x + w, y + h, 1f, 1f))) {
                val by = cy + dy * b; val bx = cx + dx * b
                segs += Seg(bx + dx * GAP, by, bx + dx * (GAP + LEN), by)
                segs += Seg(bx, by + dy * GAP, bx, by + dy * (GAP + LEN))
            }
        }
        val regs = if (!reg) emptyList() else {
            val off = b + GAP + REG_R
            listOf(Reg(x + w / 2, y - off, REG_R), Reg(x + w / 2, y + h + off, REG_R), Reg(x - off, y + h / 2, REG_R), Reg(x + w + off, y + h / 2, REG_R))
        }
        return Layout(segs, regs)
    }

    /** Hairline fix: a stroke width below [min] (including 0, "thinnest the device can draw") becomes [min]. */
    fun widen(width: Float, min: Float): Float = if (min > 0f && width < min) min else width

    /**
     * Rewrite the operands of every `w` operator in a parsed content stream so no line is thinner than [min].
     * Returns the number of changed widths; [tokens] is changed in place.
     */
    fun fixLineWidths(tokens: MutableList<Any?>, min: Float): Int {
        if (min <= 0f) return 0
        var n = 0
        for (i in tokens.indices) {
            val op = tokens[i] as? Operator ?: continue
            if (op.name != "w" || i == 0) continue
            val v = tokens[i - 1] as? COSNumber ?: continue
            val nw = widen(v.floatValue(), min)
            if (nw != v.floatValue()) { tokens[i - 1] = COSFloat(nw); n++ }
        }
        return n
    }
}
