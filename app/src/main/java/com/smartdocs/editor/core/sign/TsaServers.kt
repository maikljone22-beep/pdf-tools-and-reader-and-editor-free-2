package com.smartdocs.editor.core.sign

import android.content.Context
import com.smartdocs.editor.R
import java.security.KeyStore
import java.security.cert.X509Certificate

/** One saved RFC 3161 time-stamp server. The password is not part of it: it is kept encrypted (see [TsaServers.setPassword]). */
data class TsaServer(
    val id: String,
    val name: String,
    val url: String,
    val username: String = "",
    /** Use this server only when its signer certificate chains to a trusted anchor ("attestable server"). */
    val requireTrusted: Boolean = false,
    /** One of [TsaClient.presets]: can be edited (login, trust option) but not deleted, and its URL is fixed. */
    val builtIn: Boolean = false,
)

/**
 * Saved time-stamp servers (Nitro's list / new / edit / delete / default). Built-in presets are always present.
 * List + default id in "psf_sign"; passwords encrypted with [SecretBox] in "tsa_secrets" (excluded from backups).
 * [install] connects the list to [TsaClient] so every time-stamp request to a saved URL uses its login and trust option.
 */
object TsaServers {
    private const val PREFS = "psf_sign"
    private const val SECRETS = "tsa_secrets"
    private const val KEY_LIST = "nq_tsa_servers"
    private const val KEY_DEFAULT = "nq_tsa_default"

    // ---------- pure ----------

    fun builtIns(): List<TsaServer> = TsaClient.presets.map { u ->
        TsaServer(id = "builtin:$u", name = hostOf(u), url = u, builtIn = true)
    }

    fun hostOf(url: String): String = url.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').ifBlank { url.trim() }

    fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()

    /** Stored list with every built-in present (missing ones appended, built-in URLs/names restored). */
    fun merge(stored: List<TsaServer>): List<TsaServer> {
        val b = builtIns().associateBy { it.id }
        val kept = stored.mapNotNull { s ->
            if (s.id.startsWith("builtin:")) b[s.id]?.let { base -> s.copy(name = base.name, url = base.url, builtIn = true) } else s.copy(builtIn = false)
        }.distinctBy { it.id }
        return kept + b.values.filter { bi -> kept.none { it.id == bi.id } }
    }

    fun encode(list: List<TsaServer>): String = org.json.JSONArray().apply {
        list.forEach { s ->
            put(org.json.JSONObject().put("id", s.id).put("name", s.name).put("url", s.url).put("user", s.username).put("trusted", s.requireTrusted))
        }
    }.toString()

    fun decode(json: String?): List<TsaServer> {
        val arr = json?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: return merge(emptyList())
        return merge((0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            if (url.isEmpty()) return@mapNotNull null
            TsaServer(o.optString("id").ifBlank { "u:$url" }, o.optString("name").ifBlank { url }, url, o.optString("user"), o.optBoolean("trusted"))
        })
    }

    /** Adds or replaces (same id) a user server; a built-in keeps its URL. */
    fun upsert(list: List<TsaServer>, s: TsaServer): List<TsaServer> {
        val i = list.indexOfFirst { it.id == s.id }
        if (i < 0) return list + s.copy(builtIn = false)
        val old = list[i]
        val fixed = if (old.builtIn) s.copy(url = old.url, name = old.name, builtIn = true) else s.copy(builtIn = false)
        return list.toMutableList().also { it[i] = fixed }
    }

    /** Removes a user server; built-ins cannot be deleted. */
    fun delete(list: List<TsaServer>, id: String): List<TsaServer> = list.filterNot { it.id == id && !it.builtIn }

    fun find(list: List<TsaServer>, url: String): TsaServer? = normalize(url).let { n -> list.firstOrNull { normalize(it.url) == n } }

    fun newId(): String = "u:" + java.util.UUID.randomUUID().toString()

    // ---------- storage ----------

    fun load(context: Context): List<TsaServer> =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null))

    fun save(context: Context, list: List<TsaServer>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LIST, encode(list)).apply()
        // drop the passwords of deleted servers
        val sp = context.getSharedPreferences(SECRETS, Context.MODE_PRIVATE)
        val ids = list.map { it.id }.toSet()
        val gone = sp.all.keys.filter { it !in ids }
        if (gone.isNotEmpty()) { val e = sp.edit(); gone.forEach { e.remove(it) }; e.apply() }
    }

    fun defaultId(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT, null)

    fun setDefault(context: Context, id: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT, id).apply()
    }

    fun hasPassword(context: Context, id: String): Boolean = context.getSharedPreferences(SECRETS, Context.MODE_PRIVATE).contains(id)

    /** Stores [password] encrypted; null or empty removes it. */
    fun setPassword(context: Context, id: String, password: String?) {
        val sp = context.getSharedPreferences(SECRETS, Context.MODE_PRIVATE)
        if (password.isNullOrEmpty()) sp.edit().remove(id).apply()
        else sp.edit().putString(id, SecretBox.encrypt(password)).apply()
    }

    private fun password(context: Context, id: String): String? =
        context.getSharedPreferences(SECRETS, Context.MODE_PRIVATE).getString(id, null)?.let { SecretBox.decrypt(it) }

    /** Android system CA store (plus user-installed CAs), loaded once. */
    private val systemCas: List<X509Certificate> by lazy {
        runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            ks.aliases().toList().mapNotNull { ks.getCertificate(it) as? X509Certificate }
        }.getOrDefault(emptyList())
    }

    fun policyFor(context: Context, url: String): TsaClient.Policy? {
        val s = find(load(context), url) ?: return null
        if (s.username.isBlank() && !s.requireTrusted) return null
        return TsaClient.Policy(
            username = s.username.takeIf { it.isNotBlank() },
            password = if (s.username.isNotBlank()) password(context, s.id) else null,
            requireTrusted = s.requireTrusted,
            anchors = { TrustedCerts.certificates(context) + systemCas },
            untrustedMessage = context.getString(R.string.nq_tsa_untrusted, s.name),
        )
    }

    /** Lets [TsaClient] find the login / trust option of a saved server by URL (idempotent). */
    fun install(context: Context) {
        val app = context.applicationContext
        TsaClient.policyResolver = { url -> policyFor(app, url) }
    }
}
