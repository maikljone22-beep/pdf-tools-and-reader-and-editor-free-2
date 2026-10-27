package com.smartdocs.editor.core.sync

import java.io.File

/**
 * Replaces a document with new content without ever leaving it truncated (audit DI-26).
 *
 * The new bytes go to a hidden sibling `.<name>.part` first (sync ignores it, see [SyncRules.ignored]) and its
 * length is checked. Then the original is renamed to `.<name>.bak`, the part is renamed to `<name>` and the backup is
 * deleted. When the folder cannot rename, the original is copied to the backup first, overwritten in place, checked,
 * and restored from the backup when the check fails; the backup is kept if even the restore fails.
 *
 * Pure over [Ops] so it is unit tested with a fake (SafeReplaceTest); [SyncEngine] passes SAF operations.
 */
object SafeReplace {
    interface Ops<D> {
        /** Creates an empty sibling document named [name] (null when the folder refuses). */
        fun create(name: String): D?
        /** Overwrites [doc] with the bytes of [src] (truncating). */
        fun write(doc: D, src: File)
        /** Copies the content of [from] into [to] (truncating). */
        fun copy(from: D, to: D)
        fun length(doc: D): Long
        /** Renames [doc]; returns the renamed document, or null when renaming is not supported / failed. */
        fun rename(doc: D, name: String): D?
        fun delete(doc: D): Boolean
    }

    fun partName(name: String) = ".$name.part"
    fun backupName(name: String) = ".$name.bak"

    /**
     * Puts the content of [src] into [target] (named [name]); [target] null = a new document [name] is created.
     * Returns the document that now holds the content. Throws when it could not be written; the original is then intact
     * (or, in the no-rename fallback when even the restore failed, kept as `.<name>.bak`).
     */
    fun <D> replace(ops: Ops<D>, target: D?, name: String, src: File): D {
        val want = src.length()
        val part = ops.create(partName(name))
        if (part != null) {
            val ok = runCatching { ops.write(part, src); ops.length(part) == want }.getOrDefault(false)
            if (!ok) { ops.delete(part); throw IllegalStateException("cannot write $name") }
            if (target == null) {
                ops.rename(part, name)?.let { return it }
                ops.delete(part)
                return writeNew(ops, name, src, want)
            }
            val bak = ops.rename(target, backupName(name))
            if (bak != null) {
                val done = ops.rename(part, name)
                if (done != null) { ops.delete(bak); return done }
                // could not put the new file in place: bring the original back
                ops.rename(bak, name) ?: throw IllegalStateException("cannot replace $name (original kept as ${backupName(name)})")
                ops.delete(part)
                throw IllegalStateException("cannot replace $name")
            }
            ops.delete(part)
        }
        if (target == null) return writeNew(ops, name, src, want)
        return inPlaceWithBackup(ops, target, name, src, want)
    }

    private fun <D> writeNew(ops: Ops<D>, name: String, src: File, want: Long): D {
        val doc = ops.create(name) ?: throw IllegalStateException("cannot create $name")
        val ok = runCatching { ops.write(doc, src); ops.length(doc) == want }.getOrDefault(false)
        if (!ok) { ops.delete(doc); throw IllegalStateException("cannot write $name") }
        return doc
    }

    /** No rename: back up the original (verified), overwrite in place, verify, restore on failure. */
    private fun <D> inPlaceWithBackup(ops: Ops<D>, target: D, name: String, src: File, want: Long): D {
        val origLen = ops.length(target)
        val bak = ops.create(backupName(name)) ?: throw IllegalStateException("cannot back up $name")
        val backedUp = runCatching { ops.copy(target, bak); ops.length(bak) == origLen }.getOrDefault(false)
        if (!backedUp) { ops.delete(bak); throw IllegalStateException("cannot back up $name") }
        val ok = runCatching { ops.write(target, src); ops.length(target) == want }.getOrDefault(false)
        if (ok) { ops.delete(bak); return target }
        val restored = runCatching { ops.copy(bak, target); ops.length(target) == origLen }.getOrDefault(false)
        if (restored) ops.delete(bak) // else: keep the backup, it is the only good copy
        throw IllegalStateException(if (restored) "cannot write $name" else "cannot write $name (original kept as ${backupName(name)})")
    }
}
