package com.smartdocs.editor.core.gaps6

/**
 * Pure layout decision for foldables / dual screens / large screens (input comes from androidx.window's
 * FoldingFeature + the window width; see ui/gaps6/FoldState.kt).
 */
object FoldLayout {
    /** Width (dp) from which list-detail two-pane is used without a hinge (Material "expanded" class). */
    const val EXPANDED_DP = 840

    enum class Posture { NONE, FLAT, BOOK, TABLETOP }
    enum class Mode { SINGLE, TWO_PANE, BOOK, TABLETOP }

    data class Input(
        val widthDp: Int,
        val hasFold: Boolean = false,
        val vertical: Boolean = true,
        val halfOpened: Boolean = false,
        val separating: Boolean = false,
    )

    fun posture(i: Input): Posture = when {
        !i.hasFold -> Posture.NONE
        i.halfOpened && i.vertical -> Posture.BOOK
        i.halfOpened && !i.vertical -> Posture.TABLETOP
        else -> Posture.FLAT
    }

    /** How a list-detail screen (Home / Files) lays out. */
    fun listDetail(i: Input): Mode = when (posture(i)) {
        Posture.BOOK -> Mode.TWO_PANE
        Posture.TABLETOP -> Mode.TABLETOP
        Posture.FLAT -> if ((i.separating && i.vertical) || i.widthDp >= EXPANDED_DP) Mode.TWO_PANE else Mode.SINGLE
        Posture.NONE -> if (i.widthDp >= EXPANDED_DP) Mode.TWO_PANE else Mode.SINGLE
    }

    /** How the reader lays out: two pages across a vertical hinge (BOOK), document over panel (TABLETOP). */
    fun reader(i: Input): Mode = when (posture(i)) {
        Posture.BOOK -> Mode.BOOK
        Posture.TABLETOP -> Mode.TABLETOP
        Posture.FLAT -> if (i.separating && i.vertical) Mode.BOOK else if (i.widthDp >= EXPANDED_DP) Mode.TWO_PANE else Mode.SINGLE
        Posture.NONE -> if (i.widthDp >= EXPANDED_DP) Mode.TWO_PANE else Mode.SINGLE
    }

    /**
     * Width of the first pane (px) when splitting a container that starts at [containerLeft] with width
     * [containerWidth] around a vertical hinge [hingeLeft]..[hingeRight] (window px). Null when the hinge is
     * not inside the container (then use weights).
     */
    fun splitAtHinge(containerLeft: Int, containerWidth: Int, hingeLeft: Int, hingeRight: Int): Pair<Int, Int>? {
        val l = hingeLeft - containerLeft
        val r = hingeRight - containerLeft
        if (l <= containerWidth / 5 || r >= containerWidth * 4 / 5 || r < l) return null
        return l to (r - l)
    }
}
