package com.smartdocs.editor.core.ai5

/** A numbered passage given to the model; [n] is the 1-based citation number, [page] 0-based. */
data class Source(val n: Int, val docKey: String, val fileName: String, val filePath: String, val page: Int, val text: String)

/**
 * Whole-document retrieval: BM25 per document, then a page-diverse pick under a character budget. Questions that
 * ask about the document as a whole ("لخص", "what is this about") get passages spread over every part instead.
 */
object Retriever {
    const val MAX_CHARS = 9000
    const val K = 10

    private val GENERIC = listOf(
        "لخص", "تلخيص", "ملخص", "عن ماذا", "عما", "موضوع", "الفكره الرئيسيه", "الافكار الرئيسيه", "اهم النقاط", "اهم الافكار", "نظره عامه", "فهرس", "اشرح المستند", "اشرح الملف",
        "summar", "overview", "what is this", "main idea", "main point", "key point", "outline", "tl;dr", "tldr", "about this",
    )

    fun isGeneric(query: String): Boolean {
        val f = AiNorm.fold(query)
        return GENERIC.any { f.contains(it) } || AiTokens.tokens(query).isEmpty()
    }

    fun retrieve(indexes: List<DocIndex>, query: String, maxChars: Int = MAX_CHARS, k: Int = K): List<Source> {
        val docs = indexes.filter { it.chunks.isNotEmpty() }
        if (docs.isEmpty()) return emptyList()
        val picked = ArrayList<Pair<DocIndex, DocChunk>>()
        if (isGeneric(query)) {
            val per = maxChars / docs.size
            docs.forEach { d -> d.spread(per).forEach { picked += d to it } }
        } else {
            val scored = docs.flatMap { d ->
                val hits = d.search(query, k * 3)
                val best = hits.firstOrNull()?.second?.coerceAtLeast(1e-9) ?: 1.0
                hits.map { (c, s) -> Triple(d, c, s / best) }
            }
            // normalise per document so a second file is not drowned by the first; keep every doc that matched
            val ranked = scored.sortedByDescending { it.third }
            val perPage = HashMap<String, Int>()
            var used = 0
            val mustHave = docs.mapNotNull { d -> ranked.firstOrNull { it.first == d } }
            for (t in mustHave + ranked) {
                if (picked.size >= k) break
                if (picked.any { it.second.id == t.second.id && it.first == t.first }) continue
                val pk = t.first.key + ":" + t.second.page
                if ((perPage[pk] ?: 0) >= 2) continue
                if (used + t.second.text.length > maxChars && picked.isNotEmpty()) continue
                picked += t.first to t.second; used += t.second.text.length
                perPage[pk] = (perPage[pk] ?: 0) + 1
            }
            // nothing matched lexically: fall back to a spread so the model still sees the document
            if (picked.isEmpty()) docs.forEach { d -> d.spread(maxChars / docs.size).forEach { picked += d to it } }
            // add the neighbour of the best hit when budget allows (definitions often continue on the next chunk)
            picked.firstOrNull()?.let { (d, c) ->
                val next = d.chunks.getOrNull(c.id + 1)
                if (next != null && next.page == c.page && picked.none { it.first == d && it.second.id == next.id } && picked.sumOf { it.second.text.length } + next.text.length <= maxChars) picked += d to next
            }
        }
        // stable order: by document, then position, so the model reads passages in reading order
        val order = docs.withIndex().associate { it.value.key to it.index }
        return picked.sortedWith(compareBy({ order[it.first.key] ?: 0 }, { it.second.id }))
            .mapIndexed { i, (d, c) -> Source(i + 1, d.key, d.name, d.path, c.page, c.text) }
    }

    /** Numbered block for the prompt. With several documents each passage names its file. */
    fun sourcesBlock(sources: List<Source>): String {
        val multi = sources.map { it.docKey }.distinct().size > 1
        return buildString {
            sources.forEach { s ->
                append('[').append(s.n).append("] (")
                if (multi) append(s.fileName).append(" — ")
                append("صفحة ").append(s.page + 1).append(")\n")
                append(s.text.trim()).append("\n\n")
            }
        }.trim()
    }
}
