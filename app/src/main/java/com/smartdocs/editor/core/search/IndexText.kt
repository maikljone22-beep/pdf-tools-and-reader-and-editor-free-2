package com.smartdocs.editor.core.search

/**
 * Pure text helpers of the full-text index: what is stored in the FTS column and how a user query becomes an
 * FTS4 MATCH expression. The index folds with fixed options (tashkeel, letter forms, ة=ه, digits, case), so a
 * document is indexed once whatever the user's switches; [TextMatcher] verifies candidates with the real options.
 */
object IndexText {
    val INDEX_NORM = NormOptions(ignoreDiacritics = true, unifyLetters = true, taMarbuta = true, unifyDigits = true, matchCase = false, ignoreWidth = true)

    /** Folded words plus their light stems (when different), space separated: only letters and digits. */
    fun body(raw: String): String {
        val words = ArabicNormalizer.words(ArabicNormalizer.fold(raw, INDEX_NORM).text)
        val sb = StringBuilder(raw.length + 16)
        for (w in words) {
            sb.append(w).append(' ')
            val s = ArabicNormalizer.lightStem(w)
            if (s != w && s.length >= 2) sb.append(s).append(' ')
        }
        return sb.toString().trimEnd()
    }

    /**
     * FTS4 MATCH expression for [query]: each word becomes a prefix term of its light stem (so "الكتاب" finds
     * "كتابه" and "الكتب" candidates); words are AND-ed, or OR-ed for [SearchMode.ANY_WORDS]. Null when the
     * query has no word (or is a regex): the caller then scans without FTS.
     */
    fun ftsQuery(query: String, mode: SearchMode): String? {
        if (mode == SearchMode.REGEX) return null
        val words = ArabicNormalizer.words(ArabicNormalizer.fold(query, INDEX_NORM).text).distinct()
        if (words.isEmpty()) return null
        val terms = words.map { w ->
            val s = ArabicNormalizer.lightStem(w).takeIf { it.length >= 2 } ?: w
            "$s*"
        }.distinct()
        return terms.joinToString(if (mode == SearchMode.ANY_WORDS) " OR " else " ")
    }

    /**
     * The MATCH expressions a search runs, results united (audit AN-15): [ftsQuery] plus, for Arabic words, the
     * reversed words, because visual-order PDFs store "محمد" as "دمحم" and the body is indexed from the raw text.
     * A reversed word can only be matched by prefix from its logical end, so each Arabic word gives its reversed
     * stem ("الكتاب" → "باتك*" finds "باتكلا") and its reversed full word ("كتابه" → "هباتك*"). Separate
     * expressions instead of `(a OR b) c` keep the meaning the same in FTS standard and enhanced query syntax.
     * Empty when [ftsQuery] is null.
     */
    fun ftsQueries(query: String, mode: SearchMode): List<String> {
        val forward = ftsQuery(query, mode) ?: return emptyList()
        val words = ArabicNormalizer.words(ArabicNormalizer.fold(query, INDEX_NORM).text).distinct()
        if (words.none { ArabicNormalizer.hasArabic(it) && it.length > 1 }) return listOf(forward)
        fun stem(w: String) = ArabicNormalizer.lightStem(w).takeIf { it.length >= 2 } ?: w
        fun rev(w: String, useStem: Boolean) = if (ArabicNormalizer.hasArabic(w) && w.length > 1) (if (useStem) stem(w) else w).reversed() + "*" else stem(w) + "*"
        val reversed = listOf(true, false).map { useStem -> words.map { rev(it, useStem) }.distinct() }
        return if (mode == SearchMode.ANY_WORDS) listOf((forward.split(" OR ") + reversed.flatten()).distinct().joinToString(" OR "))
        else (listOf(forward) + reversed.map { it.joinToString(" ") }).distinct()
    }
}
