package com.smartdocs.editor.core.security

import java.net.URLDecoder

/** Where an open document came from (Protected View and privileged locations). */
enum class OriginKind { APP_STORAGE, LOCAL, DOWNLOADS, OTHER_APP, EMAIL, WEB }

/** [localPath]: the file the reader opened; [source]: the URI it was imported from (null = opened in place). */
data class DocOrigin(val kind: OriginKind, val localPath: String, val source: String? = null) {
    /** Unsafe locations of Acrobat's Protected View: downloads, other apps' shares, e-mail attachments, the web. */
    val unsafe: Boolean get() = kind in UNSAFE

    companion object { val UNSAFE = setOf(OriginKind.DOWNLOADS, OriginKind.OTHER_APP, OriginKind.EMAIL, OriginKind.WEB) }
}

/**
 * Classification of a document's origin. [appDirs] are the app's own storage folders; [ownAuthorities] the app's
 * content-provider authorities (a file shared from the app itself is app storage).
 */
object DocOrigins {
    /** Content-provider authority prefixes of common e-mail apps (attachments). */
    val EMAIL = listOf(
        "com.google.android.gm", "com.android.email", "com.samsung.android.email", "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail", "ch.protonmail", "me.proton.android.mail", "com.fsck.k9", "net.thunderbird",
        "org.kman.aquamail", "com.easilydo.mail", "ru.mail.mailapp", "com.yandex.mail", "com.readdle.spark", "com.edison.mail",
    )
    private const val EXTERNAL = "com.android.externalstorage.documents"
    private const val PRIMARY = "/storage/emulated/0/"

    fun classify(localPath: String, source: String?, appDirs: List<String>, ownAuthorities: List<String>): DocOrigin {
        val src = source?.trim()?.takeIf { it.isNotEmpty() }
        val scheme = src?.substringBefore(':', "")?.lowercase().orEmpty()
        val kind = when {
            src == null || scheme == "file" && pathOf(src) == localPath -> byPath(localPath, appDirs)
            scheme == "http" || scheme == "https" -> OriginKind.WEB
            scheme == "file" -> byPath(pathOf(src) ?: localPath, appDirs).let { if (it == OriginKind.APP_STORAGE) OriginKind.LOCAL else it }
            scheme == "content" -> {
                val auth = authority(src).lowercase()
                val decoded = decode(src).lowercase()
                when {
                    ownAuthorities.any { it.equals(auth, true) } -> byPath(localPath, appDirs)
                    EMAIL.any { auth == it || auth.startsWith("$it.") } -> OriginKind.EMAIL
                    auth.startsWith("com.android.providers.downloads") || "/download/" in decoded || ":download/" in decoded || decoded.endsWith(":download") -> OriginKind.DOWNLOADS
                    auth == EXTERNAL || auth == "com.android.providers.media.documents" || auth == "media" -> OriginKind.LOCAL
                    else -> OriginKind.OTHER_APP
                }
            }
            else -> OriginKind.OTHER_APP
        }
        return DocOrigin(kind, localPath, src)
    }

    private fun byPath(path: String, appDirs: List<String>): OriginKind = when {
        appDirs.any { under(path, it) } -> OriginKind.APP_STORAGE
        "/download/" in path.lowercase() -> OriginKind.DOWNLOADS
        else -> OriginKind.LOCAL
    }

    fun authority(uri: String): String = uri.substringAfter("://", "").substringBefore('/').substringBefore('?')

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /**
     * File-system path of a source URI when it can be known: `file://` paths and documents / trees of the system
     * storage provider ("primary:Documents/x.pdf" → "/storage/emulated/0/Documents/x.pdf"). Null otherwise.
     */
    fun pathOf(uri: String): String? {
        val s = uri.trim()
        if (s.startsWith("/")) return s
        if (s.startsWith("file://")) return decode(s.removePrefix("file://")).let { if (it.startsWith("/")) it else "/$it" }
        if (!s.startsWith("content://") || authority(s) != EXTERNAL) return null
        val tail = s.substringAfter(EXTERNAL)
        val id = decode(tail.substringAfterLast("/document/", tail.substringAfterLast("/tree/", "")))
        if (id.isEmpty() || ':' !in id) return null
        val vol = id.substringBefore(':'); val rel = id.substringAfter(':').trim('/')
        val base = if (vol.equals("primary", true)) PRIMARY else "/storage/$vol/"
        return (base + rel).trimEnd('/')
    }

