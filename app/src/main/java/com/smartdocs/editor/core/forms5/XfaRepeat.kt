package com.smartdocs.editor.core.forms5

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A repeating XFA subform in a generated form that can take another instance: [count] instances of [name]
 * under the data group [dataParent] (max -1 = unlimited). [box] is the last instance on [page], in the
 * reader's display space (points, y up), where the reader shows its "add row" button.
 */
data class XfaRepeat(val key: String, val dataParent: String, val name: String, val count: Int, val max: Int, val page: Int, val box: Box5) {
    val canAdd: Boolean get() = max < 0 || count < max

    companion object {
        fun toJson(list: List<XfaRepeat>): String = JsonArray(list.map { r ->
            JsonObject(mapOf(
                "k" to JsonPrimitive(r.key), "d" to JsonPrimitive(r.dataParent), "n" to JsonPrimitive(r.name), "c" to JsonPrimitive(r.count),
                "m" to JsonPrimitive(r.max), "pg" to JsonPrimitive(r.page),
                "r" to JsonArray(listOf(r.box.left, r.box.top, r.box.right, r.box.bottom).map { JsonPrimitive(it) }),
            ))
        }).toString()

        fun parse(json: String?): List<XfaRepeat> {
            if (json.isNullOrBlank()) return emptyList()
            return Json.parseToJsonElement(json).jsonArray.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    val r = o["r"]!!.jsonArray.map { it.jsonPrimitive.float }
                    XfaRepeat(o["k"]!!.jsonPrimitive.content, o["d"]!!.jsonPrimitive.content, o["n"]!!.jsonPrimitive.content,
                        o["c"]!!.jsonPrimitive.int, o["m"]!!.jsonPrimitive.int, o["pg"]!!.jsonPrimitive.int, Box5(r[0], r[1], r[2], r[3]))
                }.getOrNull()
            }
        }
    }
}
