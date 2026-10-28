package com.smartdocs.editor.core.search

import android.content.Context

/** Stored Arabic search switches, shared by the reader and the search tools. */
object SearchPrefs {
    private const val FILE = "search_prefs"

    fun load(context: Context, matchCase: Boolean = false): NormOptions {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return NormOptions(
            ignoreDiacritics = p.getBoolean("diacritics", true),
            unifyLetters = p.getBoolean("letters", true),
            taMarbuta = p.getBoolean("ta_marbuta", false),
            unifyDigits = p.getBoolean("digits", true),
            matchCase = matchCase,
            ignoreWidth = p.getBoolean("width", false),   // fc-settings
        )
    }

    fun save(context: Context, o: NormOptions) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("diacritics", o.ignoreDiacritics)
            .putBoolean("letters", o.unifyLetters)
            .putBoolean("ta_marbuta", o.taMarbuta)
            .putBoolean("digits", o.unifyDigits)
            .putBoolean("width", o.ignoreWidth)
            .apply()
    }
}
