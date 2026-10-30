package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.text.PDFMarkedContentExtractor
import com.tom_roush.pdfbox.text.TextPosition

/**
 * FINAL_CHECK_2 #20 — the Content panel (Acrobat / Foxit "Content"): the marked-content items of one page in
 * content-stream order — the marked-content tag, MCID, what it holds (text / picture / other) and the structure
 * element that owns it (none = untagged content) — and "create tag from selection": the chosen items become one new
 * structure element of a type, placed under the document element, with the parent tree updated. Pure over
 * [PDDocument] — JVM-tested.
 */
object AccessContent {

    /** One marked-content item of a page. [mcid] -1 = not marked with an MCID (cannot be tagged until the page is marked). */
    data class Item(val page: Int, val mcid: Int, val tag: String, val kind: String, val text: String, val elemType: String?, val elemPath: List<Int>?) {
        val tagged: Boolean get() = elemPath != null
        val artifact: Boolean get() = tag == "Artifact"
    }

    private val N_ROOT = COSName.getPDFName("StructTreeRoot")
    private val N_PG = COSName.getPDFName("Pg")
    private val N_MCID = COSName.getPDFName("MCID")
    private val N_SP = COSName.getPDFName("StructParents")
    private val N_PT = COSName.getPDFName("ParentTree")
    private val N_NEXT = COSName.getPDFName("ParentTreeNextKey")
    private val N_NUMS = COSName.getPDFName("Nums")
    private val N_KIDS = COSName.getPDFName("Kids")
    private val N_LIMITS = COSName.getPDFName("Limits")

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
    private fun isElem(b: COSBase?) = (deref(b) as? COSDictionary)?.let { it.containsKey(COSName.S) && it.getNameAsString(COSName.TYPE).let { t -> t == null || t == "StructElem" } } == true
    private fun kidsOf(e: COSDictionary): List<COSBase> = when (val k = deref(e.getDictionaryObject(COSName.K))) {
        is COSArray -> (0 until k.size()).map { k.get(it) }
        null -> emptyList()
        else -> listOf(e.getItem(COSName.K))
    }

    /** MCID → (element type, path) of every element whose content lies on [page]. */
    fun owners(doc: PDDocument, page: Int): Map<Int, Pair<String, List<Int>>> {
        val root = deref(doc.documentCatalog.cosObject.getDictionaryObject(N_ROOT)) as? COSDictionary ?: return emptyMap()
        val pageCos = doc.getPage(page).cosObject
        val out = HashMap<Int, Pair<String, List<Int>>>()
        fun walk(e: COSDictionary, path: List<Int>, depth: Int, inherited: COSDictionary?) {
            if (depth > 60) return
            val pg = deref(e.getDictionaryObject(N_PG)) as? COSDictionary ?: inherited
            val type = e.getNameAsString(COSName.S) ?: "?"
            kidsOf(e).forEachIndexed { i, b ->
                val d = deref(b)
                when {
                    d is COSInteger -> if (pg === pageCos) out[d.intValue()] = type to path
                    d is COSDictionary && isElem(d) -> walk(d, path + i, depth + 1, pg)
                    d is COSDictionary && d.getNameAsString(COSName.TYPE) == "MCR" -> {
                        val mpg = deref(d.getDictionaryObject(N_PG)) as? COSDictionary ?: pg
                        if (mpg === pageCos) (deref(d.getDictionaryObject(N_MCID)) as? COSInteger)?.let { out[it.intValue()] = type to path }
                    }
                }
            }
        }
        // root's kids are elements at paths [i]
        kidsOf(root).forEachIndexed { i, b -> (deref(b) as? COSDictionary)?.takeIf { isElem(it) }?.let { walk(it, listOf(i), 1, null) } }
        return out
    }

    /** The marked-content items of [page] in stream order. */
    fun items(doc: PDDocument, page: Int): List<Item> {
        val owners = owners(doc, page)
        val ex = object : PDFMarkedContentExtractor() {}
        runCatching { ex.processPage(doc.getPage(page)) }
        val out = ArrayList<Item>()
        ex.markedContents.forEach { mc ->
            val sb = StringBuilder(); var pics = 0; var other = 0
            fun collect(c: Any?) {
                when (c) {
                    is TextPosition -> sb.append(c.unicode)
                    is PDXObject -> pics++
                    is com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent -> c.contents.forEach { collect(it) }
                    is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage -> pics++
                    null -> Unit
                    else -> other++
                }
            }
            mc.contents.forEach { collect(it) }
            val text = sb.toString().replace(Regex("\\s+"), " ").trim()
            val kind = when { text.isNotEmpty() && pics == 0 -> "text"; pics > 0 && text.isEmpty() -> "image"; text.isNotEmpty() -> "mixed"; else -> "other" }
            val own = if (mc.mcid >= 0) owners[mc.mcid] else null
            out += Item(page, mc.mcid, mc.tag ?: "", kind, text.take(160), own?.first, own?.second)
        }
        return out
    }

