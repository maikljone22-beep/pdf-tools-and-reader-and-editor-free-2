package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument

/** One structure element of the tags tree. [path] = indexes into the /K arrays from the root (e.g. [0, 3, 1]). */
data class TagNode(
    val path: List<Int>, val depth: Int, val type: String, val mapped: String?, val title: String?, val alt: String?,
    val actualText: String?, val lang: String?, val expansion: String?, val id: String?, val page: Int,
    val contentItems: Int, val children: Int,
)

/** Table / list attributes of an element (/A with /O /Table or /List). */
data class TagAttrs(val scope: String? = null, val rowSpan: Int? = null, val colSpan: Int? = null, val headers: String? = null,
                    val summary: String? = null, val listNumbering: String? = null)

/** An annotation with no structure element (Foxit "Find: unmarked annotations / links"). */
data class Unmarked(val page: Int, val subtype: String)

/**
 * Foxit 34.47–34.51 / Nitro 19 tags panel on the COS level: tree with properties, change type, edit Title / Alt /
 * ActualText / Lang / E / ID and table or list attributes, new child / sibling, delete (children move up), delete
 * empty tags, move up / down, indent / outdent, role map, "document is tagged" flag, clear one page's structure,
 * find and tag unmarked annotations. Pure over [PDDocument] — JVM-tested.
 */
object AccessTags {
    private val N_ROOT = COSName.getPDFName("StructTreeRoot")
    private val N_PG = COSName.getPDFName("Pg")
    private val N_ROLE = COSName.getPDFName("RoleMap")
    private val N_ALT = COSName.getPDFName("Alt"); private val N_ACT = COSName.getPDFName("ActualText"); private val N_E = COSName.getPDFName("E")
    private val N_T = COSName.T; private val N_ID = COSName.getPDFName("ID"); private val N_A = COSName.A; private val N_O = COSName.O

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
    fun root(doc: PDDocument): COSDictionary? = deref(doc.documentCatalog.cosObject.getDictionaryObject(N_ROOT)) as? COSDictionary

    /** /K of [node] as an array (a single kid is wrapped so it can be edited). */
    private fun kids(node: COSDictionary): COSArray {
        return when (val k = deref(node.getDictionaryObject(COSName.K))) {
            is COSArray -> k
            null -> COSArray().also { node.setItem(COSName.K, it) }
            else -> COSArray().also { a -> a.add(node.getItem(COSName.K)); node.setItem(COSName.K, a) }
        }
    }
    private fun isElem(b: COSBase?) = (deref(b) as? COSDictionary)?.let { it.containsKey(COSName.S) && it.getNameAsString(COSName.TYPE).let { t -> t == null || t == "StructElem" } } == true

    fun at(doc: PDDocument, path: List<Int>): COSDictionary? {
        var cur = root(doc) ?: return null
        for (i in path) { val k = deref(cur.getDictionaryObject(COSName.K)); cur = (if (k is COSArray) deref(k.getObject(i)) else if (i == 0) k else null) as? COSDictionary ?: return null }
        return cur
    }

    fun roleMap(doc: PDDocument): Map<String, String> = (deref(root(doc)?.getDictionaryObject(N_ROLE)) as? COSDictionary)?.let { m ->
        m.keySet().associate { it.name to ((deref(m.getDictionaryObject(it)) as? COSName)?.name ?: "") }
    }.orEmpty()

    fun setRole(doc: PDDocument, custom: String, standard: String?) {
        val r = root(doc) ?: return
        val m = (deref(r.getDictionaryObject(N_ROLE)) as? COSDictionary) ?: COSDictionary().also { r.setItem(N_ROLE, it) }
        if (standard.isNullOrBlank()) m.removeItem(COSName.getPDFName(custom)) else m.setItem(COSName.getPDFName(custom), COSName.getPDFName(standard))
        if (m.size() == 0) r.removeItem(N_ROLE)
    }

