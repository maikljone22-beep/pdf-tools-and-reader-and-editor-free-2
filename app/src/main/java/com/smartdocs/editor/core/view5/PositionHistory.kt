package com.smartdocs.editor.core.view5

/**
 * Back / forward history of reading positions (page numbers, 0-based), like a browser:
 * [visit] records the page being left before a jump; [back] / [forward] move through it.
 * Consecutive duplicates are collapsed and the back stack is capped at [max] entries.
 */
class PositionHistory(private val max: Int = 50) {
    private val backStack = ArrayDeque<Int>()
    private val fwdStack = ArrayDeque<Int>()

    val canBack: Boolean get() = backStack.isNotEmpty()
    val canForward: Boolean get() = fwdStack.isNotEmpty()
    val backSize: Int get() = backStack.size
    val forwardSize: Int get() = fwdStack.size
    fun peekBack(): Int? = backStack.lastOrNull()

    /** The user jumps from [from] to [to]. Recorded only when they differ; clears the forward stack. */
    fun visit(from: Int, to: Int) {
        if (from == to || from < 0) return
        if (backStack.lastOrNull() != from) backStack.addLast(from)
        while (backStack.size > max) backStack.removeFirst()
        fwdStack.clear()
    }

    /** Go back from [current]: returns the page to show, or null when there is none. */
    fun back(current: Int): Int? {
        var target = backStack.removeLastOrNull() ?: return null
        while (target == current) target = backStack.removeLastOrNull() ?: return null
        if (fwdStack.lastOrNull() != current) fwdStack.addLast(current)
        return target
    }

    fun forward(current: Int): Int? {
        var target = fwdStack.removeLastOrNull() ?: return null
        while (target == current) target = fwdStack.removeLastOrNull() ?: return null
        if (backStack.lastOrNull() != current) backStack.addLast(current)
        while (backStack.size > max) backStack.removeFirst()
        return target
    }

    fun clear() { backStack.clear(); fwdStack.clear() }

    /** Back entries, most recent first (for a history list). */
    fun backList(): List<Int> = backStack.reversed()
}
