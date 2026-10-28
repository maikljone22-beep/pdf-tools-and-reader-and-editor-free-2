package com.smartdocs.editor.core.tools5

import android.graphics.RectF
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.PageFrame
import com.smartdocs.editor.core.edit5.PageSet
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.security.MessageDigest

/** What a compare change is about (Foxit "filter by text / images / annotations / formatting"; Nitro markers). */
enum class ChangeKind { TEXT, IMAGE, ANNOTATION, FORMAT, PAGE, TABLE }

/** One marked box on a page (display space, points, top-left origin). [note] says what changed. */
class Mark(val box: RectF, val type: ChangeType, val kind: ChangeKind, val note: String = "")

/** Old page ↔ new page (zero-based). A null side is a deleted (new null) or inserted (old null) page. */
data class PagePair(val old: Int?, val new: Int?, val similarity: Float = 0f)

enum class PageChangeType { INSERTED, DELETED, MOVED, ROTATED, CROPPED }

/** A page-level change; [detail] is e.g. "90° → 0°" or the crop box sizes. */
data class PageChange(val type: PageChangeType, val old: Int?, val new: Int?, val detail: String = "")

/** An image or annotation inserted, deleted or replaced (changed). */
class ObjChange(val kind: ChangeKind, val type: ChangeType, val oldPage: Int?, val newPage: Int?, val oldBox: RectF?, val newBox: RectF?, val label: String)

/** The same word ([text]) set in another font or size. Indexes into [CompareResult.oldWords] / [CompareResult.newWords]. */
data class FormatChange(val oldWord: Int, val newWord: Int, val oldFont: String, val newFont: String, val oldSize: Float, val newSize: Float, val text: String = "")

/** A table cell (or a whole row when [col] < 0) that changed. Rows and columns are zero-based in that page's table. */
data class TableChange(val oldPage: Int?, val newPage: Int?, val row: Int, val col: Int, val type: ChangeType, val old: String, val new: String)

/**
 * What to compare: page ranges of each document ("1-3, 7"; blank = all), which change kinds, and [autoMatch]
 * (pair pages by content, so reordered, inserted and deleted pages are found instead of shifting everything).
 */
data class CompareOptions(
    val oldRange: String = "", val newRange: String = "",
    val text: Boolean = true, val images: Boolean = true, val annotations: Boolean = true, val formatting: Boolean = true,
    val tables: Boolean = false, val pages: Boolean = true, val autoMatch: Boolean = true,
)

/** Page matching by content: global alignment on word-set similarity, then moved pages. Pure, unit-tested. */
object PageMatch {
    const val MIN_SIMILARITY = 0.3f

