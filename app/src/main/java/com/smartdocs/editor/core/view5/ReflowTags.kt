package com.smartdocs.editor.core.view5

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
import java.util.IdentityHashMap

/** A cell of a tagged table: the marked-content ids it owns on one page, in structure order. */
data class TagCell(val mcids: List<Int>, val header: Boolean)

/**
 * Tables of tagged PDFs for the reflow reader (OLD A1): when the structure tree has Table elements (TR, TH / TD,
 * also inside THead / TBody / TFoot, role-mapped names resolved), their cells are taken from the tags instead of
 * being guessed from the layout. [tables] walks the COS tree only (unit tested); [load] reads the cell text.
 */
object ReflowTags {
    private val S = COSName.getPDFName("S")
    private val K = COSName.getPDFName("K")
    private val PG = COSName.getPDFName("Pg")
    private val MCID = COSName.getPDFName("MCID")
    private val OBJR = COSName.getPDFName("OBJR")

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.`object` else b

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

    /** Rows of the tables of the tree under [root] that draw on [page]; [roles] is the RoleMap (custom name → standard). */
    fun tables(root: COSDictionary, page: COSDictionary, roles: Map<String, String> = emptyMap()): List<List<List<TagCell>>> {
        val out = ArrayList<List<List<TagCell>>>()
        val seen = IdentityHashMap<COSDictionary, Boolean>()
        fun mcids(n: COSBase?, pg: COSDictionary?, acc: MutableList<Int>) {
            when (n) {
                is COSNumber -> if (pg === page) acc += n.intValue()
                is COSDictionary -> {
                    if (seen.put(n, true) != null) return
                    if (n.getCOSName(COSName.TYPE) == OBJR) return
                    val p = (deref(n.getItem(PG)) as? COSDictionary) ?: pg
                    if (n.containsKey(MCID) && !n.containsKey(S)) { if (p === page) acc += n.getInt(MCID); return }
                    kids(n).forEach { mcids(it, p, acc) }
                }
                else -> {}
            }
        }
        fun rows(n: COSDictionary, pg: COSDictionary?, acc: MutableList<List<TagCell>>) {
            for (k in kids(n)) {
                val d = k as? COSDictionary ?: continue
                val p = (deref(d.getItem(PG)) as? COSDictionary) ?: pg
                when (type(d, roles)) {
                    "THead", "TBody", "TFoot" -> rows(d, p, acc)
                    "TR" -> {
                        val cells = kids(d).mapNotNull { c -> (c as? COSDictionary)?.let { cd ->
                            val t = type(cd, roles)
                            if (t != "TH" && t != "TD") null else ArrayList<Int>().also { mcids(cd, (deref(cd.getItem(PG)) as? COSDictionary) ?: p, it) }.let { TagCell(it, t == "TH") }
                        } }
                        if (cells.isNotEmpty()) acc += cells
                    }
                }
            }
        }
        fun walk(n: COSBase?, pg: COSDictionary?, depth: Int) {
            val d = n as? COSDictionary ?: return
            if (depth > 200 || seen.containsKey(d)) return
            val p = (deref(d.getItem(PG)) as? COSDictionary) ?: pg
            if (type(d, roles) == "Table") {
                seen[d] = true
                val r = ArrayList<List<TagCell>>()
                rows(d, p, r)
                if (r.any { row -> row.any { it.mcids.isNotEmpty() } }) out += r
                return
            }
            kids(d).forEach { walk(it, p, depth + 1) }
        }
        walk(root, null, 0)
        return out
    }

    /** True when the tree has a Table element somewhere (cheap check before reading text). */
    fun hasTables(root: COSDictionary, roles: Map<String, String> = emptyMap()): Boolean {
        val seen = IdentityHashMap<COSDictionary, Boolean>()
        fun any(n: COSBase?, depth: Int): Boolean {
            val d = n as? COSDictionary ?: return false
            if (depth > 200 || seen.put(d, true) != null) return false
            if (type(d, roles) == "Table") return true
            return kids(d).any { any(it, depth + 1) }
        }
        return any(root, 0)
    }

    private class Run(val text: String, val l: Float, val r: Float, val top: Float, val bottom: Float)

    /** Text and box of a marked content (PDF user space, y up: [top] is the crop box top, [x0] its left edge). */
    private fun runs(mc: PDMarkedContent, top: Float, x0: Float, sb: StringBuilder, box: FloatArray, last: FloatArray) {
        mc.contents.forEach { c ->
            when (c) {
                is TextPosition -> {
                    val x = x0 + c.xDirAdj; val w = c.widthDirAdj; val base = top - c.yDirAdj; val h = c.heightDir
                    // generated space when the glyphs leave a gap (many tagged files have no space glyphs)
                    if (sb.isNotEmpty() && !sb.endsWith(" ") && (x - last[0] > c.fontSizeInPt * 0.2f || kotlin.math.abs(base - last[1]) > h)) sb.append(' ')
                    sb.append(c.unicode); last[0] = x + w; last[1] = base
                    box[0] = minOf(box[0], x); box[1] = maxOf(box[1], x + w); box[2] = maxOf(box[2], base + h); box[3] = minOf(box[3], base - h * 0.25f)
                }
                is PDMarkedContent -> runs(c, top, x0, sb, box, last)
            }
        }
    }

    /** Tagged tables of [pageIndex] as reflow tables (no line indexes: the caller drops the lines inside the box). */
    fun load(doc: PDDocument, pageIndex: Int): List<RTable> {
        val cat = doc.documentCatalog.cosObject
        val root = deref(cat.getItem(COSName.getPDFName("StructTreeRoot"))) as? COSDictionary ?: return emptyList()
        val roles = (deref(root.getItem(COSName.getPDFName("RoleMap"))) as? COSDictionary)?.let { m ->
            m.keySet().mapNotNull { k -> (deref(m.getItem(k)) as? COSName)?.let { k.name to it.name } }.toMap()
        } ?: emptyMap()
        if (!hasTables(root, roles)) return emptyList()
        val page = doc.getPage(pageIndex)
        val tables = tables(root, page.cosObject, roles)
        if (tables.isEmpty()) return emptyList()
        val crop = page.cropBox
        val byId = HashMap<Int, Run>()
        val ex = PDFMarkedContentExtractor()
        ex.processPage(page)
        fun collect(mc: PDMarkedContent) {
            if (mc.mcid >= 0 && mc.mcid !in byId) {
                val sb = StringBuilder(); val box = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE)
                runs(mc, crop.upperRightY, crop.lowerLeftX, sb, box, floatArrayOf(-Float.MAX_VALUE, 0f))
                byId[mc.mcid] = Run(sb.toString().replace(Regex("\\s+"), " ").trim(), box[0], box[1], box[2], box[3])
            }
            mc.contents.forEach { if (it is PDMarkedContent) collect(it) }
        }
        ex.markedContents.forEach { collect(it) }
        return tables.mapNotNull { rows ->
            val used = rows.flatten().flatMap { it.mcids }.mapNotNull { byId[it] }.filter { it.l < it.r }
            if (used.isEmpty()) return@mapNotNull null
            val width = rows.maxOf { it.size }
            val text = rows.map { r -> List(width) { c -> r.getOrNull(c)?.mcids?.mapNotNull { byId[it]?.text }?.filter { it.isNotEmpty() }?.joinToString(" ") ?: "" } }
            RTable(used.maxOf { it.top }, used.minOf { it.bottom }, used.minOf { it.l }, used.maxOf { it.r }, text,
                rows.firstOrNull()?.any { it.header } == true, emptySet())
        }
    }
}
