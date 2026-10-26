package com.smartdocs.editor.core.tools5

/**
 * Plain-Kotlin text helpers shared by the tools5 parsers (no Android imports, unit-tested).
 * Arabic letters are referred to by code point so no raw Arabic or invisible characters live in the source.
 */
object TextNorm {

    /** Arabic-Indic (U+0660..0669) and Extended/Persian (U+06F0..06F9) digits → ASCII; Arabic decimal/thousands separators → '.' / ','. */
    fun asciiDigits(s: String): String {
        if (s.none { it.code in 0x0660..0x066C || it.code in 0x06F0..0x06F9 }) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            val k = c.code
            when {
                k in 0x0660..0x0669 -> sb.append('0' + (k - 0x0660))
                k in 0x06F0..0x06F9 -> sb.append('0' + (k - 0x06F0))
                k == 0x066B -> sb.append('.')
                k == 0x066C -> sb.append(',')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun isArabicChar(c: Char): Boolean {
        val k = c.code
        return k in 0x0600..0x06FF || k in 0x0750..0x077F || k in 0x08A0..0x08FF || k in 0xFB50..0xFDFF || k in 0xFE70..0xFEFF
    }

    fun hasArabic(s: String): Boolean = s.any { isArabicChar(it) }

    /** Harakat, Quranic marks, superscript alef and tatweel. */
    fun isArabicMark(c: Char): Boolean {
        val k = c.code
        return k in 0x064B..0x065F || k == 0x0670 || k in 0x06D6..0x06ED || k == 0x0640
    }

    /** Bidi / zero-width controls that OCR and PDF text often carry. */
    fun isInvisible(c: Char): Boolean {
        val k = c.code
        return k in 0x200B..0x200F || k in 0x202A..0x202E || k in 0x2066..0x2069 || k == 0xFEFF || k == 0x00AD
    }

    /**
     * Comparison key: lower case, ASCII digits, no marks / tatweel / invisible characters, alef variants → bare alef,
     * taa marbuta → haa, alef maqsura → yaa, hamza carriers → base letter.
     */
    fun arabicKey(s: String): String {
        val sb = StringBuilder(s.length)
        for (c0 in asciiDigits(s)) {
            if (isArabicMark(c0) || isInvisible(c0)) continue
            val c = when (c0.code) {
                0x0622, 0x0623, 0x0625, 0x0671, 0x0672, 0x0673 -> 'ا'
                0x0629 -> 'ه'
                0x0649 -> 'ي'
                0x0624 -> 'و'
                0x0626 -> 'ي'
                0x06CC -> 'ي'
                0x06A9 -> 'ك'
                else -> c0
            }
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /** Strip leading/trailing punctuation (keeps letters, digits, marks inside). */
    fun trimPunct(s: String): String {
        var a = 0; var b = s.length
        while (a < b && !s[a].isLetterOrDigit()) a++
        while (b > a && !s[b - 1].isLetterOrDigit() && !isArabicMark(s[b - 1])) b--
        return s.substring(a, b)
    }

    /** Characters not allowed in file names on Android / FAT / Windows. */
    fun safeFileName(s: String, max: Int = 80): String {
        val cleaned = s.filterNot { isInvisible(it) || it.code < 32 }
            .replace(Regex("""[\\/:*?"<>|#%]"""), " ")
            .trim().replace(Regex("""\s+"""), "_")
            .trim('_', '.', '-')
        return if (cleaned.length <= max) cleaned else cleaned.substring(0, max).trimEnd('_', '.', '-')
    }
}
