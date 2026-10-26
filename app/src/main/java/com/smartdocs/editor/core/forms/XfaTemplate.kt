package com.smartdocs.editor.core.forms

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** XFA measurements ("10mm", "0.5in", "12pt", "2cm", "3px"; no unit = inches) in points. */
object XfaUnits {
    fun pt(s: String?, def: Float = 0f): Float {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return def
        val m = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+))\s*([a-z%]*)$""").find(t.lowercase()) ?: return def
        val v = m.groupValues[1].toFloatOrNull() ?: return def
        return when (m.groupValues[2]) {
            "pt" -> v; "mm" -> v * 72f / 25.4f; "cm" -> v * 72f / 2.54f; "in", "" -> v * 72f
            "px" -> v * 0.75f; "mp" -> v / 1000f; "pc", "pica" -> v * 12f
            else -> def
        }
    }

    /** "r,g,b" (0..255) -> 0xFFRRGGBB. */
    fun color(s: String?, def: Int? = null): Int? {
        val p = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return def
        if (p.size < 3) return def
        return (0xFF shl 24) or (p[0].coerceIn(0, 255) shl 16) or (p[1].coerceIn(0, 255) shl 8) or p[2].coerceIn(0, 255)
    }
}

data class XFont(val size: Float = 10f, val bold: Boolean = false, val italic: Boolean = false, val color: Int = 0xFF000000.toInt(), val typeface: String = "")
data class XPara(val hAlign: String? = null, val vAlign: String? = null, val spaceAbove: Float = 0f, val spaceBelow: Float = 0f, val marginLeft: Float = 0f, val marginRight: Float = 0f)
data class XMargin(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) { companion object { val NONE = XMargin() } }
/** Edges in XFA order top, right, bottom, left (null = hidden). */
data class XEdge(val color: Int, val thickness: Float, val dashed: Boolean = false)
data class XBorder(val edges: List<XEdge?>, val fill: Int?, val radius: Float = 0f) {
    val visible: Boolean get() = edges.any { it != null } || fill != null
}
data class XCaption(val text: String, val placement: String = "left", val reserve: Float? = null, val font: XFont? = null, val para: XPara? = null, val hidden: Boolean = false)
data class XOccur(val min: Int = 1, val max: Int = 1, val initial: Int = 1) { val repeats: Boolean get() = max != 1 || min != 1 }
data class XBreak(val page: Boolean = false, val contentArea: Boolean = false, val target: String? = null)
/** Data binding: [match] once / none / global / dataRef. */
data class XBind(val match: String = "once", val ref: String? = null)
data class XScripts(val initialize: String? = null, val calculate: String? = null)

/** Static content of a draw element. */
sealed class XContent {
    data class Text(val text: String) : XContent()
    data class Line(val slope: String, val edge: XEdge?) : XContent()
    data class Rect(val border: XBorder) : XContent()
    data class Arc(val edge: XEdge?, val fill: Int?) : XContent()
    data class Image(val data: ByteArray?, val aspect: String = "fit") : XContent()
}

enum class XUi { TEXT, NUMERIC, DATE, PASSWORD, CHECK, CHOICE, SIGNATURE, BUTTON, IMAGE, BARCODE }

/** Common part of the template nodes laid out by [XfaLayout]. */
sealed class XNode {
    abstract val name: String
    abstract val box: XGeom
    abstract val presence: String
    abstract val margin: XMargin
    abstract val border: XBorder?
    abstract val font: XFont?
    abstract val para: XPara?
    abstract val scripts: XScripts
    val hidden: Boolean get() = presence == "hidden" || presence == "inactive"
}

/** Geometry attributes (null = not given / growable). */
data class XGeom(val x: Float = 0f, val y: Float = 0f, val w: Float? = null, val h: Float? = null, val minW: Float? = null, val minH: Float? = null,
                 val maxW: Float? = null, val maxH: Float? = null, val anchor: String = "topLeft", val colSpan: Int = 1)

class XSub(
    override val name: String, override val box: XGeom, override val presence: String, override val margin: XMargin, override val border: XBorder?,
    override val font: XFont?, override val para: XPara?, override val scripts: XScripts,
    val layout: String, val children: List<XNode>, val occur: XOccur, val bind: XBind,
    val breakBefore: XBreak?, val breakAfter: XBreak?, val columnWidths: List<Float>, val keepIntact: Boolean, val isArea: Boolean = false,
    val isExclGroup: Boolean = false, val required: Boolean = false, val caption: XCaption? = null,
) : XNode()

class XFieldT(
    override val name: String, override val box: XGeom, override val presence: String, override val margin: XMargin, override val border: XBorder?,
    override val font: XFont?, override val para: XPara?, override val scripts: XScripts,
    val ui: XUi, val caption: XCaption?, val items: List<String>, val saveItems: List<String>, val defaultValue: String, val image: ByteArray?,
    val bind: XBind, val multiline: Boolean, val maxChars: Int, val combCells: Int, val uiBorder: XBorder?, val uiMargin: XMargin,
    val readOnly: Boolean, val required: Boolean, val checkSize: Float, val roundCheck: Boolean, val listOpen: String, val textEntry: Boolean,
    val tooltip: String,
) : XNode()

class XDraw(
    override val name: String, override val box: XGeom, override val presence: String, override val margin: XMargin, override val border: XBorder?,
    override val font: XFont?, override val para: XPara?, override val scripts: XScripts, val content: XContent?, val caption: XCaption?,
) : XNode()

data class XArea(val x: Float, val y: Float, val w: Float, val h: Float, val name: String = "")
class XPageArea(val name: String, val id: String, val w: Float, val h: Float, val areas: List<XArea>, val content: List<XNode>, val occurMax: Int)
class XTemplate(val root: XSub, val pages: List<XPageArea>)

/** Parser of the XFA `template` packet into [XTemplate] (proto / use references are not resolved). */
object XfaTemplateParser {

    fun parseXml(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    internal fun Node.local(): String = (localName ?: nodeName).substringAfter(':')
    internal fun Element.kids(): List<Element> {
        val out = ArrayList<Element>(); var n = firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }
    internal fun Element.kid(local: String): Element? = kids().firstOrNull { it.local() == local }
    private fun Element.attr(n: String): String? = getAttribute(n).takeIf { it.isNotEmpty() }

    internal fun findFirst(root: Node, local: String): Element? {
        if (root is Element && root.local() == local) return root
        var n = root.firstChild
        while (n != null) { findFirst(n, local)?.let { return it }; n = n.nextSibling }
        return null
    }

    fun parse(xml: Document): XTemplate? {
        val template = findFirst(xml, "template") ?: return null
        val root = template.kids().firstOrNull { it.local() == "subform" } ?: return null
        val pages = ArrayList<XPageArea>()
        collectPages(root, pages)
        val sub = subform(root, isRoot = true)
        return XTemplate(sub, pages.ifEmpty { listOf(XPageArea("page", "", 612f, 792f, listOf(XArea(36f, 36f, 540f, 720f)), emptyList(), -1)) })
    }

    private fun collectPages(e: Element, out: MutableList<XPageArea>) {
        for (c in e.kids()) when (c.local()) {
            "pageSet" -> collectPages(c, out)
            "pageArea" -> out += pageArea(c)
        }
    }

    private fun pageArea(e: Element): XPageArea {
        val med = e.kid("medium")
        var w = XfaUnits.pt(med?.attr("short"), 612f); var h = XfaUnits.pt(med?.attr("long"), 792f)
        if (med?.attr("orientation") == "landscape") { val t = w; w = h; h = t }
        val areas = e.kids().filter { it.local() == "contentArea" }.map { a ->
            XArea(XfaUnits.pt(a.attr("x")), XfaUnits.pt(a.attr("y")), XfaUnits.pt(a.attr("w"), w), XfaUnits.pt(a.attr("h"), h), a.attr("name").orEmpty())
        }.ifEmpty { listOf(XArea(0f, 0f, w, h)) }
        val content = e.kids().mapNotNull { node(it) }
        val occurMax = e.kid("occur")?.attr("max")?.toIntOrNull() ?: -1
        return XPageArea(e.attr("name").orEmpty(), e.attr("id").orEmpty(), w, h, areas, content, occurMax)
    }

    private fun geom(e: Element) = XGeom(
        XfaUnits.pt(e.attr("x")), XfaUnits.pt(e.attr("y")), e.attr("w")?.let { XfaUnits.pt(it) }, e.attr("h")?.let { XfaUnits.pt(it) },
        e.attr("minW")?.let { XfaUnits.pt(it) }, e.attr("minH")?.let { XfaUnits.pt(it) }, e.attr("maxW")?.let { XfaUnits.pt(it) },
        e.attr("maxH")?.let { XfaUnits.pt(it) }, e.attr("anchorType") ?: "topLeft", e.attr("colSpan")?.toIntOrNull() ?: 1,
    )

    private fun margin(e: Element?): XMargin {
        val m = e?.kid("margin") ?: return XMargin.NONE
        return XMargin(XfaUnits.pt(m.attr("leftInset")), XfaUnits.pt(m.attr("topInset")), XfaUnits.pt(m.attr("rightInset")), XfaUnits.pt(m.attr("bottomInset")))
    }

    private fun colorOf(e: Element?): Int? = e?.kid("color")?.let { XfaUnits.color(it.attr("value"), 0xFF000000.toInt()) }

    private fun edge(e: Element?): XEdge? {
        if (e == null) return XEdge(0xFF000000.toInt(), 0.5f)
        if (e.attr("presence").let { it == "hidden" || it == "invisible" }) return null
        val stroke = e.attr("stroke") ?: "solid"
        return XEdge(colorOf(e) ?: 0xFF000000.toInt(), XfaUnits.pt(e.attr("thickness"), 0.5f), stroke == "dashed" || stroke == "dotted" || stroke == "dashDot")
    }

    private fun fill(e: Element?): Int? {
        val f = e?.kid("fill") ?: return null
        if (f.attr("presence").let { it == "hidden" || it == "invisible" }) return null
        return colorOf(f) ?: 0xFFFFFFFF.toInt()
    }

    internal fun border(e: Element?): XBorder? {
        val b = e?.kid("border") ?: return null
        if (b.attr("presence").let { it == "hidden" || it == "invisible" }) return null
        val es = b.kids().filter { it.local() == "edge" }.map { edge(it) }
        val edges = when (es.size) {
            0 -> List(4) { XEdge(0xFF000000.toInt(), 0.5f) }
            1 -> List(4) { es[0] }
            2 -> listOf(es[0], es[1], es[0], es[1])
            3 -> listOf(es[0], es[1], es[2], es[1])
            else -> es.take(4)
        }
        val radius = b.kid("corner")?.attr("radius")?.let { XfaUnits.pt(it) } ?: 0f
        return XBorder(edges, fill(b), radius)
    }

    private fun font(e: Element?): XFont? {
        val f = e?.kid("font") ?: return null
        return XFont(XfaUnits.pt(f.attr("size"), 10f), f.attr("weight") == "bold", f.attr("posture") == "italic",
            colorOf(f.kid("fill")) ?: 0xFF000000.toInt(), f.attr("typeface").orEmpty())
    }

    private fun para(e: Element?): XPara? {
        val p = e?.kid("para") ?: return null
        return XPara(p.attr("hAlign"), p.attr("vAlign"), XfaUnits.pt(p.attr("spaceAbove")), XfaUnits.pt(p.attr("spaceBelow")),
            XfaUnits.pt(p.attr("marginLeft")), XfaUnits.pt(p.attr("marginRight")))
    }

    /** Plain text of a value / caption (rich text paragraphs become lines). */
    internal fun textOf(v: Element?): String {
        if (v == null) return ""
        val ex = v.kids().firstOrNull { it.local() == "exData" }
        if (ex != null) {
            val sb = StringBuilder()
            fun walk(n: Node) {
                if (n.nodeType == Node.TEXT_NODE) sb.append(n.nodeValue.replace(Regex("\\s+"), " "))
                var c = n.firstChild
                while (c != null) { walk(c); c = c.nextSibling }
                if (n is Element && (n.local() == "p" || n.local() == "br" || n.local() == "div")) sb.append('\n')
            }
            walk(ex)
            return sb.toString().lines().joinToString("\n") { it.trim() }.trim()
        }
        val t = v.kids().firstOrNull { it.local() in setOf("text", "integer", "decimal", "float", "date", "time", "dateTime", "boolean") }
        return t?.textContent?.trim() ?: ""
    }

    private fun caption(e: Element): XCaption? {
        val c = e.kid("caption") ?: return null
        val text = textOf(c.kid("value"))
        return XCaption(text, c.attr("placement") ?: "left", c.attr("reserve")?.let { XfaUnits.pt(it) }?.takeIf { it >= 0f }, font(c), para(c),
            c.attr("presence").let { it == "hidden" || it == "invisible" })
    }

    private fun scripts(e: Element): XScripts {
        fun js(s: Element?): String? = s?.takeIf { it.attr("contentType")?.contains("javascript", true) == true }?.textContent?.takeIf { it.isNotBlank() }
        var init: String? = null
        for (ev in e.kids().filter { it.local() == "event" }) if (ev.attr("activity") == "initialize") init = js(ev.kid("script")) ?: init
        val calc = js(e.kid("calculate")?.kid("script"))
        return XScripts(init, calc)
    }

    private fun breakOf(e: Element, before: Boolean): XBreak? {
        e.kid(if (before) "breakBefore" else "breakAfter")?.let { b ->
            val tt = b.attr("targetType") ?: "auto"
            return when (tt) {
                "pageArea" -> XBreak(page = true, target = b.attr("target")?.removePrefix("#"))
                "contentArea" -> XBreak(contentArea = true, target = b.attr("target")?.removePrefix("#"))
                else -> null
            }
        }
        val b = e.kid("break") ?: return null
        val kind = b.attr(if (before) "before" else "after") ?: return null
        val target = b.attr(if (before) "beforeTarget" else "afterTarget")?.removePrefix("#")
        return when (kind) { "pageArea", "pageEven", "pageOdd" -> XBreak(page = true, target = target); "contentArea" -> XBreak(contentArea = true, target = target); else -> null }
    }

    private fun bind(e: Element): XBind {
        val b = e.kid("bind") ?: return XBind()
        return XBind(b.attr("match") ?: "once", b.attr("ref"))
    }

    private fun subform(e: Element, isRoot: Boolean = false, excl: Boolean = false): XSub {
        val occ = e.kid("occur")
        val occur = if (occ == null) XOccur() else {
            val min = occ.attr("min")?.toIntOrNull() ?: 1
            XOccur(min, occ.attr("max")?.toIntOrNull() ?: 1, occ.attr("initial")?.toIntOrNull() ?: min)
        }
        val children = e.kids().mapNotNull { node(it) }
        val layout = e.attr("layout") ?: if (excl) "position" else "position"
        return XSub(e.attr("name").orEmpty(), geom(e), e.attr("presence") ?: "visible", margin(e), border(e), font(e), para(e), scripts(e),
            layout, children, occur, bind(e), breakOf(e, true), breakOf(e, false),
            e.attr("columnWidths")?.split(Regex("\\s+"))?.map { XfaUnits.pt(it, -1f) }.orEmpty(),
            keepIntact = e.kid("keep")?.attr("intact").let { it == "contentArea" || it == "pageArea" } || (layout == "position" && !isRoot),
            isArea = e.local() == "area" || e.local() == "subformSet", isExclGroup = excl,
            required = e.kid("validate")?.attr("nullTest") == "error", caption = caption(e))
    }

    private fun node(c: Element): XNode? = when (c.local()) {
        "subform" -> subform(c)
        "subformSet", "area" -> subform(c).let { s -> XSub(s.name, s.box, s.presence, s.margin, s.border, s.font, s.para, s.scripts, if (c.local() == "area") "position" else "tb",
            s.children, XOccur(), XBind("none"), s.breakBefore, s.breakAfter, emptyList(), false, isArea = true) }
        "exclGroup" -> subform(c, excl = true)
        "field" -> field(c)
        "draw" -> draw(c)
        else -> null
    }

    private fun field(e: Element): XFieldT {
        val uiEl = e.kid("ui")?.kids()?.firstOrNull { it.local() != "picture" && it.local() != "extras" }
        val ui = when (uiEl?.local()) {
            "numericEdit" -> XUi.NUMERIC; "dateTimeEdit" -> XUi.DATE; "passwordEdit" -> XUi.PASSWORD; "checkButton" -> XUi.CHECK
            "choiceList" -> XUi.CHOICE; "signature" -> XUi.SIGNATURE; "button" -> XUi.BUTTON; "imageEdit" -> XUi.IMAGE; "barcode" -> XUi.BARCODE
            else -> XUi.TEXT
        }
        val lists = e.kids().filter { it.local() == "items" }
        val display = lists.firstOrNull { it.getAttribute("save") != "1" } ?: lists.firstOrNull()
        val save = lists.firstOrNull { it.getAttribute("save") == "1" } ?: display
        fun texts(x: Element?) = x?.kids()?.map { it.textContent.trim() }.orEmpty()
        val valueEl = e.kid("value")
        val textEl = valueEl?.kid("text") ?: valueEl?.kid("exData")
        val img = valueEl?.kid("image")?.let { decodeImage(it.textContent) }
        val access = e.attr("access")
        return XFieldT(e.attr("name").orEmpty(), geom(e), e.attr("presence") ?: "visible", margin(e), border(e), font(e), para(e), scripts(e),
            ui, caption(e), texts(display), texts(save), textOf(valueEl), img, bind(e),
            multiline = uiEl?.attr("multiLine") == "1",
            maxChars = textEl?.attr("maxChars")?.toIntOrNull() ?: 0, combCells = uiEl?.kid("comb")?.attr("numberOfCells")?.toIntOrNull() ?: 0,
            uiBorder = border(uiEl), uiMargin = margin(uiEl),
            readOnly = access == "readOnly" || access == "protected" || access == "nonInteractive",
            required = e.kid("validate")?.attr("nullTest") == "error",
            checkSize = XfaUnits.pt(uiEl?.attr("size"), 10f), roundCheck = uiEl?.attr("shape") == "round",
            listOpen = uiEl?.attr("open") ?: "userControl", textEntry = uiEl?.attr("textEntry") == "1",
            tooltip = e.kid("assist")?.kid("toolTip")?.textContent?.trim().orEmpty())
    }

    private fun draw(e: Element): XDraw {
        val v = e.kid("value")
        val first = v?.kids()?.firstOrNull()
        val content: XContent? = when (first?.local()) {
            "text", "exData", "integer", "decimal", "float", "date" -> XContent.Text(textOf(v))
            "line" -> XContent.Line(first.attr("slope") ?: "\\", edge(first.kid("edge")))
            "rectangle" -> XContent.Rect(border(XmlWrap.wrap(first, "border")) ?: XBorder(List(4) { edge(first.kid("edge")) }, fill(first)))
            "arc" -> XContent.Arc(edge(first.kid("edge")), fill(first))
            "image" -> XContent.Image(decodeImage(first.textContent), first.attr("aspect") ?: "fit")
            else -> null
        }
        return XDraw(e.attr("name").orEmpty(), geom(e), e.attr("presence") ?: "visible", margin(e), border(e), font(e), para(e), scripts(e), content, caption(e))
    }

    fun decodeImage(b64: String?): ByteArray? {
        val s = b64?.filter { !it.isWhitespace() } ?: return null
        if (s.length < 16) return null
        return runCatching { java.util.Base64.getDecoder().decode(s) }.getOrNull()
    }
}

/** A rectangle element is parsed like a border: its edges / corners / fill are direct children. */
internal object XmlWrap {
    fun wrap(e: Element, as_: String): Element {
        val doc = e.ownerDocument
        val holder = doc.createElement("holder")
        val b = doc.createElement(as_)
        var n = e.firstChild
        while (n != null) { b.appendChild(n.cloneNode(true)); n = n.nextSibling }
        holder.appendChild(b)
        return holder
    }
}
