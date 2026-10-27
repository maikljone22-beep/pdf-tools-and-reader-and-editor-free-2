package com.smartdocs.editor.core.edit5

import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A text range of a page that should become a link to [target] (zero-based page). */
data class TextHit(val range: IntRange, val target: Int)

/**
 * Automatic links made from the page text (character boxes from PDFium):
 * - Auto Page Links (Nitro): page numbers at the end of table-of-contents lines → link to that page;
 * - cross-reference links (Foxit): "see page 12", "p. 12", "صفحة 12" → link to that page;
 * - Auto Keyword Links (Nitro): every occurrence of a phrase → link to a web address, page or destination.
 * The text rules are pure; [apply] turns hits into real links.
 */
object AutoLinks {
    private fun latin(s: String) = buildString { for (c in s) append(if (c in '٠'..'٩') '0' + (c - '٠') else if (c in '۰'..'۹') '0' + (c - '۰') else c) }

    /** Lines of [text] with their start offsets. */
    private fun lines(text: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var start = 0
        for (i in text.indices) if (text[i] == '\n' || text[i] == '\r') { out += start to text.substring(start, i); start = i + 1 }
        if (start < text.length) out += start to text.substring(start)
        return out
    }

    private val TOC_LINE = Regex("""^(.*\p{L}.*?)[\s.·…_\-]{1,}([\d٠-٩۰-۹]{1,5})\s*$""")
    private val TOC_LINE_RTL = Regex("""^\s*([\d٠-٩۰-۹]{1,5})[\s.·…_\-]{1,}(.*\p{L}.*)$""")

    /**
     * Table-of-contents page numbers: a line with words followed by a number (dot leaders allowed), or, in RTL
     * extraction order, the number first. Target = printed number − 1 + [offset] (printed page 1 = physical page 1
     * + offset). [wholeLine] links the whole line instead of only the number.
     */
    fun tocNumbers(text: String, pageCount: Int, offset: Int = 0, wholeLine: Boolean = false): List<TextHit> {
        val out = ArrayList<TextHit>()
        for ((start, line) in lines(text)) {
            val m = TOC_LINE.find(line)
            val (numRange, n) = if (m != null) m.groups[2]!!.range to latin(m.groupValues[2]).toInt()
            else TOC_LINE_RTL.find(line)?.let { r -> r.groups[1]!!.range to latin(r.groupValues[1]).toInt() } ?: continue
            val target = n - 1 + offset
            if (target !in 0 until pageCount) continue
            val r = if (wholeLine) line.indexOfFirst { !it.isWhitespace() }..line.indexOfLast { !it.isWhitespace() } else numRange
            out += TextHit((start + r.first)..(start + r.last), target)
        }
        return out
    }

    private val XREF = Regex("""(?i)\b(?:see\s+)?(?:page|pages|pg\.?|p\.)\s*([\d٠-٩]{1,5})\b|(?:انظر\s+)?(?:الصفحة|صفحة|ص\s*\.?)\s*([\d٠-٩]{1,5})""")

    /** "see page 12", "p. 12", "pg 4", "انظر الصفحة 12", "ص 5" → page 12 / 4 / 5 (+ [offset]). The whole phrase is linked. */
    fun crossRefs(text: String, pageCount: Int, offset: Int = 0): List<TextHit> = XREF.findAll(text).mapNotNull { m ->
        val num = (m.groups[1] ?: m.groups[2])?.value ?: return@mapNotNull null
        val target = latin(num).toInt() - 1 + offset
        if (target !in 0 until pageCount) null else TextHit(m.range, target)
    }.toList()

