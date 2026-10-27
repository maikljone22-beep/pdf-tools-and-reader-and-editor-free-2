package com.smartdocs.editor.core.nve.shell

import org.json.JSONArray
import org.json.JSONObject

/** One AI translation kept in the history list (Foxit "translation history"). */
data class TranslationEntry(val time: Long, val source: String, val target: String, val text: String, val result: String)

/** Translation history: newest first, at most [MAX] entries, de-duplicated by source text + target language. */
object TranslationHistory {
    const val MAX = 50

    fun add(list: List<TranslationEntry>, e: TranslationEntry): List<TranslationEntry> =
        (listOf(e) + list.filterNot { it.text == e.text && it.target == e.target }).take(MAX)

    fun toJson(list: List<TranslationEntry>): String = JSONArray(list.map { e ->
        JSONObject().put("t", e.time).put("s", e.source).put("g", e.target).put("x", e.text.take(4000)).put("r", e.result.take(8000))
    }).toString()

    fun fromJson(raw: String?): List<TranslationEntry> = runCatching {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { o ->
            TranslationEntry(o.optLong("t"), o.optString("s"), o.optString("g"), o.optString("x"), o.optString("r"))
        }
    }.getOrDefault(emptyList())

    fun search(list: List<TranslationEntry>, q: String): List<TranslationEntry> =
        if (q.isBlank()) list else list.filter { it.text.contains(q, true) || it.result.contains(q, true) }
}
