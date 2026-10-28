package com.smartdocs.editor.core.forms5

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle in the reader's displayed page space: points, origin bottom-left, y up
 * ([top] is the larger y). Plain floats so the logic runs in JVM unit tests.
 */
data class Box5(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = top - bottom
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Maps a point of the PDF user space (PdfBox coordinates, widget /Rect) to the displayed page space the
 * reader uses: the crop-box origin moves to 0,0 and the page /Rotate (clockwise) is applied, so the result
 * matches PDFium's page width/height and the reader's PageGeometry.
 */
object PageSpace {
    fun toDisplay(x: Float, y: Float, cropLeft: Float, cropBottom: Float, cropW: Float, cropH: Float, rotate: Int): Pair<Float, Float> {
        val xx = x - cropLeft; val yy = y - cropBottom
        return when (((rotate % 360) + 360) % 360) {
            90 -> yy to (cropW - xx)
            180 -> (cropW - xx) to (cropH - yy)
            270 -> (cropH - yy) to xx
            else -> xx to yy
        }
    }

    fun rectToDisplay(l: Float, b: Float, r: Float, t: Float, cropLeft: Float, cropBottom: Float, cropW: Float, cropH: Float, rotate: Int): Box5 {
        val a = toDisplay(l, b, cropLeft, cropBottom, cropW, cropH, rotate)
        val c = toDisplay(r, t, cropLeft, cropBottom, cropW, cropH, rotate)
        return Box5(min(a.first, c.first), max(a.second, c.second), max(a.first, c.first), min(a.second, c.second))
    }
}

/** One stop of the Next / Previous order. */
data class NavItem(
    val id: String,
    val page: Int,
    val box: Box5,
    val annotIndex: Int = 0,
    val tabStop: Boolean = true,
)

/**
 * Field navigation order (Next / Previous in the field bar and the IME "next" key).
 * Pages in order; inside a page either rows (default, `/Tabs /R` or `/S` or none) or columns (`/Tabs /C`).
 * Rows: fields whose vertical centres fall inside the current row band join that row; a row reads
 * right-to-left on Arabic pages and left-to-right otherwise. The annotation index breaks ties.
 */
object FieldNavigation {

    fun order(items: List<NavItem>, rtl: (Int) -> Boolean = { false }, tabs: (Int) -> String? = { null }): List<String> {
        val out = ArrayList<String>()
        items.filter { it.tabStop }.groupBy { it.page }.toSortedMap().forEach { (page, list) ->
            // "A" (§37 preference "document order" on pages without /Tabs): the /Annots order
            val ordered = when (tabs(page)) { "C" -> columns(list, rtl(page)); "A" -> list.sortedBy { it.annotIndex }; else -> rows(list, rtl(page)) }
            ordered.forEach { out += it.id }
        }
        return out
    }

    private fun rows(list: List<NavItem>, rtl: Boolean): List<NavItem> {
        val sorted = list.sortedWith(compareByDescending<NavItem> { it.box.top }.thenBy { it.annotIndex })
        val rows = ArrayList<MutableList<NavItem>>()
        var bandBottom = 0f
        for (it in sorted) {
            // sorted by top: a field belongs to the current row while its centre is not below the row's first field
            if (rows.isEmpty() || it.box.centerY < bandBottom) { rows += mutableListOf(it); bandBottom = it.box.bottom }
            else rows.last() += it
        }
        return rows.flatMap { r ->
            if (rtl) r.sortedWith(compareByDescending<NavItem> { it.box.right }.thenBy { it.annotIndex })
            else r.sortedWith(compareBy<NavItem> { it.box.left }.thenBy { it.annotIndex })
        }
    }

    private fun columns(list: List<NavItem>, rtl: Boolean): List<NavItem> {
        val sorted = if (rtl) list.sortedWith(compareByDescending<NavItem> { it.box.right }.thenBy { it.annotIndex })
        else list.sortedWith(compareBy<NavItem> { it.box.left }.thenBy { it.annotIndex })
        // a column is the fields whose leading edge (left, or right on RTL pages) lines up with the column's first field
        val cols = ArrayList<MutableList<NavItem>>()
        var edge = 0f
        for (it in sorted) {
            val e = if (rtl) it.box.right else it.box.left
            if (cols.isEmpty() || kotlin.math.abs(e - edge) > 12f) { cols += mutableListOf(it); edge = e }
            else cols.last() += it
        }
        return cols.flatMap { col -> col.sortedWith(compareByDescending<NavItem> { it.box.top }.thenBy { it.annotIndex }) }
    }

    /** The id [delta] stops away from [current] (wrapping), the first/last when [current] is not in the order. */
    fun step(order: List<String>, current: String?, delta: Int): String? {
        if (order.isEmpty()) return null
        val i = order.indexOf(current)
        if (i < 0) return if (delta >= 0) order.first() else order.last()
        val n = order.size
        return order[((i + delta) % n + n) % n]
    }

    /** "n of N" position, 1-based; 0 when absent. */
    fun position(order: List<String>, current: String?): Int = order.indexOf(current) + 1
}
