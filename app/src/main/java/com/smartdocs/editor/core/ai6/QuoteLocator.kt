package com.smartdocs.editor.core.ai6

import com.smartdocs.editor.core.search.ArabicNormalizer
import com.smartdocs.editor.core.search.NormOptions

/**
 * ai6 (B1): finds a citation's quote in the raw page text so the reader can highlight the passage. Matching folds
 * Arabic (tashkeel, alef/ya/ta-marbuta forms, digits, case) and ignores all whitespace, because PDFium page text
 * breaks lines where the model's quote has spaces. When the full quote is not found, shorter prefixes are tried
 * (models often shorten or paraphrase the tail), then the reversed quote (visual-order Arabic PDFs).
 * Returns the range in the ORIGINAL text (inclusive), or null.
 */
object QuoteLocator {
    private val opts = NormOptions(ignoreDiacritics = true, unifyLetters = true, taMarbuta = true, unifyDigits = true, matchCase = false)
    private const val MIN_CHARS = 8

    private class Stripped(val text: String, val map: IntArray)

    /** Folded text without whitespace and punctuation; [Stripped.map] points to the source index of each kept char. */
    private fun strip(s: String): Stripped {
        val f = ArabicNormalizer.fold(s, opts)
        val sb = StringBuilder(f.text.length)
        val map = IntArray(f.text.length)
        var n = 0
        for (i in f.text.indices) {
            val c = f.text[i]
            if (c.isWhitespace() || !(c.isLetterOrDigit())) continue
            sb.append(c); map[n++] = f.map.getOrElse(i) { i }
        }
        return Stripped(sb.toString(), map.copyOf(n))
    }

    fun locate(pageText: String, quote: String): IntRange? {
        if (pageText.isBlank() || quote.isBlank()) return null
        val page = strip(pageText)
        val q = strip(quote.trim().trim('"', '«', '»', '“', '”', '…')).text
        if (q.length < 3 || page.text.isEmpty()) return null
        val tries = LinkedHashSet<String>()
        tries += q
        for (len in intArrayOf(80, 60, 40, 25, 15, MIN_CHARS)) if (q.length > len) tries += q.take(len)
        // the tail may be exact while the head is paraphrased
        if (q.length > 25) tries += q.takeLast(25)
        for (t in tries) {
            if (t.length < minOf(MIN_CHARS, q.length)) continue
            val at = page.text.indexOf(t)
            if (at >= 0) return range(page, at, if (t === q || t == q) q.length else t.length)
        }
        val rev = q.reversed()
        val at = page.text.indexOf(rev.take(minOf(rev.length, 40)))
        if (at >= 0) return range(page, at, minOf(rev.length, 40))
        return null
    }

    private fun range(p: Stripped, at: Int, len: Int): IntRange {
        val s = p.map[at]
        val e = p.map[(at + len - 1).coerceAtMost(p.map.size - 1)]
        return minOf(s, e)..maxOf(s, e)
    }
}
