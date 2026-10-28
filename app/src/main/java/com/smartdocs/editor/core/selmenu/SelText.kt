package com.smartdocs.editor.core.selmenu

/**
 * Pure text helpers of the selection menu (feature/selection-menus): Arabic-specific copy variants and digit
 * conversion. No Android dependencies, so the JVM tests cover them.
 */
object SelText {
    private const val TATWEEL = '\u0640'

    /** Arabic-Indic (U+0660..) or extended Arabic-Indic / Persian (U+06F0..) digit → its value, else -1. */
    fun arabicDigitValue(c: Char): Int = when (c) {
        in '\u0660'..'\u0669' -> c - '\u0660'
        in '\u06F0'..'\u06F9' -> c - '\u06F0'
        else -> -1
    }

    /** Arabic harakat, Quranic marks and superscript alef (not letters, not tatweel). */
    fun isDiacritic(c: Char): Boolean {
        val u = c.code
        return u in 0x064B..0x065F || u == 0x0670 || u in 0x0610..0x061A || u in 0x06D6..0x06DC ||
            u in 0x06DF..0x06E4 || u == 0x06E7 || u == 0x06E8 || u in 0x06EA..0x06ED
    }

    private fun isInvisible(c: Char): Boolean {
        val u = c.code
        return u in 0x200B..0x200F || u == 0x061C || u in 0x202A..0x202E || u in 0x2066..0x2069 || u == 0xFEFF || u == 0x00AD
    }

    fun hasArabic(s: String): Boolean = s.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F || it.code in 0xFB50..0xFEFF }
    fun hasDiacritics(s: String): Boolean = s.any { isDiacritic(it) || it == TATWEEL }
    fun hasArabicDigits(s: String): Boolean = s.any { arabicDigitValue(it) >= 0 }
    fun hasLatinDigits(s: String): Boolean = s.any { it in '0'..'9' }

    /** Text without harakat and tatweel ("مُحَمَّـد" → "محمد"). */
    fun stripDiacritics(s: String): String = buildString(s.length) { s.forEach { if (!isDiacritic(it) && it != TATWEEL) append(it) } }

    /**
     * "Normalised" copy: no diacritics / tatweel / invisible bidi controls, the hamza-carrying alef forms written as
     * a plain alef, and runs of spaces collapsed. Taa marbuta and alef maqsura are kept (changing them changes spelling).
     */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (isDiacritic(c) || c == TATWEEL || isInvisible(c)) continue
            sb.append(when (c) { '\u0623', '\u0625', '\u0622', '\u0671' -> '\u0627'; '\u00A0' -> ' '; else -> c })
        }
        return sb.toString().lines().joinToString("\n") { it.replace(Regex(" {2,}"), " ").trim() }
    }

    /** Arabic-Indic digits (both blocks) and the Arabic decimal / thousands separators → ASCII. Length is kept. */
    fun latinDigits(s: String): String = buildString(s.length) {
        for (c in s) {
            val v = arabicDigitValue(c)
            append(when {
                v >= 0 -> '0' + v
                c == '\u066B' -> '.'
                c == '\u066C' -> ','
                else -> c
            })
        }
    }

    /** ASCII digits → Arabic-Indic digits (U+0660..). */
    fun arabicDigits(s: String): String = buildString(s.length) { s.forEach { append(if (it in '0'..'9') '\u0660' + (it - '0') else it) } }

    /**
     * One paragraph per blank-line block: soft line breaks inside a paragraph become spaces and a word hyphenated
     * across a line end is joined ("exam-\nple" → "example"). Used by "Copy as plain text".
     */
    fun joinLines(s: String): String {
        val paras = s.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        return paras.joinToString("\n\n") { p ->
            p.replace(Regex("(\\p{L})-\n(\\p{Ll})"), "$1$2").replace(Regex("\\s*\n\\s*"), " ").trim()
        }.trim()
    }

    /**
     * The sentence of [text] around [index] (for "Select sentence" after a long-press on a word): bounded by . ! ?
     * the Arabic ؟ and ۔, a line break followed by an empty line, or the text ends. Returns an inclusive range.
     */
    fun sentenceAt(text: String, index: Int): IntRange {
        if (text.isEmpty()) return 0..0
        val i = index.coerceIn(0, text.length - 1)
        fun isEnd(k: Int): Boolean {
            val c = text[k]
            if (c == '.' || c == '!' || c == '?' || c == '\u061F' || c == '\u06D4') return k + 1 >= text.length || text[k + 1].isWhitespace()
            return c == '\n' && k + 1 < text.length && (text[k + 1] == '\n' || text[k + 1] == '\r')
        }
        var s = i
        while (s > 0 && !isEnd(s - 1)) s--
        var e = i
        while (e < text.length - 1 && !isEnd(e)) e++
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e].isWhitespace()) e--
        return s..e
    }
}
