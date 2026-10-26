package com.smartdocs.editor.core.search

import java.text.Normalizer

/** What [ArabicNormalizer.fold] ignores. Defaults match what Arabic readers expect from a search box. */
data class NormOptions(
    val ignoreDiacritics: Boolean = true,   // tashkeel, Quranic marks, Latin accents
    val unifyLetters: Boolean = true,       // أإآٱ→ا  ؤ→و  ئ/ى/ی→ي
    val taMarbuta: Boolean = false,         // ة→ه
    val unifyDigits: Boolean = true,        // ٠١٢ / ۰۱۲ → 012
    val matchCase: Boolean = false,
)

/** Folded text plus, for every folded char, the index of the source char it came from. */
class Folded(val text: String, val map: IntArray)

/**
 * Folds text for matching: "مُحَمَّد" and "محمد" fold to the same string, so do "أحمد" and "احمد".
 * Whitespace runs (including PDFium's line breaks) collapse to one space so phrases match across lines.
 */
object ArabicNormalizer {

    fun fold(src: String, o: NormOptions = NormOptions()): Folded {
        val sb = StringBuilder(src.length)
        var map = IntArray(src.length + 16)
        var lastSpace = true
        fun mark(i: Int) {
            if (sb.length >= map.size) map = map.copyOf(map.size * 2)
            map[sb.length] = i
        }
        fun put(c: Char, i: Int) {
            if (c.isWhitespace() || c.code == 0x00A0) {
                if (lastSpace) return
                lastSpace = true
                mark(i)
                sb.append(' ')
                return
            }
            lastSpace = false
            mark(i)
            sb.append(if (o.matchCase) c else c.lowercaseChar())
        }
        for (i in src.indices) {
            val c = src[i]
            if (isInvisible(c) || c.code == 0x0640) continue
            if (o.ignoreDiacritics && isArabicMark(c)) continue
            // presentation forms and ligatures (ﻻ, ﷲ, ﺃ…) → base letters
            if (c.code in 0xFB50..0xFDFF || c.code in 0xFE70..0xFEFF) {
                val n = Normalizer.normalize(c.toString(), Normalizer.Form.NFKC)
                for (ch in n) if (!(o.ignoreDiacritics && isArabicMark(ch))) put(mapLetter(ch, o), i)
                continue
            }
            if (o.ignoreDiacritics && c.code in 0x00C0..0x024F) {
                val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull { it.code !in 0x0300..0x036F }
                put(base ?: c, i); continue
            }
            if (o.ignoreDiacritics && c.code in 0x0300..0x036F) continue
            put(mapLetter(c, o), i)
        }
        // drop a trailing space so "word " and "word" fold alike
        var len = sb.length
        if (len > 0 && sb[len - 1] == ' ') { sb.setLength(len - 1); len-- }
        return Folded(sb.toString(), map.copyOf(len))
    }

    fun foldQuery(q: String, o: NormOptions) = fold(q.trim(), o).text

    private fun mapLetter(c: Char, o: NormOptions): Char = when {
        o.unifyLetters && (c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ' || c == 'ٲ' || c == 'ٳ') -> 'ا'
        o.unifyLetters && c == 'ؤ' -> 'و'
        o.unifyLetters && (c == 'ئ' || c == 'ى' || c == 'ی' || c == 'ې') -> 'ي'
        o.unifyLetters && c == 'ک' -> 'ك'
        o.taMarbuta && c == 'ة' -> 'ه'
        o.unifyDigits && c.code in 0x0660..0x0669 -> '0' + (c.code - 0x0660)
        o.unifyDigits && c.code in 0x06F0..0x06F9 -> '0' + (c.code - 0x06F0)
        else -> c
    }

    /** Tashkeel, superscript alef and Quranic annotation marks. */
    fun isArabicMark(c: Char): Boolean {
        val u = c.code
        return u in 0x064B..0x065F || u == 0x0670 || u in 0x0610..0x061A || u in 0x06D6..0x06DC || u in 0x06DF..0x06E8 || u in 0x06EA..0x06ED
    }

    private fun isInvisible(c: Char): Boolean {
        val u = c.code
        return u in 0x200B..0x200F || u in 0x202A..0x202E || u in 0x2066..0x2069 || u == 0x00AD || u == 0xFEFF || u == 0x061C
    }

    fun hasArabic(s: String) = s.any { it.code in 0x0600..0x06FF || it.code in 0xFB50..0xFEFF }

    // ---------------- additions (search5): words, light stemming, regex patterns, digits ----------------

    private val WORD = Regex("[\\p{L}\\p{Nd}\\p{M}]+")

    /** Word ranges of [s] (letters, digits and combining marks). */
    fun wordRanges(s: String): List<IntRange> = WORD.findAll(s).map { it.range }.toList()

    /** Words of [s]. */
    fun words(s: String): List<String> = WORD.findAll(s).map { it.value }.toList()

    /** Arabic-Indic / Persian digits → Latin, one char for one char (indexes stay valid). */
    fun latinDigits(s: String): String {
        if (s.none { it.code in 0x0660..0x0669 || it.code in 0x06F0..0x06F9 }) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(when (c.code) { in 0x0660..0x0669 -> '0' + (c.code - 0x0660); in 0x06F0..0x06F9 -> '0' + (c.code - 0x06F0); else -> c })
        return sb.toString()
    }

    /**
     * Folds the Arabic letters of a regular expression like [fold] does for text, but keeps everything else
     * (escapes such as `\W`, character classes, case) untouched. Case-insensitivity is a regex flag instead.
     */
    fun foldPattern(p: String, o: NormOptions): String {
        val sb = StringBuilder(p.length)
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '\\' && i + 1 < p.length) { sb.append(c).append(p[i + 1]); i += 2; continue }
            if (isInvisible(c) || c.code == 0x0640) { i++; continue }
            if (o.ignoreDiacritics && isArabicMark(c)) { i++; continue }
            if (c.code in 0x0600..0x06FF) sb.append(mapLetter(c, o)) else sb.append(c)
            i++
        }
        return sb.toString()
    }

    private val AR_PREFIXES = listOf("وال", "بال", "كال", "فال", "لل", "ال")
    private val AR_SUFFIXES = listOf("ها", "ان", "ات", "ون", "ين", "يه", "ية", "ه", "ة", "ي")

    /**
     * Light stemmer (Larkey's light10 style) for folded Arabic words: strips a leading و, the article forms
     * (ال، وال، بال، كال، فال، لل) and common suffixes (ها ان ات ون ين يه ية ه ة ي), keeping at least two letters.
     * Latin words lose a plural / -ing / -ed ending. Other words are returned unchanged.
     */
    fun lightStem(word: String): String {
        if (word.isEmpty()) return word
        if (!hasArabic(word)) {
            val w = word.lowercase()
            return when {
                w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
                w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
                w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
                w.length > 4 && w.endsWith("es") -> w.dropLast(2)
                w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
                else -> w
            }
        }
        var w = word
        if (w.length > 3 && w[0] == 'و') w = w.substring(1)
        for (p in AR_PREFIXES) if (w.startsWith(p) && w.length - p.length >= 2) { w = w.substring(p.length); break }
        for (s in AR_SUFFIXES) if (w.length - s.length >= 2 && w.endsWith(s)) w = w.dropLast(s.length)
        return w
    }
}
