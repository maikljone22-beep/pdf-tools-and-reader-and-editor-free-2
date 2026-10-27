package com.smartdocs.editor.core.ai6

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.Source
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * ai6: embeddings re-rank. BM25 ([com.smartdocs.editor.core.ai5.Retriever]) picks a wide candidate list; when the
 * endpoint (or proxy) supports `/embeddings`, the candidates are re-scored by cosine similarity with the
 * question and the best ones within the character budget are kept. Chunk vectors are cached on disk per
 * document key. Any failure silently keeps the BM25 order.
 */
object Reranker {
    /** Weight of the semantic score against the BM25 rank (0..1). */
    const val ALPHA = 0.7

    /**
     * Combined order of [candidates] (BM25 order = list order) given cosine [sims] (same size).
     * BM25 contributes 1 - rank / n so the lexical best still counts.
     */
    fun order(sims: List<Double>, alpha: Double = ALPHA): List<Int> {
        val n = sims.size
        if (n == 0) return emptyList()
        val lo = sims.minOrNull() ?: 0.0; val hi = sims.maxOrNull() ?: 0.0
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        return sims.indices.sortedByDescending { i -> alpha * ((sims[i] - lo) / span) + (1 - alpha) * (1.0 - i.toDouble() / n) }
    }

    /**
     * Keep the best [k] of [candidates] in [order] within [maxChars], then restore reading order (document, page)
     * and renumber 1..k so citations stay consistent.
     */
    fun select(candidates: List<Source>, order: List<Int>, maxChars: Int, k: Int): List<Source> {
        val picked = ArrayList<Source>()
        var used = 0
        for (i in order) {
            if (picked.size >= k) break
            val s = candidates.getOrNull(i) ?: continue
            if (used + s.text.length > maxChars && picked.isNotEmpty()) continue
            picked += s; used += s.text.length
        }
        val docOrder = candidates.map { it.docKey }.distinct()
        return picked.sortedWith(compareBy({ docOrder.indexOf(it.docKey) }, { it.page }, { it.n }))
            .mapIndexed { i, s -> s.copy(n = i + 1) }
    }

    fun textKey(t: String): String = MessageDigest.getInstance("SHA-1").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun cacheFile(ctx: Context, docKey: String) =
        File(File(ctx.cacheDir, "ai6_emb").apply { mkdirs() }, docKey.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun loadCache(f: File): MutableMap<String, FloatArray> = runCatching {
        val o = AiClient.json.parseToJsonElement(f.readText()) as JsonObject
        o.entries.associate { (k, v) -> k to (v as JsonArray).let { a -> FloatArray(a.size) { i -> a[i].jsonPrimitive.content.toFloat() } } }.toMutableMap()
    }.getOrDefault(HashMap())

    private fun saveCache(f: File, m: Map<String, FloatArray>) {
        runCatching { f.writeText(buildJsonObject { m.forEach { (k, v) -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it)) } }) } }.toString()) }
    }

    /** Re-rank [candidates]; returns null when embeddings are not available (caller keeps its own pick). */
    suspend fun rerank(ctx: Context, client: AiClient, query: String, candidates: List<Source>, maxChars: Int, k: Int): List<Source>? {
        if (candidates.size <= 1) return null
        return runCatching {
            if (!AiMedia.supports(client, AiFeature.EMBEDDINGS)) return null
            val caches = candidates.map { it.docKey }.distinct().associateWith { loadCache(cacheFile(ctx, it)) }
            val missing = candidates.filter { caches[it.docKey]?.containsKey(textKey(it.text)) != true }
            val vectors = AiMedia.embed(client, listOf(query) + missing.map { it.text })
            if (vectors.size != missing.size + 1) return null
            missing.forEachIndexed { i, s -> caches[s.docKey]?.put(textKey(s.text), vectors[i + 1]) }
            caches.forEach { (key, m) -> saveCache(cacheFile(ctx, key), m) }
            val q = vectors[0]
            val sims = candidates.map { s -> caches[s.docKey]?.get(textKey(s.text))?.let { AiMedia.cosine(q, it) } ?: 0.0 }
            select(candidates, order(sims), maxChars, k)
        }.getOrNull()
    }
}
