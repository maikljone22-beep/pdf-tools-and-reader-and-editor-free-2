package com.smartdocs.editor.core.ai5

import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.SearchHit

/** A finding with its location, in exactly the convention of [PdfSession.search] hits (PDFium rects, PDF space). */
data class PiiHit(val match: PiiMatch, val hit: SearchHit)

/** Runs [PiiDetector] over pages of an open session; rects come from [PdfSession.searchWith]. */
object PiiScanner {

    /** On-device scan; NAME / ORG / ROLE come from the offline Smart Redact patterns when asked for (AI: [com.smartdocs.editor.core.ai6.AiPii]). */
    suspend fun scan(session: PdfSession, kinds: Set<PiiKind> = PiiKind.entries.toSet() - PiiEntities.KINDS, pages: IntRange = 0 until session.pageCount,
                     progress: (Int, Int) -> Unit = { _, _ -> }): List<PiiHit> {
        val out = ArrayList<PiiHit>()
        for (p in pages) {
            progress(p, session.pageCount)
            var found: List<PiiMatch> = emptyList()
            val hits = runCatching {
                session.searchWith(p..p) { text -> found = PiiDetector.detect(text, kinds); found.map { it.start until it.end } }
            }.getOrDefault(emptyList())
            hits.forEach { h -> found.firstOrNull { it.start == h.charIndex }?.let { out += PiiHit(it, h) } }
        }
        return out
    }

    /** Only the [SearchHit]s, for screens that already handle search hits (redaction). */
    suspend fun hits(session: PdfSession, kinds: Set<PiiKind> = PiiKind.entries.toSet() - PiiEntities.KINDS): List<SearchHit> = scan(session, kinds).map { it.hit }
}
