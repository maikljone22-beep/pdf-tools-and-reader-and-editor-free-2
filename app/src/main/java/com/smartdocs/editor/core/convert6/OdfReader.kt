package com.smartdocs.editor.core.convert6

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** OpenDocument (ODT / ODS / ODP) readers → [FlowDoc] / sheet rows / slide texts. DOM based, pure JVM. */
object OdfReader {

    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    private const val XLINK = "http://www.w3.org/1999/xlink"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val PRES = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"

    private class Sty(val bold: Boolean, val italic: Boolean, val rtl: Boolean, val center: Boolean, val parent: String?)

    private fun parseXml(bytes: ByteArray): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(bytes.inputStream()).documentElement
    }

    private fun kids(e: Node): List<Element> {
        val out = ArrayList<Element>(); val l = e.childNodes
        for (i in 0 until l.length) (l.item(i) as? Element)?.let { out += it }
        return out
    }

    private fun Element.attr(ns: String, name: String): String? = getAttributeNS(ns, name).ifEmpty { null }
    private fun Element.isA(ns: String, name: String) = namespaceURI == ns && localName == name

    private fun styles(vararg roots: Element?): Map<String, Sty> {
        val map = HashMap<String, Sty>()
        roots.filterNotNull().forEach { root ->
            val list = root.getElementsByTagNameNS(STYLE, "style")
            for (i in 0 until list.length) {
                val s = list.item(i) as Element
                val name = s.attr(STYLE, "name") ?: continue
                var bold = false; var italic = false; var rtl = false; var center = false
                kids(s).forEach { k ->
                    if (k.isA(STYLE, "text-properties")) {
                        bold = k.attr(FO, "font-weight") == "bold" || k.attr(STYLE, "font-weight-complex") == "bold"
                        italic = k.attr(FO, "font-style") == "italic" || k.attr(STYLE, "font-style-complex") == "italic"
                    }
                    if (k.isA(STYLE, "paragraph-properties")) {
                        rtl = k.attr(STYLE, "writing-mode")?.startsWith("rl") == true
                        center = k.attr(FO, "text-align") == "center"
                    }
                }
                map[name] = Sty(bold, italic, rtl, center, s.attr(STYLE, "parent-style-name"))
            }
        }
        return map
    }

    private class Ctx(val st: Map<String, Sty>, val entry: (String) -> ByteArray?, val out: MutableList<FBlock>)

    private fun sty(c: Ctx, name: String?): Sty? {
        var n = name; var guard = 0
        while (n != null && guard++ < 8) { val s = c.st[n] ?: return null; if (s.bold || s.italic || s.rtl || s.center || s.parent == null) return s; n = s.parent }
        return null
    }

    /** Runs of a text:p / text:h, collecting frames (pictures) into [images]. */
    private fun runs(c: Ctx, e: Element, bold: Boolean, italic: Boolean, link: String?, out: MutableList<FRun>, images: MutableList<FBlock>) {
        val l = e.childNodes
        for (i in 0 until l.length) {
            val n = l.item(i)
            if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) { out += FRun(n.nodeValue.replace(Regex("""\s+"""), " "), bold, italic, link = link); continue }
            val k = n as? Element ?: continue
            when {
                k.isA(TEXT, "s") -> out += FRun(" ".repeat(k.attr(TEXT, "c")?.toIntOrNull()?.coerceIn(1, 100) ?: 1), bold, italic)
                k.isA(TEXT, "tab") -> out += FRun("\t", bold, italic)
                k.isA(TEXT, "line-break") -> out += FRun("\n", bold, italic)
                k.isA(TEXT, "span") -> { val s = sty(c, k.attr(TEXT, "style-name")); runs(c, k, bold || s?.bold == true, italic || s?.italic == true, link, out, images) }
                k.isA(TEXT, "a") -> runs(c, k, bold, italic, k.attr(XLINK, "href"), out, images)
                k.isA(TEXT, "note") -> {}
                k.isA(DRAW, "frame") -> frame(c, k, images)
                else -> runs(c, k, bold, italic, link, out, images)
            }
        }
    }

    private fun frame(c: Ctx, f: Element, images: MutableList<FBlock>) {
        kids(f).forEach { k ->
            if (k.isA(DRAW, "image")) {
                val href = k.attr(XLINK, "href") ?: return@forEach
                val bytes = c.entry(href.removePrefix("./")) ?: return@forEach
                images += FBlock.Image(bytes, Images6.sniffExt(bytes) ?: href.substringAfterLast('.', "png"), ptOf(f.attr("urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0", "width")), ptOf(f.attr("urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0", "height")))
            }
            if (k.isA(DRAW, "text-box")) blocks(c, k, 0)
        }
    }

    /** "2.5cm" / "10mm" / "1in" / "12pt" → points (0 when unknown). */
    fun ptOf(v: String?): Float {
        v ?: return 0f
        val m = Regex("""^([0-9.]+)(cm|mm|in|pt|px)?$""").find(v.trim()) ?: return 0f
        val n = m.groupValues[1].toFloatOrNull() ?: return 0f
        return when (m.groupValues[2]) { "cm" -> n * 72f / 2.54f; "mm" -> n * 72f / 25.4f; "in" -> n * 72f; "px" -> n * 0.75f; else -> n }
    }

    private fun para(c: Ctx, e: Element, heading: Int, listLevel: Int, number: Int) {
        val s = sty(c, e.attr(TEXT, "style-name"))
        val rs = ArrayList<FRun>(); val imgs = ArrayList<FBlock>()
        runs(c, e, s?.bold == true && heading == 0, s?.italic == true, null, rs, imgs)
        val t = Text6.plain(rs)
        val rtl = s?.rtl == true || Text6.rtlDominant(t)
        when {
            heading > 0 -> if (t.isNotBlank()) c.out += FBlock.Heading(heading, rs, rtl)
            listLevel >= 0 -> if (t.isNotBlank()) c.out += FBlock.Item(rs, rtl, listLevel, number)
            else -> c.out += FBlock.Para(if (t.isBlank()) emptyList() else rs, rtl, if (s?.center == true) FAlign.CENTER else FAlign.START)
        }
        c.out += imgs
    }

    private fun blocks(c: Ctx, parent: Element, listLevel: Int, ordered: Boolean = false) {
        var num = 0
        kids(parent).forEach { k ->
            when {
                k.isA(TEXT, "h") -> para(c, k, k.attr(TEXT, "outline-level")?.toIntOrNull()?.coerceIn(1, 6) ?: 1, -1, 0)
                k.isA(TEXT, "p") -> para(c, k, 0, if (listLevel > 0) listLevel - 1 else -1, if (ordered) num else 0)
                k.isA(TEXT, "list") -> {
                    val styleName = k.attr(TEXT, "style-name").orEmpty()
                    blocks(c, k, listLevel + 1, ordered = isOrderedList(c, styleName) ?: ordered)
                }
                k.isA(TEXT, "list-item") || k.isA(TEXT, "list-header") -> { num++; kids(k).forEach { li ->
                    if (li.isA(TEXT, "p") || li.isA(TEXT, "h")) para(c, li, 0, (listLevel - 1).coerceAtLeast(0), if (ordered) num else 0)
                    else if (li.isA(TEXT, "list")) blocks(c, li, listLevel + 1, ordered)
                } }
                k.isA(TABLE, "table") -> c.out += FBlock.Table(tableRows(k, 2000, 200), false, Text6.rtlDominant(k.textContent))
                k.isA(TEXT, "section") || k.isA(TEXT, "index-body") || k.isA(TEXT, "table-of-content") -> blocks(c, k, listLevel)
                k.isA(DRAW, "frame") -> { val imgs = ArrayList<FBlock>(); frame(c, k, imgs); c.out += imgs }
                k.isA(TEXT, "soft-page-break") -> {}
            }
        }
    }

    private var orderedStyles: Set<String> = emptySet()
    private fun isOrderedList(@Suppress("UNUSED_PARAMETER") c: Ctx, name: String): Boolean? = if (name.isEmpty()) null else name in orderedStyles

    private fun orderedListStyles(vararg roots: Element?): Set<String> {
        val out = HashSet<String>()
        roots.filterNotNull().forEach { r ->
            val l = r.getElementsByTagNameNS(TEXT, "list-style")
            for (i in 0 until l.length) {
                val s = l.item(i) as Element
                val first = kids(s).firstOrNull()
                if (first != null && first.isA(TEXT, "list-level-style-number")) s.attr(STYLE, "name")?.let { out += it }
            }
        }
        return out
    }

    /** Cell texts of a table:table, expanding repeats (bounded) and dropping trailing empty rows / columns. */
    fun tableRows(t: Element, maxRows: Int, maxCols: Int): List<List<String>> {
        val rows = ArrayList<List<String>>()
        fun rowsOf(e: Element) {
            kids(e).forEach { r ->
                when {
                    r.isA(TABLE, "table-row") -> {
                        val cells = ArrayList<String>()
                        kids(r).forEach { cell ->
                            if (cell.isA(TABLE, "table-cell") || cell.isA(TABLE, "covered-table-cell")) {
                                val rep = cell.attr(TABLE, "number-columns-repeated")?.toIntOrNull()?.coerceIn(1, maxCols) ?: 1
                                val txt = kids(cell).filter { it.isA(TEXT, "p") || it.isA(TEXT, "h") }.joinToString("\n") { it.textContent }
                                repeat(rep) { if (cells.size < maxCols) cells += txt }
                            }
                        }
                        val rep = r.attr(TABLE, "number-rows-repeated")?.toIntOrNull()?.coerceIn(1, maxRows) ?: 1
                        repeat(rep) { if (rows.size < maxRows) rows += cells }
                    }
                    r.isA(TABLE, "table-rows") || r.isA(TABLE, "table-header-rows") || r.isA(TABLE, "table-row-group") -> rowsOf(r)
                }
            }
        }
        rowsOf(t)
        while (rows.isNotEmpty() && rows.last().all { it.isBlank() }) rows.removeAt(rows.size - 1)
        val width = rows.maxOfOrNull { r -> r.indexOfLast { it.isNotBlank() } + 1 } ?: 0
        return rows.map { r -> (0 until width).map { r.getOrElse(it) { "" } } }
    }

    fun readOdt(entry: (String) -> ByteArray?, title: String): FlowDoc {
        val content = parseXml(entry("content.xml") ?: throw IllegalArgumentException("no content.xml"))
        val stylesRoot = entry("styles.xml")?.let { runCatching { parseXml(it) }.getOrNull() }
        orderedStyles = orderedListStyles(content, stylesRoot)
        val out = ArrayList<FBlock>()
        val c = Ctx(styles(content, stylesRoot), entry, out)
        val body = content.getElementsByTagNameNS(OFFICE, "text").item(0) as? Element ?: throw IllegalArgumentException("no office:text")
        blocks(c, body, 0)
        return FlowDoc(title, out)
    }

    /** Sheets: name → rows. */
    fun readOds(entry: (String) -> ByteArray?): List<Pair<String, List<List<String>>>> {
        val content = parseXml(entry("content.xml") ?: throw IllegalArgumentException("no content.xml"))
        val l = content.getElementsByTagNameNS(TABLE, "table")
        return (0 until l.length).map { i -> val t = l.item(i) as Element; (t.attr(TABLE, "name") ?: "Sheet${i + 1}") to tableRows(t, 20000, 500) }
    }

    /** Slides: (title, lines). */
    fun readOdp(entry: (String) -> ByteArray?): List<Pair<String, List<String>>> {
        val content = parseXml(entry("content.xml") ?: throw IllegalArgumentException("no content.xml"))
        val pages = content.getElementsByTagNameNS(DRAW, "page")
        return (0 until pages.length).map { i ->
            val p = pages.item(i) as Element
            var title = ""; val lines = ArrayList<String>()
            val frames = p.getElementsByTagNameNS(DRAW, "frame")
            for (j in 0 until frames.length) {
                val f = frames.item(j) as Element
                val cls = f.attr(PRES, "class")
                val ps = f.getElementsByTagNameNS(TEXT, "p")
                val texts = (0 until ps.length).map { ps.item(it).textContent.trim() }.filter { it.isNotEmpty() }
                if (cls == "title" && title.isEmpty()) title = texts.joinToString(" ") else if (cls != "notes") lines += texts
            }
            if (title.isEmpty() && lines.isNotEmpty()) title = lines.removeAt(0)
            title to lines
        }
    }

    fun zipEntry(f: File): (String) -> ByteArray? {
        val cache = HashMap<String, ByteArray?>()
        return { name -> cache.getOrPut(name) { runCatching { ZipFile(f).use { z -> z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } } } }.getOrNull() } }
    }
}
