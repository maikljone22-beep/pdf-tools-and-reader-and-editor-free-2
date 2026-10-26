package com.smartdocs.editor.core.search

/** How a query is interpreted. */
enum class SearchMode { PHRASE, ANY_WORDS, ALL_WORDS, REGEX }

/**
 * Full search options. [stem] matches words by their light stem ([ArabicNormalizer.lightStem]); [proximity]
 * (ALL_WORDS only) is the window, in words, that must contain every query word; 0 = anywhere in the text.
 */
data class SearchOptions(
    val norm: NormOptions = NormOptions(),
    val wholeWord: Boolean = false,
    val mode: SearchMode = SearchMode.PHRASE,
    val stem: Boolean = false,
    val proximity: Int = 0,
)

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

    // ---------------- additions (search5): modes, stemming, proximity, regex ----------------

    /** Matches [query] in [text] with [o]; ranges are in the ORIGINAL text, sorted and non-overlapping. */
    fun find(text: String, query: String, o: SearchOptions): List<IntRange> {
        if (query.isBlank() || text.isEmpty()) return emptyList()
        return when (o.mode) {
            SearchMode.PHRASE -> if (!o.stem) find(text, query, o.norm, o.wholeWord) else phraseStem(text, query, o)
            SearchMode.ANY_WORDS -> merge(queryWords(query, o).flatMap { wordHits(text, ArabicNormalizer.fold(text, o.norm), it, o) })
            SearchMode.ALL_WORDS -> allWords(text, query, o)
            SearchMode.REGEX -> regex(text, query, o)
        }
    }

    private fun queryWords(q: String, o: SearchOptions) = ArabicNormalizer.words(ArabicNormalizer.foldQuery(q, o.norm)).distinct()

    private fun toSource(src: String, f: Folded, a: Int, bExclusive: Int): IntRange? {
        if (a < 0 || bExclusive <= a || bExclusive > f.map.size) return null
        val start = f.map[a]
        var end = f.map[bExclusive - 1] + 1
        while (end < src.length && ArabicNormalizer.isArabicMark(src[end])) end++
        return start until end
    }

    private fun merge(list: List<IntRange>): List<IntRange> = list.sortedBy { it.first }.fold(ArrayList()) { acc, r ->
        if (acc.isNotEmpty() && r.first <= acc.last().last) acc else acc.apply { add(r) }
    }

    /** Word-level hits of one folded query word: whole word / substring, or same light stem. Source ranges. */
    private fun wordHits(src: String, f: Folded, qw: String, o: SearchOptions): List<IntRange> = foldedWordHits(f.text, qw, o).mapNotNull { toSource(src, f, it.first, it.last + 1) }

    private fun foldedWordHits(t: String, qw: String, o: SearchOptions): List<IntRange> {
        val out = ArrayList<IntRange>()
        val qs = if (o.stem) ArabicNormalizer.lightStem(qw) else qw
        for (wr in ArabicNormalizer.wordRanges(t)) {
            val w = t.substring(wr.first, wr.last + 1)
            when {
                o.stem -> if (ArabicNormalizer.lightStem(w) == qs) out += wr
                o.wholeWord -> if (w == qw) out += wr
                else -> { var at = w.indexOf(qw); while (at >= 0) { out += (wr.first + at) until (wr.first + at + qw.length); at = w.indexOf(qw, at + 1) } }
            }
        }
        return out
    }

    private fun phraseStem(src: String, query: String, o: SearchOptions): List<IntRange> {
        val q = queryWords(query, o).map { ArabicNormalizer.lightStem(it) }
        if (q.isEmpty()) return emptyList()
        val f = ArabicNormalizer.fold(src, o.norm)
        val wr = ArabicNormalizer.wordRanges(f.text)
        val stems = wr.map { ArabicNormalizer.lightStem(f.text.substring(it.first, it.last + 1)) }
        val out = ArrayList<IntRange>()
        var i = 0
        while (i + q.size <= stems.size) {
            if ((q.indices).all { stems[i + it] == q[it] }) { toSource(src, f, wr[i].first, wr[i + q.size - 1].last + 1)?.let { out += it }; i += q.size } else i++
        }
        return out
    }

    private fun allWords(src: String, query: String, o: SearchOptions): List<IntRange> {
        val qws = queryWords(query, o)
        if (qws.isEmpty()) return emptyList()
        val f = ArabicNormalizer.fold(src, o.norm)
        val per = qws.map { foldedWordHits(f.text, it, o) }
        if (per.any { it.isEmpty() }) return emptyList()
        if (o.proximity <= 0) return merge(per.flatten().mapNotNull { toSource(src, f, it.first, it.last + 1) })
        // proximity: keep hits that lie in a window of [proximity] words containing every query word
        val wr = ArabicNormalizer.wordRanges(f.text)
        fun wordIndex(pos: Int): Int { var lo = 0; var hi = wr.size - 1; while (lo < hi) { val m = (lo + hi + 1) / 2; if (wr[m].first <= pos) lo = m else hi = m - 1 }; return lo }
        val events = per.flatMapIndexed { qi, l -> l.map { Triple(wordIndex(it.first), qi, it) } }.sortedBy { it.first }
        val keep = HashSet<IntRange>()
        val last = arrayOfNulls<Triple<Int, Int, IntRange>>(qws.size)
        for (e in events) {
            last[e.second] = e
            if (last.all { it != null }) {
                val lo = last.minOf { it!!.first }; val hi = last.maxOf { it!!.first }
                if (hi - lo < o.proximity) last.forEach { keep += it!!.third }
            }
        }
        return merge(keep.mapNotNull { toSource(src, f, it.first, it.last + 1) })
    }

    private fun regex(src: String, pattern: String, o: SearchOptions): List<IntRange> {
        val f = ArabicNormalizer.fold(src, o.norm.copy(matchCase = true))
        val p = ArabicNormalizer.foldPattern(pattern, o.norm)
        val flags = if (o.norm.matchCase) 0 else (java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE)
        val re = runCatching { java.util.regex.Pattern.compile(if (o.wholeWord) "(?<![\\p{L}\\p{Nd}])(?:$p)(?![\\p{L}\\p{Nd}])" else p, flags) }.getOrNull() ?: return emptyList()
        val m = re.matcher(f.text)
        val out = ArrayList<IntRange>()
        var guard = 0
        while (m.find() && guard++ < 100_000) {
            if (m.end() > m.start()) toSource(src, f, m.start(), m.end())?.let { out += it }
        }
        return merge(out)
    }

    /** True when [pattern] compiles as a regular expression. */
    fun isValidRegex(pattern: String): Boolean = runCatching { java.util.regex.Pattern.compile(pattern) }.isSuccess

    fun snippet(text: String, r: IntRange, pad: Int = 30): String {
        val s = (r.first - pad).coerceAtLeast(0); val e = (r.last + 1 + pad).coerceAtMost(text.length)
        return text.substring(s, e).replace('\r', ' ').replace('\n', ' ')
    }
}
