package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.edit5.E5Bookmark
import com.smartdocs.editor.core.edit5.OutlineStore
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import com.tom_roush.pdfbox.text.PDFMarkedContentExtractor
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Tags panel tools: Find Element (Foxit 34.49) and New Bookmarks From Structure (34.18). An element's text is the
 * text of the marked content it owns (its MCIDs, in order, through its child elements).
 */
object AccessFind {
    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    /** 34.49: elements of [types] (type or its role-mapped standard type; empty = any) whose title, alt, actual text, id or language contains [query]. Pure. */
    fun find(nodes: List<TagNode>, types: Set<String>, query: String): List<TagNode> {
        val q = query.trim()
        return nodes.filter { n ->
            (types.isEmpty() || n.type in types || n.mapped in types) &&
                (q.isEmpty() || listOfNotNull(n.title, n.alt, n.actualText, n.id, n.lang, n.type).any { it.contains(q, ignoreCase = true) })
        }
    }

    /** Heading level of a standard type (H1..H6 → 1..6, H → 1), else 0. Pure. */
    fun headingLevel(type: String): Int = when {
        type == "H" -> 1
        type.length == 2 && type[0] == 'H' && type[1] in '1'..'6' -> type[1] - '0'
        else -> 0
    }

    /** MCID → text of [page]'s marked content. */
    fun mcidTexts(page: PDPage): Map<Int, String> = runCatching {
        val ex = PDFMarkedContentExtractor(); ex.processPage(page)
        val out = HashMap<Int, String>()
        fun text(mc: PDMarkedContent, sb: StringBuilder, depth: Int) {
            if (depth > 30) return
            mc.contents.forEach { c -> when (c) { is TextPosition -> sb.append(c.unicode); is PDMarkedContent -> text(c, sb, depth + 1) } }
        }
        fun walk(list: List<PDMarkedContent>, depth: Int) {
            if (depth > 30) return
            list.forEach { mc ->
                if (mc.mcid >= 0) out[mc.mcid] = StringBuilder().also { text(mc, it, 0) }.toString()
                walk(mc.contents.filterIsInstance<PDMarkedContent>(), depth + 1)
            }
        }
        walk(ex.markedContents, 0)
        out
    }.getOrDefault(emptyMap())

    /** The text an element owns, in content order ([cache]: page index → MCID texts). */
    fun elementText(doc: PDDocument, path: List<Int>, cache: MutableMap<Int, Map<Int, String>>): String {
        val e = AccessTags.at(doc, path) ?: return ""
        val pages = doc.pages.withIndex().associate { it.value.cosObject to it.index }
        val sb = StringBuilder()
        fun pageOf(d: COSDictionary, inherited: Int) = (deref(d.getDictionaryObject(COSName.getPDFName("Pg"))) as? COSDictionary)?.let { pages[it] } ?: inherited
        fun add(page: Int, mcid: Int) {
            if (page < 0) return
            val texts = cache.getOrPut(page) { mcidTexts(doc.getPage(page)) }
            texts[mcid]?.let { if (sb.isNotEmpty()) sb.append(' '); sb.append(it.trim()) }
        }
        fun walk(d: COSDictionary, page: Int, depth: Int) {
            if (depth > 40 || sb.length > 400) return
            val pg = pageOf(d, page)
            val k = deref(d.getDictionaryObject(COSName.K))
            val list = if (k is COSArray) (0 until k.size()).map { deref(k.get(it)) } else listOfNotNull(k)
            list.forEach { c ->
                when (c) {
                    is COSInteger -> add(pg, c.intValue())
                    is COSDictionary -> when (c.getNameAsString(COSName.TYPE)) {
                        "MCR" -> add(pageOf(c, pg), c.getInt(COSName.getPDFName("MCID"), -1))
                        "OBJR" -> {}
                        else -> if (c.containsKey(COSName.S)) walk(c, pg, depth + 1)
                    }
                }
            }
        }
        walk(e, -1, 0)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * 34.18: one bookmark per element of [types] (in tag order) under a new top bookmark [parentTitle]; headings keep
     * their level. Titles: the element's text, else its title / actual text / alt, else [untitled] of its type.
     */
    fun bookmarks(doc: PDDocument, types: Set<String>, parentTitle: String, untitled: (String) -> String): List<E5Bookmark> {
        val nodes = find(AccessTags.tree(doc), types, "")
        if (nodes.isEmpty()) return emptyList()
        val cache = HashMap<Int, Map<Int, String>>()
        val firstPage = nodes.firstOrNull { it.page >= 0 }?.page ?: 0
        val out = arrayListOf(E5Bookmark(key = 1, title = parentTitle, page = firstPage, level = 0, open = true))
        nodes.forEachIndexed { i, n ->
            val text = elementText(doc, n.path, cache).take(160)
            val title = text.ifBlank { listOfNotNull(n.title, n.actualText, n.alt).firstOrNull { it.isNotBlank() } ?: untitled(n.mapped ?: n.type) }
            val lv = headingLevel(n.mapped ?: n.type).coerceAtLeast(1)
            out += E5Bookmark(key = i.toLong() + 2, title = title, page = n.page.coerceAtLeast(0), level = lv)
        }
        return out
    }

    /** Append [bookmarks] after the document's own outline. Returns how many items were added (without the parent). */
    fun addBookmarks(doc: PDDocument, types: Set<String>, parentTitle: String, untitled: (String) -> String): Int {
        val add = bookmarks(doc, types, parentTitle, untitled)
        if (add.isEmpty()) return 0
        val old = OutlineStore.read(doc)
        val base = (old.maxOfOrNull { it.key } ?: 0L) + 1
        OutlineStore.write(doc, old + add.map { it.copy(key = it.key + base) })
        return add.size - 1
    }

    /** Standard element types offered in the dialogs. */
    val TYPES = listOf("H1", "H2", "H3", "H4", "H5", "H6", "P", "Figure", "Table", "L", "LI", "Caption", "Link", "Note", "Form", "Sect", "Art")
}
