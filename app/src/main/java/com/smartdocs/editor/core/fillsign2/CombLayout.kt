package com.smartdocs.editor.core.fillsign2

/**
 * Fill & Sign "Comb" text (nitro-fillsign2): the typed characters are spread one per cell over N equal cells, for
 * boxed paper forms (ID numbers, dates, postcodes). Pure layout math, unit-tested; the appearance writer
 * (core/pdf/Annotations) and the on-screen preview (ui/reader/AnnotationOverlay) both place glyphs with it.
 */
object CombLayout {
    const val MAX_CELLS = 99

    /** Characters (code points, so surrogate pairs stay whole) that go into cells: line breaks and controls are dropped. */
    fun chars(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); val n = Character.charCount(cp)
            if (!Character.isISOControl(cp)) out += text.substring(i, i + n)
            i += n
        }
        return out
    }

    /** Default number of cells for [text]: one per character (at least 1). */
    fun defaultCells(text: String): Int = chars(text).size.coerceIn(1, MAX_CELLS)

    /**
     * (cell index, character) for every character that fits into [cells] cells. Left-to-right text starts in the
     * first (leftmost) cell; right-to-left text (Arabic boxed forms) starts in the rightmost cell.
     */
    fun place(text: String, cells: Int, rtl: Boolean): List<Pair<Int, String>> {
        val n = cells.coerceIn(1, MAX_CELLS)
        return chars(text).take(n).mapIndexed { i, c -> (if (rtl) n - 1 - i else i) to c }
    }

    /**
     * Tab audit D29: characters of [text] that do not fit into [cells] cells (they would be dropped). 0 when all fit.
     */
    fun overflow(text: String, cells: Int): Int = (chars(text).size - cells.coerceIn(1, MAX_CELLS)).coerceAtLeast(0)

    /** Cells to use for [text] when the user asked for [requested]: grown so no character is dropped (up to [MAX_CELLS]). */
    fun cellsFor(text: String, requested: Int?): Int = maxOf(requested ?: 0, chars(text).size).coerceIn(1, MAX_CELLS)

    /** Left edge of cell [i] of a box starting at [left] that is [width] wide. */
    fun cellLeft(left: Float, width: Float, cells: Int, i: Int): Float = left + width * i / cells.coerceAtLeast(1)

    /** x of a glyph [glyphWidth] wide centred in cell [i]. */
    fun glyphX(left: Float, width: Float, cells: Int, i: Int, glyphWidth: Float): Float {
        val cw = width / cells.coerceAtLeast(1)
        return cellLeft(left, width, cells, i) + (cw - glyphWidth) / 2f
    }

    /** Character spacing (PDF `Tc`) that advances one cell per glyph of an average width [avgGlyph]. */
    fun charSpacing(cellWidth: Float, avgGlyph: Float): Float = (cellWidth - avgGlyph).coerceAtLeast(0f)

    /** Box of the comb: [cells] x [cellWidth] wide, one line of [fontSize] high (with padding). */
    fun boxSize(cells: Int, cellWidth: Float, fontSize: Float): Pair<Float, Float> =
        cells.coerceIn(1, MAX_CELLS) * cellWidth.coerceAtLeast(1f) to fontSize * 1.5f + 4f

    /** Default cell width for a font size: a little wider than a digit. */
    fun defaultCellWidth(fontSize: Float): Float = (fontSize * 1.4f).coerceAtLeast(6f)
}