    /**
     * Create one element of [type] from the items [mcids] of [page]: they leave their current elements and become the
     * new element's content; the parent tree of the page points at it. The element goes under the first element of
     * the tree (the document element) — or the root when there is none. Returns the new element's path.
     */
    fun createTag(doc: PDDocument, page: Int, mcids: List<Int>, type: String): List<Int> {
        require(mcids.isNotEmpty())
        val cat = doc.documentCatalog.cosObject
        val root = (deref(cat.getDictionaryObject(N_ROOT)) as? COSDictionary) ?: COSDictionary().also {
            it.setItem(COSName.TYPE, N_ROOT); it.setItem(COSName.K, COSArray()); cat.setItem(N_ROOT, it)
            (deref(cat.getDictionaryObject(COSName.getPDFName("MarkInfo"))) as? COSDictionary ?: COSDictionary().also { m -> cat.setItem(COSName.getPDFName("MarkInfo"), m) }).setBoolean(COSName.getPDFName("Marked"), true)
        }
        val pageCos = doc.getPage(page).cosObject
        val wanted = mcids.toSet()
        // take the MCIDs away from their current owners
        fun strip(e: COSDictionary, inherited: COSDictionary?, depth: Int) {
            if (depth > 60) return
            val pg = deref(e.getDictionaryObject(N_PG)) as? COSDictionary ?: inherited
            val k = deref(e.getDictionaryObject(COSName.K))
            val list = if (k is COSArray) k else null
            val kids = kidsOf(e)
            val keep = ArrayList<COSBase>()
            for (b in kids) {
                val d = deref(b)
                val drop = when {
                    d is COSInteger -> pg === pageCos && d.intValue() in wanted
                    d is COSDictionary && d.getNameAsString(COSName.TYPE) == "MCR" ->
                        ((deref(d.getDictionaryObject(N_PG)) as? COSDictionary ?: pg) === pageCos) && (deref(d.getDictionaryObject(N_MCID)) as? COSInteger)?.intValue() in wanted
                    else -> { if (d is COSDictionary && isElem(d)) strip(d, pg, depth + 1); false }
                }
                if (!drop) keep += b
            }
            if (keep.size != kids.size) {
                if (list != null) { list.clear(); keep.forEach { list.add(it) } }
                else if (keep.isEmpty()) e.removeItem(COSName.K) else e.setItem(COSName.K, keep.first())
            }
        }
        kidsOf(root).forEach { b -> (deref(b) as? COSDictionary)?.takeIf { isElem(it) }?.let { strip(it, null, 1) } }

        // the new element under the document element (first root kid) or the root
        val rootKids = root.getDictionaryObject(COSName.K).let { k -> deref(k) as? COSArray ?: COSArray().also { a -> if (k != null) a.add(k); root.setItem(COSName.K, a) } }
        val first = (0 until rootKids.size()).map { rootKids.getObject(it) }.firstOrNull { isElem(it) } as? COSDictionary
        val parent = first ?: root
        val elem = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setItem(COSName.S, COSName.getPDFName(type)); setItem(COSName.P, parent); setItem(N_PG, pageCos)
            setItem(COSName.K, COSArray().apply { mcids.sorted().forEach { add(COSInteger.get(it.toLong())) } })
        }
        val pk = parent.getDictionaryObject(COSName.K).let { k -> deref(k) as? COSArray ?: COSArray().also { a -> if (k != null) a.add(k); parent.setItem(COSName.K, a) } }
        pk.add(elem)
        val path = if (first != null) listOf((0 until rootKids.size()).indexOfFirst { rootKids.getObject(it) === first }, pk.size() - 1) else listOf(pk.size() - 1)
        // parent tree: page's StructParents array, MCID → element
        val key = (deref(pageCos.getDictionaryObject(N_SP)) as? COSInteger)?.intValue() ?: run {
            val next = (deref(root.getDictionaryObject(N_NEXT)) as? COSInteger)?.intValue() ?: 0
            pageCos.setInt(N_SP, next); root.setInt(N_NEXT, next + 1); next
        }
        val arr = parentArray(root, key)
        val max = mcids.max()
        while (arr.size() <= max) arr.add(com.tom_roush.pdfbox.cos.COSNull.NULL)
        mcids.forEach { arr.set(it, elem) }
        return path
    }

    /** The parent-tree array of [key] (created in the root /Nums when missing). */
    private fun parentArray(root: COSDictionary, key: Int): COSArray {
        val pt = (deref(root.getDictionaryObject(N_PT)) as? COSDictionary) ?: COSDictionary().also { it.setItem(N_NUMS, COSArray()); root.setItem(N_PT, it) }
        fun find(node: COSDictionary, depth: Int): COSArray? {
            if (depth > 30) return null
            (deref(node.getDictionaryObject(N_NUMS)) as? COSArray)?.let { nums ->
                var i = 0
                while (i + 1 < nums.size()) {
                    if ((deref(nums.get(i)) as? COSInteger)?.intValue() == key) {
                        val v = deref(nums.get(i + 1))
                        return v as? COSArray ?: COSArray().also { a -> if (v != null && v !is com.tom_roush.pdfbox.cos.COSNull) a.add(v); nums.set(i + 1, a) }
                    }
                    i += 2
                }
            }
            (deref(node.getDictionaryObject(N_KIDS)) as? COSArray)?.let { kids -> for (j in 0 until kids.size()) (deref(kids.get(j)) as? COSDictionary)?.let { c -> find(c, depth + 1)?.let { return it } } }
            return null
        }
        find(pt, 0)?.let { return it }
        // not there: append to the root Nums (kept sorted by key)
        val nums = (deref(pt.getDictionaryObject(N_NUMS)) as? COSArray) ?: COSArray().also { pt.setItem(N_NUMS, it); pt.removeItem(N_LIMITS) }
        val arr = COSArray()
        var at = nums.size()
        var i = 0
        while (i + 1 < nums.size()) { if (((deref(nums.get(i)) as? COSInteger)?.intValue() ?: -1) > key) { at = i; break }; i += 2 }
        nums.add(at, arr); nums.add(at, COSInteger.get(key.toLong()))
        return arr
    }

    /** A structure element's content box on a page, in page space with the origin top-left (fractions 0..1 of the page size). */
    data class ElemBox(val path: List<Int>, val type: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * FINAL_CHECK_2 #19 (reading order on the page): the box of every element that has content on [page], in tree
     * (reading) order — the union of its marked content's glyph boxes (figures without text: their /BBox attribute).
     */
    fun elementBoxes(doc: PDDocument, page: Int): List<ElemBox> {
        val owners = owners(doc, page)
        if (owners.isEmpty()) return emptyList()
        val pd = doc.getPage(page)
        val cb = pd.cropBox
        val rot = ((pd.rotation % 360) + 360) % 360
        val pw = if (rot % 180 == 0) cb.width else cb.height; val ph = if (rot % 180 == 0) cb.height else cb.width
        val ex = object : PDFMarkedContentExtractor() {}
        runCatching { ex.processPage(pd) }
        val boxes = HashMap<List<Int>, FloatArray>()   // path → l, t, r, b (fractions)
        fun add(path: List<Int>, l: Float, t: Float, r: Float, b: Float) {
            val a = boxes.getOrPut(path) { floatArrayOf(1f, 1f, 0f, 0f) }
            a[0] = minOf(a[0], l); a[1] = minOf(a[1], t); a[2] = maxOf(a[2], r); a[3] = maxOf(a[3], b)
        }
        fun collect(path: List<Int>, c: Any?) {
            when (c) {
                is TextPosition -> {
                    // TextPosition: origin top-left after the page rotation, in points
                    val x = c.xDirAdj; val y = c.yDirAdj; val w = c.widthDirAdj; val h = c.heightDir
                    add(path, (x / pw).coerceIn(0f, 1f), ((y - h) / ph).coerceIn(0f, 1f), ((x + w) / pw).coerceIn(0f, 1f), (y / ph).coerceIn(0f, 1f))
                }
                is com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent -> c.contents.forEach { collect(path, it) }
                else -> Unit
            }
        }
        ex.markedContents.forEach { mc -> owners[mc.mcid]?.let { (_, path) -> mc.contents.forEach { collect(path, it) } } }
        // figures with a /BBox but no text (PDF space, y up)
        AccessInspect.figures(doc).forEach { f ->
            if (f.page != page || f.bbox == null || f.bbox.size < 4) return@forEach
            val o = owners.values.map { it.second }.distinct().firstOrNull { p -> boxes[p] == null && AccessTags.at(doc, p) === f.elem } ?: return@forEach
            val l = (f.bbox[0] - cb.lowerLeftX) / cb.width; val r = (f.bbox[2] - cb.lowerLeftX) / cb.width
            val t = 1f - (f.bbox[3] - cb.lowerLeftY) / cb.height; val b = 1f - (f.bbox[1] - cb.lowerLeftY) / cb.height
            if (rot == 0) add(o, l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        }
        // reading order = the order the owners were met in the tree walk (paths compare lexicographically)
        return boxes.entries.sortedWith { a, b -> comparePaths(a.key, b.key) }
            .map { (path, a) -> ElemBox(path, owners.values.firstOrNull { it.second == path }?.first ?: "?", a[0], a[1], a[2], a[3]) }
            .filter { it.right > it.left && it.bottom > it.top }
    }

    private fun comparePaths(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until minOf(a.size, b.size)) { val c = a[i].compareTo(b[i]); if (c != 0) return c }
        return a.size.compareTo(b.size)
    }

    /** Marks a page's unmarked content with MCIDs (text runs as P, pictures as Figure, decoration as artifacts) without structure, so it can be tagged. */
    fun markPage(doc: PDDocument, page: Int): Boolean = AccessAutoTag.markContent(doc, doc.getPage(page))
}
