package com.smartdocs.editor.core.files

import java.io.File

/** Rename inside the documents folder without ever replacing another file (audit DI-04). Pure java.io, unit tested. */
object SafeRename {
    sealed interface Result {
        data class Ok(val file: File) : Result
        data object Blank : Result
        /** "/", "\", ".." or other characters a file name cannot hold. */
        data object InvalidName : Result
        data class Exists(val other: File) : Result
        data object Failed : Result
    }

    private val bad = Regex("""[\\/:*?"<>|\u0000]""")

    /** The file [f] would become when renamed to [input] (extension kept for files), or the reason it cannot. */
    fun target(f: File, input: String): Result {
        val name = input.trim()
        if (name.isEmpty()) return Result.Blank
        if (bad.containsMatchIn(name) || name == "." || name == ".." || name.startsWith("..")) return Result.InvalidName
        val parent = f.parentFile ?: return Result.Failed
        val full = if (f.isDirectory || f.extension.isEmpty()) name else "$name.${f.extension}"
        val target = File(parent, full)
        if (target.name == f.name) return Result.Ok(f)
        // case-insensitive: "Report.pdf" and "report.pdf" are the same file on FAT/sdcardfs volumes
        val clash = (parent.listFiles() ?: emptyArray()).firstOrNull { it.name.equals(full, ignoreCase = true) && it.absolutePath != f.absolutePath }
        if (clash != null) return Result.Exists(clash)
        return Result.Ok(target)
    }

    /** Rename [f] to [input]; never overwrites. A case-only change goes through a temporary name. */
    fun rename(f: File, input: String): Result {
        val r = target(f, input)
        // compare paths, not Files: File.equals ignores case on case-insensitive filesystems
        if (r !is Result.Ok || r.file.path == f.path) return r
        val ok = if (r.file.name.equals(f.name, ignoreCase = true)) {
            val tmp = File(f.parentFile, ".${f.name}.rename")
            f.renameTo(tmp) && (tmp.renameTo(r.file) || tmp.renameTo(f).let { false })
        } else !r.file.exists() && f.renameTo(r.file)
        return if (ok) r else Result.Failed
    }
}
