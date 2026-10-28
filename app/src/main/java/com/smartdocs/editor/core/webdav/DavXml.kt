package com.smartdocs.editor.core.webdav

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/** One resource of a WebDAV listing. [url] is absolute; [modified] epoch millis (0 = unknown). */
data class DavEntry(
    val url: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long = 0L,
    val modified: Long = 0L,
    val etag: String? = null,
    val contentType: String? = null,
    /** Token of a lock someone holds on the resource (from lockdiscovery), when the server reports it. */
    val lockedBy: String? = null,
)

/**
 * Pure WebDAV (RFC 4918) helpers: request bodies, multistatus / lock response parsing, URL building. No Android
 * classes, so everything here is unit-tested on the JVM.
 */
object DavXml {

    const val NS = "DAV:"

    /** PROPFIND body asking only for what the browser shows. */
    val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/><d:getcontenttype/><d:lockdiscovery/></d:prop></d:propfind>"""

    /** Exclusive write LOCK body; [owner] is shown to other clients ("locked by …"). */
    fun lockBody(owner: String): String = """<?xml version="1.0" encoding="utf-8"?>
<d:lockinfo xmlns:d="DAV:"><d:lockscope><d:exclusive/></d:lockscope><d:locktype><d:write/></d:locktype><d:owner>${escape(owner)}</d:owner></d:lockinfo>"""

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun parse(xml: String): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isExpandEntityReferences = false
        // no DTDs / external entities (XXE); not every parser knows every feature
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun Element.kids(local: String): List<Element> {
        val out = ArrayList<Element>()
        val nl = childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n.localName ?: n.nodeName.substringAfter(':')) == local && (n.namespaceURI == null || n.namespaceURI == NS)) out += n as Element
        }
        return out
    }

    private fun Element.kid(local: String): Element? = kids(local).firstOrNull()

    private fun Element.text(local: String): String? = kid(local)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    /** Elements named [local] anywhere under this one. */
    private fun Element.deep(local: String): List<Element> {
        val nl = getElementsByTagNameNS(NS, local)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    /** HTTP status code of a "HTTP/1.1 200 OK" line (0 when absent). */
    fun statusCode(line: String?): Int = line?.trim()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0

    /**
     * Parses a 207 multistatus answer of PROPFIND sent to [requestUrl]. Hrefs are resolved against it; only the
     * successful propstat blocks are read. The requested resource itself is included (see [children]).
     */
    fun parseMultistatus(xml: String, requestUrl: String): List<DavEntry> {
        val root = parse(xml).documentElement ?: return emptyList()
        return root.kids("response").mapNotNull { r ->
            val href = r.text("href") ?: return@mapNotNull null
            val url = resolve(requestUrl, href)
            val ok = r.kids("propstat").filter { ps -> statusCode(ps.text("status")).let { it == 0 || it in 200..299 } }
            val props = ok.mapNotNull { it.kid("prop") }
            fun prop(local: String): Element? = props.firstNotNullOfOrNull { it.kid(local) }
            val isFolder = prop("resourcetype")?.kid("collection") != null
            val display = prop("displayname")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val lock = prop("lockdiscovery")?.deep("locktoken")?.firstOrNull()?.text("href")
            DavEntry(
                url = url,
                name = display ?: nameOf(url),
                isFolder = isFolder,
                size = prop("getcontentlength")?.textContent?.trim()?.toLongOrNull() ?: 0L,
                modified = parseHttpDate(prop("getlastmodified")?.textContent),
                etag = prop("getetag")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                contentType = prop("getcontenttype")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                lockedBy = lock,
            )
        }
    }

    /**
     * fc-sync: PROPFIND body asking only for version markers: the ETag of each resource and the collection tag
     * (getctag, CalendarServer extension, sent by Nextcloud / ownCloud / SabreDAV), which changes whenever anything
     * inside the folder changes.
     */
    val VERSION_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"><d:prop><d:getetag/><cs:getctag/><d:resourcetype/></d:prop></d:propfind>"""

    /**
     * fc-sync: version marker of each resource in a multistatus answer: normalized URL → getctag, else getetag. Only
     * successful propstat blocks count; resources without either are left out.
     */
    fun parseVersions(xml: String, requestUrl: String): Map<String, String> {
        val root = parse(xml).documentElement ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (r in root.kids("response")) {
            val href = r.text("href") ?: continue
            val ok = r.kids("propstat").filter { ps -> statusCode(ps.text("status")).let { it == 0 || it in 200..299 } }
            fun any(local: String): String? = ok.firstNotNullOfOrNull { ps ->
                val nl = ps.getElementsByTagNameNS("*", local)
                (0 until nl.length).firstNotNullOfOrNull { nl.item(it).textContent?.trim()?.takeIf { t -> t.isNotEmpty() } }
            }
            val v = any("getctag") ?: any("getetag") ?: continue
            out[normalize(resolve(requestUrl, href))] = v
        }
        return out
    }

