package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.edit.OutlineModel
import org.w3c.dom.Element
import java.io.InputStream

/** Pure outline helpers: sections, current bookmark, search, expand / collapse all, deleted pages. */
object OutlineNav {

    /**
     * Pages (zero-based) of the section of bookmark [i]: from its page to the page before the next bookmark at the
     * same or a higher level (or the end). Empty for a bookmark without a page.
     */
    fun sectionPages(pages: List<Int>, levels: List<Int>, i: Int, pageCount: Int): IntRange {
        val start = pages.getOrNull(i) ?: return IntRange.EMPTY
        if (start !in 0 until pageCount) return IntRange.EMPTY
        var end = pageCount - 1
        for (k in i + 1 until pages.size) {
            if (levels[k] <= levels[i] && pages[k] >= 0) { end = if (pages[k] > start) pages[k] - 1 else start; break }
        }
        return start..end.coerceAtLeast(start)
    }

    fun sectionPages(list: List<E5Bookmark>, i: Int, pageCount: Int) = sectionPages(list.map { it.page }, list.map { it.level }, i, pageCount)

    /**
     * The bookmark for [page] ("find current bookmark"): the last one in reading order whose page is at or before
     * [page], preferring the deepest one on that page. -1 when none.
     */
    fun current(pages: List<Int>, page: Int): Int {
        var best = -1
        for (i in pages.indices) if (pages[i] in 0..page && (best < 0 || pages[i] >= pages[best])) best = i
        return best
    }

    /** Indexes of the items whose title contains [query] (case- and diacritics-insensitive), plus their ancestors. */
    fun search(titles: List<String>, levels: List<Int>, query: String): Set<Int> {
        val q = fold(query)
        if (q.isBlank()) return titles.indices.toSet()
        val out = HashSet<Int>()
        for (i in titles.indices) if (fold(titles[i]).contains(q)) {
            out += i
            var lvl = levels[i]
            var k = i - 1
            while (k >= 0 && lvl > 0) { if (levels[k] < lvl) { out += k; lvl = levels[k] }; k-- }
        }
        return out
    }

    /** Lower case without Arabic diacritics / tatweel, alef forms folded (search box). */
    fun fold(s: String): String = s.lowercase().filter { it !in 'ً'..'ٟ' && it != 'ـ' && it != 'ٰ' }
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')

    fun setOpenAll(list: List<E5Bookmark>, open: Boolean) = list.map { it.copy(open = open) }

    /**
     * The outline after pages [deleted] are removed: bookmarks pointing at them are dropped and the other pages are
     * renumbered. Children of a dropped bookmark move up (levels are normalised).
     */
    fun afterDelete(list: List<E5Bookmark>, deleted: Set<Int>): List<E5Bookmark> {
        val sorted = deleted.sorted()
        fun shift(p: Int) = p - sorted.count { it < p }
        val kept = list.filter { it.page < 0 || it.page !in deleted }.map { b -> if (b.page >= 0) b.copy(page = shift(b.page)) else b }
        return OutlineModel.normalize(kept.map { it.row() }).let { n -> kept.mapIndexed { i, b -> b.copy(level = n[i].level) } }
    }

    /**
     * Where a bookmark for [page] goes (Foxit "add bookmark from selected text"): after the subtree of the current
     * bookmark of that page, at its level; at the start (level 0) when no bookmark comes before the page.
     */
    fun insertForPage(list: List<E5Bookmark>, page: Int, b: E5Bookmark): List<E5Bookmark> {
        val cur = current(list.map { it.page }, page)
        if (cur < 0) return listOf(b.copy(level = 0)) + list
        val lvl = list[cur].level
        var k = cur + 1
        while (k < list.size && list[k].level > lvl) k++
        return list.subList(0, k) + b.copy(level = lvl) + list.subList(k, list.size)
    }

    /** Insert [add] before (at the start), after (at the end) or instead of [list]. */
    fun insert(list: List<E5Bookmark>, add: List<E5Bookmark>, mode: AutoInsert): List<E5Bookmark> = when (mode) {
        AutoInsert.REPLACE -> add
        AutoInsert.BEFORE -> add + list
        AutoInsert.AFTER -> list + add
    }
}

enum class AutoInsert { REPLACE, BEFORE, AFTER }

/**
 * Bookmarks as XML (Foxit "Export / Import bookmarks"): nested `<bookmark>` elements with title, page (1-based), zoom
 * and look. Actions other than go-to-page are exported as their web link / file / script so they round-trip.
 *
 * ```
 * <bookmarks version="1"><bookmark title="Intro" page="1" fit="XYZ" zoom="100" color="#C62828" bold="true" open="true">…</bookmark></bookmarks>
 * ```
 */
