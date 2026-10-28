package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata

/**
 * audit 5 T-16: keeps an existing XMP packet in step with the /Info values the quick "Document properties" writes.
 * Only the six properties are replaced; every other property (pdfaid:part, pdfuaid, xmpMM ids, …) stays, so a
 * PDF/A file keeps its identification. A document without XMP is left without one (/Info is enough then).
 */
object XmpInfo {
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val PDF = "http://ns.adobe.com/pdf/1.3/"
    private const val XMP = "http://ns.adobe.com/xap/1.0/"

    fun sync(doc: PDDocument, title: String, author: String, subject: String, keywords: String, creator: String, producer: String) {
        val md = doc.documentCatalog.metadata ?: return
        val old = runCatching { md.exportXMPMetadata().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: return
        val new = update(old, title, author, subject, keywords, creator, producer) ?: return
        val m = PDMetadata(doc); m.importXMPMetadata(new.toByteArray(Charsets.UTF_8))
        doc.documentCatalog.metadata = m
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The packet [xmp] with the six properties replaced (empty values removed); null when it has no rdf:Description. Pure. */
    fun update(xmp: String, title: String, author: String, subject: String, keywords: String, creator: String, producer: String): String? {
        var s = xmp
        val props = listOf("dc:title", "dc:creator", "dc:description", "pdf:Keywords", "pdf:Producer", "xmp:CreatorTool")
        for (p in props) {
            val q = Regex.escape(p)
            s = s.replace(Regex("<$q(\\s[^>]*)?/>"), "")
            s = s.replace(Regex("(?s)<$q(\\s[^>]*)?>.*?</$q>"), "")
            s = s.replace(Regex("\\s$q\\s*=\\s*\"[^\"]*\""), "")
            s = s.replace(Regex("\\s$q\\s*=\\s*'[^']*'"), "")
        }
        val add = buildString {
            if (title.isNotBlank()) append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(title)}</rdf:li></rdf:Alt></dc:title>\n")
            if (author.isNotBlank()) append("<dc:creator><rdf:Seq><rdf:li>${esc(author)}</rdf:li></rdf:Seq></dc:creator>\n")
            if (subject.isNotBlank()) append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(subject)}</rdf:li></rdf:Alt></dc:description>\n")
            if (keywords.isNotBlank()) append("<pdf:Keywords>${esc(keywords)}</pdf:Keywords>\n")
            if (producer.isNotBlank()) append("<pdf:Producer>${esc(producer)}</pdf:Producer>\n")
            if (creator.isNotBlank()) append("<xmp:CreatorTool>${esc(creator)}</xmp:CreatorTool>\n")
        }
        val desc = Regex("<rdf:Description\\b[^>]*?(/?)>").find(s) ?: return null
        var open = desc.value
        val selfClosing = desc.groupValues[1] == "/"
        fun ns(prefix: String, uri: String) { if (!Regex("xmlns:$prefix\\s*=").containsMatchIn(open)) open = open.replaceFirst(Regex("\\s*/?>$"), " xmlns:$prefix=\"$uri\"" + if (selfClosing) "/>" else ">") }
        ns("dc", DC); ns("pdf", PDF); ns("xmp", XMP)
        val replacement = if (selfClosing) open.removeSuffix("/>") + ">\n" + add + "</rdf:Description>" else open + "\n" + add
        return s.substring(0, desc.range.first) + replacement + s.substring(desc.range.last + 1)
    }
}
