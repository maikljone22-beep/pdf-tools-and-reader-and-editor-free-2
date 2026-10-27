package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.redact.SanitizeItem
import com.smartdocs.editor.core.search.PatternKind
import com.smartdocs.editor.core.search.SearchRequest

/** Pure parsing of the options of the nav-batch steps (unit-tested; the runner applies them). */
object BatchSteps {

    /** REDACT step → search request: terms (one per line or comma), pattern kind names, regexes (one per line). */
    fun redactRequest(s: Step): SearchRequest = SearchRequest(
        words = RecipeOps.list(s.p("terms")),
        patterns = RecipeOps.list(s.p("patterns")).mapNotNull { n -> PatternKind.entries.firstOrNull { it.name.equals(n, true) } }.toSet(),
        regexes = s.p("regex").lines().map { it.trim() }.filter { it.isNotEmpty() },
    )

    /** SANITIZE step: blank = the default set, "all" = everything, else the listed item names. */
    fun sanitizeItems(s: String): Set<SanitizeItem> {
        val names = RecipeOps.list(s)
        if (names.isEmpty()) return SanitizeItem.DEFAULTS
        if (names.any { it.equals("all", true) }) return SanitizeItem.entries.toSet()
        return names.mapNotNull { n -> SanitizeItem.entries.firstOrNull { it.name.equals(n, true) } }.toSet().ifEmpty { SanitizeItem.DEFAULTS }
    }

    /** CONVERT_OFFICE formats → hub conversion ids. */
    val officeConversions: Map<String, String> = linkedMapOf(
        "docx" to "pdf_docx", "xlsx" to "pdf_xlsx", "pptx" to "pdf_pptx", "odt" to "pdf_odt", "ods" to "pdf_ods",
        "odp" to "pdf_odp", "rtf" to "pdf_rtf", "txt" to "pdf_txt", "html" to "pdf_html", "csv" to "pdf_csv",
    )

    fun conversionId(format: String): String? = officeConversions[format.trim().lowercase().removePrefix(".")]

    /**
     * XMP packet with the PDF/UA identification (pdfuaid:part). An existing packet keeps everything it had and gets
     * one more rdf:Description (or is returned unchanged when it already names a part); without one a minimal
     * packet with the title is made.
     */
    fun withUaPart(existing: String?, title: String?, part: Int = 1): String {
        val desc = "<rdf:Description rdf:about=\"\" xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\"><pdfuaid:part>$part</pdfuaid:part></rdf:Description>\n"
        if (existing != null && existing.contains("</rdf:RDF>")) {
            if (existing.contains("pdfuaid:part")) return existing
            val at = existing.lastIndexOf("</rdf:RDF>")
            return existing.substring(0, at) + desc + existing.substring(at)
        }
        val t = title?.takeIf { it.isNotBlank() }?.let { XmpWriter.esc(it) }
        return buildString {
            append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            if (t != null) append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">")
                .append(t).append("</rdf:li></rdf:Alt></dc:title></rdf:Description>\n")
            append(desc)
            append("</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>")
        }
    }

    /** Standard Info keys (the rest are custom keys). */
    val standardInfo = setOf("Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Trapped")
}