    fun similarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val inter = a.count { it in b }
        return inter.toFloat() / (a.size + b.size - inter)
    }

    /**
     * Pairs pages of [old] and [new] (their word sets, in page order; [oldIds]/[newIds] the page numbers). Order-keeping
     * pairs come from a Needleman-Wunsch alignment (score = similarity, gaps cost 0, pairs below [MIN_SIMILARITY]
     * are not allowed); pages left over that still match each other are paired as moved. Returned in new-page order,
     * deleted pages placed before the next kept page.
     */
    fun align(old: List<Set<String>>, new: List<Set<String>>, oldIds: List<Int> = old.indices.toList(), newIds: List<Int> = new.indices.toList()): List<PagePair> {
        val n = old.size; val m = new.size
        val sim = Array(n) { i -> FloatArray(m) { j -> similarity(old[i], new[j]) } }
        val dp = Array(n + 1) { FloatArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            val diag = if (sim[i - 1][j - 1] >= MIN_SIMILARITY) dp[i - 1][j - 1] + sim[i - 1][j - 1] else -1f
            dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1], diag)
        }
        val matchOf = IntArray(n) { -1 }
        var i = n; var j = m
        while (i > 0 && j > 0) {
            val s = sim[i - 1][j - 1]
            when {
                s >= MIN_SIMILARITY && dp[i][j] == dp[i - 1][j - 1] + s -> { matchOf[i - 1] = j - 1; i--; j-- }
                dp[i][j] == dp[i - 1][j] -> i--
                else -> j--
            }
        }
        // moved pages: best remaining matches out of order
        val usedNew = matchOf.filter { it >= 0 }.toMutableSet()
        val moved = HashSet<Int>()
        val cands = ArrayList<Triple<Int, Int, Float>>()
        for (a in 0 until n) if (matchOf[a] < 0) for (b in 0 until m) if (b !in usedNew && sim[a][b] >= 0.5f) cands += Triple(a, b, sim[a][b])
        cands.sortedByDescending { it.third }.forEach { (a, b, _) -> if (matchOf[a] < 0 && b !in usedNew) { matchOf[a] = b; usedNew += b; moved += a } }
        val out = ArrayList<PagePair>()
        val oldForNew = HashMap<Int, Int>(); matchOf.forEachIndexed { a, b -> if (b >= 0) oldForNew[b] = a }
        var nextOld = 0
        for (b in 0 until m) {
            val a = oldForNew[b]
            if (a != null && a !in moved) {
                // deleted old pages that came before this kept one
                while (nextOld < a) { if (matchOf[nextOld] < 0) out += PagePair(oldIds[nextOld], null); nextOld++ }
                nextOld = maxOf(nextOld, a + 1)
            }
            out += PagePair(a?.let { oldIds[it] }, newIds[b], a?.let { sim[it][b] } ?: 0f)
        }
        while (nextOld < n) { if (matchOf[nextOld] < 0) out += PagePair(oldIds[nextOld], null); nextOld++ }
        return out
    }

    /** Pairs page by page in order (no matching): extra pages are inserted / deleted. */
    fun inOrder(oldIds: List<Int>, newIds: List<Int>): List<PagePair> =
        (0 until maxOf(oldIds.size, newIds.size)).map { k -> PagePair(oldIds.getOrNull(k), newIds.getOrNull(k), 1f) }

    /** Matched pairs whose old pages are out of order (not on a longest increasing run): the moved pages. */
    fun movedPairs(pairs: List<PagePair>): Set<PagePair> {
        val matched = pairs.filter { it.old != null && it.new != null }
        val olds = matched.map { it.old!! }
        // longest increasing subsequence (patience, O(n log n) not needed for page counts)
        val len = IntArray(olds.size) { 1 }; val prev = IntArray(olds.size) { -1 }
        for (x in olds.indices) for (y in 0 until x) if (olds[y] < olds[x] && len[y] + 1 > len[x]) { len[x] = len[y] + 1; prev[x] = y }
        val keep = HashSet<Int>()
        var k = len.indices.maxByOrNull { len[it] } ?: -1
        while (k >= 0) { keep += k; k = prev[k] }
        return matched.filterIndexed { idx, _ -> idx !in keep }.toSet()
    }
}

/** Table rows from word positions: words on one line, cut into cells at wide gaps. Pure, unit-tested. */
object TableDetect {
    /** A word reduced to what the detector needs: text and box (x left/right, y centre, height). */
    data class W(val text: String, val l: Float, val r: Float, val cy: Float, val h: Float, val index: Int)

    /** A row of cells; each cell is the list of word indexes in it. */
    data class Row(val cells: List<List<Int>>, val texts: List<String>)

    /** Rows with at least [minCells] cells. Cells split where the gap between words exceeds [gapFactor] × line height. */
    fun rows(words: List<W>, minCells: Int = 2, gapFactor: Float = 1.2f): List<Row> {
        val lines = ArrayList<MutableList<W>>()
        for (w in words.sortedWith(compareBy({ it.cy }, { it.l }))) {
            val line = lines.lastOrNull()
            if (line != null && kotlin.math.abs(line.first().cy - w.cy) <= maxOf(line.first().h, w.h) * 0.5f) line += w else lines += mutableListOf(w)
        }
        return lines.mapNotNull { ln ->
            val sorted = ln.sortedBy { it.l }
            val h = sorted.map { it.h }.average().toFloat().coerceAtLeast(1f)
            val cells = ArrayList<MutableList<W>>()
            sorted.forEach { w -> val c = cells.lastOrNull(); if (c != null && w.l - c.last().r <= h * gapFactor) c += w else cells += mutableListOf(w) }
            if (cells.size < minCells) null else Row(cells.map { c -> c.map { it.index } }, cells.map { c -> c.joinToString(" ") { it.text } })
        }
    }

