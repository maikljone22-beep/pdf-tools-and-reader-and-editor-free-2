package com.smartdocs.editor.core.edit5

import android.content.Context
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Page operations that work inside the document (page dictionaries keep their identity), so bookmarks, links and
 * form fields that point at kept pages stay valid. Copies share content streams and resources with the original.
 */
object PageOps5 {

    // ---------------- low-level helpers ----------------

    /** Copy of [page] inside the same document (shallow: content/resources shared; annotations copied, widgets dropped). */
    fun clonePage(page: PDPage): PDPage {
        val src = page.cosObject
        val d = COSDictionary()
        for ((k, v) in src.entrySet()) {
            if (k == COSName.PARENT || k == COSName.ANNOTS || k == COSName.STRUCT_PARENTS) continue
            d.setItem(k, v)
        }
        // inheritable attributes must be explicit on the copy (the parent may change)
        listOf(COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE).forEach { k ->
            if (!d.containsKey(k)) com.tom_roush.pdfbox.pdmodel.PDPageTree.getInheritableAttribute(src, k)?.let { d.setItem(k, it) }
        }
        val np = PDPage(d)
        val annots = runCatching { page.annotations }.getOrNull().orEmpty()
        if (annots.isNotEmpty()) {
            val copies = ArrayList<PDAnnotation>()
            for (a in annots) {
                if (a.subtype == "Widget" || a.subtype == "Popup") continue
                val ad = COSDictionary(a.cosObject)
                ad.removeItem(COSName.getPDFName("Popup")); ad.removeItem(COSName.getPDFName("IRT"))
                ad.setItem(COSName.P, d)
                copies += PDAnnotation.createAnnotation(ad)
            }
            np.annotations = copies
        }
        return np
    }

    /**
     * Re-arrange the page tree to [order] (indexes may repeat: repeats become copies; pages not listed are removed).
     * audit 5 E-4: the inherited /Resources /MediaBox /CropBox /Rotate are pushed down onto the pages before they are
     * re-parented ([com.smartdocs.editor.core.pdf.PdfOps.arrangeInPlace] when every page stays). audit 5 E-5 / E-7:
     * page labels follow the pages, fields whose widgets were on removed pages leave the form. An order without any
     * valid page changes nothing.
     */
    fun arrange(doc: PDDocument, order: List<Int>) {
        val n = doc.numberOfPages
        val valid = order.filter { it in 0 until n }
        if (valid.isEmpty()) return
        if (valid.toSet().size == n) { com.smartdocs.editor.core.pdf.PdfOps.arrangeInPlace(doc, valid); return }
        PageLabelRemap.keep(doc) {
            com.smartdocs.editor.core.pdf.PageExtract.pushDownInherited(doc)
            val pages = doc.pages.toList()
            val kept = valid.toSet()
            val gone = FormPrune.widgetsOn(pages.indices.filter { it !in kept }.map { pages[it] })
            val used = HashSet<Int>()
            setPages(doc, valid.map { i -> if (used.add(i)) pages[i] else clonePage(pages[i]) })
            FormPrune.prune(doc, gone)
        }
    }

    /** Make [seq] the flat page tree of [doc] (inherited attributes must have been pushed down first). */
    private fun setPages(doc: PDDocument, seq: List<PDPage>) {
        val root = doc.pages.cosObject
        val kids = COSArray()
        seq.forEach { p -> p.cosObject.setItem(COSName.PARENT, root); kids.add(p.cosObject) }
        root.setItem(COSName.KIDS, kids)
        root.setInt(COSName.COUNT, kids.size())
    }

    /** Insert [page] at [at] (0..count). */
    fun insertAt(doc: PDDocument, page: PDPage, at: Int) {
        val n = doc.numberOfPages
        if (at >= n) doc.pages.add(page) else doc.pages.insertBefore(page, doc.getPage(at.coerceAtLeast(0)))
    }

