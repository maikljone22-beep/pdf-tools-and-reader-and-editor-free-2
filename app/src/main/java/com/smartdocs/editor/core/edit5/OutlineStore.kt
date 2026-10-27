package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.edit.OutlineModel
import com.smartdocs.editor.core.edit.OutlineRow
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One bookmark with its look and destination. [color] null = viewer default. [orig] = pre-order index of the item
 * in the file it was read from (-1 for new ones). [actions] non-empty = the bookmark runs this action list (web link,
 * file, named action, JavaScript, print / extract pages, several in a row) instead of going to [page]; action types
 * we do not edit are kept from item [orig] ([NavAction.Kept]).
 */
data class E5Bookmark(
    val key: Long, val title: String, val page: Int, val level: Int,
    val color: Int? = null, val bold: Boolean = false, val italic: Boolean = false, val open: Boolean = false,
    val fit: DestFit = DestFit.XYZ, val zoom: Float = 0f, val top: Float? = null, val left: Float? = null,
    val orig: Int = -1, val fitR: List<Float>? = null, val actions: List<NavAction> = emptyList(),
) {
    fun row() = OutlineRow(key, title, page, level)
    val view: DestView get() = DestView(fit, left, top, zoom, fitR)
    fun withView(v: DestView) = copy(fit = v.fit, left = v.left, top = v.top, zoom = v.zoom, fitR = v.rect)
}

