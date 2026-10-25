package com.smartdocs.editor.core.office

import java.io.File

/**
 * Light DOCX editing: replace a body paragraph's text (keeping its paragraph properties and the
 * first run's formatting), insert new paragraphs, delete paragraphs. Saving splices the original
 * word/document.xml text (no re-serialisation, so unknown markup survives) and stream-copies every
 * other ZIP entry unchanged.
 *
 * Only direct children `w:p` of `w:body` are editable; tables, content controls and section
 * properties are kept verbatim. Pictures/objects inside an edited paragraph are kept after the new
 * text; hyperlinks, fields, comments anchors and mixed run formatting of an edited paragraph are lost.
 */
class DocxEditor private constructor(private val src: File, private val docPath: String, private val xml: String) {

    class EditablePara internal constructor(
        /** Stable key for UI lists. */
        val key: Long,
        /** Index among the original body paragraphs, or -1 for an inserted paragraph. */
        val origIndex: Int,
        val originalText: String,
        var text: String,
        val rtl: Boolean,
        /** Paragraph contains pictures/objects that are kept after the text when edited. */
        val hasObjects: Boolean,
        /** For inserted paragraphs: original paragraph whose formatting is copied. */
        internal val templateIndex: Int,
    ) {
        val isNew: Boolean get() = origIndex < 0
        val isChanged: Boolean get() = isNew || text != originalText
    }

    private val w = XmlScan.prefixFor(xml, W_NS, "w")
    private val bodyElem: XmlElem = XmlScan.find(xml, "body") ?: throw OfficeFormatException("w:body missing")
    private val bodyKids: List<XmlElem> = XmlScan.children(xml, bodyElem)
    private val paraElems: List<XmlElem> = bodyKids.filter { it.local == "p" }
    private var nextKey = 1L

    /** Current paragraph list in document order; edit through the methods below or the `text` field. */
    val paragraphs: MutableList<EditablePara> = paraElems.mapIndexed { i, e ->
        val t = paragraphText(e)
        EditablePara(nextKey++, i, t, t, isRtl(e), hasObjects(e), i)
    }.toMutableList()

    fun setText(position: Int, text: String) { paragraphs[position].text = text }

    /** Inserts a paragraph after [position] (-1 = at the start) copying that paragraph's formatting. */
    fun insertAfter(position: Int, text: String = ""): EditablePara {
        val tmpl = paragraphs.getOrNull(position) ?: paragraphs.getOrNull(0)
        val templ = tmpl?.let { if (it.isNew) it.templateIndex else it.origIndex } ?: -1
        val p = EditablePara(nextKey++, -1, "", text, tmpl?.rtl ?: hasRtlChars(text), false, templ)
        paragraphs.add(position + 1, p)
        return p
    }

    fun delete(position: Int) { paragraphs.removeAt(position) }

    val isModified: Boolean get() = paragraphs.size != paraElems.size || paragraphs.anyIndexed { i, p -> p.origIndex != i || p.isChanged }

    private inline fun <T> List<T>.anyIndexed(f: (Int, T) -> Boolean): Boolean { for (i in indices) if (f(i, this[i])) return true; return false }

    /** Builds the new document.xml text. */
    fun buildXml(): String {
        // new paragraphs anchored to the closest preceding original paragraph (-1 = body start)
        val anchored = HashMap<Int, MutableList<EditablePara>>()
        val kept = HashMap<Int, EditablePara>()
        var anchor = -1
        for (p in paragraphs) {
            if (p.isNew) anchored.getOrPut(anchor) { ArrayList() }.add(p) else { kept[p.origIndex] = p; anchor = p.origIndex }
        }
        val sb = StringBuilder(xml.length + 256)
        var cursor = bodyElem.openEnd
        sb.append(xml, 0, cursor)
        anchored[-1]?.forEach { sb.append(newParagraphXml(it)) }
        var pIndex = 0
        for (k in bodyKids) {
            sb.append(xml, cursor, k.start)
            cursor = k.end
            if (k.local != "p") { sb.append(xml, k.start, k.end); continue }
            val idx = pIndex++
            val ep = kept[idx]
            if (ep != null) sb.append(if (ep.isChanged) rewriteParagraph(paraElems[idx], ep.text) else xml.substring(k.start, k.end))
            anchored[idx]?.forEach { sb.append(newParagraphXml(it)) }
        }
        sb.append(xml, cursor, xml.length)
        return sb.toString()
    }

    /** Writes the edited document to [dst] (must differ from the source file). */
    fun save(dst: File) {
        val bytes = buildXml().toByteArray(Charsets.UTF_8)
        dst.outputStream().use { OoxmlPackage.rewrite(src, it, mapOf(docPath to bytes)) }
    }

    /** Saves over the source file through a temp file next to it. */
    fun saveInPlace() = replaceFileSafely(src) { save(it) }

    // ---------------- paragraph XML helpers ----------------

    private fun kids(e: XmlElem) = XmlScan.children(xml, e)

    private fun paragraphText(e: XmlElem): String {
        val sb = StringBuilder()
        fun walk(el: XmlElem) {
            when (el.local) {
                "t" -> if (!el.selfClosing) sb.append(XmlScan.unescape(xml.substring(el.innerRange(xml).first, el.innerRange(xml).last + 1)))
                "tab", "ptab" -> sb.append('\t')
                "br", "cr" -> sb.append('\n')
                "Fallback", "del", "txbxContent", "drawing", "pict", "object", "instrText", "delText", "pPr", "rPr", "moveFrom" -> {}
                else -> kids(el).forEach { walk(it) }
            }
        }
        kids(e).forEach { walk(it) }
        return sb.toString()
    }

