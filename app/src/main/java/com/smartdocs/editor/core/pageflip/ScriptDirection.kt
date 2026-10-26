package com.smartdocs.editor.core.pageflip

/** Reading direction of a document from its text (Arabic documents default to RTL). */
object ScriptDirection {
    /** True when [c] is a strong right-to-left letter (Hebrew, Arabic, Syriac, Thaana, NKo, presentation forms). */
    fun isRtlChar(c: Char): Boolean {
        val x = c.code
        return x in 0x0590..0x08FF || x in 0xFB1D..0xFDFF || x in 0xFE70..0xFEFF
    }

    /**
     * true = RTL, false = LTR, null = not enough letters to decide (fewer than [minLetters]).
     * RTL wins when at least 40 % of the letters are RTL (Arabic documents often contain Latin words and numbers).
     */
    fun detect(text: String, minLetters: Int = 20): Boolean? {
        var rtl = 0; var ltr = 0
        for (c in text) {
            if (isRtlChar(c)) { if (Character.isLetter(c)) rtl++ }
            else if (Character.isLetter(c)) ltr++
        }
        val total = rtl + ltr
        if (total < minLetters) return null
        return rtl * 10 >= total * 4
    }
}
