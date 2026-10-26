package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.AfNumbers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * Values the user typed before, offered again while typing in a form field (like Acrobat's
 * "remember entered values"). Values are grouped by a normalised field key ("Name_2[0]" and "name" share
 * one key), and every value also lands in a global pool used when the key has nothing that matches.
 * Pure logic: persistence is [toJson] / [fromJson]; [AutocompleteFile] stores it on disk.
 */
class AutocompleteStore(private val maxPerKey: Int = 40, private val maxGlobal: Int = 300) {

    data class Entry(val value: String, val count: Int, val last: Long)

    private val byKey = LinkedHashMap<String, MutableList<Entry>>()

    val size: Int get() = byKey.values.sumOf { it.size }

    fun record(fieldName: String, value: String, now: Long = System.currentTimeMillis()) {
        val v = value.trim()
        if (v.isEmpty() || v.length > 300) return
        add(normKey(fieldName), v, now, maxPerKey)
        add(GLOBAL, v, now, maxGlobal)
    }

    private fun add(key: String, v: String, now: Long, cap: Int) {
        if (key.isEmpty()) return
        val list = byKey.getOrPut(key) { ArrayList() }
        val i = list.indexOfFirst { it.value == v }
        if (i >= 0) list[i] = list[i].copy(count = list[i].count + 1, last = now) else list += Entry(v, 1, now)
        if (list.size > cap) {
            // drop the least useful: fewest uses, then oldest
            list.sortWith(compareByDescending<Entry> { it.count }.thenByDescending { it.last })
            while (list.size > cap) list.removeAt(list.lastIndex)
        }
    }

    /**
     * Up to [limit] earlier values for [fieldName] matching [typed]: values that start with it first,
     * then values containing a word that starts with it; inside each group by use count, then recency.
     * The value equal to [typed] is never offered. Empty [typed] lists the most used values of the key.
     */
    fun suggestions(fieldName: String, typed: String, limit: Int = 5): List<String> {
        val q = fold(typed.trim())
        val own = rank(byKey[normKey(fieldName)].orEmpty(), q)
        val out = LinkedHashSet<String>(own)
        if (out.size < limit && q.isNotEmpty()) out += rank(byKey[GLOBAL].orEmpty(), q)
        return out.filter { fold(it) != q }.take(limit)
    }

    private fun rank(list: List<Entry>, q: String): List<String> {
        val order = compareByDescending<Entry> { it.count }.thenByDescending { it.last }
        if (q.isEmpty()) return list.sortedWith(order).map { it.value }
        val starts = list.filter { fold(it.value).startsWith(q) }.sortedWith(order)
        val words = list.filter { e -> val f = fold(e.value); !f.startsWith(q) && f.split(' ', '-', '_', '/', ',').any { it.startsWith(q) } }.sortedWith(order)
        return (starts + words).map { it.value }
    }

    fun remove(fieldName: String, value: String) {
        byKey[normKey(fieldName)]?.removeAll { it.value == value }
        byKey[GLOBAL]?.removeAll { it.value == value }
    }

    fun clear() = byKey.clear()

    fun toJson(): String = buildJsonObject {
        byKey.forEach { (k, list) ->
            put(k, buildJsonArray { list.forEach { e -> add(buildJsonArray { add(JsonPrimitive(e.value)); add(JsonPrimitive(e.count)); add(JsonPrimitive(e.last)) }) } })
        }
    }.toString()

    companion object {
        const val GLOBAL = "*"

        /** "form1.Page1.FirstName_2[0]" → "firstname". */
        fun normKey(fieldName: String): String {
            val last = fieldName.substringAfterLast('.').replace(Regex("""\[\d+]"""), "")
            return last.lowercase().trimEnd { it.isDigit() || it == '_' || it == '-' || it == ' ' }.ifEmpty { last.lowercase() }
        }

        /** Case-, digit- and alef/yaa/taa-marbuta-insensitive form used for matching. */
        fun fold(s: String): String = buildString(s.length) {
            for (c in AfNumbers.normalizeDigits(s).lowercase()) append(
                when (c.code) {
                    0x0622, 0x0623, 0x0625, 0x0671 -> 'ا'   // alef forms -> bare alef
                    0x0649 -> 'ي'                           // alef maqsura -> yaa
                    0x0629 -> 'ه'                           // taa marbuta -> haa
                    else -> c
                }
            )
        }.filter { it.code !in 0x064B..0x0652 && it.code != 0x0640 }   // drop harakat and tatweel

        fun fromJson(json: String?, maxPerKey: Int = 40): AutocompleteStore {
            val st = AutocompleteStore(maxPerKey)
            if (json.isNullOrBlank()) return st
            runCatching {
                val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
                root.forEach { (k, arr) ->
                    val list = ArrayList<Entry>()
                    (arr as? JsonArray)?.forEach { item ->
                        runCatching { val a = item.jsonArray; list += Entry(a[0].jsonPrimitive.content, a[1].jsonPrimitive.int, a[2].jsonPrimitive.long) }
                    }
                    st.byKey[k] = list
                }
            }
            return st
        }
    }
}

/** Disk persistence of the [AutocompleteStore] (one small JSON file in the app's private files). */
class AutocompleteFile(private val file: File) {
    @Volatile private var cached: AutocompleteStore? = null

    @Synchronized fun store(): AutocompleteStore = cached ?: AutocompleteStore.fromJson(runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()).also { cached = it }

    @Synchronized fun save() {
        val s = cached ?: return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(s.toJson())
            if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
        }
    }
}