    private fun isRtl(e: XmlElem): Boolean {
        val ppr = kids(e).firstOrNull { it.local == "pPr" } ?: return false
        val b = kids(ppr).firstOrNull { it.local == "bidi" } ?: return false
        return XmlScan.attr(xml, b, "val").let { it == null || it == "1" || it == "true" || it == "on" }
    }

    private fun hasObjects(e: XmlElem): Boolean = objectRx.containsMatchIn(xml.substring(e.start, e.end))

    private fun openTag(e: XmlElem): String {
        val t = xml.substring(e.start, e.openEnd)
        return if (e.selfClosing) t.removeSuffix("/>").trimEnd() + ">" else t
    }

    private fun firstRunProps(e: XmlElem): String? {
        fun search(el: XmlElem): String? {
            for (k in kids(el)) {
                if (k.local == "r") kids(k).firstOrNull { it.local == "rPr" }?.let { return xml.substring(it.start, it.end) }
                if (k.local == "hyperlink" || k.local == "ins" || k.local == "smartTag" || k.local == "fldSimple") search(k)?.let { return it }
            }
            return null
        }
        return search(e)
    }

    private fun runsXml(text: String, rPr: String?): String {
        val sb = StringBuilder()
        val lines = text.split('\n')
        sb.append("<$w:r>")
        if (rPr != null) sb.append(rPr)
        lines.forEachIndexed { li, line ->
            if (li > 0) sb.append("<$w:br/>")
            line.split('\t').forEachIndexed { ti, seg ->
                if (ti > 0) sb.append("<$w:tab/>")
                if (seg.isNotEmpty()) sb.append("<$w:t xml:space=\"preserve\">").append(XmlScan.escape(seg)).append("</$w:t>")
            }
        }
        sb.append("</$w:r>")
        return sb.toString()
    }

    private fun rewriteParagraph(e: XmlElem, text: String): String {
        val k = kids(e)
        val pPr = k.firstOrNull { it.local == "pPr" }?.let { xml.substring(it.start, it.end) } ?: ""
        val objects = k.filter { it.local == "r" && objectRx.containsMatchIn(xml.substring(it.start, it.end)) }.joinToString("") { xml.substring(it.start, it.end) }
        return openTag(e) + pPr + runsXml(text, firstRunProps(e)) + objects + "</${e.qname}>"
    }

    private fun newParagraphXml(p: EditablePara): String {
        val tmpl = paraElems.getOrNull(p.templateIndex)
        var pPr = ""; var rPr: String? = null
        if (tmpl != null) {
            kids(tmpl).firstOrNull { it.local == "pPr" }?.let { pe ->
                // drop section breaks, forced page breaks and heading/title styles from the copy
                val drop = kids(pe).filter { c ->
                    c.local == "sectPr" || c.local == "pageBreakBefore" || c.local == "pPrChange" ||
                        (c.local == "pStyle" && XmlScan.attr(xml, c, "val")?.contains(Regex("(?i)heading|title|caption|toc")) == true)
                }
                var s = xml.substring(pe.start, pe.end)
                drop.sortedByDescending { it.start }.forEach { d -> s = s.removeRange(d.start - pe.start, d.end - pe.start) }
                pPr = s
            }
            rPr = firstRunProps(tmpl)
        }
        if (hasRtlChars(p.text) && !pPr.contains(Regex("<[\\w.-]+:bidi[\\s/>]"))) pPr = withBidi(pPr)
        return "<$w:p>" + pPr + (if (p.text.isEmpty()) "" else runsXml(p.text, rPr)) + "</$w:p>"
    }

    /** Adds `<w:bidi/>` respecting the CT_PPr child order (before spacing/ind/jc/...). */
    private fun withBidi(pPr: String): String {
        if (pPr.isEmpty()) return "<$w:pPr><$w:bidi/></$w:pPr>"
        val followers = setOf("adjustRightInd", "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc",
            "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange")
        val root = XmlScan.children(pPr, 0, pPr.length).firstOrNull() ?: return pPr
        if (root.selfClosing) return "<$w:pPr><$w:bidi/></$w:pPr>"
        val at = XmlScan.children(pPr, root).firstOrNull { it.local in followers }?.start ?: pPr.lastIndexOf('<')
        return pPr.substring(0, at) + "<$w:bidi/>" + pPr.substring(at)
    }

    companion object {
        const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private val objectRx = Regex("""<[\w.-]+:(drawing|pict|object)[\s>/]""")

        fun open(file: File): DocxEditor = OoxmlPackage(file).use { pkg ->
            val path = pkg.rels("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "word/document.xml"
            val bytes = pkg.bytes(path) ?: throw OfficeFormatException("word/document.xml missing")
            DocxEditor(file, path, String(bytes, Charsets.UTF_8).removePrefix("﻿"))
        }
    }
}

/** Convenience one-shot operations (each re-opens and saves the file). */
object DocxWriter2 {
    fun replaceParagraphText(src: File, dst: File, bodyIndex: Int, text: String) = DocxEditor.open(src).run { setText(bodyIndex, text); save(dst) }
    fun insertParagraphAfter(src: File, dst: File, bodyIndex: Int, text: String) = DocxEditor.open(src).run { insertAfter(bodyIndex, text); save(dst) }
    fun deleteParagraph(src: File, dst: File, bodyIndex: Int) = DocxEditor.open(src).run { delete(bodyIndex); save(dst) }
}
