package com.smartdocs.editor.ui.office

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Unsaved Office edits survive the app being killed (low memory in the background, a crash, a dead battery):
 * the editor writes a recovery copy after a pause in editing and when the app leaves the screen. The user's file is
 * never touched by this; saving or discarding removes the copy, and the next open offers it while it is newer.
 */
object OfficeRecovery {
    private fun dir(ctx: Context) = File(ctx.filesDir, "office_recovery").apply { mkdirs() }

    private fun key(f: File): String = MessageDigest.getInstance("SHA-1").digest(f.absolutePath.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)

    fun fileFor(ctx: Context, f: File, ext: String): File = File(dir(ctx), key(f) + "." + ext)

    /** Pure: a copy is worth offering when it is newer than the file it belongs to and not empty. */
    fun worthOffering(copyModified: Long, copyLength: Long, fileModified: Long): Boolean = copyLength > 0 && copyModified > fileModified

    /** The recovery copy of [f] to offer, or null (a stale copy is removed). */
    fun pending(ctx: Context, f: File): File? {
        val c = dir(ctx).listFiles { x -> x.name.startsWith(key(f) + ".") && !x.name.endsWith(".tmp") }?.firstOrNull() ?: return null
        if (worthOffering(c.lastModified(), c.length(), f.lastModified())) return c
        c.delete(); return null
    }

    /** Writes the copy (temp file, then rename, so a kill mid-write never leaves a broken copy). Main thread: the model is not thread-safe. */
    fun write(ctx: Context, host: OfficeHost) {
        val out = fileFor(ctx, host.file, host.ext)
        val tmp = File(out.path + ".tmp")
        host.saveTo(tmp)
        if (tmp.length() == 0L) { tmp.delete(); return }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
    }

    fun clear(ctx: Context, f: File) {
        dir(ctx).listFiles { x -> x.name.startsWith(key(f) + ".") }?.forEach { it.delete() }
    }
}
