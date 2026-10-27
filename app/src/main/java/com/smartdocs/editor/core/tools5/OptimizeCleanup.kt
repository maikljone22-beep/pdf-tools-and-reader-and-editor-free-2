package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.redact.SanitizeItem
import com.smartdocs.editor.core.redact.Sanitizer
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode

/**
 * Foxit "Discard objects / Discard user data / Clean up" panels of the optimizer. Every switch is off by default
 * except where noted in the UI. All operations work on the loaded document before it is saved.
 */
data class CleanupOptions(
    val formActions: Boolean = false,
    val alternateImages: Boolean = false,
    val tags: Boolean = false,
    val printSettings: Boolean = false,
    val searchIndex: Boolean = false,
    val multimedia: Boolean = false,
    val externalLinks: Boolean = false,
    val objectData: Boolean = false,
    val flattenLayers: Boolean = false,
    val lzwToFlate: Boolean = false,
    val invalidLinks: Boolean = false,
    val unusedDests: Boolean = false,
    val optimizeContent: Boolean = false,
    val linesToCurves: Boolean = false,
)

object OptimizeCleanup {
    private fun n(s: String) = COSName.getPDFName(s)
    private val FORM_ACTIONS = setOf("SubmitForm", "ResetForm", "ImportData")
    private val EXTERNAL_ACTIONS = setOf("GoToR", "GoToE", "Launch", "URI")
    private val MEDIA_ANNOTS = setOf("Screen", "Movie", "Sound", "RichMedia", "3D")
    private val MEDIA_ACTIONS = setOf("Rendition", "Movie", "Sound", "RichMediaExecute", "GoTo3DView")

    /** Runs the chosen cleanups. Returns the number of things changed per option name (for the result line). */
    fun run(doc: PDDocument, o: CleanupOptions): Map<String, Int> {
        val r = LinkedHashMap<String, Int>()
        fun put(k: String, v: Int) { if (v > 0) r[k] = v }
        if (o.formActions) put("formActions", removeActions(doc) { it in FORM_ACTIONS })
        if (o.multimedia) {
            var c = removeAnnots(doc) { it in MEDIA_ANNOTS }
            c += removeActions(doc) { it in MEDIA_ACTIONS }
            put("multimedia", c)
        }
        if (o.externalLinks) {
            // the link annotations stay for internal targets; only external actions go
            var c = removeActions(doc) { it in EXTERNAL_ACTIONS }
            // links left without an action or destination are dead: remove them
            c += removeLinkAnnots(doc) { a -> !a.containsKey(COSName.A) && !a.containsKey(COSName.DEST) }
            put("externalLinks", c)
        }
        if (o.alternateImages) put("alternateImages", alternates(doc))
        if (o.tags) put("tags", Sanitizer.clean(doc, setOf(SanitizeItem.STRUCTURE))[SanitizeItem.STRUCTURE] ?: 0)
        if (o.objectData) put("objectData", objectData(doc))
        if (o.searchIndex) put("searchIndex", Sanitizer.clean(doc, setOf(SanitizeItem.SEARCH_INDEX))[SanitizeItem.SEARCH_INDEX] ?: 0)
        if (o.printSettings) put("printSettings", printSettings(doc))
        if (o.flattenLayers) put("flattenLayers", flattenLayers(doc))
        if (o.invalidLinks) put("invalidLinks", invalidLinks(doc))
        if (o.unusedDests) put("unusedDests", unusedDests(doc))
        if (o.lzwToFlate) put("lzwToFlate", lzwToFlate(doc))
        if (o.linesToCurves) put("linesToCurves", doc.pages.sumOf { p -> runCatching { ContentTidy.rewrite(doc, p, curves = true) }.getOrDefault(0) })
        if (o.optimizeContent) put("optimizeContent", doc.pages.count { p -> runCatching { ContentTidy.rewrite(doc, p, curves = false) >= 0 }.getOrDefault(false) })
        return r
    }

    // ---------- actions ----------

