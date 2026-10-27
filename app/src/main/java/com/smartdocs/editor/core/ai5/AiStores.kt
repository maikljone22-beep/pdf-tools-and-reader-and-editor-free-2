package com.smartdocs.editor.core.ai5

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/** A Space: several files questioned together, with optional instructions ("persona"). */
data class Space(val id: String, val name: String, val files: List<String>, val persona: String = "", val created: Long = System.currentTimeMillis())

/** One stored chat turn. Assistant turns keep their citations and follow-ups. */
data class StoredMsg(val role: String, val text: String, val citations: List<Citation> = emptyList(), val followUps: List<String> = emptyList(),
                     // ai6: thumbs feedback (1 / -1 / 0), web results used ([W1]…), number of images sent with a question
                     val feedback: Int = 0, val web: List<com.smartdocs.editor.core.ai6.WebResult> = emptyList(), val images: Int = 0)

/** ai6: [files] = the documents the chat was about, so a chat can be reopened from Home (AI-5). */
data class SavedChat(val id: String, val scope: String, val title: String, val updated: Long, val messages: List<StoredMsg>, val files: List<String> = emptyList())

/** A saved study creation. [data] is JSON text: cards (with SRS), quiz/glossary/mind map answer, or the guide's Markdown. */
data class Creation(val id: String, val scope: String, val kind: StudyKind, val title: String, val created: Long, val data: String)

/**
 * Small JSON-file stores under `filesDir/ai5/` (no Room migration needed). [scope] is the document content key
 * ([DocIndex.key]) for one file, or `space:<id>` for a Space.
 */
class AiStore(ctx: Context) {
    private val root = File(ctx.applicationContext.filesDir, "ai5").apply { mkdirs() }
    private val spacesFile = File(root, "spaces.json")
    private val chatsDir = File(root, "chats").apply { mkdirs() }
    private val creationsDir = File(root, "creations").apply { mkdirs() }

    companion object {
        fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()
        fun spaceScope(id: String) = "space:$id"
        private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }

