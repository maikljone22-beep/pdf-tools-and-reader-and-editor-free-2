package com.smartdocs.editor.core.esign

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A request I sent (owner key kept only here) or one I was asked to sign (signer token). */
data class EsignRef(
    val id: String, val title: String, val sent: Boolean, val secret: String, val created: Long,
    val localPath: String = "", val status: String = "out", val lastCheck: Long = 0L, val finalPath: String = "", val counts: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("sent", sent).put("secret", secret).put("created", created)
        .put("path", localPath).put("status", status).put("last", lastCheck).put("final", finalPath).put("counts", counts)
    companion object {
        fun from(o: JSONObject) = EsignRef(o.getString("id"), o.optString("title"), o.optBoolean("sent"), o.getString("secret"), o.optLong("created"),
            o.optString("path"), o.optString("status", "out"), o.optLong("last"), o.optString("final"), o.optString("counts"))
    }
}

object EsignStore {
    private val _items = MutableStateFlow<List<EsignRef>>(emptyList())
    val items: StateFlow<List<EsignRef>> = _items
    @Volatile private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("esign_store", Context.MODE_PRIVATE)

    fun load(c: Context): List<EsignRef> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val arr = runCatching { JSONArray(prefs(c).getString("items", "[]")) }.getOrDefault(JSONArray())
                _items.value = (0 until arr.length()).mapNotNull { runCatching { EsignRef.from(arr.getJSONObject(it)) }.getOrNull() }
                loaded = true
            }
        }
        return _items.value
    }

    private fun save(c: Context, list: List<EsignRef>) {
        _items.value = list
        prefs(c).edit().putString("items", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun upsert(c: Context, r: EsignRef) = synchronized(this) { save(c, load(c).filterNot { it.id == r.id && it.sent == r.sent } + r) }
    fun remove(c: Context, id: String, sent: Boolean) = synchronized(this) { save(c, load(c).filterNot { it.id == id && it.sent == sent }) }
    fun sent(c: Context, id: String) = load(c).firstOrNull { it.id == id && it.sent }
    fun received(c: Context, id: String) = load(c).firstOrNull { it.id == id && !it.sent }
    fun update(c: Context, id: String, sent: Boolean, block: (EsignRef) -> EsignRef) = synchronized(this) {
        save(c, load(c).map { if (it.id == id && it.sent == sent) block(it) else it })
    }
}