    /** Replace page references (destinations of bookmarks, links, named destinations, open action) old → new. */
    fun repoint(doc: PDDocument, map: Map<COSDictionary, COSDictionary>) {
        if (map.isEmpty()) return
        val seen = HashSet<COSBase>()
        fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
        fun fixArray(a: COSArray) {
            val first = deref(a.getObject(0)) as? COSDictionary ?: return
            map[first]?.let { a.set(0, it) }
        }
        fun visitDest(b: COSBase?) {
            when (val v = deref(b)) {
                is COSArray -> fixArray(v)
                is COSDictionary -> (deref(v.getDictionaryObject(COSName.D)) as? COSArray)?.let { fixArray(it) }
                else -> Unit
            }
        }
        fun visitAction(b: COSBase?) {
            val a = deref(b) as? COSDictionary ?: return
            if (!seen.add(a)) return
            if (a.getNameAsString(COSName.S) == "GoTo") visitDest(a.getDictionaryObject(COSName.D))
            when (val next = deref(a.getDictionaryObject(COSName.NEXT))) { is COSArray -> next.forEach { visitAction(it) }; is COSDictionary -> visitAction(next); else -> Unit }
        }
        // outline
        fun outline(node: COSDictionary?, depth: Int) {
            var item = deref(node?.getDictionaryObject(COSName.FIRST)) as? COSDictionary
            while (item != null && depth < 40 && seen.add(item)) {
                visitDest(item.getDictionaryObject(COSName.DEST)); visitAction(item.getDictionaryObject(COSName.A))
                outline(item, depth + 1)
                item = deref(item.getDictionaryObject(COSName.NEXT)) as? COSDictionary
            }
        }
        val cat = doc.documentCatalog.cosObject
        outline(deref(cat.getDictionaryObject(COSName.OUTLINES)) as? COSDictionary, 0)
        // annotations
        for (p in doc.pages) runCatching { p.annotations }.getOrNull()?.forEach { a ->
            visitDest(a.cosObject.getDictionaryObject(COSName.DEST)); visitAction(a.cosObject.getDictionaryObject(COSName.A))
        }
        // open action, /Dests dictionary, /Names /Dests tree
        when (val oa = deref(cat.getDictionaryObject(COSName.OPEN_ACTION))) { is COSArray -> fixArray(oa); is COSDictionary -> visitAction(oa); else -> Unit }
        (deref(cat.getDictionaryObject(COSName.DESTS)) as? COSDictionary)?.getValues()?.forEach { visitDest(it) }
        fun tree(n: COSDictionary?, depth: Int) {
            if (n == null || depth > 30) return
            (deref(n.getDictionaryObject(COSName.NAMES)) as? COSArray)?.let { arr -> for (k in 1 until arr.size() step 2) visitDest(arr.getObject(k)) }
            (deref(n.getDictionaryObject(COSName.KIDS)) as? COSArray)?.forEach { tree(deref(it) as? COSDictionary, depth + 1) }
        }
        tree(deref((deref(cat.getDictionaryObject(COSName.NAMES)) as? COSDictionary)?.getDictionaryObject(COSName.DESTS)) as? COSDictionary, 0)
    }

    // ---------------- operations ----------------