    /** Changed rows and cells between two tables (rows aligned with the word diff on their joined text). */
    fun diff(old: List<Row>, new: List<Row>, oldPage: Int?, newPage: Int?): List<TableChange> {
        val ch = WordDiff.diffKeys(old.map { r -> r.texts.joinToString("|") { WordDiff.key(it) } }, new.map { r -> r.texts.joinToString("|") { WordDiff.key(it) } })
        val out = ArrayList<TableChange>()
        ch.forEach { c ->
            when (c.type) {
                ChangeType.EQUAL -> {}
                ChangeType.INSERT -> c.b.forEach { out += TableChange(oldPage, newPage, it, -1, ChangeType.INSERT, "", new[it].texts.joinToString(" | ")) }
                ChangeType.DELETE -> c.a.forEach { out += TableChange(oldPage, newPage, it, -1, ChangeType.DELETE, old[it].texts.joinToString(" | "), "") }
                ChangeType.REPLACE -> {
                    val pairs = minOf(c.a.count(), c.b.count())
                    for (k in 0 until pairs) {
                        val ro = old[c.a.first + k]; val rn = new[c.b.first + k]
                        for (col in 0 until maxOf(ro.texts.size, rn.texts.size)) {
                            val a = ro.texts.getOrNull(col).orEmpty(); val b = rn.texts.getOrNull(col).orEmpty()
                            if (WordDiff.key(a) != WordDiff.key(b)) out += TableChange(oldPage, newPage, c.b.first + k, col,
                                if (a.isEmpty()) ChangeType.INSERT else if (b.isEmpty()) ChangeType.DELETE else ChangeType.REPLACE, a, b)
                        }
                    }
                    c.a.drop(pairs).forEach { out += TableChange(oldPage, newPage, it, -1, ChangeType.DELETE, old[it].texts.joinToString(" | "), "") }
                    c.b.drop(pairs).forEach { out += TableChange(oldPage, newPage, it, -1, ChangeType.INSERT, "", new[it].texts.joinToString(" | ")) }
                }
            }
        }
        return out
    }
}

/** Runs a compare on two loaded documents. */
internal object CompareCore {

    private class Side(val doc: PDDocument, val words: List<PWord>, val pages: List<Int>) {
        val byPage: Map<Int, List<Int>> = words.indices.groupBy { words[it].page }
        fun sig(p: Int): Set<String> = byPage[p].orEmpty().map { WordDiff.key(words[it].text) }.filter { it.isNotEmpty() }.toSet()
    }

    private class Marks { val m = HashMap<Int, MutableList<Mark>>(); fun add(p: Int, mark: Mark) { m.getOrPut(p) { ArrayList() } += mark } }

