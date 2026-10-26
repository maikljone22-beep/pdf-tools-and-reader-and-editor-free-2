package com.smartdocs.editor.core.share5

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A view-only link this device created. The owner key is the only credential to manage it. */
data class ViewLinkRef(val id: String, val ownerKey: String, val url: String, val title: String, val fileName: String, val created: Long,
                       val expires: Long, val hasPassword: Boolean, val allowDownload: Boolean, val disabled: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("key", ownerKey).put("url", url).put("title", title).put("file", fileName).put("created", created)
        .put("expires", expires).put("pw", hasPassword).put("dl", allowDownload).put("off", disabled)
    companion object {
        fun from(o: JSONObject) = ViewLinkRef(o.getString("id"), o.getString("key"), o.getString("url"), o.optString("title"), o.optString("file"),
            o.optLong("created"), o.optLong("expires"), o.optBoolean("pw"), o.optBoolean("dl", true), o.optBoolean("off"))
    }
}

object ViewLinkStore {
    private val _links = MutableStateFlow<List<ViewLinkRef>>(emptyList())
    val links: StateFlow<List<ViewLinkRef>> = _links
    @Volatile private var loaded = false
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("share5_links", Context.MODE_PRIVATE)

    fun load(c: Context): List<ViewLinkRef> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val a = runCatching { JSONArray(prefs(c).getString("links", "[]")) }.getOrDefault(JSONArray())
                _links.value = (0 until a.length()).mapNotNull { runCatching { ViewLinkRef.from(a.getJSONObject(it)) }.getOrNull() }
                loaded = true
            }
        }
        return _links.value
    }

    private fun save(c: Context, list: List<ViewLinkRef>) {
        _links.value = list
        prefs(c).edit().putString("links", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun get(c: Context, id: String) = load(c).firstOrNull { it.id == id }
    fun upsert(c: Context, l: ViewLinkRef) = synchronized(this) { save(c, listOf(l) + load(c).filterNot { it.id == l.id }) }
    fun remove(c: Context, id: String) = synchronized(this) { save(c, load(c).filterNot { it.id == id }) }
}

/** Local mirror of the owner settings of managed reviews + the "comments seen" counter used for the new-comments badge. */
object ReviewExtras {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("share5_reviews", Context.MODE_PRIVATE)

    fun get(c: Context, id: String): ReviewExtra? = prefs(c).getString("x_$id", null)?.let { runCatching { ReviewExtra.from(JSONObject(it)) }.getOrNull() }
    fun put(c: Context, id: String, e: ReviewExtra) = prefs(c).edit().putString("x_$id", e.toJson().toString()).apply()

    fun webUrl(c: Context, id: String): String? = prefs(c).getString("w_$id", null)
    fun setWebUrl(c: Context, id: String, url: String?) = prefs(c).edit().putString("w_$id", url).apply()

    fun seenCount(c: Context, id: String): Int = prefs(c).getInt("s_$id", 0)
    fun setSeenCount(c: Context, id: String, n: Int) = prefs(c).edit().putInt("s_$id", n).apply()

    fun remove(c: Context, id: String) = prefs(c).edit().remove("x_$id").remove("w_$id").remove("s_$id").apply()
}
