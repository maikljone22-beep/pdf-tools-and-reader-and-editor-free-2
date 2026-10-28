package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import java.util.IdentityHashMap

/**
 * audit 5 E-5: keeps the /PageLabels of a document right after pages were deleted, moved, duplicated, replaced or
 * inserted. Every page that stays keeps the label it had ("iv" stays "iv" when page 2 is deleted).
 *
 * Rules (documented choice):
 * - A page that is new in the result (inserted, imported, a duplicate copy, a replacement, a split part) gets no
 *   label of its own: it continues the run of the page before it (same style and prefix, next number). A new page at
 *   the very start takes the style and prefix of the first kept page, numbered from 1.
 * - A document without /PageLabels is not given any.
 * - Labels that only repeat the physical numbering (one decimal range from page 1 without prefix) stay that way: the
 *   pages are renumbered 1..n instead of carrying their old numbers around.
 */
object PageLabelRemap {

    /** One page's label parts. [number] is meaningless for [LabelStyle.NONE]. */
    private data class Spec(val style: LabelStyle, val prefix: String, val number: Int)

    private fun specs(ranges: List<LabelRange>, count: Int): List<Spec> {
        val sorted = ranges.filter { it.startPage in 0 until count }.sortedBy { it.startPage }.distinctBy { it.startPage }
        return (0 until count).map { p ->
            val r = sorted.lastOrNull { it.startPage <= p } ?: return@map Spec(LabelStyle.DECIMAL, "", p + 1)
            Spec(r.style, r.prefix, r.start.coerceAtLeast(1) + (p - r.startPage))
        }
    }

    /** True when [ranges] are the physical numbering 1..n (nothing worth keeping per page). Pure. */
    fun isPlain(ranges: List<LabelRange>): Boolean =
        ranges.size == 1 && ranges[0].let { it.startPage == 0 && it.style == LabelStyle.DECIMAL && it.prefix.isEmpty() && it.start == 1 }

    /**
     * New label ranges after the pages were rearranged: [order] lists, for every page of the result, its old index
     * (0 until [oldCount]) or -1 for a page that is new. Pure.
     */
    fun remap(ranges: List<LabelRange>, oldCount: Int, order: List<Int>): List<LabelRange> {
        if (order.isEmpty()) return emptyList()
        if (isPlain(ranges)) return listOf(LabelRange(0))
        val old = specs(ranges, oldCount)
        val firstKept = order.firstOrNull { it in old.indices }?.let { old[it] }
        val out = ArrayList<LabelRange>()
        var prev: Spec? = null
        order.forEachIndexed { i, o ->
            val p = prev
            val cur = when {
                o in old.indices -> old[o]
                p != null -> p.copy(number = p.number + 1)
                else -> Spec(firstKept?.style ?: LabelStyle.DECIMAL, firstKept?.prefix ?: "", 1)
            }
            val continues = p != null && p.style == cur.style && p.prefix == cur.prefix && (cur.style == LabelStyle.NONE || cur.number == p.number + 1)
            if (!continues) out += LabelRange(i, cur.style, cur.prefix, if (cur.style == LabelStyle.NONE) 1 else cur.number.coerceAtLeast(1))
            prev = cur
        }
        return out
    }

    /** The label ranges of [doc], or null when it has no /PageLabels (or they cannot be read). */
    fun read(doc: PDDocument): List<LabelRange>? {
        if (doc.documentCatalog.cosObject.getDictionaryObject(COSName.PAGE_LABELS) == null) return null
        val pl = runCatching { doc.documentCatalog.pageLabels }.getOrNull() ?: return null
        val out = ArrayList<LabelRange>()
        for (start in pl.pageIndices) {
            val r = pl.getPageLabelRange(start) ?: continue
            val style = LabelStyle.values().firstOrNull { it.pdf != null && it.pdf == r.style } ?: LabelStyle.NONE
            out += LabelRange(start, style, r.prefix ?: "", r.start.coerceAtLeast(1))
        }
        return out
    }

    /** Write [ranges] as the /PageLabels of [doc] (empty removes them; the first range is forced to page 0). */
    fun write(doc: PDDocument, ranges: List<LabelRange>) {
        val sorted = ranges.filter { it.startPage in 0 until doc.numberOfPages }.sortedBy { it.startPage }.distinctBy { it.startPage }
        if (sorted.isEmpty()) { doc.documentCatalog.cosObject.removeItem(COSName.PAGE_LABELS); return }
        val list = if (sorted.first().startPage != 0) listOf(LabelRange(0)) + sorted else sorted
        val pl = PDPageLabels(doc)
        list.forEach { r ->
            val pr = PDPageLabelRange()
            r.style.pdf?.let { pr.style = it }
            if (r.prefix.isNotEmpty()) pr.prefix = r.prefix
            pr.start = r.start.coerceAtLeast(1)
            pl.setLabelItem(r.startPage, pr)
        }
        doc.documentCatalog.pageLabels = pl
    }

    /** The labels and page objects of [doc] before a page operation (null = nothing to keep). */
    class Snapshot internal constructor(val ranges: List<LabelRange>, val pages: List<COSDictionary>)

    fun snapshot(doc: PDDocument): Snapshot? {
        val ranges = runCatching { read(doc) }.getOrNull() ?: return null
        return Snapshot(ranges, doc.pages.map { it.cosObject })
    }

    /**
     * Rebuild the labels of [doc] after a page operation from [snap]: pages are matched by identity (a page object
     * seen again is a copy, i.e. new).
     */
    fun restore(doc: PDDocument, snap: Snapshot?) {
        if (snap == null) return
        val index = IdentityHashMap<COSDictionary, Int>()
        snap.pages.forEachIndexed { i, p -> if (!index.containsKey(p)) index[p] = i }
        val seen = IdentityHashMap<COSDictionary, Boolean>()
        val order = doc.pages.map { p ->
            val d = p.cosObject
            val i = index[d]
            if (i != null && seen.put(d, true) == null) i else -1
        }
        runCatching { write(doc, remap(snap.ranges, snap.pages.size, order)) }
    }

    /** Run [block] (which changes the page tree of [doc]) and keep every remaining page's label. */
    inline fun <T> keep(doc: PDDocument, block: () -> T): T {
        val snap = snapshot(doc)
        val r = block()
        restore(doc, snap)
        return r
    }

    /** Give [target], a new document of the pages [pages] (old indexes, in order) of [source], their old labels. */
    fun copyTo(source: PDDocument, target: PDDocument, pages: List<Int>) {
        val ranges = runCatching { read(source) }.getOrNull() ?: return
        val seen = HashSet<Int>()
        val order = pages.filter { it in 0 until source.numberOfPages }.map { if (seen.add(it)) it else -1 }
        if (order.size != target.numberOfPages) return
        runCatching { write(target, remap(ranges, source.numberOfPages, order)) }
    }
}
