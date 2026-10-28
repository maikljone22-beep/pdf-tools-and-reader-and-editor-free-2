package com.smartdocs.editor.core.fillsign2

import android.content.Context

/** Fill & Sign preferences of nitro-fillsign2 (not part of the general settings store). */
object FillSignPrefs {
    private const val FILE = "fillsign2_prefs"
    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * "Flatten signatures when saving" (Foxit "PDF Sign: flatten on apply"): placed QuickSign signatures are baked into
     * the page content at every save, so they can no longer be moved or deleted. Never applied to a digitally signed
     * file (that would break its signatures).
     */
    fun flattenOnSave(c: Context): Boolean = p(c).getBoolean("flatten_on_save", false)
    fun setFlattenOnSave(c: Context, on: Boolean) = p(c).edit().putBoolean("flatten_on_save", on).apply()

    /** Tab audit D26: the Date tool's pattern (one of [FsDate.PATTERNS]). */
    fun datePattern(c: Context): String = FsDate.valid(p(c).getString("date_pattern", null))
    fun setDatePattern(c: Context, pattern: String) = p(c).edit().putString("date_pattern", FsDate.valid(pattern)).apply()
}
