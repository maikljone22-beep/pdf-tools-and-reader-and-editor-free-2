package com.smartdocs.editor.core.redact

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * A named set of password-security settings (Nitro "Security Profiles"). Passwords are never part of a
 * profile: they are typed each time, so the profile file holds nothing secret.
 */
data class SecurityProfile(
    val name: String,
    val algo: EncAlgo = EncAlgo.AES_256,
    val scope: EncScope = EncScope.ALL,
    /** "Restrict editing and printing" on: [permissions] apply; off: every permission is granted. */
    val restrict: Boolean = false,
    val permissions: Permissions = Permissions(),
)

/**
 * Profiles stored as one JSON array in `filesDir/security_profiles.json` (app-private). The list helpers are pure
 * so they are unit tested; names are unique ignoring case.
 */
object SecurityProfiles {
    private const val FILE = "security_profiles.json"

    fun file(context: Context) = File(context.filesDir, FILE)

    fun toJson(list: List<SecurityProfile>): String = JsonArray(list.map { p ->
        val perms = p.permissions
        JsonObject(mapOf(
            "name" to JsonPrimitive(p.name), "algo" to JsonPrimitive(p.algo.name), "scope" to JsonPrimitive(p.scope.name),
            "restrict" to JsonPrimitive(p.restrict),
            "perms" to JsonObject(mapOf(
                "print" to JsonPrimitive(perms.print), "printHigh" to JsonPrimitive(perms.printHigh), "modify" to JsonPrimitive(perms.modify),
                "assemble" to JsonPrimitive(perms.assemble), "copy" to JsonPrimitive(perms.copy), "accessibility" to JsonPrimitive(perms.accessibility),
                "annotate" to JsonPrimitive(perms.annotate), "fillForms" to JsonPrimitive(perms.fillForms),
            )),
        ))
    }).toString()

    /** Tolerant parse: a broken file gives an empty list, a broken entry is skipped, unknown values fall back to defaults. */
    fun fromJson(text: String): List<SecurityProfile> {
        val arr = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val name = o["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (name.isEmpty()) return@runCatching null
                val pm = o["perms"] as? JsonObject
                fun b(k: String) = (pm?.get(k) as? JsonPrimitive)?.booleanOrNull ?: true
                val d = SecurityProfile(name)
                SecurityProfile(
                    name = name,
                    algo = o["algo"]?.jsonPrimitive?.content?.let { a -> EncAlgo.values().firstOrNull { it.name == a } } ?: d.algo,
                    scope = o["scope"]?.jsonPrimitive?.content?.let { s -> EncScope.values().firstOrNull { it.name == s } } ?: d.scope,
                    restrict = (o["restrict"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    permissions = Permissions(b("print"), b("printHigh"), b("modify"), b("assemble"), b("copy"), b("accessibility"), b("annotate"), b("fillForms")),
                ).let { p -> if (p.scope != EncScope.ALL && !p.algo.aes) p.copy(scope = EncScope.ALL) else p }   // RC4 cannot scope
            }.getOrNull()
        }.distinctBy { it.name.lowercase() }
    }

    fun find(list: List<SecurityProfile>, name: String) = list.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Adds [p], or replaces the profile with the same name (keeps its position). */
    fun upsert(list: List<SecurityProfile>, p: SecurityProfile): List<SecurityProfile> {
        val clean = p.copy(name = p.name.trim())
        require(clean.name.isNotEmpty()) { "empty name" }
        val i = list.indexOfFirst { it.name.equals(clean.name, ignoreCase = true) }
        return if (i < 0) list + clean else list.toMutableList().also { it[i] = clean }
    }

    /** Renames [old] to [new]; fails when [new] is blank or already used by another profile. */
    fun rename(list: List<SecurityProfile>, old: String, new: String): List<SecurityProfile> {
        val n = new.trim()
        require(n.isNotEmpty()) { "empty name" }
        require(list.none { it.name.equals(n, true) && !it.name.equals(old, true) }) { "name in use" }
        return list.map { if (it.name.equals(old, true)) it.copy(name = n) else it }
    }

    fun delete(list: List<SecurityProfile>, name: String) = list.filterNot { it.name.equals(name, true) }

    suspend fun load(context: Context): List<SecurityProfile> = withContext(Dispatchers.IO) {
        val f = file(context)
        if (!f.exists()) emptyList() else runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
    }

    /** Writes through a temporary file so a crash never leaves a half-written list. */
    suspend fun save(context: Context, list: List<SecurityProfile>) = withContext(Dispatchers.IO) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(toJson(list))
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() } }
    }
}
