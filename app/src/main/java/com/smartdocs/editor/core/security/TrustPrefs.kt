package com.smartdocs.editor.core.security

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.security.MessageDigest

/**
 * Trust Manager settings (nitro-protect2), read synchronously where a decision is made: Safe Reading Mode,
 * privileged locations, Protected View, JavaScript run level, per-document trust and attachment rules.
 */
object TrustPrefs {
    private const val FILE = "np2_trust"
    private const val SAFE = "safe_mode"
    private const val FOLDERS = "priv_folders"
    private const val HOSTS = "priv_hosts"
    private const val APP_STORAGE = "priv_app"
    private const val PV_MODE = "pv_mode"
    private const val PV_ENABLED = "pv_enabled"
    private const val JS_LEVEL = "js_level"
    private const val JS_TRUSTED = "js_trusted"
    private const val JS_BLOCKED = "js_blocked"
    private const val ATTACH = "attach_rules"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun set(c: Context, key: String): Set<String> = p(c).getStringSet(key, emptySet()).orEmpty()
    private fun put(c: Context, key: String, v: Set<String>) = p(c).edit { putStringSet(key, v) }

    // ---- Safe Reading Mode + privileged locations
    fun safeMode(c: Context): Boolean = p(c).getBoolean(SAFE, false)
    fun setSafeMode(c: Context, v: Boolean) = p(c).edit { putBoolean(SAFE, v) }

    fun privileged(c: Context): Privileged = Privileged(set(c, FOLDERS).sorted(), set(c, HOSTS).sorted(), p(c).getBoolean(APP_STORAGE, true))
    fun setAppStoragePrivileged(c: Context, v: Boolean) = p(c).edit { putBoolean(APP_STORAGE, v) }
    fun addFolder(c: Context, input: String): Boolean { val f = Privileged.normalizeFolder(input) ?: return false; put(c, FOLDERS, set(c, FOLDERS) + f); return true }
    fun removeFolder(c: Context, f: String) = put(c, FOLDERS, set(c, FOLDERS) - f)
    fun addHost(c: Context, input: String): Boolean { val h = InternetAccess.normalizeHost(input) ?: return false; put(c, HOSTS, set(c, HOSTS) + h); return true }
    fun removeHost(c: Context, h: String) = put(c, HOSTS, set(c, HOSTS) - h)

    // ---- Protected View
    fun pvMode(c: Context): PvMode = p(c).getString(PV_MODE, null)?.let { m -> PvMode.values().firstOrNull { it.name == m } } ?: PvMode.OFF
    fun setPvMode(c: Context, m: PvMode) = p(c).edit { putString(PV_MODE, m.name) }
    fun editingEnabled(c: Context, path: String): Boolean = path in set(c, PV_ENABLED)
    fun enableEditing(c: Context, path: String) = put(c, PV_ENABLED, (set(c, PV_ENABLED) + path).toList().takeLast(500).toSet())

    // ---- JavaScript run level and per-document decisions (by SHA-256 of the file)
    /** The level; before it was ever chosen, the old on/off switch decides ([legacyOn]). */
    fun jsLevel(c: Context, legacyOn: Boolean = JsPolicy.enabled): JsLevel =
        p(c).getString(JS_LEVEL, null)?.let { m -> JsLevel.values().firstOrNull { it.name == m } } ?: if (legacyOn) JsLevel.ON else JsLevel.OFF
    fun setJsLevel(c: Context, l: JsLevel) = p(c).edit { putString(JS_LEVEL, l.name) }
    fun isTrusted(c: Context, hash: String): Boolean = hash in set(c, JS_TRUSTED)
    fun answer(c: Context, hash: String): JsAnswer? = when (hash) {
        in set(c, JS_BLOCKED) -> JsAnswer.BLOCK
        in set(c, JS_TRUSTED) -> JsAnswer.ALLOW
        else -> null
    }
    fun setAnswer(c: Context, hash: String, a: JsAnswer) {
        if (a == JsAnswer.ALLOW) { put(c, JS_TRUSTED, set(c, JS_TRUSTED) + hash); put(c, JS_BLOCKED, set(c, JS_BLOCKED) - hash) }
        else { put(c, JS_BLOCKED, set(c, JS_BLOCKED) + hash); put(c, JS_TRUSTED, set(c, JS_TRUSTED) - hash) }
    }
    /** A trusted document saved by the app keeps its trust under the new content hash. */
    fun carryTrust(c: Context, oldHash: String, newHash: String) { if (oldHash != newHash && isTrusted(c, oldHash)) put(c, JS_TRUSTED, set(c, JS_TRUSTED) + newHash) }
    fun decisionCount(c: Context): Int = set(c, JS_TRUSTED).size + set(c, JS_BLOCKED).size
    fun clearDecisions(c: Context) = p(c).edit { remove(JS_TRUSTED); remove(JS_BLOCKED) }

    // ---- attachments
    fun attachRules(c: Context): Map<String, AttachRule> = AttachmentPolicy.decode(p(c).getString(ATTACH, null)) ?: AttachmentPolicy.defaults()
    fun setAttachRules(c: Context, rules: Map<String, AttachRule>) = p(c).edit { putString(ATTACH, AttachmentPolicy.encode(rules)) }
    fun restoreAttachDefaults(c: Context) = p(c).edit { remove(ATTACH) }

    /** SHA-256 of a file's content, hex. */
    fun hashOf(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * The document the reader shows now, as seen by link / submit / launch decisions that only get a Context
 * ([com.smartdocs.editor.ui.security.DocSecurityGate]). Set by the reader when a document opens or comes back to
 * the front; without one, Safe Reading Mode applies (the stricter choice).
 */
object DocContext {
    data class Current(val path: String, val privileged: Boolean, val actionsBlocked: Boolean)
    @Volatile var current: Current? = null

    fun clear(path: String) { if (current?.path == path) current = null }
}
