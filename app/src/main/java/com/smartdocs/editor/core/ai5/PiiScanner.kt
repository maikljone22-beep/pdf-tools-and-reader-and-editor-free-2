package com.smartdocs.editor.core.ai5

import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.SearchHit

/** A finding with its location, in exactly the convention of [PdfSession.search] hits (PDFium rects, PDF space). */
data class PiiHit(val match: PiiMatch, val hit: SearchHit)

/** Runs [PiiDetector] over pages of an open session; rects come from [PdfSession.searchWith]. */
object PiiScanner {

    /**
     * [ai] (optional) finds more matches in a page's text — the Smart Redact names / organisations / roles; its
     * matches are added where they do not overlap the on-device ones.
     */
    suspend fun scan(session: PdfSession, kinds: Set<PiiKind> = PiiKind.entries.toSet() - PiiEntities.KINDS, pages: IntRange = 0 until session.pageCount,
                     progress: (Int, Int) -> Unit = { _, _ -> }, ai: (suspend (String) -> List<PiiMatch>)? = null): List<PiiHit> {
        val out = ArrayList<PiiHit>()
        for (p in pages) {
            progress(p, session.pageCount)
            var found: List<PiiMatch> = emptyList()
            val extra: List<PiiMatch> = if (ai == null) emptyList() else {
                var pageText = ""
                runCatching { session.searchWith(p..p) { text -> pageText = text; emptyList() } }
                runCatching { ai(pageText) }.getOrDefault(emptyList())
            }
            val hits = runCatching {
                session.searchWith(p..p) { text ->
                    val local = PiiDetector.detect(text, if (ai != null) kinds - PiiEntities.KINDS else kinds)
                    found = (local + extra.filter { e -> e.end <= text.length && local.none { it.start < e.end && e.start < it.end } }).sortedBy { it.start }
                    found.map { it.start until it.end }
                }
            }.getOrDefault(emptyList())
            hits.forEach { h -> found.firstOrNull { it.start == h.charIndex }?.let { out += PiiHit(it, h) } }
        }
        return out
    }

    /** Only the [SearchHit]s, for screens that already handle search hits (redaction). */
    suspend fun hits(session: PdfSession, kinds: Set<PiiKind> = PiiKind.entries.toSet() - PiiEntities.KINDS): List<SearchHit> = scan(session, kinds).map { it.hit }
}
