package com.smartdocs.editor.core.pdf

import java.io.File

/**
 * tab-view-core E-03 / E-20: the password a document is open with in the reader, kept in memory only (never written
 * to disk) so the tools opened on it (form designer, Edit tools screens) do not ask again. Set when the reader opens
 * the file, removed when the reader closes it.
 */
object OpenPasswords {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun put(file: File, password: String?) { if (password.isNullOrEmpty()) map.remove(file.absolutePath) else map[file.absolutePath] = password }
    fun get(file: File?): String? = file?.let { map[it.absolutePath] }
    fun remove(file: File?) { file?.let { map.remove(it.absolutePath) } }
}