    fun run(od: PDDocument, nd: PDDocument, o: CompareOptions): CompareResult {
        val os = Side(od, WordExtractor.extract(od), PageSet.parse(o.oldRange, od.numberOfPages))
        val ns = Side(nd, WordExtractor.extract(nd), PageSet.parse(o.newRange, nd.numberOfPages))
        val pairs = if (o.autoMatch) PageMatch.align(os.pages.map { os.sig(it) }, ns.pages.map { ns.sig(it) }, os.pages, ns.pages)
            else PageMatch.inOrder(os.pages, ns.pages)
        // word sequences in pair order, so a moved page lines up with itself
        val oldSeq = pairs.mapNotNull { it.old }.flatMap { os.byPage[it].orEmpty() }
        val newSeq = pairs.mapNotNull { it.new }.flatMap { ns.byPage[it].orEmpty() }
        val ow = oldSeq.map { os.words[it] }; val nw = newSeq.map { ns.words[it] }
        // audit 5 C-12: diffed per page pair (same concatenated order as oldSeq / newSeq), so a long document never
        // hits the whole-document edit limit and reports "everything changed"
        val changes = WordDiff.diffPaged(
            pairs.map { p -> p.old?.let { os.byPage[it].orEmpty().map { i -> os.words[i].text } }.orEmpty() },
            pairs.map { p -> p.new?.let { ns.byPage[it].orEmpty().map { i -> ns.words[i].text } }.orEmpty() })
        val om = Marks(); val nm = Marks()
        if (o.text) changes.forEach { c ->
            if (c.type == ChangeType.EQUAL) return@forEach
            c.a.forEach { i -> ow[i].let { w -> om.add(w.page, Mark(w.box, c.type, ChangeKind.TEXT, w.text)) } }
            c.b.forEach { i -> nw[i].let { w -> nm.add(w.page, Mark(w.box, c.type, ChangeKind.TEXT, w.text)) } }
            if (c.a.isEmpty()) CompareEngine.neighbour(ow, c.a.first)?.let { w -> om.add(w.page, Mark(RectF(w.box.right, w.box.top, w.box.right + 2f, w.box.bottom), ChangeType.INSERT, ChangeKind.TEXT)) }
            if (c.b.isEmpty()) CompareEngine.neighbour(nw, c.b.first)?.let { w -> nm.add(w.page, Mark(RectF(w.box.right, w.box.top, w.box.right + 2f, w.box.bottom), ChangeType.DELETE, ChangeKind.TEXT,
                c.a.joinToString(" ") { ow[it].text })) }
        }
        val formats = ArrayList<FormatChange>()
        if (o.formatting) changes.filter { it.type == ChangeType.EQUAL }.forEach { c ->
            c.a.forEachIndexed { k, i ->
                val a = ow[i]; val b = nw[c.b.first + k]
                val fontChanged = a.font.isNotEmpty() && b.font.isNotEmpty() && baseFont(a.font) != baseFont(b.font)
                val sizeChanged = a.size > 0f && b.size > 0f && kotlin.math.abs(a.size - b.size) > 0.6f
                if (fontChanged || sizeChanged) {
                    formats += FormatChange(i, c.b.first + k, baseFont(a.font), baseFont(b.font), a.size, b.size, b.text)
                    val note = "${baseFont(a.font)} ${fmt(a.size)} → ${baseFont(b.font)} ${fmt(b.size)}"
                    om.add(a.page, Mark(a.box, ChangeType.REPLACE, ChangeKind.FORMAT, note)); nm.add(b.page, Mark(b.box, ChangeType.REPLACE, ChangeKind.FORMAT, note))
                }
            }
        }
        val pageChanges = ArrayList<PageChange>()
        val objects = ArrayList<ObjChange>()
        val tables = ArrayList<TableChange>()
        val moved = PageMatch.movedPairs(pairs)
        for (p in pairs) {
            val op = p.old?.let { od.getPage(it) }; val np = p.new?.let { nd.getPage(it) }
            if (o.pages) {
                when {
                    op == null && np != null -> { pageChanges += PageChange(PageChangeType.INSERTED, null, p.new); nm.add(p.new!!, Mark(pageBox(np), ChangeType.INSERT, ChangeKind.PAGE)) }
                    np == null && op != null -> { pageChanges += PageChange(PageChangeType.DELETED, p.old, null); om.add(p.old!!, Mark(pageBox(op), ChangeType.DELETE, ChangeKind.PAGE)) }
                    op != null && np != null -> {
                        if (p in moved) pageChanges += PageChange(PageChangeType.MOVED, p.old, p.new, "${p.old!! + 1} → ${p.new!! + 1}")
                        val ro = norm(op.rotation); val rn = norm(np.rotation)
                        if (ro != rn) pageChanges += PageChange(PageChangeType.ROTATED, p.old, p.new, "$ro° → $rn°")
                        val co = op.cropBox; val cn = np.cropBox
                        if (kotlin.math.abs(co.width - cn.width) > 1f || kotlin.math.abs(co.height - cn.height) > 1f || kotlin.math.abs(co.lowerLeftX - cn.lowerLeftX) > 1f || kotlin.math.abs(co.lowerLeftY - cn.lowerLeftY) > 1f)
                            pageChanges += PageChange(PageChangeType.CROPPED, p.old, p.new, "${fmt(co.width)}×${fmt(co.height)} @${fmt(co.lowerLeftX)},${fmt(co.lowerLeftY)} → ${fmt(cn.width)}×${fmt(cn.height)} @${fmt(cn.lowerLeftX)},${fmt(cn.lowerLeftY)}")
                    }
                }
            }
            if (op == null || np == null) continue
            if (o.images) objects += images(op, p.old!!, np, p.new!!, om, nm)
            if (o.annotations) objects += annotations(op, p.old!!, np, p.new!!, om, nm)
            if (o.tables) {
                val to = TableDetect.rows(tableWords(os, p.old!!)); val tn = TableDetect.rows(tableWords(ns, p.new!!))
                if (to.isNotEmpty() || tn.isNotEmpty()) TableDetect.diff(to, tn, p.old, p.new).forEach { t ->
                    tables += t
                    if (t.type != ChangeType.DELETE) tn.getOrNull(t.row)?.let { row -> cellBox(ns, row, t.col)?.let { nm.add(p.new!!, Mark(it, t.type, ChangeKind.TABLE, "${t.old} → ${t.new}")) } }
                }
            }
        }
        return CompareResult(ow, nw, changes, WordDiff.stats(changes), SideMarks(om.m), SideMarks(nm.m), od.numberOfPages, nd.numberOfPages,
            pairs, pageChanges, objects, formats, tables)
    }

