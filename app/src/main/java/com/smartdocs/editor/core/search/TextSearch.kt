package com.smartdocs.editor.core.search

import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.SearchHit
import com.smartdocs.editor.core.redact.RedactMath
import java.io.File

/**
 * What to look for in a document: a [query] (with [options]), a list of words/phrases [words], pattern kinds,
 * custom regular expressions and the names list. [keepLast] > 0 shrinks each hit so its last N letters/digits
 * stay visible (partial redaction).
 */
data class SearchRequest(
    val query: String = "",
    val options: SearchOptions = SearchOptions(),
    val words: List<String> = emptyList(),
    val patterns: Set<PatternKind> = emptySet(),
    val regexes: List<String> = emptyList(),
    val keepLast: Int = 0,
) {
    val isEmpty get() = query.isBlank() && words.none { it.isNotBlank() } && patterns.isEmpty() && regexes.none { it.isNotBlank() }
}

/** A hit with what produced it: [source] "q" (query), "w" (word list), "re" (custom regex) or a [PatternKind] name. */
data class Found(val hit: SearchHit, val source: String, val kind: PatternKind? = null)

/** A hit outside the page text: in a comment, bookmark, form field or attachment. */
data class ScopeHit(val kind: PartKind, val page: Int, val text: String, val ranges: List<IntRange>)

/**
 * Searches a [PdfSession] with [TextMatcher] and [SearchPatterns] over PDFium's page text. Hits keep the
 * `SearchHit` shape of `PdfSession.search` (PDFium char indexes, per-line rects in PDF space).
 */
object TextSearch {

    /** Ranges of [text] matched by [req], each with its source; sorted, overlaps removed (first source wins). */
    fun ranges(text: String, req: SearchRequest): List<Pair<IntRange, Pair<String, PatternKind?>>> {
        val out = ArrayList<Pair<IntRange, Pair<String, PatternKind?>>>()
        if (req.query.isNotBlank()) TextMatcher.find(text, req.query, req.options).forEach { out += it to ("q" to null) }
        val wordOpts = if (req.options.mode == SearchMode.REGEX) req.options.copy(mode = SearchMode.PHRASE) else req.options
        req.words.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { w ->
            TextMatcher.find(text, w, wordOpts).forEach { out += it to ("w" to null) }
        }
        if (req.patterns.isNotEmpty()) SearchPatterns.find(text, req.patterns).forEach { out += it.range to (it.kind.name to it.kind) }
        req.regexes.filter { it.isNotBlank() }.forEach { re ->
            SearchPatterns.findCustom(text, re, !req.options.norm.matchCase).forEach { out += it to ("re" to null) }
        }
        val sorted = out.sortedBy { it.first.first }
        val kept = ArrayList<Pair<IntRange, Pair<String, PatternKind?>>>()
        for (p in sorted) if (kept.none { it.first.first <= p.first.last && p.first.first <= it.first.last }) kept += p
        return kept.mapNotNull { (r, src) ->
            val rr = if (req.keepLast > 0) RedactMath.keepLast(text, r, req.keepLast) else r
            if (rr == null || rr.isEmpty() || rr.first < 0 || rr.last >= text.length) null else rr to src
        }
    }

    suspend fun search(session: PdfSession, req: SearchRequest, pages: IntRange = 0 until session.pageCount): List<Found> {
        if (req.isEmpty) return emptyList()
        val labels = ArrayList<Pair<String, PatternKind?>>()
        val hits = session.searchWith(pages) { text ->
            val r = ranges(text, req)
            r.forEach { labels += it.second }
            r.map { it.first }
        }
        // searchWith returns one hit per range, in order (ranges are valid and non-empty)
        return hits.mapIndexed { i, h -> val l = labels.getOrNull(i); Found(h, l?.first ?: "q", l?.second) }
    }

    /** Plain query search with the given options (reader-like result list). */
    suspend fun search(session: PdfSession, query: String, options: SearchOptions, pages: IntRange = 0 until session.pageCount): List<SearchHit> =
        search(session, SearchRequest(query = query, options = options), pages).map { it.hit }

    /** Hits in comments, bookmarks, form fields and attachments. */
    suspend fun searchScopes(file: File, password: String?, req: SearchRequest, kinds: Set<PartKind> = setOf(PartKind.COMMENTS, PartKind.BOOKMARK, PartKind.FIELD, PartKind.ATTACHMENT)): List<ScopeHit> {
        if (req.isEmpty) return emptyList()
        return DocParts.load(file, password).filter { it.kind in kinds }.mapNotNull { p ->
            val r = ranges(p.text, req.copy(keepLast = 0)).map { it.first }
            if (r.isEmpty()) null else ScopeHit(p.kind, p.page, p.text, r)
        }
    }
}