    // ---- spaces ----
    @Synchronized fun spaces(): List<Space> = runCatching {
        (AiJson.json.parseToJsonElement(spacesFile.readText()) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Space(AiJson.str(o, "id") ?: return@mapNotNull null, AiJson.str(o, "name").orEmpty(),
                (o["files"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(), AiJson.str(o, "persona").orEmpty(),
                (o["created"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L)
        }
    }.getOrDefault(emptyList())

    fun space(id: String): Space? = spaces().firstOrNull { it.id == id }

    @Synchronized fun saveSpace(s: Space) {
        val list = spaces().filter { it.id != s.id } + s
        spacesFile.writeText(buildJsonArray {
            list.forEach { x -> add(buildJsonObject {
                put("id", x.id); put("name", x.name); put("persona", x.persona); put("created", x.created)
                put("files", buildJsonArray { x.files.forEach { add(JsonPrimitive(it)) } })
            }) }
        }.toString())
    }

    @Synchronized fun deleteSpace(id: String) {
        val list = spaces().filter { it.id != id }
        spacesFile.writeText(buildJsonArray { list.forEach { x -> add(buildJsonObject {
            put("id", x.id); put("name", x.name); put("persona", x.persona); put("created", x.created)
            put("files", buildJsonArray { x.files.forEach { add(JsonPrimitive(it)) } })
        }) } }.toString())
        chats(spaceScope(id)).forEach { deleteChat(it.id) }
        creations(spaceScope(id)).forEach { deleteCreation(it.id) }
    }

    // ---- chats ----
    private fun msgJson(m: StoredMsg) = buildJsonObject {
        put("r", m.role); put("t", m.text)
        if (m.citations.isNotEmpty()) put("c", CitationFormat.listToJson(m.citations))
        if (m.followUps.isNotEmpty()) put("f", buildJsonArray { m.followUps.forEach { add(JsonPrimitive(it)) } })
        if (m.feedback != 0) put("fb", m.feedback)
        if (m.images > 0) put("img", m.images)
        if (m.web.isNotEmpty()) put("w", buildJsonArray { m.web.forEach { w -> add(buildJsonObject { put("t", w.title); put("u", w.url); put("s", w.snippet) }) } })
    }

    @Synchronized fun saveChat(c: SavedChat) {
        File(chatsDir, safe(c.id) + ".json").writeText(buildJsonObject {
            put("id", c.id); put("scope", c.scope); put("title", c.title); put("updated", c.updated)
            put("m", buildJsonArray { c.messages.forEach { add(msgJson(it)) } })
            if (c.files.isNotEmpty()) put("files", buildJsonArray { c.files.forEach { add(JsonPrimitive(it)) } })
        }.toString())
    }

    private fun readChat(f: File): SavedChat? = runCatching {
        val o = AiJson.json.parseToJsonElement(f.readText()) as JsonObject
        SavedChat(AiJson.str(o, "id")!!, AiJson.str(o, "scope").orEmpty(), AiJson.str(o, "title").orEmpty(),
            (o["updated"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: f.lastModified(),
            (o["m"] as? JsonArray)?.mapNotNull { e ->
                val m = e as? JsonObject ?: return@mapNotNull null
                StoredMsg(AiJson.str(m, "r") ?: "user", (m["t"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    CitationFormat.listFromJson(m["c"] as? JsonArray), AiJson.strings(m["f"] as? JsonArray),
                    AiJson.int(m, "fb") ?: 0,
                    (m["w"] as? JsonArray)?.mapNotNull { w -> (w as? JsonObject)?.let { o -> com.smartdocs.editor.core.ai6.WebResult(AiJson.str(o, "t").orEmpty(), AiJson.str(o, "u") ?: return@mapNotNull null, AiJson.str(o, "s").orEmpty()) } }.orEmpty(),
                    AiJson.int(m, "img") ?: 0)
            }.orEmpty(),
            (o["files"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
    }.getOrNull()

    /** Chats of a scope, newest first. */
    @Synchronized fun chats(scope: String): List<SavedChat> =
        chatsDir.listFiles()?.mapNotNull { readChat(it) }?.filter { it.scope == scope }?.sortedByDescending { it.updated }.orEmpty()

    /** Every scope that has saved chats (for a global history list). */
    @Synchronized fun chatScopes(): List<String> = chatsDir.listFiles()?.mapNotNull { readChat(it)?.scope }?.distinct().orEmpty()

    /** ai6 (AI-5): every saved chat, newest first (Home "Conversations"). */
    @Synchronized fun allChats(): List<SavedChat> = chatsDir.listFiles()?.mapNotNull { readChat(it) }?.sortedByDescending { it.updated }.orEmpty()

    fun chat(id: String): SavedChat? = File(chatsDir, safe(id) + ".json").takeIf { it.exists() }?.let(::readChat)

    @Synchronized fun deleteChat(id: String) { File(chatsDir, safe(id) + ".json").delete() }

    // ---- creations ----
    @Synchronized fun saveCreation(c: Creation) {
        File(creationsDir, safe(c.id) + ".json").writeText(buildJsonObject {
            put("id", c.id); put("scope", c.scope); put("kind", c.kind.name); put("title", c.title); put("created", c.created); put("data", c.data)
        }.toString())
    }

    @Synchronized fun creations(scope: String): List<Creation> = creationsDir.listFiles()?.mapNotNull { f ->
        runCatching {
            val o = AiJson.json.parseToJsonElement(f.readText()) as JsonObject
            Creation(AiJson.str(o, "id")!!, AiJson.str(o, "scope").orEmpty(), StudyKind.valueOf(AiJson.str(o, "kind")!!), AiJson.str(o, "title").orEmpty(),
                (o["created"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L, (o["data"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }.getOrNull()
    }?.filter { it.scope == scope }?.sortedByDescending { it.created }.orEmpty()

    @Synchronized fun deleteCreation(id: String) { File(creationsDir, safe(id) + ".json").delete() }
}