    /** The entries inside [folderUrl] (the folder itself removed), folders first, then by name. */
    fun children(entries: List<DavEntry>, folderUrl: String): List<DavEntry> {
        val self = normalize(folderUrl)
        return entries.filter { normalize(it.url) != self }
            .sortedWith(compareBy<DavEntry>({ !it.isFolder }, { it.name.lowercase() }))
    }

    /** Lock token of a LOCK answer: the Lock-Token header ("<opaquelocktoken:…>") or the body's activelock. */
    fun lockToken(header: String?, xml: String?): String? {
        header?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotEmpty() }?.let { return it }
        if (xml.isNullOrBlank()) return null
        return runCatching { parse(xml).documentElement.deep("locktoken").firstOrNull()?.text("href") }.getOrNull()
    }

    /** True when an OPTIONS "DAV:" header advertises class 2 (locking). */
    fun supportsLocking(davHeader: String?): Boolean = davHeader?.split(',')?.map { it.trim() }?.contains("2") == true

    /** RFC 1123 date (getlastmodified) → epoch millis, 0 when it cannot be read. */
    fun parseHttpDate(s: String?): Long {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        for (p in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }
            runCatching { return f.parse(t)!!.time }
        }
        return 0L
    }

    /** Absolute URL of [href] (an absolute URL or a server path) seen from [base]. */
    fun resolve(base: String, href: String): String = runCatching {
        val h = href.trim()
        if (h.startsWith("http://", true) || h.startsWith("https://", true)) h
        else URI(base).resolve(URI(encodePathKeepingSlashes(h))).toString()
    }.getOrDefault(href)

    /** Re-encodes a server path that may hold raw spaces or non-ASCII characters (some servers send them unencoded). */
    private fun encodePathKeepingSlashes(path: String): String =
        if (path.any { it == ' ' || it.code > 127 }) path.split('/').joinToString("/") { seg -> encodeSegment(decode(seg)) } else path

    /** Last path segment, decoded ("/dav/My%20File.pdf" → "My File.pdf"). */
    fun nameOf(url: String): String {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: url
        return decode(path.trimEnd('/').substringAfterLast('/')).ifEmpty { "/" }
    }

    fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** Percent-encodes one path segment (RFC 3986 unreserved characters stay). */
    fun encodeSegment(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }

    /** URL of [name] inside the folder [folderUrl]. */
    fun childUrl(folderUrl: String, name: String, folder: Boolean = false): String =
        folderUrl.trimEnd('/') + "/" + encodeSegment(name) + if (folder) "/" else ""

    /** The parent folder URL of [url] (with a trailing slash). */
    fun parentOf(url: String): String {
        val u = url.trimEnd('/')
        return u.substring(0, u.lastIndexOf('/') + 1)
    }

    /** Comparable form: scheme/host lower-case, path decoded, no trailing slash. */
    fun normalize(url: String): String = runCatching {
        val u = URI(url)
        (u.scheme?.lowercase() ?: "") + "://" + (u.host?.lowercase() ?: "") + (if (u.port > 0) ":${u.port}" else "") + decode(u.rawPath ?: "").trimEnd('/')
    }.getOrDefault(url.trimEnd('/'))

    /** Path of [url] below [rootUrl] ("a/b.pdf"), decoded; the last segment when it is not below the root. */
    fun relativePath(rootUrl: String, url: String): String {
        val r = normalize(rootUrl); val n = normalize(url)
        return if (n.startsWith("$r/")) n.removePrefix("$r/") else nameOf(url)
    }

    /** Checks a place URL typed by the user; returns it normalised (scheme added, trailing slash) or null. */
    fun cleanPlaceUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("webdav://", true)) s = "http://" + s.substring(9)
        if (s.startsWith("webdavs://", true) || s.startsWith("davs://", true)) s = "https://" + s.substringAfter("://")
        if (!s.contains("://")) s = "https://$s"
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        if (u.scheme?.lowercase() !in setOf("http", "https") || u.host.isNullOrBlank()) return null
        return if (s.endsWith("/")) s else "$s/"
    }

    /** Folder segments of [url] below [rootUrl], for a breadcrumb: (label, url). */
    fun crumbs(rootUrl: String, url: String, rootLabel: String): List<Pair<String, String>> {
        val out = arrayListOf(rootLabel to rootUrl)
        val rel = relativePath(rootUrl, url).trim('/')
        if (normalize(url) == normalize(rootUrl) || rel.isEmpty()) return out
        var cur = rootUrl
        rel.split('/').forEach { seg -> cur = childUrl(cur, seg, folder = true); out += seg to cur }
        return out
    }
}
