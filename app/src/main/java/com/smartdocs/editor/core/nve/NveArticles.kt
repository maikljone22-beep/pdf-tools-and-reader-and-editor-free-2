package com.smartdocs.editor.core.nve

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * PDF article threads (ISO 32000 12.4.3): catalog /Threads = array of thread dictionaries (/Type /Thread, /I info,
 * /F first bead); beads (/Type /Bead, /T thread, /N next, /V previous, /P page, /R rect) form a circular list; each
 * page lists its beads in /B. Pure PDFBox (unit tested). Rects are [llx, lly, urx, ury] in the page's user space.
 */
object NveArticles {
    private val THREADS = COSName.getPDFName("Threads")
    private val THREAD = COSName.getPDFName("Thread")
    private val BEAD = COSName.getPDFName("Bead")
    private val I = COSName.getPDFName("I")
    private val B = COSName.getPDFName("B")
    private val R = COSName.getPDFName("R")
    private val V = COSName.getPDFName("V")

    data class Bead(val page: Int, val rect: FloatArray) {
        override fun equals(other: Any?) = other is Bead && other.page == page && other.rect.contentEquals(rect)
        override fun hashCode() = page * 31 + rect.contentHashCode()
    }

    data class Info(val title: String = "", val subject: String = "", val author: String = "", val keywords: String = "")

    data class Article(val index: Int, val info: Info, val beads: List<Bead>)

    private fun deref(b: COSBase?): COSBase? = (b as? COSObject)?.`object` ?: b

    private fun threads(doc: PDDocument, create: Boolean): COSArray? {
        val cat = doc.documentCatalog.cosObject
        (cat.getDictionaryObject(THREADS) as? COSArray)?.let { return it }
        if (!create) return null
        return COSArray().also { cat.setItem(THREADS, it) }
    }

    private fun readInfo(t: COSDictionary): Info {
        val i = t.getDictionaryObject(I) as? COSDictionary ?: return Info()
        return Info(i.getString(COSName.TITLE).orEmpty(), i.getString(COSName.SUBJECT).orEmpty(), i.getString(COSName.AUTHOR).orEmpty(), i.getString(COSName.KEYWORDS).orEmpty())
    }

    private fun writeInfo(t: COSDictionary, info: Info) {
        val d = COSDictionary()
        if (info.title.isNotBlank()) d.setItem(COSName.TITLE, COSString(info.title))
        if (info.subject.isNotBlank()) d.setItem(COSName.SUBJECT, COSString(info.subject))
        if (info.author.isNotBlank()) d.setItem(COSName.AUTHOR, COSString(info.author))
        if (info.keywords.isNotBlank()) d.setItem(COSName.KEYWORDS, COSString(info.keywords))
        t.setItem(I, d)
    }

    private fun beadDicts(t: COSDictionary): List<COSDictionary> {
        val first = t.getDictionaryObject(COSName.F) as? COSDictionary ?: return emptyList()
        val out = ArrayList<COSDictionary>()
        var b: COSDictionary? = first
        var guard = 0
        while (b != null && guard++ < 10_000) {
            out += b
            val n = b.getDictionaryObject(COSName.N) as? COSDictionary
            if (n == null || n === first) break
            b = n
        }
        return out
    }

    private fun pageIndex(doc: PDDocument, p: COSDictionary?): Int {
        p ?: return -1
        doc.pages.forEachIndexed { i, pg -> if (pg.cosObject === p) return i }
        return -1
    }

