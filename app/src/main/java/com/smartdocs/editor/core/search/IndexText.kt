package com.smartdocs.editor.core.search

/**
 * Pure text helpers of the full-text index: what is stored in the FTS column and how a user query becomes an
 * FTS4 MATCH expression. The index folds with fixed options (tashkeel, letter forms, ة=ه, digits, case), so a
 * document is indexed once whatever the user's switches; [TextMatcher] verifies candidates with the real options.
 */
object IndexText {
    val INDEX_NORM = NormOptions(ignoreDiacritics = true, unifyLetters = true, taMarbuta = true, unifyDigits = true, matchCase = false)

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
}
