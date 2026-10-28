package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.edit.OutlineModel
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import com.tom_roush.pdfbox.text.PDFMarkedContentExtractor
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap

/**
 * fix/links-bookmarks (Foxit §16 "New bookmarks from structure"): bookmarks from the elements of the tag tree the
 * user picks (H1–H6, H, Title, Part, Sect, Caption, Table, Figure …), in structure order. The title is the element's
 * /T, /ActualText or /Alt, else the text of its marked content; the target is the element's page at its top.
 * Levels: Hn → n - 1, other types → how many picked elements contain it.
 */
object TagBookmarks {
    /** Element types offered in the picker (standard names; role-mapped custom names resolve to them). */
    val TYPES = listOf("Title", "H1", "H2", "H3", "H4", "H5", "H6", "H", "Part", "Art", "Sect", "Caption", "Table", "Figure", "Formula", "TOCI")
    val DEFAULT = setOf("Title", "H1", "H2", "H3", "H4", "H5", "H6", "H")

    private val S = COSName.getPDFName("S")
    private val K = COSName.getPDFName("K")
    private val PG = COSName.getPDFName("Pg")
    private val MCID = COSName.getPDFName("MCID")
    private val OBJR = COSName.getPDFName("OBJR")
    private val ACTUAL = COSName.getPDFName("ActualText")
    private val ALT = COSName.getPDFName("Alt")

    /** One picked element: its type, level, page dictionary, own title (may be blank) and the MCIDs on that page. */
    data class Elem(val type: String, val level: Int, val page: COSDictionary?, val title: String, val mcids: List<Int>)

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    private fun kids(d: COSDictionary): List<COSBase> = when (val k = deref(d.getItem(K))) {
        is COSArray -> (0 until k.size()).mapNotNull { deref(k.get(it)) }
        null -> emptyList()
        else -> listOf(k)
    }

    private fun type(d: COSDictionary, roles: Map<String, String>): String? {
        var t = d.getCOSName(S)?.name ?: return null
        repeat(4) { t = roles[t]?.takeIf { it != t } ?: return t }
        return t
    }

    fun roleMap(root: COSDictionary): Map<String, String> = (deref(root.getItem(COSName.getPDFName("RoleMap"))) as? COSDictionary)?.let { m ->
        m.keySet().mapNotNull { k -> (deref(m.getItem(k)) as? COSName)?.let { k.name to it.name } }.toMap()
    } ?: emptyMap()

    /** Level of a picked element of [type] with [depth] picked ancestors. */
    fun levelOf(type: String, depth: Int): Int = Regex("""^H([1-6])$""").find(type)?.groupValues?.get(1)?.toInt()?.minus(1) ?: depth

    /** The picked elements of the tree under [root], in structure (pre-) order. Pure COS walk. */
    fun elements(root: COSDictionary, picked: Set<String>, roles: Map<String, String> = emptyMap()): List<Elem> {
        val out = ArrayList<Elem>()
        val seen = IdentityHashMap<COSDictionary, Boolean>()
        /** MCIDs of the first page the content of [n] is on (and that page). */
        fun content(n: COSBase?, pg: COSDictionary?, acc: MutableList<Int>, page: Array<COSDictionary?>, depth: Int) {
            if (depth > 200) return
            when (n) {
                is COSNumber -> if (pg != null && (page[0] == null || page[0] === pg)) { page[0] = pg; acc += n.intValue() }
                is COSDictionary -> {
                    if (n.getCOSName(COSName.TYPE) == OBJR) return
                    val p = (deref(n.getItem(PG)) as? COSDictionary) ?: pg
                    if (n.containsKey(MCID) && !n.containsKey(S)) { if (p != null && (page[0] == null || page[0] === p)) { page[0] = p; acc += n.getInt(MCID) }; return }
                    kids(n).forEach { content(it, p, acc, page, depth + 1) }
                }
                else -> Unit
            }
        }
        fun walk(n: COSBase?, pg: COSDictionary?, depth: Int, guard: Int) {
            val d = n as? COSDictionary ?: return
            if (guard > 200 || seen.put(d, true) != null) return
            val p = (deref(d.getItem(PG)) as? COSDictionary) ?: pg
            val t = type(d, roles)
            var inner = depth
            if (t != null && t in picked) {
                val acc = ArrayList<Int>(); val page = arrayOf<COSDictionary?>(null)
                content(d, p, acc, page, 0)
                val title = (d.getString(COSName.T) ?: d.getString(ACTUAL) ?: d.getString(ALT) ?: "").replace(Regex("""\s+"""), " ").trim()
                out += Elem(t, levelOf(t, depth), page[0] ?: p, title, acc)
                inner = depth + 1
            }
            kids(d).forEach { walk(it, p, inner, guard + 1) }
        }
        walk(root, null, 0, 0)
        return out
    }