    fun tree(doc: PDDocument, max: Int = 5000): List<TagNode> {
        val r = root(doc) ?: return emptyList()
        val pageIdx = doc.pages.withIndex().associate { it.value.cosObject to it.index }
        val roles = roleMap(doc)
        val out = ArrayList<TagNode>()
        fun walk(node: COSDictionary, path: List<Int>, depth: Int, page: Int) {
            if (depth > 60 || out.size >= max) return
            val k = deref(node.getDictionaryObject(COSName.K))
            val list = if (k is COSArray) (0 until k.size()).map { k.get(it) } else listOfNotNull(node.getItem(COSName.K))
            list.forEachIndexed { i, b ->
                val e = deref(b) as? COSDictionary
                if (e == null || !isElem(e)) return@forEachIndexed
                val pg = (deref(e.getDictionaryObject(N_PG)) as? COSDictionary)?.let { pageIdx[it] } ?: page
                val ek = deref(e.getDictionaryObject(COSName.K))
                val ekList = if (ek is COSArray) (0 until ek.size()).map { ek.get(it) } else listOfNotNull(e.getItem(COSName.K))
                val type = e.getNameAsString(COSName.S) ?: "?"
                out += TagNode(path + i, depth, type, roles[type], e.getString(N_T), e.getString(N_ALT), e.getString(N_ACT), e.getString(COSName.LANG), e.getString(N_E),
                    e.getString(N_ID), pg, ekList.count { !isElem(it) }, ekList.count { isElem(it) })
                walk(e, path + i, depth + 1, pg)
            }
        }
        walk(r, emptyList(), 0, -1)
        return out
    }

    fun setType(doc: PDDocument, path: List<Int>, type: String) { at(doc, path)?.setItem(COSName.S, COSName.getPDFName(type)) }

    /** Title, Alt, ActualText, Lang, E (expansion), ID; blank removes. */
    fun setProps(doc: PDDocument, path: List<Int>, title: String?, alt: String?, actual: String?, lang: String?, expansion: String?, id: String?) {
        val e = at(doc, path) ?: return
        fun put(k: COSName, v: String?) { if (v.isNullOrBlank()) e.removeItem(k) else e.setItem(k, COSString(v)) }
        put(N_T, title); put(N_ALT, alt); put(N_ACT, actual); put(COSName.LANG, lang); put(N_E, expansion)
        if (id.isNullOrBlank()) e.removeItem(N_ID) else e.setItem(N_ID, COSString(id))
    }

    private fun attrDict(e: COSDictionary, owner: String, create: Boolean): COSDictionary? {
        val a = deref(e.getDictionaryObject(N_A))
        val list = when (a) { is COSArray -> (0 until a.size()).mapNotNull { deref(a.get(it)) as? COSDictionary }; is COSDictionary -> listOf(a); else -> emptyList() }
        list.firstOrNull { it.getNameAsString(N_O) == owner }?.let { return it }
        if (!create) return null
        val d = COSDictionary().also { it.setItem(N_O, COSName.getPDFName(owner)) }
        when (a) { is COSArray -> a.add(d); is COSDictionary -> e.setItem(N_A, COSArray().also { x -> x.add(a); x.add(d) }); else -> e.setItem(N_A, d) }
        return d
    }

    fun attrs(doc: PDDocument, path: List<Int>): TagAttrs {
        val e = at(doc, path) ?: return TagAttrs()
        val t = attrDict(e, "Table", false); val l = attrDict(e, "List", false)
        val headers = (deref(t?.getDictionaryObject(COSName.getPDFName("Headers"))) as? COSArray)?.mapNotNull { (deref(it) as? COSString)?.string }?.joinToString(" ")
        return TagAttrs(t?.getNameAsString(COSName.getPDFName("Scope")), t?.getInt(COSName.getPDFName("RowSpan"), -1)?.takeIf { it > 0 },
            t?.getInt(COSName.getPDFName("ColSpan"), -1)?.takeIf { it > 0 }, headers, t?.getString(COSName.getPDFName("Summary")), l?.getNameAsString(COSName.getPDFName("ListNumbering")))
    }