    private fun rectOf(d: COSDictionary): FloatArray {
        val a = d.getDictionaryObject(R) as? COSArray ?: return floatArrayOf(0f, 0f, 0f, 0f)
        val v = (0 until minOf(4, a.size())).map { (a.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
        return if (v.size == 4) floatArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3])) else floatArrayOf(0f, 0f, 0f, 0f)
    }

    /** Every article of [doc] in /Threads order. */
    fun list(doc: PDDocument): List<Article> {
        val arr = threads(doc, false) ?: return emptyList()
        return (0 until arr.size()).mapNotNull { i ->
            val t = deref(arr.get(i)) as? COSDictionary ?: return@mapNotNull null
            Article(i, readInfo(t), beadDicts(t).map { b -> Bead(pageIndex(doc, b.getDictionaryObject(COSName.P) as? COSDictionary), rectOf(b)) })
        }
    }

    /** Removes the beads of [t] from their pages' /B arrays. */
    private fun unlinkBeads(doc: PDDocument, t: COSDictionary) {
        val old = beadDicts(t).toSet()
        if (old.isEmpty()) return
        for (p in doc.pages) {
            val b = p.cosObject.getDictionaryObject(B) as? COSArray ?: continue
            var i = b.size() - 1
            while (i >= 0) { if (deref(b.get(i)) in old) b.remove(i); i-- }
            if (b.size() == 0) p.cosObject.removeItem(B)
        }
    }

    /** (Re)writes the circular bead list of thread [t] from [beads] (pages must exist). */
    private fun writeBeads(doc: PDDocument, t: COSDictionary, beads: List<Bead>) {
        unlinkBeads(doc, t)
        val valid = beads.filter { it.page in 0 until doc.numberOfPages }
        if (valid.isEmpty()) { t.removeItem(COSName.F); return }
        val dicts = valid.map { bd ->
            COSDictionary().apply {
                setItem(COSName.TYPE, BEAD)
                setItem(COSName.T, t)
                val page = doc.getPage(bd.page)
                setItem(COSName.P, page.cosObject)
                setItem(R, COSArray().apply { bd.rect.forEach { add(COSFloat(it)) } })
                val pb = (page.cosObject.getDictionaryObject(B) as? COSArray) ?: COSArray().also { page.cosObject.setItem(B, it) }
                pb.add(this)
            }
        }
        dicts.forEachIndexed { i, d ->
            d.setItem(COSName.N, dicts[(i + 1) % dicts.size])
            d.setItem(V, dicts[(i - 1 + dicts.size) % dicts.size])
            if (i != 0) d.removeItem(COSName.T)          // only the first bead points at the thread
        }
        t.setItem(COSName.F, dicts.first())
    }

    private fun thread(doc: PDDocument, index: Int): COSDictionary =
        deref(threads(doc, false)?.get(index)) as? COSDictionary ?: throw IndexOutOfBoundsException("article $index")

    /** Adds a new article with [beads] (in reading order); returns its index. */
    fun add(doc: PDDocument, info: Info, beads: List<Bead>): Int {
        val arr = threads(doc, true)!!
        val t = COSDictionary().apply { setItem(COSName.TYPE, THREAD) }
        writeInfo(t, info)
        arr.add(t)
        writeBeads(doc, t, beads)
        return arr.size() - 1
    }

    fun setInfo(doc: PDDocument, index: Int, info: Info) = writeInfo(thread(doc, index), info)

    fun setBeads(doc: PDDocument, index: Int, beads: List<Bead>) {
        val t = thread(doc, index)
        if (beads.isEmpty()) delete(doc, index) else writeBeads(doc, t, beads)
    }

    /** Deletes article [index] and its beads. */
    fun delete(doc: PDDocument, index: Int) {
        val arr = threads(doc, false) ?: return
        val t = deref(arr.get(index)) as? COSDictionary ?: return
        unlinkBeads(doc, t)
        arr.remove(index)
        if (arr.size() == 0) doc.documentCatalog.cosObject.removeItem(THREADS)
    }

    /** Box operations on article [index]. */
    fun insertBead(doc: PDDocument, index: Int, at: Int, bead: Bead) {
        val l = list(doc)[index].beads.toMutableList(); l.add(at.coerceIn(0, l.size), bead); setBeads(doc, index, l)
    }

    fun deleteBead(doc: PDDocument, index: Int, bead: Int) {
        val l = list(doc)[index].beads.toMutableList(); if (bead in l.indices) l.removeAt(bead); setBeads(doc, index, l)
    }

    fun moveBead(doc: PDDocument, index: Int, from: Int, to: Int) {
        val l = list(doc)[index].beads.toMutableList()
        if (from !in l.indices) return
        val b = l.removeAt(from); l.add(to.coerceIn(0, l.size), b); setBeads(doc, index, l)
    }

    /** §22: moves / resizes box [bead] of article [index] to [rect] ([llx, lly, urx, ury] in the page's user space). */
    fun setBeadRect(doc: PDDocument, index: Int, bead: Int, rect: FloatArray) {
        val l = list(doc)[index].beads.toMutableList()
        if (bead !in l.indices) return
        l[bead] = l[bead].copy(rect = BoxDrag.normalized(rect))
        setBeads(doc, index, l)
    }

    /** Renumbers the boxes of an article in reading order: page, then top to bottom, then left/right by [rtl]. */
    fun renumber(doc: PDDocument, index: Int, rtl: Boolean = false) {
        val l = list(doc)[index].beads
        val sorted = l.sortedWith(compareBy<Bead> { it.page }.thenByDescending { it.rect[3] }.thenBy { if (rtl) -it.rect[2] else it.rect[0] })
        setBeads(doc, index, sorted)
    }

    /** Appends the boxes of article [other] to article [into] and deletes [other]. */
    fun combine(doc: PDDocument, into: Int, other: Int) {
        if (into == other) return
        val all = list(doc)
        val merged = all[into].beads + all[other].beads
        setBeads(doc, into, merged)
        delete(doc, other)
    }
}

