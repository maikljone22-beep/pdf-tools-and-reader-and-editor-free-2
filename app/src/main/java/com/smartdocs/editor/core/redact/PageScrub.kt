package com.smartdocs.editor.core.redact

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.util.IdentityHashMap

/**
 * Page-level clean-up of redaction that leaves NO reachable copy of the redacted content (pure PdfBox, unit-tested):
 *  - audit 5 R-1: [replacePage] swaps a page for its rasterised copy. Every reference to the old page (StructTree
 *    /Pg, annotation /P, outline / link / named / catalog destinations, GoTo /D, OpenAction, thread beads) is
 *    re-pointed to the new page, the old page's annotations and their form fields are dropped, and the old page
 *    object is emptied, so neither it nor its content streams and resources (form XObjects with the text) are written.
 *  - audit 5 R-3: [wipePage] empties a page AND removes the form fields of its widgets (their /V, /DV, appearances)
 *    and /ActualText /Alt /E of the structure elements on that page.
 *  - audit 5 R-4: [stripSignatures] removes the signature values of a rewritten (no longer validly signed) output.
 */
object PageScrub {
    private val ACTUAL_TEXT = COSName.getPDFName("ActualText")
    private val ALT = COSName.getPDFName("Alt")
    private val E = COSName.getPDFName("E")
    private val TEXT_KEYS = listOf(ACTUAL_TEXT, ALT, E)
    private val MCR = COSName.getPDFName("MCR")
    private val OBJR = COSName.getPDFName("OBJR")
    private val CO = COSName.getPDFName("CO")
    private val RV = COSName.getPDFName("RV")
    private val SIG_FLAGS = COSName.getPDFName("SigFlags")
    private val THUMB = COSName.getPDFName("Thumb")
    private val PIECE = COSName.getPDFName("PieceInfo")
    private val STRUCT_PARENTS = COSName.getPDFName("StructParents")
    private val BEADS = COSName.getPDFName("B")
    private val SEPARATION_INFO = COSName.getPDFName("SeparationInfo")

    private fun <T> identitySet(): MutableSet<T> = java.util.Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    /** The annotation dictionaries listed in [page]'s /Annots (identity set). */
    fun annotDicts(page: COSDictionary): MutableSet<COSDictionary> {
        val out = identitySet<COSDictionary>()
        val arr = page.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return out
        for (i in 0 until arr.size()) (arr.getObject(i) as? COSDictionary)?.let { out += it }
        return out
    }

    /**
     * audit 5 R-1: puts [newPage] where [oldPage] is and makes the old page unreachable. Returns how many
     * references were re-pointed or dropped.
     */
    fun replacePage(doc: PDDocument, oldPage: PDPage, newPage: PDPage): Int {
        val od = oldPage.cosObject
        val annots = annotDicts(od)
        doc.pages.insertAfter(newPage, oldPage)
        doc.removePage(oldPage)   // needs the old page's /Parent: emptied only afterwards
        stripPageStructure(doc, od, dropContent = true)
        dropFields(doc, annots)
        val n = redirect(doc, od, newPage.cosObject, annots)
        clearDict(od); od.setItem(COSName.TYPE, COSName.PAGE)
        annots.forEach(::clearDict)
        return n
    }

    /**
     * audit 5 R-3: whole-page wipe. No content, no resources, no annotations; the fields of its widgets are removed
     * from the form (values with them), and the structure elements on the page lose their text. Returns the number
     * of form fields removed.
     */
    fun wipePage(doc: PDDocument, page: PDPage): Int {
        val pd = page.cosObject
        val annots = annotDicts(pd)
        page.setContents(PDStream(doc))
        page.resources = PDResources()
        page.annotations = emptyList()
        listOf(THUMB, PIECE, STRUCT_PARENTS, COSName.METADATA, BEADS, SEPARATION_INFO).forEach(pd::removeItem)
        stripPageStructure(doc, pd, dropContent = true)
        val n = dropFields(doc, annots)
        redirect(doc, null, null, annots)
        annots.forEach(::clearDict)
        return n
    }

    private fun clearDict(d: COSDictionary) { for (k in d.keySet().toList()) d.removeItem(k) }

    /**
     * Removes /ActualText /Alt /E from every structure element on [page] (own or inherited /Pg, or content kids
     * on the page) and from its ancestors that summarise that content. With [dropContent], the marked-content
     * (MCID / MCR) and object-reference (OBJR) kids on that page are removed too (the content is gone).
     * Returns how many elements lost text.
     */
    fun stripPageStructure(doc: PDDocument, page: COSDictionary, dropContent: Boolean): Int {
        val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT) as? COSDictionary ?: return 0
        var n = 0
        val seen = identitySet<COSDictionary>()
        fun onPage(d: COSDictionary, pg: COSDictionary?) = ((d.getDictionaryObject(COSName.PG) as? COSDictionary) ?: pg) === page

