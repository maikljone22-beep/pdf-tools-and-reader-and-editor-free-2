package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.pdf.PageExtract
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.util.IdentityHashMap

/**
 * audit 5 E-4 / E-6: pages of another document brought into [PDDocument]s that are edited in place (copy pages,
 * interleave, replace, insert file, insert into files, mix). Unlike a bare `importPage`:
 * - the inherited /Resources come along (PdfBox copies the boxes and /Rotate, not inherited resources),
 * - every page gets its own annotation copies (/P, /Popup, /IRT re-pointed onto the copies),
 * - internal links (GoTo, named destinations) that jump to an imported page are re-pointed onto its copy; links to
 *   pages that were not imported are dropped (they would lead into the other file),
 * - form widgets are merged into the target's AcroForm (field names made unique); widgets whose field cannot be
 *   found are dropped, a signature value is cleared (it cannot be valid in another file).
 */
object PageImport {
    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
    private fun shallow(d: COSDictionary): COSDictionary { val c = COSDictionary(); for ((k, v) in d.entrySet()) c.setItem(k, v); return c }
    private val POPUP = COSName.getPDFName("Popup")
    private val IRT = COSName.getPDFName("IRT")

    /**
     * Copies of pages [indexes] (zero-based, in this order; repeats allowed) of [src] for [target]. They are NOT in
     * the page tree of [target]: the caller inserts them (insertBefore / add) in the returned order.
     */
    fun importPages(target: PDDocument, src: PDDocument, indexes: List<Int>): List<PDPage> {
        val pairs = ArrayList<Pair<PDPage, PDPage>>()
        val firstCopy = IdentityHashMap<COSDictionary, PDPage>()
        for (i in indexes) {
            if (i !in 0 until src.numberOfPages) continue
            val sp = src.getPage(i)
            val np = target.importPage(sp)
            target.pages.remove(np)
            val d = np.cosObject
            if (!d.containsKey(COSName.RESOURCES)) PageExtract.inherited(sp.cosObject, COSName.RESOURCES)?.let { d.setItem(COSName.RESOURCES, it) }
            d.removeItem(COSName.STRUCT_PARENTS); d.removeItem(COSName.B)
            if (!firstCopy.containsKey(sp.cosObject)) firstCopy[sp.cosObject] = np
            pairs += sp to np
        }
        // own annotation copies
        val annotMap = IdentityHashMap<COSDictionary, COSDictionary>()
        for ((sp, np) in pairs) {
            val annots = deref(sp.cosObject.getDictionaryObject(COSName.ANNOTS)) as? COSArray
            if (annots == null) { np.cosObject.removeItem(COSName.ANNOTS); continue }
            val first = firstCopy[sp.cosObject] === np
            val out = COSArray()
            for (k in 0 until annots.size()) {
                val a = deref(annots.get(k)) as? COSDictionary ?: continue
                val sub = a.getNameAsString(COSName.SUBTYPE)
                // a repeated page gets no second copy of the widgets (one widget belongs to one field and one page)
                if (!first && (sub == "Widget" || sub == "Popup")) continue
                val c = shallow(a)
                c.removeItem(COSName.STRUCT_PARENT)
                c.setItem(COSName.P, np.cosObject)
                if (first) annotMap[a] = c else { c.removeItem(POPUP); c.removeItem(IRT) }
                out.add(c)
            }
            np.cosObject.setItem(COSName.ANNOTS, out)
        }
        for (c in annotMap.values) {
            for (key in listOf(POPUP, COSName.PARENT, IRT)) {
                if (key == COSName.PARENT && c.getNameAsString(COSName.SUBTYPE) == "Widget") continue   // a widget's /Parent is its field
                val ref = deref(c.getDictionaryObject(key)) as? COSDictionary ?: continue
                val mapped = annotMap[ref]
                if (mapped != null) c.setItem(key, mapped) else c.removeItem(key)
            }
        }
        // internal links onto the copies (or dropped)
        for ((_, np) in pairs) {
            val annots = np.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            val keep = COSArray()
            for (k in 0 until annots.size()) {
                val a = annots.getObject(k) as? COSDictionary ?: continue
                if (a.getNameAsString(COSName.SUBTYPE) == "Link") {
                    val target0 = runCatching { PageOrganize.itemPage(src, a) }.getOrNull()
                    if (target0 != null) {
                        val mapped = firstCopy[target0] ?: continue
                        val old = (deref(a.getDictionaryObject(COSName.DEST)) as? COSArray)
                            ?: ((deref(a.getDictionaryObject(COSName.A)) as? COSDictionary)?.let { deref(it.getDictionaryObject(COSName.D)) } as? COSArray)
                        val dest = COSArray(); dest.add(mapped.cosObject)
                        if (old != null && old.size() > 1) for (j in 1 until old.size()) dest.add(old.get(j)) else dest.add(COSName.getPDFName("Fit"))
                        a.removeItem(COSName.A); a.setItem(COSName.DEST, dest)
                    }
                }
                keep.add(a)
            }
            np.cosObject.setItem(COSName.ANNOTS, keep)
        }
        mergeFields(target, src, annotMap, pairs.map { it.second })
        return pairs.map { it.second }
    }

