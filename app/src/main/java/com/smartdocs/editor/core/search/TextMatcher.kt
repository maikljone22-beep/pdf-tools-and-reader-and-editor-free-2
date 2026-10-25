package com.smartdocs.editor.core.search

/**
 * Finds a query in text using [ArabicNormalizer] folding and returns ranges in the ORIGINAL text.
 * Also tries the reversed query for Arabic, because many PDFs store Arabic in visual (reversed) order.
 */
object TextMatcher {

    fun find(text: String, query: String, norm: NormOptions, wholeWord: Boolean): List<IntRange> {
        val q = ArabicNormalizer.foldQuery(query, norm)
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val f = ArabicNormalizer.fold(text, norm)
        val out = ArrayList<IntRange>()
        collect(text, f, q, wholeWord, out)
        if (ArabicNormalizer.hasArabic(q) && q.length > 1) {
            val rev = q.reversed()
            if (rev != q) collect(text, f, rev, wholeWord, out)
        }
        return out.sortedBy { it.first }.fold(ArrayList<IntRange>()) { acc, r ->
            if (acc.isNotEmpty() && r.first <= acc.last().last) acc else acc.apply { add(r) }
        }
    }

    private fun collect(src: String, f: Folded, q: String, wholeWord: Boolean, out: MutableList<IntRange>) {
        val t = f.text
        var from = 0
        while (true) {
            val at = t.indexOf(q, from)
            if (at < 0) break
            from = at + 1
            val endF = at + q.length
            if (wholeWord && (isWordChar(t.getOrNull(at - 1)) || isWordChar(t.getOrNull(endF)))) continue
            val start = f.map[at]
            var end = f.map[endF - 1] + 1
            // take the tashkeel that follows the last letter, so the highlight covers "مُحَمَّدٌ" fully
            while (end < src.length && ArabicNormalizer.isArabicMark(src[end])) end++
            out += start until end
        }
    }

    private fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit() || ArabicNormalizer.isArabicMark(c))

    fun snippet(text: String, r: IntRange, pad: Int = 30): String {
        val s = (r.first - pad).coerceAtLeast(0); val e = (r.last + 1 + pad).coerceAtMost(text.length)
        return text.substring(s, e).replace('\r', ' ').replace('\n', ' ')
    }
}
