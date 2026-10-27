package com.smartdocs.editor.core.edit5

import android.content.Context
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How split / extract parts are named (Nitro / Foxit split options). */
enum class PartName { NUMBER, PADDED, LETTER, RANGE }

/**
 * Nitro / Foxit page organisation beyond [PageOps5]: bookmark-aware delete, insert, extract and split; move a range,
 * swap two pages, rearrange by bookmark order, mix many files, insert pages into other files, flatten a page range,
 * page thumbnails. Every operation keeps the document's own page dictionaries where it can, so links, form fields
 * and bookmarks that point at kept pages stay valid.
 */
object PageOrganize {
    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    // ------------------------------------------------------------------ destinations / outline

    /** The page dictionary a destination (array, name, or GoTo action dictionary) points at, or null. */
    fun destPage(doc: PDDocument, dest: COSBase?): COSDictionary? = when (val d = deref(dest)) {
        is COSArray -> deref(d.getObject(0)) as? COSDictionary
        is COSString, is COSName -> runCatching {
            doc.documentCatalog.findNamedDestinationPage(if (d is COSString) PDNamedDestination(d) else PDNamedDestination(d as COSName))?.cosObject
        }.getOrNull()?.let { deref(it.getObject(0)) as? COSDictionary }
        is COSDictionary -> if (d.getNameAsString(COSName.S) == "GoTo") destPage(doc, d.getDictionaryObject(COSName.D)) else null
        else -> null
    }

    /** The page an outline item / link opens (its /Dest, or a GoTo /A). */
    fun itemPage(doc: PDDocument, item: COSDictionary): COSDictionary? =
        destPage(doc, item.getDictionaryObject(COSName.DEST)) ?: (deref(item.getDictionaryObject(COSName.A)) as? COSDictionary)?.let { destPage(doc, it) }

    private fun children(node: COSDictionary): List<COSDictionary> {
        val out = ArrayList<COSDictionary>(); val seen = HashSet<COSDictionary>()
        var it = deref(node.getDictionaryObject(COSName.FIRST)) as? COSDictionary
        while (it != null && seen.add(it) && out.size < 100_000) { out += it; it = deref(it.getDictionaryObject(COSName.NEXT)) as? COSDictionary }
        return out
    }

    /** Re-links [kids] as the children of [node] (First/Last/Prev/Next/Parent/Count). */
    private fun relink(node: COSDictionary, kids: List<COSDictionary>) {
        node.removeItem(COSName.FIRST); node.removeItem(COSName.LAST)
        kids.forEachIndexed { i, k ->
            k.setItem(COSName.PARENT, node)
            if (i > 0) k.setItem(COSName.PREV, kids[i - 1]) else k.removeItem(COSName.PREV)
            if (i < kids.size - 1) k.setItem(COSName.NEXT, kids[i + 1]) else k.removeItem(COSName.NEXT)
        }
        if (kids.isNotEmpty()) { node.setItem(COSName.FIRST, kids.first()); node.setItem(COSName.LAST, kids.last()) }
        val open = node.getInt(COSName.COUNT, 0) >= 0
        if (kids.isEmpty()) node.removeItem(COSName.COUNT) else node.setInt(COSName.COUNT, if (open || node.containsKey(COSName.TYPE)) kids.size else -kids.size)
    }

