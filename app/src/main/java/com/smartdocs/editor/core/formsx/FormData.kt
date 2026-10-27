package com.smartdocs.editor.core.formsx

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.fdf.FDFDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory

enum class DataFormat(val ext: String, val mime: String) {
    FDF("fdf", "application/vnd.fdf"), XFDF("xfdf", "application/vnd.adobe.xfdf"), XML("xml", "application/xml"), CSV("csv", "text/csv"), TXT("txt", "text/plain")
}

/**
 * Form data exchange (Nitro 8.73 / Foxit 26.79-26.82): export the values of an AcroForm as FDF, XFDF, XML, CSV
 * or tab-delimited text, import them back, and collect many returned forms into one CSV sheet (8.76 / 26.81).
 *
 * Value convention: fully qualified name -> raw value (check box / radio: the on-state name or "Off"; list boxes
 * with several selections: values joined by '\n').
 */
object FormData {

    // ================= values =================

    fun values(doc: PDDocument): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val form = doc.documentCatalog.getAcroForm(null) ?: return out
        FxEditor.terminals(form).forEach { f ->
            if (f is PDPushButton || f is PDSignatureField) return@forEach
            out[f.fullyQualifiedName] = valueOf(f)
        }
        return out
    }

    /** Check boxes and radio groups: their FDF values are names. */
    fun buttonNames(doc: PDDocument): Set<String> = doc.documentCatalog.getAcroForm(null)?.let { form ->
        FxEditor.terminals(form).filter { it is PDCheckBox || it is PDRadioButton }.map { it.fullyQualifiedName }.toSet() } ?: emptySet()

    private fun valueOf(f: PDTerminalField): String = when (f) {
        is PDChoice -> runCatching { f.value.joinToString("\n") }.getOrDefault("")
        is PDButton -> f.cosObject.getDictionaryObject(COSName.V).let { (it as? COSName)?.name ?: (it as? COSString)?.string ?: "Off" }
        else -> f.cosObject.getDictionaryObject(COSName.V).let { v -> when (v) { is COSString -> v.string; is com.tom_roush.pdfbox.cos.COSStream -> runCatching { v.toTextString() }.getOrDefault(""); else -> "" } }
    }

    // ================= export =================

    fun export(doc: PDDocument, format: DataFormat, out: OutputStream, pdfName: String? = null) {
        val v = values(doc)
        val bytes: ByteArray = when (format) {
            DataFormat.FDF -> fdf(v, pdfName ?: "", buttonNames(doc))
            DataFormat.XFDF -> xfdf(v, pdfName ?: "").toByteArray(Charsets.UTF_8)
            DataFormat.XML -> xml(v).toByteArray(Charsets.UTF_8)
            DataFormat.CSV -> (csvRow(v.keys.toList(), ',') + "\r\n" + csvRow(v.values.toList(), ',') + "\r\n").toByteArray(Charsets.UTF_8).let { byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + it }
            DataFormat.TXT -> (csvRow(v.keys.toList(), '\t') + "\r\n" + csvRow(v.values.toList(), '\t') + "\r\n").toByteArray(Charsets.UTF_8)
        }
        out.write(bytes)
    }

    private fun pdfString(s: String): String {
        if (s.all { it.code in 32..126 }) return "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
        val b = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)
        return "<" + b.joinToString("") { "%02X".format(it.toInt() and 0xFF) } + ">"
    }

    private fun pdfName(s: String): String = "/" + s.toByteArray(Charsets.UTF_8).joinToString("") { b ->
        val c = b.toInt() and 0xFF
        if (c in 33..126 && c.toChar() !in "/()<>[]{}%#") c.toChar().toString() else "#%02X".format(c)
    }

    /** FDF with a nested /Kids tree (hierarchical names), names as /V for check boxes and radios. */
    fun fdf(values: Map<String, String>, fileName: String, buttons: Set<String> = emptySet()): ByteArray {
        class Node(val name: String, val full: String) { val kids = LinkedHashMap<String, Node>(); var value: String? = null }
        val root = Node("", "")
        values.forEach { (k, v) ->
            var node = root
            k.split('.').forEach { part -> val parent = node; node = node.kids.getOrPut(part) { Node(part, if (parent.full.isEmpty()) part else parent.full + "." + part) } }
            node.value = v
        }
        val sb = StringBuilder()
        fun write(nd: Node) {
            sb.append("<< /T ").append(pdfString(nd.name))
            nd.value?.let { v ->
                val full = v
                sb.append(" /V ")
                when {
                    nd.full in buttons -> sb.append(pdfName(full))
                    full.contains('\n') -> sb.append("[").append(full.split('\n').joinToString(" ") { pdfString(it) }).append("]")
                    else -> sb.append(pdfString(full))
                }
            }
            if (nd.kids.isNotEmpty()) { sb.append(" /Kids ["); nd.kids.values.forEach { write(it); sb.append(' ') }; sb.append("]") }
            sb.append(" >>")
        }
        val header = "%FDF-1.2\n%\u00E2\u00E3\u00CF\u00D3\n"
        val obj = StringBuilder("1 0 obj\n<< /FDF << ")
        if (fileName.isNotEmpty()) obj.append("/F ").append(pdfString(fileName)).append(' ')
        obj.append("/Fields [")
        root.kids.values.forEach { write(it); sb.append('\n') }
        obj.append(sb).append("] >> /Type /Catalog >>\nendobj\n")
        val xrefAt = header.length + obj.length
        val tail = "xref\n0 2\n0000000000 65535 f \n" + String.format(java.util.Locale.US, "%010d", header.length) + " 00000 n \n" +
            "trailer\n<< /Size 2 /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n"
        return (header + obj + tail).toByteArray(Charsets.ISO_8859_1)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** XFDF with nested <field> elements for hierarchical names; several <value> for multi-selections. */
    fun xfdf(values: Map<String, String>, fileName: String): String {
        class Node(val name: String) { val kids = LinkedHashMap<String, Node>(); var value: String? = null }
        val root = Node("")
        values.forEach { (k, v) -> var node = root; k.split('.').forEach { p -> node = node.kids.getOrPut(p) { Node(p) } }; node.value = v }
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xfdf xmlns=\"http://ns.adobe.com/xfdf/\" xml:space=\"preserve\">\n<fields>\n")
        fun write(nd: Node, depth: Int) {
            sb.append("  ".repeat(depth)).append("<field name=\"").append(esc(nd.name)).append("\">")
            nd.value?.let { v -> v.split('\n').forEach { sb.append("<value>").append(esc(it)).append("</value>") } }
            if (nd.kids.isNotEmpty()) { sb.append('\n'); nd.kids.values.forEach { write(it, depth + 1) }; sb.append("  ".repeat(depth)) }
            sb.append("</field>\n")
        }
        root.kids.values.forEach { write(it, 1) }
        sb.append("</fields>\n")
        if (fileName.isNotEmpty()) sb.append("<f href=\"").append(esc(fileName)).append("\"/>\n")
        sb.append("</xfdf>\n")
        return sb.toString()
    }

    /** Simple XML data: one <field name="full.name"> element per field. */
    fun xml(values: Map<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<fields>\n")
        values.forEach { (k, v) -> append("  <field name=\"").append(esc(k)).append("\">").append(esc(v)).append("</field>\n") }
        append("</fields>\n")
    }

    fun csvRow(cells: List<String>, sep: Char): String = cells.joinToString(sep.toString()) { c ->
        if (c.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + c.replace("\"", "\"\"") + "\"" else c
    }

    // ================= parse =================

    fun detect(fileName: String, head: ByteArray): DataFormat? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        DataFormat.entries.firstOrNull { it.ext == ext }?.let { return it }
        val s = String(head.copyOf(minOf(head.size, 512)), Charsets.ISO_8859_1).trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', ' ', '\n', '\r', '\t')
        return when {
            s.startsWith("%FDF") -> DataFormat.FDF
            s.contains("<xfdf") -> DataFormat.XFDF
            s.startsWith("<?xml") || s.startsWith("<") -> DataFormat.XML
            s.lineSequence().firstOrNull()?.contains('\t') == true -> DataFormat.TXT
            s.lineSequence().firstOrNull()?.contains(',') == true -> DataFormat.CSV
            else -> null
        }
    }

    fun parse(bytes: ByteArray, format: DataFormat): Map<String, String> = when (format) {
        DataFormat.FDF -> parseFdf(bytes)
        DataFormat.XFDF -> parseXfdf(bytes)
        DataFormat.XML -> parseXml(bytes)
        DataFormat.CSV -> parseTable(bytes, ',').firstRow()
        DataFormat.TXT -> parseTable(bytes, '\t').firstRow()
    }

    private fun List<Map<String, String>>.firstRow() = firstOrNull() ?: emptyMap()

    private fun strOf(b: COSBase?): String? = when (b) {
        is COSString -> b.string
        is COSName -> b.name
        is COSArray -> (0 until b.size()).mapNotNull { strOf(b.getObject(it)) }.joinToString("\n")
        else -> null
    }

    fun parseFdf(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        FDFDocument.load(ByteArrayInputStream(bytes)).use { fdf ->
            val fields = (fdf.catalog.cosObject.getDictionaryObject(COSName.getPDFName("FDF")) as? COSDictionary)?.getDictionaryObject(COSName.FIELDS) as? COSArray ?: return out
            fun walk(arr: COSArray, prefix: String, depth: Int) {
                if (depth > 32) return
                for (i in 0 until arr.size()) {
                    val d = arr.getObject(i) as? COSDictionary ?: continue
                    val t = strOf(d.getDictionaryObject(COSName.T)) ?: continue
                    val full = if (prefix.isEmpty()) t else "$prefix.$t"
                    strOf(d.getDictionaryObject(COSName.V))?.let { out[full] = it }
                    (d.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { walk(it, full, depth + 1) }
                }
            }
            walk(fields, "", 0)
        }
        return out
    }

    private fun dom(bytes: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun children(e: Element, tag: String): List<Element> {
        val out = ArrayList<Element>(); val nl = e.childNodes
        for (i in 0 until nl.length) (nl.item(i) as? Element)?.let { if (it.tagName.substringAfter(':') == tag) out += it }
        return out
    }

    fun parseXfdf(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val root = dom(bytes).documentElement
        val fieldsEl = children(root, "fields").firstOrNull() ?: return out
        fun walk(e: Element, prefix: String, depth: Int) {
            if (depth > 32) return
            children(e, "field").forEach { f ->
                val name = f.getAttribute("name")
                val full = if (prefix.isEmpty()) name else "$prefix.$name"
                val vals = children(f, "value").map { it.textContent ?: "" } + children(f, "value-richtext").map { it.textContent ?: "" }
                if (vals.isNotEmpty()) out[full] = vals.joinToString("\n")
                walk(f, full, depth + 1)
            }
        }
        walk(fieldsEl, "", 0)
        return out
    }

    /** Our <fields><field name=...> or Acrobat-style XML (element paths of leaf elements). */
    fun parseXml(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val root = dom(bytes).documentElement
        if (root.tagName.substringAfter(':') == "xfdf") return parseXfdf(bytes)
        val named = root.getElementsByTagName("field")
        if (named.length > 0 && (named.item(0) as Element).hasAttribute("name")) {
            for (i in 0 until named.length) { val e = named.item(i) as Element; out[e.getAttribute("name")] = e.textContent ?: "" }
            return out
        }
        fun walk(e: Element, prefix: String, depth: Int) {
            if (depth > 32) return
            val kids = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
            if (kids.isEmpty()) { if (prefix.isNotEmpty()) out[prefix] = e.textContent ?: ""; return }
            kids.forEach { k -> walk(k, if (prefix.isEmpty()) k.tagName else "$prefix.${k.tagName}", depth + 1) }
        }
        (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }.forEach { walk(it, it.tagName, 1) }
        return out
    }

    /** RFC 4180 table (header row + data rows) -> one map per data row. */
    fun parseTable(bytes: ByteArray, sep: Char): List<Map<String, String>> {
        var text = String(bytes, Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                sep -> { row += cell.toString(); cell.clear() }
                '\r' -> {}
                '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        val header = rows.firstOrNull() ?: return emptyList()
        return rows.drop(1).filter { r -> r.any { it.isNotEmpty() } }.map { r -> header.indices.associate { k -> header[k] to (r.getOrNull(k) ?: "") } }
    }

    // ================= import =================

    /** Put [values] into the form fields of [doc]. Returns how many fields were set. */
    fun importInto(doc: PDDocument, values: Map<String, String>): Int {
        val form = doc.documentCatalog.getAcroForm(null) ?: return 0
        var count = 0
        values.forEach { (name, v) ->
            val f = form.getField(name) as? PDTerminalField ?: return@forEach
            if (f is PDPushButton || f is PDSignatureField) return@forEach
            val ok = when (f) {
                is PDCheckBox -> {
                    val on = f.widgets.mapNotNull { FxEditor.onState(it) }.firstOrNull() ?: "Yes"
                    val checked = v == on || v.equals("true", true) || v.equals("yes", true) || v.equals("on", true) || v == "1"
                    setButton(f, if (checked) on else "Off")
                }
                is PDRadioButton -> {
                    val states = f.widgets.mapNotNull { FxEditor.onState(it) }
                    setButton(f, if (v in states) v else "Off")
                }
                is PDChoice -> {
                    val list = v.split('\n').filter { it.isNotEmpty() }
                    try { if (f is PDListBox && f.isMultiSelect) f.setValue(list) else f.setValue(list.firstOrNull() ?: ""); true }
                    catch (_: Throwable) {
                        f.cosObject.setItem(COSName.V, if (list.size > 1) COSArray().apply { list.forEach { add(COSString(it)) } } else COSString(list.firstOrNull() ?: ""))
                        form.needAppearances = true; true
                    }
                }
                is PDTextField -> {
                    try { f.value = v; true } catch (_: Throwable) { f.cosObject.setString(COSName.V, v); form.needAppearances = true; true }
                }
                else -> false
            }
            if (ok) count++
        }
        runCatching { FxBarcodes.refreshAll(doc) }
        return count
    }

    /** Set a check box / radio by its state name on the COS level (works for any appearance set). */
    private fun setButton(f: PDButton, state: String): Boolean {
        f.cosObject.setItem(COSName.V, COSName.getPDFName(state))
        f.widgets.forEach { w ->
            val on = FxEditor.onState(w)
            w.cosObject.setItem(COSName.AS, if (on != null && on == state) COSName.getPDFName(state) else COSName.Off)
        }
        return true
    }

    fun importFile(doc: PDDocument, file: File): Int {
        val bytes = file.readBytes()
        val fmt = detect(file.name, bytes) ?: error("unknown form data format")
        return importInto(doc, parse(bytes, fmt))
    }

    // ================= collect responses =================

    /**
     * Form to sheet / collect returned forms: one CSV row per PDF of [files] (header = union of field names, first
     * column the file name). [append] continues an existing CSV (its header is kept and extended). Returns the CSV.
     */
    fun collect(files: List<Pair<String, Map<String, String>>>, append: ByteArray? = null): String {
        val existing = append?.let { parseTable(it, ',') } ?: emptyList()
        val oldHeader = append?.let { b -> String(b, Charsets.UTF_8).removePrefix("\uFEFF").lineSequence().firstOrNull()?.let { csvHeader(it) } } ?: emptyList()
        val header = LinkedHashSet<String>()
        header += FILE_COL
        oldHeader.forEach { header += it }
        files.forEach { (_, v) -> header += v.keys }
        val h = header.toList()
        val sb = StringBuilder("\uFEFF").append(csvRow(h, ',')).append("\r\n")
        existing.forEach { r -> sb.append(csvRow(h.map { r[it] ?: "" }, ',')).append("\r\n") }
        files.forEach { (name, v) -> sb.append(csvRow(h.map { if (it == FILE_COL) name else v[it] ?: "" }, ',')).append("\r\n") }
        return sb.toString()
    }

    private fun csvHeader(line: String): List<String> {
        val r = parseTable((line + "\n" + "x\n").toByteArray(), ',')
        return r.firstOrNull()?.keys?.toList() ?: emptyList()
    }

    const val FILE_COL = "file"
}