    /** Text and top (user space) of each marked content of a page. */
    private fun pageText(doc: PDDocument, pageIndex: Int): Map<Int, Pair<String, Float?>> {
        val page = doc.getPage(pageIndex)
        val crop = page.cropBox
        val ex = PDFMarkedContentExtractor()
        runCatching { ex.processPage(page) }
        val out = HashMap<Int, Pair<String, Float?>>()
        fun text(mc: PDMarkedContent, sb: StringBuilder, top: FloatArray) {
            mc.contents.forEach { c ->
                when (c) {
                    is TextPosition -> { sb.append(c.unicode); top[0] = maxOf(top[0], crop.upperRightY - c.yDirAdj + c.heightDir) }
                    is PDMarkedContent -> text(c, sb, top)
                }
            }
        }
        fun collect(mc: PDMarkedContent) {
            if (mc.mcid >= 0 && mc.mcid !in out) {
                val sb = StringBuilder(); val top = floatArrayOf(-Float.MAX_VALUE)
                text(mc, sb, top)
                out[mc.mcid] = sb.toString() to top[0].takeIf { it > -Float.MAX_VALUE }
            }
            mc.contents.forEach { if (it is PDMarkedContent) collect(it) }
        }
        ex.markedContents.forEach { collect(it) }
        return out
    }

    /** Bookmarks of the picked elements of [doc]; empty when it has no tag tree. */
    fun bookmarks(doc: PDDocument, picked: Set<String>, view: DestView = DestView(DestFit.XYZ)): List<E5Bookmark> {
        val root = deref(doc.documentCatalog.cosObject.getItem(COSName.STRUCT_TREE_ROOT)) as? COSDictionary ?: return emptyList()
        val elems = elements(root, picked, roleMap(root))
        if (elems.isEmpty()) return emptyList()
        val pageIdx = IdentityHashMap<COSDictionary, Int>().apply { doc.pages.forEachIndexed { i, p -> put(p.cosObject, i) } }
        val texts = HashMap<Int, Map<Int, Pair<String, Float?>>>()
        val list = elems.mapNotNull { e ->
            val pi = e.page?.let { pageIdx[it] } ?: return@mapNotNull null
            val runs = if (e.mcids.isEmpty()) emptyList() else e.mcids.mapNotNull { texts.getOrPut(pi) { pageText(doc, pi) }[it] }
            val title = e.title.ifBlank { runs.joinToString(" ") { it.first }.replace(Regex("""\s+"""), " ").trim() }.take(200)
            if (title.isBlank()) return@mapNotNull null
            val top = runs.mapNotNull { it.second }.maxOrNull()?.let { it + 4f }
            val v = when (view.fit) {
                DestFit.XYZ -> if (top == null) DestView(DestFit.FIT) else view.copy(top = top)
                DestFit.FITH, DestFit.FITBH -> view.copy(top = top)
                else -> view
            }
            E5Bookmark(OutlineModel.newKey(), title, pi, e.level).withView(v)
        }
        return OutlineModel.normalize(list.map { it.row() }).let { n -> list.mapIndexed { i, b -> b.copy(level = n[i].level) } }
    }

    suspend fun load(src: File, password: String?, picked: Set<String>, view: DestView): List<E5Bookmark> = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { bookmarks(it, picked, view) }
    }
}
