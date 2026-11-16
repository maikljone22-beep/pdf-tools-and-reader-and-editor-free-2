package com.smartdocs.editor.core.files

import java.io.File
import java.security.MessageDigest

/** Persistent presentation metadata, separate from both document bytes and the disposable preview cache. */
class ThumbnailChoices(private val root: File) {
    init { check(root.isDirectory || root.mkdirs()) }
    private fun key(file: File) = MessageDigest.getInstance("SHA-256").digest(file.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun marker(file: File) = File(root, key(file) + ".choice")
    /** null = automatic; empty = hidden; otherwise the selected image. */
    fun choice(file: File): String? {
        val entry = marker(file)
        if (!entry.isFile) return null
        val name = entry.readText()
        if (name == "hidden") return ""
        if (name != File(name).name || name.contains('\\')) return null
        return File(root, name).takeIf { it.isFile }?.absolutePath
    }
    @Synchronized private fun commit(file: File, name: String?) {
        val old = choice(file)?.takeIf { it.isNotEmpty() }?.let(::File)
        val target = marker(file)
        if (name == null) { if (target.exists()) check(target.delete()) }
        else {
            val temp = File.createTempFile("choice", ".tmp", root)
            try { temp.writeText(name); check(temp.renameTo(target)) { "Could not save thumbnail choice" } }
            finally { temp.delete() }
        }
        old?.takeIf { it.name != name }?.delete()
    }
    fun replace(file: File, image: File) {
        require(image.isFile && image.parentFile?.canonicalFile == root.canonicalFile)
        commit(file, image.name)
    }
    fun hide(file: File) = commit(file, "hidden")
    fun reset(file: File) = commit(file, null)
    @Synchronized fun relocate(from: File, to: File) {
        val old = marker(from)
        if (old.isFile && from.absolutePath != to.absolutePath) {
            commit(to, old.readText())
            check(old.delete())
        }
    }
}
