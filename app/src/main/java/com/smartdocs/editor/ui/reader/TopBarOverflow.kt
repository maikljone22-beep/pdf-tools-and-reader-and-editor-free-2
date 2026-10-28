package com.smartdocs.editor.ui.reader

/**
 * tab-view-core V-03: which top-bar actions fit next to the title, the back arrow and the always-visible ⋮ button.
 * Pure (unit-tested). [items] are (id, width dp) in keep-priority order; items stay in the bar while they fit into
 * [budgetDp]. Once one item overflows every later one overflows too, so a lower-priority action never takes the
 * place of a higher one. The overflowing ones are offered in the ⋮ menu, which is always visible.
 */
object TopBarOverflow {
    /** Width of the nav icon, the ⋮ button and the smallest useful title (dp). */
    const val RESERVED_DP = 48 + 48 + 104

    data class Split(val shown: List<String>, val overflow: List<String>)

    fun partition(budgetDp: Int, items: List<Pair<String, Int>>): Split {
        var left = budgetDp
        val shown = ArrayList<String>(); val over = ArrayList<String>()
        var full = false
        for ((id, w) in items) {
            if (!full && w <= left) { shown += id; left -= w } else { full = true; over += id }
        }
        return Split(shown, over)
    }

    /** Budget for the actions of a top bar [screenDp] wide. */
    fun budget(screenDp: Int): Int = (screenDp - RESERVED_DP).coerceAtLeast(0)
}