    private fun norm(r: Int) = ((r % 360) + 360) % 360
    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(java.util.Locale.US, v)
    internal fun baseFont(name: String) = if (name.length > 7 && name[6] == '+') name.substring(7) else name

    private fun pageBox(p: PDPage): RectF { val f = PageFrame.of(p); return RectF(0f, 0f, f.width, f.height) }

    /** A user-space box as a display box (points, top-left origin), like the word boxes. */
    private fun display(p: PDPage, l: Float, b: Float, r: Float, t: Float): RectF {
        val f = PageFrame.of(p)
        val pts = listOf(l to b, r to b, l to t, r to t).map { (x, y) -> Affine.apply(f.toDisplay, x, y) }
        return RectF(pts.minOf { it.first }, f.height - pts.maxOf { it.second }, pts.maxOf { it.first }, f.height - pts.minOf { it.second })
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private class Img(val hash: String, val box: RectF)

    private fun pageImages(p: PDPage): List<Img> {
        val insp = runCatching { com.smartdocs.editor.core.redact.PageInspector(p, null).run(p) }.getOrNull() ?: return emptyList()
        return insp.paints.filter { it.kind == com.smartdocs.editor.core.redact.PaintKind.IMAGE || it.kind == com.smartdocs.editor.core.redact.PaintKind.INLINE_IMAGE }.map { pr ->
            val img = pr.image
            val hash = if (img != null) runCatching { md5(img.cosObject.createRawInputStream().use { it.readBytes() }) + "_${img.width}x${img.height}" }.getOrDefault("?") else "inline"
            Img(hash, display(p, pr.box.l, pr.box.b, pr.box.r, pr.box.t))
        }
    }

    private fun near(a: RectF, b: RectF, tol: Float = 3f) = kotlin.math.abs(a.left - b.left) <= tol && kotlin.math.abs(a.top - b.top) <= tol &&
        kotlin.math.abs(a.width() - b.width()) <= tol && kotlin.math.abs(a.height() - b.height()) <= tol

    private fun overlaps(a: RectF, b: RectF) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun images(op: PDPage, oi: Int, np: PDPage, ni: Int, om: Marks, nm: Marks): List<ObjChange> {
        val a = pageImages(op).toMutableList(); val b = pageImages(np).toMutableList()
        // the same picture in the same place: unchanged
        a.toList().forEach { x -> b.firstOrNull { y -> y.hash == x.hash && near(x.box, y.box) }?.let { y -> a -= x; b -= y } }
        val out = ArrayList<ObjChange>()
        // the same place, another picture (or the same picture moved / resized): replaced
        a.toList().forEach { x ->
            val y = b.firstOrNull { it.hash == x.hash } ?: b.firstOrNull { overlaps(it.box, x.box) } ?: return@forEach
            a -= x; b -= y
            out += ObjChange(ChangeKind.IMAGE, ChangeType.REPLACE, oi, ni, x.box, y.box, if (x.hash == y.hash) "moved" else "replaced")
            om.add(oi, Mark(x.box, ChangeType.REPLACE, ChangeKind.IMAGE)); nm.add(ni, Mark(y.box, ChangeType.REPLACE, ChangeKind.IMAGE))
        }
        a.forEach { x -> out += ObjChange(ChangeKind.IMAGE, ChangeType.DELETE, oi, ni, x.box, null, "deleted"); om.add(oi, Mark(x.box, ChangeType.DELETE, ChangeKind.IMAGE)) }
        b.forEach { y -> out += ObjChange(ChangeKind.IMAGE, ChangeType.INSERT, oi, ni, null, y.box, "inserted"); nm.add(ni, Mark(y.box, ChangeType.INSERT, ChangeKind.IMAGE)) }
        return out
    }

    private class An(val subtype: String, val text: String, val box: RectF)

    private fun pageAnnots(p: PDPage): List<An> = runCatching { p.annotations }.getOrNull().orEmpty()
        .filter { it.subtype != "Popup" && it.subtype != "Link" && it.subtype != "Widget" }
        .mapNotNull { a -> val r = a.rectangle ?: return@mapNotNull null; An(a.subtype ?: "?", (a.contents ?: "").trim(), display(p, r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)) }

    private fun annotations(op: PDPage, oi: Int, np: PDPage, ni: Int, om: Marks, nm: Marks): List<ObjChange> {
        val a = pageAnnots(op).toMutableList(); val b = pageAnnots(np).toMutableList()
        a.toList().forEach { x -> b.firstOrNull { y -> y.subtype == x.subtype && y.text == x.text && near(x.box, y.box, 4f) }?.let { y -> a -= x; b -= y } }
        val out = ArrayList<ObjChange>()
        a.toList().forEach { x ->
            val y = b.firstOrNull { it.subtype == x.subtype && (near(it.box, x.box, 4f) || (it.text == x.text && it.text.isNotEmpty())) } ?: return@forEach
            a -= x; b -= y
            out += ObjChange(ChangeKind.ANNOTATION, ChangeType.REPLACE, oi, ni, x.box, y.box, "${x.subtype}: ${x.text.take(60)} → ${y.text.take(60)}")
            om.add(oi, Mark(x.box, ChangeType.REPLACE, ChangeKind.ANNOTATION, x.text)); nm.add(ni, Mark(y.box, ChangeType.REPLACE, ChangeKind.ANNOTATION, y.text))
        }
        a.forEach { x -> out += ObjChange(ChangeKind.ANNOTATION, ChangeType.DELETE, oi, ni, x.box, null, "${x.subtype}: ${x.text.take(80)}"); om.add(oi, Mark(x.box, ChangeType.DELETE, ChangeKind.ANNOTATION, x.text)) }
        b.forEach { y -> out += ObjChange(ChangeKind.ANNOTATION, ChangeType.INSERT, oi, ni, null, y.box, "${y.subtype}: ${y.text.take(80)}"); nm.add(ni, Mark(y.box, ChangeType.INSERT, ChangeKind.ANNOTATION, y.text)) }
        return out
    }

    private fun tableWords(s: Side, page: Int): List<TableDetect.W> = s.byPage[page].orEmpty().map { i ->
        val w = s.words[i]; TableDetect.W(w.text, w.box.left, w.box.right, w.box.centerY(), w.box.height(), i)
    }

    private fun cellBox(s: Side, row: TableDetect.Row, col: Int): RectF? {
        val idx = if (col < 0) row.cells.flatten() else row.cells.getOrNull(col) ?: return null
        val boxes = idx.map { s.words[it].box }
        if (boxes.isEmpty()) return null
        return RectF(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }
}
