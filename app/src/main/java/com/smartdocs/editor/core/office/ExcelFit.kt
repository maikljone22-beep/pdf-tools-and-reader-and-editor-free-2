package com.smartdocs.editor.core.office

/**
 * A worksheet's own print setup: `<pageSetup orientation fitToWidth fitToHeight scale>` and
 * `<sheetPr><pageSetUpPr fitToPage>`. fitToWidth / fitToHeight count only when [fitToPage] is on; 0 means "automatic"
 * (no limit in that direction), as in Excel.
 */
data class SheetPrint(
    val landscape: Boolean? = null,
    val fitToPage: Boolean = false,
    val fitToWidth: Int = 1,
    val fitToHeight: Int = 1,
    val scale: Int = 100,
) {
    companion object {
        /** From the attributes of `<pageSetup>` ([setup]) and `<pageSetUpPr>` ([pr]); null when the sheet has neither. */
        fun of(setup: Map<String, String>?, pr: Map<String, String>?): SheetPrint? {
            if (setup == null && pr == null) return null
            fun int(k: String, def: Int) = setup?.get(k)?.toIntOrNull() ?: def
            val fit = pr?.get("fitToPage").let { it == "1" || it == "true" }
            return SheetPrint(
                landscape = when (setup?.get("orientation")) { "landscape" -> true; "portrait" -> false; else -> null },
                fitToPage = fit,
                fitToWidth = int("fitToWidth", 1).coerceAtLeast(0),
                fitToHeight = int("fitToHeight", 1).coerceAtLeast(0),
                scale = int("scale", 100).coerceIn(10, 400),
            )
        }
    }
}

/**
 * FINAL_CHECK_2 #9 Excel → PDF fit to page. Scaling of a printed sheet and the column bands it is printed in
 * (Excel's "down, then over" page order): columns that still do not fit the page width after scaling go on further
 * pages instead of being cut off.
 */
object ExcelFit {
    /** The sheet's own setup when it has one (fit to N×M pages, scale %), else all columns on one page width. */
    const val AUTO = 0
    /** Actual size (100 %); wide sheets go on to further pages. */
    const val ACTUAL = 1
    /** All columns on one page width. */
    const val COLUMNS = 2
    /** The whole sheet on one page. */
    const val ONE_PAGE = 3

    /** Excel's own limits for the print scale. */
    const val MIN_ZOOM = 0.1f
    const val MAX_ZOOM = 4f

    /**
     * [zoom] for the printed table; [bands] the column ranges (absolute indexes) printed one after the other;
     * [onePart] when the rows must not be split into print parts (fit to a number of pages tall).
     */
    data class Plan(val zoom: Float, val bands: List<IntRange>, val onePart: Boolean)

    /**
     * [cols] the used columns (absolute indexes) with [colPx] their widths, [heightPx] the height of the used rows;
     * [pageW] × [pageH] the printable page in CSS px.
     */
    fun plan(cols: IntRange, colPx: List<Int>, heightPx: Int, pageW: Int, pageH: Int, mode: Int, setup: SheetPrint?): Plan {
        val width = colPx.sum().coerceAtLeast(1).toFloat()
        val height = heightPx.coerceAtLeast(1).toFloat()
        val toWidth = pageW / width
        val toHeight = pageH / height
        var onePart = false
        val zoom = when (mode) {
            ACTUAL -> 1f
            COLUMNS -> minOf(1f, toWidth)
            ONE_PAGE -> { onePart = true; minOf(1f, toWidth, toHeight) }
            else -> when {
                setup?.fitToPage == true -> {
                    val w = if (setup.fitToWidth > 0) toWidth * setup.fitToWidth else Float.MAX_VALUE
                    val h = if (setup.fitToHeight > 0) toHeight * setup.fitToHeight else Float.MAX_VALUE
                    onePart = setup.fitToHeight > 0
                    minOf(1f, w, h)
                }
                setup != null && setup.scale != 100 -> setup.scale / 100f
                else -> minOf(1f, toWidth)
            }
        }.coerceIn(MIN_ZOOM, MAX_ZOOM)
        return Plan(zoom, bands(cols, colPx, pageW / zoom), onePart)
    }

    /** Columns grouped into bands no wider than [avail] px (a single wider column is a band of its own). */
    fun bands(cols: IntRange, colPx: List<Int>, avail: Float): List<IntRange> {
        if (cols.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var start = cols.first; var w = 0f
        cols.forEachIndexed { i, c ->
            val cw = colPx.getOrElse(i) { 0 }
            if (c > start && w + cw > avail + 0.5f) { out += start until c; start = c; w = 0f }
            w += cw
        }
        out += start..cols.last
        return out
    }

    /**
     * Document orientation: with [AUTO] the first sheet that sets one decides; otherwise (or when none does) null, and
     * the caller picks from the width of the sheets.
     */
    fun landscape(setups: List<SheetPrint?>, mode: Int): Boolean? =
        if (mode == AUTO) setups.firstNotNullOfOrNull { it?.landscape } else null
}
