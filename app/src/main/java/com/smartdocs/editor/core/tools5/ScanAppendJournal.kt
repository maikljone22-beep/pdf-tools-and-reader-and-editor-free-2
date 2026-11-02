package com.smartdocs.editor.core.tools5

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * "Add scanned pages to an existing PDF" rewrites the user's own file ("wt" empties it first). If the app dies in the
 * middle, the file would stay cut short. Before the write the original is copied here with the size the new file must
 * have; [recover] (next time the scanner opens) puts the original back when the file is not that size, then forgets it.
 */
object ScanAppendJournal {
    private fun dir(ctx: Context) = File(ctx.filesDir, "scan_append_journal").apply { mkdirs() }

    /** Pure decision: restore when the file cannot be measured or is not the size of a finished write. */
    fun needsRestore(actual: Long?, expected: Long): Boolean = actual == null || actual != expected

    /** Copies [original] aside; returns the entry to pass to [finish]. */
    fun begin(ctx: Context, target: Uri, original: File, expected: Long): File {
        val id = System.currentTimeMillis().toString()
        val bak = File(dir(ctx), "$id.pdf"); original.copyTo(bak, true)
        val meta = File(dir(ctx), "$id.meta")
        val tmp = File(dir(ctx), "$id.meta.tmp"); tmp.writeText(target.toString() + "\n" + expected)
        if (!tmp.renameTo(meta)) { tmp.copyTo(meta, true); tmp.delete() }
        return meta
    }

    fun finish(meta: File) { File(meta.parentFile, meta.name.removeSuffix(".meta") + ".pdf").delete(); meta.delete() }

    /** Returns how many interrupted writes were rolled back. */
    fun recover(ctx: Context): Int {
        var n = 0
        dir(ctx).listFiles()?.forEach { f ->
            if (f.name.endsWith(".meta.tmp")) { f.delete(); return@forEach }
            if (!f.name.endsWith(".meta")) return@forEach
            val bak = File(f.parentFile, f.name.removeSuffix(".meta") + ".pdf")
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            val uri = lines.getOrNull(0)?.let(Uri::parse); val expected = lines.getOrNull(1)?.toLongOrNull()
            if (uri != null && expected != null && bak.exists()) {
                val actual = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
                if (needsRestore(actual, expected)) {
                    val ok = runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { o -> bak.inputStream().use { it.copyTo(o) } } }.isSuccess
                    // keep the backup for the next try, for a week (the file may be gone or its permission revoked)
                    if (ok) n++ else if (System.currentTimeMillis() - f.lastModified() < 7L * 86_400_000) return@forEach
                }
            }
            bak.delete(); f.delete()
        }
        // orphan backups without their entry
        dir(ctx).listFiles { x -> x.name.endsWith(".pdf") && !File(x.parentFile, x.name.removeSuffix(".pdf") + ".meta").exists() }?.forEach { it.delete() }
        return n
    }
}
