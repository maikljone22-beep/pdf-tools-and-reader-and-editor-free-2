package com.smartdocs.editor.core.sign

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Named digital-signature profiles (Nitro "signature profiles": create, edit, duplicate, delete): the signer
 * fields, visibility, certification and lock settings of the sign tab. The digital ID itself is not stored
 * (its password is asked each time). Kept as JSON in `filesDir/sign_profiles.json`.
 */
object SignProfiles {

    data class Profile(
        val name: String,
        val signerName: String = "",
        val location: String = "",
        val reason: String = "",
        val contact: String = "",
        val visible: Boolean = false,
        val certify: Int = 0,
        val pades: Boolean = true,
        val timestamp: Boolean = false,
        val ltv: Boolean = false,
        /** null = no lock; otherwise the FieldMDP action name (ALL / INCLUDE / EXCLUDE). */
        val lock: SigFields.LockAction? = null,
        val lockFields: List<String> = emptyList(),
        val lockNoChanges: Boolean = false,
    )

    fun toJson(list: List<Profile>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().apply {
                put("name", p.name); put("signer", p.signerName); put("location", p.location); put("reason", p.reason)
                put("contact", p.contact); put("visible", p.visible); put("certify", p.certify); put("pades", p.pades)
                put("tsa", p.timestamp); put("ltv", p.ltv); p.lock?.let { put("lock", it.name) }
                put("lockFields", JSONArray(p.lockFields)); put("lockNoChanges", p.lockNoChanges)
            })
        }
    }.toString()

    fun fromJson(text: String): List<Profile> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Profile(
                name = name, signerName = o.optString("signer"), location = o.optString("location"), reason = o.optString("reason"),
                contact = o.optString("contact"), visible = o.optBoolean("visible"), certify = o.optInt("certify").coerceIn(0, 3),
                pades = o.optBoolean("pades", true), timestamp = o.optBoolean("tsa"), ltv = o.optBoolean("ltv"),
                lock = o.optString("lock").takeIf { it.isNotBlank() }?.let { n -> SigFields.LockAction.entries.firstOrNull { it.name == n } },
                lockFields = o.optJSONArray("lockFields")?.let { f -> (0 until f.length()).map { f.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
                lockNoChanges = o.optBoolean("lockNoChanges"),
            )
        }
    }.getOrDefault(emptyList())

    /** Adds or replaces (same name, case-insensitive) and keeps the list sorted by name. */
    fun upsert(list: List<Profile>, p: Profile): List<Profile> =
        (list.filterNot { it.name.equals(p.name, true) } + p).sortedBy { it.name.lowercase() }

    /** "Name (2)", "Name (3)" … for a duplicate. */
    fun copyName(list: List<Profile>, base: String): String {
        val names = list.map { it.name.lowercase() }.toSet()
        var i = 2
        while ("$base ($i)".lowercase() in names) i++
        return "$base ($i)"
    }

    fun load(file: File): List<Profile> = if (file.exists()) fromJson(file.readText()) else emptyList()

    fun save(file: File, list: List<Profile>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(list))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
}
