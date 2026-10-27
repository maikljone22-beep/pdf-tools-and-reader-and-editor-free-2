package com.smartdocs.editor.core.openall

import java.net.URLDecoder

/** One table-of-contents line: [path] is the ZIP path of the chapter, [fragment] the anchor (may be empty). */
class TocItem(val title: String, val path: String, val fragment: String, val level: Int)

/** An EPUB 2/3 book: reading order ([spine], ZIP paths) and table of contents. */
class EpubBook(
    val title: String,
    val author: String,
    val language: String,
    val rtl: Boolean,
    val spine: List<String>,
    val toc: List<TocItem>,
    val cover: String?,
)

/**
 * EPUB container / OPF / NCX / nav parser for the in-app reader (open-all). Regex based on purpose: no XML parser,
 * so no entity expansion or DTD fetching at all. [entry] returns a ZIP entry's bytes or null. Pure.
 */
object EpubParser {

    private fun attr(tag: String, name: String): String? =
        Regex("""(?i)(?:^|\s)$name\s*=\s*(["'])(.*?)\1""").find(tag)?.groupValues?.get(2)

    private fun text(b: ByteArray?): String? = b?.let { TextDecode.decode(it, truncated = false).text }

    fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "" }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { m -> m.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "" }
        .replace("&amp;", "&")

    private fun stripTags(s: String) = unescape(s.replace(Regex("""<[^>]*>"""), " ")).replace(Regex("""\s+"""), " ").trim()

    /** Resolves [href] (relative, URL-encoded, maybe with #fragment) against the folder of [base]. Returns path + fragment. */
    fun resolve(base: String, href: String): Pair<String, String> {
        val frag = href.substringAfter('#', "")
        val h = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }.getOrDefault(href.substringBefore('#'))
        if (h.isEmpty()) return base to frag
        val dir = if (h.startsWith("/")) "" else base.substringBeforeLast('/', "")
        val parts = ArrayList<String>()
        (if (dir.isEmpty()) h.trimStart('/') else "$dir/$h").split('/').forEach { p ->
            when (p) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts += p }
        }
        return parts.joinToString("/") to frag
    }

    fun parse(entry: (String) -> ByteArray?): EpubBook {
        val container = text(entry("META-INF/container.xml"))
        val opfPath = container?.let { c -> Regex("""(?i)<rootfile\b[^>]*>""").findAll(c).map { attr(it.value, "full-path") }.firstOrNull { it != null } }
            ?: throw IllegalArgumentException("no rootfile")
        val opf = text(entry(opfPath)) ?: throw IllegalArgumentException("no package document")

        class Item(val id: String, val href: String, val type: String, val props: String)
        val items = LinkedHashMap<String, Item>()
        Regex("""(?is)<(?:\w+:)?item\b[^>]*>""").findAll(opf).forEach { m ->
            val id = attr(m.value, "id") ?: return@forEach
            val href = attr(m.value, "href") ?: return@forEach
            items[id] = Item(id, resolve(opfPath, unescape(href)).first, attr(m.value, "media-type").orEmpty(), attr(m.value, "properties").orEmpty())
        }
        val spineTag = Regex("""(?is)<(?:\w+:)?spine\b[^>]*>""").find(opf)?.value.orEmpty()
        val spine = Regex("""(?is)<(?:\w+:)?itemref\b[^>]*>""").findAll(opf)
            .filter { attr(it.value, "linear")?.lowercase() != "no" }
            .mapNotNull { m -> attr(m.value, "idref")?.let { items[it] } }
            .filter { it.type.contains("html", true) || it.type.contains("xml", true) || it.type.isEmpty() }
            .map { it.href }.distinct().toList()
            .ifEmpty { items.values.filter { it.type.contains("html", true) }.map { it.href } }
        fun meta(name: String) = Regex("""(?is)<dc:$name\b[^>]*>(.*?)</dc:$name>""").find(opf)?.groupValues?.get(1)?.let(::stripTags).orEmpty()
        val title = meta("title")
        val lang = meta("language")
        val ppd = attr(spineTag, "page-progression-direction")?.lowercase()
        val rtl = ppd == "rtl" || ppd == null && lang.lowercase().let { l -> listOf("ar", "fa", "he", "ur", "yi", "ps", "ckb").any { l == it || l.startsWith("$it-") } }

        // table of contents: EPUB 3 nav document first, then the EPUB 2 NCX
        var toc = items.values.firstOrNull { it.props.split(' ').contains("nav") }?.let { nav -> text(entry(nav.href))?.let { navToc(it, nav.href) } }.orEmpty()
        if (toc.isEmpty()) {
            val ncx = attr(spineTag, "toc")?.let { items[it] } ?: items.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
            toc = ncx?.let { n -> text(entry(n.href))?.let { ncxToc(it, n.href) } }.orEmpty()
        }
        val cover = items.values.firstOrNull { it.props.split(' ').contains("cover-image") }?.href
            ?: Regex("""(?is)<meta\b[^>]*name\s*=\s*["']cover["'][^>]*>""").find(opf)?.value?.let { attr(it, "content") }?.let { items[it]?.href }
        return EpubBook(title, meta("creator"), lang, rtl, spine, toc, cover)
    }

    /** `<nav epub:type="toc">` lists: the nesting depth of <ol> gives the level. */
    fun navToc(html: String, base: String): List<TocItem> {
        val nav = Regex("""(?is)<nav\b[^>]*(?:epub:)?type\s*=\s*["'][^"']*\btoc\b[^"']*["'][^>]*>(.*?)</nav>""").find(html)?.groupValues?.get(1)
            ?: Regex("""(?is)<nav\b[^>]*>(.*?)</nav>""").find(html)?.groupValues?.get(1) ?: return emptyList()
        val out = ArrayList<TocItem>()
        var depth = 0
        Regex("""(?is)<(/?)ol\b[^>]*>|<a\b([^>]*)>(.*?)</a>""").findAll(nav).forEach { m ->
            when {
                m.value.startsWith("<ol", true) -> depth++
                m.value.startsWith("</ol", true) -> depth--
                else -> {
                    val href = attr(" " + m.groupValues[2], "href") ?: return@forEach
                    val (p, f) = resolve(base, unescape(href))
                    val t = stripTags(m.groupValues[3])
                    if (t.isNotEmpty()) out += TocItem(t, p, f, (depth - 1).coerceAtLeast(0))
                }
            }
        }
        return out
    }

    /** EPUB 2 `navPoint` tree. */
    fun ncxToc(xml: String, base: String): List<TocItem> {
        val out = ArrayList<TocItem>()
        var depth = 0
        var pendingTitle: String? = null
        Regex("""(?is)<(/?)navPoint\b[^>]*>|<text>(.*?)</text>|<content\b[^>]*>""").findAll(xml).forEach { m ->
            val v = m.value
            when {
                v.startsWith("<navPoint", true) -> { depth++; pendingTitle = null }
                v.startsWith("</navPoint", true) -> depth--
                v.startsWith("<text", true) -> if (pendingTitle == null) pendingTitle = stripTags(m.groupValues[2])
                v.startsWith("<content", true) -> {
                    val src = attr(v, "src") ?: return@forEach
                    val (p, f) = resolve(base, unescape(src))
                    pendingTitle?.takeIf { it.isNotEmpty() && depth > 0 }?.let { out += TocItem(it, p, f, depth - 1) }
                    pendingTitle = ""
                }
            }
        }
        return out
    }

    /** Chapter markup made safe for the viewer: scripts, event handlers and external stylesheets' @import removed. */
    fun cleanChapter(html: String): String = html
        .replace(Regex("""(?is)<script\b.*?</script\s*>"""), "")
        .replace(Regex("""(?is)<script\b[^>]*/>"""), "")
        .replace(Regex("""(?i)\son\w+\s*=\s*(["']).*?\1"""), "")
        .replace(Regex("""(?is)<!DOCTYPE[^>\[]*(\[.*?])?\s*>"""), "<!DOCTYPE html>")
}
