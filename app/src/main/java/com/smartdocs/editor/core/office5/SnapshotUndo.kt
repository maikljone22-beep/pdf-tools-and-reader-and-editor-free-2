package com.smartdocs.editor.core.office5

/**
 * Multi-level undo/redo for a package-backed editor: a checkpoint is a whole-package snapshot (unchanged
 * parts share their byte arrays, so a checkpoint costs about the size of the edited parts).
 */
class SnapshotUndo(private val pkg: OPackage, private val maxLevels: Int = 40, private val maxBytes: Long = 96L shl 20) {
    private val undo = ArrayDeque<OPackage.Snapshot>()
    private val redo = ArrayDeque<OPackage.Snapshot>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Call before a change: the current state becomes one undo step. */
    fun checkpoint() {
        undo.addLast(pkg.snapshot()); redo.clear()
        while (undo.size > maxLevels) undo.removeFirst()
        // rough memory cap: count only the newest part sizes once
        while (undo.size > 2 && undo.last().size * undo.size > maxBytes) undo.removeFirst()
    }

    fun undo(): Boolean {
        val s = undo.removeLastOrNull() ?: return false
        redo.addLast(pkg.snapshot()); pkg.restore(s); return true
    }

    fun redo(): Boolean {
        val s = redo.removeLastOrNull() ?: return false
        undo.addLast(pkg.snapshot()); pkg.restore(s); return true
    }

    fun clear() { undo.clear(); redo.clear() }
}
