package com.smartdocs.editor.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * "Continue where you left off" for any screen (user request): the screen open when the app was closed or stopped is
 * remembered (its full route with arguments); Home offers to go back to it. Leaving a screen normally (back to a main
 * tab) forgets it. The screens keep their own work (Office autosave, scan projects, the pictures job, reader page).
 */
object LastScreen {
    private const val PREFS = "last_screen"

    /** Pure: the concrete route of [pattern] ("tool/{id}?uri={uri}") filled with [args] (values encoded). */
    fun concrete(pattern: String, args: Map<String, String?>, encode: (String) -> String = { Uri.encode(it) }): String =
        Regex("""\{([^}]+)\}""").replace(pattern) { m -> encode(args[m.groupValues[1]] ?: "") }

    fun save(ctx: Context, pattern: String?, args: Bundle?) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (pattern == null) { p.edit().clear().apply(); return }
        val map = args?.keySet()?.associateWith { k -> runCatching { args.get(k)?.toString() }.getOrNull() } ?: emptyMap()
        p.edit().putString("route", concrete(pattern, map)).putString("pattern", pattern).putString("arg_uri", map["uri"]).putString("arg_id", map["id"]).apply()
    }

    fun clear(ctx: Context) { launch = null; ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }

    private var read = false
    @Volatile private var launch: Entry? = null
    /** The screen the app was left on in its PREVIOUS run, read once per process (review: returning to Home normally
     *  used to show the card for the screen just left). Offered once: taken (resumed or dismissed) it is gone. */
    @Synchronized fun atLaunch(ctx: Context): Entry? { if (!read) { read = true; launch = get(ctx) }; return launch }
    fun taken() { launch = null }

    data class Entry(val route: String, val pattern: String, val uri: String?, val id: String?)

    fun get(ctx: Context): Entry? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val r = p.getString("route", null) ?: return null
        return Entry(r, p.getString("pattern", "") ?: "", p.getString("arg_uri", null)?.takeIf { it.isNotBlank() }, p.getString("arg_id", null))
    }
}
