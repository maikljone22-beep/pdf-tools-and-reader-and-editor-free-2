package com.smartdocs.editor.core.forms

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * XFA support without an XFA engine:
 *  - detection of static (with AcroForm fallback) vs dynamic XFA,
 *  - dynamic XFA: the template's field list (names, captions, types, choices) bound to `xfa:datasets` with
 *    normal (name-based) or `bind match="dataRef"` binding, edited as a list and written back by rewriting only
 *    the datasets packet of the XFA stream (the template, config, etc. are kept as they are).
 * Rendering / flattening a dynamic XFA layout is not possible on device.
 */
object XfaSupport {

    enum class Kind { NONE, STATIC, DYNAMIC }

    data class Info(val kind: Kind, val acroFieldCount: Int)

    enum class XType { TEXT, NUMBER, DATE, CHECK, CHOICE, RADIO, SIGNATURE, BUTTON, PASSWORD, IMAGE, OTHER }

    data class XField(
        val path: String,                 // data binding path, e.g. form1.Page1.Name[0]
        val name: String,
        val caption: String,
        val type: XType,
        val options: List<String> = emptyList(),      // display texts
        val exportValues: List<String> = emptyList(), // saved values (same size as options when present)
        val value: String = "",
        val multiline: Boolean = false,
        val bound: Boolean = true,        // false for bind match="none" (not saved)
    )

    private data class Seg(val name: String, val index: Int)

    // ---------------------------------------------------------------- detection

    fun detect(doc: PDDocument): Info {
        val acro = doc.documentCatalog.acroForm ?: return Info(Kind.NONE, 0)
        if (!acro.hasXFA()) return Info(Kind.NONE, 0)
        val n = runCatching { acro.fieldTree.count() }.getOrDefault(0)
        return Info(if (acro.xfaIsDynamic() || n == 0) Kind.DYNAMIC else Kind.STATIC, n)
    }

    suspend fun detect(file: File, password: String? = null): Info = withContext(Dispatchers.IO) {
        runCatching { PdfOps.load(file, password).use { detect(it) } }.getOrDefault(Info(Kind.NONE, 0))
    }

    // ---------------------------------------------------------------- XML helpers

