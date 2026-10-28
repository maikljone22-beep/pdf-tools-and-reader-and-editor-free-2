package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * FINAL_CHECK (Foxit 34.7 "Fix" for every rule): structure fixes for the checker rules that [AccessFix] does not
 * cover — nested alt text, alt text without content or hiding annotations, formulas without alt, rows / cells / list
 * items outside their parents, a header row, table summaries, heading levels. Same rules as [AccessInspect] (role map
 * applied). Each returns how many places it changed. Pure over [PDDocument] — JVM-tested.
 */
object AccessFix2 {
    private val S = COSName.S; private val K = COSName.K; private val P = COSName.P
    private val ALT = COSName.getPDFName("Alt"); private val ACT = COSName.getPDFName("ActualText")

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
    private fun root(doc: PDDocument) = deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("StructTreeRoot"))) as? COSDictionary
    private fun isElem(b: COSBase?) = (deref(b) as? COSDictionary)?.let { it.containsKey(S) && it.getNameAsString(COSName.TYPE).let { t -> t == null || t == "StructElem" } } == true
    private fun kidsOf(e: COSDictionary): List<COSBase> = when (val k = deref(e.getDictionaryObject(K))) { is COSArray -> (0 until k.size()).mapNotNull { deref(k.get(it)) }; null -> emptyList(); else -> listOf(k) }
    private fun kidArray(e: COSDictionary): COSArray = when (val k = deref(e.getDictionaryObject(K))) {
        is COSArray -> k; null -> COSArray().also { e.setItem(K, it) }; else -> COSArray().also { a -> a.add(e.getItem(K)); e.setItem(K, a) }
    }
    private fun roles(doc: PDDocument): Map<String, String> = (deref(root(doc)?.getDictionaryObject(COSName.getPDFName("RoleMap"))) as? COSDictionary)?.let { m ->
        m.keySet().associate { it.name to ((deref(m.getDictionaryObject(it)) as? COSName)?.name ?: it.name) }
    }.orEmpty()
    private fun typeOf(e: COSDictionary, roles: Map<String, String>): String { var t = e.getNameAsString(S) ?: ""; var n = 0; while (n++ < 8) t = roles[t]?.takeIf { it != t } ?: break; return t }
    private fun hasAlt(e: COSDictionary) = (deref(e.getDictionaryObject(ALT)) as? COSString)?.string?.isNotBlank() == true

    /** Every element with its parent, depth-first (parents before children). */
    private fun all(doc: PDDocument): List<Pair<COSDictionary, COSDictionary>> {
        val r = root(doc) ?: return emptyList()
        val out = ArrayList<Pair<COSDictionary, COSDictionary>>(); val seen = HashSet<COSDictionary>()
        fun walk(p: COSDictionary, d: Int) { if (d > 60) return; kidsOf(p).forEach { k -> if (isElem(k) && seen.add(k as COSDictionary)) { out += k to p; walk(k, d + 1) } } }
        walk(r, 0)
        return out
    }

    private fun subtree(e: COSDictionary, roles: Map<String, String>, depth: Int = 0): Triple<Boolean, Boolean, Boolean> {   // content, objr, alt below
        var content = false; var objr = false; var alt = false
        if (depth > 60) return Triple(false, false, false)
        kidsOf(e).forEach { k ->
            when {
                k is COSInteger -> content = true
                k is COSDictionary && k.getNameAsString(COSName.TYPE) == "MCR" -> content = true
                k is COSDictionary && k.getNameAsString(COSName.TYPE) == "OBJR" -> objr = true
                isElem(k) -> { val (c, o, a) = subtree(k as COSDictionary, roles, depth + 1); content = content || c; objr = objr || o; alt = alt || a || hasAlt(k) }
            }
        }
        return Triple(content, objr, alt)
    }

    /** nested_alt: alt text on an element whose descendants have their own: the outer one goes. */
    fun removeNestedAlt(doc: PDDocument): Int {
        val r = roles(doc); var n = 0
        all(doc).forEach { (e, _) -> if (hasAlt(e) && subtree(e, r).third) { e.removeItem(ALT); n++ } }
        return n
    }

    /** alt_content: alt text on an element with no content (it would be read for nothing). */
    fun removeAltWithoutContent(doc: PDDocument): Int {
        val r = roles(doc); var n = 0
        all(doc).forEach { (e, _) -> val (c, o, _) = subtree(e, r); if (hasAlt(e) && !c && !o) { e.removeItem(ALT); n++ } }
        return n
    }

    /** alt_hides_annot: alt text on a non-annotation element that holds an annotation hides it from screen readers. */
    fun removeAltHidingAnnots(doc: PDDocument): Int {
        val r = roles(doc); var n = 0
        all(doc).forEach { (e, _) -> if (hasAlt(e) && subtree(e, r).second && typeOf(e, r) !in setOf("Link", "Annot", "Form", "Figure")) { e.removeItem(ALT); n++ } }
        return n
    }

    /** other_alt: Formula / Ruby / Warichu without alt text get their ActualText, or [fallback]. */
    fun fillOtherAlt(doc: PDDocument, fallback: String): Int {
        val r = roles(doc); var n = 0
        all(doc).forEach { (e, _) ->
            if (typeOf(e, r) in setOf("Formula", "Ruby", "Warichu") && !hasAlt(e)) {
                e.setItem(ALT, COSString((deref(e.getDictionaryObject(ACT)) as? COSString)?.string?.takeIf { it.isNotBlank() } ?: fallback)); n++
            }
        }
        return n
    }

    /**
     * Wrap runs of [childTypes] children whose parent is not one of [parentTypes] into a new [wrapper] element
     * (table_rows: TR → Table, table_cells: TH/TD → TR, list_items: LI → L, list_body: Lbl/LBody → LI).
     */
    fun wrapOrphans(doc: PDDocument, childTypes: Set<String>, parentTypes: Set<String>, wrapper: String): Int {
        val r = roles(doc); var n = 0
        val parents = all(doc).map { it.second }.distinct() + listOfNotNull(root(doc))
        parents.distinct().forEach { p ->
            if (p.getNameAsString(COSName.TYPE) != "StructTreeRoot" && typeOf(p, r) in parentTypes) return@forEach
            val k = kidArray(p)
            var i = 0
            while (i < k.size()) {
                val c = deref(k.get(i))
                if (isElem(c) && typeOf(c as COSDictionary, r) in childTypes) {
                    val w = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setItem(S, COSName.getPDFName(wrapper)); setItem(P, p); setItem(K, COSArray()) }
                    c.getDictionaryObject(COSName.getPDFName("Pg"))?.let { w.setItem(COSName.getPDFName("Pg"), it) }
                    val wk = kidArray(w)
                    while (i < k.size()) {
                        val x = deref(k.get(i))
                        if (!(isElem(x) && typeOf(x as COSDictionary, r) in childTypes)) break
                        x.setItem(P, w); wk.add(x); k.remove(i)
                    }
                    k.add(i, w); i++; n++
                } else i++
            }
        }
        return n
    }

    /** table_headers: tables without any TH get their first row's cells as column headers. */
    fun firstRowHeaders(doc: PDDocument): Int {
        val r = roles(doc); var n = 0
        all(doc).map { it.first }.filter { typeOf(it, r) == "Table" }.forEach { t ->
            val rows = ArrayList<COSDictionary>()
            fun collect(x: COSDictionary, d: Int) { kidsOf(x).filter(::isElem).forEach { c -> c as COSDictionary; when (typeOf(c, r)) { "TR" -> rows += c; "THead", "TBody", "TFoot" -> if (d < 3) collect(c, d + 1) } } }
            collect(t, 0)
            val hasTh = rows.any { row -> kidsOf(row).any { isElem(it) && typeOf(it as COSDictionary, r) == "TH" } }
            val first = rows.firstOrNull() ?: return@forEach
            if (hasTh) return@forEach
            kidsOf(first).filter { isElem(it) && typeOf(it as COSDictionary, r) == "TD" }.forEach { c ->
                c as COSDictionary
                c.setItem(S, COSName.getPDFName("TH"))
                val a = (deref(c.getDictionaryObject(COSName.A)) as? COSDictionary) ?: COSDictionary().also { c.setItem(COSName.A, it); it.setItem(COSName.O, COSName.getPDFName("Table")) }
                a.setItem(COSName.getPDFName("Scope"), COSName.getPDFName("Column"))
            }
            n++
        }
        return n
    }

    /** table_summary: tables without a summary get "[label]: R × C" (rows × columns). */
    fun summaries(doc: PDDocument, label: (Int, Int) -> String): Int {
        val r = roles(doc); var n = 0
        all(doc).map { it.first }.filter { typeOf(it, r) == "Table" }.forEach { t ->
            val a = deref(t.getDictionaryObject(COSName.A))
            val existing = when (a) { is COSDictionary -> a.getString(COSName.getPDFName("Summary")); is COSArray -> (0 until a.size()).mapNotNull { (deref(a.get(it)) as? COSDictionary)?.getString(COSName.getPDFName("Summary")) }.firstOrNull(); else -> null }
            if (!existing.isNullOrBlank()) return@forEach
            val rows = ArrayList<Int>()
            fun collect(x: COSDictionary, d: Int) { kidsOf(x).filter(::isElem).forEach { c -> c as COSDictionary; when (typeOf(c, r)) { "TR" -> rows += kidsOf(c).count { isElem(it) && typeOf(it as COSDictionary, r) in setOf("TH", "TD") }; "THead", "TBody", "TFoot" -> if (d < 3) collect(c, d + 1) } } }
            collect(t, 0)
            val d = COSDictionary().apply { setItem(COSName.O, COSName.getPDFName("Table")); setString(COSName.getPDFName("Summary"), label(rows.size, rows.maxOrNull() ?: 0)) }
            when (a) { is COSArray -> a.add(d); is COSDictionary -> t.setItem(COSName.A, COSArray().also { x -> x.add(a); x.add(d) }); else -> t.setItem(COSName.A, d) }
            n++
        }
        return n
    }

    /** heading_nesting: H1 → H3 jumps become H1 → H2 (each heading at most one level below the previous one). */
    fun fixHeadingSkips(doc: PDDocument): Int {
        val r = roles(doc); var n = 0; var last = 0
        all(doc).map { it.first }.forEach { e ->
            val t = typeOf(e, r)
            if (t.length == 2 && t[0] == 'H' && t[1] in '1'..'6') {
                var lv = t[1] - '0'
                if (last > 0 && lv > last + 1) { lv = last + 1; e.setItem(S, COSName.getPDFName("H$lv")); n++ }
                last = lv
            }
        }
        return n
    }

    /** Pure: rule ids that have a direct fix here or in [AccessFix] (the rest open a tool or need a person). */
    val STRUCTURE_FIXES = setOf("nested_alt", "alt_content", "alt_hides_annot", "other_alt", "table_rows", "table_cells", "table_headers",
        "table_summary", "list_items", "list_body", "heading_nesting", "tagged_annots", "tagged_fields", "nav_links", "multimedia", "encoding")
}