    /** Removes actions whose /S matches [match] from annotations, fields, pages, outline items and the catalog (incl. /Next chains). */
    fun removeActions(doc: PDDocument, match: (String) -> Boolean): Int {
        var n = 0
        fun clean(holder: COSDictionary, key: COSName) {
            val a = holder.getDictionaryObject(key) as? COSDictionary ?: return
            if (a.getCOSName(COSName.S)?.name?.let(match) == true) { holder.removeItem(key); n++; return }
            when (val next = a.getDictionaryObject(COSName.NEXT)) {
                is COSDictionary -> if (next.getCOSName(COSName.S)?.name?.let(match) == true) { a.removeItem(COSName.NEXT); n++ } else clean(a, COSName.NEXT)
                is COSArray -> {
                    var i = 0
                    while (i < next.size()) { val x = next.getObject(i) as? COSDictionary; if (x?.getCOSName(COSName.S)?.name?.let(match) == true) { next.remove(i); n++ } else i++ }
                }
                else -> {}
            }
        }
        fun cleanAA(holder: COSDictionary) {
            val aa = holder.getDictionaryObject(COSName.AA) as? COSDictionary ?: return
            aa.keySet().toList().forEach { clean(aa, it) }
            if (aa.size() == 0) holder.removeItem(COSName.AA)
        }
        val cat = doc.documentCatalog.cosObject
        clean(cat, COSName.OPEN_ACTION); cleanAA(cat)
        doc.pages.forEach { p ->
            cleanAA(p.cosObject)
            runCatching { p.annotations }.getOrNull()?.forEach { a -> clean(a.cosObject, COSName.A); cleanAA(a.cosObject) }
        }
        runCatching { doc.documentCatalog.acroForm?.fieldTree?.forEach { f -> cleanAA(f.cosObject) } }
        doc.documentCatalog.documentOutline?.let { walkOutline(it) { item -> clean(item.cosObject, COSName.A) } }
        return n
    }

    private fun walkOutline(node: PDOutlineNode, depth: Int = 0, f: (PDOutlineItem) -> Unit) {
        if (depth > 40) return
        var it = node.firstChild; var guard = 0
        while (it != null && guard++ < 20_000) { val next = it.nextSibling; f(it); walkOutline(it, depth + 1, f); it = next }
    }

    private fun removeAnnots(doc: PDDocument, subtype: (String) -> Boolean): Int {
        var n = 0
        doc.pages.forEach { p ->
            val list = runCatching { p.annotations }.getOrNull() ?: return@forEach
            val keep = list.filterNot { subtype(it.subtype ?: "") }
            if (keep.size != list.size) { n += list.size - keep.size; p.annotations = keep }
        }
        return n
    }

    private fun removeLinkAnnots(doc: PDDocument, dead: (COSDictionary) -> Boolean): Int {
        var n = 0
        doc.pages.forEach { p ->
            val list = runCatching { p.annotations }.getOrNull() ?: return@forEach
            val keep = list.filterNot { it.subtype == "Link" && dead(it.cosObject) }
            if (keep.size != list.size) { n += list.size - keep.size; p.annotations = keep }
        }
        return n
    }

    // ---------- objects ----------

    private fun allStreams(doc: PDDocument): List<COSStream> = doc.document.objects.mapNotNull { it.`object` as? COSStream }

    /** Discard alternate images (/Alternates of image XObjects). */
    fun alternates(doc: PDDocument): Int = allStreams(doc).count { s ->
        s.getCOSName(COSName.SUBTYPE)?.name == "Image" && s.containsKey(n("Alternates")).also { if (it) s.removeItem(n("Alternates")) }
    }

    /** Discard object data of the tag tree (attribute objects /A, classes /C and the /ClassMap). */
    fun objectData(doc: PDDocument): Int {
        val root = doc.documentCatalog.cosObject.getDictionaryObject(n("StructTreeRoot")) as? COSDictionary ?: return 0
        var c = 0
        if (root.containsKey(n("ClassMap"))) { root.removeItem(n("ClassMap")); c++ }
        val seen = HashSet<COSDictionary>()
        fun walk(d: COSDictionary, depth: Int) {
            if (depth > 80 || !seen.add(d)) return
            if (d.containsKey(COSName.S)) {
                if (d.containsKey(COSName.A)) { d.removeItem(COSName.A); c++ }
                if (d.containsKey(COSName.C)) { d.removeItem(COSName.C); c++ }
            }
            when (val k = d.getDictionaryObject(COSName.K)) {
                is COSDictionary -> walk(k, depth + 1)
                is COSArray -> for (i in 0 until k.size()) (k.getObject(i) as? COSDictionary)?.let { walk(it, depth + 1) }
                else -> {}
            }
        }
        walk(root, 0)
        return c
    }

