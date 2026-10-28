package com.smartdocs.editor.core.tools5

/**
 * Combine thumbnail view (row 111): pages picked on thumbnails ↔ the entry's page range text. Pure.
 */
object CombinePick {
    /** Zero-based pages → "1-3, 5" (blank = every page, as the range field reads it). Keeps the picked order sorted. */
    fun rangeOf(picked: Collection<Int>, count: Int): String {
        val p = picked.filter { it in 0 until count }.distinct().sorted()
        if (p.isEmpty() || p.size == count) return ""
        val parts = ArrayList<String>()
        var start = p[0]; var prev = p[0]
        for (i in 1..p.size) {
            val cur = p.getOrNull(i)
            if (cur == prev + 1) { prev = cur; continue }
            parts += if (start == prev) "${start + 1}" else "${start + 1}-${prev + 1}"
            if (cur != null) { start = cur; prev = cur }
        }
        return parts.joinToString(", ")
    }

    /** The pages a range text selects (blank = all). */
    fun pagesOf(range: String, count: Int): Set<Int> = com.smartdocs.editor.core.pdf.PdfOps.parseRange(range, count).toSet()
}
