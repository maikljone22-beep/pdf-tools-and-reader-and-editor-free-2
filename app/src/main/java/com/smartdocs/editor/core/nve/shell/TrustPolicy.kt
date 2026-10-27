package com.smartdocs.editor.core.nve.shell

/** Safe reading: trust decision for links and document JavaScript (Foxit "Trust Manager" / Nitro trust prompt). */
object TrustPolicy {
    enum class Mode { ASK, ALWAYS, NEVER }
    enum class Decision { ALLOW, ASK, BLOCK }

    /** Host of a link ("mailto:"/"tel:" use the scheme as host); null when not parseable. */
    fun hostOf(raw: String): String? {
        val t = raw.trim()
        if (t.startsWith("mailto:", true) || t.startsWith("tel:", true)) return t.substringBefore(':').lowercase()
        val withScheme = if (t.contains("://")) t else "https://$t"
        val rest = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return rest.substringAfterLast('@').substringBefore(':').lowercase().removePrefix("www.").ifBlank { null }
    }

    /** A trusted host also trusts its subdomains ("example.com" trusts "docs.example.com"). */
    fun isTrusted(host: String, trusted: Set<String>): Boolean = trusted.any { t -> host == t || host.endsWith(".$t") }

    fun link(raw: String, trusted: Set<String>, mode: Mode): Decision = when (mode) {
        Mode.ALWAYS -> Decision.ALLOW
        Mode.NEVER -> hostOf(raw)?.let { if (isTrusted(it, trusted)) Decision.ALLOW else Decision.BLOCK } ?: Decision.BLOCK
        Mode.ASK -> hostOf(raw)?.let { if (isTrusted(it, trusted)) Decision.ALLOW else Decision.ASK } ?: Decision.ASK
    }

    fun script(docKey: String, trustedDocs: Set<String>, mode: Mode): Decision = when {
        docKey in trustedDocs -> Decision.ALLOW
        mode == Mode.ALWAYS -> Decision.ALLOW
        mode == Mode.NEVER -> Decision.BLOCK
        else -> Decision.ASK
    }
}