    /** Discard embedded print settings (viewer preferences that drive printing). */
    fun printSettings(doc: PDDocument): Int {
        val vp = doc.documentCatalog.cosObject.getDictionaryObject(COSName.VIEWER_PREFERENCES) as? COSDictionary ?: return 0
        var c = 0
        listOf("PrintScaling", "Duplex", "PickTrayByPDFSize", "PrintPageRange", "NumCopies", "PrintArea", "PrintClip").forEach { k ->
            if (vp.containsKey(n(k))) { vp.removeItem(n(k)); c++ }
        }
        return c
    }

    /** Discard hidden layer content, then flatten the visible layers into the page (no optional content left). */
    fun flattenLayers(doc: PDDocument): Int {
        val cat = doc.documentCatalog
        if (cat.ocProperties == null) return 0
        val removed = Sanitizer.clean(doc, setOf(SanitizeItem.HIDDEN_LAYERS))[SanitizeItem.HIDDEN_LAYERS] ?: 0
        val groups = runCatching { cat.ocProperties!!.optionalContentGroups.count() }.getOrDefault(0)
        cat.cosObject.removeItem(COSName.OCPROPERTIES)
        doc.pages.forEach { p ->
            runCatching { p.annotations }.getOrNull()?.forEach { it.cosObject.removeItem(n("OC")) }
            val xo = p.resources?.cosObject?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
            xo?.keySet()?.forEach { k -> (xo.getDictionaryObject(k) as? COSDictionary)?.removeItem(n("OC")) }
        }
        return removed + groups
    }

    /** Remove bookmarks and links that point to pages that do not exist (or to unknown names). */
    fun invalidLinks(doc: PDDocument): Int {
        val pages = HashSet<COSBase>(); doc.pages.forEach { pages += it.cosObject }
        val cat = doc.documentCatalog
        fun valid(dest: COSBase?): Boolean {
            val d = if (dest is com.tom_roush.pdfbox.cos.COSObject) dest.`object` else dest
            return when (d) {
                null -> true
                is COSArray -> { val first = d.getObject(0); first is COSNumber || first in pages }
                is COSString, is COSName -> runCatching {
                    val named = if (d is COSString) PDNamedDestination(d) else PDNamedDestination(d as COSName)
                    cat.findNamedDestinationPage(named) != null
                }.getOrDefault(false)
                is COSDictionary -> valid(d.getDictionaryObject(COSName.D))
                else -> true
            }
        }
        fun actionValid(a: COSDictionary?): Boolean = a == null || a.getCOSName(COSName.S)?.name != "GoTo" || valid(a.getDictionaryObject(COSName.D))
        var c = 0
        doc.pages.forEach { p ->
            val list = runCatching { p.annotations }.getOrNull() ?: return@forEach
            val keep = list.filterNot { a -> a.subtype == "Link" && (!valid(a.cosObject.getDictionaryObject(COSName.DEST)) || !actionValid(a.cosObject.getDictionaryObject(COSName.A) as? COSDictionary)) }
            if (keep.size != list.size) { c += list.size - keep.size; p.annotations = keep }
        }
        // bookmarks: an invalid item is removed with its children moved up would change the outline; remove its target instead
        cat.documentOutline?.let { o ->
            walkOutline(o) { item ->
                val d = item.cosObject
                if (!valid(d.getDictionaryObject(COSName.DEST))) { d.removeItem(COSName.DEST); c++ }
                if (!actionValid(d.getDictionaryObject(COSName.A) as? COSDictionary)) { d.removeItem(COSName.A); c++ }
            }
            c += pruneDeadLeaves(o)
        }
        return c
    }

