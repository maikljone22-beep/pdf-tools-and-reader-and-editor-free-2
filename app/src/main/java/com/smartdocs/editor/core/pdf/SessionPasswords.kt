package com.smartdocs.editor.core.pdf

import java.io.File

/**
 * audit 5 T-09 / C-4 / C-4b / G-02 / E-13: the password the reader opened a document with, handed to the tool
 * screens it opens (`Routes.tool(id, file)`), so they do not ask again. In memory only (never written to disk),
 * keyed by the canonical path, and short-lived: an entry expires after [TTL_MS] without use and is removed when the
 * reader closes the document ([forget]).
 */
object SessionPasswords {
    const val TTL_MS = 30 * 60 * 1000L
    private class Entry(val password: String, @Volatile var used: Long)
    private val map = HashMap<String, Entry>()

    private fun key(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    /** Remember that [f] opens with [password] (null / empty forgets it). */
    @Synchronized fun put(f: File, password: String?, now: Long = System.currentTimeMillis()) {
        prune(now)
        if (password.isNullOrEmpty()) map.remove(key(f)) else map[key(f)] = Entry(password, now)
    }

    /** The password the reader used for [f], or null. */
    @Synchronized fun get(f: File?, now: Long = System.currentTimeMillis()): String? {
        if (f == null) return null
        prune(now)
        val e = map[key(f)] ?: return null
        e.used = now
        return e.password
    }

    @Synchronized fun forget(f: File) { map.remove(key(f)) }

    @Synchronized fun clear() { map.clear() }

    private fun prune(now: Long) { map.entries.removeAll { now - it.value.used > TTL_MS } }
}
