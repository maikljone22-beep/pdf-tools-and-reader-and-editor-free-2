package com.smartdocs.editor.core.ai5

import kotlin.math.ln

/** One retrievable passage: [page] is 0-based, [para] is the paragraph index on that page. */
data class DocChunk(val id: Int, val page: Int, val para: Int, val text: String) {
    /** Short human label, e.g. "ص 12" (1-based). */
    val pageLabel: Int get() = page + 1
}

/**
 * Splits page texts into passages of about [target] characters. Paragraph breaks (blank lines, or lines that end
 * a sentence) are preferred; long paragraphs are cut at sentence ends, then at spaces. A chunk never spans pages,
 * so every citation points at exactly one page.
 */
object Chunker {
    const val TARGET = 900
    const val MAX = 1400
    const val MIN = 120

    private val sentenceEnd = Regex("(?<=[.!?؟۔؛:])\\s+")

    fun chunk(pages: List<String>, target: Int = TARGET, max: Int = MAX): List<DocChunk> {
        val out = ArrayList<DocChunk>()
        pages.forEachIndexed { p, raw -> chunkPage(p, raw, target, max).forEachIndexed { i, t -> out += DocChunk(out.size, p, i, t) } }
        return out
    }

    fun paragraphs(raw: String): List<String> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val paras = ArrayList<String>()
        val cur = StringBuilder()
        for (line0 in text.split('\n')) {
            val line = line0.trim()
            if (line.isEmpty()) { if (cur.isNotEmpty()) { paras += cur.toString(); cur.setLength(0) }; continue }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(line)
            val last = line.last()
            if (last == '.' || last == '۔' || last == '!' || last == '?' || last == '؟') { paras += cur.toString(); cur.setLength(0) }
        }
        if (cur.isNotEmpty()) paras += cur.toString()
        return paras.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
    }

    fun chunkPage(page: Int, raw: String, target: Int = TARGET, max: Int = MAX): List<String> {
        val pieces = ArrayList<String>()
        paragraphs(raw).forEach { p -> if (p.length <= max) pieces += p else pieces += splitLong(p, target, max) }
        // merge small neighbours up to target
        val merged = ArrayList<String>()
        val cur = StringBuilder()
        for (p in pieces) {
            if (cur.isNotEmpty() && cur.length + 1 + p.length > target) { merged += cur.toString(); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(p)
        }
        if (cur.isNotEmpty()) {
            if (cur.length < MIN && merged.isNotEmpty() && merged.last().length + cur.length < max) merged[merged.size - 1] = merged.last() + "\n" + cur
            else merged += cur.toString()
        }
        return merged
    }

    private fun splitLong(p: String, target: Int, max: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in p.split(sentenceEnd)) {
            var sent = s
            while (sent.length > max) {
                val cut = sent.lastIndexOf(' ', target).takeIf { it > target / 2 } ?: target
                if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                out += sent.substring(0, cut).trim(); sent = sent.substring(cut).trim()
            }
            if (cur.isNotEmpty() && cur.length + 1 + sent.length > target) { out += cur.toString(); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(sent)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.filter { it.isNotBlank() }
    }
}

/** BM25 index over the chunks of one document. Built in memory; cheap enough for thousands of chunks. */
class DocIndex(val key: String, val name: String, val path: String, val pageCount: Int, val chunks: List<DocChunk>) {
    private val k1 = 1.4
    private val b = 0.72
    private val tf: List<Map<String, Int>> = chunks.map { c -> AiTokens.tokens(c.text).groupingBy { it }.eachCount() }
    private val len: IntArray = IntArray(chunks.size) { i -> tf[i].values.sum() }
    private val avg: Double = if (chunks.isEmpty()) 1.0 else len.average().coerceAtLeast(1.0)
    private val df: Map<String, Int> = HashMap<String, Int>().also { m -> tf.forEach { t -> t.keys.forEach { m[it] = (m[it] ?: 0) + 1 } } }

    private val folded: List<String> by lazy { chunks.map { AiNorm.fold(it.text) } }

    val totalChars: Int get() = chunks.sumOf { it.text.length }

    fun idf(term: String): Double {
        val n = df[term] ?: 0
        return ln(1.0 + (chunks.size - n + 0.5) / (n + 0.5))
    }

    /** Scores per chunk for [query] (chunks with score 0 omitted), best first. */
    fun search(query: String, k: Int = 12): List<Pair<DocChunk, Double>> {
        val q = AiTokens.tokens(query).distinct()
        if (q.isEmpty() || chunks.isEmpty()) return emptyList()
        val scores = DoubleArray(chunks.size)
        q.forEach { term ->
            val idf = idf(term)
            if (idf <= 0.0) return@forEach
            for (i in chunks.indices) {
                val f = tf[i][term] ?: prefixCount(tf[i], term)
                if (f == 0) continue
                scores[i] += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * len[i] / avg))
            }
        }
        // phrase bonus: the folded query appears verbatim
        val phrase = AiNorm.fold(query).trim()
        if (phrase.length >= 6) for (i in chunks.indices) if (scores[i] > 0 && folded[i].contains(phrase)) scores[i] *= 1.5
        return chunks.indices.filter { scores[it] > 0 }.sortedByDescending { scores[it] }.take(k).map { chunks[it] to scores[it] }
    }

    /** Partial credit when the stemmer left different endings ("مكتب" vs "مكتبه"): tokens starting with the term. */
    private fun prefixCount(m: Map<String, Int>, term: String): Int {
        if (term.length < 4) return 0
        var n = 0
        m.forEach { (t, c) -> if (t.length > term.length && t.startsWith(term)) n += c }
        return n
    }

    /**
     * Passages spread evenly over the whole document up to [maxChars] (for "summarise", "what is this about").
     * Every page region is represented; long chunks are clipped so many regions fit.
     */
    fun spread(maxChars: Int): List<DocChunk> {
        if (chunks.isEmpty()) return emptyList()
        if (totalChars <= maxChars) return chunks
        val per = (maxChars / chunks.size).coerceAtLeast(0)
        if (per >= 250) return chunks.map { it.copy(text = clip(it.text, per)) }
        val n = (maxChars / 400).coerceIn(1, chunks.size)
        val step = chunks.size.toDouble() / n
        return (0 until n).map { i -> chunks[(i * step).toInt().coerceAtMost(chunks.size - 1)] }.distinct().map { it.copy(text = clip(it.text, 400)) }
    }

    companion object {
        fun clip(s: String, n: Int): String = if (s.length <= n) s else s.take(n).substringBeforeLast(' ', s.take(n)) + "…"

        /** Page-marked text of [chunks] in document order ("[صفحة N]" markers, like the rest of the app). */
        fun material(chunks: List<DocChunk>): String = buildString {
            var last = -1
            chunks.sortedBy { it.id }.forEach { c ->
                if (c.page != last) { append("\n[صفحة ").append(c.page + 1).append("]\n"); last = c.page }
                append(c.text).append('\n')
            }
        }.trim()
    }
}
