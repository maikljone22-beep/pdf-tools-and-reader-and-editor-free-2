package com.smartdocs.editor.core.pdf

import java.io.File

/**
 * The open password of documents the reader has unlocked in this process, so tool screens that get only the file
 * (digital sign / verify, audit D4; form designer and Edit tools screens, tab-view-core E-03 / E-20) can open it too.
 * Memory only: never written to disk or to saved UI state. Removed when the reader closes the document.
 */
object OpenPasswords {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun key(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.path)

    fun put(file: File, password: String?) { if (password.isNullOrEmpty()) map.remove(key(file)) else map[key(file)] = password }

    fun get(file: File?): String? = file?.let { map[key(it)] }

    fun remove(file: File?) { file?.let { map.remove(key(it)) } }
}
