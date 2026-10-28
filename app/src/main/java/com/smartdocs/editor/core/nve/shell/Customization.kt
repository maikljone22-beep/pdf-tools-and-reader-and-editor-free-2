package com.smartdocs.editor.core.nve.shell

import org.json.JSONArray
import org.json.JSONObject

/** A user-defined reader tab ("customise ribbon": new tab with its own name, icon and tools). */
data class UserTab(val id: String, val name: String, val icon: String, val tools: List<String>)

/**
 * Everything the user customised in the shell (reader tab order / hidden tabs / hidden chips, user tabs,
 * favourite tools, the Custom tab name), as one JSON document for export / import.
 */
data class Customization(
    val tabOrder: List<String> = ToolbarModel.DEFAULT_TABS,
    val hiddenTabs: Set<String> = emptySet(),
    val hiddenChips: Set<String> = emptySet(),
    val userTabs: List<UserTab> = emptyList(),
    val favorites: List<String> = emptyList(),
    val favoritesTitle: String? = null,
    val customTabName: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("format", "smartdocs-customization").put("version", 1)
        .put("tabOrder", JSONArray(tabOrder)).put("hiddenTabs", JSONArray(hiddenTabs.toList())).put("hiddenChips", JSONArray(hiddenChips.toList()))
        .put("userTabs", JSONArray(userTabs.map { t -> JSONObject().put("id", t.id).put("name", t.name).put("icon", t.icon).put("tools", JSONArray(t.tools)) }))
        .put("favorites", JSONArray(favorites)).put("favoritesTitle", favoritesTitle ?: "").put("customTabName", customTabName ?: "")
        .toString(2)

    companion object {
        const val MAX_USER_TABS = 6
        const val MAX_TOOLS_PER_TAB = 16
        val ICONS = listOf("star", "work", "edit", "school", "bolt", "build", "favorite", "folder")

        private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList()

        /** Parse an exported file; unknown tabs are dropped and limits applied. Throws on a foreign file. */
        fun fromJson(text: String): Customization {
            val o = JSONObject(text)
            require(o.optString("format") == "smartdocs-customization") { "not a SmartDocs customization file" }
            val tabs = o.optJSONArray("userTabs")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { t ->
                    UserTab(t.optString("id").ifBlank { "u" + t.hashCode() }, t.optString("name").take(30), t.optString("icon").takeIf { it in ICONS } ?: "star",
                        t.strings("tools").distinct().take(MAX_TOOLS_PER_TAB))
                }.distinctBy { it.id }.take(MAX_USER_TABS)
            } ?: emptyList()
            return Customization(
                tabOrder = ToolbarModel.order(o.strings("tabOrder")),
                hiddenTabs = o.strings("hiddenTabs").filter { it in ToolbarModel.DEFAULT_TABS }.toSet(),
                hiddenChips = o.strings("hiddenChips").toSet(),
                userTabs = tabs,
                favorites = o.strings("favorites").distinct().take(Favorites.MAX),
                favoritesTitle = o.optString("favoritesTitle").ifBlank { null },
                customTabName = o.optString("customTabName").ifBlank { null },
            )
        }

        fun parseTabs(raw: String?): List<UserTab> = runCatching { if (raw.isNullOrBlank()) emptyList() else fromJson(Customization(userTabs = emptyList()).toJson().let { base ->
            JSONObject(base).put("userTabs", JSONArray(raw)).toString() }).userTabs }.getOrDefault(emptyList())

        fun tabsJson(tabs: List<UserTab>): String = JSONArray(tabs.map { t -> JSONObject().put("id", t.id).put("name", t.name).put("icon", t.icon).put("tools", JSONArray(t.tools)) }).toString()
    }
}
