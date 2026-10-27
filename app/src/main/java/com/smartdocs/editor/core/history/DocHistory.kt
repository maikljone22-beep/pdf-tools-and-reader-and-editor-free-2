package com.smartdocs.editor.core.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Unified multi-level undo / redo of one open document (see MODULE_history_r5.md).
 *
 * File-level changes keep a byte copy of the previous version in `<tempDir>/history/<docId>/`; in-memory changes
 * (pending annotations, form values) are callbacks. Both live in one [UndoStack], so they undo in the order they
 * were made. The reader supplies [current] (the working file + its password) and [restorer], which puts a
 * version back through its safe swap path (close session, replace, reopen at the same page, write back) without
 * recording a new step.
 *
 * Observable for Compose through [version]; every mutation bumps it.
 */
class DocHistory internal constructor(val dir: File, maxSteps: Int, maxBytes: Long) {

    private val lock = Any()
    private val stack = UndoStack(maxSteps, maxBytes) { drop(it) }
    private val opMutex = Mutex()

    /** Bumped on every change of the lists (read it to recompose). */
    var version by mutableIntStateOf(0); private set
    /** An undo / redo is running (buttons disabled). */
    var busy by mutableStateOf(false); private set
    @Volatile private var disposed = false

    var current: () -> Pair<File, String?>? = { null }
    var restorer: (suspend (file: File, password: String?) -> Unit)? = null

    val canUndo: Boolean get() { version; return !busy && synchronized(lock) { stack.canUndo } }
    val canRedo: Boolean get() { version; return !busy && synchronized(lock) { stack.canRedo } }
    fun undoList(): List<HistoryStep> { version; return synchronized(lock) { stack.undoList() } }
    fun redoList(): List<HistoryStep> { version; return synchronized(lock) { stack.redoList() } }
    fun peekUndo(): HistoryStep? { version; return synchronized(lock) { stack.peekUndo() } }
    fun peekRedo(): HistoryStep? { version; return synchronized(lock) { stack.peekRedo() } }
    fun steps(): List<HistoryStep> = synchronized(lock) { stack.all() }
    fun totalBytes(): Long = synchronized(lock) { stack.totalBytes() }

    private fun bump() { version++ }

    private fun drop(step: HistoryStep) {
        if (step is HistoryStep.FileSnapshot) step.files().forEach { runCatching { it.delete() } }
    }

    // ================= recording =================

