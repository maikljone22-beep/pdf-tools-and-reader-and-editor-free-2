package com.smartdocs.editor.core.annot

import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import kotlin.math.abs

/**
 * Pencil stroke merging (Nitro pencil preference): a stroke drawn within the timeout of the previous one, on the same
 * page and in the same style, joins that ink annotation instead of becoming a new one. 0 ms = off.
 */
object InkMerge {
    val CHOICES = listOf(0L, 1000L, 2000L, 3000L)

    fun canMerge(prev: Annot?, next: Annot, elapsedMs: Long, timeoutMs: Long): Boolean =
        timeoutMs > 0 && prev != null && elapsedMs in 0..timeoutMs &&
            prev.kind == AnnotKind.INK && next.kind == AnnotKind.INK && !prev.locked && prev.page == next.page &&
            prev.color == next.color && abs(prev.strokeWidth - next.strokeWidth) < 0.01f && abs(prev.opacity - next.opacity) < 0.01f && prev.dash == next.dash

    fun merge(prev: Annot, next: Annot): Annot = prev.copy(paths = prev.paths + next.paths)

    /** The next choice after [current] (menu cycles off → 1 s → 2 s → 3 s → off). */
    fun next(current: Long): Long = CHOICES[(CHOICES.indexOf(current).coerceAtLeast(0) + 1) % CHOICES.size]
}
