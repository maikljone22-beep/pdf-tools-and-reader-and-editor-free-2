package com.smartdocs.editor.core.annot

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * §37 tab order as a preference: the order of comments (comments panel, page sort) and of form fields
 * (Next / Previous) on pages that have no tab order of their own (/Tabs).
 * STRUCTURE follows the document order (structure tree / annotation order), ROWS reads row by row and COLUMNS
 * column by column. [tabs] is the /Tabs code used as the fallback ("A" = document order).
 */
enum class NavOrder(val tabs: String) { STRUCTURE("A"), ROWS("R"), COLUMNS("C") }

/** One item to order: [top] / [left] in PDF points (top = larger y), [index] its place in the document order. */
data class NavKey(val page: Int, val top: Float, val left: Float, val index: Int)

object NavOrderLogic {
    /** Row / column band in points: items this close count as the same row or column. */
    private const val BAND = 6f

    fun comparator(order: NavOrder, rtl: Boolean = false): Comparator<NavKey> = when (order) {
        NavOrder.STRUCTURE -> compareBy<NavKey>({ it.page }, { it.index })
        NavOrder.ROWS -> compareBy<NavKey>({ it.page }, { -Math.round(it.top / BAND) }, { if (rtl) -it.left else it.left }, { it.index })
        NavOrder.COLUMNS -> compareBy<NavKey>({ it.page }, { Math.round((if (rtl) -it.left else it.left) / BAND) }, { -it.top }, { it.index })
    }

    fun <T> sort(items: List<T>, order: NavOrder, rtl: Boolean = false, key: (T) -> NavKey): List<T> {
        val c = comparator(order, rtl)
        return items.sortedWith { a, b -> c.compare(key(a), key(b)) }
    }
}

/** The preference, kept with the comment tools' preferences ("comment_prefs"). */
object NavOrderPrefs {
    private const val FILE = "comment_prefs"
    private const val KEY = "nav_order"

    var order by mutableStateOf(NavOrder.ROWS); private set
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val name = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        order = NavOrder.entries.firstOrNull { it.name == name } ?: NavOrder.ROWS
        loaded = true
    }

    fun set(context: Context, v: NavOrder) {
        order = v
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, v.name).apply()
    }
}