    /** Byte copy of [src] into the history folder (the same encrypted bytes for protected files). */
    suspend fun copyIn(src: File, tag: String = "b"): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val ext = src.extension.ifBlank { "pdf" }
        val out = File(dir, "${System.nanoTime()}_$tag.$ext")
        src.copyTo(out, overwrite = true)
        out
    }

    fun push(step: HistoryStep) {
        if (disposed) { drop(step); return }
        synchronized(lock) { stack.push(step) }
        bump()
    }

    fun pushMemory(label: String, page: Int, owner: String?, tag: Any? = null, undo: () -> Unit, redo: () -> Unit): HistoryStep.Memory =
        HistoryStep.Memory(label, undo, redo, page, owner, tag).also { push(it) }

    /** Replace in-memory steps (just written into the file at save time) by one file step. Returns their labels. */
    fun replaceMemory(step: HistoryStep, pred: (HistoryStep) -> Boolean = { true }): List<String> {
        if (disposed) { drop(step); return emptyList() }
        val l = synchronized(lock) { stack.replaceMemory(step, pred) }
        bump(); return l
    }

    /** Labels of the in-memory steps currently in the undo list (oldest first). */
    fun memoryLabels(): List<String> = synchronized(lock) { stack.undoList().filterIsInstance<HistoryStep.Memory>().map { it.label } }

    fun hasMemorySteps(): Boolean = synchronized(lock) { stack.all().any { it is HistoryStep.Memory } }

    fun removeOwner(owner: String) { if (synchronized(lock) { stack.removeIf { it.owner == owner && it is HistoryStep.Memory } } > 0) bump() }

    /** Drop every in-memory step (pending changes were discarded or are no longer reachable). */
    fun removeMemory() { if (synchronized(lock) { stack.removeIf { it is HistoryStep.Memory } } > 0) bump() }

    // ================= undo / redo =================

    /** Undo the newest step. Returns it, or null when there was nothing to undo. Throws when restoring failed. */
    suspend fun undo(): HistoryStep? = opMutex.withLock { undoLocked() }

    suspend fun redo(): HistoryStep? = opMutex.withLock { redoLocked() }

    /** Undo back to before step [id] (it and every newer step are undone). Returns the last undone step. */
    suspend fun undoTo(id: Long): HistoryStep? = opMutex.withLock {
        var n = synchronized(lock) { stack.undoCountTo(id) }
        var last: HistoryStep? = null
        while (n-- > 0) last = undoLocked() ?: break
        last
    }

    /** Redo up to and including step [id]. Returns the last redone step. */
    suspend fun redoTo(id: Long): HistoryStep? = opMutex.withLock {
        var n = synchronized(lock) { stack.redoCountTo(id) }
        var last: HistoryStep? = null
        while (n-- > 0) last = redoLocked() ?: break
        last
    }

    /**
     * Undo through the file step whose snapshot is [before] (used by "تراجع" buttons that kept the returned copy).
     * Audit AN-08: when newer changes were made since, they would be undone too, so this refuses (returns null)
     * unless [allowNewer]; check [newerStepsThan] to tell the user why.
     */
    suspend fun undoThrough(before: File, allowNewer: Boolean = false): HistoryStep? {
        val id = synchronized(lock) { stack.undoList().lastOrNull { it is HistoryStep.FileSnapshot && it.before == before }?.id } ?: return null
        if (!allowNewer && newerStepsThan(before) > 0) return null
        return undoTo(id)
    }

    /** The steps [undoTo] ([id]) would undo, newest first. */
    fun undoStepsTo(id: Long): List<HistoryStep> = synchronized(lock) {
        val l = stack.undoList(); val i = l.indexOfLast { it.id == id }
        if (i < 0) emptyList() else l.subList(i, l.size).asReversed().toList()
    }

    /** The steps [redoTo] ([id]) would redo, in redo order. */
    fun redoStepsTo(id: Long): List<HistoryStep> = synchronized(lock) {
        val l = stack.redoList(); val i = l.indexOfFirst { it.id == id }
        if (i < 0) emptyList() else l.subList(0, i + 1).toList()
    }

    /** How many undo steps were made after the file step that saved [before]; -1 when it is no longer in the history. */
    fun newerStepsThan(before: File): Int = synchronized(lock) {
        val l = stack.undoList()
        val i = l.indexOfLast { it is HistoryStep.FileSnapshot && it.before == before }
        if (i < 0) -1 else l.size - 1 - i
    }

    /** Whether the file step that saved [before] can still be undone. */
    fun canUndoThrough(before: File): Boolean { version; return synchronized(lock) { stack.undoList().any { it is HistoryStep.FileSnapshot && it.before == before } } }

    private suspend fun undoLocked(): HistoryStep? {
        val step = synchronized(lock) { stack.peekUndo() } ?: return null
        busy = true
        try {
            when (step) {
                is HistoryStep.Memory -> withContext(Dispatchers.Main.immediate) { step.undo() }
                is HistoryStep.FileSnapshot -> withContext(NonCancellable) {
                    val r = restorer ?: error("no document")
                    if (!step.before.exists()) error("snapshot missing")
                    if (step.after == null) {
                        val (f, pw) = current() ?: error("no document")
                        step.after = copyIn(f, "a"); step.afterPassword = pw
                    }
                    r(step.before, step.beforePassword)
                }
            }
            synchronized(lock) { stack.commitUndo(step) }
            return step
        } finally { busy = false; bump() }
    }

    private suspend fun redoLocked(): HistoryStep? {
        val step = synchronized(lock) { stack.peekRedo() } ?: return null
        busy = true
        try {
            when (step) {
                is HistoryStep.Memory -> withContext(Dispatchers.Main.immediate) { step.redo() }
                is HistoryStep.FileSnapshot -> withContext(NonCancellable) {
                    val r = restorer ?: error("no document")
                    val a = step.after?.takeIf { it.exists() } ?: error("snapshot missing")
                    r(a, step.afterPassword)
                }
            }
            synchronized(lock) { stack.commitRedo(step) }
            return step
        } finally { busy = false; bump() }
    }

    // ================= lifetime =================

    /** The document was closed for good: drop every step and delete the folder. */
    fun dispose() {
        disposed = true
        synchronized(lock) { stack.clear() }
        runCatching { dir.deleteRecursively() }
        synchronized(live) { live.remove(dir.absolutePath) }
    }

    companion object {
        const val MAX_STEPS = 60
        const val MAX_BYTES = 300L * 1024 * 1024
        private val live = HashSet<String>()

        fun root(tempDir: File) = File(tempDir, "history")

        /**
         * History of a newly opened document. Folders left by an earlier process (crash, killed app) are deleted
         * here: every folder not owned by a live [DocHistory] of this process is stale.
         */
        fun open(tempDir: File, docKey: String, maxSteps: Int = MAX_STEPS, maxBytes: Long = MAX_BYTES): DocHistory {
            val root = root(tempDir)
            cleanupStale(root)
            val id = HistoryNames.folderName(docKey, System.nanoTime())
            val dir = File(root, id)
            synchronized(live) { live += dir.absolutePath }
            return DocHistory(dir, maxSteps, maxBytes)
        }

        fun cleanupStale(root: File) {
            val keep = synchronized(live) { live.toSet() }
            root.listFiles()?.forEach { d -> if (d.absolutePath !in keep) runCatching { d.deleteRecursively() } }
        }
    }
}

/** Pure helpers (unit-tested). */
object HistoryNames {
    /** Folder name of one open document: a stable hash of the document key plus a unique open counter. */
    fun folderName(docKey: String, unique: Long): String =
        "d" + Integer.toHexString(docKey.hashCode()) + "_" + java.lang.Long.toHexString(unique)

    /** Label of the file step that replaces in-memory steps at save time: the last label, plus how many others. */
    fun groupLabel(labels: List<String>, fallback: String, more: (String, Int) -> String): String = when {
        labels.isEmpty() -> fallback
        labels.size == 1 -> labels[0]
        else -> more(labels.last(), labels.size - 1)
    }
}