    /** Table attributes (Scope Row / Column / Both, spans, header IDs, Summary) and the list numbering style. */
    fun setAttrs(doc: PDDocument, path: List<Int>, a: TagAttrs) {
        val e = at(doc, path) ?: return
        val t = attrDict(e, "Table", true)!!
        fun name(k: String, v: String?) { if (v.isNullOrBlank()) t.removeItem(COSName.getPDFName(k)) else t.setItem(COSName.getPDFName(k), COSName.getPDFName(v)) }
        fun int(k: String, v: Int?) { if (v == null || v <= 1) t.removeItem(COSName.getPDFName(k)) else t.setInt(COSName.getPDFName(k), v) }
        name("Scope", a.scope); int("RowSpan", a.rowSpan); int("ColSpan", a.colSpan)
        if (a.headers.isNullOrBlank()) t.removeItem(COSName.getPDFName("Headers"))
        else t.setItem(COSName.getPDFName("Headers"), COSArray().also { arr -> a.headers.split(' ', ',').filter { it.isNotBlank() }.forEach { arr.add(COSString(it)) } })
        if (a.summary.isNullOrBlank()) t.removeItem(COSName.getPDFName("Summary")) else t.setString(COSName.getPDFName("Summary"), a.summary)
        if (t.keySet().size <= 1) { removeAttr(e, t) }
        if (a.listNumbering != null) {
            val l = attrDict(e, "List", true)!!
            if (a.listNumbering.isBlank()) removeAttr(e, l) else l.setItem(COSName.getPDFName("ListNumbering"), COSName.getPDFName(a.listNumbering))
        }
    }

    private fun removeAttr(e: COSDictionary, d: COSDictionary) {
        when (val a = deref(e.getDictionaryObject(N_A))) {
            is COSArray -> { for (k in a.size() - 1 downTo 0) if (deref(a.get(k)) === d) a.remove(k); if (a.size() == 0) e.removeItem(N_A) }
            is COSDictionary -> if (a === d) e.removeItem(N_A)
            else -> {}
        }
    }

    private fun parentOf(doc: PDDocument, path: List<Int>): COSDictionary? = if (path.isEmpty()) null else if (path.size == 1) root(doc) else at(doc, path.dropLast(1))