    /** True when [path] is [folder] or lies below it (case-insensitive, trailing slashes ignored). */
    fun under(path: String, folder: String): Boolean {
        val p = path.trimEnd('/').lowercase(); val f = folder.trimEnd('/').lowercase()
        return f.isNotEmpty() && (p == f || p.startsWith("$f/"))
    }
}

/** Privileged locations (Trust Manager): folders, hosts and the app's own storage. */
data class Privileged(val folders: List<String> = emptyList(), val hosts: List<String> = emptyList(), val appStorage: Boolean = true) {
    fun covers(o: DocOrigin): Boolean {
        if (appStorage && o.kind == OriginKind.APP_STORAGE) return true
        val sourcePath = o.source?.let { DocOrigins.pathOf(it) }
        if (folders.any { f -> DocOrigins.under(o.localPath, f) || (sourcePath != null && DocOrigins.under(sourcePath, f)) }) return true
        if (o.kind == OriginKind.WEB) {
            val h = InternetAccess.hostOf(o.source.orEmpty()) ?: return false
            return InternetAccess.trusted(h, hosts)
        }
        return false
    }

    /** True when a document may still reach [url] in Safe Reading Mode (its host is a privileged host). */
    fun allowsHost(url: String): Boolean = InternetAccess.hostOf(url)?.let { InternetAccess.trusted(it, hosts) } == true

    companion object {
        /** A folder entry typed or picked by the user, as a path; null when it is not a usable folder. */
        fun normalizeFolder(input: String): String? {
            val s = input.trim()
            val p = DocOrigins.pathOf(s) ?: return null
            return p.trimEnd('/').takeIf { it.length > 1 && ".." !in it.split('/') }
        }
    }
}

/**
 * Safe Reading Mode: one switch that stops every action that leaves the document (web links, form submission,
 * file launch, JavaScript) unless the document is in a privileged location. A web address on a privileged host
 * still follows the normal Internet-access policy.
 */
object SafeReading {
    fun active(safeMode: Boolean, privileged: Boolean): Boolean = safeMode && !privileged

    /** Decision for [url] opened by a document: [active] safe mode or [actionsBlocked] ("Keep blocked") first. */
    fun web(active: Boolean, actionsBlocked: Boolean, priv: Privileged, mode: NetMode, sites: Collection<String>, url: String): NetDecision {
        if (!InternetAccess.isWeb(url)) return NetDecision.ALLOW
        if ((active || actionsBlocked) && !priv.allowsHost(url)) return NetDecision.BLOCK
        return InternetAccess.decide(mode, sites, url)
    }

    fun launch(active: Boolean, actionsBlocked: Boolean, target: String, blockAll: Boolean): LaunchVerdict = when {
        LaunchPolicy.isExecutable(target) -> LaunchVerdict.BLOCKED_EXECUTABLE
        active || actionsBlocked -> LaunchVerdict.BLOCKED_SAFE_MODE
        else -> LaunchPolicy.check(target, blockAll)
    }
}

/** Protected View setting: off, files from unsafe locations, all files. */
enum class PvMode { OFF, UNSAFE, ALL }

object ProtectedView {
    /** Read-only Protected View for [o] until the user enables editing for that file. */
    fun applies(mode: PvMode, o: DocOrigin, privileged: Boolean, editingEnabled: Boolean): Boolean = when {
        mode == PvMode.OFF || privileged || editingEnabled -> false
        mode == PvMode.ALL -> true
        else -> o.unsafe
    }
}

