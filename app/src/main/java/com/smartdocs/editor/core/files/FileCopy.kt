package com.smartdocs.editor.core.files

import java.io.File

/** Copy files / folders into a destination folder (Files tab "copy to"), plus unzip target naming. */
object FileCopy {

    /** "name", "name (2)", "name (3)"… for folders; "base.ext", "base (2).ext"… for files — first free one in [dir]. */
    fun freeName(dir: File, name: String, isDirectory: Boolean, exists: (File) -> Boolean = { it.exists() }): File {
        val first = File(dir, name)
        if (!exists(first)) return first
        val base = if (isDirectory || !name.contains('.')) name else name.substringBeforeLast('.')
        val ext = if (isDirectory || !name.contains('.')) "" else "." + name.substringAfterLast('.')
        var n = 2
        while (true) {
            val f = File(dir, "$base ($n)$ext")
            if (!exists(f)) return f
            n++
        }
    }

    /** True when [dest] is [src] itself or inside it (a folder cannot be copied into itself). */
    fun isInside(src: File, dest: File): Boolean {
        val s = src.canonicalFile.absolutePath
        val d = dest.canonicalFile.absolutePath
        return d == s || d.startsWith(s + File.separator)
    }

    data class Result(val copied: List<File>, val skipped: List<File>)

    /** Copies every item of [items] into [destDir] under a free name. Folders inside themselves are skipped. */
    fun copyInto(items: List<File>, destDir: File): Result {
        destDir.mkdirs()
        val copied = ArrayList<File>(); val skipped = ArrayList<File>()
        for (f in items) {
            if (!f.exists() || (f.isDirectory && isInside(f, destDir))) { skipped += f; continue }
            val target = freeName(destDir, f.name, f.isDirectory)
            val ok = runCatching { if (f.isDirectory) f.copyRecursively(target, overwrite = false) else { f.copyTo(target, overwrite = false); true } }.getOrDefault(false)
            if (ok) copied += target else skipped += f
        }
        return Result(copied, skipped)
    }

    /** Folder to extract [zip] into: next to it, named after it, made unique. */
    fun unzipTarget(zip: File): File = freeName(zip.parentFile ?: File("."), zip.nameWithoutExtension, isDirectory = true)
}
