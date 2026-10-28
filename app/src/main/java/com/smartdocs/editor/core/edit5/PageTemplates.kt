package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A page template stored in the document: [visible] = named page (/Names /Pages), else hidden (/Names /Templates). */
data class PageTemplate(val name: String, val visible: Boolean, val pageIndex: Int)

/**
 * Page templates kept inside the PDF (ISO 32000 §12.7.6): visible named pages in the /Pages name tree and hidden
 * templates in the /Templates name tree (page objects outside the page tree, /Type /Template). A template can be
 * instantiated (copied) any number of times at any position, in this app or in Acrobat (JavaScript `spawn`).
 */
object PageTemplates {
    private val PAGES = COSName.getPDFName("Pages")
    private val TEMPLATES = COSName.getPDFName("Templates")
    private val TEMPLATE = COSName.getPDFName("Template")

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    /** All (name, page dictionary) leaves of a name tree. */
    private fun leaves(tree: COSDictionary?): List<Pair<String, COSDictionary>> {
        val out = ArrayList<Pair<String, COSDictionary>>()
        fun walk(n: COSDictionary?, depth: Int) {
            if (n == null || depth > 30) return
            (deref(n.getDictionaryObject(COSName.NAMES)) as? COSArray)?.let { a ->
                for (k in 0 until a.size() - 1 step 2) {
                    val key = (deref(a.getObject(k)) as? COSString)?.string ?: continue
                    (deref(a.getObject(k + 1)) as? COSDictionary)?.let { out += key to it }
                }
            }
            (deref(n.getDictionaryObject(COSName.KIDS)) as? COSArray)?.forEach { walk(deref(it) as? COSDictionary, depth + 1) }
        }
        walk(tree, 0)
        return out
    }

    private fun names(doc: PDDocument, create: Boolean): COSDictionary? {
        val cat = doc.documentCatalog.cosObject
        (deref(cat.getDictionaryObject(COSName.NAMES)) as? COSDictionary)?.let { return it }
        if (!create) return null
        return COSDictionary().also { cat.setItem(COSName.NAMES, it) }
    }

    private fun tree(doc: PDDocument, key: COSName): COSDictionary? = deref(names(doc, false)?.getDictionaryObject(key)) as? COSDictionary

    /** Rewrite a name tree as one flat, sorted /Names array (valid for any size we produce). */
    private fun writeTree(doc: PDDocument, key: COSName, entries: List<Pair<String, COSDictionary>>) {
        val names = names(doc, entries.isNotEmpty()) ?: return
        if (entries.isEmpty()) { names.removeItem(key); return }
        val arr = COSArray()
        entries.sortedBy { it.first }.distinctBy { it.first }.forEach { (n, d) -> arr.add(COSString(n)); arr.add(d) }
        names.setItem(key, COSDictionary().apply { setItem(COSName.NAMES, arr) })
    }

    private fun read(doc: PDDocument): List<PageTemplate> {
        val idx = doc.pages.withIndex().associate { it.value.cosObject to it.index }
        return leaves(tree(doc, PAGES)).map { (n, d) -> PageTemplate(n, true, idx[d] ?: -1) } +
               leaves(tree(doc, TEMPLATES)).map { (n, _) -> PageTemplate(n, false, -1) }
    }

    suspend fun list(src: File, password: String?): List<PageTemplate> = withContext(Dispatchers.IO) { E5Io.load(src, password).use { read(it) } }

