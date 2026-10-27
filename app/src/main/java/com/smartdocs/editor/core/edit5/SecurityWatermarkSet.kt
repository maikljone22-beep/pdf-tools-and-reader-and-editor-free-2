package com.smartdocs.editor.core.edit5

import org.json.JSONArray
import org.json.JSONObject

/**
 * A security-watermark profile holding one or more watermarks (Foxit "security watermark profiles", row 10). It is
 * stored in the profile JSON of [E5ProfileBar] as `{"secset": [spec, spec, ...]}`, so the existing named profiles,
 * rename / copy / delete and JSON import / export carry it; a single-spec profile still loads as a set of one.
 */
object SecurityWatermarkSet {
    const val KEY = "secset"

    fun encode(specs: List<DecorSpec>): String {
        if (specs.size == 1) return specs[0].toJson()
        return JSONObject().put(KEY, JSONArray().apply { specs.forEach { put(JSONObject(it.toJson())) } }).toString()
    }

    /** The specs of a profile (empty when it holds none that parse). Every spec becomes a security watermark. */
    fun decode(json: String?): List<DecorSpec> {
        if (json.isNullOrBlank()) return emptyList()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = o.optJSONArray(KEY)
        val specs = if (arr != null) (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { DecorSpec.fromJson(it.toString()) } }
            else listOfNotNull(DecorSpec.fromJson(json))
        return specs.map { it.copy(type = DecorType.SECURITY) }
    }
}
