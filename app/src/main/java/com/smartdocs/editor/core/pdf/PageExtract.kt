package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage

/**
 * Builds a new document from some pages of an open source document (audit 5 T-05 / G-03 / E-3 / E-6): the one
 * implementation behind extract, "share / print pages", split and the e5 part writers.
 *
 * Unlike a bare `importPage` loop the output
 * - keeps the source's protection ([PdfOps.protectLike]: same cipher, key length and permission bits),
 * - gets the inherited /Resources /MediaBox /CropBox /Rotate of each page (PdfBox does not import inherited resources),
 * - has its own copies of the page annotations (/P, /Popup, /Parent, /IRT re-pointed onto the copies),
 * - keeps the form fields whose widgets are on the kept pages (an AcroForm with only those branches; signature
 *   values are dropped, a signature cannot survive a new document),
 * - keeps the bookmarks and internal links that point at kept pages (links to dropped pages are removed),
 * - keeps the document information.
 * The source document is not changed (only shallow copies are edited).
 */
object PageExtract {

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    private val INHERITABLE = listOf(COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE)

    /** The value of an inheritable [key] of [page], from the page itself or the nearest /Pages ancestor. */
    fun inherited(page: COSDictionary, key: COSName): COSBase? {
        page.getDictionaryObject(key)?.let { return it }
        var node = page.getDictionaryObject(COSName.PARENT) as? COSDictionary
        var depth = 0
        while (node != null && depth++ < 64) {
            node.getDictionaryObject(key)?.let { return it }
            node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        return null
    }

    /** Copy the inherited attributes of every page of [doc] onto the page itself (before pages are re-parented). */
    fun pushDownInherited(doc: PDDocument) {
        for (p in doc.pages) pushDown(p.cosObject)
    }

    fun pushDown(page: COSDictionary) {
        for (key in INHERITABLE) if (!page.containsKey(key)) inherited(page, key)?.let { page.setItem(key, it) }
    }

    /**
     * New document of [pages] (zero-based, in this order; repeats allowed) of [src], which was opened with [password].
     * [protect] = keep the source's protection (default); false only when the caller checked owner access and the user
     * asked for an unprotected output.
     */
    fun build(src: PDDocument, pages: List<Int>, password: String?, keepNavigation: Boolean = true, keepForms: Boolean = true,
              protect: Boolean = true): PDDocument {
        val nd = PDDocument()
        try {
            val map = LinkedHashMap<COSDictionary, PDPage>()          // source page -> its (first) copy
            val annotMap = HashMap<COSDictionary, COSDictionary>()     // source annotation -> copy
            val copies = ArrayList<Pair<PDPage, PDPage>>()
            for (i in pages) {
                if (i !in 0 until src.numberOfPages) continue
                val sp = src.getPage(i)
                val np = nd.importPage(sp)
                // PdfBox does not import inherited resources ("inherited resources of source document are not imported")
                if (!np.cosObject.containsKey(COSName.RESOURCES)) inherited(sp.cosObject, COSName.RESOURCES)?.let { np.cosObject.setItem(COSName.RESOURCES, it) }
                np.cosObject.removeItem(COSName.STRUCT_PARENTS); np.cosObject.removeItem(COSName.B)
                if (!map.containsKey(sp.cosObject)) map[sp.cosObject] = np
                copies += sp to np
            }
            // own annotation copies per page
            val inside = HashSet<COSDictionary>()
            for ((sp, np) in copies) {
                val annots = deref(sp.cosObject.getDictionaryObject(COSName.ANNOTS)) as? COSArray
                if (annots == null) { np.cosObject.removeItem(COSName.ANNOTS); continue }
                val out = COSArray()
                val firstCopy = map[sp.cosObject] === np
                for (k in 0 until annots.size()) {
                    val a = deref(annots.get(k)) as? COSDictionary ?: continue
                    val sub = a.getNameAsString(COSName.SUBTYPE)
                    // a repeated page gets no second copy of the form widgets (a widget belongs to one field / page)
                    if (!firstCopy && sub == "Widget") continue
                    val c = shallow(a)
                    c.removeItem(COSName.STRUCT_PARENT)
                    c.setItem(COSName.P, np.cosObject)
                    if (firstCopy) annotMap[a] = c
                    out.add(c)
                }
                np.cosObject.setItem(COSName.ANNOTS, out)
                inside += np.cosObject
            }
            // re-point popup / parent / reply references onto the copies
            for (c in annotMap.values) {
                for (key in listOf(COSName.getPDFName("Popup"), COSName.PARENT, COSName.getPDFName("IRT"))) {
                    val ref = deref(c.getDictionaryObject(key)) as? COSDictionary ?: continue
                    val sub = c.getNameAsString(COSName.SUBTYPE)
                    if (key == COSName.PARENT && sub == "Widget") continue   // a widget's /Parent is its field (below)
                    val mapped = annotMap[ref]
                    if (mapped != null) c.setItem(key, mapped) else c.removeItem(key)
                }
            }
            if (keepNavigation) {
                for (np in map.values) {
                    val annots = np.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                    val keep = COSArray()
                    for (k in 0 until annots.size()) {
                        val a = annots.getObject(k) as? COSDictionary ?: continue
                        if (a.getNameAsString(COSName.SUBTYPE) == "Link") {
                            val target = runCatching { com.smartdocs.editor.core.edit5.PageOrganize.itemPage(src, a) }.getOrNull()
                            if (target != null) {
                                val np2 = map[target] ?: continue      // jumps to a page that is not in the output
                                val d = COSArray(); d.add(np2.cosObject); d.add(COSName.getPDFName("Fit"))
                                a.removeItem(COSName.A); a.setItem(COSName.DEST, d)
                            }
                        }
                        keep.add(a)
                    }
                    np.cosObject.setItem(COSName.ANNOTS, keep)
                }
                runCatching { com.smartdocs.editor.core.edit5.PageOrganize.copyOutline(src, nd, map) }
            } else {
                // without navigation: internal links would point into the source document
                for (np in map.values) {
                    val annots = np.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                    val keep = COSArray()
                    for (k in 0 until annots.size()) {
                        val a = annots.getObject(k) as? COSDictionary ?: continue
                        if (a.getNameAsString(COSName.SUBTYPE) == "Link" && runCatching { com.smartdocs.editor.core.edit5.PageOrganize.itemPage(src, a) }.getOrNull() != null) continue
                        keep.add(a)
                    }
                    np.cosObject.setItem(COSName.ANNOTS, keep)
                }
            }
            if (keepForms) copyFields(src, nd, annotMap)
            else for (np in nd.pages) {
                // no form in the output: widgets would be orphans
                val annots = np.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                val keep = COSArray()
                for (k in 0 until annots.size()) { val a = annots.getObject(k) as? COSDictionary ?: continue; if (a.getNameAsString(COSName.SUBTYPE) != "Widget") keep.add(a) }
                np.cosObject.setItem(COSName.ANNOTS, keep)
            }
            nd.documentInformation = src.documentInformation
            if (protect) PdfOps.protectLike(nd, src, password)
            return nd
        } catch (t: Throwable) {
            runCatching { nd.close() }; throw t
        }
    }

    private fun shallow(d: COSDictionary): COSDictionary { val c = COSDictionary(); for ((k, v) in d.entrySet()) c.setItem(k, v); return c }

    /** An AcroForm in [nd] with the field branches of [src] whose widgets were copied ([annotMap] source → copy). */
    private fun copyFields(src: PDDocument, nd: PDDocument, annotMap: Map<COSDictionary, COSDictionary>) {
        val sAcro = deref(src.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM)) as? COSDictionary
        val widgetCopies = annotMap.filter { (_, c) -> c.getNameAsString(COSName.SUBTYPE) == "Widget" }
        if (sAcro == null) {
            // widgets without a form: drop them (they would be dead)
            if (widgetCopies.isNotEmpty()) dropWidgets(nd)
            return
        }
        val fields = deref(sAcro.getDictionaryObject(COSName.FIELDS)) as? COSArray ?: COSArray()
        val visiting = HashSet<COSDictionary>()
        fun clone(node: COSDictionary, depth: Int): COSDictionary? {
            if (depth > 32 || !visiting.add(node)) return null
            try {
                val widget = annotMap[node]?.takeIf { it.getNameAsString(COSName.SUBTYPE) == "Widget" }
                val kids = deref(node.getDictionaryObject(COSName.KIDS)) as? COSArray
                val clonedKids = ArrayList<COSDictionary>()
                if (kids != null) for (k in 0 until kids.size()) (deref(kids.get(k)) as? COSDictionary)?.let { kd -> clone(kd, depth + 1)?.let { clonedKids += it } }
                if (widget == null && clonedKids.isEmpty()) return null
                val c = widget ?: shallow(node)
                c.removeItem(COSName.PARENT)
                if (kids != null) {
                    if (clonedKids.isEmpty()) c.removeItem(COSName.KIDS)
                    else { val a = COSArray(); clonedKids.forEach { k -> k.setItem(COSName.PARENT, c); a.add(k) }; c.setItem(COSName.KIDS, a) }
                }
                // a signature value cannot be valid in a new document
                if (c.getNameAsString(COSName.FT) == "Sig") c.removeItem(COSName.V)
                return c
            } finally { visiting.remove(node) }
        }
        val roots = COSArray()
        for (k in 0 until fields.size()) (deref(fields.get(k)) as? COSDictionary)?.let { f -> clone(f, 0)?.let { roots.add(it) } }
        // widgets whose field could not be found in /Fields: keep them as they are (annotation only)
        if (roots.size() == 0) { if (widgetCopies.isNotEmpty()) dropWidgetParents(nd); return }
        val acro = COSDictionary()
        acro.setItem(COSName.FIELDS, roots)
        for (key in listOf(COSName.DA, COSName.DR, COSName.Q, COSName.getPDFName("NeedAppearances"))) sAcro.getDictionaryObject(key)?.let { acro.setItem(key, it) }
        nd.documentCatalog.cosObject.setItem(COSName.ACRO_FORM, acro)
    }

    private fun dropWidgets(nd: PDDocument) {
        for (np in nd.pages) {
            val annots = np.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            val keep = COSArray()
            for (k in 0 until annots.size()) { val a = annots.getObject(k) as? COSDictionary ?: continue; if (a.getNameAsString(COSName.SUBTYPE) != "Widget") keep.add(a) }
            np.cosObject.setItem(COSName.ANNOTS, keep)
        }
    }

    private fun dropWidgetParents(nd: PDDocument) {
        for (np in nd.pages) {
            val annots = np.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            for (k in 0 until annots.size()) { val a = annots.getObject(k) as? COSDictionary ?: continue; if (a.getNameAsString(COSName.SUBTYPE) == "Widget") a.removeItem(COSName.PARENT) }
        }
    }
}