    /** Removes outline leaves that point nowhere (no /Dest, no /A, no children). */
    private fun pruneDeadLeaves(root: PDOutlineNode): Int {
        var c = 0
        val dead = ArrayList<PDOutlineItem>()
        walkOutline(root) { item -> if (!item.hasChildren() && !item.cosObject.containsKey(COSName.DEST) && !item.cosObject.containsKey(COSName.A)) dead += item }
        dead.forEach { if (unlink(it)) c++ }
        return c
    }

    /** Takes [item] out of its parent's sibling chain (PdfBox 2 has no outline delete). */
    private fun unlink(item: PDOutlineItem): Boolean {
        val d = item.cosObject
        val parent = d.getDictionaryObject(COSName.PARENT) as? COSDictionary ?: return false
        val prev = d.getDictionaryObject(COSName.PREV) as? COSDictionary
        val next = d.getDictionaryObject(COSName.NEXT) as? COSDictionary
        if (prev != null) { if (next != null) prev.setItem(COSName.NEXT, next) else prev.removeItem(COSName.NEXT) } else if (next != null) parent.setItem(COSName.FIRST, next) else parent.removeItem(COSName.FIRST)
        if (next != null) { if (prev != null) next.setItem(COSName.PREV, prev) else next.removeItem(COSName.PREV) } else if (prev != null) parent.setItem(COSName.LAST, prev) else parent.removeItem(COSName.LAST)
        val count = parent.getInt(COSName.COUNT, 0)
        if (count > 0) parent.setInt(COSName.COUNT, count - 1) else if (count < 0) parent.setInt(COSName.COUNT, count + 1)
        if (parent.getDictionaryObject(COSName.FIRST) == null) parent.removeItem(COSName.COUNT)
        return true
    }

    /** Discard named destinations nobody refers to (links, actions, bookmarks, OpenAction). */
    fun unusedDests(doc: PDDocument): Int {
        val used = HashSet<String>()
        fun note(d: COSBase?) {
            when (val x = if (d is com.tom_roush.pdfbox.cos.COSObject) d.`object` else d) {
                is COSString -> used += x.string
                is COSName -> used += x.name
                else -> {}
            }
        }
        fun noteAction(a: COSDictionary?, depth: Int = 0) {
            if (a == null || depth > 20) return
            note(a.getDictionaryObject(COSName.D))
            when (val nx = a.getDictionaryObject(COSName.NEXT)) {
                is COSDictionary -> noteAction(nx, depth + 1)
                is COSArray -> for (i in 0 until nx.size()) noteAction(nx.getObject(i) as? COSDictionary, depth + 1)
                else -> {}
            }
        }
        val cat = doc.documentCatalog.cosObject
        noteAction(cat.getDictionaryObject(COSName.OPEN_ACTION) as? COSDictionary); note(cat.getDictionaryObject(COSName.OPEN_ACTION))
        doc.pages.forEach { p -> runCatching { p.annotations }.getOrNull()?.forEach { a ->
            note(a.cosObject.getDictionaryObject(COSName.DEST)); noteAction(a.cosObject.getDictionaryObject(COSName.A) as? COSDictionary)
            (a.cosObject.getDictionaryObject(COSName.AA) as? COSDictionary)?.values?.forEach { v -> noteAction((if (v is com.tom_roush.pdfbox.cos.COSObject) v.`object` else v) as? COSDictionary) }
        } }
        doc.documentCatalog.documentOutline?.let { o -> walkOutline(o) { item -> note(item.cosObject.getDictionaryObject(COSName.DEST)); noteAction(item.cosObject.getDictionaryObject(COSName.A) as? COSDictionary) } }
        var c = 0
        (cat.getDictionaryObject(COSName.DESTS) as? COSDictionary)?.let { d -> d.keySet().toList().forEach { k -> if (k.name !in used) { d.removeItem(k); c++ } } }
        val tree = (cat.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.getDictionaryObject(COSName.DESTS) as? COSDictionary
        if (tree != null) c += NameTrees.retain(tree) { it in used }
        return c
    }

    /** Replace LZW with Flate. */
    fun lzwToFlate(doc: PDDocument): Int {
        var c = 0
        allStreams(doc).forEach { s ->
            val f = s.getDictionaryObject(COSName.FILTER)
            val lzw = f == COSName.LZW_DECODE || (f is COSArray && (0 until f.size()).any { f.getObject(it) == COSName.LZW_DECODE })
            if (!lzw) return@forEach
            // only when LZW is the whole chain (images with DCT after LZW keep their own encoding)
            if (f is COSArray && f.size() > 1) return@forEach
            runCatching {
                val data = s.createInputStream().use { it.readBytes() }
                s.removeItem(COSName.DECODE_PARMS)
                s.createOutputStream(COSName.FLATE_DECODE).use { it.write(data) }
                c++
            }
        }
        return c
    }
}

/** Name-tree editing: keep only the entries whose name passes [keep] (flat rewrite of the leaves; limits refreshed). */
object NameTrees {
    fun retain(node: COSDictionary, depth: Int = 0, keep: (String) -> Boolean): Int {
        if (depth > 20) return 0
        var c = 0
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
            var i = 0
            while (i + 1 < arr.size()) { val name = (arr.getObject(i) as? COSString)?.string; if (name != null && !keep(name)) { arr.remove(i + 1); arr.remove(i); c++ } else i += 2 }
            if (arr.size() >= 2) node.setItem(COSName.LIMITS, COSArray().apply { add(arr.get(0)); add(arr.get(arr.size() - 2)) })
        }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids -> for (k in 0 until kids.size()) (kids.getObject(k) as? COSDictionary)?.let { c += retain(it, depth + 1, keep) } }
        return c
    }
}

