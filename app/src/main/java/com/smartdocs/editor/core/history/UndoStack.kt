package com.smartdocs.editor.core.history

import java.io.File

/**
 * One step of a document's unified undo history.
 *
 * - [Memory]: a change that only lives in memory (pending annotation edit, in-page form value). [undo] / [redo]
 *   are plain callbacks run on the main thread.
 * - [FileSnapshot]: a change of the document file itself. [before] is a byte copy of the version before the change
 *   (same encrypted bytes for protected files, opened with [beforePassword]); [after] is captured the first time the
 *   step is undone, so redo can put the changed version back.
 *
 * [owner] groups steps by feature (e.g. "annots", "forms") so a feature can ask whether the top step is its own.
 */
sealed class HistoryStep {
    abstract val id: Long
    abstract val label: String
    abstract val time: Long
    abstract val page: Int
    abstract val owner: String?
    /** Disk bytes held by the step (counted against the size limit). */
    abstract val bytes: Long

    class Memory(
        override val label: String,
        val undo: () -> Unit,
        val redo: () -> Unit,
        override val page: Int = -1,
        override val owner: String? = null,
        /** Free payload for the owner (e.g. the annotation lists of the step). */
        val tag: Any? = null,
        override val id: Long = HistoryIds.next(),
        override val time: Long = System.currentTimeMillis(),
    ) : HistoryStep() {
        override val bytes: Long get() = 0L
    }

    class FileSnapshot(
        override val label: String,
        val before: File,
        val beforePassword: String?,
        override val page: Int = -1,
        override val owner: String? = null,
        override val id: Long = HistoryIds.next(),
        override val time: Long = System.currentTimeMillis(),
    ) : HistoryStep() {
        @Volatile var after: File? = null
        @Volatile var afterPassword: String? = null
        override val bytes: Long get() = before.length() + (after?.length() ?: 0L)
        /** Every file this step owns (deleted when the step is dropped). */
        fun files(): List<File> = listOfNotNull(before, after)
    }
}

object HistoryIds {
    private var last = 0L
    @Synchronized fun next(): Long { last++; return last }
}

/**
 * Pure ordering logic of the unified history: an undo list (oldest first) and a redo list (next to redo last).
 * The caller performs the actual undo / redo and then commits the move with [commitUndo] / [commitRedo], so a step
 * whose action failed stays where it was. A new step clears the redo list. Limits: [maxSteps] steps and
 * [maxBytes] disk bytes over both lists; the oldest steps are dropped first (the newest step is always kept).
 * Every dropped step is passed to [onDrop] (file snapshots delete their copies there). Not thread-safe:
 * the owner synchronises.
 */
class UndoStack(
    val maxSteps: Int = 60,
    val maxBytes: Long = 300L * 1024 * 1024,
    private val onDrop: (HistoryStep) -> Unit = {},
) {
    private val undo = ArrayList<HistoryStep>()
    private val redo = ArrayList<HistoryStep>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val size: Int get() = undo.size + redo.size

    /** Undo steps, oldest first (last = the one [peekUndo] returns). */
    fun undoList(): List<HistoryStep> = undo.toList()
    /** Redo steps in redo order (first = the one [peekRedo] returns). */
    fun redoList(): List<HistoryStep> = redo.asReversed().toList()
    fun all(): List<HistoryStep> = undo + redo

    fun peekUndo(): HistoryStep? = undo.lastOrNull()
    fun peekRedo(): HistoryStep? = redo.lastOrNull()

    fun totalBytes(): Long = undo.sumOf { it.bytes } + redo.sumOf { it.bytes }

    /** Record a new change: the redo list is cleared, then the limits are applied. */
    fun push(step: HistoryStep) {
        clearRedo()
        undo += step
        prune()
    }

    fun clearRedo() {
        val r = redo.toList(); redo.clear()
        r.forEach(onDrop)
    }

    /** The top undo step was undone: move it to the redo list. */
    fun commitUndo(step: HistoryStep) {
        if (undo.lastOrNull() !== step) return
        undo.removeAt(undo.lastIndex); redo += step
        prune()
    }

    /** The top redo step was redone: move it back to the undo list (the rest of the redo list stays). */
    fun commitRedo(step: HistoryStep) {
        if (redo.lastOrNull() !== step) return
        redo.removeAt(redo.lastIndex); undo += step
        prune()
    }

    /** How many undos bring the document back to before step [id] (0 = not in the undo list). */
    fun undoCountTo(id: Long): Int {
        val i = undo.indexOfLast { it.id == id }
        return if (i < 0) 0 else undo.size - i
    }

    /** How many redos re-apply step [id] (0 = not in the redo list). */
    fun redoCountTo(id: Long): Int {
        val i = redo.indexOfLast { it.id == id }
        return if (i < 0) 0 else redo.size - i
    }

    fun find(id: Long): HistoryStep? = undo.firstOrNull { it.id == id } ?: redo.firstOrNull { it.id == id }

    /** Remove (and drop) every step matching [pred] from both lists. */
    fun removeIf(pred: (HistoryStep) -> Boolean): Int {
        val gone = undo.filter(pred) + redo.filter(pred)
        undo.removeAll(pred); redo.removeAll(pred)
        gone.forEach(onDrop)
        return gone.size
    }

    /**
     * Consolidation at save time: the in-memory steps (pending annotations / form values that were just written
     * into the file) are replaced by one file step [step]. The redo list is cleared (saving is a new change).
     * Returns the labels of the replaced memory steps, oldest first.
     */
    fun replaceMemory(step: HistoryStep, pred: (HistoryStep) -> Boolean = { it is HistoryStep.Memory }): List<String> {
        val labels = undo.filter { it is HistoryStep.Memory && pred(it) }.map { it.label }
        removeIf { it is HistoryStep.Memory && pred(it) }
        push(step)
        return labels
    }

    fun clear() {
        val all = undo + redo
        undo.clear(); redo.clear()
        all.forEach(onDrop)
    }

    /** Drop the oldest steps while over the limits; the single newest step is always kept. */
    fun prune() {
        while (size > 1 && (size > maxSteps || totalBytes() > maxBytes)) {
            // oldest undo first; with a single undo step left, the furthest redo step goes before it
            val victim = if (undo.size > 1) undo.removeAt(0) else if (redo.size > 1) redo.removeAt(0) else undo.removeAt(0)
            onDrop(victim)
        }
    }
}
