package com.smartdocs.editor.core.gaps6

/** At most [MAX] tags per file (as the reference). Detaching is always allowed. */
object TagLimit {
    const val MAX = 5

    enum class Decision { ATTACH, DETACH, LIMIT_REACHED }

    /** What tapping a tag chip does for a file that currently has [current] tag ids. */
    fun toggle(current: Collection<Long>, tagId: Long): Decision = when {
        tagId in current -> Decision.DETACH
        current.size >= MAX -> Decision.LIMIT_REACHED
        else -> Decision.ATTACH
    }

    /** May one more tag be attached to a file that already has [count] tags? */
    fun canAdd(count: Int): Boolean = count < MAX

    /** Remaining free tag slots. */
    fun remaining(count: Int): Int = (MAX - count).coerceAtLeast(0)
}
