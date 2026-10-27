package com.smartdocs.editor.core.security

import android.content.Context
import androidx.core.content.edit

/** "Manage Internet Access": what a PDF may do when it wants to open a web address. */
enum class NetMode { ALLOW_ALL, BLOCK_ALL, ASK }

enum class NetDecision { ALLOW, BLOCK, ASK }

/**
 * Pure decision logic for addresses opened by a document (URI links, form buttons, form submission). Everything
 * except a hand-over to the mail / phone / SMS app counts as Internet access, including bare "example.com:8080"
 * (the reader opens it as https) and app schemes. Trusted sites match the host exactly or as a parent domain
 * ("example.com" trusts "www.example.com").
 */
object InternetAccess {
    private val HANDOVER = setOf("mailto", "tel", "sms")

    /** Scheme of [url] in lower case ("" when there is none). "host:8080" gives "host": see [isWeb]. */
    fun scheme(url: String): String = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*):""").find(url.trim())?.groupValues?.get(1)?.lowercase().orEmpty()

    fun isWeb(url: String): Boolean = scheme(url) !in HANDOVER

    /** Host of a web address in lower case; null when it has none (or is a mail / phone hand-over). */
    fun hostOf(url: String): String? {
        var s = url.trim()
        if (s.isEmpty() || !isWeb(s)) return null
        if (s.contains("://")) s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        s = if (s.startsWith("[")) s.substringBefore(']') + "]" else s.substringBefore(':')
        return normalizeHost(s)
    }

    /** User input of the trusted-site list ("https://Example.com/x", "example.com.") reduced to a host; null if invalid. */
    fun normalizeHost(input: String): String? {
        var s = input.trim().lowercase()
        if (s.contains("://")) s = s.substringAfter("://")
        s = s.substringBefore('/').substringAfterLast('@').trimEnd('.')
        if (!s.startsWith("[")) s = s.substringBefore(':')
        if (s.isEmpty() || s.length > 253) return null
        if (s.startsWith("[")) return s.takeIf { it.endsWith("]") && it.length > 2 }
        if (!Regex("""^[a-z0-9\-._~%]+$""").matches(s) || s.startsWith('.') || ".." in s) return null
        return s
    }

    fun trusted(host: String, sites: Collection<String>): Boolean =
        sites.any { t -> host == t || host.endsWith(".$t") }

    fun decide(mode: NetMode, sites: Collection<String>, url: String): NetDecision {
        if (!isWeb(url)) return NetDecision.ALLOW
        return when (mode) {
            NetMode.ALLOW_ALL -> NetDecision.ALLOW
            NetMode.BLOCK_ALL -> NetDecision.BLOCK
            NetMode.ASK -> {
                val h = hostOf(url) ?: return NetDecision.ASK
                if (trusted(h, sites)) NetDecision.ALLOW else NetDecision.ASK
            }
        }
    }
}

enum class LaunchVerdict { ALLOWED_WITH_WARNING, BLOCKED_BY_SETTING, BLOCKED_EXECUTABLE }

/** File-launch actions (Launch / GoToR): always confirmed, never for executable-like files, or all off. */
object LaunchPolicy {
    val EXECUTABLE = setOf("exe", "bat", "cmd", "com", "scr", "msi", "js", "vbs", "ps1", "apk", "jar", "jse", "vbe", "wsf", "pif", "cpl", "lnk", "sh")

    fun isExecutable(name: String): Boolean = name.trim().trimEnd('.', ' ').substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase() in EXECUTABLE

    fun check(target: String, blockAll: Boolean): LaunchVerdict = when {
        isExecutable(target) -> LaunchVerdict.BLOCKED_EXECUTABLE
        blockAll -> LaunchVerdict.BLOCKED_BY_SETTING
        else -> LaunchVerdict.ALLOWED_WITH_WARNING
    }
}

/**
 * Document-security settings read synchronously at the moment a link fires (SharedPreferences): Internet access
 * mode, trusted sites, block launch actions. The "run JavaScript" switch lives in SettingsStore ([JsPolicy]).
 */
object SecurityPrefs {
    private const val FILE = "np_doc_security"
    private const val NET_MODE = "net_mode"
    private const val SITES = "trusted_sites"
    private const val BLOCK_LAUNCH = "block_launch"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun netMode(c: Context): NetMode = p(c).getString(NET_MODE, null)?.let { m -> NetMode.values().firstOrNull { it.name == m } } ?: NetMode.ASK
    fun setNetMode(c: Context, m: NetMode) = p(c).edit { putString(NET_MODE, m.name) }

    fun sites(c: Context): List<String> = p(c).getStringSet(SITES, emptySet()).orEmpty().sorted()
    fun addSite(c: Context, input: String): Boolean {
        val h = InternetAccess.normalizeHost(input) ?: return false
        p(c).edit { putStringSet(SITES, sites(c).toSet() + h) }
        return true
    }
    fun removeSite(c: Context, host: String) = p(c).edit { putStringSet(SITES, sites(c).toSet() - host) }

    fun blockLaunch(c: Context): Boolean = p(c).getBoolean(BLOCK_LAUNCH, false)
    fun setBlockLaunch(c: Context, v: Boolean) = p(c).edit { putBoolean(BLOCK_LAUNCH, v) }
}

/**
 * Process-wide gate of document JavaScript, mirrored from the "Run JavaScript in PDF documents" setting. Every
 * script entry point checks it: field scripts on the Rhino runtime ([com.smartdocs.editor.core.forms5.FormScripts]),
 * the pattern-based AF emulation ([com.smartdocs.editor.core.forms.FormCalc]), validation, JavaScript button
 * actions and XFA scripts ([com.smartdocs.editor.core.forms.XfaJs]).
 */
object JsPolicy {
    @Volatile var enabled: Boolean = true
}