/**
 * §22: dragging an article box (or another rectangle) in screen space (y down): the whole box ([Handle.MOVE]) or one
 * corner, never smaller than [min] on a side. Pure: unit tested.
 */
object BoxDrag {
    enum class Handle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** [l, t, r, b] (screen, t < b) after a drag by ([dx], [dy]) of [h]. */
    fun apply(box: FloatArray, h: Handle, dx: Float, dy: Float, min: Float): FloatArray {
        var (l, t, r, b) = box.toList()
        when (h) {
            Handle.MOVE -> { l += dx; r += dx; t += dy; b += dy }
            Handle.TOP_LEFT -> { l = minOf(l + dx, r - min); t = minOf(t + dy, b - min) }
            Handle.TOP_RIGHT -> { r = maxOf(r + dx, l + min); t = minOf(t + dy, b - min) }
            Handle.BOTTOM_LEFT -> { l = minOf(l + dx, r - min); b = maxOf(b + dy, t + min) }
            Handle.BOTTOM_RIGHT -> { r = maxOf(r + dx, l + min); b = maxOf(b + dy, t + min) }
        }
        return floatArrayOf(l, t, r, b)
    }

    /** The handle under ([x], [y]) for the box [l, t, r, b]: a corner within [grab], else MOVE inside, else null. */
    fun hit(box: FloatArray, x: Float, y: Float, grab: Float): Handle? {
        val (l, t, r, b) = box.toList()
        fun near(cx: Float, cy: Float) = kotlin.math.hypot(x - cx, y - cy) <= grab
        return when {
            near(l, t) -> Handle.TOP_LEFT
            near(r, t) -> Handle.TOP_RIGHT
            near(l, b) -> Handle.BOTTOM_LEFT
            near(r, b) -> Handle.BOTTOM_RIGHT
            x in l..r && y in t..b -> Handle.MOVE
            else -> null
        }
    }

    /** [llx, lly, urx, ury] with the lower-left corner first. */
    fun normalized(r: FloatArray): FloatArray = floatArrayOf(minOf(r[0], r[2]), minOf(r[1], r[3]), maxOf(r[0], r[2]), maxOf(r[1], r[3]))
}
