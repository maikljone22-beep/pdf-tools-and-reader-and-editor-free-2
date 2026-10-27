package com.smartdocs.editor.core.nve

import com.smartdocs.editor.core.search.ArabicNormalizer
import com.smartdocs.editor.core.search.FileResult
import com.smartdocs.editor.core.search.IndexText
import com.smartdocs.editor.core.search.PartKind
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.Calendar

/**
 * Pure helpers of the advanced search (Foxit "Advanced Search": sort, filter, metadata criteria, save results,
 * index options, attachment contents). No android classes: unit tested on the JVM.
 */
object NveSearchCore {

    enum class Sort { RELEVANCE, NAME, DATE, LOCATION }

    /** Document metadata used by the "additional criteria" filter and the date sort. Dates in epoch ms. */
    data class Meta(
        val title: String = "", val author: String = "", val subject: String = "", val keywords: String = "",
        val created: Long? = null, val modified: Long? = null, val fileTime: Long = 0L,
    )

    /** Additional criteria; blank text / null date = not used. Text criteria are "contains", folded (tashkeel, case). */
    data class Criteria(
        val title: String = "", val author: String = "", val subject: String = "", val keywords: String = "",
        val createdFrom: Long? = null, val createdTo: Long? = null, val modifiedFrom: Long? = null, val modifiedTo: Long? = null,
    ) {
        val active: Boolean get() = listOf(title, author, subject, keywords).any { it.isNotBlank() } ||
            listOf(createdFrom, createdTo, modifiedFrom, modifiedTo).any { it != null }
    }

    private fun fold(s: String) = ArabicNormalizer.fold(s, IndexText.INDEX_NORM).text

    private fun contains(hay: String, needle: String) = needle.isBlank() || fold(hay).contains(fold(needle.trim()))

    private fun inRange(t: Long?, from: Long?, to: Long?): Boolean {
        if (from == null && to == null) return true
        if (t == null) return false
        return (from == null || t >= from) && (to == null || t <= to)
    }

    fun matches(m: Meta?, c: Criteria): Boolean {
        if (!c.active) return true
        if (m == null) return false
        return contains(m.title, c.title) && contains(m.author, c.author) && contains(m.subject, c.subject) && contains(m.keywords, c.keywords) &&
            inRange(m.created, c.createdFrom, c.createdTo) && inRange(m.modified ?: m.fileTime, c.modifiedFrom, c.modifiedTo)
    }

    /** Keeps results whose file name / path contains [nameFilter] and that have hits of one of [kinds]. */
    fun filter(results: List<FileResult>, nameFilter: String, kinds: Set<PartKind>): List<FileResult> =
        results.filter { r -> nameFilter.isBlank() || fold(r.name).contains(fold(nameFilter.trim())) || fold(r.path).contains(fold(nameFilter.trim())) }
            .mapNotNull { r ->
                val hits = r.hits.filter { it.kind in kinds }
                if (hits.isEmpty()) null else r.copy(hits = hits)
            }