/** Outline editor model that keeps colour, bold/italic, open state and destination zoom (the base editor drops them). */
object OutlineStore {
    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    /** Pre-order list of outline item dictionaries with their level. */
    private fun items(doc: PDDocument): List<Pair<COSDictionary, Int>> {
        val out = ArrayList<Pair<COSDictionary, Int>>()
        val seen = HashSet<COSDictionary>()
        fun walk(node: COSDictionary?, level: Int) {
            var it = deref(node?.getDictionaryObject(COSName.FIRST)) as? COSDictionary
            while (it != null && seen.add(it) && level < 40) {
                out += it to level
                walk(it, level + 1)
                it = deref(it.getDictionaryObject(COSName.NEXT)) as? COSDictionary
            }
        }
        walk(deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES)) as? COSDictionary, 0)
        return out
    }

    suspend fun load(src: File, password: String?): Pair<List<E5Bookmark>, Int> = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc -> read(doc) to doc.numberOfPages }
    }

    /** The outline of [doc] (pre-order with levels). */
    fun read(doc: PDDocument): List<E5Bookmark> {
        run {
            val pageIdx = doc.pages.withIndex().associate { it.value.cosObject to it.index }
            val list = items(doc).mapIndexed { k, (d, level) ->
                val c = (deref(d.getDictionaryObject(COSName.C)) as? COSArray)?.toFloatArray()
                val color = if (c != null && c.size >= 3) (((c[0] * 255).toInt() and 0xFF) shl 16) or (((c[1] * 255).toInt() and 0xFF) shl 8) or ((c[2] * 255).toInt() and 0xFF) else null
                val flags = d.getInt(COSName.F, 0)
                var bm = E5Bookmark(OutlineModel.newKey(), d.getString(COSName.TITLE) ?: "", -1, level, color = color.takeIf { it != 0 }, bold = flags and 2 != 0, italic = flags and 1 != 0,
                    open = d.getInt(COSName.COUNT, 0) > 0, orig = k)
                var dest = deref(d.getDictionaryObject(COSName.DEST))
                val action = deref(d.getDictionaryObject(COSName.A)) as? COSDictionary
                if (dest == null && action != null) {
                    if (action.getNameAsString(COSName.S) == "GoTo" && action.getDictionaryObject(COSName.NEXT) == null) dest = deref(action.getDictionaryObject(COSName.D))
                    else bm = bm.copy(actions = NavActions.read(doc, action))
                }
                if (dest is COSString || dest is COSName) {
                    val name = (dest as? COSString)?.string ?: (dest as COSName).name
                    dest = runCatching { doc.documentCatalog.findNamedDestinationPage(com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination(name))?.cosObject }.getOrNull()
                }
                (dest as? COSArray)?.let { a ->
                    val pg = (deref(a.getObject(0)) as? COSDictionary)?.let { pageIdx[it] } ?: (a.getObject(0) as? COSInteger)?.intValue() ?: -1
                    bm = bm.withView(Dests.view(a)).copy(page = pg)
                }
                // the page of an action list's first go-to (for "find current bookmark" and lists)
                if (bm.actions.isNotEmpty()) (bm.actions.firstOrNull { it is NavAction.GoTo } as? NavAction.GoTo)?.let { g -> if (g.page >= 0) bm = bm.copy(page = g.page) }
                bm
            }
            return list
        }
    }

    suspend fun save(src: File, out: File, password: String?, list: List<E5Bookmark>) = withContext(Dispatchers.IO) {
        NavIo.edit(src, out, password) { doc, _ -> write(doc, list) }
    }

    /** Write [list] (pre-order with levels) as the document outline. */
    fun write(doc: PDDocument, list: List<E5Bookmark>) {
        val cat = doc.documentCatalog.cosObject
        val old = items(doc).map { it.first }
        val rows = OutlineModel.normalize(list.map { it.row() }).let { norm -> list.mapIndexed { i, b -> b.copy(level = norm[i].level) } }
        if (rows.isEmpty()) { cat.removeItem(COSName.OUTLINES); return }
        val root = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("Outlines")) }
        val n = doc.numberOfPages
        class Node(val d: COSDictionary, val b: E5Bookmark?, val kids: ArrayList<Node> = ArrayList())
        val top = Node(root, null)
        val stack = ArrayList<Node>().apply { add(top) }
        for (b in rows) {
            val d = COSDictionary()
            d.setString(COSName.TITLE, b.title)
            b.color?.let { c -> d.setItem(COSName.C, COSArray().apply { E5Io.rgb(c).forEach { add(COSFloat(it)) } }) }
            val f = (if (b.italic) 1 else 0) or (if (b.bold) 2 else 0)
            if (f != 0) d.setInt(COSName.F, f)
            val chain = if (b.actions.isNotEmpty()) NavActions.write(doc, b.actions, old.getOrNull(b.orig)?.getDictionaryObject(COSName.A)) else null
            if (chain != null) d.setItem(COSName.A, chain)
            else if (b.page in 0 until n) d.setItem(COSName.DEST, Dests.array(doc.getPage(b.page).cosObject, b.view))
            val node = Node(d, b)
            while (stack.size > b.level + 1) stack.removeAt(stack.size - 1)
            stack.last().kids += node
            stack += node
        }
        // link siblings / parents and compute /Count (open: visible descendants; closed: negative descendant count)
        fun link(p: Node): Int {
            var visible = 0
            p.kids.forEachIndexed { i, k ->
                k.d.setItem(COSName.PARENT, p.d)
                if (i > 0) k.d.setItem(COSName.PREV, p.kids[i - 1].d)
                if (i < p.kids.size - 1) k.d.setItem(COSName.NEXT, p.kids[i + 1].d)
                val sub = link(k)
                visible += 1 + (if (k.b?.open == true) sub else 0)
            }
            if (p.kids.isNotEmpty()) {
                p.d.setItem(COSName.FIRST, p.kids.first().d); p.d.setItem(COSName.LAST, p.kids.last().d)
                val isOpen = p.b == null || p.b.open
                p.d.setInt(COSName.COUNT, if (isOpen) visible else -visible)
            }
            return visible
        }
        link(top)
        cat.setItem(COSName.OUTLINES, root)
    }

    /** Run a flat-tree operation of [OutlineModel] on the list and keep each row's style (new rows get defaults). */
    fun tree(list: List<E5Bookmark>, op: (List<OutlineRow>) -> List<OutlineRow>): List<E5Bookmark> {
        val byKey = list.associateBy { it.key }
        return op(list.map { it.row() }).map { r -> byKey[r.key]?.copy(title = r.title, page = r.page, level = r.level) ?: E5Bookmark(r.key, r.title, r.page, r.level) }
    }

    /** Pairs-returning variant (move up / down). */
    fun tree2(list: List<E5Bookmark>, op: (List<OutlineRow>) -> Pair<List<OutlineRow>, Int>): Pair<List<E5Bookmark>, Int> {
        var idx = 0
        val res = tree(list) { rows -> val (r, i) = op(rows); idx = i; r }
        return res to idx
    }
}