    /** Every occurrence of [phrase] in [text] ([wholeWord]: not inside a longer word). target = -1 (the caller sets it). */
    fun keyword(text: String, phrase: String, matchCase: Boolean, wholeWord: Boolean): List<IntRange> {
        val p = phrase.trim()
        if (p.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var i = text.indexOf(p, 0, !matchCase)
        while (i >= 0) {
            val end = i + p.length
            val okL = !wholeWord || i == 0 || !text[i - 1].isLetterOrDigit()
            val okR = !wholeWord || end >= text.length || !text[end].isLetterOrDigit()
            if (okL && okR) out += i until end
            i = text.indexOf(p, end, !matchCase)
        }
        return out
    }

    /** Link specs of [ranges] on one page, from its character [boxes] (one quad per text line). */
    fun specs(ranges: List<IntRange>, boxes: List<RectF>, make: (Int, RectF, List<RectF>) -> LinkSpec): List<LinkSpec> = ranges.mapIndexedNotNull { k, r ->
        val rs = r.mapNotNull { boxes.getOrNull(it) }.filter { it.width() != 0f }
        val lines = LinkOps.linesOf(rs)
        val u = E5Geom.union(lines) ?: return@mapIndexedNotNull null
        make(k, u, lines)
    }

    /** Page links for [hits] (go to page, view [fit]). */
    fun pageLinks(hits: List<TextHit>, boxes: List<RectF>, base: LinkSpec): List<LinkSpec> =
        specs(hits.map { it.range }, boxes) { k, u, lines -> base.copy(rect = u, quads = lines, kind = LinkKind.PAGE, page = hits[k].target) }

    // ---------------- cross-reference groups (Foxit Cross Reference panel) ----------------

    /** Key marking a link as one stop of a cross-reference group (custom entry, ignored by other viewers). */
    val XREF_KEY: COSName = COSName.getPDFName("SDXRef")

    /** One stop of a cross-reference group: a rectangle on a page. */
    data class XStop(val page: Int, val rect: RectF, val quads: List<RectF> = emptyList())
    /** A group as found in the file: its name and its links (page, annotation index, target page). */
    data class XGroup(val name: String, val links: List<Triple<Int, Int, Int>>)

    /**
     * Links of a cross-reference group: each stop links to the next one (the last back to the first), so tapping
     * them cycles through the linked places. The view of each target is the top of the next stop.
     */
    fun groupLinks(name: String, stops: List<XStop>, base: LinkSpec): Map<Int, List<LinkSpec>> {
        if (stops.size < 2) return emptyMap()
        val out = LinkedHashMap<Int, MutableList<LinkSpec>>()
        stops.forEachIndexed { i, s ->
            val next = stops[(i + 1) % stops.size]
            val top = maxOf(next.rect.top, next.rect.bottom)
            out.getOrPut(s.page) { ArrayList() } += base.copy(rect = s.rect, quads = s.quads, kind = LinkKind.PAGE, page = next.page,
                fit = DestFit.XYZ, top = top + 12f, contents = base.contents.ifBlank { name })
        }
        return out
    }

    /** Add the links of group [name] (made by [groupLinks]). */
    suspend fun addGroup(src: File, out: File, password: String?, name: String, links: Map<Int, List<LinkSpec>>) =
        LinkOps.add(src, out, password, links.mapValues { (_, l) -> l.map { it.copy(group = name) } })

    /** The cross-reference groups of the document. */
    suspend fun groups(src: File, password: String?): List<XGroup> = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            val m = LinkedHashMap<String, MutableList<Triple<Int, Int, Int>>>()
            val idx = doc.pages.withIndex().associate { it.value.cosObject to it.index }
            doc.pages.forEachIndexed { p, pg ->
                runCatching { pg.annotations }.getOrNull()?.forEachIndexed { i, a ->
                    val n = (a.cosObject.getDictionaryObject(XREF_KEY) as? COSString)?.string ?: return@forEachIndexed
                    val dest = a.cosObject.getDictionaryObject(COSName.DEST) as? com.tom_roush.pdfbox.cos.COSArray
                    val target = (dest?.getObject(0) as? com.tom_roush.pdfbox.cos.COSDictionary)?.let { idx[it] } ?: -1
                    m.getOrPut(n) { ArrayList() } += Triple(p, i, target)
                }
            }
            m.map { XGroup(it.key, it.value) }
        }
    }

    /** Remove group [name] (all its links). */
    suspend fun removeGroup(src: File, out: File, password: String?, name: String) = withContext(Dispatchers.IO) {
        NavIo.edit(src, out, password) { doc, changed ->
            for (pg in doc.pages) {
                val annots = runCatching { pg.annotations }.getOrNull() ?: continue
                val keep = annots.filter { (it.cosObject.getDictionaryObject(XREF_KEY) as? COSString)?.string != name }
                if (keep.size != annots.size) { pg.annotations = keep.toMutableList(); changed += pg }
            }
        }
    }

    /** Rename group [from] to [to]. */
    suspend fun renameGroup(src: File, out: File, password: String?, from: String, to: String) = withContext(Dispatchers.IO) {
        NavIo.edit(src, out, password) { doc, changed ->
            for (pg in doc.pages) runCatching { pg.annotations }.getOrNull()?.forEach { a ->
                if ((a.cosObject.getDictionaryObject(XREF_KEY) as? COSString)?.string == from) { a.cosObject.setItem(XREF_KEY, COSString(to)); changed += pg }
            }
        }
    }
}