    /**
     * Store page [page] as template [name]. [hide] moves the page out of the document into /Templates (it no longer
     * shows or prints); otherwise it stays in place and is registered as a named page.
     */
    suspend fun save(src: File, out: File, password: String?, page: Int, name: String, hide: Boolean) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val p = doc.getPage(page)
            val clean = name.trim().ifEmpty { "Template ${page + 1}" }
            val visible = leaves(tree(doc, PAGES)).filter { it.first != clean }.toMutableList()
            val hidden = leaves(tree(doc, TEMPLATES)).filter { it.first != clean }.toMutableList()
            if (hide) {
                if (doc.numberOfPages <= 1) throw IllegalStateException("last page")
                val d = PageOps5.clonePage(p).cosObject     // detached copy with explicit inherited attributes
                d.setItem(COSName.TYPE, TEMPLATE)
                hidden += clean to d
                visible.removeAll { it.second == p.cosObject }
                PageLabelRemap.keep(doc) { doc.pages.remove(p) }   // audit 5 E-5
            } else visible += clean to p.cosObject
            writeTree(doc, PAGES, visible); writeTree(doc, TEMPLATES, hidden)
        }
    }

    /** Insert [count] copies of template [name] at [at]. */
    suspend fun instantiate(src: File, out: File, password: String?, name: String, at: Int, count: Int = 1) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val d = (leaves(tree(doc, TEMPLATES)) + leaves(tree(doc, PAGES))).firstOrNull { it.first == name }?.second ?: throw IllegalArgumentException(name)
            var pos = at.coerceIn(0, doc.numberOfPages)
            PageLabelRemap.keep(doc) {   // audit 5 E-5
                repeat(count.coerceIn(1, 500)) {
                    val copy = PageOps5.clonePage(PDPage(d))
                    copy.cosObject.setItem(COSName.TYPE, COSName.PAGE)
                    PageOps5.insertAt(doc, copy, pos++)
                }
            }
        }
    }

    /** Remove template [name]; a hidden template's page is dropped, a named page stays as an ordinary page. */
    suspend fun delete(src: File, out: File, password: String?, name: String) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            writeTree(doc, PAGES, leaves(tree(doc, PAGES)).filter { it.first != name })
            writeTree(doc, TEMPLATES, leaves(tree(doc, TEMPLATES)).filter { it.first != name })
        }
    }

    /** Toggle: show a hidden template as a page at the end, or hide a named page. */
    suspend fun setVisible(src: File, out: File, password: String?, name: String, visible: Boolean) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val vis = leaves(tree(doc, PAGES)).toMutableList(); val hid = leaves(tree(doc, TEMPLATES)).toMutableList()
            if (visible) {
                val e = hid.firstOrNull { it.first == name } ?: return@edit
                hid.remove(e)
                val page = PDPage(e.second); page.cosObject.setItem(COSName.TYPE, COSName.PAGE)
                PageLabelRemap.keep(doc) { doc.pages.add(page) }; vis += name to page.cosObject
            } else {
                val e = vis.firstOrNull { it.first == name } ?: return@edit
                if (doc.numberOfPages <= 1) throw IllegalStateException("last page")
                vis.remove(e)
                val page = PDPage(e.second)
                val d = PageOps5.clonePage(page).cosObject; d.setItem(COSName.TYPE, TEMPLATE)
                hid += name to d
                PageLabelRemap.keep(doc) { doc.pages.remove(page) }
            }
            writeTree(doc, PAGES, vis); writeTree(doc, TEMPLATES, hid)
        }
    }

    /** Rename template [old] to [new] (kept visible or hidden); an existing template called [new] is replaced. */
    suspend fun rename(src: File, out: File, password: String?, old: String, new: String) = withContext(Dispatchers.IO) {
        val clean = new.trim()
        require(clean.isNotEmpty()) { "empty name" }
        E5Io.edit(src, out, password) { doc ->
            fun ren(l: List<Pair<String, COSDictionary>>) = l.filter { it.first != clean || clean == old }.map { if (it.first == old) clean to it.second else it }
            writeTree(doc, PAGES, ren(leaves(tree(doc, PAGES))))
            writeTree(doc, TEMPLATES, ren(leaves(tree(doc, TEMPLATES))))
        }
    }

    /**
     * Replace template [name] with page [page]: a hidden template gets a detached copy of the page, a named
     * (visible) template now names that page.
     */
    suspend fun replaceWithPage(src: File, out: File, password: String?, name: String, page: Int) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val p = doc.getPage(page)
            val vis = leaves(tree(doc, PAGES)).toMutableList(); val hid = leaves(tree(doc, TEMPLATES)).toMutableList()
            val vi = vis.indexOfFirst { it.first == name }
            val hi = hid.indexOfFirst { it.first == name }
            when {
                vi >= 0 -> vis[vi] = name to p.cosObject
                hi >= 0 -> {
                    val d = PageOps5.clonePage(p).cosObject; d.setItem(COSName.TYPE, TEMPLATE)
                    hid[hi] = name to d
                }
                else -> throw IllegalArgumentException(name)
            }
            writeTree(doc, PAGES, vis); writeTree(doc, TEMPLATES, hid)
        }
    }
}
