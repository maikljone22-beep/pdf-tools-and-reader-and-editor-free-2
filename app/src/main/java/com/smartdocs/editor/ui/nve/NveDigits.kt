package com.smartdocs.editor.ui.nve

import android.content.Context

/**
 * Digit shapes of typed text (edit mode, annotation text): prefs "nve_edit", key "digits".
 * AUTO: Arabic-Indic digits when the text is Arabic (Arabic letters, no Latin letters), unchanged otherwise.
 */
object NveDigits {
    enum class Mode { KEEP, AUTO, LATIN, ARABIC_INDIC, EASTERN_ARABIC_INDIC }

    const val PREFS = "nve_edit"
    private const val KEY = "digits"

    /**
     * The typing digits setting. main's nav-prefs value (NpfPrefs.digits: keep / arabic / latin) is the single source;
     * "keep" may be refined here with the extra AUTO / Eastern (Persian) modes.
     */
    fun mode(ctx: Context): Mode = when (com.smartdocs.editor.core.data.NpfPrefs.digits) {
        "arabic" -> Mode.ARABIC_INDIC
        "latin" -> Mode.LATIN
        else -> ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { n -> Mode.entries.firstOrNull { it.name == n && (it == Mode.AUTO || it == Mode.EASTERN_ARABIC_INDIC) } } ?: Mode.KEEP
    }

    fun setMode(ctx: Context, m: Mode) {
        val P = com.smartdocs.editor.core.data.NpfPrefs
        P.setString(P.K.DIGITS, when (m) { Mode.ARABIC_INDIC -> "arabic"; Mode.LATIN -> "latin"; else -> "keep" })
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply()
    }

    /** [text] with its digits converted for the saved mode. */
    fun apply(ctx: Context, text: String): String = convert(text, mode(ctx))

    /** Pure conversion between 0-9, U+0660-0669 and U+06F0-06F9. */
    fun convert(text: String, mode: Mode): String {
        val target = when (mode) {
            Mode.KEEP -> return text
            Mode.LATIN -> '0'
            Mode.ARABIC_INDIC -> '\u0660'
            Mode.EASTERN_ARABIC_INDIC -> '\u06F0'
            Mode.AUTO -> {
                val arabic = text.any { it in '\u0600'..'\u06FF' && it.isLetter() }
                val latin = text.any { it.isLetter() && it.code < 0x0250 }
                if (arabic && !latin) '\u0660' else return text
            }
        }
        val sb = StringBuilder(text.length)
        for (c in text) {
            val d = when (c) { in '0'..'9' -> c - '0'; in '\u0660'..'\u0669' -> c - '\u0660'; in '\u06F0'..'\u06F9' -> c - '\u06F0'; else -> -1 }
            sb.append(if (d >= 0) target + d else c)
        }
        return sb.toString()
    }
}
