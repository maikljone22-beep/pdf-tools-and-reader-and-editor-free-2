package com.smartdocs.editor.core.forms5

/** 8.69: the Acrobat named actions (/S /Named /N …) a form can run at fill time, by name (case-insensitive). */
enum class NamedOp { NEXT, PREV, FIRST, LAST, PRINT, SAVE, FIND, ZOOM_IN, ZOOM_OUT, FIT_WIDTH, FIT_PAGE, FULL_SCREEN, SINGLE_PAGE, ONE_COLUMN, TWO_PAGES, TWO_COLUMNS, UNSUPPORTED }

object NamedActions {
    private val MAP = mapOf(
        "nextpage" to NamedOp.NEXT, "prevpage" to NamedOp.PREV, "firstpage" to NamedOp.FIRST, "lastpage" to NamedOp.LAST,
        "print" to NamedOp.PRINT, "save" to NamedOp.SAVE,
        "find" to NamedOp.FIND, "acrosrch:query" to NamedOp.FIND, "findcurrentdocument" to NamedOp.FIND,
        "zoomviewin" to NamedOp.ZOOM_IN, "zoomviewout" to NamedOp.ZOOM_OUT,
        "fitpage" to NamedOp.FIT_PAGE, "fitheight" to NamedOp.FIT_PAGE, "fitwidth" to NamedOp.FIT_WIDTH, "fitvisible" to NamedOp.FIT_WIDTH, "actualsize" to NamedOp.FIT_WIDTH,
        "fullscreen" to NamedOp.FULL_SCREEN, "fullscreenview" to NamedOp.FULL_SCREEN,
        "singlepage" to NamedOp.SINGLE_PAGE, "onecolumn" to NamedOp.ONE_COLUMN, "twopages" to NamedOp.TWO_PAGES, "twocolumns" to NamedOp.TWO_COLUMNS,
    )

    fun resolve(name: String): NamedOp = MAP[name.trim().lowercase()] ?: NamedOp.UNSUPPORTED

    /** JS doc.scroll: a y of the page's user space (0 = bottom) → share of the page height from the top (0..1). */
    fun scrollFraction(y: Float, pageH: Float): Float = if (pageH <= 0f) 0f else (1f - y / pageH).coerceIn(0f, 1f)

    /** One zoom step of ZoomViewIn / ZoomViewOut, kept within [min]..[max]. */
    fun zoomStep(zoom: Float, inward: Boolean, min: Float, max: Float): Float = (if (inward) zoom * 1.25f else zoom / 1.25f).coerceIn(min, max)
}