    private fun parse(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun Node.local(): String = (localName ?: nodeName).substringAfter(':')

    private fun Element.children(): List<Element> {
        val out = ArrayList<Element>(); var n = firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }

    private fun Element.child(local: String): Element? = children().firstOrNull { it.local() == local }

    private fun findFirst(root: Node, local: String): Element? {
        if (root is Element && root.local() == local) return root
        var n = root.firstChild
        while (n != null) { findFirst(n, local)?.let { return it }; n = n.nextSibling }
        return null
    }

    private fun textOf(e: Element?): String = e?.textContent?.trim().orEmpty()

    private fun captionOf(e: Element): String {
        val cap = e.child("caption") ?: return ""
        val v = cap.child("value") ?: return textOf(cap)
        return textOf(v.children().firstOrNull() ?: v)
    }

    private fun typeOf(field: Element): Pair<XType, Boolean> {
        val ui = field.child("ui")?.children()?.firstOrNull() ?: return XType.TEXT to false
        return when (ui.local()) {
            "textEdit" -> XType.TEXT to (ui.getAttribute("multiLine") == "1")
            "numericEdit" -> XType.NUMBER to false
            "dateTimeEdit" -> XType.DATE to false
            "checkButton" -> XType.CHECK to false
            "choiceList" -> XType.CHOICE to false
            "signature" -> XType.SIGNATURE to false
            "button" -> XType.BUTTON to false
            "passwordEdit" -> XType.PASSWORD to false
            "imageEdit" -> XType.IMAGE to false
            "barcode" -> XType.OTHER to false
            else -> XType.TEXT to false
        }
    }

    private fun items(field: Element): Pair<List<String>, List<String>> {
        val lists = field.children().filter { it.local() == "items" }
        val display = lists.firstOrNull { it.getAttribute("save") != "1" } ?: lists.firstOrNull()
        val save = lists.firstOrNull { it.getAttribute("save") == "1" } ?: display
        fun texts(e: Element?) = e?.children()?.map { textOf(it) }.orEmpty()
        return texts(display) to texts(save)
    }

    private fun defaultValue(field: Element): String = field.child("value")?.children()?.firstOrNull()?.let { textOf(it) }.orEmpty()

    /** `$.a.b[0]`, `$record.a.b`, `$data.a` → segments relative to the data root. */
    private fun refPath(ref: String, base: List<Seg>): List<Seg>? {
        var r = ref.trim()
        val relative = r.startsWith("$.")
        r = r.removePrefix("\$record.").removePrefix("\$data.").removePrefix("$.").removePrefix("!")
        if (r.isBlank() || r.startsWith("$")) return null
        val segs = r.split('.').filter { it.isNotBlank() }.map { s ->
            val m = Regex("""^(.+?)\[(\d+|\*)]$""").find(s)
            if (m != null) Seg(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0) else Seg(s, 0)
        }
        return if (relative) base + segs else (base.take(1) + segs)
    }

    // ---------------------------------------------------------------- reading

    private fun walk(e: Element, path: List<Seg>, out: MutableList<Pair<List<Seg>, XField>>, counters: MutableMap<String, Int>) {
        for (c in e.children()) {
            when (c.local()) {
                "pageSet", "proto", "draw", "event", "script", "variables" -> Unit
                "subform", "subformSet", "area" -> {
                    val n = c.getAttribute("name")
                    if (n.isNullOrBlank() || c.child("bind")?.getAttribute("match") == "none") walk(c, path, out, counters)
                    else {
                        val key = path.joinToString(".") { "${it.name}[${it.index}]" } + "/" + n
                        val idx = counters.getOrDefault(key, 0); counters[key] = idx + 1
                        walk(c, path + Seg(n, idx), out, counters)
                    }
                }
                "exclGroup" -> {
                    val n = c.getAttribute("name")
                    val opts = c.children().filter { it.local() == "field" }
                    val captions = opts.map { o -> captionOf(o).ifBlank { o.getAttribute("name") } }
                    val exports = opts.map { o -> items(o).second.firstOrNull() ?: o.getAttribute("name") }
                    if (n.isNullOrBlank()) { walk(c, path, out, counters); continue }
                    val key = path.joinToString(".") + "/" + n
                    val idx = counters.getOrDefault(key, 0); counters[key] = idx + 1
                    val segs = bindPath(c, path, Seg(n, idx))
                    out += (segs ?: path + Seg(n, idx)) to XField("", n, captionOf(c).ifBlank { n }, XType.RADIO, captions, exports, bound = segs != null)
                }
                "field" -> {
                    val n = c.getAttribute("name")
                    if (n.isNullOrBlank()) continue
                    val key = path.joinToString(".") + "/" + n
                    val idx = counters.getOrDefault(key, 0); counters[key] = idx + 1
                    val (type, multi) = typeOf(c)
                    if (type == XType.BUTTON) continue
                    val (disp, save) = if (type == XType.CHOICE || type == XType.CHECK) items(c) else (emptyList<String>() to emptyList())
                    val segs = bindPath(c, path, Seg(n, idx))
                    out += (segs ?: path + Seg(n, idx)) to XField("", n, captionOf(c).ifBlank { c.getAttribute("assist").orEmpty() }.ifBlank { n }, type, disp, save, defaultValue(c), multi, bound = segs != null)
                }
                else -> walk(c, path, out, counters)
            }
        }
    }

    /** Data path for a field/exclGroup, null when it is not bound to data (bind match="none"). */
    private fun bindPath(node: Element, path: List<Seg>, self: Seg): List<Seg>? {
        val bind = node.child("bind") ?: return path + self
        return when (bind.getAttribute("match")) {
            "none" -> null
            "global" -> path.take(1) + Seg(self.name, 0)
            "dataRef" -> refPath(bind.getAttribute("ref"), path) ?: (path + self)
            else -> path + self
        }
    }

    private fun dataRoot(xml: Document): Element? {
        val datasets = findFirst(xml, "datasets") ?: return null
        return datasets.children().firstOrNull { it.local() == "data" }
    }

    private fun locate(data: Element, segs: List<Seg>, create: Boolean): Element? {
        var cur = data
        for (s in segs) {
            val matches = cur.children().filter { it.local() == s.name }
            var next = matches.getOrNull(s.index)
            if (next == null) {
                if (!create) return null
                repeat(s.index + 1 - matches.size) {
                    val el = cur.ownerDocument.createElement(s.name)
                    cur.appendChild(el); next = el
                }
            }
            cur = next!!
        }
        return cur
    }

    private fun pathString(segs: List<Seg>) = segs.joinToString(".") { "${it.name}[${it.index}]" }

    private fun xfaBytes(doc: PDDocument): ByteArray? = doc.documentCatalog.acroForm?.xfa?.bytes

    /** Fields of the XFA template with their current data values. */
    fun readFields(doc: PDDocument): List<XField> {
        val bytes = xfaBytes(doc) ?: return emptyList()
        val xml = parse(bytes)
        val template = findFirst(xml, "template") ?: return emptyList()
        val raw = ArrayList<Pair<List<Seg>, XField>>()
        walk(template, emptyList(), raw, HashMap())
        val data = dataRoot(xml)
        return raw.map { (segs, f) ->
            val v = data?.let { d -> locate(d, segs, false) }?.let { el -> if (el.children().isEmpty()) textOf(el) else null }
            f.copy(path = pathString(segs), value = v ?: f.value)
        }
    }

    suspend fun readFields(file: File, password: String? = null): List<XField> = withContext(Dispatchers.IO) {
        PdfOps.load(file, password).use { readFields(it) }
    }

    // ---------------------------------------------------------------- writing

    private fun parsePath(p: String): List<Seg> = p.split('.').map { s ->
        val m = Regex("""^(.+)\[(\d+)]$""").find(s)
        if (m != null) Seg(m.groupValues[1], m.groupValues[2].toInt()) else Seg(s, 0)
    }

    private fun serialize(node: Node, omitDecl: Boolean): ByteArray {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, if (omitDecl) "yes" else "no")
        val bos = ByteArrayOutputStream()
        t.transform(DOMSource(node), StreamResult(bos))
        return bos.toByteArray()
    }

