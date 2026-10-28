package com.smartdocs.editor.core.redact

import android.graphics.RectF
import com.smartdocs.editor.core.search.Found

/**
 * #17 Search & Remove Text: a word list (typed or imported from a .txt file) is searched over the document and every
 * ticked hit is removed for real by the redaction core ([RedactApplier] with [RedactApplier.Options.removeTextOnly]):
 * the glyphs leave the content stream, nothing is drawn over them.
 */
object SearchRemove {

    /** Words / phrases of a typed or imported list: one per line (commas and semicolons also split), trimmed, unique. Pure. */
    fun parseWords(text: String): List<String> =
        text.removePrefix("﻿").split('\n', '\r', ',', '،', ';', '؛').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** [text] with the lines of [imported] appended (no duplicates), as the word field shows it. Pure. */
    fun merge(text: String, imported: String): String = (parseWords(text) + parseWords(imported)).distinct().joinToString("\n")

    /** Key of a hit in the checkable list. */
    fun key(f: Found) = "${f.hit.page}:${f.hit.charIndex}:${f.hit.length}"

    /** One removal mark per ticked hit (one rect per line of the hit). */
    fun marks(found: List<Found>, checked: Set<String>): List<RedactMark> =
        found.filter { key(it) in checked && it.hit.rects.isNotEmpty() }.mapIndexed { i, f ->
            RedactMark("sr$i", f.hit.page, f.hit.rects.map { RectF(it) }, label = f.hit.snippet.trim())
        }

    /** Hits by page, pages in order. Pure. */
    fun byPage(found: List<Found>): List<Pair<Int, List<Found>>> = found.groupBy { it.hit.page }.toSortedMap().map { it.key to it.value }
}