object BookmarkXml {
    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            '\n' -> append("&#10;"); '\r' -> append("&#13;"); '\t' -> append("&#9;")
            else -> if (c < ' ') Unit else append(c)
        }
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    fun write(list: List<E5Bookmark>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<bookmarks version=\"1\">\n")
        val norm = OutlineModel.normalize(list.map { it.row() }).map { it.level }
        val open = ArrayList<Int>()
        list.forEachIndexed { i, b ->
            val lvl = norm[i]
            while (open.isNotEmpty() && open.last() >= lvl) { open.removeAt(open.size - 1); append("  ".repeat(open.size + 1)).append("</bookmark>\n") }
            append("  ".repeat(open.size + 1)).append("<bookmark title=\"").append(esc(b.title)).append('"')
            if (b.page >= 0) append(" page=\"").append(b.page + 1).append('"')
            append(" fit=\"").append(b.fit.name).append('"')
            if (b.zoom > 0f) append(" zoom=\"").append((b.zoom * 100).toInt()).append('"')
            b.top?.let { append(" top=\"").append(it).append('"') }
            b.left?.let { append(" left=\"").append(it).append('"') }
            b.fitR?.takeIf { it.size == 4 }?.let { append(" rect=\"").append(it.joinToString(",")).append('"') }
            b.color?.let { append(" color=\"").append(hex(it)).append('"') }
            if (b.bold) append(" bold=\"true\"")
            if (b.italic) append(" italic=\"true\"")
            if (b.open) append(" open=\"true\"")
            append(">\n")
            b.actions.forEach { a ->
                val pad = "  ".repeat(open.size + 2)
                when (a) {
                    is NavAction.Uri -> append(pad).append("<action type=\"uri\" value=\"").append(esc(a.uri)).append("\"/>\n")
                    is NavAction.GoTo -> append(pad).append("<action type=\"goto\" page=\"").append(a.page + 1).append("\" named=\"").append(esc(a.named)).append("\" fit=\"").append(a.view.fit.name).append("\"/>\n")
                    is NavAction.GoToR -> append(pad).append("<action type=\"gotor\" value=\"").append(esc(a.file)).append("\" page=\"").append(a.page + 1).append("\" named=\"").append(esc(a.named)).append("\"/>\n")
                    is NavAction.Launch -> append(pad).append("<action type=\"launch\" value=\"").append(esc(a.file)).append("\"/>\n")
                    is NavAction.Named -> append(pad).append("<action type=\"named\" value=\"").append(esc(a.name)).append("\"/>\n")
                    is NavAction.Js -> append(pad).append("<action type=\"js\" value=\"").append(esc(a.script)).append("\"/>\n")
                    is NavAction.Kept -> Unit
                }
            }
            open += lvl
        }
        while (open.isNotEmpty()) { open.removeAt(open.size - 1); append("  ".repeat(open.size + 1)).append("</bookmark>\n") }
        append("</bookmarks>\n")
    }

    /** Parse [input]; pages outside 1..[pageCount] are clamped. Throws on malformed XML. */
    fun read(input: InputStream, pageCount: Int): List<E5Bookmark> {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false; isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true) }
        }
        val root = f.newDocumentBuilder().parse(input).documentElement
        val out = ArrayList<E5Bookmark>()
        fun attr(e: Element, n: String) = e.getAttribute(n).takeIf { it.isNotEmpty() }
        fun walk(parent: Element, level: Int) {
            val kids = parent.childNodes
            for (i in 0 until kids.length) {
                val e = kids.item(i) as? Element ?: continue
                if (e.tagName != "bookmark" && e.tagName != "Bookmark") continue
                val page = (attr(e, "page")?.toIntOrNull() ?: 0) - 1
                val actions = ArrayList<NavAction>()
                val ak = e.childNodes
                for (k in 0 until ak.length) {
                    val a = ak.item(k) as? Element ?: continue
                    if (a.tagName != "action") continue
                    val v = a.getAttribute("value")
                    when (a.getAttribute("type")) {
                        "uri" -> actions += NavAction.Uri(v)
                        "goto" -> actions += NavAction.GoTo(((a.getAttribute("page").toIntOrNull() ?: 1) - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                            DestView(runCatching { DestFit.valueOf(a.getAttribute("fit")) }.getOrDefault(DestFit.FIT)), a.getAttribute("named"))
                        "gotor" -> actions += NavAction.GoToR(v, ((a.getAttribute("page").toIntOrNull() ?: 1) - 1).coerceAtLeast(0), a.getAttribute("named"))
                        "launch" -> actions += NavAction.Launch(v)
                        "named" -> actions += NavAction.Named(v)
                        "js" -> actions += NavAction.Js(v)
                    }
                }
                val color = attr(e, "color")?.removePrefix("#")?.toIntOrNull(16)
                out += E5Bookmark(
                    OutlineModel.newKey(), e.getAttribute("title"), if (page < 0) (if (actions.isEmpty()) 0 else -1) else page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), level,
                    color = color?.takeIf { it != 0 }, bold = attr(e, "bold") == "true", italic = attr(e, "italic") == "true", open = attr(e, "open") == "true",
                    fit = runCatching { DestFit.valueOf(attr(e, "fit") ?: "XYZ") }.getOrDefault(DestFit.XYZ),
                    zoom = (attr(e, "zoom")?.toFloatOrNull() ?: 0f) / 100f, top = attr(e, "top")?.toFloatOrNull(), left = attr(e, "left")?.toFloatOrNull(),
                    fitR = attr(e, "rect")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 4 },
                    actions = actions,
                )
                walk(e, level + 1)
            }
        }
        walk(root, 0)
        return out
    }
}
