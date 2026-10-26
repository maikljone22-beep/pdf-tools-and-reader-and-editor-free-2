package com.smartdocs.editor.core.view5

import java.io.File
import java.util.Properties

/**
 * 30-day trash bin. A deleted file is moved into [dir] under a unique name, with a `.meta` sidecar
 * (java.util.Properties: original path, deletion time, size). Pure java.io so the logic is unit tested;
 * the Android entry points pass `File(context.filesDir, "trash")`.
 */
class Trash(val dir: File, private val days: Int = DAYS) {

    data class Entry(val id: String, val file: File, val originalPath: String, val name: String, val deletedAt: Long, val size: Long, val isDir: Boolean) {
        fun daysLeft(now: Long, days: Int = DAYS): Int = (((deletedAt + days * DAY_MS) - now + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(0)
    }

    private fun metaOf(id: String) = File(dir, "$id.meta")

    /** Move [f] (file or folder) into the trash. Returns the entry, or null when the move failed. */
    fun moveToTrash(f: File, now: Long = System.currentTimeMillis()): Entry? {
        if (!f.exists()) return null
        dir.mkdirs()
        val id = "t${now}_${(f.name.hashCode().toLong() and 0xFFFFFF).toString(16)}_${System.nanoTime() % 100000}"
        val target = File(dir, id)
        val size = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
        val isDir = f.isDirectory
        val moved = f.renameTo(target) || runCatching { f.copyRecursively(target, overwrite = true) && f.deleteRecursively() }.getOrDefault(false)
        if (!moved) { target.deleteRecursively(); return null }
        val p = Properties().apply {
            setProperty("path", f.absolutePath); setProperty("name", f.name); setProperty("deleted", now.toString())
            setProperty("size", size.toString()); setProperty("dir", isDir.toString())
        }
        metaOf(id).outputStream().use { p.store(it, null) }
        return Entry(id, target, f.absolutePath, f.name, now, size, isDir)
    }

    /**
     * Put a COPY of [f] into the trash, leaving [f] in place: the safety copy kept before a tool overwrites a document
     * (audit UX-02). Returns the entry, or null when the copy failed (the caller must then not overwrite [f]).
     */
    fun copyToTrash(f: File, now: Long = System.currentTimeMillis()): Entry? {
        if (!f.isFile) return null
        dir.mkdirs()
        val id = "t${now}_${(f.name.hashCode().toLong() and 0xFFFFFF).toString(16)}_${System.nanoTime() % 100000}"
        val target = File(dir, id)
        val ok = runCatching { f.copyTo(target, overwrite = true); target.length() == f.length() }.getOrDefault(false)
        if (!ok) { target.delete(); return null }
        val p = Properties().apply {
            setProperty("path", f.absolutePath); setProperty("name", f.name); setProperty("deleted", now.toString())
            setProperty("size", f.length().toString()); setProperty("dir", "false")
        }
        if (runCatching { metaOf(id).outputStream().use { p.store(it, null) } }.isFailure) { target.delete(); metaOf(id).delete(); return null }
        return Entry(id, target, f.absolutePath, f.name, now, f.length(), false)
    }

    fun list(): List<Entry> = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".meta") }.mapNotNull { m ->
        runCatching {
            val p = Properties().apply { m.inputStream().use { load(it) } }
            val id = m.name.removeSuffix(".meta")
            val f = File(dir, id)
            if (!f.exists()) { m.delete(); return@runCatching null }
            Entry(id, f, p.getProperty("path", ""), p.getProperty("name", id), p.getProperty("deleted", "0").toLong(),
                p.getProperty("size", "0").toLong(), p.getProperty("dir", "false").toBoolean())
        }.getOrNull()
    }.sortedByDescending { it.deletedAt }

    /** Put an entry back where it was (a free name is chosen when that path is taken). Returns the restored file. */
    fun restore(e: Entry, fallbackDir: File? = null): File? {
        val orig = File(e.originalPath)
        val parent = orig.parentFile?.takeIf { it.exists() || it.mkdirs() } ?: fallbackDir ?: return null
        val target = freeName(parent, e.name)
        val ok = e.file.renameTo(target) || runCatching { e.file.copyRecursively(target, overwrite = false) && e.file.deleteRecursively() }.getOrDefault(false)
        if (!ok) return null
        metaOf(e.id).delete()
        return target
    }

    fun deleteForever(e: Entry) { e.file.deleteRecursively(); metaOf(e.id).delete() }

    fun empty() = list().forEach { deleteForever(it) }

    /** Delete entries older than the retention period. Returns how many were removed. */
    fun purgeExpired(now: Long = System.currentTimeMillis()): Int {
        val old = list().filter { isExpired(it.deletedAt, now, days) }
        old.forEach { deleteForever(it) }
        return old.size
    }

    fun totalSize(): Long = list().sumOf { it.size }

    companion object {
        const val DAYS = 30
        const val DAY_MS = 24L * 60 * 60 * 1000

        fun isExpired(deletedAt: Long, now: Long, days: Int = DAYS): Boolean = now - deletedAt >= days * DAY_MS

        /** "name.pdf" → "name (2).pdf" … when taken. */
        fun freeName(dir: File, name: String): File {
            var f = File(dir, name); if (!f.exists()) return f
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val base = name.substring(0, dot); val ext = name.substring(dot)
            var i = 2
            while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
            return f
        }
    }
}
