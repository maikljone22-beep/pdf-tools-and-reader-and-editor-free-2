package com.smartdocs.editor.core.edit

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One outline (bookmark / TOC) entry in flat pre-order form: [level] 0 = top level. [page] is zero-based. */
data class OutlineRow(val key: Long, val title: String, val page: Int, val level: Int)

/**
 * Outline editing in a flat list (pre-order). A row's subtree = the following rows with a deeper level.
 * Every operation keeps the list a valid tree: first level 0, each level ≤ previous + 1.
 */
object OutlineModel {
    private var nextKey = System.nanoTime()
    fun newKey(): Long = ++nextKey

    suspend fun load(file: File, password: String?): List<OutlineRow> = withContext(Dispatchers.IO) {
        EditIo.load(file, password).use { doc ->
            val out = ArrayList<OutlineRow>()
            fun walk(node: PDOutlineNode, level: Int, guard: HashSet<Any>) {
                var next: PDOutlineItem? = node.firstChild
                while (true) {
                    val item = next ?: break
                    if (!guard.add(item.cosObject)) break
                    val page = runCatching { item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } }.getOrNull() ?: -1
                    out.add(OutlineRow(newKey(), item.title ?: "", page, level))
                    if (level < 30) walk(item, level + 1, guard)
                    next = item.nextSibling
                }
            }
            doc.documentCatalog.documentOutline?.let { walk(it, 0, HashSet()) }
            out
        }
    }

    /** Write [rows] as the document outline (replacing the old one) from [src] into [out]. */
    suspend fun save(src: File, out: File, password: String?, rows: List<OutlineRow>) = withContext(Dispatchers.IO) {
        EditIo.load(src, password).use { doc ->
            writeInto(doc, normalize(rows))
            EditIo.save(doc, out)
        }
    }

    private fun writeInto(doc: PDDocument, rows: List<OutlineRow>) {
        val catalog = doc.documentCatalog
        if (rows.isEmpty()) { catalog.documentOutline = null; return }
        val outline = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
        catalog.documentOutline = outline
        val stack = ArrayList<Pair<Int, PDOutlineItem>>()
        val n = doc.numberOfPages
        for (r in rows) {
            val item = PDOutlineItem()
            item.title = r.title
            if (r.page in 0 until n) item.setDestination(doc.getPage(r.page))
            while (stack.isNotEmpty() && stack.last().first >= r.level) stack.removeAt(stack.size - 1)
            if (stack.isEmpty()) outline.addLast(item) else stack.last().second.addLast(item)
            stack.add(r.level to item)
        }
        outline.openNode()
    }

    // ---------------- flat-tree operations ----------------

    /** Clamp levels so the list is a valid pre-order tree. */
    fun normalize(rows: List<OutlineRow>): List<OutlineRow> {
        var prev = -1
        return rows.map { r -> val l = r.level.coerceIn(0, prev + 1); prev = l; if (l == r.level) r else r.copy(level = l) }
    }

    /** End (exclusive) of the subtree starting at [i]. */
    fun subtreeEnd(rows: List<OutlineRow>, i: Int): Int {
        var j = i + 1
        while (j < rows.size && rows[j].level > rows[i].level) j++
        return j
    }

    /** Insert a new entry after the subtree of [after] as its sibling (or at the end when [after] is -1). */
    fun insert(rows: List<OutlineRow>, after: Int, title: String, page: Int): List<OutlineRow> {
        if (after !in rows.indices) return rows + OutlineRow(newKey(), title, page, 0)
        val end = subtreeEnd(rows, after)
        return rows.toMutableList().apply { add(end, OutlineRow(newKey(), title, page, rows[after].level)) }
    }

    fun delete(rows: List<OutlineRow>, i: Int): List<OutlineRow> {
        if (i !in rows.indices) return rows
        val end = subtreeEnd(rows, i)
        return normalize(rows.subList(0, i) + rows.subList(end, rows.size))
    }

    fun update(rows: List<OutlineRow>, i: Int, title: String, page: Int): List<OutlineRow> =
        rows.mapIndexed { k, r -> if (k == i) r.copy(title = title, page = page) else r }

    /** Make [i] (with its subtree) a child of its previous sibling. */
    fun indent(rows: List<OutlineRow>, i: Int): List<OutlineRow> {
        if (i <= 0 || i !in rows.indices || rows[i - 1].level < rows[i].level) return rows
        val end = subtreeEnd(rows, i)
        return rows.mapIndexed { k, r -> if (k in i until end) r.copy(level = r.level + 1) else r }
    }

    /** Move [i] (with its subtree) one level up. */
    fun outdent(rows: List<OutlineRow>, i: Int): List<OutlineRow> {
        if (i !in rows.indices || rows[i].level == 0) return rows
        val end = subtreeEnd(rows, i)
        return normalize(rows.mapIndexed { k, r -> if (k in i until end) r.copy(level = r.level - 1) else r })
    }

    /**
     * Move the subtree at [from] so that it starts at position [to] of the list without it, taking [level]
     * (clamped to what the new position allows). Returns the new list and the new index of the moved row.
     */
    fun move(rows: List<OutlineRow>, from: Int, to: Int, level: Int = rows.getOrNull(from)?.level ?: 0): Pair<List<OutlineRow>, Int> {
        if (from !in rows.indices) return rows to from
        val end = subtreeEnd(rows, from)
        val block = rows.subList(from, end)
        val rest = rows.subList(0, from) + rows.subList(end, rows.size)
        // never drop into the middle of another subtree's children list: any index is a valid pre-order position
        val at = to.coerceIn(0, rest.size)
        val maxLevel = if (at == 0) 0 else rest[at - 1].level + 1
        val newLevel = level.coerceIn(0, maxLevel)
        val shift = newLevel - block.first().level
        val moved = block.map { it.copy(level = it.level + shift) }
        val result = rest.subList(0, at) + moved + rest.subList(at, rest.size)
        return normalize(result) to at
    }

    /** One step up / down among the visible order (keeps the level, clamped). */
    fun moveUp(rows: List<OutlineRow>, i: Int): Pair<List<OutlineRow>, Int> {
        if (i <= 0) return rows to i
        // previous sibling start (or parent) position
        var j = i - 1
        while (j > 0 && rows[j].level > rows[i].level) j--
        return move(rows, i, j)
    }

    fun moveDown(rows: List<OutlineRow>, i: Int): Pair<List<OutlineRow>, Int> {
        val end = subtreeEnd(rows, i)
        if (end >= rows.size) return rows to i
        // jump over the next sibling's whole subtree
        val nextEnd = subtreeEnd(rows, end)
        val size = end - i
        return move(rows, i, nextEnd - size)
    }
}