        fun walk(el: COSDictionary, inheritedPg: COSDictionary?, depth: Int): Boolean {
            if (depth > 256 || !seen.add(el)) return false
            val pg = (el.getDictionaryObject(COSName.PG) as? COSDictionary) ?: inheritedPg
            var hit = el !== root && pg === page
            // true when the kid is content on the page
            fun kidOnPage(kid: COSBase?): Boolean = when (val v = deref(kid)) {
                is COSInteger -> pg === page
                is COSDictionary -> {
                    val type = v.getCOSName(COSName.TYPE)
                    if (type == MCR || type == OBJR) onPage(v, pg)
                    else { if (walk(v, pg, depth + 1)) hit = true; false }
                }
                else -> false
            }
            when (val k = el.getDictionaryObject(COSName.K)) {
                is COSArray -> {
                    var i = k.size() - 1
                    while (i >= 0) {
                        if (kidOnPage(k.get(i))) { hit = true; if (dropContent) k.remove(i) }
                        i--
                    }
                }
                null -> {}
                else -> if (kidOnPage(k)) { hit = true; if (dropContent) el.removeItem(COSName.K) }
            }
            if (hit && el !== root && TEXT_KEYS.any(el::containsKey)) { TEXT_KEYS.forEach(el::removeItem); n++ }
            return hit
        }
        walk(root, null, 0)
        return n
    }

    /**
     * Removes from the form every widget in [widgets] and every field left without widgets (their /V, /DV, /RV and
     * appearances are cleared too). Returns how many field / widget dictionaries were removed.
     */
    fun dropFields(doc: PDDocument, widgets: Set<COSDictionary>): Int {
        if (widgets.isEmpty()) return 0
        val form = doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary ?: return 0
        val fields = form.getDictionaryObject(COSName.FIELDS) as? COSArray ?: return 0
        val removed = identitySet<COSDictionary>()
        fun prune(arr: COSArray, depth: Int) {
            var i = arr.size() - 1
            while (i >= 0) {
                val d = arr.getObject(i) as? COSDictionary
                if (d != null) {
                    val gone = if (d in widgets) true else {
                        val kids = d.getDictionaryObject(COSName.KIDS) as? COSArray
                        if (kids != null && kids.size() > 0 && depth < 64) { prune(kids, depth + 1); kids.size() == 0 } else false
                    }
                    if (gone) {
                        arr.remove(i)
                        listOf(COSName.V, COSName.DV, RV, COSName.AP).forEach { d.removeItem(it) }
                        removed += d
                    }
                }
                i--
            }
        }
        prune(fields, 0)
        (form.getDictionaryObject(CO) as? COSArray)?.let { co ->
            var i = co.size() - 1
            while (i >= 0) { if (co.getObject(i).let { it is COSDictionary && it in removed }) co.remove(i); i-- }
        }
        return removed.size
    }

    /**
     * Walks everything reachable from the trailer: references to [old] become [replacement] (or are removed when it
     * is null), references to a dictionary in [drop] are removed. [old] and [drop] themselves are not entered.
     * Returns how many references changed.
     */
    fun redirect(doc: PDDocument, old: COSDictionary?, replacement: COSDictionary?, drop: Set<COSDictionary>): Int {
        var n = 0
        val seen = identitySet<COSBase>()
        val stack = ArrayDeque<COSBase>()
        stack.addLast(doc.document.trailer)
        fun isOld(t: COSBase?) = old != null && t === old
        fun isDropped(t: COSBase?) = t is COSDictionary && t in drop
        while (stack.isNotEmpty()) {
            val b = stack.removeLast()
            if (!seen.add(b)) continue
            when (b) {
                is COSDictionary -> {
                    if (b === old || b in drop) continue
                    for (k in b.keySet().toList()) {
                        val t = deref(b.getItem(k))
                        when {
                            isOld(t) -> { if (replacement != null) b.setItem(k, replacement) else b.removeItem(k); n++ }
                            isDropped(t) -> { b.removeItem(k); n++ }
                            t is COSDictionary || t is COSArray -> stack.addLast(t)
                            else -> {}
                        }
                    }
                }
                is COSArray -> {
                    var i = b.size() - 1
                    while (i >= 0) {
                        val t = deref(b.get(i))
                        when {
                            isOld(t) -> { if (replacement != null) b.set(i, replacement) else b.remove(i); n++ }
                            isDropped(t) -> { b.remove(i); n++ }
                            t is COSDictionary || t is COSArray -> stack.addLast(t)
                            else -> {}
                        }
                        i--
                    }
                }
                else -> {}
            }
        }
        return n
    }

    /**
     * audit 5 R-4: the output of a full rewrite is no longer validly signed, so it must not claim to be signed and
     * altered: the values (/V) of signature fields, the catalog /Perms (DocMDP / UR3 point at them) and /SigFlags
     * are removed. The visible appearance stays (it is page content). Returns how many signatures were removed.
     */
    fun stripSignatures(doc: PDDocument): Int {
        val cat = doc.documentCatalog.cosObject
        val form = cat.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary ?: return 0
        val fields = form.getDictionaryObject(COSName.FIELDS) as? COSArray ?: return 0
        var n = 0
        val seen = identitySet<COSDictionary>()
        fun walk(arr: COSArray, inheritedFt: COSName?, depth: Int) {
            if (depth > 64) return
            for (i in 0 until arr.size()) {
                val d = arr.getObject(i) as? COSDictionary ?: continue
                if (!seen.add(d)) continue
                val ft = d.getCOSName(COSName.FT) ?: inheritedFt
                if (ft == COSName.SIG && d.containsKey(COSName.V)) { d.removeItem(COSName.V); n++ }
                (d.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { walk(it, ft, depth + 1) }
            }
        }
        walk(fields, null, 0)
        if (n > 0) { cat.removeItem(COSName.PERMS); form.removeItem(SIG_FLAGS) }
        return n
    }
}