    /**
     * Nitro "remove related bookmarks when deleting pages" (and the dead-bookmark bug): outline items that point at
     * [gone] pages are removed; their children move up to the removed item's place, so nothing below is lost.
     * Returns the number of items removed.
     */
    fun pruneOutline(doc: PDDocument, gone: Set<COSDictionary>): Int {
        val root = deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES)) as? COSDictionary ?: return 0
        var n = 0
        fun walk(node: COSDictionary, depth: Int): List<COSDictionary> {
            if (depth > 40) return children(node)
            val kept = ArrayList<COSDictionary>()
            for (k in children(node)) {
                val sub = walk(k, depth + 1)
                val pg = itemPage(doc, k)
                if (pg != null && pg in gone) { n++; kept += sub } else { relink(k, sub); kept += k }
            }
            return kept
        }
        relink(root, walk(root, 0))
        return n
    }

    /** Link annotations that jump to [gone] pages (they would lead nowhere). Returns how many were removed. */
    fun pruneLinks(doc: PDDocument, gone: Set<COSDictionary>): Int {
        var n = 0
        doc.pages.forEach { p ->
            val list = runCatching { p.annotations }.getOrNull() ?: return@forEach
            val keep = list.filterNot { a -> a.subtype == "Link" && itemPage(doc, a.cosObject)?.let { it in gone } == true }
            if (keep.size != list.size) { n += list.size - keep.size; p.annotations = keep }
        }
        return n
    }

    /** Deletes [pages] (zero-based) and the bookmarks / links that pointed only at them. */
    fun deleteIn(doc: PDDocument, pages: Collection<Int>) {
        val gone = pages.filter { it in 0 until doc.numberOfPages }.map { doc.getPage(it).cosObject }.toSet()
        if (gone.isEmpty()) return
        pruneOutline(doc, gone); pruneLinks(doc, gone)
        pages.filter { it in 0 until doc.numberOfPages }.sortedDescending().forEach { doc.removePage(it) }
    }

    /**
     * Deep copy of [src]'s outline under [parent] of [dst] (or at the top level when null): items whose target page is
     * in [pageMap] (source page → copied page) keep a destination onto the copy; items without a target in the map
     * are kept only for their children. Returns the number of items copied.
     */
    fun copyOutline(src: PDDocument, dst: PDDocument, pageMap: Map<COSDictionary, PDPage>, parent: COSDictionary? = null): Int {
        val sroot = deref(src.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES)) as? COSDictionary ?: return 0
        val droot = parent ?: (deref(dst.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES)) as? COSDictionary
            ?: PDDocumentOutline().also { dst.documentCatalog.documentOutline = it }.cosObject)
        var n = 0
        fun copy(node: COSDictionary, depth: Int): List<COSDictionary> {
            if (depth > 40) return emptyList()
            val out = ArrayList<COSDictionary>()
            for (k in children(node)) {
                val kids = copy(k, depth + 1)
                val target = itemPage(src, k)?.let { pageMap[it] }
                if (target == null && kids.isEmpty()) continue
                val d = COSDictionary()
                d.setItem(COSName.TITLE, COSString(k.getString(COSName.TITLE) ?: ""))
                (deref(k.getDictionaryObject(COSName.C)) as? COSArray)?.let { d.setItem(COSName.C, it) }
                if (k.containsKey(COSName.F)) d.setInt(COSName.F, k.getInt(COSName.F, 0))
                if (target != null) {
                    // same view (XYZ / Fit …) onto the copied page
                    val old = (deref(k.getDictionaryObject(COSName.DEST)) as? COSArray)
                        ?: ((deref(k.getDictionaryObject(COSName.A)) as? COSDictionary)?.let { deref(it.getDictionaryObject(COSName.D)) } as? COSArray)
                    val a = COSArray(); a.add(target.cosObject)
                    if (old != null && old.size() > 1) for (i in 1 until old.size()) a.add(old.get(i)) else a.add(COSName.getPDFName("Fit"))
                    d.setItem(COSName.DEST, a)
                }
                relink(d, kids)
                n++; out += d
            }
            return out
        }
        val added = copy(sroot, 0)
        relink(droot, children(droot) + added)
        return n
    }

    /** Copies the document-level attachments of [src] into [dst] (names made unique). Returns how many. */
    fun copyAttachments(src: PDDocument, dst: PDDocument): Int {
        val parts = com.smartdocs.editor.core.tools5.Embed.all(src)
        if (parts.isEmpty()) return 0
        val list = parts.mapNotNull { (k, s) ->
            val ef = s.embeddedFileUnicode ?: s.embeddedFile ?: return@mapNotNull null
            com.smartdocs.editor.core.tools5.Attachment(s.fileUnicode ?: s.file ?: k, ef.subtype ?: com.smartdocs.editor.core.tools5.Embed.mimeOf(k), ef.toByteArray())
        }
        com.smartdocs.editor.core.tools5.Embed.addFiles(dst, list, parts.values.mapNotNull { s -> (s.fileUnicode ?: s.file)?.let { n -> s.fileDescription?.let { n to it } } }.toMap())
        return list.size
    }

    // ------------------------------------------------------------------ file operations

    /** Delete [pages]; bookmarks and links that lead to them go too. */
    suspend fun delete(src: File, out: File, password: String?, pages: Collection<Int>) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc -> deleteIn(doc, pages) }
    }

    /** Foxit "delete blank pages": pages without text and with almost no ink. Returns the pages removed. */
    suspend fun deleteBlank(context: Context, src: File, out: File, password: String?, onProgress: (Float) -> Unit = {}): List<Int> {
        val blank = PageOps5.blankPages(context, src, password, onProgress = onProgress)
        val count = E5Io.load(src, password).use { it.numberOfPages }
        if (blank.isEmpty() || blank.size >= count) return emptyList()
        delete(src, out, password, blank)
        return blank
    }

    /**
     * Insert all pages of [other] at [at], with its bookmarks (under a new top-level item named [title]) and its
     * attachments when asked.
     */
    suspend fun insertFile(src: File, out: File, password: String?, other: File, otherPassword: String?, at: Int,
                           keepBookmarks: Boolean = true, keepAttachments: Boolean = true, title: String = other.nameWithoutExtension) = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            E5Io.load(other, otherPassword).use { od ->
                var pos = at.coerceIn(0, doc.numberOfPages)
                val map = HashMap<COSDictionary, PDPage>()
                var first: PDPage? = null
                od.pages.forEach { sp ->
                    val imported = doc.importPage(sp)
                    doc.pages.remove(imported); PageOps5.insertAt(doc, imported, pos++)
                    map[sp.cosObject] = imported
                    if (first == null) first = imported
                }
                val firstPage = first
                if (keepBookmarks && firstPage != null && od.documentCatalog.documentOutline != null) {
                    val holder = PDOutlineItem().apply { this.title = title; destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination().apply { setPage(firstPage) } }
                    val outline = doc.documentCatalog.documentOutline ?: PDDocumentOutline().also { doc.documentCatalog.documentOutline = it }
                    outline.addLast(holder)
                    copyOutline(od, doc, map, holder.cosObject)
                }
                if (keepAttachments) copyAttachments(od, doc)
                E5Io.save(doc, out)
            }
        }
    }

    /** Order after moving [pages] (kept in their order) before page [before] (0..count; count = to the end). Pure. */
    fun moveOrder(count: Int, pages: List<Int>, before: Int): List<Int> {
        val set = pages.filter { it in 0 until count }.toSortedSet()
        val rest = (0 until count).filter { it !in set }
        val pos = rest.count { it < before.coerceIn(0, count) }
        return rest.take(pos) + set + rest.drop(pos)
    }

    /** Moves the item at [from] to [to] (list reorder for the insert / mix source lists). No-op when out of range. */
    fun <T> moveItem(list: MutableList<T>, from: Int, to: Int) {
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
    }

    /** Order after swapping pages [a] and [b]. Pure. */
    fun swapOrder(count: Int, a: Int, b: Int): List<Int> = (0 until count).map { when (it) { a -> b; b -> a; else -> it } }

    suspend fun arrange(src: File, out: File, password: String?, order: List<Int>) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc -> PageOps5.arrange(doc, order) }
    }

    /**
     * Foxit "rearrange pages to match the bookmark order": each bookmarked page starts a section that runs to the next
     * bookmarked page (document order); sections are put in the order their first bookmark appears in the outline
     * (pre-order). Pages before the first section stay in front. Pure: [starts] = section start pages in outline order.
     */
    fun bookmarkOrder(count: Int, starts: List<Int>): List<Int> {
        val firsts = starts.filter { it in 0 until count }.distinct()
        if (firsts.isEmpty()) return (0 until count).toList()
        val sorted = firsts.sorted()
        val sectionOf = HashMap<Int, List<Int>>()
        sorted.forEachIndexed { i, s -> sectionOf[s] = (s until (sorted.getOrNull(i + 1) ?: count)).toList() }
        return (0 until sorted.first()).toList() + firsts.flatMap { sectionOf.getValue(it) }
    }

    /** Section starts in outline order for [bookmarkOrder]. */
    fun outlineStarts(doc: PDDocument): List<Int> {
        val idx = doc.pages.withIndex().associate { it.value.cosObject to it.index }
        val root = deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES)) as? COSDictionary ?: return emptyList()
        val out = ArrayList<Int>()
        fun walk(n: COSDictionary, d: Int) { if (d > 40) return; children(n).forEach { k -> itemPage(doc, k)?.let { idx[it] }?.let { out += it }; walk(k, d + 1) } }
        walk(root, 0)
        return out
    }

    suspend fun rearrangeByBookmarks(src: File, out: File, password: String?): Boolean = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            val order = bookmarkOrder(doc.numberOfPages, outlineStarts(doc))
            if (order == (0 until doc.numberOfPages).toList()) return@use false
            PageOps5.arrange(doc, order); E5Io.save(doc, out); true
        }
    }

    /** Bookmark levels: start pages of the items at [level] (0 = top) in document order. */
    fun levelStarts(doc: PDDocument, level: Int): List<Int> {
        val idx = doc.pages.withIndex().associate { it.value.cosObject to it.index }
        val root = deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES)) as? COSDictionary ?: return emptyList()
        val out = ArrayList<Int>()
        fun walk(n: COSDictionary, d: Int) { if (d > level) return; children(n).forEach { k -> if (d == level) itemPage(doc, k)?.let { idx[it] }?.let { out += it }; walk(k, d + 1) } }
        walk(root, 0)
        return out.distinct().sorted()
    }

    /** Name of part [k] (0-based) of [total] for [pages] (zero-based). Pure. */
    fun partName(base: String, scheme: PartName, k: Int, total: Int, pages: List<Int>): String = base + "_" + when (scheme) {
        PartName.NUMBER -> (k + 1).toString()
        PartName.PADDED -> (k + 1).toString().padStart(total.toString().length.coerceAtLeast(2), '0')
        PartName.LETTER -> { var n = k; val sb = StringBuilder(); do { sb.insert(0, 'A' + n % 26); n = n / 26 - 1 } while (n >= 0); sb.toString() }
        PartName.RANGE -> if (pages.isEmpty()) "" else if (pages.size == 1) "p${pages[0] + 1}" else "p${pages.first() + 1}-${pages.last() + 1}"
    }

    /**
     * Writes each list of [groups] (zero-based pages of [src]) as its own PDF in [outDir], keeping the bookmarks, links
     * and attachments that belong to it when [keepNavigation] (Nitro "update navigational elements"). Names come from
     * [base] and [scheme]. Returns the files.
     */
    suspend fun writeParts(src: File, password: String?, groups: List<List<Int>>, outDir: File, base: String, scheme: PartName,
                           keepNavigation: Boolean = true, unique: (File, String) -> File = { d, b -> File(d, "$b.pdf") }): List<File> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        E5Io.load(src, password).use { doc ->
            groups.filter { it.isNotEmpty() }.let { gs ->
                gs.mapIndexed { k, list ->
                    PDDocument().use { nd ->
                        val map = HashMap<COSDictionary, PDPage>()
                        list.filter { it in 0 until doc.numberOfPages }.forEach { i -> val sp = doc.getPage(i); map[sp.cosObject] = nd.importPage(sp) }
                        nd.documentInformation = doc.documentInformation
                        if (keepNavigation) {
                            copyOutline(doc, nd, map)
                            // links that jump outside the part would lead nowhere
                            val inside = map.values.map { it.cosObject }.toSet()
                            nd.pages.forEach { p -> runCatching { p.annotations }.getOrNull()?.let { an ->
                                val keep = an.filterNot { a -> a.subtype == "Link" && itemPage(doc, a.cosObject)?.let { pg -> map[pg]?.cosObject ?: pg }?.let { it !in inside } == true }
                                // re-point the kept internal links onto the copied pages
                                keep.filter { it.subtype == "Link" }.forEach { a -> repointLink(doc, a.cosObject, map) }
                                if (keep.size != an.size) p.annotations = keep
                            } }
                        }
                        val f = unique(outDir, partName(base, scheme, k, gs.size, list))
                        E5Io.save(nd, f); f
                    }
                }
            }
        }
    }

    private fun repointLink(src: PDDocument, link: COSDictionary, map: Map<COSDictionary, PDPage>) {
        val pg = itemPage(src, link) ?: return
        val np = map[pg] ?: return
        val a = COSArray(); a.add(np.cosObject); a.add(COSName.getPDFName("Fit"))
        link.removeItem(COSName.A); link.setItem(COSName.DEST, a)
    }

    /** Parts at bookmarks of [level]: each part runs from a bookmarked page to the page before the next one. */
    suspend fun splitByBookmarks(src: File, password: String?, level: Int, outDir: File, base: String, scheme: PartName,
                                 keepNavigation: Boolean, unique: (File, String) -> File): List<File> {
        val groups = E5Io.load(src, password).use { doc ->
            val starts = levelStarts(doc, level)
            if (starts.isEmpty()) return emptyList()
            val n = doc.numberOfPages
            val all = (listOf(0) + starts).distinct().sorted()
            all.mapIndexed { i, s -> (s until (all.getOrNull(i + 1) ?: n)).toList() }
        }
        return writeParts(src, password, groups, outDir, base, scheme, keepNavigation, unique)
    }

    /** Foxit extract modes. ONE = one PDF; GROUPS = one PDF per comma-separated range; EACH = one PDF per page. */
    enum class ExtractMode { ONE, GROUPS, EACH }

    /** Groups of a range text "1-3, 5, 8-9" for [ExtractMode.GROUPS] (each comma part is one group). Pure. */
    fun rangeGroups(spec: String, count: Int): List<List<Int>> =
        Digits.toWestern(spec).replace('،', ',').split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { PageSet.parse(it, count) }.filter { it.isNotEmpty() }

    /**
     * Interleave several files (Foxit Alternate Mix): one page of each file in turn, in the given order; a file with
     * [reversed] set gives its pages last to first. Pure: returns (file index, page) pairs.
     */
    fun mixOrder(counts: List<Int>, reversed: List<Boolean>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val max = counts.maxOrNull() ?: 0
        for (i in 0 until max) counts.forEachIndexed { f, c -> if (i < c) out += f to (if (reversed.getOrElse(f) { false }) c - 1 - i else i) }
        return out
    }

    suspend fun mix(files: List<Pair<File, String?>>, reversed: List<Boolean>, out: File) = withContext(Dispatchers.IO) {
        val docs = files.map { (f, pw) -> E5Io.load(f, pw) }
        try {
            PDDocument().use { nd ->
                mixOrder(docs.map { it.numberOfPages }, reversed).forEach { (f, p) -> nd.importPage(docs[f].getPage(p)) }
                E5Io.save(nd, out)
            }
        } finally { docs.forEach { runCatching { it.close() } } }
    }

    /** Where [insertInto] puts the pages in each target. */
    enum class IntoPos { START, END, AFTER }

    /** Foxit "Insert > Into file": [pages] of [src] go into each target (the source is kept). Returns the outputs. */
    suspend fun insertInto(src: File, password: String?, pages: List<Int>, targets: List<Pair<File, String?>>, pos: IntoPos, afterPage: Int,
                           outFor: (File) -> File): List<File> = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { sd ->
            targets.map { (t, tpw) ->
                E5Io.load(t, tpw).use { td ->
                    var at = when (pos) { IntoPos.START -> 0; IntoPos.END -> td.numberOfPages; IntoPos.AFTER -> afterPage.coerceIn(0, td.numberOfPages) }
                    pages.filter { it in 0 until sd.numberOfPages }.forEach { i ->
                        val imp = td.importPage(sd.getPage(i)); td.pages.remove(imp); PageOps5.insertAt(td, imp, at++)
                    }
                    val o = outFor(t); E5Io.save(td, o); o
                }
            }
        }
    }

    /** Flatten annotations and form fields of [pages] only (Foxit "flatten with a page range"). Returns how many. */
    suspend fun flattenPages(src: File, out: File, password: String?, pages: Collection<Int>): Int = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            val target = pages.filter { it in 0 until doc.numberOfPages }.map { doc.getPage(it).cosObject }.toSet()
            var n = 0
            doc.documentCatalog.acroForm?.let { af ->
                val fields = runCatching { af.fieldTree.toList() }.getOrDefault(emptyList()).filter { f ->
                    f is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField && f.widgets.any { w -> (deref(w.cosObject.getDictionaryObject(COSName.P)) as? COSDictionary)?.let { it in target } == true }
                }
                if (fields.isNotEmpty()) runCatching { af.flatten(fields, true); n += fields.size }
            }
            val annots = HashSet<COSDictionary>()
            pages.filter { it in 0 until doc.numberOfPages }.forEach { p -> runCatching { doc.getPage(p).annotations }.getOrNull()?.forEach { annots += it.cosObject } }
            n += com.smartdocs.editor.core.fillsign2.SignatureFlatten.flattenIn(doc) { it.cosObject in annots }
            E5Io.save(doc, out)
            n
        }
    }

    /** Nitro / Foxit "Embed all page thumbnails": a small JPEG /Thumb per page (rendered with PDFium). */
    suspend fun embedThumbnails(context: Context, src: File, out: File, password: String?, size: Int = 106, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val s = PdfSession.open(context, src, password)
        try {
            E5Io.edit(src, out, password) { doc ->
                for (i in 0 until minOf(s.pageCount, doc.numberOfPages)) {
                    onProgress((i + 1f) / s.pageCount)
                    val (w, h) = s.pageSize(i)
                    val k = size / maxOf(w, h).coerceAtLeast(1f)
                    val bmp = s.render(i, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), annotations = true)
                    val img = JPEGFactory.createFromImage(doc, bmp, 0.7f)
                    s.invalidateCache()
                    doc.getPage(i).cosObject.setItem(COSName.getPDFName("Thumb"), img.cosObject)
                }
            }
        } finally { s.closeAsync() }
    }

    suspend fun removeThumbnails(src: File, out: File, password: String?): Int = withContext(Dispatchers.IO) {
        var n = 0
        E5Io.edit(src, out, password) { doc -> doc.pages.forEach { p -> if (p.cosObject.containsKey(COSName.getPDFName("Thumb"))) { p.cosObject.removeItem(COSName.getPDFName("Thumb")); n++ } } }
        n
    }

    /** Pages of [pages] that remain after removing [removed]: their new indexes. Pure (used to re-select after delete). */
    fun remap(count: Int, removed: Set<Int>): Map<Int, Int> {
        val out = HashMap<Int, Int>(); var k = 0
        for (i in 0 until count) if (i !in removed) out[i] = k++
        return out
    }

    @Suppress("unused") private fun intOf(b: COSBase?) = (deref(b) as? COSInteger)?.intValue()
}