/**
 * Content stream rewriting: "Optimize page content" (one compact, Flate-compressed stream without comments or
 * extra white space) and "Convert smooth lines to curves" (runs of short `l` segments that follow a smooth
 * course become `c` Bézier curves within half a unit of the original points).
 */
object ContentTidy {
    /** Rewrites [page]; returns the number of line segments replaced (0 when only tidied). */
    fun rewrite(doc: PDDocument, page: PDPage, curves: Boolean): Int {
        val parser = PDFStreamParser(page); parser.parse()
        val tokens = parser.tokens
        var replaced = 0
        val out = if (curves) LineFit.toCurves(tokens).also { replaced = it.second }.first else tokens
        val s = PDStream(doc)
        s.createOutputStream(COSName.FLATE_DECODE).use { os -> ContentStreamWriter(os).writeTokens(out) }
        page.setContents(s)
        return replaced
    }
}

/** Fits smooth runs of line segments with cubic Béziers (Catmull-Rom through the kept points). Pure, unit-tested. */
object LineFit {
    /** Perpendicular distance of ([px], [py]) from the segment a–b. */
    private fun dist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return Math.hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Douglas-Peucker simplification: indexes of the points to keep. */
    fun simplify(pts: List<Pair<Double, Double>>, tol: Double): List<Int> {
        if (pts.size < 3) return pts.indices.toList()
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack += 0 to pts.size - 1
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var best = -1; var bd = 0.0
            for (i in a + 1 until b) { val d = dist(pts[i].first, pts[i].second, pts[a].first, pts[a].second, pts[b].first, pts[b].second); if (d > bd) { bd = d; best = i } }
            if (best >= 0 && bd > tol) { keep[best] = true; stack += a to best; stack += best to b }
        }
        return pts.indices.filter { keep[it] }
    }

    /** True when consecutive segments turn by less than [maxTurnDeg] everywhere (a smooth course, not a polygon). */
    fun smooth(pts: List<Pair<Double, Double>>, maxTurnDeg: Double = 35.0): Boolean {
        for (i in 1 until pts.size - 1) {
            val a1 = Math.atan2(pts[i].second - pts[i - 1].second, pts[i].first - pts[i - 1].first)
            val a2 = Math.atan2(pts[i + 1].second - pts[i].second, pts[i + 1].first - pts[i].first)
            var d = Math.toDegrees(a2 - a1); while (d > 180) d -= 360; while (d < -180) d += 360
            if (kotlin.math.abs(d) > maxTurnDeg) return false
        }
        return true
    }

    /** Bézier control points (c1, c2, end) of Catmull-Rom segments through [p]. */
    fun catmullRom(p: List<Pair<Double, Double>>): List<Triple<Pair<Double, Double>, Pair<Double, Double>, Pair<Double, Double>>> =
        (0 until p.size - 1).map { i ->
            val p0 = p[maxOf(0, i - 1)]; val p1 = p[i]; val p2 = p[i + 1]; val p3 = p[minOf(p.size - 1, i + 2)]
            Triple((p1.first + (p2.first - p0.first) / 6) to (p1.second + (p2.second - p0.second) / 6),
                (p2.first - (p3.first - p1.first) / 6) to (p2.second - (p3.second - p1.second) / 6), p2)
        }

    private fun num(v: Double): COSBase = if (v == kotlin.math.round(v)) COSInteger.get(v.toLong()) else COSFloat(((v * 1000).toLong() / 1000.0).toFloat())

    /**
     * Replaces runs of at least 6 `x y l` operators following a point (`m`, `l` or `c` end) by curves, when the run is
     * smooth and the fitted curve keeps to the original points within [tol]. Returns the new tokens and the number
     * of `l` operators replaced.
     */
    fun toCurves(tokens: List<Any>, tol: Double = 0.5, minRun: Int = 6): Pair<List<Any>, Int> {
        val out = ArrayList<Any>(tokens.size)
        var replaced = 0
        var i = 0
        fun isOp(t: Any?, name: String) = t is Operator && t.name == name
        fun numAt(k: Int) = (tokens.getOrNull(k) as? COSNumber)?.doubleValue()
        while (i < tokens.size) {
            // a run starts at "x y l" with a known current point before it in [out]
            if (isOp(tokens.getOrNull(i + 2), "l") && numAt(i) != null && numAt(i + 1) != null && out.size >= 3 &&
                (isOp(out.last(), "m") || isOp(out.last(), "l")) && out[out.size - 3] is COSNumber && out[out.size - 2] is COSNumber) {
                val start = (out[out.size - 3] as COSNumber).doubleValue() to (out[out.size - 2] as COSNumber).doubleValue()
                val pts = arrayListOf(start)
                var j = i
                while (isOp(tokens.getOrNull(j + 2), "l") && numAt(j) != null && numAt(j + 1) != null) { pts += numAt(j)!! to numAt(j + 1)!!; j += 3 }
                val segs = pts.size - 1
                if (segs >= minRun && smooth(pts)) {
                    val keep = simplify(pts, tol).map { pts[it] }
                    if (keep.size < pts.size && within(pts, keep, tol)) {
                        catmullRom(keep).forEach { (c1, c2, e) ->
                            out += num(c1.first); out += num(c1.second); out += num(c2.first); out += num(c2.second); out += num(e.first); out += num(e.second)
                            out += Operator.getOperator("c")
                        }
                        replaced += segs
                        i = j
                        continue
                    }
                }
                // not smooth: copy the run as it is
                while (i < j) { out += tokens[i]; i++ }
                continue
            }
            out += tokens[i]; i++
        }
        return out to replaced
    }

    /** Every original point lies within [tol] of the curve through [keep] (sampled). */
    private fun within(pts: List<Pair<Double, Double>>, keep: List<Pair<Double, Double>>, tol: Double): Boolean {
        val samples = ArrayList<Pair<Double, Double>>()
        var prev = keep.first(); samples += prev
        catmullRom(keep).forEach { (c1, c2, e) ->
            for (s in 1..16) {
                val t = s / 16.0; val u = 1 - t
                samples += (u * u * u * prev.first + 3 * u * u * t * c1.first + 3 * u * t * t * c2.first + t * t * t * e.first) to
                    (u * u * u * prev.second + 3 * u * u * t * c1.second + 3 * u * t * t * c2.second + t * t * t * e.second)
            }
            prev = e
        }
        return pts.all { p -> (0 until samples.size - 1).any { k -> dist(p.first, p.second, samples[k].first, samples[k].second, samples[k + 1].first, samples[k + 1].second) <= tol * 1.5 } }
    }
}