    /** Sorts files: relevance = most hits first; name A-Z; date = newest first; location = folder then name. Hits by page. */
    fun sort(results: List<FileResult>, sort: Sort, meta: Map<String, Meta> = emptyMap()): List<FileResult> {
        val sorted = when (sort) {
            Sort.RELEVANCE -> results.sortedByDescending { it.total }
            Sort.NAME -> results.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            Sort.DATE -> results.sortedByDescending { meta[it.path]?.let { m -> m.modified ?: m.created ?: m.fileTime } ?: 0L }
            Sort.LOCATION -> results.sortedWith(compareBy<FileResult, String>(String.CASE_INSENSITIVE_ORDER) { it.path.substringBeforeLast('/', "") }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        return if (sort == Sort.LOCATION) sorted.map { r -> r.copy(hits = r.hits.sortedBy { if (it.page < 0) Int.MAX_VALUE else it.page }) } else sorted
    }

    private fun csvCell(s: String): String {
        val t = s.replace("\r", " ").replace("\n", " ")
        return if (t.any { it == ',' || it == '"' || it == ';' } || t.startsWith(" ") || t.endsWith(" ")) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }

    /** CSV (UTF-8 with BOM for spreadsheet apps): header + one row per hit. [kindName] gives the localised location kind. */
    fun csv(query: String, results: List<FileResult>, header: List<String>, kindName: (PartKind) -> String): String {
        val sb = StringBuilder("﻿")
        sb.append(header.joinToString(",") { csvCell(it) }).append("\r\n")
        for (r in results) for (h in r.hits) {
            sb.append(listOf(query, r.name, r.path, if (h.page >= 0) (h.page + 1).toString() else "", kindName(h.kind), h.snippet).joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- index options

    /** Index options (Foxit catalog: stop words, numbers, excluded folders). */
    data class IndexOptions(val stopWords: Set<String> = emptySet(), val includeNumbers: Boolean = true, val excluded: List<String> = emptyList()) {
        val foldedStops: Set<String> by lazy { stopWords.map { fold(it.trim()) }.filter { it.isNotEmpty() }.toSet() }
    }

    private fun isNumber(w: String) = w.isNotEmpty() && w.all { it.isDigit() || it == '.' || it == ',' }

    /** FTS body without stop words (and their stems) and, when disabled, numbers. */
    fun filterBody(body: String, o: IndexOptions): String {
        if (o.foldedStops.isEmpty() && o.includeNumbers) return body
        return body.split(' ').filter { w -> w.isNotEmpty() && w !in o.foldedStops && (o.includeNumbers || !isNumber(w)) }.joinToString(" ")
    }

    /** The query without the words the index does not keep (they are still verified in the raw text afterwards). */
    fun stripQuery(query: String, o: IndexOptions): String {
        if (o.foldedStops.isEmpty() && o.includeNumbers) return query
        val kept = query.split(Regex("\\s+")).filter { w -> val f = fold(w); f.isNotEmpty() && f !in o.foldedStops && (o.includeNumbers || !isNumber(f)) }
        return kept.joinToString(" ")
    }

    fun excluded(path: String, o: IndexOptions): Boolean = o.excluded.any { ex -> ex.isNotBlank() && (path == ex || path.startsWith(ex.trimEnd('/') + "/")) }

    // ---------------------------------------------------------------- metadata & attachments

    /** PDF date string "D:YYYYMMDDHHmmSS..." → epoch ms (UTC when no offset), or null. */
    fun pdfDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val d = s.trim().removePrefix("D:")
        val digits = d.takeWhile { it.isDigit() }
        if (digits.length < 4) return null
        fun part(a: Int, b: Int, def: Int) = digits.substring(a, minOf(b, digits.length)).takeIf { it.length == b - a }?.toIntOrNull() ?: def
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(part(0, 4, 1970), part(4, 6, 1) - 1, part(6, 8, 1), part(8, 10, 0), part(10, 12, 0), part(12, 14, 0))
        var ms = cal.timeInMillis
        val rest = d.substring(digits.length)
        if (rest.isNotEmpty() && (rest[0] == '+' || rest[0] == '-')) {
            val nums = rest.substring(1).filter { it.isDigit() }
            val oh = nums.take(2).toIntOrNull() ?: 0; val om = nums.drop(2).take(2).toIntOrNull() ?: 0
            val off = (oh * 60 + om) * 60_000L
            ms -= if (rest[0] == '+') off else -off
        }
        return ms
    }

    fun meta(doc: PDDocument, fileTime: Long): Meta {
        val info = doc.documentInformation
        val raw = info.cosObject
        return Meta(
            title = info.title.orEmpty(), author = info.author.orEmpty(), subject = info.subject.orEmpty(), keywords = info.keywords.orEmpty(),
            created = pdfDate(raw.getString(COSName.CREATION_DATE)), modified = pdfDate(raw.getString(COSName.MOD_DATE)), fileTime = fileTime,
        )
    }

    /** An embedded file: its name and its bytes (null when unreadable / too big). */
    class Embedded(val name: String, val bytes: ByteArray?)

    /** Every embedded file of the document (name tree /EmbeddedFiles and FileAttachment annotations), up to [maxBytes] each. */
    fun embeddedFiles(doc: PDDocument, maxBytes: Int = 20 * 1024 * 1024): List<Embedded> {
        val out = ArrayList<Embedded>()
        fun fromSpec(spec: COSDictionary?, fallback: String) {
            spec ?: return
            val name = spec.getString(COSName.UF) ?: spec.getString(COSName.F) ?: fallback
            val ef = spec.getDictionaryObject(COSName.EF) as? COSDictionary
            val stream = (ef?.getDictionaryObject(COSName.UF) ?: ef?.getDictionaryObject(COSName.F)) as? COSStream
            val bytes = stream?.let { s -> runCatching { s.createInputStream().use { it.readBytes() } }.getOrNull()?.takeIf { it.size <= maxBytes } }
            out += Embedded(name, bytes)
        }
        fun walk(node: COSDictionary, depth: Int) {
            if (depth > 20) return
            (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
                var i = 0
                while (i + 1 < arr.size()) { fromSpec(arr.getObject(i + 1) as? COSDictionary, (arr.getObject(i) as? COSString)?.string ?: ""); i += 2 }
            }
            (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { k -> for (j in 0 until k.size()) (k.getObject(j) as? COSDictionary)?.let { walk(it, depth + 1) } }
        }
        runCatching {
            val ef = (doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.getDictionaryObject(COSName.EMBEDDED_FILES) as? COSDictionary
            ef?.let { walk(it, 0) }
        }
        runCatching {
            for (p in doc.pages) for (a in p.annotations) if (a.subtype == "FileAttachment") fromSpec(a.cosObject.getDictionaryObject(COSName.getPDFName("FS")) as? COSDictionary, "")
        }
        return out
    }

    /** True for a PDF portfolio (collection) document. */
    fun isPortfolio(doc: PDDocument): Boolean = doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("Collection")) != null

    /**
     * Text contents of the embedded files (PDF: extracted text; .txt/.csv/.md/.xml/.html/.json: decoded text), each
     * prefixed by the file name, so the index and the reader find bar can search inside attachments / portfolio parts.
     */
    fun attachmentTexts(doc: PDDocument, maxChars: Int = 200_000): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (e in embeddedFiles(doc)) {
            val b = e.bytes ?: continue
            val ext = e.name.substringAfterLast('.', "").lowercase()
            val text = when {
                b.size > 4 && String(b, 0, 5, Charsets.ISO_8859_1) == "%PDF-" -> runCatching {
                    PDDocument.load(b).use { d -> if (d.isEncrypted) "" else com.tom_roush.pdfbox.text.PDFTextStripper().getText(d) }
                }.getOrDefault("")
                ext in setOf("txt", "csv", "md", "xml", "html", "htm", "json", "tsv", "log") -> runCatching { String(b, Charsets.UTF_8) }.getOrDefault("")
                else -> ""
            }
            if (text.isNotBlank()) out += e.name to text.take(maxChars)
        }
        return out
    }
}
