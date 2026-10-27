package com.smartdocs.editor.core.sync

import com.smartdocs.editor.core.sync.SyncRules.Action
import java.io.File
import java.util.Properties

/**
 * Mass-deletion guard of folder sync (audit DI-25). A pass collects its deletions instead of running them; before
 * the first one runs, [tooMany] decides whether the whole set needs the user's confirmation (an unmounted SD card or
 * an empty cloud listing looks like "everything was deleted on the other side").
 */
object DeleteGuard {
    const val MAX_DELETES = 20
    const val MAX_PERCENT = 30
    /** The percentage rule needs at least this many deletes (deleting 1 of 3 files is a normal edit). */
    const val MIN_FOR_PERCENT = 2

    /** [GUARD]: normal runs; [ALLOW]: the user confirmed the deletions; [RESTORE]: the user wants the files back. */
    enum class Policy { GUARD, ALLOW, RESTORE }

    fun tooMany(deletes: Int, snapshotSize: Int): Boolean =
        deletes > MAX_DELETES || (deletes >= MIN_FOR_PERCENT && deletes * 100L > snapshotSize * MAX_PERCENT.toLong())

    /** The collected deletes that may run now: all of them, or none when the set needs confirmation. */
    fun <T> toRun(pending: List<T>, snapshotSize: Int, policy: Policy): List<T> =
        if (policy == Policy.GUARD && tooMany(pending.size, snapshotSize)) emptyList() else pending

    fun isDelete(a: Action) = a == Action.DELETE_LOCAL || a == Action.DELETE_REMOTE

    /** [Policy.RESTORE]: a withheld delete becomes a copy back to the side that lost the file. */
    fun restored(a: Action): Action = when (a) {
        Action.DELETE_LOCAL -> Action.UPLOAD_NEW
        Action.DELETE_REMOTE -> Action.DOWNLOAD_NEW
        else -> a
    }
}

/**
 * Puts a copy of a synced SAF document into the app trash (`filesDir/trash`) before sync deletes it locally (DI-25),
 * in the format [com.smartdocs.editor.core.view5.Trash] reads: `<dir>/<id>` + `<id>.meta` (path, name, deleted, size,
 * dir). The path is left empty because a SAF document has no file path; the trash screen then restores it into
 * Documents. Pure java.io, unit tested in DeleteGuardTest.
 */
object SyncTrash {
    /** Writes the entry with [write] and checks it has [expectedSize] bytes. Returns the entry file; throws on failure. */
    fun add(dir: File, name: String, uri: String, expectedSize: Long, now: Long = System.currentTimeMillis(), write: (File) -> Unit): File {
        dir.mkdirs()
        val id = "t${now}_${(name.hashCode().toLong() and 0xFFFFFF).toString(16)}_s${System.nanoTime() % 100000}"
        val target = File(dir, id)
        val ok = runCatching { write(target); target.isFile && target.length() == expectedSize }.getOrDefault(false)
        if (!ok) { target.delete(); throw IllegalStateException("cannot move $name to the trash") }
        val p = Properties().apply {
            setProperty("path", ""); setProperty("name", name); setProperty("deleted", now.toString())
            setProperty("size", target.length().toString()); setProperty("dir", "false"); setProperty("uri", uri)
        }
        File(dir, "$id.meta").outputStream().use { p.store(it, null) }
        return target
    }

    /** Undo of [add] when the document could not be deleted after all. */
    fun remove(entry: File) { File(entry.parentFile, entry.name + ".meta").delete(); entry.delete() }
}