/** JavaScript run levels ("Run Script Right Control"). */
enum class JsLevel { OFF, ASK, TRUSTED, ON }

/** The user's answer in the action-inspector banner for one document (remembered by file hash). */
enum class JsAnswer { ALLOW, BLOCK }

object JsRun {
    /**
     * Whether a document's scripts run. Safe Reading Mode and "off" stop everything, "Keep blocked" stops this
     * document; "on" runs the rest; "trusted only" and "ask" run documents trusted by hash or in a privileged location
     * (in "ask" the banner's Allow trusts the document).
     */
    fun allowed(level: JsLevel, safeActive: Boolean, trusted: Boolean, answer: JsAnswer?): Boolean = when {
        safeActive || level == JsLevel.OFF || answer == JsAnswer.BLOCK -> false
        level == JsLevel.ON -> true
        else -> trusted || answer == JsAnswer.ALLOW
    }

    /** The action-inspector banner shows once per document that has something to report and no answer yet. */
    fun showBanner(findings: ActionFindings?, trusted: Boolean, answer: JsAnswer?): Boolean =
        findings != null && findings.needsBanner && !trusted && answer == null

    /** The banner offers "Allow" for scripts only where an answer can change the outcome. */
    fun canAllow(level: JsLevel, safeActive: Boolean): Boolean = !safeActive && (level == JsLevel.ASK || level == JsLevel.ON)
}

/** Allow / ask / block for opening or saving an embedded attachment with another app. */
enum class AttachRule { ALLOW, ASK, BLOCK }

object AttachmentPolicy {
    /** Executables and scripts: never handed to another app by default. */
    val DEFAULT_BLOCK: Set<String> = LaunchPolicy.EXECUTABLE + setOf(
        "hta", "msp", "mst", "reg", "dll", "sys", "ocx", "inf", "app", "dmg", "pkg", "command", "py", "pyw", "pl", "rb", "php",
        "vb", "vbscript", "ws", "wsh", "wsc", "psm1", "psd1", "msc", "appx", "appxbundle", "msix", "scf", "url", "desktop", "xapk", "apks",
        "docm", "dotm", "xlsm", "xltm", "xlam", "pptm", "potm", "ppam", "sldm", "iqy", "slk", "chm", "hlp", "gadget", "application",
    )
    /** Common documents, images and media: opened without asking by default. */
    val DEFAULT_ALLOW: Set<String> = setOf(
        "pdf", "txt", "csv", "rtf", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff",
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "mp4", "m4v", "mov", "webm", "3gp",
    )

    fun defaults(): Map<String, AttachRule> =
        DEFAULT_BLOCK.associateWith { AttachRule.BLOCK } + DEFAULT_ALLOW.associateWith { AttachRule.ALLOW }

    /** Extension of [name] in lower case ("" when there is none); trailing dots / spaces are ignored like Windows does. */
    fun extension(name: String): String =
        name.trim().trimEnd('.', ' ').substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()

    /** Rule for [name]: its extension's entry, else "ask" (files without an extension too). */
    fun decide(name: String, rules: Map<String, AttachRule>): AttachRule = rules[extension(name)] ?: AttachRule.ASK

    /** An extension typed by the user (".EXE", "exe ") reduced to "exe"; null when invalid. */
    fun normalizeExt(input: String): String? =
        input.trim().trimStart('*').trimStart('.').lowercase().takeIf { it.isNotEmpty() && it.length <= 16 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }

    fun encode(rules: Map<String, AttachRule>): String = rules.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value.name}" }

    fun decode(s: String?): Map<String, AttachRule>? {
        if (s == null) return null
        val out = LinkedHashMap<String, AttachRule>()
        s.split(';').forEach { e ->
            val k = normalizeExt(e.substringBefore('=')) ?: return@forEach
            val v = AttachRule.values().firstOrNull { it.name == e.substringAfter('=', "") } ?: return@forEach
            out[k] = v
        }
        return out
    }
}