    /** The field branches of [src] whose widgets were copied go into the AcroForm of [target]. */
    private fun mergeFields(target: PDDocument, src: PDDocument, annotMap: IdentityHashMap<COSDictionary, COSDictionary>, pages: List<PDPage>) {
        val widgetCopies = annotMap.values.filter { it.getNameAsString(COSName.SUBTYPE) == "Widget" }
        if (widgetCopies.isEmpty()) return
        val placed = IdentityHashMap<COSDictionary, Boolean>()
        val roots = ArrayList<COSDictionary>()
        val sAcro = deref(src.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM)) as? COSDictionary
        if (sAcro != null) {
            val fields = deref(sAcro.getDictionaryObject(COSName.FIELDS)) as? COSArray ?: COSArray()
            val visiting = IdentityHashMap<COSDictionary, Boolean>()
            fun clone(node: COSDictionary, depth: Int): COSDictionary? {
                if (depth > 32 || visiting.put(node, true) != null) return null
                try {
                    val widget = annotMap[node]?.takeIf { it.getNameAsString(COSName.SUBTYPE) == "Widget" }
                    val kids = deref(node.getDictionaryObject(COSName.KIDS)) as? COSArray
                    val clonedKids = ArrayList<COSDictionary>()
                    if (kids != null) for (k in 0 until kids.size()) (deref(kids.get(k)) as? COSDictionary)?.let { kd -> clone(kd, depth + 1)?.let { clonedKids += it } }
                    if (widget == null && clonedKids.isEmpty()) return null
                    val c = widget ?: shallow(node)
                    if (widget != null) placed[widget] = true
                    c.removeItem(COSName.PARENT)
                    if (kids != null) {
                        if (clonedKids.isEmpty()) c.removeItem(COSName.KIDS)
                        else { val a = COSArray(); clonedKids.forEach { k -> k.setItem(COSName.PARENT, c); a.add(k) }; c.setItem(COSName.KIDS, a) }
                    }
                    if (c.getNameAsString(COSName.FT) == "Sig") c.removeItem(COSName.V)
                    return c
                } finally { visiting.remove(node) }
            }
            for (k in 0 until fields.size()) (deref(fields.get(k)) as? COSDictionary)?.let { f -> clone(f, 0)?.let { roots += it } }
        }
        // widgets without a field in the source form would be dead in the target: drop them
        if (placed.size != widgetCopies.size) for (p in pages) {
            val annots = p.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            val keep = COSArray()
            for (k in 0 until annots.size()) {
                val a = annots.getObject(k) as? COSDictionary ?: continue
                if (a.getNameAsString(COSName.SUBTYPE) == "Widget" && !placed.containsKey(a)) continue
                keep.add(a)
            }
            p.cosObject.setItem(COSName.ANNOTS, keep)
        }
        if (roots.isEmpty() || sAcro == null) return
        val cat = target.documentCatalog.cosObject
        val tAcro = deref(cat.getDictionaryObject(COSName.ACRO_FORM)) as? COSDictionary ?: COSDictionary().also { d ->
            for (key in listOf(COSName.DA, COSName.Q, COSName.getPDFName("NeedAppearances"))) sAcro.getDictionaryObject(key)?.let { d.setItem(key, it) }
            cat.setItem(COSName.ACRO_FORM, d)
        }
        // default resources: the fonts the imported fields name must exist in the target
        val sDr = deref(sAcro.getDictionaryObject(COSName.DR)) as? COSDictionary
        if (sDr != null) {
            val tDr = deref(tAcro.getDictionaryObject(COSName.DR)) as? COSDictionary
            if (tDr == null) tAcro.setItem(COSName.DR, sDr)
            else {
                val sFonts = deref(sDr.getDictionaryObject(COSName.FONT)) as? COSDictionary
                if (sFonts != null) {
                    val tFonts = deref(tDr.getDictionaryObject(COSName.FONT)) as? COSDictionary ?: COSDictionary().also { tDr.setItem(COSName.FONT, it) }
                    for ((k, v) in sFonts.entrySet()) if (!tFonts.containsKey(k)) tFonts.setItem(k, v)
                }
            }
        }
        if (!tAcro.containsKey(COSName.DA)) sAcro.getDictionaryObject(COSName.DA)?.let { tAcro.setItem(COSName.DA, it) }
        val tFields = deref(tAcro.getDictionaryObject(COSName.FIELDS)) as? COSArray ?: COSArray().also { tAcro.setItem(COSName.FIELDS, it) }
        val names = HashSet<String>()
        for (k in 0 until tFields.size()) (deref(tFields.get(k)) as? COSDictionary)?.getString(COSName.T)?.let { names += it }
        for (r in roots) {
            r.getString(COSName.T)?.let { t ->
                var name = t; var n = 2
                while (name in names) name = "${t}_${n++}"
                if (name != t) r.setString(COSName.T, name)
                names += name
            }
            tFields.add(r)
        }
    }

    /**
     * audit 5 E-3: [target] (edited in place, keeps its own protection) receives pages of the encrypted [source]: an
     * unencrypted target is protected like the source, so the source's restrictions are not lost.
     */
    fun protectForImport(target: PDDocument, source: PDDocument, sourcePassword: String?) {
        if (!target.isEncrypted && source.isEncrypted) PdfOps.protectLike(target, source, sourcePassword)
    }
}

