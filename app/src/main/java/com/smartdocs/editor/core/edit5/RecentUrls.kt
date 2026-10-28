package com.smartdocs.editor.core.edit5

import android.content.Context

/**
 * fix/links-bookmarks (Foxit §15 "recently used URLs"): the last web addresses typed into the link and action
 * editors, newest first, kept on this device.
 */
object RecentUrls {
    const val MAX = 12
    private const val PREFS = "lbm_recent_urls"
    private const val SEP = "\n"

    /** [list] with [url] moved / added to the front, duplicates (ignoring case and a trailing slash) dropped. Pure. */
    fun push(list: List<String>, url: String, max: Int = MAX): List<String> {
        val u = url.trim()
        if (u.isEmpty() || u.equals("https://", true) || u.equals("http://", true)) return list
        fun k(s: String) = s.trim().trimEnd('/').lowercase()
        return (listOf(u) + list.filter { k(it) != k(u) }).take(max)
    }

    /** Entries of [list] that contain [typed] (all when it is blank or only a scheme). Pure. */
    fun matching(list: List<String>, typed: String): List<String> {
        val t = typed.trim().removePrefix("https://").removePrefix("http://")
        return if (t.isEmpty()) list else list.filter { it.contains(t, ignoreCase = true) && !it.equals(typed.trim(), true) }
    }

    fun load(c: Context): List<String> = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("l", null)?.split(SEP)?.filter { it.isNotBlank() } ?: emptyList()

    fun add(c: Context, url: String) {
        if (!url.trim().let { it.contains("://") || it.contains('.') } || url.startsWith("mailto:", true)) return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("l", push(load(c), url).joinToString(SEP)).apply()
    }

    fun clear(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("l").apply()
}