    suspend fun reverse(src: File, out: File, password: String?, pages: List<Int>? = null) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val all = (0 until doc.numberOfPages).toMutableList()
            val sel = (pages ?: all).sorted()
            val rev = sel.reversed()
            sel.forEachIndexed { k, idx -> all[idx] = rev[k] }
            arrange(doc, all)
        }
    }

    /** Duplicate [pages] [times] times; copies go right after each page or (when [atEnd]) at the end. */
    suspend fun duplicate(src: File, out: File, password: String?, pages: List<Int>, times: Int, atEnd: Boolean) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val n = doc.numberOfPages
            val set = pages.toSet()
            val order = if (atEnd) (0 until n).toList() + (1..times).flatMap { pages.sorted() }
                        else (0 until n).flatMap { i -> if (i in set) List(times + 1) { i } else listOf(i) }
            arrange(doc, order)
        }
    }

    /** Insert pages [pagesB] of [other] into [src] at [at] (copy pages between files). */
    suspend fun copyPages(src: File, out: File, password: String?, other: File, otherPassword: String?, pagesB: List<Int>, at: Int) = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            E5Io.load(other, otherPassword).use { od ->
                // audit 5 E-4 / E-5 / E-6: own resources, annotations and fields for the copies; labels kept
                PageLabelRemap.keep(doc) {
                    var pos = at.coerceIn(0, doc.numberOfPages)
                    PageImport.importPages(doc, od, pagesB).forEach { insertAt(doc, it, pos++) }
                }
                PageImport.protectForImport(doc, od, otherPassword)
                E5Io.save(doc, out)
            }
        }
    }

    /** Interleave A and B: A1 B1 A2 B2 … ([reverseB] for back sides scanned in reverse). */
    suspend fun interleave(a: File, b: File, out: File, passwordA: String?, passwordB: String?, reverseB: Boolean) = withContext(Dispatchers.IO) {
        E5Io.load(a, passwordA).use { doc ->
            E5Io.load(b, passwordB).use { bd ->
                val na = doc.numberOfPages; val nb = bd.numberOfPages
                // audit 5 E-4: inherited attributes onto A's pages before they are re-parented
                com.smartdocs.editor.core.pdf.PageExtract.pushDownInherited(doc)
                val aPages = doc.pages.toList()
                val bPages = PageImport.importPages(doc, bd, (0 until nb).toList())
                PageLabelRemap.keep(doc) {
                    setPages(doc, ImposeOrder.interleave(na, nb, reverseB).map { (which, i) -> if (which == 0) aPages[i] else bPages[i] })
                }
                PageImport.protectForImport(doc, bd, passwordB)
                E5Io.save(doc, out)
            }
        }
    }

    /**
     * Replace pages [targets] of [src] by pages [with] of [other] (pairwise; extra replacement pages are inserted after
     * the last target). Bookmarks and links that pointed at a replaced page point at its replacement.
     */
    suspend fun replace(src: File, out: File, password: String?, targets: List<Int>, other: File, otherPassword: String?, with: List<Int>) = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            E5Io.load(other, otherPassword).use { od ->
                val olds = targets.filter { it in 0 until doc.numberOfPages }.map { doc.getPage(it) }
                // audit 5 E-6: own resources / annotations / merged fields for the replacement pages
                val news = PageImport.importPages(doc, od, with.filter { it in 0 until od.numberOfPages })
                val map = HashMap<COSDictionary, COSDictionary>()
                PageLabelRemap.keep(doc) {
                    var anchor: PDPage? = null
                    news.forEachIndexed { k, np ->
                        val old = olds.getOrNull(k)
                        if (old != null) { doc.pages.insertBefore(np, old); map[old.cosObject] = np.cosObject; anchor = np }
                        else { val a = anchor; if (a != null) doc.pages.insertAfter(np, a) else doc.pages.add(np); anchor = np }
                    }
                    val replaced = olds.take(news.size)
                    // audit 5 E-7: fields whose widgets were only on the replaced pages go
                    val gone = FormPrune.widgetsOn(replaced)
                    replaced.forEach { doc.pages.remove(it) }
                    FormPrune.prune(doc, gone)
                }
                repoint(doc, map)
                PageImport.protectForImport(doc, od, otherPassword)
                // nav-prefs: Preferences → bookmarks "import bookmarks when replacing pages"
                if (com.smartdocs.editor.core.data.NpfPrefs.importBookmarksOnReplace) runCatching {
                    val srcPages = with.filter { it in 0 until od.numberOfPages }.map { od.getPage(it).cosObject }
                    com.smartdocs.editor.core.pdf.NpfOutline.importFrom(doc, od, srcPages.zip(news).toMap(), other.nameWithoutExtension)
                }
                E5Io.save(doc, out)
            }
        }
    }

    /**
     * Split each of [pages] into [cols]×[rows] pages (lossless: copies with a crop box per part), in reading order
     * ([rtl] = right to left). [xCuts]/[yCuts] are optional cut positions as fractions of the visible page.
     */
    suspend fun splitPages(src: File, out: File, password: String?, pages: List<Int>, cols: Int, rows: Int, rtl: Boolean, xCuts: List<Float>? = null, yCuts: List<Float>? = null) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc -> PageLabelRemap.keep(doc) {
            for (i in pages.sortedDescending()) {
                if (i !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(i)
                val info = PageInfo.of(page)
                val parts = ImposeOrder.splitParts(cols, rows, rtl, xCuts, yCuts)
                var last: PDPage = page
                parts.forEachIndexed { k, f ->
                    val p = if (k == 0) page else clonePage(page)
                    val r = android.graphics.RectF(f[0] * info.displayW, (f[1] + f[3]) * info.displayH, (f[0] + f[2]) * info.displayW, f[1] * info.displayH)
                    p.cropBox = E5Geom.rect(info.rectToUser(r))
                    if (k > 0) { doc.pages.insertAfter(p, last); last = p }
                }
            }
        } }
    }

    /** Blank pages (no text and almost no ink) found by rendering small thumbnails. */
    suspend fun blankPages(context: Context, src: File, password: String?, inkThreshold: Float = 0.002f, onProgress: (Float) -> Unit = {}): List<Int> = withContext(Dispatchers.IO) {
        val s = PdfSession.open(context, src, password)
        try {
            (0 until s.pageCount).filter { i ->
                onProgress((i + 1f) / s.pageCount)
                if (s.text(i).any { !it.isWhitespace() }) return@filter false
                val (w, h) = s.pageSize(i)
                val bw = 160; val bh = (if (w > 0f) 160 * h / w else 226f).toInt().coerceIn(1, 2000)
                val bmp = s.render(i, bw, bh, annotations = true)
                val px = IntArray(bw * bh); bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
                s.invalidateCache()
                MarginDetect.inkRatio(px) < inkThreshold
            }
        } finally { s.closeAsync() }
    }

    /** Pages whose text contains [marker] (case-insensitive). */
    suspend fun markerPages(context: Context, src: File, password: String?, marker: String): List<Int> = withContext(Dispatchers.IO) {
        val s = PdfSession.open(context, src, password)
        try { (0 until s.pageCount).filter { s.text(it).contains(marker, ignoreCase = true) } } finally { s.closeAsync() }
    }

    /**
     * Smart split: a new part starts at every separator page. [separators] are dropped when [dropSeparators]
     * (blank sheets) or start the next part (text markers). Files are named "<base>_<n>.pdf" in [outDir].
     */
    suspend fun splitAt(src: File, password: String?, separators: List<Int>, dropSeparators: Boolean, outDir: File, base: String): List<File> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        E5Io.load(src, password).use { doc ->
            val n = doc.numberOfPages
            val sep = separators.toSet()
            val parts = ArrayList<List<Int>>()
            var cur = ArrayList<Int>()
            for (i in 0 until n) {
                if (i in sep) {
                    if (cur.isNotEmpty()) parts += cur
                    cur = ArrayList()
                    if (!dropSeparators) cur += i
                } else cur += i
            }
            if (cur.isNotEmpty()) parts += cur
            parts.mapIndexed { k, list ->
                // audit 5 E-3: protection, forms, navigation and inherited resources kept (PageExtract); E-5 labels;
                // E-8: a name that never overwrites an earlier part or another file
                com.smartdocs.editor.core.pdf.PageExtract.build(doc, list, password).use { nd ->
                    PageLabelRemap.copyTo(doc, nd, list)
                    val f = com.smartdocs.editor.core.pdf.PdfOps.uniqueIn(outDir, "${base}_${k + 1}"); E5Io.save(nd, f); f
                }
            }
        }
    }
}
