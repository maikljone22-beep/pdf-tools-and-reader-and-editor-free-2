package com.smartdocs.editor.core.webdav

import android.content.Context
import com.smartdocs.editor.core.sign.SecretBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A WebDAV "place" (Nitro "Add Place"): a label, the folder URL, a user name and the password, which is stored
 * encrypted with an AndroidKeyStore AES-256-GCM key ([SecretBox]) and never logged.
 */
data class DavPlace(val id: String, val label: String, val url: String, val user: String, val secret: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("url", url).put("user", user).put("secret", secret)

    companion object {
        fun from(o: JSONObject) = DavPlace(o.getString("id"), o.optString("label"), o.getString("url"), o.optString("user"), o.optString("secret"))
    }
}

/** Saved WebDAV places (SharedPreferences "webdav_places"). */
object DavPlaces {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("webdav_places", Context.MODE_PRIVATE)

    fun all(c: Context): List<DavPlace> = runCatching {
        val a = JSONArray(prefs(c).getString("list", "[]"))
        (0 until a.length()).mapNotNull { runCatching { DavPlace.from(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun get(c: Context, id: String): DavPlace? = all(c).firstOrNull { it.id == id }

    private fun write(c: Context, list: List<DavPlace>) =
        prefs(c).edit().putString("list", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()

    /**
     * Adds or updates a place. [password] null keeps the stored one (editing without retyping it); "" clears it.
     * Returns the saved place.
     */
    fun save(c: Context, id: String?, label: String, url: String, user: String, password: String?): DavPlace {
        val old = id?.let { get(c, it) }
        val secret = when {
            password == null -> old?.secret.orEmpty()
            password.isEmpty() -> ""
            else -> SecretBox.encrypt(password)
        }
        val p = DavPlace(old?.id ?: UUID.randomUUID().toString(), label.ifBlank { DavXml.nameOf(url) }, url, user, secret)
        write(c, all(c).filterNot { it.id == p.id } + p)
        return p
    }

    fun remove(c: Context, id: String) {
        write(c, all(c).filterNot { it.id == id })
        DavLinks.forgetPlace(c, id)
    }

    /** Decrypted password ("" when none or when it cannot be decrypted, e.g. restored on another device). */
    fun password(p: DavPlace): String = if (p.secret.isEmpty()) "" else SecretBox.decrypt(p.secret).orEmpty()

    /** Local folder of the working copies of [p]: <docs>/Cloud/webdav/<id>/ */
    fun cacheDir(c: Context, p: DavPlace): File {
        val docs = File(c.getExternalFilesDir(null) ?: c.filesDir, "Documents")
        return File(docs, "Cloud/webdav/${p.id.take(8)}").apply { mkdirs() }
    }
}
