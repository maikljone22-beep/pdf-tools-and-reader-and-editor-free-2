package com.smartdocs.editor.core.pdf

import android.content.Context

/**
 * Which attachment types may be opened in other apps (Foxit "attachment security"): a per-extension rule set by
 * the user, plus a built-in list of risky types that always ask first. PDFs open in the app itself and never ask.
 */
object AttachmentPolicy {

    enum class Rule { ALLOW, NEVER }

    /** What to do when the user opens an attachment. */
    enum class Decision { OPEN_HERE, OPEN, ASK, ASK_RISKY, BLOCK }

    /** Types that can run code or carry scripts: always asked about unless the user allowed the type. */
    val RISKY: Set<String> = setOf(
        "apk", "aab", "xapk", "exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "vbe", "js", "jse", "wsf", "jar", "sh",
        "html", "htm", "xhtml", "svg", "hta", "lnk", "reg", "dll", "docm", "xlsm", "pptm", "dotm", "xltm",
    )

    fun extOf(name: String): String = name.substringAfterLast('.', "").trim().lowercase()

    /** Pure decision for a file [name] under the user [rules] (extension → rule). */
    fun decide(name: String, rules: Map<String, Rule>): Decision {
        val ext = extOf(name)
        if (ext == "pdf") return Decision.OPEN_HERE
        return when (rules[ext]) {
            Rule.ALLOW -> Decision.OPEN
            Rule.NEVER -> Decision.BLOCK
            null -> if (ext in RISKY || ext.isEmpty()) Decision.ASK_RISKY else Decision.ASK
        }
    }

    /** Normalises a user-typed type (".PDF ", "*.docx") to a bare extension, or null when it is not one. */
    fun normalize(input: String): String? {
        val e = input.trim().removePrefix("*").removePrefix(".").lowercase()
        return e.takeIf { it.isNotEmpty() && it.length <= 12 && it.all { c -> c.isLetterOrDigit() } }
    }

    fun encode(rules: Map<String, Rule>): String = rules.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.name}" }

    fun decode(s: String?): Map<String, Rule> = s.orEmpty().split(',').mapNotNull { p ->
        val k = p.substringBefore('=', "").trim(); val v = p.substringAfter('=', "").trim()
        val r = runCatching { Rule.valueOf(v) }.getOrNull() ?: return@mapNotNull null
        normalize(k)?.let { it to r }
    }.toMap()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("attachment_policy", Context.MODE_PRIVATE)

    fun rules(c: Context): Map<String, Rule> = decode(prefs(c).getString("rules", ""))

    fun setRule(c: Context, ext: String, rule: Rule?) {
        val e = normalize(ext) ?: return
        val m = rules(c).toMutableMap()
        if (rule == null) m.remove(e) else m[e] = rule
        prefs(c).edit().putString("rules", encode(m)).apply()
    }

    fun decide(c: Context, name: String): Decision = decide(name, rules(c))
}
