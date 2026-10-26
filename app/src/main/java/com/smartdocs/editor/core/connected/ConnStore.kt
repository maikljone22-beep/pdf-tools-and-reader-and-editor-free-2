package com.smartdocs.editor.core.connected

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A connected document I registered (owner key stays on this device). */
data class OwnedDoc(val id: String, val title: String, val ownerKey: String, val protected: Boolean, val localPath: String, val created: Long, val lastVersion: Int = 1) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("key", ownerKey).put("prot", protected).put("path", localPath)
        .put("created", created).put("v", lastVersion)
    companion object {
        fun from(o: JSONObject) = OwnedDoc(o.getString("id"), o.optString("title"), o.getString("key"), o.optBoolean("prot"), o.optString("path"), o.optLong("created"), o.optInt("v", 1))
    }
}

object ConnStore {
    private val _owned = MutableStateFlow<List<OwnedDoc>>(emptyList())
    val owned: StateFlow<List<OwnedDoc>> = _owned
    @Volatile private var loaded = false
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("connected_docs", Context.MODE_PRIVATE)

    fun load(c: Context): List<OwnedDoc> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val a = runCatching { JSONArray(prefs(c).getString("owned", "[]")) }.getOrDefault(JSONArray())
                _owned.value = (0 until a.length()).mapNotNull { runCatching { OwnedDoc.from(a.getJSONObject(it)) }.getOrNull() }
                loaded = true
            }
        }
        return _owned.value
    }

    private fun save(c: Context, l: List<OwnedDoc>) { _owned.value = l; prefs(c).edit().putString("owned", JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()).apply() }
    fun upsert(c: Context, d: OwnedDoc) = synchronized(this) { save(c, load(c).filterNot { it.id == d.id } + d) }
    fun remove(c: Context, id: String) = synchronized(this) { save(c, load(c).filterNot { it.id == id }); ConnKeyCache.remove(c, id) }
    fun get(c: Context, id: String) = load(c).firstOrNull { it.id == id }

    /** Random per-install reader id (a new one after clearing app data). */
    fun readerId(c: Context): String = prefs(c).getString("rid", null) ?: UUID.randomUUID().toString().also { prefs(c).edit().putString("rid", it).apply() }

    /** Last status seen per document (for the banner when offline). */
    fun setLastStatus(c: Context, id: String, status: String) = prefs(c).edit().putString("st_$id", status).apply()
    fun lastStatus(c: Context, id: String): String? = prefs(c).getString("st_$id", null)
}

/**
 * Offline document keys, encrypted with an AES-GCM key from the Android Keystore (never leaves the secure hardware
 * where available). Each entry has an expiry: after it, the reader must reach the server again.
 */
object ConnKeyCache {
    private const val ALIAS = "smartdocs_conn_keys"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("connected_keys", Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    /** [expiresAt] = 0 means no expiry (owner copies). */
    fun put(c: Context, docId: String, key: String, expiresAt: Long) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
            val ct = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            val o = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).put("ct", Base64.encodeToString(ct, Base64.NO_WRAP)).put("exp", expiresAt)
            prefs(c).edit().putString(docId, o.toString()).apply()
        }
    }

    /** The cached key, or null when missing/expired/undecryptable. */
    fun get(c: Context, docId: String, now: Long = System.currentTimeMillis()): String? = runCatching {
        val o = JSONObject(prefs(c).getString(docId, null) ?: return null)
        val exp = o.optLong("exp")
        if (exp in 1 until now) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(o.getString("iv"), Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(o.getString("ct"), Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun expiry(c: Context, docId: String): Long = runCatching { JSONObject(prefs(c).getString(docId, null) ?: return 0L).optLong("exp") }.getOrDefault(0L)

    fun remove(c: Context, docId: String) = prefs(c).edit().remove(docId).apply()
}