    private fun newStream(doc: PDDocument, data: ByteArray): COSStream {
        val s = doc.document.createCOSStream()
        s.createOutputStream(COSName.FLATE_DECODE).use { it.write(data) }
        return s
    }

    /**
     * Write [values] (path → value) into the datasets packet of [src] and save to [out]. Only the datasets packet
     * is replaced; usage-rights signatures (/Perms /UR3), which any change invalidates, are removed.
     */
    suspend fun saveValues(src: File, values: Map<String, String>, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        PdfOps.load(src, password).use { doc ->
            val acro = doc.documentCatalog.acroForm ?: throw IllegalStateException("no form")
            val bytes = acro.xfa?.bytes ?: throw IllegalStateException("no XFA")
            val xml = parse(bytes)
            var datasets = findFirst(xml, "datasets")
            if (datasets == null) {
                val root = xml.documentElement
                datasets = xml.createElementNS("http://www.xfa.org/schema/xfa-data/1.0/", "xfa:datasets")
                root.appendChild(datasets)
            }
            var data = datasets!!.children().firstOrNull { it.local() == "data" }
            if (data == null) {
                data = xml.createElementNS("http://www.xfa.org/schema/xfa-data/1.0/", "xfa:data")
                datasets.appendChild(data)
            }
            values.forEach { (path, v) ->
                val el = locate(data!!, parsePath(path), true) ?: return@forEach
                while (el.firstChild != null) el.removeChild(el.firstChild)
                el.textContent = v
            }
            val xfaBase = acro.cosObject.getDictionaryObject(COSName.XFA)
            if (xfaBase is COSArray) {
                var replaced = false
                var i = 0
                while (i + 1 < xfaBase.size()) {
                    val key = (xfaBase.getObject(i) as? COSString)?.string
                    if (key == "datasets") { xfaBase.set(i + 1, newStream(doc, serialize(datasets, true))); replaced = true }
                    i += 2
                }
                if (!replaced) {
                    // insert before the closing "</xdp:xdp>" packet when present, else append
                    val closeIdx = (0 until xfaBase.size() step 2).firstOrNull { (xfaBase.getObject(it) as? COSString)?.string == "</xdp:xdp>" }
                    val at = closeIdx ?: xfaBase.size()
                    xfaBase.add(at, newStream(doc, serialize(datasets, true)))
                    xfaBase.add(at, COSString("datasets"))
                }
            } else {
                acro.cosObject.setItem(COSName.XFA, newStream(doc, serialize(xml, false)))
            }
            // usage rights (Reader extensions) become invalid after any change
            (doc.documentCatalog.cosObject.getDictionaryObject(COSName.PERMS) as? COSDictionary)?.removeItem(COSName.getPDFName("UR3"))
            val tmp = File(out.parentFile, ".${out.name}.${System.nanoTime()}.tmp")
            try { doc.save(tmp); if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() } } finally { if (tmp.exists()) tmp.delete() }
        }
    }

    /** Value a check box stores when [checked] (its first saved item, "1"/"0" by default). */
    fun checkValue(f: XField, checked: Boolean): String =
        if (checked) f.exportValues.getOrNull(0) ?: "1" else f.exportValues.getOrNull(1) ?: "0"

    fun isChecked(f: XField): Boolean = f.value.isNotBlank() && f.value == (f.exportValues.getOrNull(0) ?: "1")
}