    /** New element of [type] as the last child of [path] (empty path = top level). Returns its path. */
    fun newChild(doc: PDDocument, path: List<Int>, type: String): List<Int>? {
        val parent = if (path.isEmpty()) root(doc) else at(doc, path); parent ?: return null
        val e = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setItem(COSName.S, COSName.getPDFName(type)); setItem(COSName.P, parent); setItem(COSName.K, COSArray()) }
        (deref(parent.getDictionaryObject(N_PG)))?.let { e.setItem(N_PG, it) }
        val k = kids(parent); k.add(e)
        return path + (k.size() - 1)
    }

    /** New element right after [path] in the same parent. */
    fun newSibling(doc: PDDocument, path: List<Int>, type: String): List<Int>? {
        val parent = parentOf(doc, path) ?: return null
        val me = at(doc, path) ?: return null
        val e = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setItem(COSName.S, COSName.getPDFName(type)); setItem(COSName.P, parent); setItem(COSName.K, COSArray()) }
        (deref(me.getDictionaryObject(N_PG)))?.let { e.setItem(N_PG, it) }
        val k = kids(parent); k.add(path.last() + 1, e)
        return path.dropLast(1) + (path.last() + 1)
    }

    /** Deletes the tag; its children (elements and content) move up into its place, so no content loses its tag tree. */
    fun delete(doc: PDDocument, path: List<Int>) {
        val parent = parentOf(doc, path) ?: return
        val me = at(doc, path) ?: return
        val k = kids(parent); val mine = kids(me)
        val i = path.last()
        k.remove(i)
        for (j in mine.size() - 1 downTo 0) {
            val c = mine.get(j); (deref(c) as? COSDictionary)?.let { if (isElem(it)) it.setItem(COSName.P, parent) }
            k.add(i, c)
        }
        reparentOwners(doc, me, parent)
    }

    /** ParentTree entries that pointed at [from] point at [to] (MCIDs keep an owner). */
    private fun reparentOwners(doc: PDDocument, from: COSDictionary, to: COSDictionary) {
        val r = root(doc) ?: return
        fun fix(node: COSDictionary?, depth: Int) {
            if (node == null || depth > 20) return
            (deref(node.getDictionaryObject(COSName.getPDFName("Nums"))) as? COSArray)?.let { nums ->
                for (i in 1 until nums.size() step 2) when (val v = deref(nums.get(i))) {
                    is COSArray -> for (j in 0 until v.size()) if (deref(v.get(j)) === from) v.set(j, to)
                    is COSDictionary -> if (v === from) nums.set(i, to)
                    else -> {}
                }
            }
            (deref(node.getDictionaryObject(COSName.KIDS)) as? COSArray)?.forEach { fix(deref(it) as? COSDictionary, depth + 1) }
        }
        fix(deref(r.getDictionaryObject(COSName.getPDFName("ParentTree"))) as? COSDictionary, 0)
    }

    /** Removes elements with no content and no child elements (repeats until none). Returns how many. */
    fun deleteEmpty(doc: PDDocument): Int {
        var n = 0
        while (true) {
            val empty = tree(doc).lastOrNull { it.children == 0 && it.contentItems == 0 } ?: return n
            delete(doc, empty.path); n++
            if (n > 10_000) return n
        }
    }

    /** Moves the tag one place up / down among its siblings. Returns the new path. */
    fun move(doc: PDDocument, path: List<Int>, delta: Int): List<Int> {
        val parent = parentOf(doc, path) ?: return path
        val k = kids(parent); val i = path.last(); val j = i + delta
        if (j !in 0 until k.size()) return path
        val item = k.get(i); k.remove(i); k.add(j, item)
        return path.dropLast(1) + j
    }

    /** Indent: becomes the last child of the previous sibling element. */
    fun indent(doc: PDDocument, path: List<Int>): List<Int> {
        if (path.last() == 0) return path
        val parent = parentOf(doc, path) ?: return path
        val k = kids(parent)
        val prevIndex = (path.last() - 1 downTo 0).firstOrNull { isElem(k.get(it)) } ?: return path
        val prev = deref(k.get(prevIndex)) as COSDictionary
        val item = k.get(path.last()); k.remove(path.last())
        (deref(item) as? COSDictionary)?.setItem(COSName.P, prev)
        val pk = kids(prev); pk.add(item)
        return path.dropLast(1) + prevIndex + (pk.size() - 1)
    }

    /** Outdent: becomes the next sibling of its parent. */
    fun outdent(doc: PDDocument, path: List<Int>): List<Int> {
        if (path.size < 2) return path
        val parent = parentOf(doc, path) ?: return path
        val grand = parentOf(doc, path.dropLast(1)) ?: return path
        val k = kids(parent); val item = k.get(path.last()); k.remove(path.last())
        (deref(item) as? COSDictionary)?.setItem(COSName.P, grand)
        val gk = kids(grand); val at = path[path.size - 2] + 1
        gk.add(at, item)
        return path.dropLast(2) + at
    }

    fun isTaggedFlag(doc: PDDocument) = (deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("MarkInfo"))) as? COSDictionary)?.getBoolean(COSName.getPDFName("Marked"), false) == true

    /** "Document is Tagged PDF" flag (/MarkInfo /Marked). */
    fun setTaggedFlag(doc: PDDocument, on: Boolean) {
        val c = doc.documentCatalog.cosObject
        val mi = (deref(c.getDictionaryObject(COSName.getPDFName("MarkInfo"))) as? COSDictionary) ?: COSDictionary().also { c.setItem(COSName.getPDFName("MarkInfo"), it) }
        mi.setItem(COSName.getPDFName("Marked"), if (on) COSBoolean.TRUE else COSBoolean.FALSE)
    }

    /** Clear the structure of one page: content references (MCIDs) of [page] go; elements left empty are removed. */
    fun clearPage(doc: PDDocument, page: Int): Int {
        val pd = doc.getPage(page).cosObject
        val r = root(doc) ?: return 0
        var n = 0
        fun walk(node: COSDictionary, inherited: COSDictionary?, depth: Int) {
            if (depth > 60) return
            val pg = (deref(node.getDictionaryObject(N_PG)) as? COSDictionary) ?: inherited
            val k = deref(node.getDictionaryObject(COSName.K))
            if (k is COSInteger) { if (pg === pd) { node.removeItem(COSName.K); n++ }; return }
            if (k !is COSArray) { (deref(k) as? COSDictionary)?.let { d -> if (isElem(d)) walk(d, pg, depth + 1) }; return }
            for (i in k.size() - 1 downTo 0) {
                when (val c = deref(k.get(i))) {
                    is COSInteger -> if (pg === pd) { k.remove(i); n++ }
                    is COSDictionary -> if (isElem(c)) walk(c, pg, depth + 1)
                        else if ((deref(c.getDictionaryObject(N_PG)) as? COSDictionary ?: pg) === pd) { k.remove(i); n++ }   // MCR / OBJR on the page
                    else -> {}
                }
            }
        }
        walk(r, null, 0)
        pd.removeItem(COSName.getPDFName("StructParents"))
        runCatching { doc.getPage(page).annotations }.getOrNull()?.forEach { it.cosObject.removeItem(COSName.getPDFName("StructParent")) }
        deleteEmpty(doc)
        return n
    }

    /** Annotations without a structure element (/StructParent). Popups are not content. */
    fun unmarked(doc: PDDocument): List<Unmarked> = doc.pages.flatMapIndexed { i, p ->
        runCatching { p.annotations }.getOrNull().orEmpty().filter { it.subtype != "Popup" && !it.cosObject.containsKey(COSName.getPDFName("StructParent")) }.map { Unmarked(i, it.subtype ?: "?") }
    }

    /** Foxit "Tag annotations": every unmarked annotation gets a Link / Form / Annot element (OBJR) under the first top-level element. */
    fun tagAnnotations(doc: PDDocument): Int {
        val r = root(doc) ?: return 0
        val top = (deref(r.getDictionaryObject(COSName.K)).let { if (it is COSArray) deref(it.getObject(0)) else it } as? COSDictionary)?.takeIf { isElem(it) } ?: r
        val pt = (deref(r.getDictionaryObject(COSName.getPDFName("ParentTree"))) as? COSDictionary) ?: COSDictionary().also { r.setItem(COSName.getPDFName("ParentTree"), it) }
        val nums = (deref(pt.getDictionaryObject(COSName.getPDFName("Nums"))) as? COSArray) ?: run {
            // a ParentTree with kids: a new leaf takes the new keys
            val leaf = COSDictionary(); val a = COSArray(); leaf.setItem(COSName.getPDFName("Nums"), a)
            (deref(pt.getDictionaryObject(COSName.KIDS)) as? COSArray)?.add(leaf) ?: pt.setItem(COSName.getPDFName("Nums"), a)
            a
        }
        var next = r.getInt(COSName.getPDFName("ParentTreeNextKey"), -1).takeIf { it >= 0 } ?: run {
            var mx = -1; for (i in 0 until nums.size() step 2) (deref(nums.get(i)) as? COSInteger)?.let { mx = maxOf(mx, it.intValue()) }; mx + 1
        }
        var n = 0
        doc.pages.forEach { p ->
            runCatching { p.annotations }.getOrNull()?.forEach { a ->
                if (a.subtype == "Popup" || a.cosObject.containsKey(COSName.getPDFName("StructParent"))) return@forEach
                val type = when (a.subtype) { "Link" -> "Link"; "Widget" -> "Form"; else -> "Annot" }
                val e = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setItem(COSName.S, COSName.getPDFName(type)); setItem(COSName.P, top); setItem(N_PG, p.cosObject) }
                e.setItem(COSName.K, COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("OBJR")); setItem(COSName.getPDFName("Obj"), a.cosObject); setItem(N_PG, p.cosObject) })
                a.contents?.takeIf { it.isNotBlank() }?.let { e.setString(N_ALT, it) }
                kids(top).add(e)
                a.cosObject.setInt(COSName.getPDFName("StructParent"), next)
                nums.add(COSInteger.get(next.toLong())); nums.add(e); next++; n++
            }
        }
        r.setInt(COSName.getPDFName("ParentTreeNextKey"), next)
        return n
    }

    /** Standard structure types offered by "change tag type". */
    val TYPES = listOf("Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption", "TOC", "TOCI", "Index", "NonStruct", "Private",
        "H", "H1", "H2", "H3", "H4", "H5", "H6", "P", "L", "LI", "Lbl", "LBody", "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot",
        "Span", "Quote", "Note", "Reference", "BibEntry", "Code", "Link", "Annot", "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP",
        "Figure", "Formula", "Form", "Artifact")
}
