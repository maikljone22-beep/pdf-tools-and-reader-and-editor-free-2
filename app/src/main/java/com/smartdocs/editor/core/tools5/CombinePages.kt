package com.smartdocs.editor.core.tools5

/**
 * FINAL_CHECK_2 #1 — Combine's page-level view: the PDF entries of the combine list expanded into single pages that
 * can be reordered freely (pages of one file moved into the middle of another, pages dropped), and folded back into
 * [CombineEntry] rows: every run of consecutive ascending pages of one file becomes one entry with that page range,
 * so the existing combine runs unchanged. Pure Kotlin, unit-tested.
 */
object CombinePages {

    /** One page of the sequence: [entry] = index into the entry list, [page] zero-based in that file. */
    data class Ref(val entry: Int, val page: Int)

    /** The page sequence of [entries]: PDF entries (not attachments) expanded with their ranges; [counts] = page count per entry index. */
    fun expand(entries: List<CombineEntry>, counts: Map<Int, Int>): List<Ref> {
        val out = ArrayList<Ref>()
        entries.forEachIndexed { i, e ->
            if (e.attach) return@forEachIndexed
            val n = counts[i] ?: return@forEachIndexed
            CombinePick.pagesOf(e.pages, n).sorted().forEach { out += Ref(i, it) }
        }
        return out
    }

    /**
     * Fold [refs] back into entries: runs of consecutive ascending pages of one file → one entry (title, password and
     * attach copied from the original). Entries not represented in [refs] (attachments, non-PDF files) keep their
     * place relative to the first page of the file before them; entries that lost every page are dropped.
     */
    fun fold(refs: List<Ref>, entries: List<CombineEntry>, counts: Map<Int, Int>): List<CombineEntry> {
        val out = ArrayList<CombineEntry>()
        val pdfIdx = entries.indices.filter { counts.containsKey(it) && !entries[it].attach }.toSet()
        // non-page entries are inserted before the first run whose original entry index is larger than theirs
        val others = entries.indices.filter { it !in pdfIdx }.toMutableList()
        fun flushOthersBefore(entry: Int) { while (others.isNotEmpty() && others.first() < entry) out += entries[others.removeAt(0)] }
        var i = 0
        while (i < refs.size) {
            val r = refs[i]
            var j = i
            while (j + 1 < refs.size && refs[j + 1].entry == r.entry && refs[j + 1].page == refs[j].page + 1) j++
            flushOthersBefore(r.entry)
            val e = entries[r.entry]
            val n = counts[r.entry] ?: 0
            val pages = (r.page..refs[j].page).toList()
            out += e.copy(pages = if (pages.size == n) "" else CombinePick.rangeOf(pages, n).ifBlank { "${r.page + 1}" })
            i = j + 1
        }
        while (others.isNotEmpty()) out += entries[others.removeAt(0)]
        return out
    }

    /** [list] with the items at [picked] moved by [delta] (−1 up, +1 down), kept in order and inside the list. */
    fun move(list: List<Ref>, picked: Set<Int>, delta: Int): List<Ref> {
        if (picked.isEmpty() || delta == 0) return list
        val idx = picked.filter { it in list.indices }.sorted()
        if (idx.isEmpty()) return list
        if (delta < 0 && idx.first() == 0) return list
        if (delta > 0 && idx.last() == list.lastIndex) return list
        val out = list.toMutableList()
        val order = if (delta < 0) idx else idx.reversed()
        for (k in order) { val t = out[k]; out[k] = out[k + delta]; out[k + delta] = t }
        return out
    }

    /** Indexes of [picked] after [move]. */
    fun movedIndexes(picked: Set<Int>, delta: Int, size: Int): Set<Int> {
        val idx = picked.filter { it in 0 until size }.sorted()
        if (idx.isEmpty()) return picked
        if ((delta < 0 && idx.first() == 0) || (delta > 0 && idx.last() == size - 1)) return picked
        return idx.map { it + delta }.toSet()
    }
}