/** audit 5 E-7: form fields whose widgets were all on removed pages leave the AcroForm. */
object FormPrune {
    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    /** The widget annotations on [pages] (to pass to [prune] after the pages were removed). */
    fun widgetsOn(pages: List<PDPage>): Set<COSDictionary> {
        val out = java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        for (p in pages) {
            val annots = deref(p.cosObject.getDictionaryObject(COSName.ANNOTS)) as? COSArray ?: continue
            for (k in 0 until annots.size()) (deref(annots.get(k)) as? COSDictionary)?.takeIf { it.getNameAsString(COSName.SUBTYPE) == "Widget" }?.let { out += it }
        }
        return out
    }

    /** Removes the widgets [gone] from the field tree of [doc], and every field left without widgets. Returns how many nodes went. */
    fun prune(doc: PDDocument, gone: Set<COSDictionary>): Int {
        if (gone.isEmpty()) return 0
        val acro = deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM)) as? COSDictionary ?: return 0
        val fields = deref(acro.getDictionaryObject(COSName.FIELDS)) as? COSArray ?: return 0
        val removed = java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        /** True when [node] must go. */
        fun walk(node: COSDictionary, depth: Int): Boolean {
            if (node in gone) return true
            if (depth > 32) return false
            val kids = deref(node.getDictionaryObject(COSName.KIDS)) as? COSArray ?: return false
            val keep = COSArray()
            for (k in 0 until kids.size()) {
                val kd = deref(kids.get(k)) as? COSDictionary
                if (kd != null && walk(kd, depth + 1)) removed += kd else keep.add(kids.get(k))
            }
            if (keep.size() == kids.size()) return false
            if (keep.size() == 0) return true
            node.setItem(COSName.KIDS, keep)
            return false
        }
        val keep = COSArray()
        for (k in 0 until fields.size()) {
            val f = deref(fields.get(k)) as? COSDictionary
            if (f != null && walk(f, 0)) removed += f else keep.add(fields.get(k))
        }
        if (removed.isEmpty()) return 0
        acro.setItem(COSName.FIELDS, keep)
        (deref(acro.getDictionaryObject(COSName.getPDFName("CO"))) as? COSArray)?.let { co ->
            val c2 = COSArray()
            for (k in 0 until co.size()) { val d = deref(co.get(k)) as? COSDictionary; if (d == null || d !in removed) c2.add(co.get(k)) }
            acro.setItem(COSName.getPDFName("CO"), c2)
        }
        return removed.size
    }
}
