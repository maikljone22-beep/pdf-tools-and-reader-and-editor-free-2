package com.smartdocs.editor.core.nve

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkInfo
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * NVE-file: XMP metadata import / export (replace or merge, Info dictionary kept in sync) and "Mark as PDF/UA".
 * Pure PDFBox + javax.xml (JVM-testable).
 */
object XmpOps {
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val PDF = "http://ns.adobe.com/pdf/1.3/"
    private const val XMP = "http://ns.adobe.com/xap/1.0/"
    private const val UA = "http://www.aiim.org/pdfua/ns/id/"

    enum class Mode { REPLACE, MERGE }

    /** The document's XMP packet (bytes as stored), or null. */
    fun export(doc: PDDocument): ByteArray? = runCatching { doc.documentCatalog.metadata?.toByteArray() }.getOrNull()

    fun exportFile(src: File, password: String?): ByteArray? = com.smartdocs.editor.core.pdf.PdfOps.loadRaw(src, password).use { export(it) }

    private fun parse(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        // the xpacket processing instructions are fine for the parser; a BOM in "begin" is an attribute value
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun serialize(d: Document): String {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val w = StringWriter()
        t.transform(DOMSource(d.documentElement), StreamResult(w))
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" + w.toString().trim() + "\n<?xpacket end=\"w\"?>"
    }

    private fun rdfOf(d: Document): Element? = d.getElementsByTagNameNS(RDF, "RDF").item(0) as? Element

    private fun descriptions(rdf: Element): List<Element> =
        (0 until rdf.childNodes.length).mapNotNull { rdf.childNodes.item(it) as? Element }.filter { it.namespaceURI == RDF && it.localName == "Description" }

    /** Properties of a description: child elements and non-rdf / non-xmlns attributes, as (namespace, local name). */
    private fun props(e: Element): Set<Pair<String?, String>> {
        val out = HashSet<Pair<String?, String>>()
        (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }.forEach { out += it.namespaceURI to (it.localName ?: it.nodeName) }
        for (i in 0 until e.attributes.length) {
            val a = e.attributes.item(i)
            if (a.namespaceURI == RDF || a.namespaceURI == "http://www.w3.org/2000/xmlns/" || a.nodeName.startsWith("xmlns")) continue
            out += a.namespaceURI to (a.localName ?: a.nodeName)
        }
        return out
    }

    private fun removeProps(e: Element, names: Set<Pair<String?, String>>) {
        (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
            .filter { (it.namespaceURI to (it.localName ?: it.nodeName)) in names }.forEach { e.removeChild(it) }
        val attrs = (0 until e.attributes.length).map { e.attributes.item(it) }.filter { (it.namespaceURI to (it.localName ?: it.nodeName)) in names }
        attrs.forEach { e.removeAttributeNode(it as org.w3c.dom.Attr) }
    }

    /**
     * [existing] merged with [incoming]: every property of [incoming] replaces the same property of [existing]
     * (child element or attribute form), the others are kept. Returns the new packet.
     */
    fun merge(existing: ByteArray?, incoming: ByteArray): String {
        val inc = parse(incoming)
        if (existing == null) return serialize(inc)
        val base = runCatching { parse(existing) }.getOrNull() ?: return serialize(inc)
        val baseRdf = rdfOf(base) ?: return serialize(inc)
        val incRdf = rdfOf(inc) ?: return serialize(base)
        val incDescs = descriptions(incRdf)
        val names = incDescs.flatMap { props(it) }.toSet()
        descriptions(baseRdf).forEach { removeProps(it, names) }
        incDescs.forEach { baseRdf.appendChild(base.importNode(it, true)) }
        return serialize(base)
    }

    /** Validate [bytes] as an XMP packet (has an rdf:RDF element). */
    fun isXmp(bytes: ByteArray): Boolean = runCatching { rdfOf(parse(bytes)) != null }.getOrDefault(false)

    /** Simple values found in the packet: title, author(s), subject, keywords, producer, creator tool. */
    data class Simple(val title: String?, val author: String?, val subject: String?, val keywords: String?, val producer: String?, val creatorTool: String?)

    fun simple(bytes: ByteArray): Simple {
        val d = parse(bytes)
        fun text(ns: String, local: String): String? {
            val el = d.getElementsByTagNameNS(ns, local).item(0)
            if (el != null) {
                val lis = (el as Element).getElementsByTagNameNS(RDF, "li")
                return if (lis.length > 0) (0 until lis.length).joinToString("; ") { lis.item(it).textContent.trim() } else el.textContent.trim()
            }
            // attribute form on an rdf:Description
            val descs = d.getElementsByTagNameNS(RDF, "Description")
            for (i in 0 until descs.length) {
                val a = (descs.item(i) as Element).getAttributeNodeNS(ns, local)
                if (a != null) return a.value.trim()
            }
            return null
        }
        return Simple(text(DC, "title"), text(DC, "creator"), text(DC, "description"), text(PDF, "Keywords"), text(PDF, "Producer"), text(XMP, "CreatorTool"))
    }

    /** Write [packet] as the catalog /Metadata and copy its simple values into /Info when [syncInfo]. */
    fun store(doc: PDDocument, packet: String, syncInfo: Boolean) {
        val bytes = packet.toByteArray(Charsets.UTF_8)
        doc.documentCatalog.metadata = PDMetadata(doc).also { it.importXMPMetadata(bytes) }
        if (syncInfo) {
            val s = runCatching { simple(bytes) }.getOrNull() ?: return
            val info = doc.documentInformation
            s.title?.takeIf { it.isNotBlank() }?.let { info.title = it }
            s.author?.takeIf { it.isNotBlank() }?.let { info.author = it }
            s.subject?.takeIf { it.isNotBlank() }?.let { info.subject = it }
            s.keywords?.takeIf { it.isNotBlank() }?.let { info.keywords = it }
            s.producer?.takeIf { it.isNotBlank() }?.let { info.producer = it }
            s.creatorTool?.takeIf { it.isNotBlank() }?.let { info.creator = it }
            doc.documentInformation = info
        }
    }

    /** Import [xmp] into [doc]: replace the packet or merge it into the existing one. */
    fun import(doc: PDDocument, xmp: ByteArray, mode: Mode, syncInfo: Boolean = true) {
        require(isXmp(xmp)) { "not an XMP packet" }
        val packet = if (mode == Mode.REPLACE) serialize(parse(xmp)) else merge(export(doc), xmp)
        store(doc, packet, syncInfo)
    }

    suspend fun importFile(src: File, out: File, password: String?, xmp: ByteArray, mode: Mode, syncInfo: Boolean = true) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.smartdocs.editor.core.pdf.PdfOps.load(src, password).use { d -> import(d, xmp, mode, syncInfo); saveTo(d, out) }
        }

    /** What [markPdfUa] could not fix (the UI shows them as warnings). */
    enum class UaWarning { UNTAGGED, NO_TITLE, NO_LANG }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Mark [doc] as PDF/UA-1: pdfuaid:part = 1 in the XMP (merged, other properties kept), dc:title from /Info,
     * /MarkInfo /Marked true, /ViewerPreferences /DisplayDocTitle true and /Lang ([lang] when the file has none).
     * Returns what still prevents conformance (no structure tree, no title, no language).
     */
    fun markPdfUa(doc: PDDocument, lang: String?): List<UaWarning> {
        val cat = doc.documentCatalog
        val warn = ArrayList<UaWarning>()
        val title = doc.documentInformation?.title.orEmpty()
        val snippet = buildString {
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"$RDF\">")
            append("<rdf:Description rdf:about=\"\" xmlns:pdfuaid=\"$UA\" xmlns:dc=\"$DC\"><pdfuaid:part>1</pdfuaid:part>")
            if (title.isNotBlank()) append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(esc(title)).append("</rdf:li></rdf:Alt></dc:title>")
            append("</rdf:Description></rdf:RDF></x:xmpmeta>")
        }
        store(doc, merge(export(doc), snippet.toByteArray(Charsets.UTF_8)), syncInfo = false)
        val mi = cat.markInfo ?: PDMarkInfo().also { cat.markInfo = it }
        mi.isMarked = true
        cat.markInfo = mi
        val cd = cat.cosObject
        val vp = (cd.getDictionaryObject(COSName.VIEWER_PREFERENCES) as? COSDictionary) ?: COSDictionary().also { cd.setItem(COSName.VIEWER_PREFERENCES, it) }
        vp.setBoolean(COSName.getPDFName("DisplayDocTitle"), true)
        if (cat.language.isNullOrBlank() && !lang.isNullOrBlank()) cat.language = lang.trim()
        if (!cd.containsKey(COSName.getPDFName("StructTreeRoot"))) warn += UaWarning.UNTAGGED
        if (title.isBlank()) warn += UaWarning.NO_TITLE
        if (cat.language.isNullOrBlank()) warn += UaWarning.NO_LANG
        return warn
    }

    suspend fun markPdfUaFile(src: File, out: File, password: String?, lang: String?): List<UaWarning> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.smartdocs.editor.core.pdf.PdfOps.load(src, password).use { d -> val w = markPdfUa(d, lang); saveTo(d, out); w }
        }

    /** Base URL write as a document operation. */
    suspend fun setBaseUrlFile(src: File, out: File, password: String?, url: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.smartdocs.editor.core.pdf.PdfOps.load(src, password).use { d -> com.smartdocs.editor.core.edit5.DocPropsOps.setBaseUrl(d, url); saveTo(d, out) }
        }

    internal fun saveTo(doc: PDDocument, out: File) {
        val tmp = File(out.absoluteFile.parentFile, ".${out.name}.${System.nanoTime()}.tmp")
        try { doc.save(tmp); if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() } } finally { if (tmp.exists()) tmp.delete() }
    }

    @Suppress("unused") private fun Node.elements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
}
