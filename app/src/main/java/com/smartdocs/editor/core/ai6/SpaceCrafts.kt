package com.smartdocs.editor.core.ai6

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** One output made from a Space: slides (PPTX / PDF / MD / MP4), an infographic or a web page (PNG / HTML / PDF). */
data class SpaceCraft(val kind: String, val path: String, val created: Long) {
    val file: File get() = File(path)

    companion object {
        const val SLIDES = "slides"
        const val INFOGRAPHIC = "infographic"
        const val WEB_PAGE = "web"
    }
}

/**
 * aor (B13 / §35): the outputs made from each Space, so the Space lists them again (open / share / remove).
 * One JSON file per Space under [dir]; entries whose file is gone are dropped when read. Pure JVM, unit tested.
 */
class SpaceCrafts(private val dir: File) {
    private fun file(spaceId: String) = File(dir, spaceId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    @Synchronized fun list(spaceId: String): List<SpaceCraft> {
        val f = file(spaceId)
        if (!f.exists()) return emptyList()
        val arr = runCatching { Json.parseToJsonElement(f.readText()) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            SpaceCraft((o["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty(), path, (o["created"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }.filter { it.file.exists() }.sortedByDescending { it.created }
    }

    @Synchronized fun add(spaceId: String, kind: String, out: File, now: Long = System.currentTimeMillis()) {
        save(spaceId, listOf(SpaceCraft(kind, out.absolutePath, now)) + list(spaceId).filter { it.path != out.absolutePath })
    }

    /** Forget an entry; the file itself is deleted only when [deleteFile]. */
    @Synchronized fun remove(spaceId: String, path: String, deleteFile: Boolean = false) {
        save(spaceId, list(spaceId).filter { it.path != path })
        if (deleteFile) File(path).delete()
    }

    @Synchronized fun clear(spaceId: String) { file(spaceId).delete() }

    private fun save(spaceId: String, items: List<SpaceCraft>) {
        dir.mkdirs()
        file(spaceId).writeText(buildJsonArray {
            items.forEach { c -> add(buildJsonObject { put("kind", c.kind); put("path", c.path); put("created", c.created) }) }
        }.toString())
    }
}
